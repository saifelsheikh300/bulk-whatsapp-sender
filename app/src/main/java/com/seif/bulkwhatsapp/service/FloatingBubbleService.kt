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
import android.widget.ImageButton
import android.widget.TextView
import com.seif.bulkwhatsapp.R
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.ui.ProgressActivity

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: android.view.View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isDragging = false

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val finished = intent?.getBooleanExtra("finished", false) ?: false
            updateBubble(finished)
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
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 200
        }

        setupDragAndClick()
        setupButtons()
        updateBubble(false)
        windowManager.addView(bubbleView, params)
    }

    private fun setupDragAndClick() {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x; initialY = params!!.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchX - event.rawX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    params!!.x = initialX + dx
                    params!!.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
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

    private fun setupButtons() {
        val view = bubbleView ?: return

        view.findViewById<ImageButton>(R.id.btnBubblePause)?.setOnClickListener {
            val service = WhatsAppAccessibilityService.instance
            if (SessionManager.isPaused) {
                service?.resume()
            } else {
                service?.pause()
            }
            updateBubble(false)
        }

        view.findViewById<ImageButton>(R.id.btnBubbleStop)?.setOnClickListener {
            WhatsAppAccessibilityService.instance?.stop()
            stopSelf()
        }
    }

    private fun updateBubble(finished: Boolean) {
        val view = bubbleView ?: return
        view.findViewById<TextView>(R.id.tvBubbleSent)?.text = "${SessionManager.sentCount}"
        view.findViewById<TextView>(R.id.tvBubbleRemaining)?.text = "${SessionManager.remainingCount}"
        view.findViewById<TextView>(R.id.tvBubbleStatus)?.text = when {
            finished -> "انتهى"
            SessionManager.isPaused -> "موقوف"
            else -> "يرسل"
        }
        val btnPause = view.findViewById<ImageButton>(R.id.btnBubblePause)
        btnPause?.setImageResource(
            if (SessionManager.isPaused) android.R.drawable.ic_media_play
            else android.R.drawable.ic_media_pause
        )
        if (finished) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) {}
        bubbleView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
    }
}
