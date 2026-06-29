package com.seif.bulkwhatsapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var waitingForSend = false
    private var waitingForMediaSend = false
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null
    private var currentPkg: String = "com.whatsapp"

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { super.onDestroy(); instance = null }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning || SessionManager.isPaused) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }
        if (messageSent) return

        val pkg = event?.packageName?.toString() ?: return
        if (pkg != currentPkg) return

        val root = rootInActiveWindow ?: return

        when {
            // وضع النص: نكتب ونبعت
            waitingForSend -> {
                val msg = currentVariant?.message ?: return
                if (trySendTextMessage(root, msg)) {
                    messageSent = true
                    waitingForSend = false
                    markSentAndNext()
                }
            }

            // وضع الميديا: نضغط زرار الإرسال بعد ما الملف اتحط
            waitingForMediaSend -> {
                if (tryClickSendButton(root)) {
                    messageSent = true
                    waitingForMediaSend = false
                    markSentAndNext()
                }
            }
        }
    }

    private fun trySendTextMessage(root: AccessibilityNodeInfo, message: String): Boolean {
        // لازم الشات يكون مفتوح - نشوف entry box
        val inputNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/entry")
            ?.firstOrNull() ?: return false

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Thread.sleep(400)

        val sendNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/send")
            ?.firstOrNull() ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tryClickSendButton(root: AccessibilityNodeInfo): Boolean {
        // IDs محتملة في شاشة preview الميديا
        val ids = listOf(
            "$currentPkg:id/send",
            "$currentPkg:id/caption_send",
            "$currentPkg:id/send_btn",
            "$currentPkg:id/action_send"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        // fallback: دور بـ contentDescription
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
        val session = SessionManager.currentSession ?: return
        if (SessionManager.isPaused || !SessionManager.isRunning) return
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

        try {
            if (variant.mediaUri != null && variant.mediaType != null) {
                sendMedia(phone, variant)
            } else {
                sendText(phone)
            }
        } catch (e: Exception) {
            contact.sendStatus = SendStatus.FAILED
            SessionManager.currentIndex++
            sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
            handler.postDelayed({ sendNextMessage() }, 1500)
        }
    }

    private fun sendText(phone: String) {
        // whatsapp://send?phone=xxx - بيفتح الشات مباشرة بدون شاشة اختيار
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("whatsapp://send?phone=$phone")
            setPackage(currentPkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        waitingForSend = true
        startActivity(intent)
    }

    private fun sendMedia(phone: String, variant: MessageVariant) {
        val uri = Uri.parse(variant.mediaUri)

        // whatsapp://send?phone=xxx مع الملف - بيفتح الشات مباشرة
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = variant.mediaType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra("jid", "${phone}@s.whatsapp.net")  // بيحدد الشخص مباشرة
            if (variant.message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, variant.message)
            setPackage(currentPkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        waitingForMediaSend = true
        startActivity(intent)
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
