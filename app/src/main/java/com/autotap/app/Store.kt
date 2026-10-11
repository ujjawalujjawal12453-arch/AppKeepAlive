package com.autotap.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Ek point: tap ya swipe. Position screen ke fraction (0..1) me.
// dv = is point se pehle kitna ruke (0 = default time use hoga)
// du = dv ki unit (0 ms, 1 second, 2 minute), rp = kitni baar
data class Pt(
    val x1: Float,
    val y1: Float,
    val swipe: Boolean = false,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val dv: Long = 0L,
    val du: Int = 1,
    val rp: Int = 1
)

// unit: 0 = millisecond, 1 = second, 2 = minute (default time ki)
data class Setup(
    val id: String,
    val value: Long,
    val unit: Int,
    val points: List<Pt>
)

object Store {
    private fun prefs(c: Context) =
        c.getSharedPreferences("autotap", Context.MODE_PRIVATE)

    private fun readAll(c: Context): JSONObject =
        try {
            JSONObject(prefs(c).getString("setups", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun newId(all: JSONObject): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        while (true) {
            val id = (1..6).map { chars.random() }.joinToString("")
            if (!all.has(id)) return id
        }
    }

    private fun toJson(value: Long, unit: Int, points: List<Pt>): JSONObject {
        val o = JSONObject()
        o.put("v", value)
        o.put("u", unit)
        val arr = JSONArray()
        for (p in points) {
            val j = JSONObject()
            j.put("x", p.x1.toDouble())
            j.put("y", p.y1.toDouble())
            j.put("s", if (p.swipe) 1 else 0)
            j.put("x2", p.x2.toDouble())
            j.put("y2", p.y2.toDouble())
            j.put("dv", p.dv)
            j.put("du", p.du)
            j.put("rp", p.rp)
            arr.put(j)
        }
        o.put("p", arr)
        return o
    }

    fun save(c: Context, value: Long, unit: Int, points: List<Pt>): String {
        val all = readAll(c)
        val id = newId(all)
        all.put(id, toJson(value, unit, points))
        prefs(c).edit().putString("setups", all.toString()).apply()
        return id
    }

    // pehle se bani ID me naya kaam likh do
    fun update(c: Context, id: String, value: Long, unit: Int, points: List<Pt>) {
        val all = readAll(c)
        all.put(id, toJson(value, unit, points))
        prefs(c).edit().putString("setups", all.toString()).apply()
    }

    fun exists(c: Context, id: String): Boolean = readAll(c).has(id)

    fun load(c: Context, id: String): Setup? {
        val o = readAll(c).optJSONObject(id) ?: return null
        val arr = o.getJSONArray("p")
        val pts = ArrayList<Pt>()
        for (i in 0 until arr.length()) {
            val e = arr.opt(i)
            if (e is JSONObject) {
                pts.add(
                    Pt(
                        e.getDouble("x").toFloat(),
                        e.getDouble("y").toFloat(),
                        e.optInt("s", 0) == 1,
                        e.optDouble("x2", 0.0).toFloat(),
                        e.optDouble("y2", 0.0).toFloat(),
                        e.optLong("dv", 0L),
                        e.optInt("du", 1),
                        maxOf(1, e.optInt("rp", 1))
                    )
                )
            } else if (e is JSONArray) {
                // purana format
                if (e.length() >= 4) {
                    pts.add(
                        Pt(
                            e.getDouble(0).toFloat(), e.getDouble(1).toFloat(),
                            true, e.getDouble(2).toFloat(), e.getDouble(3).toFloat()
                        )
                    )
                } else if (e.length() >= 2) {
                    pts.add(Pt(e.getDouble(0).toFloat(), e.getDouble(1).toFloat()))
                }
            }
        }
        return Setup(id, o.getLong("v"), o.getInt("u"), pts)
    }

    fun list(c: Context): List<Setup> {
        val all = readAll(c)
        val out = ArrayList<Setup>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val s = load(c, k)
            if (s != null) out.add(s)
        }
        return out
    }

    fun delete(c: Context, id: String) {
        val all = readAll(c)
        all.remove(id)
        prefs(c).edit().putString("setups", all.toString()).apply()
    }

    // ---- chhoti settings
    fun isPanelOff(c: Context): Boolean = prefs(c).getBoolean("panel_off", false)

    fun setPanelOff(c: Context, off: Boolean) {
        prefs(c).edit().putBoolean("panel_off", off).apply()
    }

    fun saveInterval(c: Context, value: Long, unit: Int) {
        prefs(c).edit().putLong("iv", value).putInt("iu", unit).apply()
    }

    fun loadInterval(c: Context): Pair<Long, Int> {
        val p = prefs(c)
        return Pair(p.getLong("iv", 1L), p.getInt("iu", 1))
    }

    // ---- AI madad (Google Gemini). Key sirf phone me rehti hai.
    fun aiKey(c: Context): String = prefs(c).getString("ai_key", "") ?: ""

    fun setAiKey(c: Context, key: String) {
        prefs(c).edit().putString("ai_key", key.trim()).apply()
    }

    // khaali = abhi chuna nahi gaya (Gemini.pickModel se apne aap chuna jata hai)
    fun aiModel(c: Context): String = prefs(c).getString("ai_model", "") ?: ""

    fun setAiModel(c: Context, model: String) {
        prefs(c).edit().putString("ai_model", model.trim()).apply()
    }

    fun isAiOn(c: Context): Boolean = prefs(c).getBoolean("ai_on", false)

    fun setAiOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("ai_on", on).apply()
    }
}
