package com.seif.bulkwhatsapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    // Track media send mode (media is sent via Share sheet, not accessibility typing)
    private var mediaSendMode = false

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

        // Media mode: look for send button after share sheet lands in WhatsApp
        if (mediaSendMode && !messageSent) {
            val root = rootInActiveWindow ?: return
            if (tryClickSendButton(root, expected)) {
                messageSent = true
                mediaSendMode = false
                waitingForSend = false
                session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
                sendProgressBroadcast()
                scheduleNext()
            }
            return
        }

        // Text mode
        if (waitingForSend && !messageSent) {
            val root = rootInActiveWindow ?: return
            if (trySendMessage(root, session.message)) {
                messageSent = true
                waitingForSend = false
                session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
                sendProgressBroadcast()
                scheduleNext()
            }
        }
    }

    private fun scheduleNext() {
        val session = SessionManager.currentSession ?: return
        val delayMs = session.delaySeconds * 1000L
        val runnable = Runnable {
            if (!SessionManager.isPaused && SessionManager.isRunning) {
                SessionManager.currentIndex++
                messageSent = false
                if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                else finishSession()
            }
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

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

    /** Tries to click the send button after a media share lands in WhatsApp */
    private fun tryClickSendButton(root: AccessibilityNodeInfo, pkg: String): Boolean {
        // WhatsApp send button IDs for the media preview screen
        val candidateIds = listOf("$pkg:id/send", "$pkg:id/caption_send")
        for (id in candidateIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
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

        val pkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = PhoneUtils.normalizeEgyptianPhone(contact.phone)

        try {
            if (session.mediaUri != null && session.mediaType != null) {
                // --- MEDIA SEND ---
                // Step 1: open chat via whatsapp URI
                val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(chatIntent)

                // Step 2: after a short delay, fire the share intent into the open chat
                waitingForSend = false
                messageSent = false
                mediaSendMode = false

                handler.postDelayed({
                    try {
                        val uri = Uri.parse(session.mediaUri)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = session.mediaType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            // Add text caption if message is not empty
                            if (session.message.isNotBlank()) {
                                putExtra(Intent.EXTRA_TEXT, session.message)
                            }
                            setPackage(pkg)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        mediaSendMode = true
                        startActivity(shareIntent)
                    } catch (e: Exception) {
                        contact.sendStatus = SendStatus.FAILED
                        SessionManager.currentIndex++
                        sendProgressBroadcast()
                        handler.postDelayed({ sendNextMessage() }, 1000)
                    }
                }, 2500) // Wait 2.5s for WhatsApp chat to open

            } else {
                // --- TEXT SEND ---
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${phone.replace("+", "")}")
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                waitingForSend = true
                messageSent = false
                startActivity(intent)
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
        waitingForSend = false
        mediaSendMode = false
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
        mediaSendMode = false
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
