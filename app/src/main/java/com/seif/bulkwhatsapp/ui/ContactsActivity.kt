package com.seif.bulkwhatsapp.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.seif.bulkwhatsapp.data.Contact
import com.seif.bulkwhatsapp.data.ContactsRepository
import com.seif.bulkwhatsapp.databinding.ActivityContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private lateinit var adapter: ContactsAdapter
    private var allContacts = listOf<Contact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupSelectAll()
        setupConfirmButton()
        loadContacts()

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter { updateUI() }
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                ContactsRepository.loadContacts(this@ContactsActivity)
            }
            allContacts = contacts
            // Restore previous selection
            allContacts.forEach { c ->
                if (MainActivity.selectedContacts.any { it.phone == c.phone }) c.isSelected = true
            }
            adapter.submitList(allContacts.toMutableList())
            binding.tvTotalContacts.text = "${allContacts.size} جهة اتصال"
            updateUI()
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                val filtered = if (query.isEmpty()) allContacts
                else allContacts.filter { it.name.contains(query, true) || it.phone.contains(query) }
                adapter.submitList(filtered.toMutableList())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupSelectAll() {
        binding.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            allContacts.forEach { it.isSelected = checked }
            adapter.submitList(allContacts.toMutableList())
            updateUI()
        }
    }

    private fun updateUI() {
        val selectedCount = allContacts.count { it.isSelected }
        binding.tvSelectedCount.text = "$selectedCount محدد"
        binding.cbSelectAll.setOnCheckedChangeListener(null)
        binding.cbSelectAll.isChecked = allContacts.isNotEmpty() && selectedCount == allContacts.size
        binding.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            allContacts.forEach { it.isSelected = checked }
            adapter.submitList(allContacts.toMutableList())
            updateUI()
        }
    }

    private fun setupConfirmButton() {
        binding.btnConfirm.setOnClickListener {
            MainActivity.selectedContacts = allContacts.filter { it.isSelected }.toMutableList()
            finish()
        }
    }
}
