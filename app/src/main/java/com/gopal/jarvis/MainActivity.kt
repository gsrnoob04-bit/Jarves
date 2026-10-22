package com.gopal.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var logView: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sp = getSharedPreferences("j", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)
        root.setBackgroundColor(Color.parseColor("#0B0F1A"))

        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.textSize = 28f
        title.setTextColor(Color.parseColor("#00E5FF"))
        root.addView(title)

        val key = EditText(this)
        key.hint = "Gemini API key (aistudio.google.com)"
        key.setText(sp.getString("k", ""))
        key.setTextColor(Color.WHITE)
        key.setHintTextColor(Color.GRAY)
        key.isSingleLine = true
        root.addView(key)

        fun btn(t: String, f: () -> Unit) {
            val x = Button(this)
            x.text = t
            x.setOnClickListener { f() }
            root.addView(x)
        }

        btn("1) Save key + Permissions") {
            sp.edit().putString("k", key.text.toString().trim()).apply()
            val p = mutableListOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS
            )
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), 1)
        }
        btn("2) Accessibility ON karo (Jarvis)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3) Overlay permission do") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("START Jarvis") {
            sp.edit().putString("k", key.text.toString().trim()).apply()
            start(null)
        }
        btn("Tap to talk (bina wake word)") { start("talk") }
        btn("STOP") { stopService(Intent(this, JarvisService::class.java)) }

        logView = TextView(this)
        logView.setTextColor(Color.WHITE)
        val sv = ScrollView(this)
        sv.addView(logView)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        JarvisService.log = { s ->
            runOnUiThread {
                logView.append(s + "\n")
                sv.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun start(action: String?) {
        val i = Intent(this, JarvisService::class.java)
        if (action != null) i.action = action
        startForegroundService(i)
    }
}
