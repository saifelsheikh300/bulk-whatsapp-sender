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
        const val ACTION_FINISHED = "com.seif.bulkwhatsapp.FINISHED"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var waitingForSend = false      // نص: منتظر الشات يفتح
    private var waitingForMedia = false     // ميديا: منتظر شاشة المعاينة
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var skipRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { super.onDestroy(); instance = null }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning || SessionManager.isPaused) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }
        if (messageSent) return

        val pkg = event?.packageName?.toString() ?: return
        val expected = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        if (pkg != expected) return

        val root = rootInActiveWindow ?: return

        // ─── وضع النص: نفس منطق الأصل بالظبط ───
        if (waitingForSend) {
            val msg = currentVariant?.message ?: return
            if (trySendMessage(root, msg, expected)) {
                cancelSkip()
                messageSent = true
                waitingForSend = false
                markSentAndNext()
            }
            return
        }

        // ─── وضع الميديا: دور على زرار الإرسال في شاشة المعاينة ───
        if (waitingForMedia) {
            if (tryClickSend(root, expected)) {
                cancelSkip()
                messageSent = true
                waitingForMedia = false
                markSentAndNext()
            }
        }
    }

    // ─── نفس الكود الأصلي بالظبط ───
    private fun trySendMessage(root: AccessibilityNodeInfo, message: String, pkg: String): Boolean {
        val inputNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")
            ?.firstOrNull() ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sendNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            ?.firstOrNull() ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tryClickSend(root: AccessibilityNodeInfo, pkg: String): Boolean {
        for (id in listOf("$pkg:id/send", "$pkg:id/caption_send")) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable)
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun markSentAndNext() {
        val session = SessionManager.currentSession ?: return
        session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
        sendProgressBroadcast()
        val delay = session.delaySeconds * 1000L
        val r = Runnable {
            if (!SessionManager.isPaused && SessionManager.isRunning) {
                SessionManager.currentIndex++
                messageSent = false
                currentVariant = null
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        pendingRunnable = r
        handler.postDelayed(r, delay)
    }

    // ─── Skip: لو مفيش واتساب على الرقم ───
    private fun startSkip(ms: Long) {
        cancelSkip()
        val r = Runnable {
            if (!messageSent) {
                val session = SessionManager.currentSession ?: return@Runnable
                val contact = session.contacts.getOrNull(SessionManager.currentIndex) ?: return@Runnable
                contact.sendStatus = SendStatus.FAILED
                waitingForSend = false
                waitingForMedia = false
                messageSent = false
                currentVariant = null
                SessionManager.currentIndex++
                sendProgressBroadcast()
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        skipRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun cancelSkip() {
        skipRunnable?.let { handler.removeCallbacks(it) }
        skipRunnable = null
    }

    fun sendNextMessage() {
        val session = SessionManager.currentSession ?: return
        if (SessionManager.isPaused || !SessionManager.isRunning) return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }

        val contact = session.contacts[SessionManager.currentIndex]
        if (contact.sendStatus == SendStatus.SENT) {
            SessionManager.currentIndex++; sendNextMessage(); return
        }

        // اختار رسالة عشوائية
        val variant = session.pickVariant()
        currentVariant = variant
        contact.sendStatus = SendStatus.SENDING
        messageSent = false
        waitingForSend = false
        waitingForMedia = false
        sendProgressBroadcast()

        val pkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone)

        // فتح الشات دايماً بنفس طريقة الأصل
        val chatIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
            setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            startActivity(chatIntent)

            if (variant.mediaUri != null && variant.mediaType != null) {
                // ميديا: استنى 2.5 ثانية عشان الشات يفتح، بعدين ابعت الملف
                // لو مفيش واتساب على الرقم → skip بعد 10 ثواني
                startSkip(10000)
                handler.postDelayed({
                    if (!messageSent) {
                        try {
                            val uri = Uri.parse(variant.mediaUri)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = variant.mediaType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                if (variant.message.isNotBlank())
                                    putExtra(Intent.EXTRA_TEXT, variant.message)
                                setPackage(pkg)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            waitingForMedia = true
                            startActivity(shareIntent)
                        } catch (e: Exception) {
                            cancelSkip()
                            contact.sendStatus = SendStatus.FAILED
                            SessionManager.currentIndex++
                            sendProgressBroadcast()
                            handler.postDelayed({ sendNextMessage() }, 1000)
                        }
                    }
                }, 2500)

            } else {
                // نص: نفس منطق الأصل بالظبط + skip لو مفيش واتساب
                waitingForSend = true
                startSkip(8000)
            }

        } catch (e: Exception) {
            cancelSkip()
            contact.sendStatus = SendStatus.FAILED
            SessionManager.currentIndex++
            sendProgressBroadcast()
            handler.postDelayed({ sendNextMessage() }, 1000)
        }
    }

    fun pause() {
        SessionManager.isPaused = true
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        cancelSkip()
        waitingForSend = false
        waitingForMedia = false
        sendProgressBroadcast()
    }

    fun resume() {
        SessionManager.isPaused = false
        val session = SessionManager.currentSession ?: return
        val current = session.contacts.getOrNull(SessionManager.currentIndex)
        if (current?.sendStatus == SendStatus.SENDING) current.sendStatus = SendStatus.PENDING
        sendNextMessage()
    }

    fun stop() {
        SessionManager.isRunning = false
        SessionManager.isPaused = false
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        cancelSkip()
        waitingForSend = false
        waitingForMedia = false
        val i = Intent(ACTION_UPDATE_PROGRESS)
        i.putExtra("finished", true)
        sendBroadcast(i)
    }

    private fun sendProgressBroadcast() {
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
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
