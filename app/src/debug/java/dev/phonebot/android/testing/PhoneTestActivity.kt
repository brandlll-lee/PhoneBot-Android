package dev.phonebot.android.testing

import android.app.Activity
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("SetTextI18n")
class PhoneTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 120, 40, 40); setBackgroundColor(0xFFFFFFFF.toInt()) }
        layout.addView(TextView(this).apply { text = "PhoneBot 中文测试 Hello"; textSize = 24f; setTextColor(0xFF111111.toInt()) })
        layout.addView(EditText(this).apply { hint = "输入文字"; contentDescription = "中文输入框"; setTextColor(0xFF111111.toInt()); setHintTextColor(0xFF666666.toInt()) })
        val result = TextView(this).apply { text = "等待点击"; textSize = 22f; setTextColor(0xFF111111.toInt()) }
        layout.addView(Button(this).apply { text = "确认点击"; setOnClickListener { result.text = "点击成功" } })
        layout.addView(result)
        layout.addView(TextView(this).apply { text = "屏幕识别 OCR English 123"; textSize = 24f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; setTextColor(0xFF111111.toInt()) })
        setContentView(layout)
    }
}
