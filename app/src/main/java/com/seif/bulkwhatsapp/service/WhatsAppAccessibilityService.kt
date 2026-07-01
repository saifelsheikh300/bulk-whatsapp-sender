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

    // الـ states
    enum class State {
        IDLE,
        WAITING_TEXT,       // نص فقط - منتظر الشات يفتح
        WAITING_TEXT_SEND,  // نص + ميديا - كتب النص في الشات، منتظر يضغط إرسال الشات
        WAITING_MEDIA       // ميديا - منتظر شاشة المعاينة وزرار الإرسال
    }

    private var state = State.IDLE
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var skipRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null
    private var currentPkg = "com.whatsapp"
    private var currentPhone = ""

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

        when (state) {

            // ─── نص فقط: نفس الكود الأصلي بالظبط ───
            State.WAITING_TEXT -> {
                val msg = currentVariant?.message ?: return
                val inputNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/entry")
                    ?.firstOrNull() ?: return
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                val sendNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/send")
                    ?.firstOrNull() ?: return
                if (sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    cancelSkip()
                    messageSent = true
                    state = State.IDLE
                    markSentAndNext()
                }
            }

            // ─── نص + ميديا: كتب النص في الشات الأول واضغط إرسال ───
            State.WAITING_TEXT_SEND -> {
                val msg = currentVariant?.message ?: ""
                val inputNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/entry")
                    ?.firstOrNull() ?: return
                // لو النص مكتوبش، اكتبه
                val currentText = inputNode.text?.toString() ?: ""
                if (currentText != msg && msg.isNotBlank()) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    return // استنى event تاني عشان يضغط إرسال
                }
                // اضغط إرسال النص
                val sendNode = root.findAccessibilityNodeInfosByViewId("$currentPkg:id/send")
                    ?.firstOrNull() ?: return
                if (sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    // بعد إرسال النص، ابعت الملف
                    state = State.WAITING_MEDIA
                    handler.postDelayed({
                        if (!messageSent) sendMediaFile()
                    }, 1000)
                }
            }

            // ─── ميديا: اضغط زرار الإرسال في شاشة المعاينة ───
            State.WAITING_MEDIA -> {
                val sendNode = findSendButton(root)
                if (sendNode != null && sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    cancelSkip()
                    messageSent = true
                    state = State.IDLE
                    markSentAndNext()
                }
            }

            else -> {}
        }
    }

    // بيبعت الملف بعد ما النص اتبعت
    private fun sendMediaFile() {
        val variant = currentVariant ?: return
        if (variant.mediaUri == null || variant.mediaType == null) return
        try {
            val uri = Uri.parse(variant.mediaUri)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = variant.mediaType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("jid", "$currentPhone@s.whatsapp.net")
                setPackage(currentPkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(shareIntent)
        } catch (e: Exception) {
            // لو فشل بعت الرسالة كـ sent بدون ميديا
            cancelSkip()
            messageSent = true
            state = State.IDLE
            markSentAndNext()
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("$currentPkg:id/send", "$currentPkg:id/caption_send", "$currentPkg:id/send_btn")) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable) return node
        }
        return findClickableByDesc(root, listOf("send", "إرسال", "ارسال"))
    }

    private fun findClickableByDesc(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (node.isClickable && keywords.any { desc.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val result = findClickableByDesc(node.getChild(i) ?: continue, keywords)
            if (result != null) return result
        }
        return null
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
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        pendingRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun startSkip(ms: Long) {
        cancelSkip()
        skipRunnable = Runnable {
            if (!messageSent) {
                val session = SessionManager.currentSession ?: return@Runnable
                val contact = session.contacts.getOrNull(SessionManager.currentIndex) ?: return@Runnable
                contact.sendStatus = SendStatus.FAILED
                state = State.IDLE
                messageSent = false
                currentVariant = null
                SessionManager.currentIndex++
                sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }.also { handler.postDelayed(it, ms) }
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

        val variant = session.pickVariant()
        currentVariant = variant
        contact.sendStatus = SendStatus.SENDING
        messageSent = false
        state = State.IDLE
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))

        currentPkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        currentPhone = PhoneUtils.normalizeEgyptianPhone(contact.phone).replace("+", "")

        val hasMedia = variant.mediaUri != null && variant.mediaType != null
        val hasText = variant.message.isNotBlank()

        try {
            val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$currentPhone")
                setPackage(currentPkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            when {
                // ─── ميديا بدون نص ───
                hasMedia && !hasText -> {
                    val uri = Uri.parse(variant.mediaUri)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = variant.mediaType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra("jid", "$currentPhone@s.whatsapp.net")
                        setPackage(currentPkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    state = State.WAITING_MEDIA
                    startSkip(8000)
                    startActivity(shareIntent)
                }

                // ─── نص + ميديا: نبعت النص الأول في الشات، بعدين الميديا ───
                hasMedia && hasText -> {
                    state = State.WAITING_TEXT_SEND
                    startSkip(15000)
                    startActivity(chatIntent)
                }

                // ─── نص فقط: نفس الأصل ───
                else -> {
                    state = State.WAITING_TEXT
                    startSkip(8000)
                    startActivity(chatIntent)
                }
            }

        } catch (e: Exception) {
            cancelSkip()
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
        cancelSkip()
        state = State.IDLE
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
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
        state = State.IDLE
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
