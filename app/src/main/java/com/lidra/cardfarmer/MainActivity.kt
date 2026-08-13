package com.lidra.cardfarmer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("cardfarmer", Context.MODE_PRIVATE) }
    private val REQ_CAPTURE = 7001

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val bg = Color.parseColor("#0E0E10")
        val fg = Color.parseColor("#EDEDED")
        val accent = Color.parseColor("#E5484D")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(pad(20), pad(28), pad(20), pad(20))
        }

        root.addView(TextView(this).apply {
            text = "CARD FARMER"
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })
        root.addView(TextView(this).apply {
            text = "Red cards only. Special on sight. Skip everything else."
            setTextColor(Color.parseColor("#8A8A8E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, pad(4), 0, pad(18))
        })

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#8A8A8E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, pad(14))
        }
        root.addView(status)

        root.addView(button("1. Turn on the tap permission", accent) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Find Card Farmer in the list and switch it on")
        })

        root.addView(button("2. Allow drawing over other apps", accent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        })

        root.addView(TextView(this).apply {
            text = "Coordinates (same format as the Termux config)"
            setTextColor(Color.parseColor("#8A8A8E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, pad(16), 0, pad(6))
        })

        editor = EditText(this).apply {
            setTextColor(fg)
            setBackgroundColor(Color.parseColor("#18181B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(pad(12), pad(12), pad(12), pad(12))
            gravity = Gravity.TOP
            setText(prefs.getString("json", null) ?: Cfg.default(screenW(), screenH()))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, pad(260))
        }
        root.addView(editor)

        root.addView(button("Reset to my screen size", Color.parseColor("#2E2E33")) {
            editor.setText(Cfg.default(screenW(), screenH()))
        })

        root.addView(button("START FARMING", accent) { start() })

        root.addView(button("STOP", Color.parseColor("#2E2E33")) {
            stopService(Intent(this, FarmService::class.java))
            FarmService.running = false
            refresh()
        })

        root.addView(TextView(this).apply {
            text = "Start it, then switch to the game. A STOP chip sits on the " +
                   "left edge of the screen, and the notification stops it too."
            setTextColor(Color.parseColor("#6E6E73"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, pad(14), 0, 0)
        })

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val acc = GestureService.instance != null
        val over = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        status.text = "tap permission: " + (if (acc) "on" else "OFF") +
                "   overlay: " + (if (over) "on" else "OFF") +
                "   running: " + (if (FarmService.running) "yes" else "no")
    }

    private fun start() {
        if (GestureService.instance == null) {
            toast("Switch on the tap permission first (step 1)")
            return
        }
        try { Cfg(editor.text.toString()) } catch (e: Exception) {
            toast("Config isn't valid JSON")
            return
        }
        prefs.edit().putString("json", editor.text.toString()).apply()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("fine for this")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_CAPTURE && res == RESULT_OK && data != null) {
            val i = Intent(this, FarmService::class.java)
                .putExtra(FarmService.EXTRA_CODE, res)
                .putExtra(FarmService.EXTRA_DATA, data)
                .putExtra(FarmService.EXTRA_JSON, editor.text.toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            toast("Running. Switch to the game.")
            moveTaskToBack(true)
        }
    }

    // ---- helpers

    private fun button(label: String, colour: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(colour)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad(10) }
        }

    private fun pad(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun metrics(): DisplayMetrics {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return m
    }

    private fun screenW() = metrics().widthPixels
    private fun screenH() = metrics().heightPixels

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
