package com.autotap.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var savedBox: LinearLayout
    private lateinit var hiddenInfo: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun lpMatch(top: Int): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        l.setMargins(0, dp(top), 0, 0)
        return l
    }

    private fun button(text: String, color: Int, size: Float, padding: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = size
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(padding), dp(16), dp(padding))
        t.background = rounded(color, 14)
        t.layoutParams = lpMatch(12)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.background = rounded(Color.WHITE, 14)
        c.layoutParams = lpMatch(14)
        return c
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 17f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(0xFF1A237E.toInt())
        return t
    }

    private fun half(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(4), dp(12), dp(4), dp(12))
        t.background = rounded(color, 10)
        val l = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l.setMargins(dp(3), dp(10), dp(3), 0)
        t.layoutParams = l
        t.setOnClickListener { onClick() }
        return t
    }

    private fun changeHidden(d: Int) {
        val n = maxOf(0, minOf(Store.REMOVABLE, Store.hiddenCount(this) + d))
        Store.setHiddenCount(this, n)
        AutoTapService.instance?.applyHidden()
        refresh()
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(24))

        // ---- header (logo + naam)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(18), dp(18), dp(18), dp(18))
        header.background = rounded(0xFF1565C0.toInt(), 18)

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logo.layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))

        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(dp(16), 0, 0, 0)
        val title = TextView(this)
        title.text = "AutoTap"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)
        val sub = TextView(this)
        sub.text = "Auto tap + swipe, floating panel ke saath"
        sub.textSize = 13f
        sub.setTextColor(0xFFBBDEFB.toInt())
        names.addView(title)
        names.addView(sub)

        header.addView(logo)
        header.addView(names)
        root.addView(header)

        // ---- status
        status = TextView(this)
        status.textSize = 16f
        status.typeface = Typeface.DEFAULT_BOLD
        status.setPadding(dp(16), dp(14), dp(16), dp(14))
        status.layoutParams = lpMatch(14)
        root.addView(status)

        // ---- START : floating aa jayega
        root.addView(button("▶   START  (floating dikhao)", 0xFF2E7D32.toInt(), 20f, 20) {
            val s = AutoTapService.instance
            if (s == null) {
                Toast.makeText(
                    this,
                    "Pehle Accessibility me AutoTap ON karo, phir wapas aakar START dabao",
                    Toast.LENGTH_LONG
                ).show()
                openAccessibility()
            } else {
                s.showPanel()
                refresh()
                // app peeche chala jaye taaki floating dusre app ke upar saaf dikhe
                moveTaskToBack(true)
            }
        })

        // ---- STOP : turant sab band
        root.addView(button("■   STOP  (floating turant band)", 0xFFC62828.toInt(), 20f, 20) {
            val s = AutoTapService.instance
            if (s == null) {
                Toast.makeText(this, "Floating pehle se band hai", Toast.LENGTH_SHORT).show()
            } else {
                s.shutNow()
                Toast.makeText(this, "Floating band ho gaya", Toast.LENGTH_SHORT).show()
            }
            refresh()
        })

        // ---- chhote buttons
        root.addView(button("Accessibility settings (ON / OFF yahan se)", 0xFF1976D2.toInt(), 15f, 12) {
            openAccessibility()
        })
        root.addView(button("Restricted setting allow karo (agar aaye)", 0xFF6A1B9A.toInt(), 15f, 12) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        })

        // ---- floating ke buttons kam / zyada
        val bcard = card()
        bcard.addView(heading("Floating ke buttons"))
        hiddenInfo = TextView(this)
        hiddenInfo.setPadding(0, dp(6), 0, 0)
        hiddenInfo.textSize = 14f
        hiddenInfo.setTextColor(0xFF263238.toInt())
        bcard.addView(hiddenInfo)
        val brow = LinearLayout(this)
        brow.orientation = LinearLayout.HORIZONTAL
        brow.addView(half("−  ek kam", 0xFFF57C00.toInt()) { changeHidden(1) })
        brow.addView(half("+  ek wapas", 0xFF2E7D32.toInt()) { changeHidden(-1) })
        brow.addView(half("Sab wapas", 0xFF1976D2.toInt()) { changeHidden(-100) })
        bcard.addView(brow)
        root.addView(bcard)

        // ---- legend
        val legend = card()
        legend.addView(heading("Floating panel ke buttons"))
        val lt = TextView(this)
        lt.setPadding(0, dp(8), 0, 0)
        lt.textSize = 15f
        lt.setTextColor(0xFF263238.toInt())
        lt.text = "Logo   pakad kar hilao, ek baar tap karo to chhota ho jayega\n" +
            "▶   start / stop (infinite loop)\n" +
            "●   record: screen par tap ya swipe karo, wahi save ho jayega\n" +
            "+   ek tap point lagao\n" +
            "↕   swipe line lagao (S se E tak, kisi bhi disha me)\n" +
            "👁   sab chhupa do, kaam chalta rahe (chhota bindu dabao to wapas)\n" +
            "−   ek button chhupao (baar-baar dabao), lamba dabao to sab wapas\n" +
            "💾   ID: save karo ya ID daalkar load karo\n" +
            "⚙   sirf time set (+/- aur jaldi wale buttons)\n" +
            "✕   band: chhupao / panel permanent band / poora band\n\n" +
            "Kuch bhi apne aap nahi hota. Sab aapke button dabane se hota hai."
        legend.addView(lt)
        root.addView(legend)

        // ---- saved
        val savedCard = card()
        savedCard.addView(heading("Saved setups (ID)"))
        savedBox = LinearLayout(this)
        savedBox.orientation = LinearLayout.VERTICAL
        savedCard.addView(savedBox)
        root.addView(savedCard)

        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFFF3F6FA.toInt())
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        hiddenInfo.text = "Chhupe hue button: " + Store.hiddenCount(this) + " / " + Store.REMOVABLE +
            "\nFloating me − dabate jao to ek-ek karke button chhupte hain."
        if (AutoTapService.instance == null) {
            status.text = "●  Band hai. Accessibility me AutoTap ON karo, phir START dabao."
            status.setTextColor(0xFFB71C1C.toInt())
            status.background = rounded(0xFFFFCDD2.toInt(), 12)
        } else if (AutoTapService.instance?.isPanelShown() != true) {
            status.text = "●  Accessibility chalu hai, floating abhi band hai. START dabao."
            status.setTextColor(0xFFE65100.toInt())
            status.background = rounded(0xFFFFE0B2.toInt(), 12)
        } else {
            status.text = "●  Floating chalu hai."
            status.setTextColor(0xFF1B5E20.toInt())
            status.background = rounded(0xFFC8E6C9.toInt(), 12)
        }

        savedBox.removeAllViews()
        val list = Store.list(this)
        val units = arrayOf("ms", "sec", "min")
        if (list.isEmpty()) {
            val e = TextView(this)
            e.text = "Abhi koi setup save nahi hai."
            e.setPadding(0, dp(8), 0, 0)
            savedBox.addView(e)
            return
        }
        for (s in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), 0, 0)

            val info = TextView(this)
            info.text = s.id + "\n" + s.points.size + " action, har " + s.value + " " + units[s.unit]
            info.textSize = 15f
            info.setTextColor(0xFF263238.toInt())
            info.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val del = TextView(this)
            del.text = "Hatao"
            del.setTextColor(Color.WHITE)
            del.setPadding(dp(14), dp(8), dp(14), dp(8))
            del.background = rounded(0xFFC62828.toInt(), 10)
            del.setOnClickListener {
                Store.delete(this, s.id)
                refresh()
            }

            row.addView(info)
            row.addView(del)
            savedBox.addView(row)
        }
    }
}
