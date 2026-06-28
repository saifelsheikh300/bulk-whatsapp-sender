package com.seif.bulkwhatsapp.data

data class SendSession(
    val contacts: List<Contact>,
    val message: String,
    val useWhatsAppBusiness: Boolean,
    val delaySeconds: Int = 10,
    val mediaUri: String? = null,       // URI string of selected file
    val mediaType: String? = null       // MIME type e.g. "image/jpeg", "video/mp4", "audio/ogg"
)

object SessionManager {
    var currentSession: SendSession? = null
    var currentIndex: Int = 0
    var isRunning: Boolean = false
    var isPaused: Boolean = false

    val sentCount get() = currentSession?.contacts?.count { it.sendStatus == SendStatus.SENT } ?: 0
    val totalCount get() = currentSession?.contacts?.size ?: 0
    val remainingCount get() = totalCount - sentCount
    val progressPercent get() = if (totalCount > 0) (sentCount * 100 / totalCount) else 0
}
