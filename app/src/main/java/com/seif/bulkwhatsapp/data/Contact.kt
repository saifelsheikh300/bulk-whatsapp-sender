package com.seif.bulkwhatsapp.data

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    var isSelected: Boolean = false,
    var sendStatus: SendStatus = SendStatus.PENDING
)

enum class SendStatus {
    PENDING, SENDING, SENT, FAILED
}
