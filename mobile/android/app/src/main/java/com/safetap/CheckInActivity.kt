package com.safetap

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Full-screen prompt shown when a check-in timer expires. The phone buzzes
// loudly and repeatedly until the user taps I'M FINE. If they ignore it for
// 10 minutes, CheckInReceiver's escalate alarm fires the full SOS.
class CheckInActivity : ComponentActivity() {

    private lateinit var escalateText: TextView
    private var buzzTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_checkin)

        escalateText = findViewById(R.id.escalateCountdownText)
        findViewById<Button>(R.id.imFineButton).setOnClickListener { imFine() }

        startBuzzing()
        startEscalateCountdown()
    }

    private fun startBuzzing() {
        // Loud, repeating buzz until dismissed.
        buzzTimer?.cancel()
        buzzTimer = object : CountDownTimer(GRACE_MS, 2000) {
            override fun onTick(millisLeft: Long) {
                try {
                    vibrator().vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
                    )
                } catch (e: Exception) { /* never crash over haptics */ }
            }
            override fun onFinish() { /* the escalate alarm handles the rest */ }
        }.also { it.start() }
    }

    private fun startEscalateCountdown() {
        object : CountDownTimer(GRACE_MS, 1000) {
            override fun onTick(millisLeft: Long) {
                val m = millisLeft / 60_000
                val s = (millisLeft % 60_000) / 1000
                escalateText.text = "SOS fires automatically in %d:%02d if you do not respond.".format(m, s)
            }
            override fun onFinish() {
                escalateText.text = "Time is up."
            }
        }.start()
    }

    private fun imFine() {
        buzzTimer?.cancel()
        val prefs = Prefs(this)
        val checkinId = prefs.activeCheckinId
        // Disarm locally first so nothing can fire while we talk to the server.
        contextDisarm()
        lifecycleScope.launch {
            val token = prefs.token
            if (token != null && checkinId != null) {
                try {
                    ApiClient.cancelCheckin(token, checkinId)
                } catch (e: Exception) {
                    // The timer is disarmed on the phone either way; the
                    // backend will expire the check-in on its own.
                }
            }
            Toast.makeText(this@CheckInActivity, "Glad you are okay.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun contextDisarm() {
        val receiver = CheckInReceiver()
        receiver.cancelAlarm(this, CheckInReceiver.ACTION_CHECKIN_EXPIRED)
        receiver.cancelAlarm(this, CheckInReceiver.ACTION_CHECKIN_ESCALATE)
        Prefs(this).activeCheckinId = null
        // Also tell the receiver in case it is mid-flight.
        sendBroadcast(Intent(CheckInReceiver.ACTION_CANCEL_CHECKIN).setClass(this, CheckInReceiver::class.java))
    }

    private fun vibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onDestroy() {
        buzzTimer?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val GRACE_MS = 10 * 60_000L // must match CheckInReceiver.ESCALATE_AFTER_MINUTES
    }
}
