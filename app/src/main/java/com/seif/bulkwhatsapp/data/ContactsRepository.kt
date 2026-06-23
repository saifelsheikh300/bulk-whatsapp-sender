package com.seif.bulkwhatsapp.data

import android.content.Context
import android.provider.ContactsContract

object ContactsRepository {
    fun loadContacts(context: Context): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val seen = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val id = it.getString(idIdx) ?: continue
                val name = it.getString(nameIdx) ?: continue
                val phone = it.getString(phoneIdx)?.replace(Regex("[\\s\\-()]"), "") ?: continue

                if (!seen.contains(phone)) {
                    seen.add(phone)
                    contacts.add(Contact(id = id, name = name, phone = phone))
                }
            }
        }
        return contacts
    }
}
