package com.seif.bulkwhatsapp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import com.seif.bulkwhatsapp.data.SendStatus
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.databinding.ActivityProgressBinding
import com.seif.bulkwhatsapp.service.WhatsAppAccessibilityService

class ProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProgressBinding
    private var countdownTimer: CountDownTimer? = null

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val finished = intent?.getBooleanExtra("finished", false) ?: false
            updateUI()
            if (!finished && SessionManager.isRunning) startCountdown()
            else if (finished) onFinished()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateUI()
        startCountdown()

        binding.btnStop.setOnClickListener {
            SessionManager.isRunning = false
            countdownTimer?.cancel()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(WhatsAppAccessibilityService.ACTION_UPDATE_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) {}
    }

    private fun updateUI() {
        val session = SessionManager.currentSession ?: return
        val total = session.contacts.size
        val sent = session.contacts.count { it.sendStatus == SendStatus.SENT }
        val remaining = total - sent
        val progress = if (total > 0) (sent * 100 / total) else 0
        val current = session.contacts.getOrNull(SessionManager.currentIndex)

        binding.tvSentCount.text = sent.toString()
        binding.tvRemainingCount.text = remaining.toString()
        binding.progressBar.progress = progress
        binding.tvProgressPercent.text = "$progress%"
        binding.tvCurrentContact.text = "جاري الإرسال إلى: ${current?.name ?: "..."}"
    }

    private fun startCountdown() {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvCountdown.text = "${millisUntilFinished / 1000} ث"
            }
            override fun onFinish() {
                binding.tvCountdown.text = "0 ث"
            }
        }.start()
    }

    private fun onFinished() {
        countdownTimer?.cancel()
        binding.tvCurrentContact.text = "تم الإرسال بنجاح ✓"
        binding.tvCountdown.text = "انتهى"
        binding.btnStop.text = "العودة للرئيسية"
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }
}
