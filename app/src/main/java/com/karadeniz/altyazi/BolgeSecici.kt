package com.karadeniz.altyazi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tam ekran kaplama: kullanıcı parmağıyla altyazı bandının üstüne dikdörtgen çizer.
 * Remote Play açıkken gerçek oyun görüntüsünün üstünde kullanılır.
 */
class BolgeSecici(
    private val ctx: Context,
    private val wm: WindowManager,
    baslangic: RectF,
    private val baslikYazi: String,
    /** Servisten: TYPE_APPLICATION_OVERLAY; bir Activity içinden: TYPE_APPLICATION. */
    private val pencereTipi: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    private val bitti: (RectF?) -> Unit
) {
    private val kok = FrameLayout(ctx)
    private val cizim = CizimView(ctx, RectF(baslangic))

    init {
        kok.addView(cizim, FrameLayout.LayoutParams(-1, -1))

        val ust = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(24, 12, 24, 12)
        }
        ust.addView(TextView(ctx).apply {
            text = "$baslikYazi  "
            setTextColor(Color.WHITE)
            textSize = 14f
        })
        ust.addView(Button(ctx).apply {
            text = "Kaydet"
            setOnClickListener { kapat(); bitti(cizim.bolgeOran()) }
        })
        ust.addView(Button(ctx).apply {
            text = "İptal"
            setOnClickListener { kapat(); bitti(null) }
        })
        kok.addView(ust, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    fun goster() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            pencereTipi,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(kok, lp)
    }

    private fun kapat() {
        try { wm.removeView(kok) } catch (_: Exception) {}
    }

    private class CizimView(ctx: Context, private var oran: RectF) : View(ctx) {
        private val golge = Paint().apply { color = Color.argb(110, 0, 0, 0) }
        private val cerceve = Paint().apply {
            color = Color.rgb(255, 214, 0); style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private var bx = 0f
        private var by = 0f

        fun bolgeOran(): RectF = RectF(oran)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = RectF(oran.left * w, oran.top * h, oran.right * w, oran.bottom * h)
            c.drawRect(0f, 0f, w, r.top, golge)
            c.drawRect(0f, r.bottom, w, h, golge)
            c.drawRect(0f, r.top, r.left, r.bottom, golge)
            c.drawRect(r.right, r.top, w, r.bottom, golge)
            c.drawRect(r, cerceve)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { bx = e.x; by = e.y }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val l = minOf(bx, e.x) / w; val r = maxOf(bx, e.x) / w
                    val t = minOf(by, e.y) / h; val b = maxOf(by, e.y) / h
                    if (r - l > 0.03f && b - t > 0.02f) {
                        oran = RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
                        invalidate()
                    }
                }
            }
            return true
        }
    }
}
