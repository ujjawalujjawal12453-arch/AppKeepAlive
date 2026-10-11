package com.autotap.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var savedBox: LinearLayout
    private lateinit var aiToggle: TextView
    private lateinit var aiKeyEdit: EditText
    private lateinit var aiInfo: TextView

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

        // ---- AI madad (Google Gemini)
        val aiCard = card()
        aiCard.addView(heading("AI madad (Google Gemini)"))
        val aiNote = TextView(this)
        aiNote.setPadding(0, dp(6), 0, dp(6))
        aiNote.textSize = 13f
        aiNote.setTextColor(0xFF455A64.toInt())
        aiNote.text = "AI apne aap so raha hota hai. Jab chalte waqt OK / Cancel / Allow jaisa popup aata hai tabhi " +
            "ye uthta hai, screen ki tasveer dekhkar wo button dabata hai, aur popup hatte hi wapas so jata hai.\n\n" +
            "Dhyan: ON hone par tasveer Google ko bheji jati hai (sirf popup aane par). " +
            "Apni key kisi ko mat dena. Android 11 ya naya chahiye."
        aiCard.addView(aiNote)

        aiKeyEdit = EditText(this)
        aiKeyEdit.hint = "Google AI API key"
        aiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        aiKeyEdit.setText(Store.aiKey(this))
        aiCard.addView(aiKeyEdit)

        aiInfo = TextView(this)
        aiInfo.textSize = 13f
        aiInfo.setTextColor(0xFF455A64.toInt())
        aiInfo.setPadding(0, dp(4), 0, 0)
        aiCard.addView(aiInfo)

        val aiRow = LinearLayout(this)
        aiRow.orientation = LinearLayout.HORIZONTAL
        aiRow.addView(half("Paste key", 0xFF00897B.toInt()) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (t.isNullOrBlank()) {
                    Toast.makeText(this, "Clipboard khaali hai. Pehle key copy karo.", Toast.LENGTH_SHORT).show()
                } else {
                    aiKeyEdit.setText(t.trim())
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Paste nahi ho paya", Toast.LENGTH_SHORT).show()
            }
        })
        aiRow.addView(half("Save", 0xFF2E7D32.toInt()) {
            val key = aiKeyEdit.text.toString().trim()
            Store.setAiKey(this, key)
            if (key.isEmpty()) {
                Store.setAiOn(this, false)
                refresh()
            } else {
                Toast.makeText(this, "Key check ho rahi hai...", Toast.LENGTH_SHORT).show()
                Thread {
                    val m = Gemini.pickModel(key)
                    runOnUiThread {
                        if (m != null) {
                            Store.setAiModel(this, m)
                            Store.setAiOn(this, true)
                            Toast.makeText(this, "Key theek hai. Model apne aap chuna: " + m, Toast.LENGTH_LONG).show()
                        } else {
                            Store.setAiOn(this, false)
                            Toast.makeText(this, "Key ya internet check karo, model nahi mila", Toast.LENGTH_LONG).show()
                        }
                        refresh()
                    }
                }.start()
            }
        })
        aiCard.addView(aiRow)

        aiToggle = button("", 0xFF757575.toInt(), 16f, 12) {
            if (Store.isAiOn(this)) {
                Store.setAiOn(this, false)
            } else if (Store.aiKey(this).isBlank() || Store.aiModel(this).isBlank()) {
                Toast.makeText(this, "Pehle key daalo aur Save dabao", Toast.LENGTH_LONG).show()
            } else {
                Store.setAiOn(this, true)
            }
            refresh()
        }
        aiCard.addView(aiToggle)
        root.addView(aiCard)

        // ---- legend
        val legend = card()
        legend.addView(heading("Floating panel ke buttons"))
        val lt = TextView(this)
        lt.setPadding(0, dp(8), 0, 0)
        lt.textSize = 15f
        lt.setTextColor(0xFF263238.toInt())
        lt.text = "Logo   pakad kar hilao, ek baar tap karo to chhota ho jayega\n" +
            "▶   start / stop (infinite loop)\n" +
            "●   record: jahan tap/swipe karoge wahi save hoga aur app me bhi chalega\n" +
            "+   ek tap point lagao\n" +
            "Number wala gola   us par tap karo: uski setting (kitni der baad, kitni baar)\n" +
            "↕   swipe line lagao (S se E tak, kisi bhi disha me)\n" +
            "👁   sab chhupa do, kaam chalta rahe (chhota bindu dabao to wapas)\n" +
            "−   last wala tap ya swipe hatao (baar-baar dabao to ek-ek karke hatenge)\n" +
            "💾   ID: save karo, Paste ya keypad se ID daalo, load karo\n" +
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
        val mdl = Store.aiModel(this)
        aiInfo.text = if (mdl.isBlank()) "Model: abhi chuna nahi (key Save karo to apne aap chunega)"
        else "Model: " + mdl + " (apne aap chuna gaya)"
        if (Store.isAiOn(this)) {
            aiToggle.text = "AI: tayyar, popup aane par apne aap chalega  (dabao to band)"
            aiToggle.background = rounded(0xFF2E7D32.toInt(), 14)
        } else {
            aiToggle.text = "AI: band hai  (dabao to tayyar)"
            aiToggle.background = rounded(0xFF757575.toInt(), 14)
        }
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
