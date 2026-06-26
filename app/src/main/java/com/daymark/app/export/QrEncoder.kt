package com.daymark.app.export

import android.graphics.Canvas
import android.graphics.Paint
import io.nayuki.qrcodegen.QrCode

/** Draws a QR code (Nayuki qrcodegen, MIT, no network) onto a PDF/Android [Canvas]. */
object QrEncoder {

    fun draw(canvas: Canvas, payload: String, left: Float, top: Float, sizePx: Float, darkPaint: Paint) {
        val qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)
        val modules = qr.size
        val cell = sizePx / modules
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (qr.getModule(x, y)) {
                    val l = left + x * cell
                    val t = top + y * cell
                    canvas.drawRect(l, t, l + cell, t + cell, darkPaint)
                }
            }
        }
    }
}
