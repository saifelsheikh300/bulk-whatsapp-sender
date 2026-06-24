package com.seif.bulkwhatsapp.data

data class SendSession(
    val contacts: List<Contact>,
    val message: String,
    val useWhatsAppBusiness: Boolean,
    val delaySeconds: Int = 10
)

object SessionManager {
    var currentSession: SendSession? = null
    var currentIndex: Int = 0
    var isRunning: Boolean = false
}
