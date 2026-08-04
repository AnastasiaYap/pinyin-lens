package io.tr8.pinyinlens.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.tr8.pinyinlens.R
import io.tr8.pinyinlens.pinyin.PinyinEngine
import io.tr8.pinyinlens.pinyin.RubyToken
import io.tr8.pinyinlens.toggle.Prefs.textSizeSp
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

    private var dialog: BottomSheetDialog? = null
    private var annotateJob: Job? = null
    private var tokens: List<RubyToken> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An editable field launches us for a result and would substitute
        // whatever we hand back. We only ever read, so cancel explicitly.
        setResult(RESULT_CANCELED)

        if (readSelection(intent) == null) {
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

        rubyView.toneColors = toneColors
        rubyView.baseTextSizeSp = textSizeSp
        rubyView.baseTextColor = getColor(R.color.text_primary)
        rubyView.rubyTextColor = getColor(R.color.text_secondary)
        copyButton.setOnClickListener { copyPinyin() }

        dialog = BottomSheetDialog(this).apply {
            setContentView(view)
            setOnDismissListener { finish() }
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            show()
        }

        bind(intent)
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

    override fun onDestroy() {
        // Clear the listener first: dismissing here would otherwise re-enter
        // finish() while the activity is already going away.
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

    private fun rawSelection(intent: Intent): String? =
        (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY))?.toString()

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
                PinyinEngine.annotate(this@ProcessTextActivity, text)
            }
            tokens = result
            progress.visibility = View.GONE

            if (result.none { it.annotation != null }) {
                empty.visibility = View.VISIBLE
            } else {
                rubyView.tokens = result
                rubyView.visibility = View.VISIBLE
                copyButton.visibility = View.VISIBLE
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

    private companion object {
        const val MAX_CHARS = 5_000
    }
}
