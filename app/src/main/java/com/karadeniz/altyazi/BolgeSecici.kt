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

        // Üst panel: başlık ayrı satırda, düğmeler altta eşit genişlikte (dar ekranda kesilmesin)
        val ust = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 0, 0, 0))
            val d = ctx.resources.displayMetrics.density
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        ust.addView(TextView(ctx).apply {
            text = baslikYazi
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        ust.addView(TextView(ctx).apply {
            text = "Köşelerden büyüt/küçült · İçinden sürükleyerek taşı · Boş yerde sürükleyerek yeni alan çiz"
            setTextColor(Color.argb(190, 255, 255, 255))
            textSize = 12f
        })
        val dugmeler = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        dugmeler.addView(Button(ctx).apply {
            text = "Kaydet"
            isAllCaps = false
            setOnClickListener { kapat(); bitti(cizim.bolgeOran()) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        dugmeler.addView(Button(ctx).apply {
            text = "İptal"
            isAllCaps = false
            setOnClickListener { kapat(); bitti(null) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ust.addView(dugmeler)
        // Durum çubuğunun altında başlasın
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val durumCubugu = if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        kok.addView(ust, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = durumCubugu })
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
        /** Dokunuş kutunun içinden başladıysa kutu taşınır, dışından başladıysa yeni kutu çizilir. */
        private var tasiniyor = false
        /** Köşeden tutulduysa hangi köşe: 0 sol-üst, 1 sağ-üst, 2 sol-alt, 3 sağ-alt; -1 yok. */
        private var kose = -1
        private val tutamakYaricap = 26f * ctx.resources.displayMetrics.density
        private val tutamak = Paint().apply { color = Color.rgb(255, 214, 0); style = Paint.Style.FILL; isAntiAlias = true }
        private var ilkOran = RectF()

        fun bolgeOran(): RectF = RectF(oran)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = RectF(oran.left * w, oran.top * h, oran.right * w, oran.bottom * h)
            c.drawRect(0f, 0f, w, r.top, golge)
            c.drawRect(0f, r.bottom, w, h, golge)
            c.drawRect(0f, r.top, r.left, r.bottom, golge)
            c.drawRect(r.right, r.top, w, r.bottom, golge)
            c.drawRect(r, cerceve)
            val ky = tutamakYaricap * 0.45f
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom))
                c.drawCircle(x, y, ky, tutamak)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bx = e.x; by = e.y
                    ilkOran = RectF(oran)
                    val px = RectF(oran.left * w, oran.top * h, oran.right * w, oran.bottom * h)
                    val koseler = listOf(px.left to px.top, px.right to px.top, px.left to px.bottom, px.right to px.bottom)
                    kose = koseler.indexOfFirst { (x, y) -> Math.hypot((e.x - x).toDouble(), (e.y - y).toDouble()) <= tutamakYaricap * 1.6 }
                    tasiniyor = kose < 0 && px.contains(e.x, e.y)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (kose >= 0) {
                    // Köşeden boyutlandır: karşı köşe sabit, en az %3 × %2
                    val x = (e.x / w).coerceIn(0f, 1f); val y = (e.y / h).coerceIn(0f, 1f)
                    var l = ilkOran.left; var t = ilkOran.top; var r = ilkOran.right; var b = ilkOran.bottom
                    if (kose == 0 || kose == 2) l = minOf(x, r - 0.03f) else r = maxOf(x, l + 0.03f)
                    if (kose == 0 || kose == 1) t = minOf(y, b - 0.02f) else b = maxOf(y, t + 0.02f)
                    oran = RectF(l, t, r, b)
                    invalidate()
                } else if (tasiniyor) {
                    // Boyutu koruyarak taşı, ekran dışına taşmasın
                    val dx = ((e.x - bx) / w).coerceIn(-ilkOran.left, 1f - ilkOran.right)
                    val dy = ((e.y - by) / h).coerceIn(-ilkOran.top, 1f - ilkOran.bottom)
                    oran = RectF(ilkOran.left + dx, ilkOran.top + dy, ilkOran.right + dx, ilkOran.bottom + dy)
                    invalidate()
                } else {
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
