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
    private var waitingForSend = false      // نص - منتظر نكتب ونبعت
    private var waitingForChat = false      // ميديا - منتظر الشات يفتح عشان نبعت
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { super.onDestroy(); instance = null }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning || SessionManager.isPaused) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }

        val pkg = event?.packageName?.toString() ?: return
        val expected = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        if (pkg != expected) return

        if (messageSent) return

        val root = rootInActiveWindow ?: return

        // وضع الميديا: منتظر الشات يفتح بعدين نبعت الملف
        if (waitingForChat) {
            // تأكد إن الشات اتفتح فعلاً (بيبقى فيه entry box)
            val entryNode = root.findAccessibilityNodeInfosByViewId("$expected:id/entry")?.firstOrNull()
            if (entryNode != null) {
                waitingForChat = false
                // الشات اتفتح، دلوقتي نبعت الملف
                handler.postDelayed({
                    sendMediaFile(expected)
                }, 500)
            }
            return
        }

        // وضع النص: اكتب الرسالة واضغط send
        if (waitingForSend) {
            val msg = currentVariant?.message ?: return
            if (trySendMessage(root, msg, expected)) {
                messageSent = true
                waitingForSend = false
                markSentAndNext()
            }
            return
        }

        // وضع ما بعد الميديا: دور على زرار الإرسال في شاشة preview
        if (currentVariant?.mediaUri != null && !waitingForChat) {
            if (tryClickSendButton(root, expected)) {
                messageSent = true
                markSentAndNext()
            }
        }
    }

    private fun sendMediaFile(pkg: String) {
        val variant = currentVariant ?: return
        val contact = SessionManager.currentSession?.contacts?.getOrNull(SessionManager.currentIndex) ?: return
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
            // بعد ما نبعت Intent، الـ onAccessibilityEvent هيلاقي زرار الإرسال
        } catch (e: Exception) {
            contact.sendStatus = SendStatus.FAILED
            SessionManager.currentIndex++
            sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
            handler.postDelayed({ sendNextMessage() }, 1000)
        }
    }

    private fun markSentAndNext() {
        val session = SessionManager.currentSession ?: return
        session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
        val delay = session.delaySeconds * 1000L
        val r = Runnable {
            if (!SessionManager.isPaused && SessionManager.isRunning) {
                SessionManager.currentIndex++
                messageSent = false
                currentVariant = null
                waitingForSend = false
                waitingForChat = false
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        pendingRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun trySendMessage(root: AccessibilityNodeInfo, message: String, pkg: String): Boolean {
        val inputNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")?.firstOrNull() ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // انتظر لحظة بعد الكتابة
        Thread.sleep(300)
        val sendNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")?.firstOrNull() ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tryClickSendButton(root: AccessibilityNodeInfo, pkg: String): Boolean {
        // جرب كل IDs محتملة لزرار الإرسال في شاشة preview الميديا
        val ids = listOf(
            "$pkg:id/send",
            "$pkg:id/caption_send",
            "$pkg:id/send_btn",
            "$pkg:id/action_send"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        // لو مش لاقي بـ ID، دور على أي زرار clickable في الأسفل
        return tryFindSendByDescription(root)
    }

    private fun tryFindSendByDescription(root: AccessibilityNodeInfo): Boolean {
        val keywords = listOf("send", "إرسال", "ارسال")
        fun search(node: AccessibilityNodeInfo): Boolean {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            if (node.isClickable && (keywords.any { desc.contains(it) } || keywords.any { text.contains(it) })) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (search(child)) return true
            }
            return false
        }
        return search(root)
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
        messageSent = false
        waitingForSend = false
        waitingForChat = false
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))

        val pkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone).replace("+", "")

        try {
            // افتح الشات المباشر دايماً أول حاجة
            val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$phone")
                setPackage(pkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            if (variant.mediaUri != null) {
                // ميديا: افتح الشات وانتظر يتفتح، بعدين ابعت الملف
                waitingForChat = true
                startActivity(chatIntent)
            } else {
                // نص: افتح الشات وانتظر تكتب
                waitingForSend = true
                startActivity(chatIntent)
            }
        } catch (e: Exception) {
            contact.sendStatus = SendStatus.FAILED
            SessionManager.currentIndex++
            sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
            handler.postDelayed({ sendNextMessage() }, 1000)
        }
    }

    fun pause() {
        SessionManager.isPaused = true
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        waitingForSend = false
        waitingForChat = false
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
        waitingForSend = false
        waitingForChat = false
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
