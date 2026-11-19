package com.navid.blobs

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

class WelcomeActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var intro: LinearLayout
    private lateinit var keyStep: LinearLayout
    private val floaters = mutableListOf<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.onboarded(this)) {
            startActivity(Intent(this, HomeActivity::class.java)); finish(); return
        }
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        applyInsets(root)
        addFloatingBlobs()
        intro = buildIntro()
        keyStep = buildKeyStep().apply { visibility = View.GONE }
        root.addView(intro, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(keyStep, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onDestroy() { floaters.forEach { it.cancel() }; super.onDestroy() }

    private fun addFloatingBlobs() {
        val layer = FrameLayout(this)
        root.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))
        // (x fraction, y fraction, size dp, shape, colour index)
        val spots = listOf(
            floatArrayOf(0.08f, 0.10f, 92f, Shapes.CIRCLE.toFloat(), 0f),
            floatArrayOf(0.62f, 0.07f, 74f, Shapes.HEXAGON.toFloat(), 7f),
            floatArrayOf(0.70f, 0.27f, 104f, Shapes.CLOUD.toFloat(), 3f),
            floatArrayOf(0.14f, 0.32f, 66f, Shapes.DROP.toFloat(), 10f),
            floatArrayOf(0.42f, 0.20f, 58f, Shapes.TRIANGLE.toFloat(), 5f),
            floatArrayOf(0.40f, 0.38f, 82f, Shapes.BLOB.toFloat(), 1f),
            floatArrayOf(0.78f, 0.45f, 56f, Shapes.SQUIRCLE.toFloat(), 8f),
            floatArrayOf(0.04f, 0.50f, 60f, Shapes.PILL.toFloat(), 6f)
        )
        layer.post {
            val w = layer.width; val h = layer.height
            spots.forEachIndexed { i, s ->
                val size = dp(s[2].toInt())
                val b = BlobView(this).apply {
                    shape = s[3].toInt(); color = C.SWATCHES[s[4].toInt()]
                    look = if (s[0] > 0.5f) -0.6f else 0.6f
                    alpha = 0f; scaleX = 0.4f; scaleY = 0.4f
                }
                layer.addView(b, FrameLayout.LayoutParams(size, size).apply {
                    leftMargin = (w * s[0]).toInt(); topMargin = (h * s[1]).toInt()
                })
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90L * i).setDuration(520)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                val amp = dpf(8f + Random.nextInt(8))
                ObjectAnimator.ofFloat(b, View.TRANSLATION_Y, -amp, amp).apply {
                    duration = 2200L + Random.nextLong(1600)
                    repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    startDelay = 300L + 120L * i
                    start(); floaters.add(this)
                }
                ObjectAnimator.ofFloat(b, View.ROTATION, -4f, 4f).apply {
                    duration = 3000L + Random.nextLong(2000)
                    repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start(); floaters.add(this)
                }
            }
        }
    }

    private fun buildIntro(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(28), 0, dp(28), dp(32))
        addView(text("Blobs", 52f, C.TEXT, true).apply { letterSpacing = -0.02f })
        addView(text("Your team of AI bots", 19f, C.SUB).apply { setPadding(0, dp(4), 0, dp(10)) })
        addView(text("Create little helpers with their own roles and chat with them, powered by Google Gemini.", 15f, C.SUB).apply {
            setPadding(0, 0, 0, dp(28)); setLineSpacing(0f, 1.2f)
        })
        addView(pillButton("Get started", Prefs.accent(this@WelcomeActivity)) {
            it.squish()
            if (Prefs.apiKey(this@WelcomeActivity).isNotEmpty()) finishOnboarding() else showKeyStep()
        }, lp(MATCH, WRAP))
        alpha = 0f; translationY = dpf(24f)
        animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(600).start()
    }

    private fun showKeyStep() {
        intro.animate().alpha(0f).translationY(-dpf(20f)).setDuration(220).withEndAction { intro.visibility = View.GONE }.start()
        keyStep.visibility = View.VISIBLE
        keyStep.alpha = 0f; keyStep.translationY = dpf(30f)
        keyStep.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380).start()
    }

    private fun buildKeyStep(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(24), 0, dp(24), dp(28))
        val card = LinearLayout(this@WelcomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(C.SURF, dpf(28f))
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        addView(card, lp(MATCH, WRAP))
        val head = LinearLayout(this@WelcomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(this@WelcomeActivity).apply {
            background = roundBg(C.SURF2, dpf(22f))
            addView(icon(R.drawable.ic_key, Prefs.accent(this@WelcomeActivity)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, lp(dp(44), dp(44)))
        head.addView(text("Connect Gemini", 22f, C.TEXT, true).apply { setPadding(dp(14), 0, 0, 0) })
        card.addView(head)
        card.addView(text("Your bots run on Google Gemini. Paste a free API key from Google AI Studio. It is encrypted and stays on this phone.", 14.5f, C.SUB).apply {
            setPadding(0, dp(14), 0, dp(10)); setLineSpacing(0f, 1.2f)
        })
        val link = LinearLayout(this@WelcomeActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(8), dp(8))
            background = ripple(null, dpf(12f))
            isClickable = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) } catch (_: Exception) {}
            }
            addView(text("Get a key at aistudio.google.com/apikey", 15f, Prefs.accent(this@WelcomeActivity), true))
            addView(icon(R.drawable.ic_open, Prefs.accent(this@WelcomeActivity), 18).apply {
                (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
            })
        }
        card.addView(link, lp(WRAP, WRAP))
        val field = field("Paste API key (AIza...)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
        }
        card.addView(field, lp(MATCH, WRAP).margins(t = dp(10)))
        val status = text("", 14f, C.SUB).apply { setPadding(dp(4), dp(10), 0, 0); visibility = View.GONE }
        card.addView(status)
        val row = LinearLayout(this@WelcomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        val test = outlineButton("Test key") {}
        val cont = pillButton("Continue", Prefs.accent(this@WelcomeActivity)) {
            val k = field.text.toString().trim()
            if (k.isEmpty()) { showStatus(status, "Paste your API key first.", C.ERR); return@pillButton }
            Prefs.setApiKey(this@WelcomeActivity, k)
            hideKeyboard(field)
            finishOnboarding()
        }
        test.setOnClickListener {
            val k = field.text.toString().trim()
            showStatus(status, "Testing...", C.SUB)
            test.isEnabled = false
            Gemini.test(k, if (Prefs.auto(this@WelcomeActivity)) null else Prefs.model(this@WelcomeActivity)) { err ->
                test.isEnabled = true
                if (err == null) showStatus(status, "Key works. You are good to go.", C.OK)
                else showStatus(status, err, C.ERR)
            }
        }
        row.addView(test, lp(0, WRAP, 1f))
        row.addView(View(this@WelcomeActivity), lp(dp(12), 1))
        row.addView(cont, lp(0, WRAP, 1f))
        card.addView(row, lp(MATCH, WRAP).margins(t = dp(18)))
        card.addView(text("Skip for now", 14f, C.SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { finishOnboarding() }
        }, lp(MATCH, WRAP))
    }

    private fun showStatus(tv: TextView, s: String, color: Int) {
        tv.text = s; tv.setTextColor(color); tv.visibility = View.VISIBLE
    }

    private fun finishOnboarding() {
        Prefs.setOnboarded(this)
        startActivity(Intent(this, HomeActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    @Suppress("unused") private val keepColor = Color.TRANSPARENT
}
