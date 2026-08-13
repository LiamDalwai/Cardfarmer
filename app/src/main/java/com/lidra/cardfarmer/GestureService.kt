package com.lidra.cardfarmer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Does the actual tapping and dragging. Android only allows this from an
 *  accessibility service, which is why the app asks you to switch it on. */
class GestureService : AccessibilityService() {

    companion object {
        @Volatile var instance: GestureService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(p, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(p, 0, ms)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
