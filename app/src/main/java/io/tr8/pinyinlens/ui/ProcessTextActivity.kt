package io.tr8.pinyinlens.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.tr8.pinyinlens.R
import io.tr8.pinyinlens.pinyin.Definitions
import io.tr8.pinyinlens.pinyin.PinyinEngine
import io.tr8.pinyinlens.pinyin.RubyToken
import io.tr8.pinyinlens.speech.Speaker
import io.tr8.pinyinlens.toggle.Prefs.speechEnabled
import io.tr8.pinyinlens.toggle.Prefs.textSizeSp
import io.tr8.pinyinlens.toggle.Prefs.thirdToneSandhi
import io.tr8.pinyinlens.toggle.Prefs.toneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point from the text-selection toolbar. Shows the selection with pinyin
 * over it in a sheet, then gets out of the way.
 */
class ProcessTextActivity : AppCompatActivity() {

    private lateinit var rubyView: RubyTextView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var truncatedNotice: TextView
    private lateinit var copyButton: View

    private lateinit var definition: TextView
    private lateinit var speakButton: View
    private var speaker: Speaker? = null
    private var dialog: BottomSheetDialog? = null
    private var annotateJob: Job? = null
    private var tokens: List<RubyToken> = emptyList()
    private var wantsClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An editable field launches us for a result and would substitute
        // whatever we hand back. We only ever read, so cancel explicitly.
        setResult(RESULT_CANCELED)

        wantsClipboard = intent.action == ACTION_READ_CLIPBOARD

        if (!wantsClipboard && readSelection(intent) == null) {
            // Silently finishing here is indistinguishable from the app being
            // broken, which is exactly how it looks to someone tapping Pinyin
            // and getting nothing.
            Toast.makeText(this, R.string.no_text_received, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val view = layoutInflater.inflate(R.layout.sheet_pinyin, null)
        rubyView = view.findViewById(R.id.ruby)
        progress = view.findViewById(R.id.progress)
        empty = view.findViewById(R.id.empty)
        truncatedNotice = view.findViewById(R.id.truncated)
        copyButton = view.findViewById(R.id.copy)
        definition = view.findViewById(R.id.definition)
        speakButton = view.findViewById(R.id.speak)

        rubyView.toneColors = toneColors
        rubyView.baseTextSizeSp = textSizeSp
        rubyView.baseTextColor = getColor(R.color.text_primary)
        rubyView.rubyTextColor = getColor(R.color.text_secondary)
        copyButton.setOnClickListener { copyPinyin() }

        rubyView.onWordTap = { word -> onWordTapped(word) }

        if (speechEnabled) {
            speaker = Speaker(this) { toast(getString(R.string.speech_unavailable)) }
            speakButton.visibility = View.VISIBLE
            speakButton.setOnClickListener {
                speaker?.speak(tokens.filter { it.annotation != null }.joinToString("") { it.base })
            }
        }

        dialog = BottomSheetDialog(this).apply {
            setContentView(view)
            setOnDismissListener { finish() }
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            show()
        }

        if (wantsClipboard) awaitFocusThenReadClipboard() else bind(intent)
    }

    /**
     * Since Android 10 the clipboard may only be read by an app that holds
     * focus. The sheet is a dialog, so it is the *dialog's* window that gets
     * focus, not the activity's — Activity.onWindowFocusChanged never fires
     * here and reading in onCreate or onResume is too early.
     */
    private fun awaitFocusThenReadClipboard() {
        val decor = dialog?.window?.decorView ?: return
        decor.viewTreeObserver.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    decor.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    if (!wantsClipboard) return
                    wantsClipboard = false

                    val text = clipboardText()
                    if (text.isNullOrBlank()) {
                        Toast.makeText(
                            this@ProcessTextActivity,
                            R.string.clipboard_empty,
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                        return
                    }
                    bind(
                        Intent(Intent.ACTION_PROCESS_TEXT)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    )
                }
            }
        )
    }

    /**
     * A second selection is delivered to the instance already on top rather
     * than starting a new one. Rebind the existing sheet — recreating the
     * activity here would strand the old dialog window on screen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (dialog == null) return
        bind(intent)
    }

    private fun onWordTapped(word: String) {
        if (speechEnabled) speaker?.speak(word)
        lifecycleScope.launch {
            val found = Definitions.lookupOrChar(this@ProcessTextActivity, word)
            definition.text = when {
                found == null -> getString(R.string.no_definition, word)
                found.first == word -> "$word — ${found.second}"
                // Fell back to the first character, so say which.
                else -> "${found.first} — ${found.second}"
            }
            definition.visibility = View.VISIBLE
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        // Clear the listener first: dismissing here would otherwise re-enter
        // finish() while the activity is already going away.
        speaker?.release()
        speaker = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun bind(intent: Intent) {
        val selection = readSelection(intent)
        if (selection == null) {
            finish()
            return
        }

        annotateJob?.cancel()
        tokens = emptyList()
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        definition.visibility = View.GONE
        rubyView.visibility = View.GONE
        copyButton.visibility = View.GONE

        if (selection.length < selectionLength(intent)) {
            truncatedNotice.text = getString(R.string.truncated, MAX_CHARS)
            truncatedNotice.visibility = View.VISIBLE
        } else {
            truncatedNotice.visibility = View.GONE
        }

        annotate(selection)
    }

    private fun rawSelection(intent: Intent): String? {
        val fromSelection = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
        if (fromSelection != null) return fromSelection.toString()
        // Shared from an app whose selection toolbar never offers us.
        if (intent.action == Intent.ACTION_SEND) {
            return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }
        return null
    }

    /**
     * The clipboard can only be read with window focus since Android 10, so
     * this is deferred until [onWindowFocusChanged] rather than read here.
     */
    private fun clipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun selectionLength(intent: Intent) = rawSelection(intent)?.length ?: 0

    /**
     * Measure and draw are linear in character count, so a pathological
     * selection is capped rather than left to stall the sheet.
     */
    private fun readSelection(intent: Intent): String? =
        rawSelection(intent)?.takeIf { it.isNotBlank() }?.take(MAX_CHARS)

    private fun annotate(text: String) {
        annotateJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                PinyinEngine.annotate(this@ProcessTextActivity, text, thirdToneSandhi)
            }
            tokens = result
            progress.visibility = View.GONE

            if (result.none { it.annotation != null }) {
                empty.visibility = View.VISIBLE
            } else {
                rubyView.tokens = result
                rubyView.visibility = View.VISIBLE
                copyButton.visibility = View.VISIBLE
                definition.setText(R.string.definition_hint)
                definition.visibility = View.VISIBLE
            }
        }
    }

    private fun copyPinyin() {
        if (tokens.isEmpty()) return
        val plain = buildString {
            for (token in tokens) {
                if (token.annotation != null) {
                    if (isNotEmpty() && last() != ' ' && last() != '\n') append(' ')
                    append(token.annotation)
                } else {
                    append(token.base)
                }
            }
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pinyin", plain))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Launched from the Quick Settings tile: read whatever was copied. */
        const val ACTION_READ_CLIPBOARD = "io.tr8.pinyinlens.READ_CLIPBOARD"

        private const val MAX_CHARS = 5_000
    }
}
