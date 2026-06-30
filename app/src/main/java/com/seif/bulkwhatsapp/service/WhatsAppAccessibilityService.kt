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
    private var skipRunnable: Runnable? = null   // timeout لو مفيش واتساب على الرقم
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
        // إلغاء الـ timeout لأن الرسالة اتبعتت بنجاح
        skipRunnable?.let { handler.removeCallbacks(it) }
        skipRunnable = null
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

    // ─── إرسال ميديا: الـ Intent بيتبعت من sendNextMessage مباشرة، هنا بس بنضغط زرار الإرسال ───
    private var mediaIntentFired = false

    private fun trySendMedia(root: AccessibilityNodeInfo, variant: MessageVariant, pkg: String): Boolean {
        // دور على زرار الإرسال في شاشة معاينة الميديا
        val sendNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")?.firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("$pkg:id/caption_send")?.firstOrNull()

        if (sendNode != null && sendNode.isClickable) {
            return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    // ── Timeout: لو مفيش واتساب على الرقم، الشات مش هيفتح → skip بعد 8 ثواني ──
    private fun startSkipTimeout() {
        skipRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (waitingForSend && !messageSent) {
                val session = SessionManager.currentSession ?: return@Runnable
                val contact = session.contacts.getOrNull(SessionManager.currentIndex) ?: return@Runnable
                contact.sendStatus = SendStatus.FAILED  // مفيش واتساب
                waitingForSend = false
                messageSent = false
                currentVariant = null
                SessionManager.currentIndex++
                sendProgressBroadcast()
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        skipRunnable = r
        handler.postDelayed(r, 8000) // 8 ثواني timeout
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
        val variant = currentVariant!!

        waitingForSend = true
        messageSent = false

        try {
            if (variant.mediaUri != null && variant.mediaType != null) {
                // ميديا: نبعت ACTION_SEND مباشرة مع jid عشان واتساب يحط الملف في شات الشخص ده بالظبط
                // من غير ما يفتح شاشة اختيار جهة اتصال
                val uri = Uri.parse(variant.mediaUri)
                val jid = "${phone.replace("+", "")}@s.whatsapp.net"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = variant.mediaType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra("jid", jid)
                    if (variant.message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, variant.message)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                mediaIntentFired = true
                startActivity(shareIntent)
                // الميديا مش محتاجة timeout - الـ accessibility event هيمسك زرار الإرسال
            } else {
                // نص: نفس الطريقة الأصلية اللي كانت شغالة بالظبط
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                // لو خلال 8 ثواني الشات مفتحش = مفيش واتساب على الرقم ده → skip
                startSkipTimeout()
            }
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
        skipRunnable?.let { handler.removeCallbacks(it) }
        skipRunnable = null
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
        skipRunnable?.let { handler.removeCallbacks(it) }
        skipRunnable = null
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
