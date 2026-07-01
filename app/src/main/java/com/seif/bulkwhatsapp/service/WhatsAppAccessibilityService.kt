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
    private var messageSent = false
    private var mediaSendMode = false
    private var pendingRunnable: Runnable? = null
    private var skipRunnable: Runnable? = null
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

        if (mediaSendMode && !messageSent) {
            val root = rootInActiveWindow ?: return
            if (tryClickSendButton(root, expected)) {
                cancelSkipTimeout()
                messageSent = true; mediaSendMode = false
                markSentAndNext()
            }
            return
        }

        if (waitingForSend && !messageSent) {
            val root = rootInActiveWindow ?: return
            val msg = currentVariant?.message ?: return
            if (trySendMessage(root, msg, expected)) {
                cancelSkipTimeout()
                messageSent = true; waitingForSend = false
                markSentAndNext()
            }
        }
    }

    // ── Skip timeout: لو مفيش واتساب على الرقم ──
    private fun startSkipTimeout(ms: Long) {
        cancelSkipTimeout()
        val r = Runnable {
            if (!messageSent) {
                val session = SessionManager.currentSession ?: return@Runnable
                val contact = session.contacts.getOrNull(SessionManager.currentIndex) ?: return@Runnable
                contact.sendStatus = SendStatus.FAILED
                waitingForSend = false
                mediaSendMode = false
                messageSent = false
                currentVariant = null
                SessionManager.currentIndex++
                sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        skipRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun cancelSkipTimeout() {
        skipRunnable?.let { handler.removeCallbacks(it) }
        skipRunnable = null
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

    private fun trySendMessage(root: AccessibilityNodeInfo, message: String, pkg: String): Boolean {
        val inputNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")?.firstOrNull() ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sendNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")?.firstOrNull() ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tryClickSendButton(root: AccessibilityNodeInfo, pkg: String): Boolean {
        for (id in listOf("$pkg:id/send", "$pkg:id/caption_send")) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
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
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))

        val pkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone)

        try {
            if (variant.mediaUri != null && variant.mediaType != null) {
                // ميديا: افتح الشات الأول
                val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                mediaSendMode = false
                startActivity(chatIntent)

                // لو مفيش واتساب الشات مش هيفتح → skip بعد 20 ثانية
                startSkipTimeout(20000)

                handler.postDelayed({
                    if (!messageSent) {
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
                            mediaSendMode = true
                            startActivity(shareIntent)
                        } catch (e: Exception) {
                            cancelSkipTimeout()
                            contact.sendStatus = SendStatus.FAILED
                            SessionManager.currentIndex++
                            sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
                            handler.postDelayed({ sendNextMessage() }, 1000)
                        }
                    }
                }, 2500)

            } else {
                // نص: افتح الشات
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                waitingForSend = true
                startActivity(intent)
                // لو مفيش واتساب → skip بعد 8 ثواني
                startSkipTimeout(8000)
            }
        } catch (e: Exception) {
            cancelSkipTimeout()
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
        cancelSkipTimeout()
        waitingForSend = false
        mediaSendMode = false
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
        SessionManager.isRunning = false; SessionManager.isPaused = false
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        cancelSkipTimeout()
        waitingForSend = false; mediaSendMode = false
        val i = Intent(ACTION_UPDATE_PROGRESS); i.putExtra("finished", true); sendBroadcast(i)
    }

    private fun finishSession() {
        SessionManager.isRunning = false; SessionManager.isPaused = false
        val i = Intent(ACTION_UPDATE_PROGRESS); i.putExtra("finished", true); sendBroadcast(i)
    }

    override fun onInterrupt() {}
}
