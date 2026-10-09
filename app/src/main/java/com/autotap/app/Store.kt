package com.autotap.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// unit: 0 = millisecond, 1 = second, 2 = minute
// points: har action ek list hai, screen ke fraction (0..1) me
//   tap   = [x, y]
//   swipe = [x1, y1, x2, y2]
data class Setup(
    val id: String,
    val value: Long,
    val unit: Int,
    val points: List<List<Float>>
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

    fun save(c: Context, value: Long, unit: Int, points: List<List<Float>>): String {
        val all = readAll(c)
        val id = newId(all)
        val o = JSONObject()
        o.put("v", value)
        o.put("u", unit)
        val arr = JSONArray()
        for (p in points) {
            val one = JSONArray()
            for (f in p) one.put(f.toDouble())
            arr.put(one)
        }
        o.put("p", arr)
        all.put(id, o)
        prefs(c).edit().putString("setups", all.toString()).apply()
        return id
    }

    fun load(c: Context, id: String): Setup? {
        val o = readAll(c).optJSONObject(id) ?: return null
        val arr = o.getJSONArray("p")
        val pts = ArrayList<List<Float>>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val one = ArrayList<Float>()
            for (j in 0 until a.length()) one.add(a.getDouble(j).toFloat())
            pts.add(one)
        }
        return Setup(id, o.getLong("v"), o.getInt("u"), pts)
    }

    // ---- chhoti settings (apne aap yaad rehti hain)
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

    // ---- abhi ka kaam apne aap save (band/chalu karne par wapas aa jata hai)
    fun saveSession(c: Context, points: List<List<Float>>) {
        val arr = JSONArray()
        for (p in points) {
            val one = JSONArray()
            for (f in p) one.put(f.toDouble())
            arr.put(one)
        }
        prefs(c).edit().putString("session", arr.toString()).apply()
    }

    fun loadSession(c: Context): List<List<Float>> {
        val out = ArrayList<List<Float>>()
        try {
            val arr = JSONArray(prefs(c).getString("session", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val one = ArrayList<Float>()
                for (j in 0 until a.length()) one.add(a.getDouble(j).toFloat())
                out.add(one)
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun delete(c: Context, id: String) {
        val all = readAll(c)
        all.remove(id)
        prefs(c).edit().putString("setups", all.toString()).apply()
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
}
