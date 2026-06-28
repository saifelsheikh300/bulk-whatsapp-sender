package com.seif.bulkwhatsapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.seif.bulkwhatsapp.R
import com.seif.bulkwhatsapp.data.SendSession
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.databinding.ActivityMainBinding
import com.seif.bulkwhatsapp.service.FloatingBubbleService
import com.seif.bulkwhatsapp.service.WhatsAppAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var useWhatsAppBusiness = false
    private var selectedDelay = 10

    // Media attachment
    private var selectedMediaUri: Uri? = null
    private var selectedMediaType: String? = null
    private var selectedMediaName: String? = null

    companion object {
        const val REQUEST_CONTACTS = 100
        var selectedContacts = mutableListOf<com.seif.bulkwhatsapp.data.Contact>()
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedMediaUri = uri
            selectedMediaType = contentResolver.getType(uri) ?: "application/octet-stream"
            // Get file name for display
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) selectedMediaName = it.getString(nameIdx)
                }
            }
            // Grant persistent read permission
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            updateMediaUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWhatsAppSelector()
        setupContactsCard()
        setupMessageInput()
        setupDelaySlider()
        setupMediaPicker()
        setupStartButton()
        checkContactsPermission()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateContactsCount()
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityEnabled()
        if (enabled) {
            binding.tvAccessibilityStatus.text = "مفعّلة ✓"
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.green_primary))
            binding.btnEnableAccessibility.visibility = View.GONE
        } else {
            binding.tvAccessibilityStatus.text = "غير مفعّلة - اضغط لتفعيلها"
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.red_error))
            binding.btnEnableAccessibility.visibility = View.VISIBLE
        }
        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${WhatsAppAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(service)
    }

    private fun setupWhatsAppSelector() {
        updateWhatsAppSelection()
        binding.btnWhatsAppNormal.setOnClickListener { useWhatsAppBusiness = false; updateWhatsAppSelection() }
        binding.btnWhatsAppBusiness.setOnClickListener { useWhatsAppBusiness = true; updateWhatsAppSelection() }
    }

    private fun updateWhatsAppSelection() {
        val on = ContextCompat.getColor(this, R.color.green_primary)
        val off = ContextCompat.getColor(this, R.color.bg_input)
        binding.btnWhatsAppNormal.setBackgroundColor(if (!useWhatsAppBusiness) on else off)
        binding.btnWhatsAppBusiness.setBackgroundColor(if (useWhatsAppBusiness) on else off)
    }

    private fun setupContactsCard() {
        binding.cardContacts.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                startActivity(Intent(this, ContactsActivity::class.java))
            else checkContactsPermission()
        }
    }

    private fun updateContactsCount() {
        val count = selectedContacts.size
        binding.tvContactsCount.text = if (count == 0) "لم يتم التحديد بعد" else "تم تحديد $count جهة اتصال"
    }

    private fun setupMessageInput() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { binding.tvCharCount.text = "${s?.length ?: 0} حرف" }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupDelaySlider() {
        binding.seekBarDelay.progress = 5
        binding.tvDelayValue.text = "10 ثانية"
        binding.seekBarDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedDelay = progress + 5
                binding.tvDelayValue.text = "$selectedDelay ثانية"
                val color = if (selectedDelay < 7) ContextCompat.getColor(this@MainActivity, R.color.red_error)
                            else ContextCompat.getColor(this@MainActivity, R.color.green_primary)
                binding.tvDelayValue.setTextColor(color)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupMediaPicker() {
        binding.btnPickMedia.setOnClickListener {
            // Pick any file type
            pickMediaLauncher.launch("*/*")
        }
        binding.btnRemoveMedia.setOnClickListener {
            selectedMediaUri = null
            selectedMediaType = null
            selectedMediaName = null
            updateMediaUI()
        }
        updateMediaUI()
    }

    private fun updateMediaUI() {
        if (selectedMediaUri != null) {
            val icon = when {
                selectedMediaType?.startsWith("image/") == true -> "🖼️"
                selectedMediaType?.startsWith("video/") == true -> "🎥"
                selectedMediaType?.startsWith("audio/") == true -> "🎵"
                else -> "📎"
            }
            binding.tvMediaName.text = "$icon ${selectedMediaName ?: "ملف محدد"}"
            binding.tvMediaName.visibility = View.VISIBLE
            binding.btnRemoveMedia.visibility = View.VISIBLE
            binding.btnPickMedia.text = "تغيير الملف"
        } else {
            binding.tvMediaName.visibility = View.GONE
            binding.btnRemoveMedia.visibility = View.GONE
            binding.btnPickMedia.text = "📎 إرفاق صورة / فيديو / صوت"
        }
    }

    private fun setupStartButton() {
        binding.btnStart.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            val hasMedia = selectedMediaUri != null
            when {
                !isAccessibilityEnabled() -> Toast.makeText(this, "يجب تفعيل خدمة الإمكانية أولاً", Toast.LENGTH_LONG).show()
                selectedContacts.isEmpty() -> Toast.makeText(this, "اختر جهات الاتصال أولاً", Toast.LENGTH_SHORT).show()
                !hasMedia && message.isEmpty() -> Toast.makeText(this, "اكتب رسالة أو اختر ملف للإرسال", Toast.LENGTH_SHORT).show()
                else -> startSending(message)
            }
        }
    }

    private fun startSending(message: String) {
        val contacts = selectedContacts.map { it.copy() }
        SessionManager.currentSession = SendSession(
            contacts = contacts,
            message = message,
            useWhatsAppBusiness = useWhatsAppBusiness,
            delaySeconds = selectedDelay,
            mediaUri = selectedMediaUri?.toString(),
            mediaType = selectedMediaType
        )
        SessionManager.currentIndex = 0
        SessionManager.isRunning = true
        SessionManager.isPaused = false

        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingBubbleService::class.java))
        }
        startActivity(Intent(this, ProgressActivity::class.java))
        WhatsAppAccessibilityService.instance?.sendNextMessage()
    }

    private fun checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "تم منح الإذن ✓", Toast.LENGTH_SHORT).show()
    }
}
