package fr.xan.souvenirnotifications

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.MediaController
import android.widget.VideoView
import android.widget.Toast
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileInputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.NumberFormat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val avatarBitmapCache = object : LruCache<String, android.graphics.Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val mediaPreviewCache = object : LruCache<String, android.graphics.Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private lateinit var statusLabel: TextView
    private lateinit var statusDot: View
    private lateinit var historyContainer: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var historyCountLabel: TextView
    private lateinit var appTabsContainer: LinearLayout
    private lateinit var appSettingsContainer: LinearLayout
    private lateinit var clearHistoryButton: Button
    private lateinit var historyScopeLabel: TextView
    private lateinit var historyModeContainer: LinearLayout
    private lateinit var galleryTypeContainer: LinearLayout
    private lateinit var captureDetailsLabel: TextView
    private lateinit var storageSummaryLabel: TextView
    private lateinit var backupSummaryLabel: TextView
    private lateinit var whatsAppMediaLabel: TextView
    private lateinit var appLockButton: Button
    private var appUnlockedThisLaunch = false
    private var lockPromptShown = false
    private var unlockRecoveryVisible = false
    private var diagnosticsExpanded = false
    private var selectedApp: Pair<String, String>? = null
    private var inTrash = false
    private var inGallery = false
    private var inConversations = false
    private var conversationFilter: Pair<String, String>? = null
    private var conversationFilterIsGroup = false
    private var historyVisibleCount = 60
    private var showAllTrashMessages = false
    private var conversationVisibleCount = 60
    private var storageRefreshGeneration = 0
    private val archiveRefreshHandler = Handler(Looper.getMainLooper())
    private val historyQueryExecutor = Executors.newSingleThreadExecutor()
    private val avatarDecodeExecutor = Executors.newFixedThreadPool(2)
    private val mediaPreviewExecutor = Executors.newFixedThreadPool(2)
    private var currentAudioPlayer: MediaPlayer? = null
    private var currentAudioPath: String? = null
    private var historyRefreshGeneration = 0
    private val historySearchAction = Runnable {
        historyVisibleCount = 60
        conversationVisibleCount = 60
        refreshHistory()
    }
    private val archiveRefreshAction = Runnable {
        if (::captureDetailsLabel.isInitialized) {
            refreshAccessStatus()
            refreshCaptureDetails()
            renderSelectedAppPanel()
            refreshHistory()
        }
    }
    private var galleryMediaType: String? = null

    private val isDarkMode: Boolean
        get() = displayPreferences().getBoolean("dark_mode", false)
    private val backgroundColor: Int get() = if (isDarkMode) Color.rgb(12, 27, 23) else Color.rgb(236, 253, 245)
    private val surfaceColor: Int get() = if (isDarkMode) Color.rgb(24, 43, 36) else Color.rgb(248, 255, 251)
    private val inkColor: Int get() = if (isDarkMode) Color.rgb(225, 245, 235) else Color.rgb(16, 59, 46)
    private val subtleColor: Int get() = if (isDarkMode) Color.rgb(164, 195, 180) else Color.rgb(76, 120, 102)
    private val mutedColor: Int get() = if (isDarkMode) Color.rgb(93, 125, 109) else Color.rgb(167, 198, 182)
    private val accentColor: Int get() = if (isDarkMode) Color.rgb(6, 95, 70) else Color.rgb(5, 150, 105)
    private val accentDarkColor: Int get() = if (isDarkMode) Color.rgb(110, 231, 183) else Color.rgb(4, 120, 87)
    private val softSurfaceColor: Int get() = if (isDarkMode) Color.rgb(31, 65, 52) else Color.rgb(209, 250, 229)
    private val borderColor: Int get() = if (isDarkMode) Color.rgb(61, 94, 77) else Color.rgb(183, 228, 209)
    private val dangerColor: Int get() = if (isDarkMode) Color.rgb(252, 165, 165) else Color.rgb(145, 45, 45)

    private val archiveUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            archiveRefreshHandler.removeCallbacks(archiveRefreshAction)
            archiveRefreshHandler.postDelayed(archiveRefreshAction, 350L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            if (getSharedPreferences("display_preferences", MODE_PRIVATE).getBoolean("dark_mode", false)) {
                R.style.SouvenirTheme_Dark
            } else {
                R.style.SouvenirTheme_Light
            }
        )
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        content.addView(buildHeader(), matchWidth())
        content.addView(buildStatusCard(), matchWidth().apply { topMargin = dp(22) })
        content.addView(sectionTitle(),
            matchWidth().apply { topMargin = dp(28) })
        appTabsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(appTabsContainer)
        }, matchWidth().apply { topMargin = dp(12) })
        appSettingsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appSettingsContainer, matchWidth().apply { topMargin = dp(12) })
        content.addView(buildStorageCard(), matchWidth().apply { topMargin = dp(12) })
        content.addView(buildHistoryHeader(), matchWidth().apply { topMargin = dp(28) })
        historyModeContainer = buildHistoryModeSelector()
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(historyModeContainer)
        }, matchWidth().apply { topMargin = dp(12) })
        galleryTypeContainer = buildGalleryTypeSelector()
        content.addView(galleryTypeContainer, matchWidth().apply { topMargin = dp(8) })
        searchField = EditText(this).apply {
            hint = "Rechercher un nom ou un message"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(surfaceColor, dp(14), borderColor)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    archiveRefreshHandler.removeCallbacks(historySearchAction)
                    archiveRefreshHandler.postDelayed(historySearchAction, 250L)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        content.addView(searchField, matchWidth().apply { topMargin = dp(14) })
        clearHistoryButton = Button(this).apply {
            textSize = 13f
            setTextColor(accentDarkColor)
            backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
            setOnClickListener { confirmClearHistory() }
        }
        content.addView(clearHistoryButton, matchWidth().apply { topMargin = dp(8) })
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyContainer, matchWidth().apply { topMargin = dp(10) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(
                dp(20) + systemBars.left,
                dp(22) + systemBars.top,
                dp(20) + systemBars.right,
                dp(28) + systemBars.bottom
            )
            windowInsets
        }
        setContentView(SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accentColor)
            setOnRefreshListener {
                try {
                    refreshScreen()
                } finally {
                    isRefreshing = false
                }
            }
            addView(scroll, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })
        content.requestFocus()
        refreshScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::statusLabel.isInitialized) refreshScreen()
        if (WhatsAppMediaImporter.savedTreeUri(this) != null) {
            WhatsAppMediaImporter.schedulePeriodicScan(this)
            WhatsAppMediaImporter.scanAsync(this, force = false) { result ->
                if (result.imported > 0) {
                    refreshStorageSummary()
                    refreshHistory()
                    refreshWhatsAppMediaStatus(result)
                }
            }
        }
        if (appLockPreferences().getBoolean("enabled", false) && !appUnlockedThisLaunch &&
            !lockPromptShown && !unlockRecoveryVisible
        ) {
            showAppUnlockPrompt()
        }
    }

    override fun onPause() {
        if (appLockPreferences().getBoolean("enabled", false) && !lockPromptShown) {
            appUnlockedThisLaunch = false
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ArchiveEvents.ACTION_UPDATED)
        ContextCompat.registerReceiver(
            this,
            archiveUpdateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        historyRefreshGeneration++
        archiveRefreshHandler.removeCallbacks(archiveRefreshAction)
        archiveRefreshHandler.removeCallbacks(historySearchAction)
        unregisterReceiver(archiveUpdateReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        historyRefreshGeneration++
        historyQueryExecutor.shutdownNow()
        avatarDecodeExecutor.shutdownNow()
        mediaPreviewExecutor.shutdownNow()
        currentAudioPlayer?.release()
        currentAudioPlayer = null
        super.onDestroy()
    }

    @Deprecated("Uses the document picker compatibility API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_APP_UNLOCK) {
            lockPromptShown = false
            if (resultCode == RESULT_OK) {
                appUnlockedThisLaunch = true
                return
            }
            showUnlockRecoveryDialog("Le code du téléphone n’a pas été validé.")
            return
        }
        if (requestCode == REQUEST_WHATSAPP_MEDIA_TREE) {
            if (resultCode != RESULT_OK) return
            val treeUri = data?.data ?: return
            runCatching {
                WhatsAppMediaImporter.saveTreePermission(this, treeUri, data.flags)
            }.onSuccess {
                Toast.makeText(this, "Dossier WhatsApp autorisé. Analyse des médias…", Toast.LENGTH_SHORT).show()
                WhatsAppMediaImporter.scanAsync(this, force = true) { result ->
                    refreshWhatsAppMediaStatus(result)
                    refreshStorageSummary()
                    refreshHistory()
                }
            }.onFailure { error ->
                Toast.makeText(this, error.localizedMessage ?: "Impossible d’autoriser ce dossier.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_CREATE_BACKUP -> runCatching {
                val output = contentResolver.openOutputStream(uri)
                    ?: error("Impossible de créer le fichier de sauvegarde")
                ArchiveBackup.export(this, output)
                saveBackupDetails(uri)
            }.onSuccess {
                AlertDialog.Builder(this)
                    .setTitle("Sauvegarde terminée")
                    .setMessage("Ton historique et tes médias ont été enregistrés dans le fichier choisi.")
                    .setPositiveButton("OK", null)
                    .show()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "La sauvegarde a échoué.", Toast.LENGTH_LONG).show()
            }
            REQUEST_RESTORE_BACKUP -> confirmRestore(uri)
        }
    }

    private fun saveBackupDetails(uri: android.net.Uri) {
        var size = 0L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) size = cursor.getLong(index)
                }
            }
        }
        getSharedPreferences("app_state", MODE_PRIVATE).edit {
            putLong("last_backup_at", System.currentTimeMillis())
            putLong("last_backup_size", size)
        }
        refreshBackupSummary()
    }

    private fun showAppUnlockPrompt() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            appLockPreferences().edit { putBoolean("enabled", false) }
            updateAppLockButton()
            return
        }
        lockPromptShown = true
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle("Déverrouiller Souvenir")
                    .setSubtitle("Utilise ton empreinte ou le code de ton téléphone")
                    .setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                prompt.authenticate(android.os.CancellationSignal(), mainExecutor,
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                            appUnlockedThisLaunch = true
                            lockPromptShown = false
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            lockPromptShown = false
                            if (!appUnlockedThisLaunch && !isFinishing) {
                                showUnlockRecoveryDialog(errString?.toString().orEmpty())
                            }
                        }
                    })
            } else {
                launchDeviceCredentialPrompt(keyguard)
            }
        } catch (error: Exception) {
            lockPromptShown = false
            showUnlockRecoveryDialog(error.localizedMessage.orEmpty())
        }
    }

    private fun showUnlockRecoveryDialog(reason: String) {
        if (isFinishing || isDestroyed || appUnlockedThisLaunch || unlockRecoveryVisible) return
        unlockRecoveryVisible = true
        AlertDialog.Builder(this)
            .setTitle("Souvenir est toujours verrouillée")
            .setMessage(
                (reason.takeIf(String::isNotBlank)?.let { "$it\n\n" } ?: "") +
                    "Réessaie avec ton empreinte ou déverrouille avec le code de ton téléphone. " +
                    "L’application reste ouverte pendant que tu choisis."
            )
            .setNegativeButton("Réessayer") { _, _ ->
                unlockRecoveryVisible = false
                showAppUnlockPrompt()
            }
            .setPositiveButton("Utiliser le code") { _, _ ->
                unlockRecoveryVisible = false
                launchDeviceCredentialPrompt(getSystemService(KEYGUARD_SERVICE) as KeyguardManager)
            }
            .setCancelable(false)
            .show()
    }

    @Suppress("DEPRECATION") // Compatibility path for Android 8–10, before BiometricPrompt device credentials.
    private fun launchDeviceCredentialPrompt(keyguard: KeyguardManager) {
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Déverrouiller Souvenir", "Utilise le code de verrouillage du téléphone"
        )
        if (intent == null) {
            lockPromptShown = false
            showUnlockRecoveryDialog("Android ne peut pas ouvrir la vérification du code.")
        } else {
            lockPromptShown = true
            startActivityForResult(intent, REQUEST_APP_UNLOCK)
        }
    }

    private fun updateAppLockButton() {
        if (!::appLockButton.isInitialized) return
        val enabled = appLockPreferences().getBoolean("enabled", false)
        appLockButton.text = if (enabled) "Désactiver le verrouillage de l’application" else
            "Protéger avec l’empreinte ou le code du téléphone"
    }

    private fun toggleAppLock() {
        val enabled = appLockPreferences().getBoolean("enabled", false)
        if (enabled) {
            appLockPreferences().edit { putBoolean("enabled", false) }
            updateAppLockButton()
            return
        }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this)
                .setTitle("Verrouillage du téléphone requis")
                .setMessage("Configure d’abord un code, un schéma ou une empreinte dans les réglages du téléphone.")
                .setNegativeButton("Plus tard", null)
                .setPositiveButton("Ouvrir les réglages") { _, _ ->
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
                .show()
            return
        }
        appLockPreferences().edit { putBoolean("enabled", true) }
        appUnlockedThisLaunch = false
        updateAppLockButton()
        showAppUnlockPrompt()
    }

    private fun requestBackupExport() {
        AlertDialog.Builder(this)
            .setTitle("Sauvegarde non chiffrée")
            .setMessage("Le fichier ZIP contient ton historique et les médias archivés. Il n’est pas protégé par un mot de passe. Choisis un emplacement sûr et ne le partage qu’avec des personnes de confiance.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Choisir l’emplacement") { _, _ -> openBackupSavePicker() }
            .show()
    }

    private fun openBackupSavePicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            putExtra(Intent.EXTRA_TITLE, "Souvenir_$date.zip")
        }
        startActivityForResult(intent, REQUEST_CREATE_BACKUP)
    }

    private fun requestBackupImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        startActivityForResult(intent, REQUEST_RESTORE_BACKUP)
    }

    private fun confirmRestore(uri: android.net.Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restaurer cette sauvegarde ?")
            .setMessage("L’historique actuel sera remplacé par celui de la sauvegarde. Les réglages d’archivage des applis seront également restaurés.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Restaurer") { _, _ ->
                runCatching {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("Impossible d’ouvrir la sauvegarde")
                    ArchiveBackup.restore(this, input)
                }.onSuccess {
                    AlertDialog.Builder(this)
                        .setTitle("Sauvegarde restaurée")
                        .setMessage("Redémarre l’application pour afficher l’historique restauré.")
                        .setPositiveButton("Redémarrer") { _, _ -> restartApplication() }
                        .setNegativeButton("Plus tard", null)
                        .show()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "La restauration a échoué.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun restartApplication() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (launchIntent != null) startActivity(launchIntent)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val badge = TextView(this@MainActivity).apply {
            text = getString(R.string.app_initials)
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(accentColor, dp(18))
        }
        addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_title)
                textSize = 25f
                setTextColor(inkColor)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(smallLabel("Notifications archivées sur ce téléphone"), matchWidth())
        }
        addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
    }

    private fun buildStatusCard(): View = verticalCard().apply {
        val statusRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this@MainActivity).apply { background = roundedBackground(mutedColor, dp(8)) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusLabel = TextView(this@MainActivity).apply {
            textSize = 14f
            setTextColor(inkColor)
        }
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        addView(statusRow, matchWidth())
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_disclaimer)
            textSize = 13f
            setTextColor(subtleColor)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWidth().apply { topMargin = dp(8) })
        captureDetailsLabel = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(subtleColor)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        addView(Button(this@MainActivity).apply {
            text = getString(R.string.manage_notification_access)
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(accentColor)
            setOnClickListener {
                if (NotificationAccess.isEnabled(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } else {
                    showNotificationAccessDisclosure()
                }
            }
        }, matchWidth().apply { topMargin = dp(14) })
        appLockButton = Button(this@MainActivity).apply {
            textSize = 13f
            setTextColor(accentDarkColor)
            backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
            setOnClickListener { toggleAppLock() }
        }
        updateAppLockButton()
        val advancedOptions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(captureDetailsLabel, matchWidth())
            addView(appLockButton, matchWidth().apply { topMargin = dp(6) })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.privacy_and_data)
                textSize = 13f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener { showPrivacyPolicy() }
            }, matchWidth().apply { topMargin = dp(6) })
            addView(Button(this@MainActivity).apply {
                text = getString(if (isDarkMode) R.string.disable_dark_mode else R.string.enable_dark_mode)
                textSize = 13f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener {
                    displayPreferences().edit { putBoolean("dark_mode", !isDarkMode) }
                    recreate()
                }
            }, matchWidth().apply { topMargin = dp(6) })
        }
        addView(buildExpandableSection(
            title = getString(R.string.advanced_settings),
            preferenceKey = "advanced_settings",
            details = advancedOptions
        ), matchWidth().apply { topMargin = dp(8) })
    }

    private fun buildExpandableSection(
        title: String,
        preferenceKey: String,
        details: View,
        summaryLabel: TextView? = null
    ): View = verticalCard().apply {
        val expanded = displayPreferences().getBoolean("expanded_$preferenceKey", false)
        val arrow = TextView(this@MainActivity).apply {
            text = if (expanded) "▾" else "▸"
            textSize = 22f
            setTextColor(accentDarkColor)
            gravity = Gravity.CENTER
            contentDescription = if (expanded) getString(R.string.collapse_section) else getString(R.string.expand_section)
        }
        val heading = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(inkColor)
            }, matchWidth())
            summaryLabel?.let { label ->
                addView(label, matchWidth().apply { topMargin = dp(3) })
            }
        }
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        addView(header, matchWidth())
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        addView(details, matchWidth().apply { topMargin = dp(8) })
        header.setOnClickListener {
            val shouldExpand = details.visibility != View.VISIBLE
            details.visibility = if (shouldExpand) View.VISIBLE else View.GONE
            arrow.text = if (shouldExpand) "▾" else "▸"
            arrow.contentDescription = getString(
                if (shouldExpand) R.string.collapse_section else R.string.expand_section
            )
            displayPreferences().edit { putBoolean("expanded_$preferenceKey", shouldExpand) }
        }
    }

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this)
            .setTitle("Politique de confidentialité")
            .setMessage(getString(R.string.privacy_policy_text))
            .setPositiveButton("Fermer", null)
            .setNegativeButton(R.string.view_privacy_policy_online) { _, _ ->
                val policyIntent = Intent(
                    Intent.ACTION_VIEW,
                    "https://jabinxan-ux.github.io/souvenir-confidentialite/".toUri()
                )
                try {
                    startActivity(policyIntent)
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, R.string.browser_missing, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.contact_privacy) { _, _ ->
                val contactIntent = Intent(
                    Intent.ACTION_SENDTO,
                    "mailto:jabinxan@gmail.com".toUri()
                )
                try {
                    startActivity(contactIntent)
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, R.string.email_app_missing, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun buildStorageCard(): View {
        storageSummaryLabel = smallLabel("Calcul de l’espace utilisé…")
        val storageTools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@MainActivity).apply {
            text = getString(R.string.manage_large_media)
            textSize = 13f
            setTextColor(accentDarkColor)
            backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
            setOnClickListener { showStorageManager() }
            }, matchWidth())
            addView(View(this@MainActivity).apply {
            setBackgroundColor(if (isDarkMode) Color.rgb(49, 72, 61) else Color.rgb(220, 238, 231))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16)
            bottomMargin = dp(14)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Archiver aussi les médias WhatsApp"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(inkColor)
            }, matchWidth())
            addView(TextView(this@MainActivity).apply {
                text = "Si tu choisis ce dossier, Souvenir copie les photos, vidéos et fichiers audio déjà téléchargés. Il le réanalyse à l’ouverture, après certaines notifications WhatsApp et périodiquement en arrière-plan, tant que l’accès reste accordé."
                textSize = 13f
                setTextColor(subtleColor)
            }, matchWidth().apply { topMargin = dp(4) })
            whatsAppMediaLabel = smallLabel("Aucun dossier WhatsApp sélectionné.")
            addView(whatsAppMediaLabel, matchWidth().apply { topMargin = dp(6) })
            addView(Button(this@MainActivity).apply {
                text = "Choisir le dossier WhatsApp/Media"
                textSize = 13f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                            REQUEST_WHATSAPP_MEDIA_TREE
                        )
                    } catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Sélecteur de dossier indisponible.", Toast.LENGTH_SHORT).show()
                    }
                }
            }, matchWidth().apply { topMargin = dp(6) })
            addView(Button(this@MainActivity).apply {
                text = "Scanner le dossier maintenant"
                textSize = 13f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener {
                    if (WhatsAppMediaImporter.savedTreeUri(this@MainActivity) == null) {
                        Toast.makeText(this@MainActivity, "Choisis d’abord le dossier WhatsApp/Media.", Toast.LENGTH_SHORT).show()
                    } else {
                        whatsAppMediaLabel.text = "Analyse des médias WhatsApp…"
                        WhatsAppMediaImporter.scanAsync(this@MainActivity, force = true) { result ->
                            refreshWhatsAppMediaStatus(result)
                            refreshStorageSummary()
                            refreshHistory()
                        }
                    }
                }
            }, matchWidth().apply { topMargin = dp(4) })
            addView(Button(this@MainActivity).apply {
                text = "Retirer l’accès à ce dossier"
                textSize = 12f
                setTextColor(subtleColor)
                backgroundTintList = ColorStateList.valueOf(surfaceColor)
                setOnClickListener {
                    val prefs = getSharedPreferences("whatsapp_media_import", MODE_PRIVATE)
                    val selectedUri = prefs.getString("tree_uri", null)?.let(android.net.Uri::parse)
                    if (selectedUri != null) {
                        contentResolver.persistedUriPermissions
                            .firstOrNull { it.uri == selectedUri }
                            ?.let { permission -> runCatching {
                                val permissionFlags =
                                    (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                                        (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                                contentResolver.releasePersistableUriPermission(selectedUri, permissionFlags)
                            } }
                    }
                    prefs.edit { remove("tree_uri"); remove("last_scan_at") }
                    WhatsAppMediaImporter.cancelPeriodicScan(this@MainActivity)
                    refreshWhatsAppMediaStatus()
                }
            }, matchWidth().apply { topMargin = dp(2) })
            addView(View(this@MainActivity).apply {
                setBackgroundColor(if (isDarkMode) Color.rgb(49, 72, 61) else Color.rgb(220, 238, 231))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(16)
                bottomMargin = dp(14)
            })
            addView(TextView(this@MainActivity).apply {
            text = getString(R.string.backup_and_restore)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(inkColor)
            }, matchWidth())
            addView(TextView(this@MainActivity).apply {
            text = getString(R.string.backup_instructions)
            textSize = 13f
            setTextColor(subtleColor)
            }, matchWidth().apply { topMargin = dp(4) })
            addView(Button(this@MainActivity).apply {
            text = getString(R.string.create_backup)
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(accentColor)
            setOnClickListener { requestBackupExport() }
            }, matchWidth().apply { topMargin = dp(10) })
            addView(Button(this@MainActivity).apply {
            text = getString(R.string.restore_from_file)
            textSize = 14f
            setTextColor(accentDarkColor)
            backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
            setOnClickListener { requestBackupImport() }
            }, matchWidth().apply { topMargin = dp(4) })
            addView(TextView(this@MainActivity).apply {
            text = getString(R.string.restore_replaces_history)
            textSize = 12f
            setTextColor(subtleColor)
            }, matchWidth().apply { topMargin = dp(2) })
        backupSummaryLabel = smallLabel("Pense à créer une sauvegarde de temps en temps.")
            addView(backupSummaryLabel, matchWidth().apply { topMargin = dp(8) })
        }
        return buildExpandableSection(
            title = getString(R.string.storage_and_backup),
            preferenceKey = "storage_and_backup",
            details = storageTools,
            summaryLabel = storageSummaryLabel
        )
    }

    private fun buildHistoryHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val heading = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.tab_history)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(inkColor)
            })
            historyScopeLabel = smallLabel("Toutes les applis · messages et aperçus")
            addView(historyScopeLabel, matchWidth().apply { topMargin = dp(3) })
        }
        addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        historyCountLabel = smallLabel("")
        addView(historyCountLabel)
    }

    private fun buildHistoryModeSelector() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val historyButton = Button(this@MainActivity).apply {
            text = getString(R.string.tab_history)
            textSize = 13f
            setOnClickListener {
                inTrash = false
                inGallery = false
                inConversations = false
                conversationFilter = null
                refreshModeSelector()
                refreshHistory()
            }
        }
        val galleryButton = Button(this@MainActivity).apply {
            text = getString(R.string.tab_media)
            textSize = 13f
            setOnClickListener {
                inTrash = false
                inGallery = true
                inConversations = false
                conversationFilter = null
                refreshModeSelector()
                refreshGalleryTypeSelector()
                refreshHistory()
            }
        }
        val conversationsButton = Button(this@MainActivity).apply {
            text = getString(R.string.tab_conversations)
            textSize = 11f
            setOnClickListener {
                inTrash = false
                inGallery = false
                inConversations = true
                conversationFilter = null
                refreshModeSelector()
                refreshHistory()
            }
        }
        val trashButton = Button(this@MainActivity).apply {
            text = getString(R.string.tab_trash)
            textSize = 13f
            setOnClickListener {
                inTrash = true
                inGallery = false
                inConversations = false
                showAllTrashMessages = false
                conversationFilter = null
                refreshModeSelector()
                refreshHistory()
            }
        }
        listOf(historyButton, galleryButton, conversationsButton, trashButton).forEachIndexed { index, button ->
            button.maxLines = 1
            addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        tag = listOf(historyButton, galleryButton, conversationsButton, trashButton)
    }

    private fun refreshModeSelector() {
        @Suppress("UNCHECKED_CAST")
        val buttons = historyModeContainer.tag as? List<Button> ?: return
        buttons.forEachIndexed { index, button ->
            val selected = when (index) {
                0 -> !inTrash && !inGallery && !inConversations && conversationFilter == null
                1 -> inGallery
                2 -> inConversations || conversationFilter != null
                else -> inTrash
            }
            button.setTextColor(if (selected) Color.WHITE else accentDarkColor)
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) accentColor else softSurfaceColor
            )
        }
    }

    private fun buildGalleryTypeSelector() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val buttons = listOf(
            "Tout" to null,
            "Photos" to "image",
            "Vidéos" to "video",
            "Vocaux" to "audio"
        ).map { (label, type) ->
            Button(this@MainActivity).apply {
                text = label
                textSize = 12f
                setOnClickListener {
                    galleryMediaType = type
                    refreshGalleryTypeSelector()
                    refreshHistory()
                }
            }
        }
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        tag = buttons
    }

    private fun refreshGalleryTypeSelector() {
        @Suppress("UNCHECKED_CAST")
        val buttons = galleryTypeContainer.tag as? List<Button> ?: return
        val selectedIndex = when (galleryMediaType) {
            "image" -> 1
            "video" -> 2
            "audio" -> 3
            else -> 0
        }
        buttons.forEachIndexed { index, button ->
            val selected = index == selectedIndex
            button.setTextColor(if (selected) Color.WHITE else accentDarkColor)
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) accentColor else softSurfaceColor
            )
        }
    }

    private fun sectionTitle(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.applications)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(inkColor)
        })
        addView(smallLabel(getString(R.string.applications_hint)), matchWidth().apply { topMargin = dp(3) })
    }

    private fun refreshScreen() {
        refreshAccessStatus()
        refreshCaptureDetails()
        refreshBackupSummary()
        refreshStorageSummary()
        refreshWhatsAppMediaStatus()
        renderApplicationTabs()
        renderSelectedAppPanel()
        refreshModeSelector()
        refreshGalleryTypeSelector()
        refreshHistory()
    }

    private fun refreshAccessStatus() {
        val enabled = NotificationAccess.isEnabled(this)
        val listenerState = getSharedPreferences("listener_state", MODE_PRIVATE)
        val lastConnected = listenerState.getLong("last_connected_at", 0L)
        val lastDisconnected = listenerState.getLong("last_disconnected_at", 0L)
        statusLabel.text = when {
            !enabled -> "Accès aux notifications à activer"
            lastDisconnected > lastConnected -> "Écoute interrompue · vérifie l’accès aux notifications"
            lastConnected == 0L -> "Accès autorisé · attente du service"
            else -> "Accès autorisé · connexion ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastConnected))}"
        }
        statusDot.background = roundedBackground(
            if (!enabled) mutedColor else if (lastDisconnected > lastConnected) Color.rgb(245, 158, 11) else accentColor,
            dp(8)
        )
    }

    private fun refreshCaptureDetails() {
        if (!::captureDetailsLabel.isInitialized) return
        val state = getSharedPreferences("listener_state", MODE_PRIVATE)
        fun timeLabel(key: String): String {
            val timestamp = state.getLong(key, 0L)
            return if (timestamp == 0L) "aucun" else
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
        }
        captureDetailsLabel.text = getString(
            R.string.capture_diagnostics,
            timeLabel("last_notification_received_at"),
            timeLabel("last_notification_archived_at"),
            timeLabel("last_media_archived_at")
        )
    }

    private fun refreshStorageSummary() {
        if (!::storageSummaryLabel.isInitialized) return
        val generation = ++storageRefreshGeneration
        historyQueryExecutor.execute {
            val summary = runCatching {
                synchronized(ArchiveEvents.databaseLock) {
                    NotificationStore(this).mediaStorageSummary()
                }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != storageRefreshGeneration ||
                    !::storageSummaryLabel.isInitialized
                ) return@runOnUiThread
                val usage = getString(
                    R.string.storage_usage,
                    resources.getQuantityString(R.plurals.archived_media_count, summary.fileCount, summary.fileCount),
                    formatBytes(summary.totalBytes)
                )
                storageSummaryLabel.text = if (summary.totalBytes >= 1024L * 1024L * 1024L) {
                    getString(R.string.storage_usage_with_warning, usage, getString(R.string.storage_exceeds_1gb))
                } else usage
                storageSummaryLabel.setTextColor(
                    if (summary.totalBytes >= 1024L * 1024L * 1024L) Color.rgb(180, 83, 9) else subtleColor
                )
            }
        }
    }

    private fun refreshWhatsAppMediaStatus(result: WhatsAppMediaImporter.Result? = null) {
        if (!::whatsAppMediaLabel.isInitialized) return
        whatsAppMediaLabel.text = when {
            result != null && result.imported > 0 -> "${result.imported} nouveau(x) média(s) archivé(s)" +
                (if (result.skipped > 0) " · ${result.skipped} ignoré(s) (taille ou accès)" else "") +
                (result.error?.let { " · $it" } ?: "")
            result?.error != null -> result.error
            result != null && result.skipped > 0 -> "Aucun nouveau média · ${result.skipped} fichier(s) ignoré(s)"
            WhatsAppMediaImporter.savedTreeUri(this) != null -> "Dossier autorisé · analyse en arrière-plan environ toutes les 15 min, à l’ouverture et après une notification WhatsApp. Android peut décaler les analyses pour économiser la batterie."
            else -> "Aucun dossier WhatsApp sélectionné."
        }
    }

    private fun refreshBackupSummary() {
        if (!::backupSummaryLabel.isInitialized) return
        val state = getSharedPreferences("app_state", MODE_PRIVATE)
        val timestamp = state.getLong("last_backup_at", 0L)
        backupSummaryLabel.text = when {
            timestamp == 0L -> "Pense à créer une sauvegarde de temps en temps."
            System.currentTimeMillis() - timestamp > 30L * 24L * 60L * 60L * 1000L ->
                "Dernière sauvegarde : ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))} · pense à en refaire une."
            else -> "Dernière sauvegarde : ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))} · " +
                state.getLong("last_backup_size", 0L).takeIf { it > 0L }?.let(::formatBytes).orEmpty().ifBlank { "taille inconnue" }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f Go", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f Mo", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f Ko", bytes / 1024.0)
        else -> "$bytes octets"
    }

    private fun renderApplicationTabs() {
        appTabsContainer.removeAllViews()
        val tabs = listOf<Pair<String, String>?>(null) + supportedApps
        tabs.forEachIndexed { index, app ->
            val label = app?.first ?: "Toutes"
            val selected = app == selectedApp
            val captureActive = app?.let {
                preferences().getBoolean(it.second, false) && consentPreferences().getBoolean(it.second, false)
            } == true
            val tab = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                contentDescription = if (app == null) "Afficher toutes les notifications" else
                    "Afficher les notifications $label${if (captureActive) ", archivage activé" else ""}"
                val pill = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(15), dp(10), dp(15), dp(10))
                    background = roundedBackground(
                        if (selected) accentColor else surfaceColor,
                        dp(18),
                        if (selected) null else borderColor
                    )
                    addView(TextView(this@MainActivity).apply {
                        text = label
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (selected) Color.WHITE else accentDarkColor)
                    })
                }
                addView(pill, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (captureActive) {
                        topMargin = dp(5)
                        marginEnd = dp(5)
                    }
                })
                if (captureActive) {
                    addView(TextView(this@MainActivity).apply {
                        text = "✓"
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextColor(accentDarkColor)
                        background = roundedBackground(softSurfaceColor, dp(10), borderColor)
                        elevation = dp(2).toFloat()
                    }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
                }
                setOnClickListener {
                    selectedApp = app
                    renderApplicationTabs()
                    renderSelectedAppPanel()
                    refreshHistory()
                }
            }
            appTabsContainer.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun renderSelectedAppPanel() {
        appSettingsContainer.removeAllViews()
        val app = selectedApp ?: run {
            appSettingsContainer.addView(verticalCard().apply {
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.all_apps)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(inkColor)
                })
                addView(smallLabel("Choisissez une appli ci-dessus pour afficher uniquement ses messages et activer ou couper son archivage."),
                    matchWidth().apply { topMargin = dp(5) })
                addView(buildNoisyFilterToggle(), matchWidth().apply { topMargin = dp(8) })
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.diagnostics_by_app)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(inkColor)
                }, matchWidth().apply { topMargin = dp(14) })
                addView(smallLabel("Reçue = transmise par Android · Archivée = ajoutée à l’historique. Si une appli ne publie pas de notification, Souvenir ne peut pas la voir."),
                    matchWidth().apply { topMargin = dp(3) })
                val diagnostics = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
                }
                supportedApps.forEach { supported ->
                    diagnostics.addView(buildAppDiagnostic(supported), matchWidth().apply { topMargin = dp(8) })
                }
                addView(Button(this@MainActivity).apply {
                    text = if (diagnosticsExpanded) "Masquer le diagnostic" else "Voir le diagnostic des applis"
                    textSize = 13f
                    setTextColor(accentDarkColor)
                    backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                    setOnClickListener {
                        diagnosticsExpanded = !diagnosticsExpanded
                        renderSelectedAppPanel()
                    }
                }, matchWidth().apply { topMargin = dp(6) })
                addView(diagnostics, matchWidth())
            }, matchWidth())
            return
        }
        appSettingsContainer.addView(verticalCard().apply {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val identity = TextView(this@MainActivity).apply {
                text = app.first.take(1).uppercase()
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(accentDarkColor)
                background = roundedBackground(softSurfaceColor, dp(14))
            }
            row.addView(identity, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(TextView(this@MainActivity).apply {
                text = app.first
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(inkColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
            val captureToggle = CheckBox(this@MainActivity).apply {
                text = getString(R.string.archive)
                textSize = 13f
                setTextColor(subtleColor)
                buttonTintList = ColorStateList.valueOf(accentColor)
                contentDescription = "Archiver les notifications de ${app.first}"
                isChecked = preferences().getBoolean(app.second, false) &&
                    consentPreferences().getBoolean(app.second, false)
                setOnCheckedChangeListener { button, checked ->
                    if (checked && !consentPreferences().getBoolean(app.second, false)) {
                        button.isChecked = false
                        showCaptureConsent(app) {
                            consentPreferences().edit { putBoolean(app.second, true) }
                            preferences().edit { putBoolean(app.second, true) }
                            renderApplicationTabs()
                            renderSelectedAppPanel()
                        }
                    } else {
                        preferences().edit { putBoolean(app.second, checked) }
                        renderApplicationTabs()
                        renderSelectedAppPanel()
                    }
                }
            }
            row.addView(captureToggle)
            addView(row, matchWidth())
            addView(smallLabel(if (captureToggle.isChecked) {
                "Archivage activé pour cette appli"
            } else {
                "Touchez la case pour activer l’archivage de cette appli"
            }), matchWidth().apply { topMargin = dp(6) })
            addView(buildAppDiagnostic(app), matchWidth().apply { topMargin = dp(12) })
            addView(buildNoisyFilterToggle(), matchWidth().apply { topMargin = dp(8) })
        }, matchWidth())
    }

    private fun buildNoisyFilterToggle(): CheckBox = CheckBox(this).apply {
        text = getString(R.string.hide_calls_and_reactions)
        textSize = 13f
        setTextColor(subtleColor)
        buttonTintList = ColorStateList.valueOf(accentColor)
        isChecked = displayPreferences().getBoolean("hide_calls_reactions", false)
        setOnCheckedChangeListener { _, checked ->
            displayPreferences().edit { putBoolean("hide_calls_reactions", checked) }
            refreshHistory()
        }
    }

    private fun buildAppDiagnostic(app: Pair<String, String>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(if (isDarkMode) Color.rgb(31, 52, 44) else Color.rgb(240, 253, 247), dp(12))
        val state = getSharedPreferences("listener_state", MODE_PRIVATE)
        val enabled = preferences().getBoolean(app.second, false) &&
            consentPreferences().getBoolean(app.second, false)
        addView(TextView(this@MainActivity).apply {
            text = getString(
                R.string.app_capture_status,
                app.first,
                getString(if (enabled) R.string.capture_active else R.string.capture_inactive)
            )
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (enabled) accentDarkColor else subtleColor)
        }, matchWidth())
        addView(smallLabel("Reçue : ${diagnosticTime(state.getLong("last_received_${app.second}", 0L))}"),
            matchWidth().apply { topMargin = dp(3) })
        addView(smallLabel("Archivée : ${diagnosticTime(state.getLong("last_archived_${app.second}", 0L))}"),
            matchWidth().apply { topMargin = dp(2) })
        state.getString("last_media_status_${app.second}", null)?.let { mediaStatus ->
            addView(smallLabel("Média : $mediaStatus"), matchWidth().apply { topMargin = dp(2) })
        }
    }

    private fun diagnosticTime(timestamp: Long): String = if (timestamp == 0L) "aucune observée" else
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

    private fun showCaptureConsent(app: Pair<String, String>, onAccept: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Autoriser l’archivage de ${app.first} ?")
            .setMessage(
                "Souvenir lira les notifications de cette appli et pourra enregistrer leur texte, " +
                    "le nom et la photo de l’expéditeur ainsi que les aperçus ou pièces jointes qu’Android fournit. " +
                    "Ces éléments restent dans le stockage privé de Souvenir et ne sont pas envoyés à l’éditeur. " +
                    "Si tu exportes une sauvegarde vers un emplacement cloud, son fournisseur peut recevoir le fichier. " +
                    "Tu peux désactiver l’archivage et supprimer l’historique à tout moment."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("J’accepte") { _, _ -> onAccept() }
            .show()
    }

    private fun showNotificationAccessDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_access_disclosure_title)
            .setMessage(R.string.notification_access_disclosure)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_to_android_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .show()
    }

    private fun isCallOrReaction(title: String, text: String): Boolean {
        val value = "$title $text".lowercase(Locale.ROOT)
        return listOf(
            "appel vocal", "appel vidéo", "appel video", "voice call", "video call",
            "a réagi par", "a réagi à", "reacted to", "reacted with"
        ).any(value::contains)
    }

    private fun refreshHistory() {
        if (!::historyContainer.isInitialized) return
        val refreshGeneration = ++historyRefreshGeneration
        historyContainer.removeAllViews()
        galleryTypeContainer.visibility = if (inGallery) View.VISIBLE else View.GONE
        clearHistoryButton.visibility = if (inGallery || inConversations || conversationFilter != null) View.GONE else View.VISIBLE
        val selectedAppName = selectedApp?.first
        historyScopeLabel.text = when {
            inGallery && selectedAppName != null -> "Galerie · $selectedAppName"
            inGallery -> "Galerie · toutes les applis"
            inConversations && selectedAppName != null -> "Conversations · $selectedAppName"
            inConversations -> "Conversations · toutes les applis"
            conversationFilter != null -> "${if (conversationFilterIsGroup) "Groupe" else "Privé"} · ${conversationFilter?.second.orEmpty()}"
            selectedAppName != null -> "$selectedAppName · ses messages et aperçus"
            else -> "Toutes les applis · messages et aperçus"
        }
        clearHistoryButton.text = when {
            inTrash && selectedAppName != null -> "Vider la corbeille $selectedAppName"
            inTrash -> "Vider toute la corbeille"
            selectedAppName != null -> "Déplacer $selectedAppName vers la corbeille"
            else -> "Tout déplacer vers la corbeille"
        }
        if (inTrash) {
            historyContainer.addView(Button(this).apply {
                text = getString(R.string.restore_all_trash)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(accentColor)
                minHeight = dp(52)
                setOnClickListener { confirmRestoreTrash() }
            }, matchWidth().apply { bottomMargin = dp(10) })
        }
        if (inConversations && conversationFilter == null) {
            historyContainer.addView(TextView(this).apply {
                text = "Chargement des conversations…"
                textSize = 14f
                setTextColor(subtleColor)
                gravity = Gravity.CENTER
            }, matchWidth().apply { setMargins(0, dp(16), 0, dp(16)) })
            loadConversationList(refreshGeneration)
            return
        }
        if (conversationFilter != null) {
            historyContainer.addView(Button(this).apply {
                text = getString(R.string.all_conversations)
                textSize = 13f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener {
                    conversationFilter = null
                    inConversations = true
                    refreshModeSelector()
                    refreshHistory()
                }
            }, matchWidth().apply { bottomMargin = dp(8) })
        }
        val query = searchField.text?.toString().orEmpty()
        val packageFilter = conversationFilter?.first ?: selectedApp?.second
        val trashFilter = inTrash
        val galleryFilter = inGallery
        val selectedMediaType = if (inGallery) galleryMediaType else null
        val senderFilter = conversationFilter?.second.takeUnless { conversationFilterIsGroup }
        val conversationNameFilter = conversationFilter?.second
        val groupFilter = conversationFilter?.let { conversationFilterIsGroup }
        val queryLimit = if (inTrash && showAllTrashMessages) Int.MAX_VALUE else 2_000
        historyQueryExecutor.execute {
            val archivedMessages = runCatching {
                synchronized(ArchiveEvents.databaseLock) {
                    NotificationStore(this).latest(
                        query = query,
                        packageNameFilter = packageFilter,
                        inTrash = trashFilter,
                        mediaOnly = galleryFilter,
                        mediaType = selectedMediaType,
                        senderFilter = senderFilter,
                        conversationNameFilter = conversationNameFilter,
                        conversationIsGroupFilter = groupFilter,
                        limit = queryLimit
                    )
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed || refreshGeneration != historyRefreshGeneration) return@runOnUiThread
                renderArchivedHistory(archivedMessages)
            }
        }
    }

    private fun renderArchivedHistory(archivedMessages: List<ArchivedNotification>) {
        if (!::historyContainer.isInitialized || isFinishing || isDestroyed) return
        val activityContext = this
        val hideNoisy = displayPreferences().getBoolean("hide_calls_reactions", false) && !inTrash && !inGallery
        val messages = if (hideNoisy) archivedMessages.filterNot { isCallOrReaction(it.title, it.text) } else archivedMessages
        historyCountLabel.text = if (messages.isEmpty()) "0" else messages.size.toString()
        if (messages.isEmpty()) {
            historyContainer.addView(verticalCard().apply {
                addView(TextView(this@MainActivity).apply {
                    text = when {
                        !searchField.text.isNullOrBlank() -> "Aucun résultat pour cette recherche."
                        inGallery && galleryMediaType == "image" -> "Aucune photo archivée ici."
                        inGallery && galleryMediaType == "video" -> "Aucune vidéo archivée ici."
                        inGallery && galleryMediaType == "audio" -> "Aucun audio ou message vocal archivé ici."
                        inGallery -> "Aucune photo, vidéo ou message vocal archivé ici."
                        inTrash -> "La corbeille est vide. Les messages déplacés y apparaîtront ici."
                        else -> "Votre historique apparaîtra ici après réception d’une notification."
                    }
                    textSize = 14f
                    setTextColor(subtleColor)
                    gravity = Gravity.CENTER
                }, matchWidth().apply { setMargins(0, dp(12), 0, dp(12)) })
            }, matchWidth())
            return
        }

        val visibleMessages = if (inTrash && showAllTrashMessages) messages else messages.take(historyVisibleCount)
        visibleMessages.forEach { item ->
            val card = verticalCard()
            val appLabel = supportedApps.firstOrNull { it.second == item.packageName }?.first ?: item.packageName
            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val appPill = TextView(this).apply {
                text = appLabel
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentDarkColor)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = roundedBackground(softSurfaceColor, dp(9))
            }
            metaRow.addView(appPill)
            metaRow.addView(TextView(this).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.postedAt))
                textSize = 12f
                setTextColor(subtleColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
            card.addView(metaRow, matchWidth())

            val groupName = item.conversationName.ifBlank { item.title }
            if (item.isGroup && groupName.isNotBlank()) {
                val groupRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                groupRow.addView(
                    avatarView(
                        item.conversationAvatarPath,
                        groupName,
                        38,
                        20,
                        "Photo du groupe $groupName"
                    ),
                    LinearLayout.LayoutParams(dp(38), dp(38))
                )
                groupRow.addView(TextView(this).apply {
                    text = groupName
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(inkColor)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(9)
                })
                card.addView(groupRow, matchWidth().apply { topMargin = dp(12) })
            }

            val displayName = item.senderName.ifBlank { item.title }
            if (displayName.isNotBlank()) {
                val senderRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                senderRow.addView(
                    avatarView(item.senderAvatarPath, displayName, 42, 22, "Photo de profil de $displayName"),
                    LinearLayout.LayoutParams(dp(42), dp(42))
                )
                senderRow.addView(TextView(this).apply {
                    text = displayName
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(inkColor)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                })
                card.addView(senderRow, matchWidth().apply { topMargin = dp(12) })
            }
            if (item.title.isNotBlank() && item.title != displayName && item.title != groupName) card.addView(TextView(this).apply {
                text = item.title
                textSize = 13f
                setTextColor(subtleColor)
            }, matchWidth().apply { topMargin = dp(5) })
            if (item.text.isNotBlank()) card.addView(TextView(this).apply {
                text = item.text
                textSize = 15f
                setTextColor(inkColor)
                setLineSpacing(dp(2).toFloat(), 1f)
            }, matchWidth().apply { topMargin = dp(4) })
            item.mediaPath?.let { path ->
                val mediaMimeType = item.mediaMimeType ?: "image/jpeg"
                if (item.mediaMimeType?.startsWith("audio/") == true) {
                    card.addView(buildAudioPlayer(path), matchWidth().apply { topMargin = dp(10) })
                } else if (item.mediaMimeType?.startsWith("video/") == true) {
                    val video = VideoView(this)
                    val controller = MediaController(this)
                    controller.setAnchorView(video)
                    video.setMediaController(controller)
                    video.setVideoPath(path)
                    card.addView(video, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
                    ).apply { topMargin = dp(10) })
                } else {
                    addImagePreviewAsync(card, path)
                }
                card.addView(buildMediaActions(path, mediaMimeType),
                    matchWidth().apply { topMargin = dp(6) })
            }
            if (inTrash) {
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(Button(activityContext).apply {
                    text = getString(R.string.restore)
                    textSize = 12f
                    setTextColor(accentDarkColor)
                    backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                    setOnClickListener {
                        NotificationStore(activityContext).restore(item.id)
                        refreshHistory()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                actions.addView(Button(activityContext).apply {
                    text = getString(R.string.delete_permanently)
                    textSize = 12f
                    setTextColor(dangerColor)
                    setOnClickListener { confirmPermanentDelete(item) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                })
                card.addView(actions, matchWidth().apply { topMargin = dp(8) })
            } else {
                card.addView(Button(this).apply {
                    text = getString(R.string.move_to_trash)
                    textSize = 12f
                    setTextColor(accentDarkColor)
                    backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                    setOnClickListener {
                        NotificationStore(activityContext).moveNotificationToTrash(item.id)
                        refreshHistory()
                    }
                }, matchWidth().apply { topMargin = dp(8) })
            }
            historyContainer.addView(card, matchWidth().apply { bottomMargin = dp(10) })
        }
        if (archivedMessages.size > historyVisibleCount && (!inTrash || !showAllTrashMessages)) {
            historyContainer.addView(Button(this).apply {
                text = if (inTrash) "Afficher toute la corbeille" else "Afficher 60 messages de plus"
                textSize = 14f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener {
                    if (inTrash) showAllTrashMessages = true else historyVisibleCount += 60
                    refreshHistory()
                }
            }, matchWidth().apply { topMargin = dp(4); bottomMargin = dp(12) })
        }
    }

    private fun showImageFullscreen(path: String) {
        val bitmap = decodeSampledBitmap(path, maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2) ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Photo en plein écran"
        }
        container.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(35, 52, 45), dp(28))
            contentDescription = "Fermer la photo"
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20)
            marginEnd = dp(20)
        })
        dialog.setContentView(container)
        dialog.show()
    }

    private fun decodeSampledBitmap(path: String, maxSide: Int) = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        val target = maxSide.coerceAtLeast(1)
        while (bounds.outWidth / sampleSize > target || bounds.outHeight / sampleSize > target) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        })
    }.getOrNull()

    private fun buildMediaActions(path: String, mimeType: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(this@MainActivity).apply {
            text = getString(R.string.share)
            textSize = 12f
            setTextColor(accentDarkColor)
            backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
            setOnClickListener { shareMedia(path, mimeType) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@MainActivity).apply {
            text = if (mimeType.startsWith("video/")) "Enregistrer dans Vidéos" else "Enregistrer dans Photos"
            textSize = 12f
            setTextColor(accentDarkColor)
            backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
            setOnClickListener { saveMediaToGallery(path, mimeType) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
    }

    private fun shareMedia(path: String, mimeType: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Média archivé", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Partager le média"))
        }.onFailure {
            Toast.makeText(this, "Impossible de partager ce média.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveMediaToGallery(path: String, mimeType: String) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "L’enregistrement direct nécessite Android 10 ou plus.", Toast.LENGTH_LONG).show()
            return
        }
        var destination: android.net.Uri? = null
        runCatching {
            val source = File(path)
            val isVideo = mimeType.startsWith("video/")
            val extension = source.extension.ifBlank { if (isVideo) "mp4" else "jpg" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Souvenir_${System.currentTimeMillis()}.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) "Movies/Souvenir notifications" else "Pictures/Souvenir notifications"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val destinationUri = contentResolver.insert(collection, values)
                ?: error("Impossible de créer le fichier média")
            destination = destinationUri
            val output = contentResolver.openOutputStream(destinationUri)
                ?: error("Impossible d’ouvrir le fichier de destination")
            output.use { target -> FileInputStream(source).use { it.copyTo(target) } }
            contentResolver.update(
                destinationUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }.onSuccess {
            Toast.makeText(this, "Média enregistré dans la galerie.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            destination?.let { contentResolver.delete(it, null, null) }
            Toast.makeText(this, "L’enregistrement du média a échoué.", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadConversationList(generation: Int) {
        val packageFilter = selectedApp?.second
        val query = searchField.text?.toString().orEmpty()
        val hideCallsAndReactions = displayPreferences().getBoolean("hide_calls_reactions", false)
        historyQueryExecutor.execute {
            val conversations = runCatching {
                synchronized(ArchiveEvents.databaseLock) {
                    NotificationStore(this).conversations(
                        packageNameFilter = packageFilter,
                        inTrash = false,
                        query = query
                    )
                }
            }.getOrDefault(emptyList()).let { results ->
                if (hideCallsAndReactions) {
                    results.filterNot { isCallOrReaction(it.displayName, it.latestText) }
                } else results
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != historyRefreshGeneration) return@runOnUiThread
                renderConversationList(conversations)
            }
        }
    }

    private fun renderConversationList(conversations: List<ConversationSummary>) {
        historyContainer.removeAllViews()
        historyCountLabel.text = NumberFormat.getIntegerInstance().format(conversations.size)
        if (conversations.isEmpty()) {
            historyContainer.addView(verticalCard().apply {
                addView(TextView(this@MainActivity).apply {
                    text = if (searchField.text.isNullOrBlank()) {
                        "Les conversations apparaîtront ici après réception de messages."
                    } else "Aucune conversation ne correspond à cette recherche."
                    textSize = 14f
                    setTextColor(subtleColor)
                    gravity = Gravity.CENTER
                }, matchWidth().apply { setMargins(0, dp(12), 0, dp(12)) })
            }, matchWidth())
            return
        }

        var previousSectionIsGroup: Boolean? = null
        conversations.take(conversationVisibleCount).forEach { conversation ->
            if (previousSectionIsGroup != conversation.isGroup) {
                historyContainer.addView(TextView(this).apply {
                    text = if (conversation.isGroup) "Groupes" else "Discussions privées"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(inkColor)
                    setPadding(dp(4), dp(10), dp(4), dp(8))
                }, matchWidth())
                previousSectionIsGroup = conversation.isGroup
            }
            val card = verticalCard().apply {
                isClickable = true
                setOnClickListener {
                    conversationFilter = conversation.packageName to conversation.displayName
                    conversationFilterIsGroup = conversation.isGroup
                    historyVisibleCount = 60
                    inConversations = false
                    refreshModeSelector()
                    refreshHistory()
                }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val appName = supportedApps.firstOrNull { it.second == conversation.packageName }?.first
                ?: conversation.packageName
            header.addView(TextView(this).apply {
                text = appName
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentDarkColor)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedBackground(softSurfaceColor, dp(9))
            })
            header.addView(smallLabel(if (conversation.isGroup) "Groupe" else "Privé"))
            header.addView(TextView(this).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(conversation.latestAt))
                textSize = 11f
                setTextColor(subtleColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
            val countLabel = if (conversation.messageCount == 1) "1 notification" else
                "${conversation.messageCount} notifications"
            header.addView(smallLabel(countLabel))
            card.addView(header, matchWidth())

            val personRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            personRow.addView(
                avatarView(
                    conversation.avatarPath,
                    conversation.displayName,
                    44,
                    22,
                    "Photo de ${if (conversation.isGroup) "groupe" else "profil"} ${conversation.displayName}"
                ),
                LinearLayout.LayoutParams(dp(44), dp(44))
            )
            personRow.addView(TextView(this).apply {
                text = conversation.displayName
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(inkColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            card.addView(personRow, matchWidth().apply { topMargin = dp(10) })
            if (conversation.isGroup && conversation.latestSenderName.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Dernier message de ${conversation.latestSenderName}"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(subtleColor)
                }, matchWidth().apply { topMargin = dp(8) })
            }
            card.addView(TextView(this).apply {
                text = conversation.latestText
                textSize = 14f
                setTextColor(inkColor)
                maxLines = 2
            }, matchWidth().apply { topMargin = dp(8) })
            historyContainer.addView(card, matchWidth().apply { bottomMargin = dp(10) })
        }
        if (conversations.size > conversationVisibleCount) {
            historyContainer.addView(Button(this).apply {
                text = getString(R.string.show_more_conversations)
                textSize = 14f
                setTextColor(accentDarkColor)
                backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
                setOnClickListener {
                    conversationVisibleCount += 60
                    refreshHistory()
                }
            }, matchWidth().apply { topMargin = dp(4); bottomMargin = dp(12) })
        }
    }

    private fun confirmClearHistory() {
        val app = selectedApp
        val appName = app?.first
        AlertDialog.Builder(this)
            .setTitle(when {
                inTrash && appName == null -> "Vider toute la corbeille ?"
                inTrash -> "Vider la corbeille $appName ?"
                appName == null -> "Déplacer tout l’historique vers la corbeille ?"
                else -> "Déplacer l’historique $appName vers la corbeille ?"
            })
            .setMessage(if (inTrash) {
                "Les éléments de la corbeille seront supprimés définitivement."
            } else {
                "Les notifications seront déplacées dans la corbeille, où vous pourrez les restaurer."
            })
            .setNegativeButton("Annuler", null)
            .setPositiveButton(if (inTrash) "Vider" else "Déplacer") { _, _ ->
                val store = NotificationStore(this)
                val files = if (inTrash) store.emptyTrash(app?.second) else {
                    store.moveToTrash(app?.second)
                    emptyList()
                }
                files.forEach { path -> runCatching { File(path).delete() } }
                refreshHistory()
            }
            .show()
    }

    private fun confirmRestoreTrash() {
        AlertDialog.Builder(this)
            .setTitle("Restaurer toute la corbeille ?")
            .setMessage("Tous les éléments quitteront la corbeille et réapparaîtront dans l’historique.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Tout restaurer") { _, _ ->
                val restored = NotificationStore(this).restoreTrash()
                inTrash = false
                refreshModeSelector()
                refreshHistory()
                Toast.makeText(this, "$restored élément(s) restauré(s)", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showStorageManager() {
        val store = NotificationStore(this)
        val summary = store.mediaStorageSummary()
        val largestFiles = store.largestMediaFiles(10)
        var managerDialog: AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(smallLabel("${summary.fileCount} médias · ${formatBytes(summary.totalBytes)} utilisés"), matchWidth())
            if (largestFiles.isEmpty()) {
                addView(smallLabel("Aucun fichier média à gérer."), matchWidth().apply { topMargin = dp(14) })
            }
            largestFiles.forEach { media ->
                val kind = when {
                    media.mimeType?.startsWith("video/") == true -> "Vidéo"
                    media.mimeType?.startsWith("audio/") == true -> "Audio ou vocal"
                    else -> "Photo"
                }
                addView(verticalCard().apply {
                    addView(TextView(this@MainActivity).apply {
                        text = media.title.ifBlank { "Média archivé" }
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(inkColor)
                    }, matchWidth())
                    addView(smallLabel("$kind · ${formatBytes(media.sizeBytes)}"),
                        matchWidth().apply { topMargin = dp(3) })
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.delete_media)
                        textSize = 12f
                        setTextColor(Color.rgb(145, 45, 45))
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Supprimer ce média ?")
                                .setMessage("Le fichier sera supprimé, mais le texte de la notification restera dans l’historique.")
                                .setNegativeButton("Annuler", null)
                                .setPositiveButton("Supprimer") { _, _ ->
                                    store.deleteMediaFile(media.path)
                                    refreshStorageSummary()
                                    refreshHistory()
                                    managerDialog?.dismiss()
                                    showStorageManager()
                                }
                                .show()
                        }
                    }, matchWidth().apply { topMargin = dp(5) })
                }, matchWidth().apply { topMargin = dp(10) })
            }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        managerDialog = AlertDialog.Builder(this)
            .setTitle("Médias les plus volumineux")
            .setView(scroll)
            .setPositiveButton("Fermer", null)
            .create()
        managerDialog?.show()
    }

    private fun buildAudioPlayer(path: String): View = Button(this).apply {
        text = "▶ Écouter le message vocal"
        textSize = 14f
        setTextColor(accentDarkColor)
        backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
        setOnClickListener {
            try {
                if (currentAudioPath == path && currentAudioPlayer != null) {
                    val player = currentAudioPlayer ?: return@setOnClickListener
                    if (player.isPlaying) {
                        player.pause()
                        text = "▶ Reprendre le message vocal"
                    } else {
                        player.start()
                        text = "⏸ Mettre en pause"
                    }
                    return@setOnClickListener
                }
                currentAudioPlayer?.release()
                currentAudioPlayer = null
                currentAudioPath = path
                text = "Préparation de l’écoute…"
                isEnabled = false
                val player = MediaPlayer()
                currentAudioPlayer = player
                player.setOnPreparedListener { readyPlayer ->
                    if (currentAudioPlayer === readyPlayer) {
                        readyPlayer.start()
                        text = "⏸ Mettre en pause"
                        isEnabled = true
                    }
                }
                player.setOnCompletionListener { finishedPlayer ->
                    if (currentAudioPlayer === finishedPlayer) {
                        text = "▶ Réécouter le message vocal"
                        currentAudioPlayer?.release()
                        currentAudioPlayer = null
                        currentAudioPath = null
                    }
                }
                player.setOnErrorListener { failedPlayer, _, _ ->
                    failedPlayer.release()
                    if (currentAudioPlayer === failedPlayer) {
                        currentAudioPlayer = null
                        currentAudioPath = null
                    }
                    text = "Lecture audio indisponible"
                    isEnabled = true
                    true
                }
                player.setDataSource(path)
                player.prepareAsync()
            } catch (error: Exception) {
                currentAudioPlayer?.release()
                currentAudioPlayer = null
                currentAudioPath = null
                text = "Lecture audio indisponible"
                isEnabled = true
                Toast.makeText(this@MainActivity, error.localizedMessage ?: "Impossible de lire ce fichier.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmPermanentDelete(item: ArchivedNotification) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer définitivement ?")
            .setMessage("Ce message ne pourra pas être restauré.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                NotificationStore(this).deletePermanently(item.id)
                    .forEach { path -> runCatching { File(path).delete() } }
                refreshHistory()
            }
            .show()
    }

    private fun verticalCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedBackground(surfaceColor, dp(16), borderColor)
    }

    private fun smallLabel(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTextColor(subtleColor)
    }

    private fun avatarView(
        path: String?,
        label: String,
        sizeDp: Int,
        radiusDp: Int,
        description: String
    ): View {
        val cacheKey = path?.let { avatarCacheKey(it, sizeDp) }
        val initials = TextView(this).apply {
            text = label.trim().take(1).uppercase().ifBlank { "?" }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentDarkColor)
            gravity = Gravity.CENTER
            background = roundedBackground(softSurfaceColor, dp(radiusDp))
            contentDescription = description
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(softSurfaceColor, dp(radiusDp))
            clipToOutline = true
            contentDescription = description
            tag = cacheKey
        }
        val container = FrameLayout(this).apply {
            addView(initials, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            contentDescription = description
        }

        if (cacheKey != null) {
            val cachedBitmap = avatarBitmapCache.get(cacheKey)
            if (cachedBitmap != null) {
                image.setImageBitmap(cachedBitmap)
                initials.visibility = View.GONE
            } else {
                runCatching {
                    avatarDecodeExecutor.execute {
                        val bitmap = loadAvatarBitmap(path, sizeDp)
                        if (bitmap != null) runOnUiThread {
                            if (!isFinishing && !isDestroyed && image.tag == cacheKey) {
                                image.setImageBitmap(bitmap)
                                initials.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
        return container
    }

    private fun avatarCacheKey(path: String, sizeDp: Int): String = "$path|$sizeDp"

    private fun addImagePreviewAsync(card: LinearLayout, path: String) {
        val previewBackground = if (isDarkMode) Color.rgb(31, 65, 52) else Color.rgb(220, 252, 231)
        val placeholder = TextView(this).apply {
            text = "Chargement de l’aperçu…"
            textSize = 13f
            setTextColor(subtleColor)
            gravity = Gravity.CENTER
            minHeight = dp(160)
            background = roundedBackground(previewBackground, dp(12))
        }
        val image = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(360)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = roundedBackground(previewBackground, dp(12))
            clipToOutline = true
            contentDescription = "Ouvrir la photo en grand"
            isClickable = true
            tag = path
            setOnClickListener { showImageFullscreen(path) }
            visibility = View.GONE
        }
        val frame = FrameLayout(this).apply {
            addView(placeholder, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        card.addView(frame, matchWidth().apply { topMargin = dp(10) })

        val cached = mediaPreviewCache.get(path)
        if (cached != null) {
            image.setImageBitmap(cached)
            image.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            return
        }

        val maxSidePx = dp(720)
        runCatching {
            mediaPreviewExecutor.execute {
                val preview = runCatching { decodeSampledBitmap(path, maxSidePx) }.getOrNull()
                if (preview != null) mediaPreviewCache.put(path, preview)
                runOnUiThread {
                    if (isFinishing || isDestroyed || image.tag != path) return@runOnUiThread
                    if (preview == null) {
                        placeholder.text = "Aperçu indisponible"
                    } else {
                        image.setImageBitmap(preview)
                        image.visibility = View.VISIBLE
                        placeholder.visibility = View.GONE
                    }
                }
            }
        }.onFailure {
            placeholder.text = "Aperçu indisponible"
        }
    }

    private fun loadAvatarBitmap(path: String?, sizeDp: Int): android.graphics.Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || file.length() < 3L) return null
        val cacheKey = avatarCacheKey(path, sizeDp)
        avatarBitmapCache.get(cacheKey)?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetPx = dp(sizeDp).coerceAtLeast(1)
        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > targetPx * 2) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        avatarBitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun roundedBackground(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun preferences() = getSharedPreferences("capture_preferences", MODE_PRIVATE)
    private fun consentPreferences() = getSharedPreferences("capture_consent", MODE_PRIVATE)
    private fun displayPreferences() = getSharedPreferences("display_preferences", MODE_PRIVATE)
    private fun appLockPreferences() = getSharedPreferences("app_lock_preferences", MODE_PRIVATE)

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REQUEST_CREATE_BACKUP = 4101
        private const val REQUEST_RESTORE_BACKUP = 4102
        private const val REQUEST_APP_UNLOCK = 4103
        private const val REQUEST_WHATSAPP_MEDIA_TREE = 4104
        private val supportedApps = listOf(
            "WhatsApp" to "com.whatsapp",
            "Telegram" to "org.telegram.messenger",
            "Messenger" to "com.facebook.orca",
            "Instagram" to "com.instagram.android",
            "Snapchat" to "com.snapchat.android",
            "TikTok" to "com.zhiliaoapp.musically"
        )
    }
}
