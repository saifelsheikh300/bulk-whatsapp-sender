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
    private var waitingForSend = false
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning || SessionManager.isPaused) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }

        val pkg = event?.packageName?.toString() ?: return
        val expected = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        if (pkg != expected) return

        if (waitingForSend && !messageSent) {
            val root = rootInActiveWindow ?: return
            val variant = currentVariant ?: return

            // لو فيه ميديا، ابعتها كـ share intent جوه نفس الشات المفتوح
            if (variant.mediaUri != null && variant.mediaType != null) {
                if (trySendMedia(root, variant, expected)) {
                    messageSent = true
                    waitingForSend = false
                    onMessageSentSuccessfully(session)
                }
            } else {
                if (trySendMessage(root, variant.message)) {
                    messageSent = true
                    waitingForSend = false
                    onMessageSentSuccessfully(session)
                }
            }
        }
    }

    private fun onMessageSentSuccessfully(session: com.seif.bulkwhatsapp.data.SendSession) {
        session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
        sendProgressBroadcast()

        val delayMs = session.delaySeconds * 1000L
        val runnable = Runnable {
            if (!SessionManager.isPaused && SessionManager.isRunning) {
                SessionManager.currentIndex++
                messageSent = false
                currentVariant = null
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    // ─── نفس منطق إرسال النص الأصلي بالظبط (كان شغال) ───
    private fun trySendMessage(root: AccessibilityNodeInfo, message: String): Boolean {
        val pkg = if (SessionManager.currentSession?.useWhatsAppBusiness == true)
            "com.whatsapp.w4b" else "com.whatsapp"
        val inputNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")
            ?.firstOrNull() ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sendNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            ?.firstOrNull() ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ─── إرسال ميديا: نفس فكرة trySendMessage بس بنبعت Intent share جوه الـ accessibility event ───
    private var mediaIntentFired = false

    private fun trySendMedia(root: AccessibilityNodeInfo, variant: MessageVariant, pkg: String): Boolean {
        // أول مرة بس - أطلق الـ share intent
        if (!mediaIntentFired) {
            mediaIntentFired = true
            try {
                val uri = Uri.parse(variant.mediaUri)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = variant.mediaType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (variant.message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, variant.message)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(shareIntent)
            } catch (e: Exception) {
                mediaIntentFired = false
            }
            return false
        }

        // بعد كده دور على زرار الإرسال في شاشة معاينة الميديا (نفس ID زي النص أساساً)
        val sendNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")?.firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("$pkg:id/caption_send")?.firstOrNull()

        if (sendNode != null && sendNode.isClickable) {
            return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    fun sendNextMessage() {
        val session = SessionManager.currentSession ?: return
        if (SessionManager.isPaused || !SessionManager.isRunning) return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }

        val contact = session.contacts[SessionManager.currentIndex]
        if (contact.sendStatus == SendStatus.SENT) {
            SessionManager.currentIndex++
            sendNextMessage()
            return
        }

        contact.sendStatus = SendStatus.SENDING
        sendProgressBroadcast()

        // اختار رسالة عشوائية من المتاح
        currentVariant = session.pickVariant()
        mediaIntentFired = false

        val pkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone)

        // دايماً بنفتح الشات بنفس الطريقة الأصلية (كانت شغالة)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
            setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        waitingForSend = true
        messageSent = false

        try {
            startActivity(intent)
        } catch (e: Exception) {
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
        waitingForSend = false
        sendProgressBroadcast()
    }

    fun resume() {
        SessionManager.isPaused = false
        val session = SessionManager.currentSession ?: return
        val current = session.contacts.getOrNull(SessionManager.currentIndex)
        if (current?.sendStatus == SendStatus.SENDING) {
            current.sendStatus = SendStatus.PENDING
        }
        sendNextMessage()
    }

    fun stop() {
        SessionManager.isRunning = false
        SessionManager.isPaused = false
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        waitingForSend = false
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
