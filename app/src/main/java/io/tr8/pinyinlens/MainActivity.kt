package io.tr8.pinyinlens

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.tr8.pinyinlens.databinding.ActivityMainBinding
import io.tr8.pinyinlens.pinyin.PinyinEngine
import io.tr8.pinyinlens.overlay.PinyinAccessibilityService
import io.tr8.pinyinlens.toggle.Lens
import io.tr8.pinyinlens.toggle.Overlay
import io.tr8.pinyinlens.toggle.NotificationController
import io.tr8.pinyinlens.toggle.Prefs
import io.tr8.pinyinlens.toggle.Prefs.notificationEnabled
import io.tr8.pinyinlens.toggle.Prefs.overlayScalePercent
import io.tr8.pinyinlens.toggle.Prefs.onboarded
import io.tr8.pinyinlens.toggle.Prefs.textSizeSp
import io.tr8.pinyinlens.toggle.Prefs.toneColors
import io.tr8.pinyinlens.toggle.PinyinTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var previewJob: Job? = null

    /** Set when we send the user to settings, so we can finish on their return. */
    private var awaitingGrant = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationEnabled = granted
        binding.notificationSwitch.isChecked = granted
        NotificationController.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ forces edge-to-edge, so without this the title sits
        // under the status bar and the last row under the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Every listener guards on isPressed: onResume writes the switches back
        // from the real state, and without the guard those writes would echo
        // straight back out as PackageManager and preference writes.
        binding.lensSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            Lens.setEnabled(this, checked)
        }

        binding.notificationSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (checked && !NotificationController.canPost(this)) {
                requestNotificationPermission()
            } else {
                notificationEnabled = checked
                NotificationController.refresh(this)
            }
        }

        binding.toneSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            toneColors = checked
            binding.preview.toneColors = checked
            PinyinAccessibilityService.instance?.onToneColorsChanged()
        }

        // Tiramisu can ask the system to offer the tile directly; before that
        // the user has to go find it in the Quick Settings editor themselves.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.addTile.visibility = View.VISIBLE
            binding.tileHint.visibility = View.GONE
            binding.addTile.setOnClickListener { requestAddTile() }
        }

        binding.overlaySwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (checked && !Overlay.isServiceGranted(this)) {
                // Nothing to turn on yet — the grant only happens in system
                // settings, so explain the trip rather than just sending them.
                button.isChecked = false
                showOverlaySetup()
            } else {
                Overlay.setEnabled(this, checked)
            }
        }

        binding.grantAccessibility.setOnClickListener { showOverlaySetup() }
        binding.helpButton.setOnClickListener { showWelcome() }

        binding.sizeSlider.apply {
            valueFrom = Prefs.TEXT_SIZE_MIN
            valueTo = Prefs.TEXT_SIZE_MAX
            stepSize = Prefs.TEXT_SIZE_STEP
            setLabelFormatter { "${it.toInt()} sp" }
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                textSizeSp = value
                binding.preview.baseTextSizeSp = value
            }
        }

        binding.overlaySizeSlider.apply {
            valueFrom = Prefs.OVERLAY_SCALE_MIN
            valueTo = Prefs.OVERLAY_SCALE_MAX
            stepSize = Prefs.OVERLAY_SCALE_STEP
            setLabelFormatter { "${it.toInt()}%" }
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                overlayScalePercent = value
                PinyinAccessibilityService.instance?.onTextSizeChanged()
            }
        }

        binding.preview.baseTextColor = getColor(R.color.text_primary)
        binding.preview.rubyTextColor = getColor(R.color.text_secondary)

        binding.sample.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderPreview(s?.toString().orEmpty())
        })

        // A trip to system settings can outlive this activity, and the dialog
        // promised the switch would come back on by itself.
        awaitingGrant = savedInstanceState?.getBoolean(STATE_AWAITING_GRANT) ?: false

        if (savedInstanceState == null) {
            binding.sample.setText(R.string.sample_text)
            if (!onboarded) {
                showWelcome()
                onboarded = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_GRANT, awaitingGrant)
    }

    override fun onResume() {
        super.onResume()
        // The tile or the notification may have flipped things while we were away.
        binding.lensSwitch.isChecked = Lens.isEnabled(this)
        binding.toneSwitch.isChecked = toneColors
        binding.preview.toneColors = toneColors
        binding.sizeSlider.value = textSizeSp
        binding.overlaySizeSlider.value = overlayScalePercent
        binding.preview.baseTextSizeSp = textSizeSp
        binding.notificationSwitch.isChecked =
            notificationEnabled && NotificationController.canPost(this)

        if (notificationEnabled && !NotificationController.canPost(this)) {
            requestNotificationPermission()
        }
        val granted = Overlay.isServiceGranted(this)
        if (awaitingGrant && granted) {
            // They went to settings from the dialog and granted it; finish the
            // job rather than making them find the switch again.
            awaitingGrant = false
            Overlay.setEnabled(this, true)
            Toast.makeText(this, R.string.overlay_enabled_toast, Toast.LENGTH_SHORT).show()
        }
        binding.overlaySwitch.isChecked = granted && Overlay.isEnabled(this)
        binding.grantAccessibility.visibility = if (granted) View.GONE else View.VISIBLE

        NotificationController.refresh(this)
        renderPreview(binding.sample.text?.toString().orEmpty())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, PinyinTileService::class.java),
            getString(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_notification),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showWelcome() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(HtmlCompat.fromHtml(
                getString(R.string.welcome_body), HtmlCompat.FROM_HTML_MODE_COMPACT,
            ))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun showOverlaySetup() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.overlay_setup_title)
            .setMessage(HtmlCompat.fromHtml(
                getString(R.string.overlay_setup_body), HtmlCompat.FROM_HTML_MODE_COMPACT,
            ))
            .setPositiveButton(R.string.overlay_setup_open) { _, _ ->
                awaitingGrant = true
                openAccessibilitySettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val STATE_AWAITING_GRANT = "awaiting_grant"
    }

    private fun renderPreview(text: String) {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val tokens = withContext(Dispatchers.Default) {
                PinyinEngine.annotate(this@MainActivity, text)
            }
            binding.preview.tokens = tokens
        }
    }
}
