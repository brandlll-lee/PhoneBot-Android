package dev.phonebot.android

import android.app.Activity
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("AppCompatCustomView")
class ParallelFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 120, 40, 40)
            addView(TextView(this@ParallelFixtureActivity).apply { text = getString(android.R.string.dialog_alert_title) })
            addView(object : EditText(this@ParallelFixtureActivity) {
                override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
                    if (intent.getBooleanExtra("pasteInput", false) && action == AccessibilityNodeInfo.ACTION_SET_TEXT) false
                    else super.performAccessibilityAction(action, arguments)
            }.apply { id = android.R.id.edit; setText(getString(android.R.string.untitled)) })
        })
    }
}
