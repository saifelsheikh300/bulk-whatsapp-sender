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
    private var state = State.IDLE
    private var messageSent = false
    private var pendingRunnable: Runnable? = null
    private var skipRunnable: Runnable? = null
    private var currentVariant: MessageVariant? = null
    private var currentPkg = "com.whatsapp"

    enum class State { IDLE, WAITING_TEXT, WAITING_MEDIA_SEND }

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

            State.WAITING_TEXT -> {
                // ─── نفس منطق الأصل بالظبط ───
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

            State.WAITING_MEDIA_SEND -> {
                // لو فيه نص → اكتبه في حقل الـ caption الأول قبل الإرسال
                val caption = currentVariant?.message ?: ""
                if (caption.isNotBlank()) {
                    // دور على حقل الـ caption (واتساب بيسميه entry أو caption)
                    val captionIds = listOf(
                        "$currentPkg:id/caption",
                        "$currentPkg:id/entry"
                    )
                    for (cId in captionIds) {
                        val captionNode = root.findAccessibilityNodeInfosByViewId(cId)?.firstOrNull()
                        if (captionNode != null && captionNode.isEditable) {
                            val currentText = captionNode.text?.toString() ?: ""
                            if (currentText.isEmpty()) {
                                // اكتب النص في حقل الـ caption
                                val args = Bundle()
                                args.putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    caption
                                )
                                captionNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                return // استنى الـ event الجاي عشان يضغط إرسال
                            }
                            break
                        }
                    }
                }
                // دور على زرار الإرسال في شاشة معاينة الميديا
                for (id in listOf("$currentPkg:id/send", "$currentPkg:id/caption_send")) {
                    val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
                    if (node != null && node.isClickable) {
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            cancelSkip()
                            messageSent = true
                            state = State.IDLE
                            markSentAndNext()
                            return
                        }
                    }
                }
            }

            else -> {}
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
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone).replace("+", "")

        try {
            if (variant.mediaUri != null && variant.mediaType != null) {
                // ─── ميديا ───
                // بنبعت ACTION_SEND مع jid مباشرة - ده بيفتح شاشة معاينة الملف
                // في شات الشخص ده مباشرة بدون شاشة "إرسال إلى..."
                val uri = Uri.parse(variant.mediaUri)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = variant.mediaType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra("jid", "$phone@s.whatsapp.net")
                    setPackage(currentPkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                state = State.WAITING_MEDIA_SEND
                // skip لو مفيش واتساب: 8 ثواني
                startSkip(8000)
                startActivity(shareIntent)

            } else {
                // ─── نص فقط: نفس منطق الأصل بالظبط ───
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$phone")
                    setPackage(currentPkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                state = State.WAITING_TEXT
                // skip لو مفيش واتساب: 8 ثواني
                startSkip(8000)
                startActivity(intent)
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
