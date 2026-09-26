package com.lana.kitchen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {
  private val SITE = "https://lana-snacks.menutek.pro"
  private lateinit var web: WebView
  private lateinit var status: TextView
  private var loop: PrintLoop? = null
  private val prefs by lazy { getSharedPreferences("lana", MODE_PRIVATE) }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val root = FrameLayout(this)
    web = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      webViewClient = WebViewClient(); webChromeClient = WebChromeClient()
      loadUrl("$SITE/kitchen")
    }
    root.addView(web)
    status = TextView(this).apply {
      text = "⚙ الطابعة"; setTextColor(Color.WHITE); textSize = 12f
      setBackgroundColor(Color.parseColor("#CC333333")); setPadding(24, 12, 24, 12)
      setOnClickListener { openSettings() }
    }
    root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(16, 16, 16, 16) })
    setContentView(root)

    if (prefs.getString("token", null).isNullOrBlank()) openSettings()
    loop = PrintLoop(
      SITE,
      this,
      { prefs.getString("token", null) },
      { prefs.getString("mode", "lan") ?: "lan" },
      { prefs.getString("ip", null) },
      { prefs.getInt("port", 9100) },
    ) { s -> runOnUiThread { status.text = "⚙ $s" } }.also { it.start() }

    // إذا الوضع USB والطابعة بلا إذن، اطلب الإذن تلقائياً
    if ((prefs.getString("mode", "lan") ?: "lan") == "usb") ensureUsbPermission()
  }

  private fun ensureUsbPermission() {
    UsbPrinter.requestPermission(this) { ok, name ->
      runOnUiThread {
        status.text = if (ok) "⚙ USB: $name" else "⚙ وصّل الطابعة USB ثم اضغط ⚙"
      }
    }
  }

  private fun openSettings() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
    val tokenField = EditText(this).apply { hint = "رمز الطابعة من لوحة الإدارة"; setText(prefs.getString("token", "")) }

    val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    val rbLan = RadioButton(this).apply { text = "عبر الشبكة (واي فاي) — الطابعة موصولة بالراوتر" }
    val rbUsb = RadioButton(this).apply { text = "عبر كابل USB (مع كابل OTG)" }
    modeGroup.addView(rbLan); modeGroup.addView(rbUsb)
    if ((prefs.getString("mode", "lan") ?: "lan") == "usb") modeGroup.check(rbUsb.id) else modeGroup.check(rbLan.id)

    val ipField = EditText(this).apply { hint = "عنوان الطابعة IP مثل 192.168.1.50"; setText(prefs.getString("ip", "")) }
    val portField = EditText(this).apply { hint = "المنفذ (افتراضي 9100)"; setText(prefs.getInt("port", 9100).toString()) }

    box.addView(tokenField)
    box.addView(TextView(this).apply { text = "طريقة التوصيل:"; setPadding(0, 24, 0, 8) })
    box.addView(modeGroup)
    box.addView(ipField); box.addView(portField)

    AlertDialog.Builder(this)
      .setTitle("إعداد الطابعة — إصدار 1.1")
      .setView(box)
      .setPositiveButton("حفظ") { _, _ ->
        val usb = modeGroup.checkedRadioButtonId == rbUsb.id
        prefs.edit()
          .putString("token", tokenField.text.toString().trim())
          .putString("mode", if (usb) "usb" else "lan")
          .putString("ip", ipField.text.toString().trim())
          .putInt("port", portField.text.toString().trim().toIntOrNull() ?: 9100)
          .apply()
        if (usb) ensureUsbPermission()
      }
      .setNeutralButton("إلغاء", null)
      .show()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

  override fun onDestroy() { loop?.running = false; loop?.interrupt(); super.onDestroy() }
}
