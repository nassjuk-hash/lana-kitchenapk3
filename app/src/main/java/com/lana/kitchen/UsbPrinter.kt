package com.lana.kitchen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** الطباعة عبر كابل USB (OTG) مباشرة إلى الطابعة الحرارية */
object UsbPrinter {
  private const val ACTION_PERMISSION = "com.lana.kitchen.USB_PERMISSION"

  fun manager(ctx: Context) = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

  /** يبحث عن الطابعة: أولاً جهاز بطبقة Printer (صنف 7)، وإلا أي جهاز عنده قناة إرسال Bulk */
  fun find(ctx: Context): UsbDevice? {
    val list = manager(ctx).deviceList.values.toList()
    if (list.isEmpty()) return null
    list.firstOrNull { d -> hasPrinterInterface(d) }?.let { return it }
    return list.firstOrNull { d -> hasBulkOut(d) }
  }

  private fun endpoints(d: UsbDevice) = buildList {
    for (i in 0 until d.interfaceCount) {
      val inf = d.getInterface(i)
      for (j in 0 until inf.endpointCount) add(inf.getEndpoint(j) to inf)
    }
  }

  private fun hasPrinterInterface(d: UsbDevice) =
    endpoints(d).any { it.second.interfaceClass == UsbConstants.USB_CLASS_PRINTER }

  private fun hasBulkOut(d: UsbDevice) =
    endpoints(d).any { it.first.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.first.direction == UsbConstants.USB_DIR_OUT }

  /** الطابعة موصولة وعليها إذن */
  fun connected(ctx: Context): UsbDevice? {
    val dev = find(ctx) ?: return null
    return if (manager(ctx).hasPermission(dev)) dev else null
  }

  /** يطلب إذن الوصول للطابعة من النظام (يظهر مربع حوار على التابلت) */
  fun requestPermission(ctx: Context, onResult: (Boolean, String) -> Unit) {
    val dev = find(ctx)
    if (dev == null) { onResult(false, "لا يوجد جهاز موصول بكابل USB"); return }
    val m = manager(ctx)
    if (m.hasPermission(dev)) { onResult(true, deviceName(dev)); return }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        onResult(ok, if (ok) deviceName(dev) else "لم يُمنح الإذن للطابعة")
      }
    }
    if (Build.VERSION.SDK_INT >= 33)
      ctx.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
    else ctx.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION))
    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    m.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags))
  }

  fun deviceName(d: UsbDevice): String {
    val name = d.productName
    return if (name.isNullOrBlank()) "طابعة USB" else name
  }

  /** يرسل أوامر الطباعة للطابعة عبر USB */
  fun send(ctx: Context, data: ByteArray) {
    val dev = connected(ctx) ?: throw Exception("الطابعة غير موصولة عبر USB أو لم يُمنح الإذن")
    val conn = manager(ctx).openDevice(dev) ?: throw Exception("تعذّر فتح الطابعة")
    try {
      var claimed = false
      for (pair in endpoints(dev)) {
        val (ep, inf) = pair
        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK || ep.direction != UsbConstants.USB_DIR_OUT) continue
        if (!conn.claimInterface(inf, true)) continue
        claimed = true
        var off = 0
        while (off < data.size) {
          val n = minOf(4096, data.size - off)
          val r = conn.bulkTransfer(ep, data, off, n, 3000)
          if (r < 0) throw Exception("فشل إرسال البيانات للطابعة")
          off += r
          Thread.sleep(10)
        }
        Thread.sleep(800)
        break
      }
      if (!claimed) throw Exception("لم يتم العثور على قناة طباعة في الطابعة")
    } finally {
      try { conn.close() } catch (_: Exception) {}
    }
  }
}
