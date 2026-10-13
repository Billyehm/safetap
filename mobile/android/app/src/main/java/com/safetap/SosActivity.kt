package com.safetap

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Full-screen SOS confirmation, reachable straight from the home-screen
// widget (and over the lock screen).
//
// Phase 1: press and HOLD the ring for 3 seconds. The ring fills as you
// hold, the phone buzzes at each quarter, and lifting your finger resets
// it. This is what stops pocket dials.
// Phase 2: a loud 5-second countdown with a huge CANCEL button.
// Phase 3: the alert is out. Shows who was contacted and how, plus the
// FALSE ALARM escape hatch (PIN or biometric, handled in MainActivity).
class SosActivity : ComponentActivity() {

    private lateinit var holdContainer: View
    private lateinit var countdownContainer: View
    private lateinit var firedContainer: View
    private lateinit var holdRing: SosHoldRingView
    private lateinit var countdownText: TextView
    private lateinit var firedStatusText: TextView

    private var holdAnimator: ValueAnimator? = null
    private var countdownTimer: CountDownTimer? = null
    private var fired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Manifest already sets showWhenLocked/turnScreenOn; keep the
        // screen awake for the whole flow on top of that.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_sos)

        holdContainer = findViewById(R.id.holdContainer)
        countdownContainer = findViewById(R.id.countdownContainer)
        firedContainer = findViewById(R.id.firedContainer)
        holdRing = findViewById(R.id.holdRing)
        countdownText = findViewById(R.id.countdownText)
        firedStatusText = findViewById(R.id.firedStatusText)

        setupHoldToArm()
        findViewById<Button>(R.id.cancelButton).setOnClickListener { abortToHold() }
        findViewById<Button>(R.id.falseAlarmButton).setOnClickListener { openFalseAlarm() }

        showPhase(1)
    }

    // ---- Phase 1: press and hold --------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldToArm() {
        holdRing.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHold()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    true
                }
                else -> false
            }
        }
    }

    private fun startHold() {
        cancelHold()
        buzz(60)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = HOLD_MS }
        var lastTick = 0
        anim.addUpdateListener { va ->
            val p = va.animatedValue as Float
            holdRing.progress = p
            // Buzz at each quarter so the holder feels progress.
            val quarter = (p * 4).toInt()
            if (quarter > lastTick) {
                lastTick = quarter
                buzz(40)
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (holdRing.progress >= 1f && !fired) startCountdown()
            }
        })
        holdAnimator = anim
        anim.start()
    }

    private fun cancelHold() {
        holdAnimator?.cancel()
        holdAnimator = null
        holdRing.progress = 0f
    }

    // ---- Phase 2: 5-second countdown -----------------------------------

    private fun startCountdown() {
        showPhase(2)
        buzz(300)
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(COUNTDOWN_MS, 500) {
            override fun onTick(millisLeft: Long) {
                val s = (millisLeft / 1000) + 1
                countdownText.text = s.toString()
                buzz(80)
            }
            override fun onFinish() = fireAlert()
        }.also { it.start() }
    }

    private fun abortToHold() {
        countdownTimer?.cancel()
        countdownTimer = null
        showPhase(1)
    }

    // ---- Phase 3: fire ---------------------------------------------------

    private fun fireAlert() {
        if (fired) return
        fired = true
        showPhase(3)
        firedStatusText.text = "Sending the alert..."
        lifecycleScope.launch {
            when (val r = AlertFirer.fire(this@SosActivity, "panic")) {
                is AlertFirer.FireResult.Online -> {
                    firedStatusText.text =
                        "Alert sent. Your contacts are being reached by SMS, " +
                        "voice call and WhatsApp. Alert id: ${r.alertId}"
                }
                is AlertFirer.FireResult.OfflineSms -> {
                    firedStatusText.text =
                        "No data connection, so the SOS went out as plain SMS " +
                        "straight to your ${r.sentTo} contacts with your last " +
                        "known location and a maps link. It will sync to the " +
                        "server when you are back online."
                }
                AlertFirer.FireResult.RateLimited -> {
                    firedStatusText.text =
                        "You have hit the safety limit of " +
                        "${Prefs.MAX_ALERTS_PER_DAY} alerts today. " +
                        "If this is a real emergency, call 112."
                }
                is AlertFirer.FireResult.Failed -> {
                    firedStatusText.text = "Could not send: ${r.reason}"
                    fired = false
                }
            }
        }
    }

    private fun openFalseAlarm() {
        // The false-alarm gate (PIN or biometric) lives in MainActivity so
        // there is exactly one implementation of it.
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_FALSE_ALARM, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun showPhase(phase: Int) {
        holdContainer.visibility = if (phase == 1) View.VISIBLE else View.GONE
        countdownContainer.visibility = if (phase == 2) View.VISIBLE else View.GONE
        firedContainer.visibility = if (phase == 3) View.VISIBLE else View.GONE
    }

    private fun buzz(ms: Long) {
        try {
            vibrator().vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Haptics are nice-to-have; never crash over them.
        }
    }

    private fun vibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        holdAnimator?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val HOLD_MS = 3000L
        private const val COUNTDOWN_MS = 5000L
    }
}

// The ring the user holds. A gray track, a red arc that fills with
// progress, and HOLD written in the middle. No external dependency.
class SosHoldRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        color = 0xFFDDDDDD.toInt()
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        strokeCap = Paint.Cap.ROUND
        color = 0xFFD32F2F.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textSize = 64f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - 24f
        canvas.drawCircle(cx, cy, r, trackPaint)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, progress * 360f, false, arcPaint)
        val label = if (progress <= 0f) "HOLD" else "${(progress * 100).toInt()}%"
        canvas.drawText(label, cx, cy + textPaint.textSize / 3f, textPaint)
    }
}
