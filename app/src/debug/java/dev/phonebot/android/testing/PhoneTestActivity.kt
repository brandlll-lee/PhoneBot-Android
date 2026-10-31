package dev.phonebot.android.testing

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
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
        val layout = LinearLayout(this).apply { id = android.R.id.list; orientation = LinearLayout.VERTICAL; setPadding(40, 120, 40, 40); setBackgroundColor(0xFFFFFFFF.toInt()) }
        layout.addView(TextView(this).apply { text = "PhoneBot 中文测试 Hello"; textSize = 24f; setTextColor(0xFF111111.toInt()) })
        layout.addView(EditText(this).apply { id = android.R.id.edit; hint = "输入文字"; contentDescription = "中文输入框"; setTextColor(0xFF111111.toInt()); setHintTextColor(0xFF666666.toInt()) })
        val result = TextView(this).apply { text = "等待点击"; textSize = 22f; setTextColor(0xFF111111.toInt()) }
        layout.addView(Button(this).apply { text = "确认点击"; setOnClickListener { result.text = "点击成功" } })
        layout.addView(result)
        val loaded = TextView(this).apply { id = android.R.id.text1; textSize = 18f; setTextColor(0xFF111111.toInt()) }
        layout.addView(loaded)
        layout.addView(Button(this).apply { text = "延迟加载"; setOnClickListener {
            loaded.text = ""
            loaded.postDelayed({ loaded.text = "异步结果" }, 1100)
        } })
        layout.addView(TextView(this).apply { text = "屏幕识别 OCR English 123"; textSize = 24f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; setTextColor(0xFF111111.toInt()) })
        repeat(2) { layout.addView(Button(this).apply { text = "重复目标" }) }
        layout.addView(Button(this).apply { text = "显示弹窗"; setOnClickListener { AlertDialog.Builder(this@PhoneTestActivity).setTitle("测试弹窗").setPositiveButton("关闭", null).show() } })
        layout.addView(Button(this).apply { text = "打开系统设置"; setOnClickListener { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } })
        layout.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        layout.addView(Button(this).apply { text = "底部按钮"; setOnClickListener { result.text = "底部点击成功" } })
        setContentView(layout)
    }
}
