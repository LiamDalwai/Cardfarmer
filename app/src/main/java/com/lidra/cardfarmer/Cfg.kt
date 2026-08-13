package com.lidra.cardfarmer

import org.json.JSONObject

class Cfg(json: String) {
    private val o = JSONObject(json)
    private fun d(k: String) = o.getDouble(k)

    val w = o.getJSONArray("screen").getInt(0)
    val h = o.getJSONArray("screen").getInt(1)
    fun px(r: Double) = (w * r).toInt()
    fun py(r: Double) = (h * r).toInt()

    val pauseX = d("pause_x"); val pauseY = d("pause_y")

    val bannerY = d("banner_y")
    val artY = d("card_art_y")
    val scanX0 = d("scan_x_min"); val scanX1 = d("scan_x_max")
    val minCardW = d("min_card_width")
    val priority: List<String> = o.getJSONArray("card_priority").let { a ->
        (0 until a.length()).map { a.getString(it) }
    }
    val dropX = d("drop_x"); val dropY = d("drop_y")
    val dragMs = o.getLong("drag_ms")
    val endTurnX = d("end_turn_x"); val endTurnY = d("end_turn_y")

    val specialX = d("special_x"); val specialY = d("special_y")
    val specialR = d("special_radius")
    val specialFire = d("special_fire_threshold")

    val btnX = d("button_x")
    val btnY0 = d("button_y_min"); val btnY1 = d("button_y_max")
    val btnMinH = d("button_min_height")
    val skipY0 = d("skip_y_min"); val skipY1 = d("skip_y_max")

    val arrowY = d("arrow_y")
    val arrowLeftX = d("arrow_left_x"); val arrowRightX = d("arrow_right_x")
    val arrowMidX = d("arrow_mid_x"); val arrowMidY = d("arrow_mid_y")
    val bannerLeftX = d("event_banner_left_x"); val bannerRightX = d("event_banner_right_x")
    val eventBannerY = d("event_banner_y")
    val eventPriority: List<String> = o.getJSONArray("event_priority").let { a ->
        (0 until a.length()).map { a.getString(it) }
    }

    val gridCols = listOf(d("grid_col1"), d("grid_col2"), d("grid_col3"))
    val gridRow1Banner = d("grid_row1_banner_y"); val gridRow1Btn = d("grid_row1_button_y")
    val gridRow2Banner = d("grid_row2_banner_y"); val gridRow2Btn = d("grid_row2_button_y")
    val confirmX = d("grid_confirm_x"); val confirmY = d("grid_confirm_y")

    val fallbackX = d("fallback_tap_x"); val fallbackY = d("fallback_tap_y")
    val popupXx = d("popup_x_x"); val popupXy = d("popup_x_y")
    val endTurnNoRed = o.getBoolean("end_turn_when_no_red")

    val actionDelay = (d("action_delay") * 1000).toLong()
    val loopDelay = (d("loop_delay") * 1000).toLong()
    val menuDelay = (d("menu_delay") * 1000).toLong()
    val watchdogAfter = o.getInt("watchdog_after")

    companion object {
        fun default(w: Int, h: Int): String = """
{
  "screen": [$w, $h],

  "pause_x": 0.940, "pause_y": 0.079,

  "banner_y": 0.822,
  "card_art_y": 0.762,
  "scan_x_min": 0.03, "scan_x_max": 0.97,
  "min_card_width": 0.085,
  "card_priority": ["red", "green", "blue"],

  "drop_x": 0.50, "drop_y": 0.330,
  "drag_ms": 500,
  "end_turn_x": 0.630, "end_turn_y": 0.953,

  "special_x": 0.884, "special_y": 0.560,
  "special_radius": 0.048,
  "special_fire_threshold": 0.13,

  "button_x": 0.50,
  "button_y_min": 0.62, "button_y_max": 0.975,
  "button_min_height": 0.012,
  "skip_y_min": 0.86, "skip_y_max": 0.965,

  "arrow_y": 0.677,
  "arrow_left_x": 0.25, "arrow_right_x": 0.75,
  "arrow_mid_x": 0.50, "arrow_mid_y": 0.708,
  "event_banner_left_x": 0.25, "event_banner_right_x": 0.75,
  "event_banner_y": 0.477,
  "event_priority": ["green", "camp", "workshop", "pink"],

  "grid_col1": 0.167, "grid_col2": 0.50, "grid_col3": 0.833,
  "grid_row1_banner_y": 0.448, "grid_row1_button_y": 0.544,
  "grid_row2_banner_y": 0.698, "grid_row2_button_y": 0.794,
  "grid_confirm_x": 0.588, "grid_confirm_y": 0.952,

  "fallback_tap_x": 0.92, "fallback_tap_y": 0.18,
  "popup_x_x": 0.79, "popup_x_y": 0.285,
  "end_turn_when_no_red": true,

  "action_delay": 0.9,
  "loop_delay": 0.6,
  "menu_delay": 1.2,
  "watchdog_after": 12
}
""".trimIndent()
    }
}

