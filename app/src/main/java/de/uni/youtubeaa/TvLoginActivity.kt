package de.uni.youtubeaa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import de.uni.youtubeaa.car.hybrid.YtSessionStore
import de.uni.youtubeaa.car.hybrid.YtTvAuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YouTube TV device-code login for personal subscriptions (no Cloud Console client).
 */
class TvLoginActivity : AppCompatActivity() {

    private val auth = YtTvAuthClient()
    private lateinit var sessionStore: YtSessionStore
    private var pollJob: Job? = null
    private var copiedDeviceCode: String? = null

    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var urlView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_login)
        applySystemBarInsets()
        sessionStore = YtSessionStore(this)

        findViewById<MaterialToolbar>(R.id.tv_login_toolbar).setNavigationOnClickListener {
            finish()
        }
        status = findViewById(R.id.tv_login_status)
        codeView = findViewById(R.id.tv_login_code)
        urlView = findViewById(R.id.tv_login_url)

        findViewById<MaterialButton>(R.id.tv_login_start).setOnClickListener { startLogin() }
        findViewById<MaterialButton>(R.id.tv_login_open).setOnClickListener {
            val url = urlView.text?.toString().orEmpty()
            if (url.startsWith("http")) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        findViewById<MaterialButton>(R.id.tv_login_copy).setOnClickListener {
            copyToClipboard(codeView.text?.toString().orEmpty())
            Toast.makeText(this, R.string.tv_login_code_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.tv_login_skip).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        if (sessionStore.isSignedIn()) {
            status.setText(R.string.tv_login_already_linked)
        }
    }

    private fun startLogin() {
        pollJob?.cancel()
        status.setText(R.string.tv_login_preparing)
        codeView.setText(R.string.device_code_placeholder)
        pollJob = lifecycleScope.launch {
            try {
                val client = withContext(Dispatchers.IO) { auth.resolveClientIdentity() }
                val device = auth.requestDeviceCode(client)
                codeView.text = device.userCode
                urlView.text = device.verificationUrl
                status.text = getString(R.string.tv_login_instructions, device.userCode)

                copyToClipboard(device.userCode)

                // Open the verification page for convenience.
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUrl)))
                }

                val tokens = auth.pollForTokens(device, client) { msg ->
                    withContext(Dispatchers.Main) {
                        status.text = getString(
                            R.string.tv_login_poll_status,
                            device.userCode,
                            device.verificationUrl,
                            msg
                        )
                    }
                }
                // Persist login first; profile failures must never discard a valid session.
                sessionStore.save(tokens)
                de.uni.youtubeaa.car.BrowseListCache.invalidate()
                // MainActivity refreshes the profile on resume; do not hold successful login
                // open for a potentially slow profile request.
                if (!sessionStore.isSignedIn()) {
                    status.setText(R.string.tv_login_session_unreadable)
                    return@launch
                }
                status.setText(R.string.tv_login_success)
                clearDeviceCodeFromClipboard()
                Toast.makeText(this@TvLoginActivity, R.string.tv_login_account_linked, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.text = getString(R.string.tv_login_error, e.message.orEmpty())
            }
        }
    }

    private fun copyToClipboard(code: String) {
        if (code.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("youtube_device_code", code))
        copiedDeviceCode = code
    }

    private fun clearDeviceCodeFromClipboard() {
        val expected = copiedDeviceCode ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val current = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (current == expected) cm.clearPrimaryClip()
        copiedDeviceCode = null
    }

    override fun onDestroy() {
        pollJob?.cancel()
        clearDeviceCodeFromClipboard()
        super.onDestroy()
    }
}
