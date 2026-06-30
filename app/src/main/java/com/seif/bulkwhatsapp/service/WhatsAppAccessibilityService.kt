package com.seif.bulkwhatsapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.seif.bulkwhatsapp.data.MessageVariant
import com.seif.bulkwhatsapp.data.SendStatus
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.utils.PhoneUtils

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhatsAppAccessibilityService? = null
        const val ACTION_UPDATE_PROGRESS = "com.seif.bulkwhatsapp.UPDATE_PROGRESS"
        private const val TAG = "WaTayar"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var waitingForSend = false
    private var waitingForMediaSend = false
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null
    private var currentPkg: String = "com.whatsapp"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service CONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Service DESTROYED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning || SessionManager.isPaused) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }
        if (messageSent) return

        val pkg = event?.packageName?.toString() ?: return
        if (pkg != currentPkg) return

        val root = rootInActiveWindow ?: return

        if (waitingForSend) {
            val msg = currentVariant?.message ?: return
            Log.d(TAG, "Trying to send text...")
            if (trySendTextMessage(root, msg)) {
                Log.d(TAG, "Text SENT successfully")
                messageSent = true
                waitingForSend = false
                markSentAndNext()
            }
        } else if (waitingForMediaSend) {
            Log.d(TAG, "Trying to click send button for media...")
            if (tryClickSendButton(root)) {
                Log.d(TAG, "Media SENT successfully")
                messageSent = true
                waitingForMediaSend = false
                markSentAndNext()
            }
        }
    }

    private fun trySendTextMessage(root: AccessibilityNodeInfo, message: String): Boolean {
        val inputNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/entry")
            ?.firstOrNull()
        if (inputNode == null) {
            Log.d(TAG, "entry box NOT found yet")
            return false
        }

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        val setOk = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Set text result: $setOk")

        Thread.sleep(400)

        val freshRoot = rootInActiveWindow ?: root
        val sendNode = freshRoot.findAccessibilityNodeInfosByViewId("$currentPkg:id/send")
            ?.firstOrNull()
        if (sendNode == null) {
            Log.d(TAG, "send button NOT found")
            return false
        }
        val clickOk = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Click send result: $clickOk")
        return clickOk
    }

    private fun tryClickSendButton(root: AccessibilityNodeInfo): Boolean {
        val ids = listOf(
            "$currentPkg:id/send",
            "$currentPkg:id/caption_send",
            "$currentPkg:id/send_btn",
            "$currentPkg:id/action_send",
            "$currentPkg:id/fab"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable) {
                Log.d(TAG, "Found send button by id: $id")
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        Log.d(TAG, "Send button not found by ID, trying description search...")
        return searchClickable(root, listOf("send", "إرسال", "ارسال"))
    }

    private fun searchClickable(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (node.isClickable && keywords.any { desc.contains(it) || text.contains(it) }) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchClickable(child, keywords)) return true
        }
        return false
    }

    private fun markSentAndNext() {
        val session = SessionManager.currentSession ?: return
        session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
        val delay = session.delaySeconds * 1000L
        val r = Runnable {
            if (!SessionManager.isPaused && SessionManager.isRunning) {
                SessionManager.currentIndex++
                resetState()
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        pendingRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun resetState() {
        messageSent = false
        waitingForSend = false
        waitingForMediaSend = false
        currentVariant = null
    }

    fun sendNextMessage() {
        val session = SessionManager.currentSession
        if (session == null) {
            Log.e(TAG, "sendNextMessage: NO SESSION!")
            return
        }
        if (SessionManager.isPaused || !SessionManager.isRunning) {
            Log.d(TAG, "sendNextMessage: paused or not running")
            return
        }
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }

        val contact = session.contacts[SessionManager.currentIndex]
        if (contact.sendStatus == SendStatus.SENT) {
            SessionManager.currentIndex++; sendNextMessage(); return
        }

        val variant = session.pickVariant()
        currentVariant = variant
        contact.sendStatus = SendStatus.SENDING
        resetState()
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))

        currentPkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone).replace("+", "")

        Log.d(TAG, "Sending to: ${contact.name} ($phone), hasMedia=${variant.mediaUri != null}")

        try {
            if (variant.mediaUri != null && variant.mediaType != null) {
                sendMedia(phone, variant)
            } else {
                sendText(phone)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendNextMessage", e)
            contact.sendStatus = SendStatus.FAILED
            SessionManager.currentIndex++
            sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
            handler.postDelayed({ sendNextMessage() }, 1500)
        }
    }

    private fun sendText(phone: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$phone")
            setPackage(currentPkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        waitingForSend = true
        Log.d(TAG, "Opening chat for text: $phone")
        startActivity(intent)
    }

    private fun sendMedia(phone: String, variant: MessageVariant) {
        // الخطوة 1: افتح الشات الأول عشان واتساب يحدد المحادثة الحالية
        val chatIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$phone")
            setPackage(currentPkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        Log.d(TAG, "Opening chat before media: $phone")
        startActivity(chatIntent)

        // الخطوة 2: بعد فترة كافية (شات اتفتح فعلاً) ابعت الميديا لنفس الشات المفتوح
        handler.postDelayed({
            try {
                val uri = Uri.parse(variant.mediaUri)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = variant.mediaType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (variant.message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, variant.message)
                    setPackage(currentPkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                waitingForMediaSend = true
                Log.d(TAG, "Sending media share intent")
                startActivity(shareIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send media", e)
                val session = SessionManager.currentSession
                val contact = session?.contacts?.getOrNull(SessionManager.currentIndex)
                contact?.sendStatus = SendStatus.FAILED
                SessionManager.currentIndex++
                sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
                handler.postDelayed({ sendNextMessage() }, 1500)
            }
        }, 3000) // 3 ثواني كفاية عشان الشات يفتح بالكامل
    }

    fun pause() {
        SessionManager.isPaused = true
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        waitingForSend = false
        waitingForMediaSend = false
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
    }

    fun resume() {
        SessionManager.isPaused = false
        val session = SessionManager.currentSession ?: return
        session.contacts.getOrNull(SessionManager.currentIndex)?.let {
            if (it.sendStatus == SendStatus.SENDING) it.sendStatus = SendStatus.PENDING
        }
        sendNextMessage()
    }

    fun stop() {
        SessionManager.isRunning = false
        SessionManager.isPaused = false
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        resetState()
        val i = Intent(ACTION_UPDATE_PROGRESS)
        i.putExtra("finished", true)
        sendBroadcast(i)
    }

    private fun finishSession() {
        SessionManager.isRunning = false
        SessionManager.isPaused = false
        val i = Intent(ACTION_UPDATE_PROGRESS)
        i.putExtra("finished", true)
        sendBroadcast(i)
    }

    override fun onInterrupt() {}
}
