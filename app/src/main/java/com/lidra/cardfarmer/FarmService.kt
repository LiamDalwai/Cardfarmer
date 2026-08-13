package com.lidra.cardfarmer

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.nio.ByteBuffer

class Stats(val bright: Double, val sat: Double)
class Card(val cx: Int, val kind: String, val width: Int)

class FarmService : Service() {

    companion object {
        @Volatile var running = false
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_JSON = "json"
        const val ACTION_STOP = "com.lidra.cardfarmer.STOP"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: Thread? = null
    private var overlay: View? = null
    private lateinit var cfg: Cfg
    private var screenW = 0
    private var screenH = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        startForegroundCompat()
        cfg = Cfg(intent.getStringExtra(EXTRA_JSON) ?: "")
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        startCapture()
        showOverlay()
        running = true
        worker = Thread { loop() }.also { it.start() }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val id = "farm"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(id, "Farming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, id)
            .setContentTitle("Card Farmer")
            .setContentText("Farming. Tap to stop.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "cardfarmer", screenW, screenH, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null
        )
    }

    private fun showOverlay() {
        val tv = TextView(this).apply {
            text = "  STOP  "
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC1E1E1E"))
            textSize = 14f
            setPadding(24, 16, 24, 16)
            setOnClickListener { stopSelf() }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, lp)
            overlay = tv
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------ pixels

    private fun classify(r: Int, g: Int, b: Int): String {
        if (r > 125 && r - g > 45 && r - b > 45) return "red"
        if (g > 95 && g - r > 35 && g - b > 25) return "green"
        if (b > 105 && b - r > 40 && b - g > 25) return "blue"
        return ""
    }

    private fun stats(buf: ByteBuffer, rowStride: Int, pixStride: Int,
                      cx: Int, cy: Int, r: Int): Stats {
        val x0 = maxOf(0, cx - r); val x1 = minOf(screenW - 1, cx + r)
        val y0 = maxOf(0, cy - r); val y1 = minOf(screenH - 1, cy + r)
        if (x1 <= x0 || y1 <= y0) return Stats(0.0, 0.0)
        var n = 0; var sumB = 0L; var sumS = 0.0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = y * rowStride + x * pixStride
                val r8 = buf.get(p).toInt() and 0xFF
                val g8 = buf.get(p + 1).toInt() and 0xFF
                val b8 = buf.get(p + 2).toInt() and 0xFF
                sumB += (r8 + g8 + b8)
                val mx = maxOf(r8, maxOf(g8, b8)); val mn = minOf(r8, minOf(g8, b8))
                sumS += (mx - mn).toDouble() / (mx + 1).toDouble()
                n++; x += 4
            }
            y += 4
        }
        if (n == 0) return Stats(0.0, 0.0)
        return Stats(sumB.toDouble() / (n * 3 * 255), sumS / n)
    }

    /** Walks the banner row and picks out every coloured card, wherever it sits. */
    private fun scanCards(buf: ByteBuffer, rowStride: Int, pixStride: Int): List<Card> {
        val out = ArrayList<Card>()
        var runKind = ""
        var runStart = -1
        var x = cfg.scanXMin
        val y = cfg.scanY.coerceIn(0, screenH - 1)

        fun close(endX: Int) {
            if (runKind != "" && runStart >= 0) {
                val wd = endX - runStart
                if (wd >= cfg.minWidth) out.add(Card(runStart + wd / 2, runKind, wd))
            }
        }

        while (x <= minOf(cfg.scanXMax, screenW - 1)) {
            val p = y * rowStride + x * pixStride
            val k = classify(
                buf.get(p).toInt() and 0xFF,
                buf.get(p + 1).toInt() and 0xFF,
                buf.get(p + 2).toInt() and 0xFF
            )
            if (k != runKind) {
                close(x)
                runKind = k
                runStart = if (k != "") x else -1
            }
            x += 5
        }
        close(minOf(cfg.scanXMax, screenW - 1))
        return out
    }

    private fun frameId(buf: ByteBuffer, rowStride: Int, pixStride: Int): Long {
        var sum = 0L
        var y = 0
        while (y < screenH) {
            var x = 0
            while (x < screenW) { sum += (buf.get(y * rowStride + x * pixStride).toInt() and 0xFF); x += 40 }
            y += 40
        }
        return sum
    }

    // ------------------------------------------------------------ loop

    private fun loop() {
        var idle = 0
        var last = -1L
        var turnStarted = true

        while (running) {
            val image = reader?.acquireLatestImage()
            if (image == null) { SystemClock.sleep(60); continue }

            var acted = false
            var frame = -1L
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val rs = plane.rowStride
                val ps = plane.pixelStride
                frame = frameId(buf, rs, ps)

                val sp = stats(buf, rs, ps, cfg.special.first, cfg.special.second, cfg.specialRadius)
                val specialUp = if (cfg.specialMode == "always_try") turnStarted
                                else (sp.sat >= cfg.specialSat && sp.bright >= cfg.specialBright)

                if (specialUp) {
                    GestureService.instance?.tap(cfg.special.first.toFloat(), cfg.special.second.toFloat())
                    turnStarted = false
                    acted = true
                } else {
                    val cards = scanCards(buf, rs, ps)
                    var chosen: Card? = null

                    outer@ for (kind in cfg.priority) {
                        for (c in cards) {
                            if (c.kind != kind) continue
                            val artY = (cfg.scanY + cfg.artOffset).coerceIn(0, screenH - 1)
                            val s = stats(buf, rs, ps, c.cx, artY, 40)
                            if (s.bright >= cfg.dimThreshold) { chosen = c; break@outer }
                        }
                    }

                    if (chosen != null) {
                        val artY = (cfg.scanY + cfg.artOffset).toFloat()
                        GestureService.instance?.drag(
                            chosen.cx.toFloat(), artY,
                            cfg.target.first.toFloat(), cfg.target.second.toFloat(),
                            cfg.dragMs
                        )
                        acted = true
                    } else {
                        GestureService.instance?.tap(cfg.endTurn.first.toFloat(), cfg.endTurn.second.toFloat())
                        turnStarted = true
                    }
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }

            val frozen = frame == last
            last = frame
            idle = if (acted && !frozen) 0 else idle + 1

            if (idle >= cfg.stuckAfter) {
                for (s in cfg.postBattle) {
                    GestureService.instance?.tap(s.x.toFloat(), s.y.toFloat())
                    SystemClock.sleep(s.wait)
                }
                idle = 0; last = -1L; turnStarted = true
            }

            SystemClock.sleep(if (acted) cfg.actionDelay else cfg.loopDelay)
        }
    }

    override fun onDestroy() {
        running = false
        try { worker?.interrupt() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        overlay?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        super.onDestroy()
    }
}
