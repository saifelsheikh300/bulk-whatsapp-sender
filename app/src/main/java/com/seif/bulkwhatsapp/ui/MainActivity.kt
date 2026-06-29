package com.seif.bulkwhatsapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.seif.bulkwhatsapp.R
import com.seif.bulkwhatsapp.data.MessageVariant
import com.seif.bulkwhatsapp.data.SendSession
import com.seif.bulkwhatsapp.data.SessionManager
import com.seif.bulkwhatsapp.databinding.ActivityMainBinding
import com.seif.bulkwhatsapp.service.FloatingBubbleService
import com.seif.bulkwhatsapp.service.WhatsAppAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var useWhatsAppBusiness = false
    private var selectedDelay = 10

    // قائمة الرسائل المتعددة
    private val variants = mutableListOf<MessageVariant>()

    // لتحديد لأي variant بنختار ميديا حاليًا
    private var pickingVariantIndex = -1

    companion object {
        const val REQUEST_CONTACTS = 100
        var selectedContacts = mutableListOf<com.seif.bulkwhatsapp.data.Contact>()
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && pickingVariantIndex >= 0 && pickingVariantIndex < variants.size) {
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            var fileName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) fileName = it.getString(idx)
                }
            }
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}

            variants[pickingVariantIndex] = variants[pickingVariantIndex].copy(
                mediaUri = uri.toString(),
                mediaType = mimeType,
                mediaName = fileName
            )
            refreshVariantsList()
        }
        pickingVariantIndex = -1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWhatsAppSelector()
        setupContactsCard()
        setupDelaySlider()
        setupVariants()
        setupStartButton()
        checkContactsPermission()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateContactsCount()
    }

    // ────────────── Accessibility ──────────────
    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityEnabled()
        binding.tvAccessibilityStatus.text = if (enabled) "مفعّلة ✓" else "غير مفعّلة - اضغط لتفعيلها"
        binding.tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.green_primary else R.color.red_error)
        )
        binding.btnEnableAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.btnEnableAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${WhatsAppAccessibilityService::class.java.canonicalName}"
        return (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(service)
    }

    // ────────────── WhatsApp selector ──────────────
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

    // ────────────── Contacts ──────────────
    private fun setupContactsCard() {
        binding.cardContacts.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                startActivity(Intent(this, ContactsActivity::class.java))
            else checkContactsPermission()
        }
    }

    private fun updateContactsCount() {
        binding.tvContactsCount.text = if (selectedContacts.isEmpty()) "لم يتم التحديد بعد"
        else "تم تحديد ${selectedContacts.size} جهة اتصال"
    }

    // ────────────── Delay slider ──────────────
    private fun setupDelaySlider() {
        binding.seekBarDelay.progress = 5
        binding.tvDelayValue.text = "10 ثانية"
        binding.seekBarDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                selectedDelay = p + 5
                binding.tvDelayValue.text = "$selectedDelay ثانية"
                binding.tvDelayValue.setTextColor(
                    ContextCompat.getColor(this@MainActivity, if (selectedDelay < 7) R.color.red_error else R.color.green_primary)
                )
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    // ────────────── Variants (الرسائل المتعددة) ──────────────
    private fun setupVariants() {
        // أضف رسالة فارغة أولى تلقائياً
        if (variants.isEmpty()) variants.add(MessageVariant())
        refreshVariantsList()

        binding.btnAddVariant.setOnClickListener {
            variants.add(MessageVariant())
            refreshVariantsList()
        }
    }

    private fun refreshVariantsList() {
        val container = binding.containerVariants
        container.removeAllViews()

        variants.forEachIndexed { index, variant ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_message_variant, container, false)

            val tvNum        = item.findViewById<TextView>(R.id.tvVariantNum)
            val etMessage    = item.findViewById<EditText>(R.id.etVariantMessage)
            val btnMedia     = item.findViewById<Button>(R.id.btnVariantMedia)
            val tvMediaName  = item.findViewById<TextView>(R.id.tvVariantMediaName)
            val btnRemMedia  = item.findViewById<Button>(R.id.btnVariantRemoveMedia)
            val btnDelete    = item.findViewById<Button>(R.id.btnDeleteVariant)

            tvNum.text = "رسالة ${index + 1}"
            etMessage.setText(variant.message)
            etMessage.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (index < variants.size)
                        variants[index] = variants[index].copy(message = s?.toString() ?: "")
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            // ميديا
            if (variant.mediaUri != null) {
                val icon = when {
                    variant.mediaType?.startsWith("image/") == true -> "🖼️"
                    variant.mediaType?.startsWith("video/") == true -> "🎥"
                    variant.mediaType?.startsWith("audio/") == true -> "🎵"
                    else -> "📎"
                }
                tvMediaName.text = "$icon ${variant.mediaName ?: "ملف"}"
                tvMediaName.visibility = View.VISIBLE
                btnRemMedia.visibility = View.VISIBLE
                btnMedia.text = "تغيير"
            } else {
                tvMediaName.visibility = View.GONE
                btnRemMedia.visibility = View.GONE
                btnMedia.text = "📎 ملف"
            }

            btnMedia.setOnClickListener {
                pickingVariantIndex = index
                pickMediaLauncher.launch("*/*")
            }

            btnRemMedia.setOnClickListener {
                variants[index] = variants[index].copy(mediaUri = null, mediaType = null, mediaName = null)
                refreshVariantsList()
            }

            // حذف variant (مش هيظهر لو في رسالة واحدة بس)
            if (variants.size > 1) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    variants.removeAt(index)
                    refreshVariantsList()
                }
            } else {
                btnDelete.visibility = View.GONE
            }

            container.addView(item)
        }

        // اظهر عدد الرسائل
        binding.tvVariantsCount.text = "سيتم الإرسال عشوائياً من ${variants.size} رسالة"
        binding.tvVariantsCount.visibility = if (variants.size > 1) View.VISIBLE else View.GONE
    }

    // ────────────── Start ──────────────
    private fun setupStartButton() {
        binding.btnStart.setOnClickListener {
            val hasContent = variants.any { it.message.isNotBlank() || it.mediaUri != null }
            when {
                !isAccessibilityEnabled() -> Toast.makeText(this, "يجب تفعيل خدمة الإمكانية أولاً", Toast.LENGTH_LONG).show()
                selectedContacts.isEmpty() -> Toast.makeText(this, "اختر جهات الاتصال أولاً", Toast.LENGTH_SHORT).show()
                !hasContent -> Toast.makeText(this, "اكتب رسالة واحدة على الأقل", Toast.LENGTH_SHORT).show()
                else -> startSending()
            }
        }
    }

    private fun startSending() {
        val validVariants = variants.filter { it.message.isNotBlank() || it.mediaUri != null }
        SessionManager.currentSession = SendSession(
            contacts = selectedContacts.map { it.copy() },
            variants = validVariants,
            useWhatsAppBusiness = useWhatsAppBusiness,
            delaySeconds = selectedDelay
        )
        SessionManager.currentIndex = 0
        SessionManager.isRunning = true
        SessionManager.isPaused = false

        if (Settings.canDrawOverlays(this))
            startService(Intent(this, FloatingBubbleService::class.java))

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
