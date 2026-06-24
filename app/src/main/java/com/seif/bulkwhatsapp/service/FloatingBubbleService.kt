package com.seif.bulkwhatsapp.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.seif.bulkwhatsapp.R
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.ui.ProgressActivity

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: android.view.View? = null
    private var params: WindowManager.LayoutParams? = null

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBubble(intent?.getBooleanExtra("finished", false) ?: false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()

        val filter = IntentFilter(WhatsAppAccessibilityService.ACTION_UPDATE_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(progressReceiver, filter)
        }
    }

    private fun createBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_bubble, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        setupDrag()
        updateBubble(false)
        windowManager.addView(bubbleView, params)
    }

    private fun setupDrag() {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x; initialY = params!!.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params!!.x = initialX + (touchX - event.rawX).toInt()
                    params!!.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - touchX)
                    val dy = Math.abs(event.rawY - touchY)
                    if (dx < 10 && dy < 10) {
                        // Click - open progress
                        val i = Intent(this, ProgressActivity::class.java)
                        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        startActivity(i)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateBubble(finished: Boolean) {
        val view = bubbleView ?: return
        val tvSent = view.findViewById<TextView>(R.id.tvBubbleSent)
        val tvRemaining = view.findViewById<TextView>(R.id.tvBubbleRemaining)
        val tvStatus = view.findViewById<TextView>(R.id.tvBubbleStatus)

        tvSent.text = "${SessionManager.sentCount}"
        tvRemaining.text = "${SessionManager.remainingCount}"
        tvStatus.text = when {
            finished -> "✅ انتهى"
            SessionManager.isPaused -> "⏸ موقوف"
            else -> "✈️ يُرسل"
        }

        if (finished) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 3000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) {}
        bubbleView?.let { windowManager.removeView(it) }
    }
}
