package com.seif.bulkwhatsapp.data

data class SendSession(
    val contacts: List<Contact>,
    val message: String,
    val useWhatsAppBusiness: Boolean
)

object SessionManager {
    var currentSession: SendSession? = null
    var currentIndex: Int = 0
    var isRunning: Boolean = false
}
