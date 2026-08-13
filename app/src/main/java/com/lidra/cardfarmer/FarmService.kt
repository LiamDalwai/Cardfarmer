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

class Stats(val red: Double, val bright: Double, val sat: Double)

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
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

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

    // ---------------------------------------------------------------- setup

    private fun startForegroundCompat() {
        val id = "farm"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(id, "Farming", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
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
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 200 }

        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, lp)
            overlay = tv
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- pixels

    private fun sample(buf: ByteBuffer, rowStride: Int, pixStride: Int,
                       cx: Int, cy: Int, r: Int): Stats {
        val x0 = maxOf(0, cx - r); val x1 = minOf(screenW - 1, cx + r)
        val y0 = maxOf(0, cy - r); val y1 = minOf(screenH - 1, cy + r)
        if (x1 <= x0 || y1 <= y0) return Stats(0.0, 0.0, 0.0)

        var n = 0; var redHits = 0
        var sumBright = 0L; var sumSat = 0.0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = y * rowStride + x * pixStride
                val r8 = buf.get(p).toInt() and 0xFF
                val g8 = buf.get(p + 1).toInt() and 0xFF
                val b8 = buf.get(p + 2).toInt() and 0xFF
                if (r8 > 100 && r8 - g8 > 45 && r8 - b8 > 40) redHits++
                sumBright += (r8 + g8 + b8)
                val mx = maxOf(r8, maxOf(g8, b8)); val mn = minOf(r8, minOf(g8, b8))
                sumSat += (mx - mn).toDouble() / (mx + 1).toDouble()
                n++
                x += 3
            }
            y += 3
        }
        if (n == 0) return Stats(0.0, 0.0, 0.0)
        return Stats(redHits.toDouble() / n, sumBright.toDouble() / (n * 3 * 255), sumSat / n)
    }

    private fun frameId(buf: ByteBuffer, rowStride: Int, pixStride: Int): Long {
        var sum = 0L
        var y = 0
        while (y < screenH) {
            var x = 0
            while (x < screenW) {
                sum += (buf.get(y * rowStride + x * pixStride).toInt() and 0xFF)
                x += 40
            }
            y += 40
        }
        return sum
    }

    // ---------------------------------------------------------------- loop

    private fun loop() {
        var idle = 0
        var last = -1L

        while (running) {
            val image = reader?.acquireLatestImage()
            if (image == null) { SystemClock.sleep(60); continue }

            var acted = false
            var frame = -1L
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val pixStride = plane.pixelStride
                frame = frameId(buf, rowStride, pixStride)

                val sp = sample(buf, rowStride, pixStride, cfg.special.first, cfg.special.second, cfg.specialRadius)
                if (sp.sat >= cfg.specialSat && sp.bright >= cfg.specialBright) {
                    tap(cfg.special.first, cfg.special.second)
                    acted = true
                } else {
                    var bestIdx = -1
                    var bestRed = 0.0
                    for (i in cfg.hand.indices) {
                        val (hx, hy) = cfg.hand[i]
                        val s = sample(buf, rowStride, pixStride, hx, hy, cfg.cardRadius)
                        if (s.red >= cfg.redThreshold && s.bright >= cfg.dimThreshold && s.red > bestRed) {
                            bestRed = s.red; bestIdx = i
                        }
                    }
                    if (bestIdx >= 0) {
                        val (hx, hy) = cfg.hand[bestIdx]
                        GestureService.instance?.swipe(
                            hx.toFloat(), hy.toFloat(),
                            cfg.target.first.toFloat(), cfg.target.second.toFloat(), 380
                        )
                        acted = true
                    } else {
                        tap(cfg.endTurn.first, cfg.endTurn.second)
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
                    tap(s.x, s.y)
                    SystemClock.sleep(s.wait)
                }
                idle = 0
                last = -1L
            }

            SystemClock.sleep(if (acted) cfg.actionDelay else cfg.loopDelay)
        }
    }

    private fun tap(x: Int, y: Int) {
        GestureService.instance?.tap(x.toFloat(), y.toFloat())
    }

    // ---------------------------------------------------------------- teardown

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
