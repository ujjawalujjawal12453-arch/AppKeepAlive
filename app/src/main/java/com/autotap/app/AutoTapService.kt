package com.autotap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

// Ek action: tap (sirf m1) ya swipe (m1 se m2 tak line)
private class Action(
    val m1: TextView,
    val p1: WindowManager.LayoutParams,
    val m2: TextView?,
    val p2: WindowManager.LayoutParams?
) {
    var dv = 0L // is point se pehle kitna ruke (0 = default time)
    var du = 1  // dv ki unit: 0 ms, 1 second, 2 minute
    var rp = 1  // kitni baar
}

class AutoTapService : AccessibilityService() {

    companion object {
        var instance: AutoTapService? = null
        const val SWIPE_MS = 400L
        const val AI_PROMPT = """This is a screenshot of an Android phone. An auto-tapper app is running a loop of taps on this screen.
If a popup, dialog, permission prompt, ad or similar overlay is blocking the screen, pick ONE button that dismisses it or lets things continue (for example OK, Allow, Continue, Close, X, Skip, Not now, Got it, or Cancel when it just closes the dialog).
NEVER pick buttons about payments, purchases, subscriptions, deleting, uninstalling, sending money, sending messages, or confirming orders.
If nothing is blocking the screen, or the only choices look risky, answer none.
Reply with ONLY JSON, no other text:
{"action":"tap","label":"button text","box_2d":[ymin,xmin,ymax,xmax]} with coordinates normalized to 0-1000
or {"action":"none"}"""
    }

    private lateinit var wm: WindowManager
    private lateinit var play: TextView
    private lateinit var rec: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var bubble: View? = null
    private var settingsView: View? = null
    private var lineView: LineView? = null
    private var recordView: View? = null
    private var recordLp: WindowManager.LayoutParams? = null

    private val actions = ArrayList<Action>()
    private val typedId = StringBuilder()
    private var currentId: String? = null
    private var rep = 0
    private var paused = false
    private var aiBusy = false
    private var aiLastMs = 0L
    private var aiCalls = 0
    private var aiFails = 0
    private var aiAttempt = 0
    private var aiModelRetried = false
    private var basePkg: String? = null
    private var lastPkg: String? = null

    private var running = false
    private var recording = false
    private var hidden = false
    private var stealth = false
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
        val iv = Store.loadInterval(this)
        value = iv.first
        unit = iv.second
    }

    // ---------------------------------------------------------------- AI madad (Google Gemini)

    // Chalte waqt koi popup / dialog aaye to (aur AI ON ho to) screen ki tasveer Gemini ko bhejkar
    // pooochta hai ki kaun sa button dabana hai. Sirf tab jab AI ON ho aur loop chal raha ho.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val cls = event.className?.toString() ?: ""
        lastPkg = pkg
        if (!running || !Store.isAiOn(this)) return
        if (pkg == "com.android.systemui") return
        val base = basePkg
        val looksDialog = cls.contains("Dialog", true) || cls.contains("Alert", true) ||
            cls.contains("Popup", true)
        val otherApp = base != null && pkg != base
        if (looksDialog || otherApp) askAi()
    }

    private fun askAi() {
        if (aiBusy || aiCalls >= 40 || aiFails >= 3) return
        val now = SystemClock.elapsedRealtime()
        if (now - aiLastMs < 3000L) return
        if (Build.VERSION.SDK_INT < 30) {
            aiFails = 99
            toast("AI ke liye Android 11 ya naya chahiye")
            return
        }
        val key = Store.aiKey(this)
        if (key.isBlank()) {
            aiFails = 99
            toast("AI key nahi hai. AutoTap app me daalo.")
            return
        }
        aiBusy = true
        paused = true
        aiAttempt = 0
        aiModelRetried = false
        aiLastMs = now
        aiCalls++
        toast("AI screen dekh raha hai...")
        handler.postDelayed({ takeShot(key) }, 700)
    }

    private fun aiDone(msg: String?, failed: Boolean = false) {
        aiBusy = false
        if (failed) aiFails++
        if (msg != null) toast(msg)
        handler.postDelayed({ paused = false }, 600)
    }

    private fun takeShot(key: String) {
        if (!running) {
            aiBusy = false
            paused = false
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executor { r -> handler.post(r) },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            if (hw == null) {
                                hb.close()
                                aiDone("Screenshot nahi mil paya", true)
                                return
                            }
                            val full = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            val fw = full.width
                            val fh = full.height
                            val sw = minOf(fw, 720)
                            val sh = maxOf(1, fh * sw / fw)
                            val small = Bitmap.createScaledBitmap(full, sw, sh, true)
                            val bos = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            callGemini(key, b64, fw, fh)
                        } catch (e: Exception) {
                            aiDone("Screenshot ka kaam nahi hua", true)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        aiDone("Screenshot nahi mil paya (code " + errorCode + ")", true)
                    }
                }
            )
        } catch (e: Exception) {
            aiDone("Screenshot nahi mil paya", true)
        }
    }

    private fun callGemini(key: String, b64: String, fw: Int, fh: Int) {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", AI_PROMPT))
        parts.put(
            JSONObject().put(
                "inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", b64)
            )
        )
        val body = JSONObject()
        body.put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        body.put(
            "generationConfig",
            JSONObject().put("responseMimeType", "application/json").put("temperature", 0)
        )
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        Thread {
            try {
                var model = Store.aiModel(this)
                if (model.isBlank()) {
                    // model abhi chuna nahi gaya: apne aap chuno aur yaad rakho
                    val picked = Gemini.pickModel(key)
                    model = picked ?: "gemini-flash-latest"
                    if (picked != null) Store.setAiModel(this, picked)
                }
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
                )
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 45000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(payload) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val txt = stream?.bufferedReader()?.readText() ?: ""
                handler.post { onAiReply(code, txt, fw, fh) }
            } catch (e: Exception) {
                handler.post { aiDone("AI se baat nahi ho paayi (internet check karo)", true) }
            }
        }.start()
    }

    private fun onAiReply(code: Int, txt: String, fw: Int, fh: Int) {
        if (!running) {
            aiBusy = false
            paused = false
            return
        }
        if (code == 404 && !aiModelRetried) {
            // model ka naam purana ho gaya ho to naya apne aap chuno aur dobara koshish karo
            aiModelRetried = true
            val key = Store.aiKey(this)
            Thread {
                val m = Gemini.pickModel(key)
                handler.post {
                    if (m != null) {
                        Store.setAiModel(this, m)
                        handler.postDelayed({ takeShot(key) }, 300)
                    } else {
                        aiDone("AI ka model nahi mila (internet ya key check karo)", true)
                    }
                }
            }.start()
            return
        }
        if (code !in 200..299) {
            val hint = if (code == 400 || code == 401 || code == 403) {
                "AI key galat ya band hai (code " + code + ")"
            } else {
                "AI ne error diya (code " + code + ")"
            }
            aiDone(hint, true)
            return
        }
        try {
            val root = JSONObject(txt)
            var t = root.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            }
            val o = if (t.startsWith("[")) JSONArray(t).getJSONObject(0) else JSONObject(t)
            if (o.optString("action") == "tap") {
                val b = o.getJSONArray("box_2d")
                val ymin = b.getDouble(0)
                val xmin = b.getDouble(1)
                val ymax = b.getDouble(2)
                val xmax = b.getDouble(3)
                if (ymin < 0 || xmin < 0 || ymax > 1000 || xmax > 1000 || ymax <= ymin || xmax <= xmin) {
                    aiDone("AI ka jawab galat tha", true)
                    return
                }
                val x = ((xmin + xmax) / 2.0 / 1000.0 * fw).toInt()
                val y = ((ymin + ymax) / 2.0 / 1000.0 * fh).toInt()
                tap(x, y)
                aiAttempt++
                toast("AI ne dabaya: " + o.optString("label"))
                if (aiAttempt < 3) {
                    // popup hata ya nahi, thodi der baad dobara dekho. Hat gaya to AI band aur loop wapas chalu.
                    val key = Store.aiKey(this)
                    aiCalls++
                    handler.postDelayed({ takeShot(key) }, 1300)
                } else {
                    aiDone(null)
                }
            } else {
                // koi popup nahi (ya hat chuka): AI wapas so jata hai, loop chalu
                aiDone(null)
            }
        } catch (e: Exception) {
            aiDone("AI ka jawab samajh nahi aaya", true)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupAll()
        instance = null
        return super.onUnbind(intent)
    }

    private fun cleanupAll() {
        stopRun()
        stopRecord()
        closeSettings()
        removeMarkers()
        removeBubble()
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        panel = null
        hidden = false
        stealth = false
        currentId = null
        paused = false
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
        onMove: (() -> Unit)? = null,
        onTap: (() -> Unit)? = null,
        onDrop: (() -> Unit)? = null
    ) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = p.x
                    oy = p.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        p.x = ox + dx.toInt()
                        p.y = oy + dy.toInt()
                        wm.updateViewLayout(target, p)
                        onMove?.invoke()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) onDrop?.invoke() else onTap?.invoke()
                }
            }
            true
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
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
        val l = LinearLayout.LayoutParams(dp(40), dp(40))
        l.setMargins(dp(3), dp(3), dp(3), dp(3))
        t.layoutParams = l
        return t
    }

    private fun tint(t: TextView, color: Int) {
        (t.background as GradientDrawable).setColor(color)
    }

    fun showPanel() {
        Store.setPanelOff(this, false)
        if (hidden) {
            restore()
            return
        }
        if (panel != null) return

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.background = rounded(0xDD1B1F2A.toInt(), 24)
        col.setPadding(dp(3), dp(3), dp(3), dp(3))

        val handle = logoView(dp(40))
        val hl = LinearLayout.LayoutParams(dp(40), dp(40))
        hl.setMargins(dp(3), dp(3), dp(3), dp(3))
        handle.layoutParams = hl
        play = btn("▶", 0xFF2E7D32.toInt())
        rec = btn("●", 0xFFD81B60.toInt())
        val add = btn("+", 0xFF1976D2.toInt())
        val swipe = btn("↕", 0xFF6A1B9A.toInt())
        val eye = btn("👁", 0xFF00796B.toInt())
        val save = btn("💾", 0xFF0277BD.toInt())
        val minus = btn("−", 0xFFEF6C00.toInt())
        val set = btn("⚙", 0xFFF57C00.toInt())
        val close = btn("✕", 0xFFC62828.toInt())

        col.addView(handle)
        col.addView(play)
        col.addView(rec)
        col.addView(add)
        col.addView(minus)
        col.addView(swipe)
        col.addView(eye)
        col.addView(save)
        col.addView(set)
        col.addView(close)

        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = dp(4)
        p.y = dp(120)
        makeDraggable(handle, col, p, onTap = { hideAll(false) })

        play.setOnClickListener {
            if (recording) {
                toast("Pehle recording band karo")
            } else if (running) {
                stopRun()
            } else {
                startRun()
            }
        }
        rec.setOnClickListener {
            if (recording) stopRecord() else startRecord()
        }
        add.setOnClickListener {
            if (running || recording) {
                toast("Pehle stop karo")
            } else {
                val dm = resources.displayMetrics
                val n = actions.size
                addTap(dm.widthPixels / 2 + n * dp(14), dm.heightPixels / 2 + n * dp(14))
            }
        }
        swipe.setOnClickListener {
            if (running || recording) {
                toast("Pehle stop karo")
            } else {
                val dm = resources.displayMetrics
                val n = actions.size
                val cx = dm.widthPixels / 2 + n * dp(14)
                val cy = dm.heightPixels / 2 + n * dp(14)
                addSwipe(cx, cy + dp(100), cx, cy - dp(100))
            }
        }
        eye.setOnClickListener {
            if (recording) {
                toast("Pehle recording band karo")
            } else if (actions.isEmpty()) {
                toast("Pehle koi tap ya swipe lagao")
            } else {
                if (!running) startRun()
                hideAll(true)
            }
        }
        save.setOnClickListener { toggleIdBox() }
        set.setOnClickListener { toggleSettings() }
        minus.setOnClickListener { removeLastAction() }
        close.setOnClickListener { showCloseChoice() }

        wm.addView(col, p)
        panel = col
        panelLp = p
    }

    fun isPanelShown(): Boolean = panel != null

    // kisi ek point ko hatao (view bhi, list se bhi) aur number dobara lagao
    private fun removeActionAt(i: Int) {
        if (i < 0 || i >= actions.size) return
        val a = actions.removeAt(i)
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
        lineView?.invalidate()
        refreshLabels()
    }

    // minus: sabse aakhri lagaya hua tap ya swipe hata do (har baar ek)
    private fun removeLastAction() {
        if (running || recording) {
            toast("Pehle stop karo")
            return
        }
        if (actions.isEmpty()) {
            toast("Hatane ke liye kuch nahi hai")
            return
        }
        removeActionAt(actions.size - 1)
        toast("Ek hata diya. Bache: " + actions.size)
    }

    private fun bringPanelToFront() {
        val v = panel ?: return
        val p = panelLp ?: return
        if (hidden) return
        try {
            wm.removeView(v)
            wm.addView(v, p)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- hide / restore / close options

    private fun setVisibleAll(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        panel?.visibility = vis
        for (a in actions) {
            a.m1.visibility = vis
            a.m2?.visibility = vis
        }
        lineView?.visibility = vis
    }

    private fun logoView(size: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(R.drawable.ic_logo)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        return v
    }

    // tiny = true: bahut chhota, lagbhag nazar na aane wala bindu (chhupa hua mode)
    // tiny = false: app ka logo wala chhota floating icon
    private fun showBubble(tiny: Boolean) {
        if (bubble != null) return
        val v: View
        val size: Int
        if (tiny) {
            size = dp(18)
            val dot = View(this)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0x551976D2)
            dot.background = bg
            v = dot
        } else {
            size = dp(48)
            v = logoView(size)
        }

        val p = lp(size, size)
        val pl = panelLp
        p.x = pl?.x ?: dp(4)
        p.y = pl?.y ?: dp(120)
        makeDraggable(v, v, p, onTap = { restore() })
        wm.addView(v, p)
        bubble = v
    }

    private fun removeBubble() {
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
    }

    // quiet = false: sab chhup jata hai aur loop ruk jata hai
    // quiet = true : sab chhup jata hai, par tapping chalti rehti hai
    private fun hideAll(quiet: Boolean) {
        if (hidden) return
        if (!quiet) stopRun()
        stopRecord()
        closeSettings()
        hidden = true
        stealth = quiet
        setVisibleAll(false)
        showBubble(quiet)
        if (quiet) {
            toast("Chhupa kar chal raha hai. Wapas lane ke liye chhota bindu dabao.")
        } else {
            toast("Chhup gaya. Wapas lane ke liye logo icon dabao.")
        }
    }

    fun restore() {
        if (!hidden) return
        hidden = false
        stealth = false
        removeBubble()
        setVisibleAll(true)
    }

    // app ke STOP button se: turant sab band (Accessibility ON rehti hai)
    fun shutNow() {
        Store.setPanelOff(this, true)
        cleanupAll()
    }

    // panel permanent band: Accessibility ON rehti hai, bas panel apne aap nahi khulta.
    // Wapas lane ke liye AutoTap app me 'Floating panel dikhao'.
    private fun panelOffForever() {
        Store.setPanelOff(this, true)
        cleanupAll()
        toast("Panel band. Wapas lane ke liye AutoTap app me START dabao.")
    }

    private fun openAccessibilitySettings() {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Phone ki Settings > Accessibility kholo")
        }
    }

    private fun showCloseChoice() {
        if (settingsView != null) closeSettings()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(16))

        box.addView(title("Floating window band karni hai?"))

        val hide = action("Abhi chhupao\n(logo icon dabane par wapas aayega)", 0xFF1976D2.toInt())
        hide.setOnClickListener {
            closeSettings()
            hideAll(false)
        }
        val off = action("Panel band karo\n(START dabane par hi wapas aayega)", 0xFFF57C00.toInt())
        off.setOnClickListener {
            closeSettings()
            panelOffForever()
        }
        val full = action("Poora band karna hai\n(Accessibility settings khulegi, wahan AutoTap OFF karo)", 0xFFC62828.toInt())
        full.setOnClickListener {
            closeSettings()
            hideAll(false)
            openAccessibilitySettings()
        }
        val cancel = action("Cancel", 0xFF616161.toInt())
        cancel.setOnClickListener { closeSettings() }

        box.addView(hide)
        box.addView(off)
        box.addView(full)
        box.addView(cancel)
        showBox(box, 100)
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
        t.textSize = 12f
        t.setPadding(0, 0, 0, 0)
        t.typeface = Typeface.DEFAULT_BOLD
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        bg.setStroke(dp(2), Color.WHITE)
        t.background = bg
        t.text = label

        val p = lp(dp(44), dp(44))
        p.x = cx - dp(22)
        p.y = cy - dp(22)
        makeDraggable(t, t, p, onMove = { lineView?.invalidate() }, onTap = { openPointSettings(t) })
        if (recording) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            t.alpha = 0.5f
        }
        wm.addView(t, p)
        return Pair(t, p)
    }

    private fun addTap(cx: Int, cy: Int, dv: Long = 0L, du: Int = 1, rp: Int = 1) {
        val n = actions.size + 1
        val m = newMarker(n.toString(), 0xAAE53935.toInt(), cx, cy)
        val a = Action(m.first, m.second, null, null)
        a.dv = dv
        a.du = du
        a.rp = rp
        actions.add(a)
        refreshLabels()
    }

    private fun addSwipe(x1: Int, y1: Int, x2: Int, y2: Int, dv: Long = 0L, du: Int = 1, rp: Int = 1) {
        ensureLine()
        val n = actions.size + 1
        val s = newMarker("S" + n, 0xAA2E7D32.toInt(), x1, y1)
        val e = newMarker("E" + n, 0xAA1565C0.toInt(), x2, y2)
        val a = Action(s.first, s.second, e.first, e.second)
        a.dv = dv
        a.du = du
        a.rp = rp
        actions.add(a)
        lineView?.invalidate()
        refreshLabels()
    }

    private fun shortUnit(u: Int): String = when (u) {
        0 -> "ms"
        1 -> "s"
        else -> "m"
    }

    // gole par number + (agar alag time/baar ho to) chhota likha hota hai
    private fun refreshLabels() {
        for (i in actions.indices) {
            val a = actions[i]
            val n = i + 1
            val sb = StringBuilder()
            if (a.dv > 0L) sb.append(a.dv).append(shortUnit(a.du))
            if (a.rp > 1) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append("x").append(a.rp)
            }
            val ex = sb.toString()
            val e2 = a.m2
            if (e2 == null) {
                a.m1.text = if (ex.isEmpty()) n.toString() else n.toString() + "\n" + ex
            } else {
                a.m1.text = if (ex.isEmpty()) "S" + n else "S" + n + "\n" + ex
                e2.text = "E" + n
            }
        }
    }

    private fun currentPoints(): List<Pt> {
        val dm = resources.displayMetrics
        val w1 = dm.widthPixels.toFloat()
        val h1 = dm.heightPixels.toFloat()
        val pts = ArrayList<Pt>()
        for (a in actions) {
            val q = a.p2
            val x1 = (a.p1.x + dp(22)) / w1
            val y1 = (a.p1.y + dp(22)) / h1
            if (q == null) {
                pts.add(Pt(x1, y1, false, 0f, 0f, a.dv, a.du, a.rp))
            } else {
                pts.add(
                    Pt(
                        x1, y1, true,
                        (q.x + dp(22)) / w1, (q.y + dp(22)) / h1,
                        a.dv, a.du, a.rp
                    )
                )
            }
        }
        return pts
    }

    private fun addFromPoints(points: List<Pt>) {
        val dm = resources.displayMetrics
        for (pt in points) {
            if (pt.swipe) {
                addSwipe(
                    (pt.x1 * dm.widthPixels).toInt(), (pt.y1 * dm.heightPixels).toInt(),
                    (pt.x2 * dm.widthPixels).toInt(), (pt.y2 * dm.heightPixels).toInt(),
                    pt.dv, pt.du, pt.rp
                )
            } else {
                addTap(
                    (pt.x1 * dm.widthPixels).toInt(), (pt.y1 * dm.heightPixels).toInt(),
                    pt.dv, pt.du, pt.rp
                )
            }
        }
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

    private fun setAllTouchable(touchable: Boolean) {
        for (a in actions) {
            setTouchable(a.m1, a.p1, touchable)
            val m2 = a.m2
            val q = a.p2
            if (m2 != null && q != null) setTouchable(m2, q, touchable)
        }
    }

    // ---------------------------------------------------------------- record

    private fun startRecord() {
        if (running) {
            toast("Pehle stop karo")
            return
        }
        if (hidden || recording) return
        closeSettings()
        recording = true
        setAllTouchable(false)

        val dm = resources.displayMetrics
        val frame = FrameLayout(this)
        frame.setBackgroundColor(0x22FF0000)

        val banner = TextView(this)
        banner.text = "●  RECORDING\nJahan tap ya swipe karoge wahi record hoga, aur app me bhi chalega. Khatam hone par ● dabao."
        banner.setTextColor(Color.WHITE)
        banner.textSize = 14f
        banner.gravity = Gravity.CENTER
        banner.setPadding(dp(14), dp(10), dp(14), dp(10))
        banner.background = rounded(0xCCC62828.toInt(), 14)
        val bl = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        bl.topMargin = dp(48)
        frame.addView(banner, bl)

        var downX = 0f
        var downY = 0f
        val trail = ArrayList<Float>() // x, y, x, y ...
        frame.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    trail.clear()
                    trail.add(downX)
                    trail.add(downY)
                }
                MotionEvent.ACTION_MOVE -> {
                    val n = trail.size
                    if (n >= 2 && n < 240) {
                        val d = Math.hypot(
                            (e.rawX - trail[n - 2]).toDouble(),
                            (e.rawY - trail[n - 1]).toDouble()
                        )
                        if (d > dp(8).toDouble()) {
                            trail.add(e.rawX)
                            trail.add(e.rawY)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val upX = e.rawX
                    val upY = e.rawY
                    val held = e.eventTime - e.downTime
                    val dist = Math.hypot((upX - downX).toDouble(), (upY - downY).toDouble())
                    if (dist < dp(24).toDouble()) {
                        addTap(downX.toInt(), downY.toInt())
                        passThrough(listOf(downX, downY), held)
                    } else {
                        addSwipe(downX.toInt(), downY.toInt(), upX.toInt(), upY.toInt())
                        trail.add(upX)
                        trail.add(upY)
                        passThrough(ArrayList(trail), held)
                    }
                }
            }
            true
        }

        val p = lp(dm.widthPixels, dm.heightPixels)
        p.x = 0
        p.y = 0
        wm.addView(frame, p)
        recordView = frame
        recordLp = p

        rec.text = "■"
        tint(rec, 0xFFB71C1C.toInt())
        bringPanelToFront()
    }

    // record ke time: ungli hatate hi wahi touch asli app par bhi bhej do, taaki app me kaam bhi kare.
    // Is beech record wali parat thodi der ke liye touch ke liye "khuli" ho jati hai.
    private fun passThrough(points: List<Float>, heldMs: Long) {
        val rv = recordView ?: return
        val lpv = recordLp ?: return
        if (points.size < 2) return

        val path = Path()
        path.moveTo(points[0], points[1])
        var i = 2
        while (i + 1 < points.size) {
            path.lineTo(points[i], points[i + 1])
            i += 2
        }
        val d = minOf(maxOf(heldMs, 10L), 3000L)

        lpv.flags = lpv.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(rv, lpv)
        } catch (e: Exception) {
        }

        val restore = Runnable {
            val cur = recordLp
            val v = recordView
            if (cur != null && v != null) {
                cur.flags = cur.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try {
                    wm.updateViewLayout(v, cur)
                } catch (e: Exception) {
                }
            }
        }

        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post(restore)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post(restore)
            }
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, d))
            .build()
        val ok = dispatchGesture(g, cb, null)
        if (!ok) restore.run()
        handler.postDelayed(restore, d + 700)
    }

    private fun stopRecord() {
        if (!recording) return
        recording = false
        recordView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        recordView = null
        recordLp = null
        setAllTouchable(true)
        if (this::rec.isInitialized) {
            rec.text = "●"
            tint(rec, 0xFFD81B60.toInt())
        }
        toast("Record ho gaya: ${actions.size} action")
    }

    // ---------------------------------------------------------------- run / stop

    private fun unitMs(u: Int): Long = when (u) {
        0 -> 1L
        1 -> 1000L
        else -> 60000L
    }

    private fun intervalMs(): Long = maxOf(value * unitMs(unit), 50L)

    // is action se pehle kitna ruke: apna time ho to wahi, nahi to default
    private fun delayOf(a: Action): Long =
        if (a.dv > 0L) maxOf(a.dv * unitMs(a.du), 50L) else intervalMs()

    // ek ek karke, number ke order me. Har point apne time ke baad, apni baar tak (rp), phir agla.
    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!running || actions.isEmpty()) return
            if (paused) {
                handler.postDelayed(this, 300)
                return
            }
            if (idx >= actions.size) idx = 0
            val a = actions[idx]
            val q = a.p2
            if (q == null) {
                tap(a.p1.x + dp(22), a.p1.y + dp(22))
            } else {
                swipeGesture(
                    a.p1.x + dp(22), a.p1.y + dp(22),
                    q.x + dp(22), q.y + dp(22)
                )
            }
            rep++
            if (rep >= a.rp) {
                rep = 0
                idx++
            }
            if (idx >= actions.size) idx = 0
            var wait = delayOf(actions[idx])
            if (q != null) wait = maxOf(wait, SWIPE_MS + 100)
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
            toast("Pehle ● record karo ya + / ↕ se point lagao")
            return
        }
        closeSettings()
        running = true
        idx = 0
        rep = 0
        paused = false
        aiBusy = false
        aiCalls = 0
        aiFails = 0
        basePkg = lastPkg
        setAllTouchable(false)
        play.text = "■"
        handler.postDelayed(tapRunnable, maxOf(300L, delayOf(actions[0])))
    }

    private fun stopRun() {
        if (!running) return
        running = false
        paused = false
        handler.removeCallbacks(tapRunnable)
        setAllTouchable(true)
        if (this::play.isInitialized) play.text = "▶"
    }

    // ---------------------------------------------------------------- dialog helpers

    private fun title(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFF1A237E.toInt())
        t.textSize = 18f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, 0, 0, dp(6))
        return t
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFF455A64.toInt())
        t.textSize = 14f
        t.setPadding(0, dp(10), 0, dp(4))
        return t
    }

    private fun action(text: String, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.textSize = 15f
        t.setPadding(dp(12), dp(11), dp(12), dp(11))
        t.background = rounded(color, 10)
        val l = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        l.setMargins(0, dp(8), 0, 0)
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

    private fun showBox(content: View, topDp: Int, focusable: Boolean = false) {
        val dm = resources.displayMetrics
        val w = minOf(dp(320), dm.widthPixels - dp(32))
        val scroll = ScrollView(this)
        scroll.background = rounded(Color.WHITE, 16)
        scroll.addView(content)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = minOf(content.measuredHeight, (dm.heightPixels * 0.85f).toInt())
        val p = lp(w, h, focusable)
        p.x = (dm.widthPixels - w) / 2
        p.y = dp(topDp)
        wm.addView(scroll, p)
        settingsView = scroll
    }

    // ---------------------------------------------------------------- settings box (keyboard ki zaroorat nahi)

    private fun unitName(u: Int): String = when (u) {
        0 -> "ms"
        1 -> "second"
        else -> "minute"
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    private fun smallBtn(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.textSize = 14f
        t.setPadding(0, dp(10), 0, dp(10))
        t.background = rounded(color, 8)
        val l = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        l.setMargins(dp(2), dp(4), dp(2), dp(4))
        t.layoutParams = l
        t.setOnClickListener { onClick() }
        return t
    }

    private fun loadSetup(s: Setup) {
        value = s.value
        unit = s.unit
        Store.saveInterval(this, value, unit)
        removeMarkers()
        currentId = s.id
        addFromPoints(s.points)
        toast("Load ho gaya: ${s.points.size} action")
    }

    // ---------------------------------------------------------------- point ki apni setting (gole par tap karke)

    private fun saveCurrent(asNew: Boolean): String {
        val pts = currentPoints()
        val cid = currentId
        if (!asNew && cid != null && Store.exists(this, cid)) {
            Store.update(this, cid, value, unit, pts)
            return cid
        }
        val id = Store.save(this, value, unit, pts)
        currentId = id
        return id
    }

    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip
            if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(this).toString() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractId(text: String?): String? {
        if (text == null) return null
        return Regex("\\b[A-HJ-NP-Z2-9]{6}\\b").find(text.uppercase())?.value
    }

    private fun openPointSettings(v: View) {
        if (running || recording) {
            toast("Pehle stop karo")
            return
        }
        val i = actions.indexOfFirst { it.m1 === v || it.m2 === v }
        if (i < 0) return
        val a = actions[i]
        closeSettings()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(16))
        val kind = if (a.m2 == null) "tap" else "swipe"
        box.addView(title("Point " + (i + 1) + " (" + kind + ")"))
        box.addView(label("Is point se pehle kitna ruke, phir ye hoga"))

        val shown = TextView(this)
        shown.textSize = 22f
        shown.typeface = Typeface.DEFAULT_BOLD
        shown.setTextColor(0xFF1565C0.toInt())
        shown.gravity = Gravity.CENTER
        shown.setPadding(0, dp(4), 0, dp(4))
        fun refreshShown() {
            if (a.dv <= 0L) {
                shown.text = "Default: " + value + " " + unitName(unit)
            } else {
                shown.text = a.dv.toString() + " " + unitName(a.du)
            }
            refreshLabels()
        }
        refreshShown()
        box.addView(shown)

        fun ensureCustom() {
            if (a.dv <= 0L) {
                a.dv = value
                a.du = unit
            }
        }

        val unitViews = ArrayList<TextView>()
        fun paintUnits() {
            for (k in unitViews.indices) {
                unitViews[k].background = rounded(
                    if (a.dv > 0L && k == a.du) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt(), 8
                )
            }
        }

        fun bump(d: Long) {
            ensureCustom()
            a.dv = maxOf(1L, a.dv + d)
            paintUnits()
            refreshShown()
        }

        val steps = row()
        steps.addView(smallBtn("-10", 0xFF455A64.toInt()) { bump(-10L) })
        steps.addView(smallBtn("-1", 0xFF455A64.toInt()) { bump(-1L) })
        steps.addView(smallBtn("+1", 0xFF455A64.toInt()) { bump(1L) })
        steps.addView(smallBtn("+10", 0xFF455A64.toInt()) { bump(10L) })
        steps.addView(smallBtn("+100", 0xFF455A64.toInt()) { bump(100L) })
        box.addView(steps)

        val unitRow = row()
        for (k in 0..2) {
            val u = smallBtn(unitName(k), 0xFF9E9E9E.toInt()) {
                ensureCustom()
                a.du = k
                paintUnits()
                refreshShown()
            }
            unitViews.add(u)
            unitRow.addView(u)
        }
        paintUnits()
        box.addView(unitRow)

        box.addView(label("Jaldi chunne ke liye"))
        val presets = listOf(
            Triple("100 ms", 100L, 0), Triple("500 ms", 500L, 0),
            Triple("1 sec", 1L, 1), Triple("2 sec", 2L, 1),
            Triple("5 sec", 5L, 1), Triple("10 sec", 10L, 1),
            Triple("30 sec", 30L, 1), Triple("1 min", 1L, 2)
        )
        for (r in 0..1) {
            val pr = row()
            for (k in 0..3) {
                val t = presets[r * 4 + k]
                pr.addView(smallBtn(t.first, 0xFF00897B.toInt()) {
                    a.dv = t.second
                    a.du = t.third
                    paintUnits()
                    refreshShown()
                })
            }
            box.addView(pr)
        }

        val useDefault = action("Default time use karo", 0xFF607D8B.toInt())
        useDefault.setOnClickListener {
            a.dv = 0L
            paintUnits()
            refreshShown()
        }
        box.addView(useDefault)

        box.addView(label("Is point par kitni baar (ek ke baad ek)"))
        val rpRow = row()
        val rpShown = TextView(this)
        rpShown.textSize = 20f
        rpShown.typeface = Typeface.DEFAULT_BOLD
        rpShown.setTextColor(0xFF1565C0.toInt())
        rpShown.gravity = Gravity.CENTER
        val rl = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        rpShown.layoutParams = rl
        fun refreshRp() {
            rpShown.text = "x " + a.rp
            refreshLabels()
        }
        refreshRp()
        rpRow.addView(smallBtn("-", 0xFF455A64.toInt()) {
            a.rp = maxOf(1, a.rp - 1)
            refreshRp()
        })
        rpRow.addView(rpShown)
        rpRow.addView(smallBtn("+", 0xFF455A64.toInt()) {
            a.rp = minOf(99, a.rp + 1)
            refreshRp()
        })
        box.addView(rpRow)

        val msg = TextView(this)
        msg.textSize = 16f
        msg.typeface = Typeface.DEFAULT_BOLD
        msg.setTextColor(0xFF2E7D32.toInt())
        msg.setPadding(0, dp(8), 0, 0)
        msg.visibility = View.GONE
        box.addView(msg)

        val save = action("Save karo (ID me)", 0xFF2E7D32.toInt())
        save.setOnClickListener {
            val id = saveCurrent(false)
            msg.text = "Save ho gaya. ID: " + id
            msg.visibility = View.VISIBLE
        }
        box.addView(save)

        val del = action("Ye point hatao", 0xFFC62828.toInt())
        del.setOnClickListener {
            closeSettings()
            removeActionAt(i)
        }
        box.addView(del)

        val done = action("Theek hai", 0xFF616161.toInt())
        done.setOnClickListener {
            refreshLabels()
            closeSettings()
        }
        box.addView(done)

        showBox(box, 40)
    }

    // ---------------------------------------------------------------- ID box (save + ID daalna, ek hi jagah)

    private fun keyBtn(text: String, onClick: () -> Unit): TextView {
        val t = smallBtn(text, 0xFF607D8B.toInt(), onClick)
        t.setPadding(0, dp(8), 0, dp(8))
        t.textSize = 15f
        return t
    }

    private fun toggleIdBox() {
        if (settingsView != null) {
            closeSettings()
            return
        }
        if (running || recording) {
            toast("Pehle stop karo")
            return
        }
        openIdBox(null, true)
    }

    private fun openIdBox(msg: String?, ok: Boolean) {
        typedId.setLength(0)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(16))
        box.addView(title("ID: save aur load"))

        if (msg != null) {
            val m = TextView(this)
            m.text = msg
            m.textSize = 18f
            m.typeface = Typeface.DEFAULT_BOLD
            m.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            m.setPadding(0, dp(4), 0, dp(4))
            box.addView(m)
        }

        // ---- save
        val cid = currentId
        val canUpdate = cid != null && Store.exists(this, cid)
        val save = action(
            if (canUpdate) "Save karo (ID " + cid + " me update)" else "Save karo (naya ID banao)",
            0xFF2E7D32.toInt()
        )
        save.setOnClickListener {
            if (actions.isEmpty()) {
                handler.post {
                    closeSettings()
                    openIdBox("Pehle koi tap ya swipe lagao, tabhi save hoga.", false)
                }
            } else {
                val id = saveCurrent(false)
                handler.post {
                    closeSettings()
                    openIdBox("Save ho gaya. ID: " + id, true)
                }
            }
        }
        box.addView(save)

        if (canUpdate) {
            val saveNew = action("Naya ID banao", 0xFF00897B.toInt())
            saveNew.setOnClickListener {
                if (actions.isEmpty()) {
                    handler.post {
                        closeSettings()
                        openIdBox("Pehle koi tap ya swipe lagao, tabhi save hoga.", false)
                    }
                } else {
                    val id = saveCurrent(true)
                    handler.post {
                        closeSettings()
                        openIdBox("Naya ID bana: " + id, true)
                    }
                }
            }
            box.addView(saveNew)
        }

        // ---- ID daalo (screen ka apna keypad ya seedha Paste)
        box.addView(label("ID daalo (keypad se ya Paste se)"))
        val disp = TextView(this)
        disp.textSize = 22f
        disp.typeface = Typeface.DEFAULT_BOLD
        disp.setTextColor(0xFF1565C0.toInt())
        disp.gravity = Gravity.CENTER
        disp.setPadding(0, dp(4), 0, dp(4))
        fun refreshDisp() {
            if (typedId.isEmpty()) {
                disp.text = "- - - - - -"
            } else {
                disp.text = typedId.toString().toCharArray().joinToString(" ")
            }
        }
        refreshDisp()
        box.addView(disp)

        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        for (r in 0 until 4) {
            val kr = row()
            for (k in 0 until 8) {
                val ch = chars[r * 8 + k].toString()
                kr.addView(keyBtn(ch) {
                    if (typedId.length < 6) {
                        typedId.append(ch)
                        refreshDisp()
                    }
                })
            }
            box.addView(kr)
        }

        val ctl = row()
        ctl.addView(smallBtn("⌫", 0xFF455A64.toInt()) {
            if (typedId.isNotEmpty()) {
                typedId.setLength(typedId.length - 1)
                refreshDisp()
            }
        })
        ctl.addView(smallBtn("Paste", 0xFF00897B.toInt()) {
            val found = extractId(readClipboard())
            if (found == null) {
                toast("Clipboard me ID nahi mili. ID copy karke dobara Paste dabao.")
            } else {
                typedId.setLength(0)
                typedId.append(found)
                refreshDisp()
                toast("Paste ho gaya. Ab Load karo dabao.")
            }
        })
        ctl.addView(smallBtn("Load karo", 0xFF1976D2.toInt()) {
            val s = Store.load(this, typedId.toString())
            if (s == null) {
                handler.post {
                    closeSettings()
                    openIdBox("Ye ID nahi mili.", false)
                }
            } else {
                handler.post {
                    loadSetup(s)
                    closeSettings()
                }
            }
        })
        box.addView(ctl)

        // ---- saved list (tap karke load)
        box.addView(label("Saved IDs (tap karke load karo)"))
        val list = Store.list(this)
        if (list.isEmpty()) {
            val e = TextView(this)
            e.text = "Abhi koi save nahi hai."
            e.setTextColor(0xFF607D8B.toInt())
            box.addView(e)
        } else {
            for (s in list) {
                val r = row()
                r.gravity = Gravity.CENTER_VERTICAL

                val info = TextView(this)
                info.text = s.id + "   " + s.points.size + " action, har " + s.value + " " + unitName(s.unit)
                info.setTextColor(0xFF263238.toInt())
                info.textSize = 14f
                info.setPadding(dp(10), dp(10), dp(10), dp(10))
                info.background = rounded(0xFFE3F2FD.toInt(), 8)
                val il = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                il.setMargins(0, dp(4), dp(6), dp(4))
                info.layoutParams = il
                info.setOnClickListener {
                    handler.post {
                        loadSetup(s)
                        closeSettings()
                    }
                }

                val copy = TextView(this)
                copy.text = "Copy"
                copy.textSize = 13f
                copy.setTextColor(Color.WHITE)
                copy.setPadding(dp(10), dp(10), dp(10), dp(10))
                copy.background = rounded(0xFF00897B.toInt(), 8)
                val cl = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                cl.setMargins(0, 0, dp(6), 0)
                copy.layoutParams = cl
                copy.setOnClickListener {
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AutoTap ID", s.id))
                        toast("ID copy ho gayi: " + s.id)
                    } catch (e: Exception) {
                        toast("Copy nahi ho paya")
                    }
                }

                val del = TextView(this)
                del.text = "Hatao"
                del.textSize = 13f
                del.setTextColor(Color.WHITE)
                del.setPadding(dp(10), dp(10), dp(10), dp(10))
                del.background = rounded(0xFFC62828.toInt(), 8)
                del.setOnClickListener {
                    Store.delete(this, s.id)
                    if (currentId == s.id) currentId = null
                    handler.post {
                        closeSettings()
                        openIdBox("ID " + s.id + " hata di.", true)
                    }
                }

                r.addView(info)
                r.addView(copy)
                r.addView(del)
                box.addView(r)
            }
        }

        val done = action("Band karo", 0xFF616161.toInt())
        done.setOnClickListener { closeSettings() }
        box.addView(done)

        showBox(box, 40, true)
    }

    private fun toggleSettings() {
        if (settingsView != null) {
            closeSettings()
            return
        }
        if (running || recording) {
            toast("Pehle stop karo")
            return
        }
        openSettings(null, true)
    }

    private fun openSettings(msg: String?, ok: Boolean) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(16))

        box.addView(title("Time settings"))
        box.addView(label("Har action (tap/swipe) ke beech ka time"))

        // ---- abhi ka time (badi likhawat me)
        val shown = TextView(this)
        shown.textSize = 24f
        shown.typeface = Typeface.DEFAULT_BOLD
        shown.setTextColor(0xFF1565C0.toInt())
        shown.gravity = Gravity.CENTER
        shown.setPadding(0, dp(4), 0, dp(4))
        fun refreshShown() {
            shown.text = value.toString() + " " + unitName(unit)
        }
        refreshShown()
        box.addView(shown)

        // ---- +/- buttons
        fun bump(d: Long) {
            value = maxOf(1L, value + d)
            Store.saveInterval(this, value, unit)
            refreshShown()
        }
        val steps = row()
        steps.addView(smallBtn("-10", 0xFF455A64.toInt()) { bump(-10L) })
        steps.addView(smallBtn("-1", 0xFF455A64.toInt()) { bump(-1L) })
        steps.addView(smallBtn("+1", 0xFF455A64.toInt()) { bump(1L) })
        steps.addView(smallBtn("+10", 0xFF455A64.toInt()) { bump(10L) })
        steps.addView(smallBtn("+100", 0xFF455A64.toInt()) { bump(100L) })
        box.addView(steps)

        // ---- ms / second / minute
        val unitViews = ArrayList<TextView>()
        fun paintUnits() {
            for (i in unitViews.indices) {
                unitViews[i].background = rounded(
                    if (i == unit) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt(), 8
                )
            }
        }
        val unitRow = row()
        for (i in 0..2) {
            val u = smallBtn(unitName(i), 0xFF9E9E9E.toInt()) {
                unit = i
                Store.saveInterval(this, value, unit)
                paintUnits()
                refreshShown()
            }
            unitViews.add(u)
            unitRow.addView(u)
        }
        paintUnits()
        box.addView(unitRow)

        // ---- jaldi chunne wale
        box.addView(label("Jaldi chunne ke liye"))
        val presets = listOf(
            Triple("100 ms", 100L, 0), Triple("500 ms", 500L, 0),
            Triple("1 sec", 1L, 1), Triple("2 sec", 2L, 1),
            Triple("5 sec", 5L, 1), Triple("10 sec", 10L, 1),
            Triple("30 sec", 30L, 1), Triple("1 min", 1L, 2)
        )
        for (r in 0..1) {
            val pr = row()
            for (k in 0..3) {
                val t = presets[r * 4 + k]
                pr.addView(smallBtn(t.first, 0xFF00897B.toInt()) {
                    value = t.second
                    unit = t.third
                    Store.saveInterval(this, value, unit)
                    paintUnits()
                    refreshShown()
                })
            }
            box.addView(pr)
        }

        val clear = action("Saare points/lines hatao", 0xFFF57C00.toInt())
        clear.setOnClickListener {
            removeMarkers()
            currentId = null
            toast("Sab hat gaya")
        }
        box.addView(clear)

        val done = action("Band karo", 0xFF616161.toInt())
        done.setOnClickListener { closeSettings() }
        box.addView(done)

        showBox(box, 50)
    }
}
