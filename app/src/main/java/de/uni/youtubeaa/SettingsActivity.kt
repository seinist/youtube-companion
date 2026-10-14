package de.uni.youtubeaa

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import de.uni.youtubeaa.car.HeadUnitDiagnostics
import de.uni.youtubeaa.car.SettingsRepository
import de.uni.youtubeaa.car.hybrid.YtSessionStore
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets()
        findViewById<MaterialToolbar>(R.id.settings_toolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var accountPreference: Preference
        private lateinit var ytSession: YtSessionStore

        private val loginLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            updateAccountPreference()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            SettingsRepository(requireContext()).getVideoBackend() // migrate legacy `auto`
            setPreferencesFromResource(R.xml.preferences, rootKey)
            ytSession = YtSessionStore(requireContext().applicationContext)
            accountPreference = requireNotNull(findPreference(KEY_ACCOUNT))
            findPreference<Preference>(KEY_SHARE_HU_LOGS)?.setOnPreferenceClickListener {
                shareHeadUnitLogs()
                true
            }
            findPreference<Preference>(KEY_CLEAR_HU_LOGS)?.setOnPreferenceClickListener {
                HeadUnitDiagnostics.clear(requireContext())
                AlertDialog.Builder(requireContext())
                    .setMessage("Head-Unit-Logs wurden gelöscht.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            accountPreference.setOnPreferenceClickListener {
                if (ytSession.isSignedIn()) {
                    showDisconnectDialog()
                } else {
                    showLoginChooser()
                }
                true
            }
            updateAccountPreference()
        }


        override fun onResume() {
            super.onResume()
            if (::accountPreference.isInitialized) updateAccountPreference()
        }


        private fun shareHeadUnitLogs() {
            val context = requireContext()
            runCatching {
                val zip = HeadUnitDiagnostics.createExportZip(context)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    zip
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "YouTube AA Head-Unit Diagnose")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Head-Unit-Logs teilen"))
            }.onFailure { error ->
                HeadUnitDiagnostics.error("SettingsActivity", "diagnostic export failed", error)
                AlertDialog.Builder(context)
                    .setTitle("Export fehlgeschlagen")
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }

        private fun showLoginChooser() {
            loginLauncher.launch(Intent(requireContext(), TvLoginActivity::class.java))
        }

        private fun showDisconnectDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_account_disconnect_title)
                .setMessage(R.string.settings_account_disconnect_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_account_disconnect) { _, _ ->
                    lifecycleScope.launch {
                        ytSession.clear()
                        updateAccountPreference()
                    }
                }
                .show()
        }

        private fun updateAccountPreference() {
            when {
                ytSession.isSignedIn() -> {
                    val label = ytSession.getTokens()?.accountLabel
                        ?: getString(R.string.status_hybrid_fallback_label)
                    accountPreference.summary = getString(R.string.settings_account_connected, label)
                }
                else -> accountPreference.summary = getString(R.string.settings_account_disconnected)
            }
        }

        companion object {
            private const val KEY_ACCOUNT = "youtube_account"
            private const val KEY_SHARE_HU_LOGS = "share_head_unit_logs"
            private const val KEY_CLEAR_HU_LOGS = "clear_head_unit_logs"
        }
    }
}
