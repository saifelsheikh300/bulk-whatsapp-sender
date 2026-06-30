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
    private var attemptCount = 0
    private var retryRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service CONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning || SessionManager.isPaused) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }
        if (messageSent) return

        val pkg = event?.packageName?.toString() ?: return
        if (pkg != currentPkg) return

        if (waitingForSend || waitingForMediaSend) {
            attemptAction()
        }
    }

    /** بيحاول ينفذ الخطوة الحالية (كتابة/إرسال)، ولو فشل بيعيد المحاولة كل نص ثانية */
    private fun attemptAction() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        val root = rootInActiveWindow
        if (root == null) {
            scheduleRetry()
            return
        }

        val success = if (waitingForSend) {
            trySendTextMessage(root)
        } else {
            tryClickSendButton(root)
        }

        if (success) {
            messageSent = true
            waitingForSend = false
            waitingForMediaSend = false
            attemptCount = 0
            markSentAndNext()
        } else {
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        attemptCount++
        if (attemptCount > 20) { // بعد ~10 ثواني من المحاولات نعتبرها فشلت
            Log.e(TAG, "Giving up after $attemptCount attempts")
            attemptCount = 0
            val session = SessionManager.currentSession ?: return
            val contact = session.contacts.getOrNull(SessionManager.currentIndex)
            contact?.sendStatus = SendStatus.FAILED
            waitingForSend = false
            waitingForMediaSend = false
            SessionManager.currentIndex++
            sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
            handler.postDelayed({ sendNextMessage() }, 1000)
            return
        }
        val r = Runnable { attemptAction() }
        retryRunnable = r
        handler.postDelayed(r, 500)
    }

    // ───────── البحث عن الـ EditText (مربع الكتابة) ─────────
    private fun findMessageEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // محاولة 1: بالـ ID المعروف
        root.findAccessibilityNodeInfosByViewId("$currentPkg:id/entry")?.firstOrNull()?.let { return it }
        // محاولة 2: دور على أي EditText قابل للتعديل في الشاشة
        return findByClassName(root, "android.widget.EditText")
    }

    // ───────── البحث عن زرار الإرسال ─────────
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "$currentPkg:id/send",
            "$currentPkg:id/caption_send",
            "$currentPkg:id/send_btn",
            "$currentPkg:id/action_send",
            "$currentPkg:id/fab"
        )
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let {
                if (it.isClickable) return it
            }
        }
        // دور بالوصف (إرسال / send)
        findByDescription(root, listOf("send", "إرسال", "ارسال"))?.let { return it }
        // آخر حل: ImageButton قابل للضغط على يمين/يسار أسفل الشاشة (زرار الإرسال الدائري الأخضر)
        return findClickableImageButton(root)
    }

    private fun findByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className == className && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByClassName(child, className)
            if (result != null) return result
        }
        return null
    }

    private fun findByDescription(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (node.isClickable && keywords.any { desc.contains(it) || text.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByDescription(child, keywords)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableImageButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if ((node.className == "android.widget.ImageButton" || node.className == "android.widget.ImageView")
            && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableImageButton(child)
            if (result != null) return result
        }
        return null
    }

    private fun trySendTextMessage(root: AccessibilityNodeInfo): Boolean {
        val message = currentVariant?.message ?: return false
        val inputNode = findMessageEntry(root)
        if (inputNode == null) {
            Log.d(TAG, "entry box not found yet")
            return false
        }

        // لو لسه ما اتكتبش فيه نص، اكتب
        val currentText = inputNode.text?.toString() ?: ""
        if (currentText.isEmpty()) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            val setOk = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Set text result: $setOk, will click send next cycle")
            return false // امنح فرصة للنظام يحدث الشاشة، الزرار هيتدوس بالمحاولة الجاية
        }

        // النص موجود بالفعل، دور على زرار الإرسال واضغطه
        val freshRoot = rootInActiveWindow ?: root
        val sendNode = findSendButton(freshRoot)
        if (sendNode == null) {
            Log.d(TAG, "send button not found")
            return false
        }
        val clickOk = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Click send result: $clickOk")
        return clickOk
    }

    private fun tryClickSendButton(root: AccessibilityNodeInfo): Boolean {
        val sendNode = findSendButton(root) ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
        attemptCount = 0
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
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

        Log.d(TAG, "Sending to: ${contact.name} ($phone), hasMedia=${variant.mediaUri != null}")

        try {
            if (variant.mediaUri != null && variant.mediaType != null) {
                sendMedia(phone, variant)
            } else {
                sendText(phone)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception", e)
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
        startActivity(intent)
        // محاولة أولى يدوية بعد 2 ثانية تحسباً لعدم وصول حدث accessibility
        handler.postDelayed({ if (waitingForSend) attemptAction() }, 2000)
    }

    private fun sendMedia(phone: String, variant: MessageVariant) {
        val chatIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$phone")
            setPackage(currentPkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(chatIntent)

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
                startActivity(shareIntent)
                // محاولة أولى يدوية بعد 2 ثانية من فتح شاشة المعاينة
                handler.postDelayed({ if (waitingForMediaSend) attemptAction() }, 2000)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send media", e)
                val session = SessionManager.currentSession
                val contact = session?.contacts?.getOrNull(SessionManager.currentIndex)
                contact?.sendStatus = SendStatus.FAILED
                SessionManager.currentIndex++
                sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
                handler.postDelayed({ sendNextMessage() }, 1500)
            }
        }, 3000)
    }

    fun pause() {
        SessionManager.isPaused = true
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
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
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
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
