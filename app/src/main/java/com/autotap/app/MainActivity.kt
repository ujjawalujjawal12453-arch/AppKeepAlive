package com.autotap.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var saved: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 60, 40, 40)

        val title = TextView(this)
        title.text = "AutoTap"
        title.textSize = 30f

        val steps = TextView(this)
        steps.text = "1) Neeche pehle button se Accessibility me AutoTap ON karo.\n" +
            "   (Agar 'Restricted setting' aaye to teesra button -> teen dot -> Allow restricted settings)\n" +
            "2) Screen par floating panel aa jayega.\n" +
            "3) Panel: ≡ hilao | ▶ start/stop | + tap point | ↕ swipe line | ⚙ time, save, ID load | ✕ band\n" +
            "4) + dabao, laal gola aayega, use jis jagah tap karwana hai wahan kheench kar rakho.\n" +
            "   ↕ dabao: hara S (shuru) aur neela E (end) gola milega, line S se E tak jayegi.\n" +
            "   S aur E ko kheench kar upar/neeche/left/right kisi bhi disha me line bana lo.\n" +
            "5) Interval set karo, Save karo, ID mil jayegi (offline, phone me hi save)."
        steps.setPadding(0, 24, 0, 24)

        val b1 = Button(this)
        b1.text = "Accessibility settings kholo"
        b1.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val b2 = Button(this)
        b2.text = "Panel dikhao"
        b2.setOnClickListener {
            val s = AutoTapService.instance
            if (s == null) {
                Toast.makeText(this, "Pehle Accessibility me AutoTap ON karo", Toast.LENGTH_LONG).show()
            } else {
                s.showPanel()
            }
        }

        val b3 = Button(this)
        b3.text = "App info (restricted settings allow)"
        b3.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        saved = TextView(this)
        saved.setPadding(0, 30, 0, 0)

        root.addView(title)
        root.addView(steps)
        root.addView(b1)
        root.addView(b2)
        root.addView(b3)
        root.addView(saved)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        val list = Store.list(this)
        val units = arrayOf("ms", "sec", "min")
        val sb = StringBuilder("Saved setups (ID):\n")
        if (list.isEmpty()) sb.append("(abhi koi nahi)")
        for (s in list) {
            sb.append(s.id).append("  -  ").append(s.points.size).append(" tap, har ")
                .append(s.value).append(" ").append(units[s.unit]).append("\n")
        }
        saved.text = sb.toString()
    }
}
