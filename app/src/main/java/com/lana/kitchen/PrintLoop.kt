package com.lana.kitchen

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** يسحب مهام الطباعة من الموقع كل 3 ثوانٍ ويطبعها: عبر الشبكة (IP:9100) أو كابل USB */
class PrintLoop(
  private val baseUrl: String,
  private val context: Context,
  private val token: () -> String?,
  private val mode: () -> String, // "lan" أو "usb"
  private val printerIp: () -> String?,
  private val printerPort: () -> Int,
  private val onStatus: (String) -> Unit,
) : Thread("lana-print") {
  @Volatile var running = true

  private fun api(path: String, body: JSONObject): JSONObject {
    val c = URL("$baseUrl/api/public/print/$path").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 20000
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    if (c.responseCode !in 200..299) throw Exception("$path HTTP ${c.responseCode}")
    return JSONObject(c.inputStream.bufferedReader().readText())
  }

  private fun sendLan(ip: String, port: Int, data: ByteArray) {
    val sock = Socket()
    try {
      sock.connect(InetSocketAddress(ip, port), 5000)
      sock.soTimeout = 5000
      val os = sock.getOutputStream()
      var i = 0
      while (i < data.size) { val n = minOf(4096, data.size - i); os.write(data, i, n); os.flush(); i += n; sleep(10) }
      sleep(800)
    } finally { try { sock.close() } catch (_: Exception) {} }
  }

  private fun send(data: ByteArray) {
    if (mode() == "usb") UsbPrinter.send(context, data) else sendLan(printerIp()!!, printerPort(), data)
  }

  override fun run() {
    while (running) {
      val t = token()
      if (t.isNullOrBlank()) { onStatus("أدخل رمز الطابعة من ⚙"); sleep(3000); continue }
      if (mode() == "lan" && printerIp().isNullOrBlank()) { onStatus("أدخل عنوان الطابعة IP من ⚙"); sleep(3000); continue }
      if (mode() == "usb" && UsbPrinter.connected(context) == null) { onStatus("وصّل الطابعة بكابل USB ثم اضغط ⚙"); sleep(3000); continue }
      try {
        val res = api("claim", JSONObject().put("token", t))
        val job = res.optJSONObject("job")
        if (job != null && res.has("ticket")) {
          val id = job.getString("id")
          try {
            val bytes = TicketRenderer.toEscPos(TicketRenderer.render(res.getJSONObject("ticket")))
            send(bytes)
            api("report", JSONObject().put("token", t).put("job_id", id).put("status", "printed"))
            onStatus("✓ تمت الطباعة")
          } catch (e: Exception) {
            api("report", JSONObject().put("token", t).put("job_id", id).put("status", "failed").put("error_message", "طباعة: ${e.message}"))
            onStatus("✗ فشل: ${e.message}")
          }
          continue
        }
        onStatus("● الطابعة جاهزة")
      } catch (e: Exception) { onStatus("✗ لا اتصال: ${e.message}") }
      try { sleep(3000) } catch (_: InterruptedException) {}
    }
  }
}
