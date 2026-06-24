package com.seif.bulkwhatsapp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.seif.bulkwhatsapp.R
import com.seif.bulkwhatsapp.ui.MainActivity
import com.seif.bulkwhatsapp.data.SendStatus
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.databinding.ActivityProgressBinding
import com.seif.bulkwhatsapp.service.WhatsAppAccessibilityService

class ProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProgressBinding
    private var countdownTimer: CountDownTimer? = null
    private var isFinished = false

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val finished = intent?.getBooleanExtra("finished", false) ?: false
            if (finished) {
                isFinished = true
                onFinished()
            } else {
                updateUI()
                if (!SessionManager.isPaused) startCountdown()
                else {
                    countdownTimer?.cancel()
                    binding.tvCountdown.text = "موقوف"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateUI()
        if (!SessionManager.isPaused) startCountdown()

        binding.btnPauseResume.setOnClickListener {
            val service = WhatsAppAccessibilityService.instance
            if (SessionManager.isPaused) {
                service?.resume()
                updatePauseButton()
                startCountdown()
            } else {
                service?.pause()
                countdownTimer?.cancel()
                binding.tvCountdown.text = "موقوف ⏸"
                updatePauseButton()
            }
        }

        binding.btnStop.setOnClickListener {
            countdownTimer?.cancel()
            WhatsAppAccessibilityService.instance?.stop()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        updatePauseButton()
        val filter = IntentFilter(WhatsAppAccessibilityService.ACTION_UPDATE_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) {}
    }

    private fun updateUI() {
        val total = SessionManager.totalCount
        val sent = SessionManager.sentCount
        val remaining = SessionManager.remainingCount
        val progress = SessionManager.progressPercent
        val session = SessionManager.currentSession
        val current = session?.contacts?.getOrNull(SessionManager.currentIndex)

        binding.tvSentCount.text = sent.toString()
        binding.tvRemainingCount.text = remaining.toString()
        binding.progressBar.progress = progress
        binding.tvProgressPercent.text = "$progress%"
        binding.tvCurrentContact.text = if (SessionManager.isPaused)
            "⏸ متوقف مؤقتاً عند: ${current?.name ?: "..."}"
        else
            "جاري الإرسال إلى: ${current?.name ?: "..."}"
    }

    private fun updatePauseButton() {
        if (SessionManager.isPaused) {
            binding.btnPauseResume.text = "▶ استئناف الإرسال"
            binding.btnPauseResume.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.green_primary)
        } else {
            binding.btnPauseResume.text = "⏸ إيقاف مؤقت"
            binding.btnPauseResume.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.orange_warning)
        }
    }

    private fun startCountdown() {
        countdownTimer?.cancel()
        val delayMs = (SessionManager.currentSession?.delaySeconds ?: 10) * 1000L
        countdownTimer = object : CountDownTimer(delayMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (!SessionManager.isPaused)
                    binding.tvCountdown.text = "${millisUntilFinished / 1000} ث"
            }
            override fun onFinish() {
                if (!SessionManager.isPaused) binding.tvCountdown.text = "0 ث"
            }
        }.start()
    }

    private fun onFinished() {
        countdownTimer?.cancel()
        binding.tvCurrentContact.text = "تم إرسال جميع الرسائل بنجاح"
        binding.tvCountdown.text = "انتهى"
        binding.btnPauseResume.isEnabled = false
        binding.tvRemainingCount.text = "0"
        binding.tvSentCount.text = SessionManager.sentCount.toString()
        binding.progressBar.progress = 100
        binding.tvProgressPercent.text = "100%"

        // Auto return to main screen after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }
}
