package com.navid.blobs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.content.Intent
import android.net.Uri
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView

/** Small Markdown renderer: headings, bold, italic, strike, inline code, fenced code, lists, quotes, tappable links. */
object Md {
    private val bareUrl = Regex("https?://[^\\s<>()\\[\\]]+")

    /** Opens http(s) / mailto links in the browser or matching app. */
    class Link(val url: String, private val color: Int) : ClickableSpan() {
        override fun onClick(widget: View) = open(widget.context, url)
        override fun updateDrawState(ds: TextPaint) { ds.color = color; ds.isUnderlineText = true }
    }

    fun open(ctx: android.content.Context, raw: String) {
        var u = raw.trim()
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) u = "https://$u"
        val scheme = Uri.parse(u).scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "mailto") return
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    /** Link taps without stealing long-press (long-press still copies the message). */
    object Touch : LinkMovementMethod() {
        private var downAt = 0L
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> downAt = event.eventTime
                MotionEvent.ACTION_UP -> if (event.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()) return false
            }
            val handled = super.onTouchEvent(widget, buffer, event)
            // Let taps outside links fall through to the view (long-press listener).
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
                val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
                val layout = widget.layout ?: return false
                val off = layout.getOffsetForHorizontal(layout.getLineForVertical(y), x.toFloat())
                return buffer.getSpans(off, off, ClickableSpan::class.java).isNotEmpty() && handled
            }
            return handled
        }
    }
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val inline = Regex("(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(__[^_\\n]+__)|(~~[^~\\n]+~~)|(\\*[^*\\s][^*\\n]*\\*)|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))")

    private class CodeBlockSpan(val bg: Int, val pad: Float) : LineBackgroundSpan {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun drawBackground(
            c: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lnum: Int
        ) {
            p.color = bg
            c.drawRect(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), p)
        }
    }

    fun render(src: String, accent: Int, density: Float): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = src.replace("\r", "").split("\n")
        var inCode = false
        var codeStart = 0
        val codeBg = 0xFF111116.toInt()
        val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        for (raw in lines) {
            if (raw.trimStart().startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                    codeStart = sb.length
                } else {
                    inCode = false
                    closeCode(sb, codeStart, codeBg, density)
                }
                continue
            }
            if (inCode) { sb.append(raw).append('\n'); continue }

            val start = sb.length
            var m = heading.find(raw)
            if (m != null) {
                inlineAppend(sb, m.groupValues[2], accent)
                val lvl = m.groupValues[1].length
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, flags)
                sb.setSpan(RelativeSizeSpan(if (lvl <= 1) 1.3f else if (lvl == 2) 1.18f else 1.08f), start, sb.length, flags)
                sb.append('\n'); continue
            }
            if (rule.matches(raw)) {
                sb.append("\n"); continue
            }
            m = bullet.find(raw)
            if (m != null) {
                val indent = m.groupValues[1].length / 2
                inlineAppend(sb, m.groupValues[2], accent)
                sb.append('\n')
                if (indent > 0) sb.setSpan(LeadingMarginSpan.Standard((indent * 16 * density).toInt()), start, sb.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                sb.setSpan(BulletSpan((8 * density).toInt(), accent), start, sb.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                continue
            }
            m = numbered.find(raw)
            if (m != null) {
                val indent = m.groupValues[1].length / 2
                sb.append(m.groupValues[2]).append(". ")
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, flags)
                sb.setSpan(ForegroundColorSpan(accent), start, sb.length, flags)
                inlineAppend(sb, m.groupValues[3], accent)
                sb.append('\n')
                if (indent > 0) sb.setSpan(LeadingMarginSpan.Standard((indent * 16 * density).toInt()), start, sb.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                continue
            }
            m = quote.find(raw)
            if (m != null) {
                inlineAppend(sb, m.groupValues[1], accent)
                sb.append('\n')
                sb.setSpan(QuoteSpan(accent), start, sb.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                continue
            }
            inlineAppend(sb, raw, accent)
            sb.append('\n')
        }
        if (inCode) closeCode(sb, codeStart, codeBg, density) // unfinished block while streaming
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun closeCode(sb: SpannableStringBuilder, start: Int, bg: Int, density: Float) {
        if (sb.length <= start) return
        val end = sb.length
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.88f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(0xFFD7D7E0.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(LeadingMarginSpan.Standard((8 * density).toInt()), start, end, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        sb.setSpan(CodeBlockSpan(bg, 8 * density), start, end, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
    }

    private fun inlineAppend(sb: SpannableStringBuilder, text: String, accent: Int) {
        var last = 0
        val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        for (m in inline.findAll(text)) {
            plain(sb, text.substring(last, m.range.first), accent)
            val tok = m.value
            val s = sb.length
            when {
                tok.startsWith("`") -> {
                    sb.append(tok.substring(1, tok.length - 1))
                    sb.setSpan(TypefaceSpan("monospace"), s, sb.length, f)
                    sb.setSpan(BackgroundColorSpan(0xFF2E2E38.toInt()), s, sb.length, f)
                    sb.setSpan(ForegroundColorSpan(0xFFFFC2D6.toInt()), s, sb.length, f)
                }
                tok.startsWith("**") || tok.startsWith("__") -> {
                    sb.append(tok.substring(2, tok.length - 2))
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, f)
                }
                tok.startsWith("~~") -> {
                    sb.append(tok.substring(2, tok.length - 2))
                    sb.setSpan(StrikethroughSpan(), s, sb.length, f)
                }
                tok.startsWith("*") -> {
                    sb.append(tok.substring(1, tok.length - 1))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, f)
                }
                tok.startsWith("[") -> {
                    val close = tok.indexOf("](")
                    sb.append(tok.substring(1, close))
                    val url = tok.substring(close + 2, tok.length - 1).trim().substringBefore(' ')
                    sb.setSpan(Link(url, accent), s, sb.length, f)
                }
                else -> sb.append(tok)
            }
            last = m.range.last + 1
        }
        plain(sb, text.substring(last), accent)
    }

    /** Plain text with bare http(s) URLs made tappable. */
    private fun plain(sb: SpannableStringBuilder, t: String, accent: Int) {
        var last = 0
        for (m in bareUrl.findAll(t)) {
            var url = m.value
            while (url.isNotEmpty() && url.last() in ".,;:!?'\"") url = url.dropLast(1)
            sb.append(t, last, m.range.first)
            val s = sb.length
            sb.append(url)
            sb.setSpan(Link(url, accent), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.first + url.length
        }
        sb.append(t, last, t.length)
    }
}
