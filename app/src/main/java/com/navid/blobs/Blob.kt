package com.navid.blobs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Blob body shapes. */
object Shapes {
    const val CIRCLE = 0
    const val SQUIRCLE = 1
    const val PILL = 2
    const val TRIANGLE = 3
    const val HEXAGON = 4
    const val CLOUD = 5
    const val BLOB = 6
    const val DROP = 7
    const val COUNT = 8
    val NAMES = arrayOf("Circle", "Rounded", "Pill", "Triangle", "Hexagon", "Cloud", "Blob", "Drop")

    /** Vertical eye position as a fraction of the shape box. */
    fun eyeY(s: Int) = when (s) {
        TRIANGLE -> 0.64f; CLOUD -> 0.58f; DROP -> 0.64f; else -> 0.48f
    }

    /** Corner rounding (fraction of size) for polygon shapes, else 0. */
    fun corner(s: Int) = when (s) { TRIANGLE -> 0.13f; HEXAGON -> 0.10f; else -> 0f }

    fun build(p: Path, shape: Int, l: Float, t: Float, s: Float) {
        p.reset()
        val cx = l + s / 2; val cy = t + s / 2
        when (shape) {
            CIRCLE -> p.addCircle(cx, cy, s * 0.47f, Path.Direction.CW)
            SQUIRCLE -> p.addRoundRect(RectF(l + s * 0.05f, t + s * 0.05f, l + s * 0.95f, t + s * 0.95f), s * 0.3f, s * 0.3f, Path.Direction.CW)
            PILL -> {
                val h = s * 0.62f
                p.addRoundRect(RectF(l + s * 0.02f, cy - h / 2, l + s * 0.98f, cy + h / 2), h / 2, h / 2, Path.Direction.CW)
            }
            TRIANGLE -> {
                p.moveTo(cx, t + s * 0.05f)
                p.lineTo(l + s * 0.98f, t + s * 0.9f)
                p.lineTo(l + s * 0.02f, t + s * 0.9f)
                p.close()
            }
            HEXAGON -> {
                val r = s * 0.49f
                for (i in 0 until 6) {
                    val a = PI / 3 * i - PI / 2
                    val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            CLOUD -> {
                val a = Path(); val b = Path()
                a.addCircle(l + s * 0.30f, t + s * 0.56f, s * 0.24f, Path.Direction.CW)
                b.addCircle(l + s * 0.53f, t + s * 0.40f, s * 0.29f, Path.Direction.CW); a.op(b, Path.Op.UNION)
                b.reset(); b.addCircle(l + s * 0.73f, t + s * 0.58f, s * 0.23f, Path.Direction.CW); a.op(b, Path.Op.UNION)
                b.reset(); b.addRoundRect(RectF(l + s * 0.08f, t + s * 0.52f, l + s * 0.95f, t + s * 0.84f), s * 0.16f, s * 0.16f, Path.Direction.CW)
                a.op(b, Path.Op.UNION)
                p.set(a)
            }
            BLOB -> {
                val radii = floatArrayOf(0.47f, 0.41f, 0.46f, 0.39f, 0.47f, 0.42f, 0.48f, 0.40f)
                val n = radii.size
                val xs = FloatArray(n); val ys = FloatArray(n)
                for (i in 0 until n) {
                    val a = 2 * PI / n * i - PI / 2
                    xs[i] = cx + (s * radii[i] * cos(a)).toFloat()
                    ys[i] = cy + (s * radii[i] * sin(a)).toFloat()
                }
                // closed Catmull-Rom -> cubic Bezier
                p.moveTo(xs[0], ys[0])
                for (i in 0 until n) {
                    val i0 = (i - 1 + n) % n; val i2 = (i + 1) % n; val i3 = (i + 2) % n
                    val c1x = xs[i] + (xs[i2] - xs[i0]) / 6f; val c1y = ys[i] + (ys[i2] - ys[i0]) / 6f
                    val c2x = xs[i2] - (xs[i3] - xs[i]) / 6f; val c2y = ys[i2] - (ys[i3] - ys[i]) / 6f
                    p.cubicTo(c1x, c1y, c2x, c2y, xs[i2], ys[i2])
                }
                p.close()
            }
            DROP -> {
                val r = s * 0.34f; val ccy = t + s * 0.62f
                p.moveTo(cx, t + s * 0.03f)
                p.cubicTo(cx + s * 0.08f, t + s * 0.18f, cx + r, ccy - r * 0.75f, cx + r, ccy)
                p.arcTo(RectF(cx - r, ccy - r, cx + r, ccy + r), 0f, 180f, false)
                p.cubicTo(cx - r, ccy - r * 0.75f, cx - s * 0.08f, t + s * 0.18f, cx, t + s * 0.03f)
                p.close()
            }
        }
    }
}

/** A cute blob avatar: coloured body + two black oval eyes that blink now and then. */
class BlobView(ctx: Context) : View(ctx) {
    var shape = Shapes.CIRCLE
        set(v) { field = v; invalidate() }
    var color = C.SWATCHES[0]
        set(v) { field = v; invalidate() }
    var blinks = true
    /** -1..1 horizontal glance of the eyes. */
    var look = 0f
        set(v) { field = v; invalidate() }

    private var blink = 0f
    private var blinkAnim: ValueAnimator? = null
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111114.toInt() }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 210 }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 46 }
    private val path = Path()
    private val oval = RectF()

    private val blinker = object : Runnable {
        override fun run() {
            doBlink()
            postDelayed(this, 2400L + Random.nextLong(3600))
        }
    }

    fun doBlink() {
        blinkAnim?.cancel()
        val twice = Random.nextInt(5) == 0
        blinkAnim = (if (twice) ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f) else ValueAnimator.ofFloat(0f, 1f, 0f)).apply {
            duration = if (twice) 360 else 170
            interpolator = LinearInterpolator()
            addUpdateListener { blink = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (blinks) postDelayed(blinker, 800L + Random.nextLong(3000))
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinker); blinkAnim?.cancel(); blink = 0f
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val s = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (s <= 0) return
        val l = paddingLeft + (width - paddingLeft - paddingRight - s) / 2
        val t = paddingTop + (height - paddingTop - paddingBottom - s) / 2
        Shapes.build(path, shape, l, t, s)
        val corner = Shapes.corner(shape)
        body.color = color
        body.pathEffect = if (corner > 0) CornerPathEffect(s * corner) else null
        c.drawPath(path, body)
        // soft highlight
        c.save(); c.clipPath(path)
        shine.pathEffect = null
        c.drawCircle(l + s * 0.32f, t + s * 0.26f, s * 0.16f, shine)
        c.restore()
        // eyes
        val cx = l + s / 2 + look * s * 0.06f
        val cy = t + s * Shapes.eyeY(shape)
        val gap = s * 0.13f
        val ew = s * 0.075f
        val eh = s * 0.125f * (1f - 0.88f * blink)
        for (dx in floatArrayOf(-gap, gap)) {
            oval.set(cx + dx - ew, cy - eh, cx + dx + ew, cy + eh)
            c.drawOval(oval, eye)
            if (blink < 0.3f && s > 40) {
                c.drawCircle(cx + dx + ew * 0.3f, cy - eh * 0.45f, ew * 0.32f, glint)
            }
        }
    }
}

/** Three bouncing dots: the "typing" indicator. */
class DotsView(ctx: Context) : View(ctx) {
    var dotColor = C.SUB
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(context.dp(44), context.dp(20))
    }

    override fun onDraw(c: Canvas) {
        val r = context.dpf(3.6f)
        val step = width / 3f
        for (i in 0 until 3) {
            val ph = ((phase - i * 0.16f) % 1f + 1f) % 1f
            val k = if (ph < 0.4f) sin(ph / 0.4f * PI).toFloat() else 0f
            p.color = dotColor
            p.alpha = (110 + 145 * k).toInt()
            c.drawCircle(step * (i + 0.5f), height / 2f + r - k * context.dpf(5f), r, p)
        }
    }
}
