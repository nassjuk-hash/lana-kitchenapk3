package com.lana.kitchen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject

/** يرسم تذكرة المطبخ بالعربي كصورة بعرض 80mm (576 نقطة) */
object TicketRenderer {
  private const val WIDTH = 576
  private const val PAD = 8

  private class Block(val text: String, val size: Float, val bold: Boolean, val align: Layout.Alignment, val divider: Boolean = false)

  private fun money(v: Double, cur: String): String {
    val label = if (cur == "JOD" || cur == "JD") "د.أ" else cur
    return String.format(java.util.Locale.US, "%.2f %s", v, label)
  }

  fun render(ticket: JSONObject): Bitmap {
    val blocks = mutableListOf<Block>()
    val C = Layout.Alignment.ALIGN_CENTER
    val R = Layout.Alignment.ALIGN_NORMAL
    val rest = ticket.getJSONObject("restaurant")
    val cur = rest.optString("currency", "JOD")
    blocks += Block(rest.optString("name", "المطعم"), 40f, true, C)
    if (ticket.optString("kind") == "test") {
      blocks += Block("—", 20f, false, C, true)
      blocks += Block("طباعة تجريبية ✓", 36f, true, C)
      blocks += Block("الطابعة تعمل عبر البلوتوث", 26f, false, C)
    }
    val o = ticket.optJSONObject("order")
    if (o != null) {
      blocks += Block("", 10f, false, C, true)
      blocks += Block("طلب #" + (if (o.isNull("number")) "" else o.get("number").toString()), 48f, true, C)
      blocks += Block(o.optString("type_label"), 32f, true, C)
      blocks += Block(o.optString("received_at"), 24f, false, C)
      blocks += Block("", 10f, false, C, true)
      if (!o.isNull("customer_name")) blocks += Block("العميل: " + o.getString("customer_name"), 28f, true, R)
      if (!o.isNull("customer_phone")) blocks += Block("الهاتف: " + o.getString("customer_phone"), 28f, false, R)
      val addr = o.optJSONArray("address_lines")
      if (addr != null) for (i in 0 until addr.length()) blocks += Block(addr.getString(i), 26f, false, R)
      blocks += Block("", 10f, false, C, true)
      val items = o.optJSONArray("items")
      if (items != null) for (i in 0 until items.length()) {
        val it = items.getJSONObject(i)
        blocks += Block("${it.optInt("quantity")} × ${it.optString("name")}", 32f, true, R)
        val opts = it.optJSONArray("options")
        if (opts != null) for (j in 0 until opts.length()) {
          val op = opts.getJSONObject(j)
          blocks += Block("   • ${op.optString("option_name")}: ${op.optString("value_name")}", 24f, false, R)
        }
        if (!it.isNull("notes") && it.optString("notes").isNotBlank()) blocks += Block("   ملاحظة: " + it.getString("notes"), 24f, false, R)
      }
      if (!o.isNull("notes") && o.optString("notes").isNotBlank()) {
        blocks += Block("", 10f, false, C, true)
        blocks += Block("ملاحظات: " + o.getString("notes"), 28f, true, R)
      }
      blocks += Block("", 10f, false, C, true)
      blocks += Block("المجموع: " + money(o.optDouble("subtotal"), cur), 26f, false, R)
      if (o.optDouble("delivery_fee") > 0) blocks += Block("التوصيل: " + money(o.optDouble("delivery_fee"), cur), 26f, false, R)
      if (o.optDouble("discount") > 0) blocks += Block("الخصم: " + money(o.optDouble("discount"), cur), 26f, false, R)
      blocks += Block("الإجمالي: " + money(o.optDouble("total"), cur), 36f, true, R)
      blocks += Block("الدفع: " + o.optString("payment_label"), 26f, false, R)
    }
    blocks += Block("", 10f, false, C, true)
    blocks += Block("شكراً لكم", 24f, false, C)

    val layouts = blocks.map { b ->
      val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = b.size
        typeface = Typeface.create(Typeface.DEFAULT, if (b.bold) Typeface.BOLD else Typeface.NORMAL)
      }
      b to StaticLayout.Builder.obtain(b.text, 0, b.text.length, p, WIDTH - PAD * 2).setAlignment(b.align).build()
    }
    val height = layouts.sumOf { (b, l) -> l.height + if (b.divider) 14 else 6 } + 40
    val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); c.drawColor(Color.WHITE)
    val line = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
    var y = 10f
    for ((b, l) in layouts) {
      if (b.divider) { c.drawLine(PAD.toFloat(), y + 6, (WIDTH - PAD).toFloat(), y + 6, line); y += 14 }
      c.save(); c.translate(PAD.toFloat(), y); l.draw(c); c.restore()
      y += l.height + 6
    }
    return bmp
  }

  /** تحويل الصورة إلى أوامر ESC/POS (GS v 0) مع قص الورق */
  fun toEscPos(bmp: Bitmap): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(0x1B, 0x40))
    val bytesPerRow = bmp.width / 8
    val px = IntArray(bmp.width)
    var y0 = 0
    while (y0 < bmp.height) {
      val h = minOf(200, bmp.height - y0)
      out.write(byteArrayOf(0x1D, 0x76, 0x30, 0, (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte()))
      for (y in y0 until y0 + h) {
        bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
        for (bx in 0 until bytesPerRow) {
          var v = 0
          for (bit in 0 until 8) {
            val p = px[bx * 8 + bit]
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            if (lum < 140) v = v or (0x80 shr bit)
          }
          out.write(v)
        }
      }
      y0 += h
    }
    out.write(byteArrayOf(0x1B, 0x64, 4, 0x1D, 0x56, 66, 0))
    return out.toByteArray()
  }
}
