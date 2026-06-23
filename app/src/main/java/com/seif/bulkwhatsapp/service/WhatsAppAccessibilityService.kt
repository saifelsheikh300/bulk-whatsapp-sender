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

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhatsAppAccessibilityService? = null
        const val DELAY_BETWEEN_MESSAGES = 10000L
        const val ACTION_UPDATE_PROGRESS = "com.seif.bulkwhatsapp.UPDATE_PROGRESS"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var waitingForSend = false
    private var messageSent = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isRunning) return
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }

        val pkg = event?.packageName?.toString() ?: return
        val expected = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        if (pkg != expected) return

        if (waitingForSend && !messageSent) {
            val root = rootInActiveWindow ?: return
            if (trySendMessage(root, session.message)) {
                messageSent = true
                waitingForSend = false
                session.contacts[SessionManager.currentIndex].sendStatus = SendStatus.SENT
                sendProgressBroadcast()
                handler.postDelayed({
                    SessionManager.currentIndex++
                    messageSent = false
                    if (SessionManager.currentIndex < session.contacts.size) sendNextMessage()
                    else finishSession()
                }, DELAY_BETWEEN_MESSAGES)
            }
        }
    }

    private fun trySendMessage(root: AccessibilityNodeInfo, message: String): Boolean {
        val pkg = if (SessionManager.currentSession?.useWhatsAppBusiness == true) "com.whatsapp.w4b" else "com.whatsapp"
        val inputNode = (root.findAccessibilityNodeInfosByViewId("$pkg:id/entry") ?: return false).firstOrNull() ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sendNode = (root.findAccessibilityNodeInfosByViewId("$pkg:id/send") ?: return false).firstOrNull() ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun sendNextMessage() {
        val session = SessionManager.currentSession ?: return
        if (SessionManager.currentIndex >= session.contacts.size) { finishSession(); return }
        val contact = session.contacts[SessionManager.currentIndex]
        contact.sendStatus = SendStatus.SENDING
        sendProgressBroadcast()
        val pkg = if (session.useWhatsAppBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val phone = contact.phone.replace(Regex("[^0-9+]"), "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$phone")
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

    private fun sendProgressBroadcast() {
        sendBroadcast(Intent(ACTION_UPDATE_PROGRESS))
    }

    private fun finishSession() {
        SessionManager.isRunning = false
        val i = Intent(ACTION_UPDATE_PROGRESS)
        i.putExtra("finished", true)
        sendBroadcast(i)
    }

    override fun onInterrupt() {}
}
