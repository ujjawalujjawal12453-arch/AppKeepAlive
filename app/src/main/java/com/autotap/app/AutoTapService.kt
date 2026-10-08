package com.autotap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// Ek action: tap (sirf m1) ya swipe (m1 se m2 tak line)
private class Action(
    val m1: TextView,
    val p1: WindowManager.LayoutParams,
    val m2: TextView?,
    val p2: WindowManager.LayoutParams?
)

class AutoTapService : AccessibilityService() {

    companion object {
        var instance: AutoTapService? = null
        const val SWIPE_MS = 400L
    }

    private lateinit var wm: WindowManager
    private lateinit var play: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var panel: LinearLayout? = null
    private var settingsView: View? = null
    private var lineView: LineView? = null

    private val actions = ArrayList<Action>()

    private var running = false
    private var idx = 0
    private var value = 1L
    private var unit = 1 // 0 ms, 1 sec, 2 min

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopRun()
        closeSettings()
        removeMarkers()
        panel?.let { wm.removeView(it) }
        panel = null
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------- line drawing

    private inner class LineView : View(this@AutoTapService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.color = 0xFF1E88E5.toInt()
            paint.strokeWidth = dp(4).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(c: Canvas) {
            for (a in actions) {
                val q = a.p2 ?: continue
                val x1 = (a.p1.x + dp(22)).toFloat()
                val y1 = (a.p1.y + dp(22)).toFloat()
                val x2 = (q.x + dp(22)).toFloat()
                val y2 = (q.y + dp(22)).toFloat()
                c.drawLine(x1, y1, x2, y2, paint)
                // teer ka nishan (end ki taraf)
                val ang = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                val len = dp(18).toDouble()
                for (d in doubleArrayOf(2.6, -2.6)) {
                    c.drawLine(
                        x2, y2,
                        (x2 + len * Math.cos(ang + d)).toFloat(),
                        (y2 + len * Math.sin(ang + d)).toFloat(),
                        paint
                    )
                }
            }
        }
    }

    private fun ensureLine() {
        if (lineView != null) return
        val dm = resources.displayMetrics
        val v = LineView()
        val p = lp(dm.widthPixels, dm.heightPixels)
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.x = 0
        p.y = 0
        wm.addView(v, p)
        lineView = v
    }

    // ---------------------------------------------------------------- window params

    private fun lp(w: Int, h: Int, focusable: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        return p
    }

    private fun makeDraggable(
        handle: View,
        target: View,
        p: WindowManager.LayoutParams,
        onMove: (() -> Unit)? = null
    ) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = p.x
                    oy = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ox + (e.rawX - sx).toInt()
                    p.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(target, p)
                    onMove?.invoke()
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- panel

    private fun btn(label: String, color: Int): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        t.background = bg
        val l = LinearLayout.LayoutParams(dp(44), dp(44))
        l.setMargins(dp(3), dp(4), dp(3), dp(4))
        t.layoutParams = l
        return t
    }

    fun showPanel() {
        if (panel != null) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val bg = GradientDrawable()
        bg.setColor(0xCC222222.toInt())
        bg.cornerRadius = dp(30).toFloat()
        row.background = bg
        row.setPadding(dp(4), dp(4), dp(4), dp(4))

        val handle = btn("≡", 0xFF616161.toInt())
        play = btn("▶", 0xFF2E7D32.toInt())
        val add = btn("+", 0xFF1976D2.toInt())
        val swipe = btn("↕", 0xFF6A1B9A.toInt())
        val set = btn("⚙", 0xFFF57C00.toInt())
        val close = btn("✕", 0xFFC62828.toInt())

        row.addView(handle)
        row.addView(play)
        row.addView(add)
        row.addView(swipe)
        row.addView(set)
        row.addView(close)

        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = dp(8)
        p.y = dp(200)
        makeDraggable(handle, row, p)

        play.setOnClickListener {
            if (running) stopRun() else startRun()
        }
        add.setOnClickListener {
            if (running) {
                toast("Pehle stop karo")
            } else {
                val dm = resources.displayMetrics
                val n = actions.size
                addTap(dm.widthPixels / 2 + n * dp(14), dm.heightPixels / 2 + n * dp(14))
            }
        }
        swipe.setOnClickListener {
            if (running) {
                toast("Pehle stop karo")
            } else {
                val dm = resources.displayMetrics
                val n = actions.size
                val cx = dm.widthPixels / 2 + n * dp(14)
                val cy = dm.heightPixels / 2 + n * dp(14)
                // shuru me neeche se upar ki line; dono sire kheench kar kisi bhi disha me kar sakte ho
                addSwipe(cx, cy + dp(100), cx, cy - dp(100))
            }
        }
        set.setOnClickListener { toggleSettings() }
        close.setOnClickListener {
            stopRun()
            closeSettings()
            removeMarkers()
            panel?.let { wm.removeView(it) }
            panel = null
        }

        wm.addView(row, p)
        panel = row
    }

    // ---------------------------------------------------------------- markers

    private fun newMarker(
        label: String,
        color: Int,
        cx: Int,
        cy: Int
    ): Pair<TextView, WindowManager.LayoutParams> {
        val t = TextView(this)
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        bg.setStroke(dp(2), Color.WHITE)
        t.background = bg
        t.text = label

        val p = lp(dp(44), dp(44))
        p.x = cx - dp(22)
        p.y = cy - dp(22)
        makeDraggable(t, t, p) { lineView?.invalidate() }
        wm.addView(t, p)
        return Pair(t, p)
    }

    private fun addTap(cx: Int, cy: Int) {
        val n = actions.size + 1
        val m = newMarker(n.toString(), 0xAAE53935.toInt(), cx, cy)
        actions.add(Action(m.first, m.second, null, null))
    }

    private fun addSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        ensureLine()
        val n = actions.size + 1
        val a = newMarker("S$n", 0xAA2E7D32.toInt(), x1, y1)
        val b = newMarker("E$n", 0xAA1565C0.toInt(), x2, y2)
        actions.add(Action(a.first, a.second, b.first, b.second))
        lineView?.invalidate()
    }

    private fun removeMarkers() {
        for (a in actions) {
            try {
                wm.removeView(a.m1)
            } catch (e: Exception) {
            }
            val m2 = a.m2
            if (m2 != null) {
                try {
                    wm.removeView(m2)
                } catch (e: Exception) {
                }
            }
        }
        actions.clear()
        lineView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        lineView = null
    }

    private fun setTouchable(m: TextView, p: WindowManager.LayoutParams, touchable: Boolean) {
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm.updateViewLayout(m, p)
        } catch (e: Exception) {
        }
        m.alpha = if (touchable) 1f else 0.5f
    }

    // ---------------------------------------------------------------- run / stop

    private fun intervalMs(): Long {
        val mul = when (unit) {
            0 -> 1L
            1 -> 1000L
            else -> 60000L
        }
        return maxOf(value * mul, 50L)
    }

    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!running || actions.isEmpty()) return
            if (idx >= actions.size) idx = 0
            val a = actions[idx]
            var wait = intervalMs()
            val q = a.p2
            if (q == null) {
                tap(a.p1.x + dp(22), a.p1.y + dp(22))
            } else {
                swipeGesture(
                    a.p1.x + dp(22), a.p1.y + dp(22),
                    q.x + dp(22), q.y + dp(22)
                )
                wait = maxOf(wait, SWIPE_MS + 100)
            }
            idx++
            handler.postDelayed(this, wait)
        }
    }

    private fun tap(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun swipeGesture(x1: Int, y1: Int, x2: Int, y2: Int) {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun startRun() {
        if (actions.isEmpty()) {
            toast("Pehle + ya ↕ se point lagao")
            return
        }
        closeSettings()
        running = true
        idx = 0
        for (a in actions) {
            setTouchable(a.m1, a.p1, false)
            val m2 = a.m2
            val q = a.p2
            if (m2 != null && q != null) setTouchable(m2, q, false)
        }
        play.text = "■"
        handler.postDelayed(tapRunnable, 300)
    }

    private fun stopRun() {
        running = false
        handler.removeCallbacks(tapRunnable)
        for (a in actions) {
            setTouchable(a.m1, a.p1, true)
            val m2 = a.m2
            val q = a.p2
            if (m2 != null && q != null) setTouchable(m2, q, true)
        }
        if (this::play.isInitialized) play.text = "▶"
    }

    // ---------------------------------------------------------------- settings box

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.BLACK)
        t.textSize = 14f
        t.setPadding(0, dp(8), 0, dp(4))
        return t
    }

    private fun action(text: String, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.textSize = 15f
        t.setPadding(dp(12), dp(10), dp(12), dp(10))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(10).toFloat()
        t.background = bg
        val l = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        l.setMargins(0, dp(6), 0, 0)
        t.layoutParams = l
        return t
    }

    private fun closeSettings() {
        settingsView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        settingsView = null
    }

    private fun toggleSettings() {
        if (settingsView != null) {
            closeSettings()
            return
        }
        if (running) {
            toast("Pehle stop karo")
            return
        }

        val dm = resources.displayMetrics
        val w = minOf(dp(320), dm.widthPixels - dp(32))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(12), dp(16), dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(16).toFloat()
        box.background = bg

        box.addView(label("Har action (tap/swipe) ke beech ka time"))

        val valueEdit = EditText(this)
        valueEdit.inputType = InputType.TYPE_CLASS_NUMBER
        valueEdit.setText(value.toString())
        valueEdit.setTextColor(Color.BLACK)
        box.addView(valueEdit)

        val unitRow = LinearLayout(this)
        unitRow.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("ms", "second", "minute")
        val unitViews = ArrayList<TextView>()
        var sel = unit

        fun paint() {
            for (i in unitViews.indices) {
                unitViews[i].setBackgroundColor(
                    if (i == sel) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt()
                )
            }
        }

        for (i in names.indices) {
            val u = TextView(this)
            u.text = names[i]
            u.setTextColor(Color.WHITE)
            u.gravity = Gravity.CENTER
            u.setPadding(0, dp(10), 0, dp(10))
            val l = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            l.setMargins(dp(2), dp(6), dp(2), dp(6))
            u.layoutParams = l
            u.setOnClickListener {
                sel = i
                paint()
            }
            unitViews.add(u)
            unitRow.addView(u)
        }
        paint()
        box.addView(unitRow)

        val idText = TextView(this)
        idText.setTextColor(0xFF2E7D32.toInt())
        idText.textSize = 18f
        idText.setPadding(0, dp(4), 0, dp(4))

        val save = action("Save karo (ID banao)", 0xFF2E7D32.toInt())
        save.setOnClickListener {
            if (actions.isEmpty()) {
                toast("Pehle + ya ↕ se point lagao")
            } else {
                value = valueEdit.text.toString().toLongOrNull() ?: 1L
                if (value < 1) value = 1
                unit = sel
                val w1 = dm.widthPixels.toFloat()
                val h1 = dm.heightPixels.toFloat()
                val pts = ArrayList<List<Float>>()
                for (a in actions) {
                    val q = a.p2
                    if (q == null) {
                        pts.add(listOf((a.p1.x + dp(22)) / w1, (a.p1.y + dp(22)) / h1))
                    } else {
                        pts.add(
                            listOf(
                                (a.p1.x + dp(22)) / w1, (a.p1.y + dp(22)) / h1,
                                (q.x + dp(22)) / w1, (q.y + dp(22)) / h1
                            )
                        )
                    }
                }
                val id = Store.save(this, value, unit, pts)
                idText.text = "ID: $id"
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AutoTap ID", id))
                toast("Save ho gaya. ID copy ho gayi: $id")
            }
        }
        box.addView(save)
        box.addView(idText)

        box.addView(label("Purani ID se load karo"))
        val idEdit = EditText(this)
        idEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        idEdit.setTextColor(Color.BLACK)
        idEdit.hint = "ID likho"
        box.addView(idEdit)

        val load = action("Load karo", 0xFF1976D2.toInt())
        load.setOnClickListener {
            val id = idEdit.text.toString().trim().uppercase()
            val s = Store.load(this, id)
            if (s == null) {
                toast("Ye ID nahi mili")
            } else {
                value = s.value
                unit = s.unit
                removeMarkers()
                for (pt in s.points) {
                    if (pt.size >= 4) {
                        addSwipe(
                            (pt[0] * dm.widthPixels).toInt(), (pt[1] * dm.heightPixels).toInt(),
                            (pt[2] * dm.widthPixels).toInt(), (pt[3] * dm.heightPixels).toInt()
                        )
                    } else if (pt.size >= 2) {
                        addTap(
                            (pt[0] * dm.widthPixels).toInt(),
                            (pt[1] * dm.heightPixels).toInt()
                        )
                    }
                }
                toast("Load ho gaya: ${s.points.size} action")
                closeSettings()
            }
        }
        box.addView(load)

        val clear = action("Saare points/lines hatao", 0xFFF57C00.toInt())
        clear.setOnClickListener {
            removeMarkers()
            toast("Sab hat gaya")
        }
        box.addView(clear)

        val done = action("Band karo", 0xFF616161.toInt())
        done.setOnClickListener { closeSettings() }
        box.addView(done)

        val p = lp(w, WindowManager.LayoutParams.WRAP_CONTENT, focusable = true)
        p.x = (dm.widthPixels - w) / 2
        p.y = dp(80)
        wm.addView(box, p)
        settingsView = box
    }
}
