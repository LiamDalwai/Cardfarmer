package com.lidra.cardfarmer

import org.json.JSONObject

class Step(val name: String, val x: Int, val y: Int, val wait: Long)

class Cfg(json: String) {
    private val o = JSONObject(json)
    private fun pt(k: String): Pair<Int, Int> {
        val a = o.getJSONArray(k); return Pair(a.getInt(0), a.getInt(1))
    }

    // the strip across the card name banners
    val scanY = o.getInt("card_scan_y")
    val scanXMin = o.getInt("card_scan_x_min")
    val scanXMax = o.getInt("card_scan_x_max")
    val minWidth = o.getInt("card_min_width")
    val artOffset = o.getInt("card_art_offset_y")

    val priority: List<String> = o.getJSONArray("card_priority").let { a ->
        (0 until a.length()).map { a.getString(it) }
    }

    val target = pt("enemy_target")
    val dragMs = o.getLong("drag_ms")

    val special = pt("special_button")
    val specialRadius = o.getInt("special_sample_radius")
    val specialMode = o.getString("special_mode")   // "detect" or "always_try"
    val specialSat = o.getDouble("special_ready_saturation")
    val specialBright = o.getDouble("special_ready_brightness")

    val endTurn = pt("end_turn_button")

    val postBattle: List<Step> = o.getJSONArray("post_battle_sequence").let { arr ->
        (0 until arr.length()).map {
            val s = arr.getJSONObject(it)
            Step(s.getString("name"), s.getInt("x"), s.getInt("y"), (s.getDouble("wait") * 1000).toLong())
        }
    }

    val dimThreshold = o.getDouble("dim_threshold")
    val actionDelay = (o.getDouble("action_delay") * 1000).toLong()
    val loopDelay = (o.getDouble("loop_delay") * 1000).toLong()
    val stuckAfter = o.getInt("stuck_after")

    companion object {
        private fun rx(w: Int, r: Double) = (w * r).toInt()
        private fun ry(h: Int, r: Double) = (h * r).toInt()

        /** Ratios measured off a 1080x2400 Card Guardians screen. */
        fun default(w: Int, h: Int): String = """
{
  "screen": [$w, $h],

  "card_scan_y": ${ry(h, 0.8217)},
  "card_scan_x_min": ${rx(w, 0.03)},
  "card_scan_x_max": ${rx(w, 0.97)},
  "card_min_width": ${rx(w, 0.09)},
  "card_art_offset_y": ${-ry(h, 0.058)},
  "card_priority": ["red", "green", "blue"],

  "enemy_target": [${rx(w, 0.50)}, ${ry(h, 0.333)}],
  "drag_ms": 650,

  "special_button": [${rx(w, 0.884)}, ${ry(h, 0.560)}],
  "special_sample_radius": ${rx(w, 0.05)},
  "special_mode": "detect",
  "special_ready_saturation": 0.30,
  "special_ready_brightness": 0.25,

  "end_turn_button": [${rx(w, 0.630)}, ${ry(h, 0.953)}],

  "post_battle_sequence": [
    {"name": "collect", "x": ${rx(w, 0.50)}, "y": ${ry(h, 0.8375)}, "wait": 2.0},
    {"name": "skip card", "x": ${rx(w, 0.50)}, "y": ${ry(h, 0.7229)}, "wait": 2.0},
    {"name": "next fight", "x": ${rx(w, 0.50)}, "y": ${ry(h, 0.7083)}, "wait": 2.5},
    {"name": "next fight again", "x": ${rx(w, 0.50)}, "y": ${ry(h, 0.7083)}, "wait": 2.5}
  ],

  "dim_threshold": 0.16,
  "action_delay": 1.2,
  "loop_delay": 0.7,
  "stuck_after": 4
}
""".trimIndent()
    }
}
