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
import de.uni.youtubeaa.car.DataUsageStats
import de.uni.youtubeaa.car.HeadUnitDiagnostics
import de.uni.youtubeaa.car.SettingsRepository
import de.uni.youtubeaa.car.hybrid.YtSessionStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
                    .setMessage(R.string.hu_logs_cleared)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            val resetDataUsage = Preference.OnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.data_usage_reset_title)
                    .setMessage(R.string.data_usage_reset_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.data_usage_reset) { _, _ ->
                        DataUsageStats.reset(requireContext())
                        updateDataUsagePreference()
                    }
                    .show()
                true
            }
            findPreference<Preference>(KEY_DATA_USAGE)?.onPreferenceClickListener = resetDataUsage
            findPreference<Preference>(KEY_NETWORK_USAGE)?.onPreferenceClickListener = resetDataUsage
            updateDataUsagePreference()
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
            updateDataUsagePreference()
        }

        /** Bytes the players actually downloaded, to compare with Android's per-app data usage. */
        private fun updateDataUsagePreference() {
            val preference = findPreference<Preference>(KEY_DATA_USAGE) ?: return
            val snapshot = DataUsageStats.snapshot(requireContext())
            fun total(kind: DataUsageStats.Kind) =
                DataUsageStats.formatBytes(snapshot.bytes[kind]?.let { it.first + it.second } ?: 0L)
            preference.summary = getString(
                R.string.pref_data_usage_summary,
                DataUsageStats.formatBytes(snapshot.meteredTotal),
                DataUsageStats.formatBytes(snapshot.unmeteredTotal),
                total(DataUsageStats.Kind.VIDEO),
                total(DataUsageStats.Kind.AUDIO),
                total(DataUsageStats.Kind.MUXED),
                total(DataUsageStats.Kind.OTHER),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(snapshot.sinceMs))
            )
            findPreference<Preference>(KEY_NETWORK_USAGE)?.let { network ->
                fun net(sub: DataUsageStats.Subsystem) =
                    DataUsageStats.formatBytes(snapshot.network[sub]?.let { it.first + it.second } ?: 0L)
                val unsupported = getString(R.string.pref_network_usage_unsupported)
                network.summary = getString(
                    R.string.pref_network_usage_summary,
                    snapshot.uidTotal?.let { DataUsageStats.formatBytes(it.first) } ?: unsupported,
                    snapshot.uidTotal?.let { DataUsageStats.formatBytes(it.second) } ?: unsupported,
                    net(DataUsageStats.Subsystem.SEARCH),
                    net(DataUsageStats.Subsystem.FEED),
                    net(DataUsageStats.Subsystem.IMAGES),
                    net(DataUsageStats.Subsystem.EXTRACTOR),
                    net(DataUsageStats.Subsystem.OTHER)
                )
            }
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
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.hu_logs_share_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.pref_share_hu_logs)))
            }.onFailure { error ->
                HeadUnitDiagnostics.error("SettingsActivity", "diagnostic export failed", error)
                AlertDialog.Builder(context)
                    .setTitle(R.string.hu_logs_export_failed)
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
            private const val KEY_DATA_USAGE = "playback_data_usage"
            private const val KEY_NETWORK_USAGE = "network_data_usage"
        }
    }
}
