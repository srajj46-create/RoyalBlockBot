package com.bot.royalblock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        l.addView(TextView(this).apply {
            textSize = 16f
            text = "1) Neeche button se Accessibility kholo\n2) 'Royal Block Bot' ON karo\n3) Game kholo\n4) Screen ke bottom-left ka gola dabao = START / STOP"
        })
        l.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(l)
    }
}
