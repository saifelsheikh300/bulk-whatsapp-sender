package com.seif.bulkwhatsapp.data

data class MessageVariant(
    val message: String = "",
    val mediaUri: String? = null,
    val mediaType: String? = null,
    val mediaName: String? = null
)

data class SendSession(
    val contacts: List<Contact>,
    val variants: List<MessageVariant>,   // قائمة الرسائل المتعددة
    val useWhatsAppBusiness: Boolean,
    val delaySeconds: Int = 10
) {
    // بيختار رسالة عشوائية لكل جهة
    fun pickVariant(): MessageVariant {
        if (variants.isEmpty()) return MessageVariant()
        return variants.random()
    }
}

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
