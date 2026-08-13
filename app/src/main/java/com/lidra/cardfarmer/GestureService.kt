package com.lidra.cardfarmer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class GestureService : AccessibilityService() {

    companion object {
        @Volatile var instance: GestureService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val s = GestureDescription.StrokeDescription(p, 0, 70)
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }

    /** Press, hold, then drag. Card games ignore quick flicks, so the hold matters. */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val hold = Path().apply { moveTo(x1, y1); lineTo(x1, y1 - 3f) }
        val first = GestureDescription.StrokeDescription(hold, 0, 200, true)

        dispatchGesture(
            GestureDescription.Builder().addStroke(first).build(),
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    val move = Path().apply { moveTo(x1, y1 - 3f); lineTo(x2, y2) }
                    val second = first.continueStroke(move, 0, ms, true)
                    dispatchGesture(
                        GestureDescription.Builder().addStroke(second).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(d2: GestureDescription?) {
                                val settle = Path().apply { moveTo(x2, y2); lineTo(x2, y2 + 2f) }
                                val third = second.continueStroke(settle, 0, 150, false)
                                dispatchGesture(
                                    GestureDescription.Builder().addStroke(third).build(),
                                    null, null
                                )
                            }
                        }, null
                    )
                }
            }, null
        )
    }
}
