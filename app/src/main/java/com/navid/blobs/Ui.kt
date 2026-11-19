package com.navid.blobs

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Dark palette shared by every screen. */
object C {
    const val BG = 0xFF0E0E12.toInt()
    const val SURF = 0xFF1A1A21.toInt()
    const val SURF2 = 0xFF25252E.toInt()
    const val LINE = 0xFF33333E.toInt()
    const val TEXT = 0xFFEDEDF2.toInt()
    const val SUB = 0xFF9C9CAA.toInt()
    const val ERR = 0xFFFF6B6B.toInt()
    const val OK = 0xFF3DDC97.toInt()

    /** 14 blob / accent swatches. */
    val SWATCHES = intArrayOf(
        0xFFFF4F8B.toInt(), 0xFFFF7A59.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFD23F.toInt(),
        0xFFB8E35A.toInt(), 0xFF3DDC97.toInt(), 0xFF2EC4B6.toInt(), 0xFF4CC9F0.toInt(),
        0xFF4D7CFE.toInt(), 0xFF6C63FF.toInt(), 0xFFA259FF.toInt(), 0xFFE056FD.toInt(),
        0xFFC08552.toInt(), 0xFFB0B7C3.toInt()
    )

    /** Text colour readable on top of [c]. */
    fun on(c: Int): Int {
        val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        return if (l > 0.62) 0xFF111114.toInt() else Color.WHITE
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun roundBg(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

fun ripple(content: Drawable?, radius: Float, rippleColor: Int = 0x33FFFFFF): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, roundBg(Color.WHITE, radius))

fun Context.text(s: CharSequence, sizeSp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

fun Context.icon(res: Int, tint: Int = C.TEXT, sizeDp: Int = 24): ImageView =
    ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** Circular icon button (48dp touch target). */
fun Context.iconButton(res: Int, tint: Int = C.TEXT, bg: Int = Color.TRANSPARENT, size: Int = 44, onClick: (View) -> Unit): FrameLayout =
    FrameLayout(this).apply {
        val r = dpf(size / 2f)
        background = ripple(if (bg == Color.TRANSPARENT) null else roundBg(bg, r), r)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        isClickable = true; isFocusable = true
        addView(ImageView(context).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener(onClick)
    }

/** Filled pill button. */
fun Context.pillButton(label: String, color: Int, textColor: Int = C.on(color), onClick: (View) -> Unit): TextView =
    text(label, 16f, textColor, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(14), dp(24), dp(14))
        background = ripple(roundBg(color, dpf(28f)), dpf(28f), if (C.on(color) == Color.WHITE) 0x33FFFFFF else 0x22000000)
        isClickable = true; isFocusable = true
        setOnClickListener(onClick)
    }

fun Context.outlineButton(label: String, onClick: (View) -> Unit): TextView =
    text(label, 15f, C.TEXT, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = ripple(roundBg(Color.TRANSPARENT, dpf(24f), C.LINE, dp(1)), dpf(24f))
        isClickable = true; isFocusable = true
        setOnClickListener(onClick)
    }

fun Context.field(hint: String, multi: Boolean = false): EditText =
    EditText(this).apply {
        this.hint = hint
        setHintTextColor(C.SUB); setTextColor(C.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = roundBg(C.SURF2, dpf(16f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1; isSingleLine = true
        }
    }

fun Context.label(s: String): TextView = text(s, 13f, C.SUB, true).apply {
    setPadding(dp(4), dp(18), 0, dp(8))
    letterSpacing = 0.04f
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

/** Draw behind system bars and pad [root] by bars + keyboard. */
@Suppress("DEPRECATION")
fun Activity.applyInsets(root: View) {
    window.statusBarColor = C.BG
    window.navigationBarColor = C.BG
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val sb = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(sb.left, sb.top, sb.right, maxOf(sb.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
    } else {
        root.fitsSystemWindows = true
    }
}

fun Activity.open(i: Intent) {
    startActivity(i)
    @Suppress("DEPRECATION")
    overridePendingTransition(R.anim.slide_in, R.anim.hold)
}

fun Activity.closeWithAnim() {
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(R.anim.hold, R.anim.slide_out)
}

fun Activity.hideKeyboard(v: View) {
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
}

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

/** Top bar: back button + title + optional trailing views. */
fun Activity.topBar(title: String, onBack: (() -> Unit)?, vararg trailing: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        if (onBack != null) addView(iconButton(R.drawable.ic_back) { onBack() })
        addView(text(title, 20f, C.TEXT, true).apply {
            setPadding(dp(if (onBack != null) 6 else 12), 0, 0, 0)
        }, lp(0, WRAP, 1f))
        trailing.forEach { addView(it) }
    }

/** Simple tap "squish" feedback. */
fun View.squish() {
    animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
        animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }.start()
}
