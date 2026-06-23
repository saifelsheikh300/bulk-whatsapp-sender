package com.seif.bulkwhatsapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.seif.bulkwhatsapp.data.Contact
import com.seif.bulkwhatsapp.databinding.ItemContactBinding

class ContactsAdapter(private val onChanged: () -> Unit) :
    ListAdapter<Contact, ContactsAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = getItem(position)
        with(holder.binding) {
            tvName.text = contact.name
            tvPhone.text = contact.phone
            cbContact.isChecked = contact.isSelected
            tvAvatar.text = contact.name.firstOrNull()?.uppercase() ?: "؟"

            root.setOnClickListener {
                contact.isSelected = !contact.isSelected
                cbContact.isChecked = contact.isSelected
                onChanged()
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Contact, newItem: Contact) = oldItem == newItem
    }
}
