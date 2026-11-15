package com.swiftget.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shown instead of a silent crash. Runs in its own process using plain Android views,
 * so it opens even if something in the main app is broken.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace") ?: "No details were recorded."
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF0E1116.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Clipz hit a problem"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Tap Copy details and send the text to me so I can fix it."
            setTextColor(0xFF9DA7B3.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        })
        root.addView(Button(this).apply {
            text = "Copy details"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Clipz crash", trace))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
