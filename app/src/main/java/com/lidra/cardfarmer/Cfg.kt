package com.lidra.cardfarmer

import org.json.JSONObject

class Step(val name: String, val x: Int, val y: Int, val wait: Long)

class Cfg(json: String) {
    private val o = JSONObject(json)

    val hand: List<Pair<Int, Int>> = o.getJSONArray("hand_slots").let { arr ->
        (0 until arr.length()).map {
            val a = arr.getJSONArray(it)
            Pair(a.getInt(0), a.getInt(1))
        }
    }
    val cardRadius = o.getInt("card_sample_radius")

    val target: Pair<Int, Int> = o.getJSONArray("enemy_target").let { Pair(it.getInt(0), it.getInt(1)) }
    val special: Pair<Int, Int> = o.getJSONArray("special_button").let { Pair(it.getInt(0), it.getInt(1)) }
    val specialRadius = o.getInt("special_sample_radius")
    val endTurn: Pair<Int, Int> = o.getJSONArray("end_turn_button").let { Pair(it.getInt(0), it.getInt(1)) }

    val postBattle: List<Step> = o.getJSONArray("post_battle_sequence").let { arr ->
        (0 until arr.length()).map {
            val s = arr.getJSONObject(it)
            Step(s.getString("name"), s.getInt("x"), s.getInt("y"), (s.getDouble("wait") * 1000).toLong())
        }
    }

    val redThreshold = o.getDouble("red_threshold")
    val dimThreshold = o.getDouble("dim_threshold")
    val specialSat = o.getDouble("special_ready_saturation")
    val specialBright = o.getDouble("special_ready_brightness")

    val actionDelay = (o.getDouble("action_delay") * 1000).toLong()
    val loopDelay = (o.getDouble("loop_delay") * 1000).toLong()
    val stuckAfter = o.getInt("stuck_after")

    companion object {
        /** Same shape as the Termux script's cg_config.json, so numbers carry over. */
        fun default(w: Int, h: Int): String = """
{
  "screen": [$w, $h],
  "hand_slots": [
    [${(w * 0.24).toInt()}, ${(h * 0.87).toInt()}],
    [${(w * 0.37).toInt()}, ${(h * 0.855).toInt()}],
    [${(w * 0.50).toInt()}, ${(h * 0.85).toInt()}],
    [${(w * 0.63).toInt()}, ${(h * 0.855).toInt()}],
    [${(w * 0.76).toInt()}, ${(h * 0.87).toInt()}]
  ],
  "card_sample_radius": ${(w * 0.035).toInt()},
  "enemy_target": [${(w * 0.50).toInt()}, ${(h * 0.34).toInt()}],
  "special_button": [${(w * 0.88).toInt()}, ${(h * 0.70).toInt()}],
  "special_sample_radius": ${(w * 0.05).toInt()},
  "end_turn_button": [${(w * 0.86).toInt()}, ${(h * 0.80).toInt()}],
  "post_battle_sequence": [
    {"name": "skip card reward", "x": ${(w * 0.50).toInt()}, "y": ${(h * 0.92).toInt()}, "wait": 1.4},
    {"name": "confirm", "x": ${(w * 0.50).toInt()}, "y": ${(h * 0.86).toInt()}, "wait": 1.4},
    {"name": "claim", "x": ${(w * 0.50).toInt()}, "y": ${(h * 0.80).toInt()}, "wait": 1.4},
    {"name": "next / replay", "x": ${(w * 0.50).toInt()}, "y": ${(h * 0.88).toInt()}, "wait": 2.5}
  ],
  "red_threshold": 0.16,
  "dim_threshold": 0.20,
  "special_ready_saturation": 0.35,
  "special_ready_brightness": 0.28,
  "action_delay": 1.1,
  "loop_delay": 0.6,
  "stuck_after": 4
}
""".trimIndent()
    }
}
