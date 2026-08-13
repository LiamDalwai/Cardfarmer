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

class Px(val r: Int, val g: Int, val b: Int)
class Card(val cx: Int, val kind: String)

class FarmService : Service() {

    companion object {
        @Volatile var running = false
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"; const val EXTRA_JSON = "json"
        const val ACTION_STOP = "com.lidra.cardfarmer.STOP"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: Thread? = null
    private var overlay: View? = null
    private lateinit var cfg: Cfg
    private var sw = 0; private var sh = 0

    private lateinit var buf: ByteBuffer
    private var rs = 0; private var ps = 0

    // ---- state ----
    private var stalled = 0          // card drags with no screen change
    private var lastEvent = ""       // which map event we walked into
    private var idleCycles = 0       // watchdog counter
    private var lastFrame = -1L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()
        cfg = Cfg(intent.getStringExtra(EXTRA_JSON) ?: "")
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        startCapture(); showOverlay()
        running = true
        worker = Thread { loop() }.also { it.start() }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val id = "farm"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(id, "Farming", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, FarmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, id)
            .setContentTitle("Card Farmer").setContentText("Farming. Tap to stop.")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(stop).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        sw = m.widthPixels; sh = m.heightPixels
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("cardfarmer", sw, sh, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
    }

    private fun showOverlay() {
        val tv = TextView(this).apply {
            text = "  STOP  "; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC1E1E1E")); textSize = 14f
            setPadding(24, 16, 24, 16); setOnClickListener { stopSelf() }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 400 }
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, lp); overlay = tv }
        catch (_: Exception) {}
    }

    // ================================================================ pixels

    private fun at(x: Int, y: Int): Px {
        val xx = x.coerceIn(0, sw - 1); val yy = y.coerceIn(0, sh - 1)
        val p = yy * rs + xx * ps
        return Px(buf.get(p).toInt() and 0xFF, buf.get(p + 1).toInt() and 0xFF, buf.get(p + 2).toInt() and 0xFF)
    }

    private fun isRed(c: Px) = c.r > 125 && c.r - c.g > 45 && c.r - c.b > 45
    private fun isGreen(c: Px) = c.g > 95 && c.g - c.r > 35 && c.g - c.b > 25
    private fun isBlue(c: Px) = c.b > 105 && c.b - c.r > 40 && c.b - c.g > 25
    private fun isOrangeBtn(c: Px) = c.r > 195 && c.g in 105..228 && c.b < 175 && c.r - c.g > 22 && c.r - c.b > 70
    private fun isBlueBtn(c: Px) = c.b > 150 && c.b - c.r > 45 && c.g > 90
    private fun isArrow(c: Px) = c.r in 120..200 && c.g in 45..110 && c.b in 30..95 && c.r - c.g > 45
    private fun isPink(c: Px) = c.r > 175 && c.b > 95 && c.g < 130 && c.r - c.g > 55
    private fun isTeal(c: Px) = c.g > 105 && c.b > 105 && c.r < 110
    private fun isCamp(c: Px) = c.r > 165 && c.g in 85..155 && c.b < 105

    private fun fraction(cx: Int, cy: Int, rad: Int, test: (Px) -> Boolean): Double {
        var n = 0; var hit = 0
        var y = cy - rad
        while (y <= cy + rad) {
            var x = cx - rad
            while (x <= cx + rad) { if (test(at(x, y))) hit++; n++; x += 3 }
            y += 3
        }
        return if (n == 0) 0.0 else hit.toDouble() / n
    }

    private fun frameId(): Long {
        var s = 0L; var y = 0
        while (y < sh) { var x = 0; while (x < sw) { s += at(x, y).r; x += 40 }; y += 40 }
        return s
    }

    /** Majority of the centre band of one row matches. Immune to button text. */
    private fun rowIs(y: Int, test: (Px) -> Boolean): Boolean {
        val x0 = (sw * 0.30).toInt(); val x1 = (sw * 0.70).toInt()
        var n = 0; var hit = 0
        var x = x0
        while (x <= x1) { if (test(at(x, y))) hit++; n++; x += 6 }
        return n > 0 && hit.toDouble() / n > 0.30
    }

    private fun findButtonIn(y0: Double, y1: Double, test: (Px) -> Boolean): Int {
        val minH = cfg.py(cfg.btnMinH)
        var best = -1; var start = -1
        var y = cfg.py(y0); val yEnd = cfg.py(y1)
        while (y <= yEnd) {
            if (rowIs(y, test)) { if (start < 0) start = y }
            else { if (start >= 0 && y - start >= minH) best = start + (y - start) / 2; start = -1 }
            y += 4
        }
        if (start >= 0 && yEnd - start >= minH) best = start + (yEnd - start) / 2
        return best
    }

    // ================================================================ screens

    private fun inBattle(): Boolean =
        fraction(cfg.px(cfg.pauseX), cfg.py(cfg.pauseY), (sw * 0.022).toInt()) { isRed(it) } > 0.35

    private fun handCards(): List<Card> {
        val out = ArrayList<Card>()
        val y = cfg.py(cfg.bannerY)
        val minW = cfg.px(cfg.minCardW)
        var kind = ""; var start = -1
        var x = cfg.px(cfg.scanX0); val xEnd = cfg.px(cfg.scanX1)
        fun close(end: Int) {
            if (kind != "" && start >= 0 && end - start >= minW) out.add(Card(start + (end - start) / 2, kind))
        }
        while (x <= xEnd) {
            val c = at(x, y)
            val k = when { isRed(c) -> "red"; isGreen(c) -> "green"; isBlue(c) -> "blue"; else -> "" }
            if (k != kind) { close(x); kind = k; start = if (k != "") x else -1 }
            x += 5
        }
        close(xEnd)
        return out
    }

    private fun specialReady(): Boolean =
        fraction(cfg.px(cfg.specialX), cfg.py(cfg.specialY), cfg.px(cfg.specialR)) {
            it.r > 200 && it.r - it.b > 120
        } >= cfg.specialFire

    private fun isCardGrid(): Boolean {
        val y = cfg.py(cfg.gridRow2Btn)
        var hits = 0
        for (c in cfg.gridCols) if (fraction(cfg.px(c), y, 30) { isOrangeBtn(it) } > 0.35) hits++
        return hits >= 3
    }

    /** A card detail popup: a big card-coloured block dead centre of the
     *  screen while we're not in battle. Close it via the X, top right. */
    private fun isCardPopup(): Boolean {
        val cx = sw / 2; val cy = (sh * 0.46).toInt()
        val f = fraction(cx, cy, (sw * 0.10).toInt()) { isRed(it) || isGreen(it) || isBlue(it) }
        return f > 0.45
    }

    private fun eventKind(cx: Int): String {
        val y = cfg.py(cfg.eventBannerY); val r = 60
        if (fraction(cx, y, r) { isGreen(it) } > 0.30) return "green"
        if (fraction(cx, y, r) { isPink(it) } > 0.30) return "pink"
        if (fraction(cx, y, r) { isCamp(it) } > 0.30) return "camp"
        if (fraction(cx, y, r) { isTeal(it) } > 0.30) return "workshop"
        return ""
    }

    // ================================================================ actions

    private fun tap(x: Int, y: Int) = GestureService.instance?.tap(x.toFloat(), y.toFloat())

    private fun battleStep(changed: Boolean) {
        // rule: the special fires the moment it is available
        if (specialReady()) {
            tap(cfg.px(cfg.specialX), cfg.py(cfg.specialY))
            SystemClock.sleep(cfg.actionDelay); return
        }

        // rule: red first. If fury mode is on, no red means End Turn,
        // because green/blue reset the special's charge and End Turn doesn't.
        val cards = handCards()
        var chosen: Card? = null
        if (cfg.endTurnNoRed) {
            for (c in cards) if (c.kind == "red") { chosen = c; break }
        } else {
            outer@ for (kind in cfg.priority) for (c in cards) if (c.kind == kind) { chosen = c; break@outer }
        }

        // nothing playable, or dragging isn't changing anything (no energy) -> End Turn
        if (chosen == null || stalled >= 2) {
            tap(cfg.px(cfg.endTurnX), cfg.py(cfg.endTurnY))
            stalled = 0
            SystemClock.sleep(cfg.actionDelay); return
        }

        // rule: press, hold, drag above the midline, release
        GestureService.instance?.drag(
            chosen.cx.toFloat(), cfg.py(cfg.artY).toFloat(),
            cfg.px(cfg.dropX).toFloat(), cfg.py(cfg.dropY).toFloat(), cfg.dragMs
        )
        stalled = if (changed) 0 else stalled + 1
        SystemClock.sleep(cfg.actionDelay)
    }

    private fun gridStep() {
        // rule: workshop upgrades a RED attack card. Everything else,
        // including anything we're unsure about, touches a BLUE defence
        // card only, so an attack card can never be removed by mistake.
        val want = if (lastEvent == "workshop") "red" else "blue"
        val rows = listOf(Pair(cfg.gridRow1Banner, cfg.gridRow1Btn), Pair(cfg.gridRow2Banner, cfg.gridRow2Btn))
        for ((bannerY, btnY) in rows) {
            for (col in cfg.gridCols) {
                val c = at(cfg.px(col), cfg.py(bannerY))
                val match = if (want == "blue") isBlue(c) else isRed(c)
                if (match) {
                    tap(cfg.px(col), cfg.py(btnY))          // Choose
                    SystemClock.sleep(900)
                    tap(cfg.px(cfg.confirmX), cfg.py(cfg.confirmY))  // Upgrade / Confirm
                    SystemClock.sleep(cfg.menuDelay)
                    lastEvent = ""
                    return
                }
            }
        }
        // No card of the wanted colour on screen. On a REMOVE screen we would
        // rather back out than delete the wrong thing, so only the workshop
        // falls back to taking slot one.
        if (lastEvent == "workshop") {
            tap(cfg.px(cfg.gridCols[0]), cfg.py(cfg.gridRow1Btn))
            SystemClock.sleep(900)
            tap(cfg.px(cfg.confirmX), cfg.py(cfg.confirmY))
        } else {
            tap(cfg.px(cfg.fallbackX), cfg.py(cfg.fallbackY))
        }
        SystemClock.sleep(cfg.menuDelay)
        lastEvent = ""
    }

    private fun menuStep() {
        // 1) card grids first. "Choose a Card" has a lone Skip below the
        //    grid -> always Skip. (Checked before the popup detector,
        //    because the centre of these screens is also a card.)
        if (isCardGrid()) {
            val skipY = findButtonIn(cfg.skipY0, cfg.skipY1, ::isOrangeBtn)
            if (skipY > 0) { tap(cfg.px(cfg.btnX), skipY); SystemClock.sleep(cfg.menuDelay) }
            else gridStep()
            return
        }

        val orange = findButtonIn(cfg.btnY0, cfg.btnY1, ::isOrangeBtn)

        // 2) card detail popup: a card fills the centre AND every orange
        //    button is dimmed out behind the overlay. A normal card screen
        //    always has a live orange Skip/Take/Collect, so it never
        //    triggers this.
        if (orange < 0 && isCardPopup()) {
            tap(cfg.px(cfg.popupXx), cfg.py(cfg.popupXy))
            SystemClock.sleep(cfg.menuDelay)
            return
        }

        // 2) map with two choices: green > camp > workshop > pink
        val leftArrow = fraction(cfg.px(cfg.arrowLeftX), cfg.py(cfg.arrowY), 45) { isArrow(it) } > 0.30
        val rightArrow = fraction(cfg.px(cfg.arrowRightX), cfg.py(cfg.arrowY), 45) { isArrow(it) } > 0.30
        if (leftArrow && rightArrow) {
            val l = eventKind(cfg.px(cfg.bannerLeftX))
            val r = eventKind(cfg.px(cfg.bannerRightX))
            var pickLeft = true
            for (p in cfg.eventPriority) {
                if (l == p) { pickLeft = true; break }
                if (r == p) { pickLeft = false; break }
            }
            lastEvent = if (pickLeft) l else r
            tap(cfg.px(if (pickLeft) cfg.arrowLeftX else cfg.arrowRightX), cfg.py(cfg.arrowY))
            SystemClock.sleep(cfg.menuDelay); return
        }

        // 3) map with a single node. Read its banner too, so a lone
        //    Expunger's Lair is still known to be a REMOVE screen.
        if (fraction(cfg.px(cfg.arrowMidX), cfg.py(cfg.arrowMidY), 45) { isArrow(it) } > 0.30) {
            lastEvent = eventKind(cfg.px(0.50))
            tap(cfg.px(cfg.arrowMidX), cfg.py(cfg.arrowMidY))
            SystemClock.sleep(cfg.menuDelay); return
        }

        // 4) orange buttons: Collect / Skip / Play without pack.
        //    Checked before blue so Victory taps Collect, never Upgrade.
        if (orange > 0) { tap(cfg.px(cfg.btnX), orange); SystemClock.sleep(cfg.menuDelay); return }

        // 5) blue buttons: New Adventure / Move On / continue screens
        val blue = findButtonIn(cfg.btnY0, cfg.btnY1, ::isBlueBtn)
        if (blue > 0) { tap(cfg.px(cfg.btnX), blue); SystemClock.sleep(cfg.menuDelay); return }

        // 6) "tap to continue" and anything unrecognised
        tap(cfg.px(cfg.fallbackX), cfg.py(cfg.fallbackY))
        SystemClock.sleep(cfg.menuDelay)
    }

    // ================================================================ loop

    private fun loop() {
        while (running) {
            val image = reader?.acquireLatestImage()
            if (image == null) { SystemClock.sleep(60); continue }
            try {
                val plane = image.planes[0]
                buf = plane.buffer; rs = plane.rowStride; ps = plane.pixelStride
                val f = frameId()
                val changed = f != lastFrame
                lastFrame = f

                // watchdog: if the screen hasn't changed in a long while,
                // whatever we've been doing isn't landing. Nudge the centre.
                idleCycles = if (changed) 0 else idleCycles + 1
                if (idleCycles >= cfg.watchdogAfter) {
                    tap(cfg.px(cfg.fallbackX), cfg.py(cfg.fallbackY))
                    idleCycles = 0
                    SystemClock.sleep(cfg.menuDelay)
                } else if (inBattle()) {
                    battleStep(changed)
                } else {
                    menuStep()
                }
            } catch (_: Exception) {
            } finally { image.close() }
            SystemClock.sleep(cfg.loopDelay)
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

