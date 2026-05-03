package com.grzwolf.grzlog

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.getActivity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.grzwolf.grzlog.DataStore.TIMESTAMP
import com.grzwolf.grzlog.FileUtils.Companion.getPath
import com.grzwolf.grzlog.MainActivity.Companion.AttachmentStorage
import com.grzwolf.grzlog.MainActivity.Companion.appName
import com.grzwolf.grzlog.MainActivity.Companion.appPwdPub
import com.grzwolf.grzlog.MainActivity.Companion.appStoragePath
import com.grzwolf.grzlog.MainActivity.Companion.contextMainActivity
import com.grzwolf.grzlog.MainActivity.Companion.ds
import com.grzwolf.grzlog.MainActivity.Companion.returningFromAppGallery
import com.grzwolf.grzlog.MainActivity.Companion.writeAppData
import com.grzwolf.grzlog.MainActivity.Companion.readAppData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.collections.MutableList


public class SettingsActivity :
    AppCompatActivity(),
    OnSharedPreferenceChangeListener,
    LifecycleObserver {
    // listener for any pref change: https://stackoverflow.com/questions/2542938/sharedpreferences-onsharedpreferencechangelistener-not-being-called-consistently
    public override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, s: String?) {
        // s == null after reset all preferences
        if (s == null) {
            return
        }
        // update data in main after data restore from a backup
        if (MainActivity.appGalleryAdapter == null) {
            MainActivity.reReadAppFileData = true
        }
        if (s == "newAtBottom") {
            MainActivity.reReadAppFileData = true
        }
        if (s == getString(R.string.chosenTheme)) {
            MainActivity.reReadAppFileData = true
        }
        if (s == "AppAtStartCheckUpdateFlag") {
            MainActivity.reReadAppFileData = true
        }
        if (s == "encryptProtectedFolders") {
            // clear app cache
            MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
            // signal to MainActivity to refresh its data
            MainActivity.reReadAppFileData = true
        }
        if (s == "app_pwd") {
            // clear app cache
            MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
            // signal to MainActivity to refresh its data
            MainActivity.reReadAppFileData = true
        }
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        // apply theme locally to settings; must be called before super.onCreate
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        var theme = sharedPref.getString(getString(R.string.chosenTheme), "Dark")
        when (theme) {
            "Dark"     -> {
                setTheme(R.style.ThemeOverlay_AppCompat_Dark)
                // API 36: set the icon/text colors in the status bar and nav bar
                WindowInsetsControllerCompat(
                    window,
                    window.decorView).isAppearanceLightStatusBars = false
                WindowInsetsControllerCompat(
                    window,
                    window.decorView).isAppearanceLightNavigationBars = false
            }
            "Light"     -> {
                setTheme(R.style.ThemeOverlay_AppCompat_Light)
                // API 36: set the icon/text colors in the status bar and nav bar
                WindowInsetsControllerCompat(
                    window,
                    window.decorView).isAppearanceLightStatusBars = true
                WindowInsetsControllerCompat(
                    window,
                    window.decorView).isAppearanceLightNavigationBars = true
            }
            else        -> {
                if (theme == "tmpdaynight") {
                    // supposed to fix too bright dialogs after a MagicOS 8 update
                    // --> MainActivity will override such theme after being back there
                    setTheme(R.style.ThemeOverlay_AppCompat_DayNight)
                } else {
                    setTheme(R.style.ThemeOverlay_AppCompat_Dark)
                }
            }
        }
        super.onCreate(savedInstanceState)

        // local app reminders
        MainActivity.showAppReminders = false

        // life cycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // make volatile data static
        appContext = applicationContext
        settingsActivity = this
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // revert API36 insets for API < 35
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setContentView(R.layout.settings_activity35)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        } else {
            setContentView(R.layout.settings_activity)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }

        // life cycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // life cycle observer knows, if app is in foreground or not
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppInForeground () {
        isSettingsActivityInForeground = true
    }
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppInBackground() {
        isSettingsActivityInForeground = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Settings menu top left back button detection
        if (item.itemId == android.R.id.home) {
            // release controlling intent, if Settings are left
            MainActivity.intentSettings = null
            // we mimic the same behaviour, as if the Android back button were clicked
            onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    // Android back button detection
    override fun onBackPressed() {
        // release controlling intent, if Settings are left
        MainActivity.intentSettings = null
        MainActivity.showAppReminders = false
        super.onBackPressed()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // http request to communicate with a website
        fun httpCheckForUpdate(context: Context, url: String, updateCheckPref: Preference, updateCheckTitle: String, appVer: String, updateLinkPref: Preference ) {
            // reset MainActivity available update flag
            MainActivity.grzlogUpdateAvailable = false
            // build web request
            val queue = Volley.newRequestQueue(context)
            val stringRequest = StringRequest(Request.Method.GET, url,
                object : Response.Listener<String?> {
                    override fun onResponse(response: String?) {
                        if (response != null) {
                            var listTags: MutableList<String> = ArrayList()
                            val m = Pattern.compile("tag/v\\d+.\\d+.\\d+").matcher(response)
                            while (m.find() == true) {
                                var group = m.group()
                                if (group.startsWith("tag/v")) {
                                    var result = m.group().substring(5)
                                    if (!listTags.contains(result)) {
                                        listTags.add(result)
                                    }
                                }
                            }
                            var updateAvailable = false
                            var tagVersion = ""
                            for (tagVer in listTags) {
                                if (isUpdateDue(appVer.split('.').toTypedArray(), tagVer.split('.').toTypedArray())) {
                                    updateAvailable = true
                                    tagVersion = tagVer
                                    break
                                }
                            }
                            if (updateAvailable) {
                                // inform MainActivity about available update
                                MainActivity.grzlogUpdateAvailable = true
                                // build update website link
                                var updateLink = "https://github.com/grzwolf/GrzLog/releases/tag/v" + tagVersion
                                updateCheckPref.setTitle(updateCheckTitle + " - " + context.getString(R.string.available) + " v" + tagVersion)
                                updateLinkPref.setTitle(updateLink)
                                // check matching download urls for APK files
                                checkDownloadUrls(context, updateLinkPref, tagVersion)
                                // leave guide hint
                                updateCheckPref.setSummary(R.string.clickExecBelow)
                            } else {
                                updateCheckPref.setTitle(updateCheckTitle + " - " + context.getString(R.string.upToDate))
                                updateCheckPref.setSummary(R.string.clickHere)
                            }
                        } else {
                            updateCheckPref.setTitle(updateCheckTitle + " - " + context.getString(R.string.errorNoData))
                            updateCheckPref.setSummary(R.string.clickHere)
                        }
                    }
                },
                object : Response.ErrorListener {
                    override fun onErrorResponse(error: VolleyError?) {
                        updateCheckPref.setTitle(updateCheckTitle + " - " + context.getString(R.string.errorWebsite))
                        updateCheckPref.setSummary(R.string.clickHere)
                    }
                })
            queue.add(stringRequest)
        }
        // two urls to check
        fun checkDownloadUrls(context: Context, updateLinkPref: Preference, tagVersion: String) {
            // check urls must happen outside of UI thread
            Thread {
                // build update file link
                var apkLink = "https://github.com/grzwolf/GrzLog/releases/download/" + "v" + tagVersion + "/com.grzwolf.grzlog.v" + tagVersion
                var apkLinkDeb = apkLink + "-debug.apk"
                var apkLinkRel = apkLink + "-release.apk"
                apkLink = ""
                // check urls
                if (urlExists(apkLinkRel)) {
                    // release APK is favorite
                    apkLink = apkLinkRel
                } else {
                    if (urlExists(apkLinkDeb)) {
                        // debug APK is 2nd choice
                        apkLink = apkLinkDeb
                    }
                }
                if (apkLink.length > 0) {
                    // if all went fine, there will be an APK link
                    (context as Activity).runOnUiThread {
                        updateLinkPref.setSummary(apkLink)
                    }
                }
            }.start()
        }

        // runnable to update status of active services
        private val srvStatusHandler = Handler()
        private var srvStatusRunnable: Runnable = Runnable {
            run {
                // pref summary update
                var msg = ""
                val stopSrvPref = findPreference("StopServices") as Preference?
                if (MainActivity.backupOngoing || gdriveUploadOngoing) {
                    if (MainActivity.backupOngoing) {
                        stopSrvPref!!.isEnabled = true
                        msg = appContext!!.getString(R.string.backup_active)
                    }
                    if (gdriveUploadOngoing) {
                        stopSrvPref!!.isEnabled = true
                        if (msg.length > 0) {
                            msg += appContext!!.getString(R.string.and_upload_active)
                        } else {
                            msg = appContext!!.getString(R.string.upload_active)
                        }
                    }
                } else {
                    stopSrvPref!!.isEnabled = false
                    msg = appContext!!.getString(R.string.stopAppServicesSummary)
                }
                stopSrvPref!!.summary = msg
                // run & repeat it
                srvStatusHandler.postDelayed(srvStatusRunnable, 5000)
            }
        }

        @Suppress("unused")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(appContext!!)
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            // check status of GrzLog services
            srvStatusRunnable.run()

            // theme chooser
            val themePreference = findPreference<Preference>(getString(R.string.chosenTheme)) as ListPreference?
            themePreference!!.summary = getString(R.string.currentTheme) + sharedPref.getString(getString(R.string.chosenTheme), "?")
            themePreference.setOnPreferenceChangeListener { preference, newValue ->
                if (preference is ListPreference) {
                    // note: preference gets automatically updated after leaving setOnPreferenceChangeListener
                    val index = preference.findIndexOfValue(newValue.toString())
                    val entry = preference.entries.get(index)
                    val entryvalue = preference.entryValues.get(index)
                    themePreference.summary = getString(R.string.currentTheme) + entry
                    // restart settings activity to apply the theme
                    requireActivity().startActivity(Intent(context, SettingsActivity::class.java))
                    // close current settings activity
                    (context as Activity).finish()
                }
                true
            }

            // new input placement
            val nip = findPreference<ListPreference>("chosenPlacement")
            if (sharedPref.getBoolean("newAtBottom", false)) {
                nip!!.setValueIndex(1)
            } else {
                nip!!.setValueIndex(0)
            }
            nip.summary = nip.entry
            nip.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { preference, newValue ->
                    // parse change
                    var newValBool = false
                    if (newValue == "bottom") {
                        newValBool = true
                        nip.setValueIndex(1)
                    }
                    if (newValue == "top") {
                        newValBool = false
                        nip.setValueIndex(0)
                    }
                    // update summary
                    nip.summary = nip.entry
                    // update shared preference
                    val spe = sharedPref.edit()
                    spe.putBoolean("newAtBottom", newValBool)
                    spe.apply()
                    true
                }

            // choose a backup mode strategy
            // at the beginning, show current states after entering Settings
            var manuallyValBoolOrig = sharedPref.getBoolean("BackupModeManually", false)
            val bakModePref = findPreference<ListPreference>("backupMode")
            if (manuallyValBoolOrig) {
                bakModePref!!.setValueIndex(0)
            } else {
                bakModePref!!.setValueIndex(1)
            }
            var pref = findPreference<Preference>("backupReminder")
            pref!!.isEnabled = manuallyValBoolOrig
            var bakFg = sharedPref.getBoolean("backupForeground", false)
            var spc = findPreference<SwitchPreferenceCompat>("backupForeground")
            spc!!.setChecked(bakFg)
            if (!manuallyValBoolOrig) {
                spc.setChecked(true)
            }
            spc.isEnabled = manuallyValBoolOrig
            pref = findPreference<Preference>("Backup")
            pref!!.isEnabled = manuallyValBoolOrig
            // handle the tap on backup mode strategy
            bakModePref.summary = bakModePref.entry
            bakModePref.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { preference, newValue ->
                    // parse change
                    var manuallyValBool = false
                    if (newValue == "manually") {
                        manuallyValBool = true
                        bakModePref.setValueIndex(0)
                    }
                    if (newValue == "automated") {
                        manuallyValBool = false
                        bakModePref.setValueIndex(1)
                    }
                    // update summary
                    bakModePref.summary = bakModePref.entry
                    // update invisible switch preference
                    var pref = findPreference<Preference>("BackupModeManually")
                    pref!!.setDefaultValue(!manuallyValBool)
                    // update 'outdated reminder'
                    pref = findPreference<Preference>("backupReminder")
                    pref!!.isEnabled = manuallyValBool
                    // update 'fg vs. bg'
                    var spc = findPreference<SwitchPreferenceCompat>("backupForeground")
                    spc!!.setChecked(bakFg)
                    if (!manuallyValBool) {
                        spc.setChecked(true)
                    }
                    spc.isEnabled = manuallyValBool
                    // update 'Backup Data now'
                    pref = findPreference<Preference>("Backup")
                    pref!!.isEnabled = manuallyValBool
                    // update shared preference
                    val spe = sharedPref.edit()
                    spe.putBoolean("BackupModeManually", manuallyValBool)
                    spe.apply()
                    true
                }

            // backup data info
            try {
                // tricky way to make the preference usable in static context
                val tmp: Preference by lazy {
                    findPreference<Preference>("BackupInfo") ?: error("fail")
                }
                backupInfo = tmp
                // show backup data info
                var file = getBackupFile(appContext!!)
                if (file != null) {
                    val lastModDate = Date(file.lastModified())
                    backupInfo.summary = file.toString() + "\n" +
                            lastModDate.toString() + "\n" +
                            bytesToHumanReadableSize(file.length().toDouble())
                } else {
                    backupInfo.summary = getString(R.string.noBackupExisting)
                }
            } catch (ise: IllegalStateException) {
                centeredToast(context!!, "Info error: " + ise.message.toString(), 3000)
            }

            // GCam usage/handling hint
            val gcam = findPreference<Preference>("useGoogleCamera") as SwitchPreferenceCompat?
            gcam!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { preference, newValue ->
                    if (newValue as Boolean == true) {
                        okBox(context, getString(R.string.note), getString(R.string.gcamhint))
                    }
                    true
                }

            // action after show help
            var showHelp = findPreference("AppHelp") as Preference?
            showHelp!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    requireActivity().startActivity(Intent(context, HelpActivity::class.java))
                } catch (e: Exception) {
                    centeredToast(appContext!!, "Help error: " + e.message.toString(), 3000)
                }
                true
            }

            // action after show GrzLog notes
            showHelp = findPreference("AppNotes") as Preference?
            showHelp!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    requireActivity().startActivity(Intent(context, NotesActivity::class.java))
                } catch (e: Exception) {
                    centeredToast(appContext!!, "Notes error: " + e.message.toString(), 3000)
                }
                true
            }

            // action after click show GrzLog What's New
            showHelp = findPreference("WhatsNew") as Preference?
            showHelp!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    requireActivity().startActivity(Intent(context, WhatsNewActivity::class.java))
                } catch (e: Exception) {
                    centeredToast(appContext!!, "What's New error: " + e.message.toString(), 3000)
                }
                true
            }

            // action after click folder encryption
            val encryptPref = findPreference("encryptProtectedFolders") as Preference?
            var pwdAvailable = sharedPref.getString("app_pwd", "")!!.isNotEmpty()
            // setting encryptPref is only available if a password for encryption is already set
            encryptPref!!.isEnabled = pwdAvailable
            var encryptProtectedFolders = sharedPref.getBoolean("encryptProtectedFolders", false)
            var encryptProtectedFoldersTmp = encryptProtectedFolders
            if (encryptProtectedFolders) {
                encryptPref!!.setSummary(getString(R.string.yesEncrypt))
            } else {
                encryptPref!!.setSummary(getString(R.string.noEncrypt))
            }
            encryptPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // build a prompt info structure
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("GrzLog Authentication")
                    .setSubtitle("Log in using system credential")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                // exec the actual prompt process
                val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(requireContext()),
                    // setup a few callbacks
                    object : BiometricPrompt.AuthenticationCallback() {
                        // error handling: usually CANCEL auth
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            centeredToast(requireContext(), "Authentication error: $errString", 3000)
                        }
                        // SUCCESS: auth did work
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            // handle authentication success
                            val items = arrayOf<CharSequence>(requireContext().getString(R.string.encryptProtectedFolders))
                            val checkedItems = BooleanArray(1) { encryptProtectedFolders }
                            val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog)
                            builder.setTitle(requireContext().getString(R.string.choose))
                            builder.setMultiChoiceItems(
                                items,
                                checkedItems,
                                OnMultiChoiceClickListener { dialog, which, isChecked ->
                                    encryptProtectedFoldersTmp = isChecked
                                })
                            builder.setPositiveButton("Ok", DialogInterface.OnClickListener { dialog, which ->
                                encryptProtectedFolders = encryptProtectedFoldersTmp
                                val spe = sharedPref.edit()
                                spe.putBoolean("encryptProtectedFolders", encryptProtectedFolders)
                                spe.apply()
                                encryptProtectedFolders = sharedPref.getBoolean("encryptProtectedFolders", false)
                                // flag to support extra backup need
                                var forceBackup = true
                                // the mode change changes data in DataStore
                                if (encryptProtectedFolders) {
                                    // folder shall become encrypted
                                    encryptPref!!.setSummary(getString(R.string.yesEncrypt))
                                    //
                                    // if encryption gets activated, there are two cases:
                                    //    1a) directly after restore from a backup, ds might contain encrypted data
                                    //        bc. data were read with disabled folder encryption flag
                                    //    1b) data were read with a wrong encryption password,
                                    //           which automatically disables folder encryption.
                                    //           but user forced folder encryption here and then re reads data
                                    //    2) normally ds contains unencrypted data
                                    //
                                    // so loop all folders to find out if at least one folder is encrypted
                                    var folderIsDataStoreEncrypted = false
                                    for (i in MainActivity.ds.timeSection.indices) {
                                        // only check protected folders
                                        if (MainActivity.ds.timeSection[i] == TIMESTAMP.AUTH) {
                                            // if a folder contains encrypted data, it would be a desaster to encrypt it again
                                            folderIsDataStoreEncrypted = KeyManager.isTextEncryptedGCM(MainActivity.ds.dataSection[i])
                                            break
                                        }
                                    }
                                    if (folderIsDataStoreEncrypted) {
                                        // write the exceptionally encrypted MainActivity.ds NOT ENCRYPTED to disk
                                        writeAppData(appStoragePath, MainActivity.ds, appName, false)
                                        // suppress fresh backup, bc. in this very specific case, it would make desaster
                                        forceBackup = false
                                    } else {
                                        // write the normally unencrypted MainActivity.ds ENCRYPTED to disk
                                        writeAppData(appStoragePath, MainActivity.ds, appName, true)
                                    }
                                    // finally read encrypted data from disk to normally decrypted MainActivity.ds (excptionally encrypted, see above)
                                    MainActivity.ds = readAppData(appStoragePath)
                                } else {
                                    // user decided to NOT encrypt folder
                                    encryptPref!!.setSummary(getString(R.string.noEncrypt))
                                    // write the always unencrypted MainActivity.ds unencrypted to disk
                                    writeAppData(appStoragePath, MainActivity.ds, appName, false)
                                    // read unencrypted data from disk to unencrypted MainActivity.ds
                                    MainActivity.ds = readAppData(appStoragePath)
                                }
                                // take care about backups, bc. any encryption mode change makes older backups obsolete
                                if (forceBackup == true) {
                                    if (sharedPref.getBoolean("BackupModeManually", false) == true) {
                                        // leave warning about outdated backups
                                        okBox(
                                            context,
                                            getString(R.string.note),
                                            getString(R.string.make_backup)
                                        )
                                    } else {
                                        // only exec backup in 'backup auto mode'
                                        if (!createTxtBackup(requireContext(), downloadDir, ds)) {
                                            okBox(requireContext(), "Note", "GrzLog.txt backup failed")
                                        }
                                        gBScontext = requireContext()
                                        gBSsrcFolder = requireContext().getExternalFilesDir(null)!!.absolutePath
                                        gBSoutFolder = downloadDir
                                        gBSzipName = "$appName.zip"
                                        gBSmaxProgress = countFiles(File(gBSsrcFolder))
                                        // start BackupService, which prevents interrupting the backup
                                        actionOnBackupService(requireContext(), BackupService.Companion.Actions.START)
                                    }
                                    // show GDrive backup as outdated
                                    val outdated = requireContext().getString(R.string.gdrive_outdated)
                                    spe.putString("BackupUploadGDrive", outdated)
                                    spe.apply()
                                    val bakGdrive = findPreference("BackupToGDrive") as Preference?
                                    bakGdrive!!.summary = requireContext().getString(R.string.clickHere) + " " + outdated
                                }
                            })
                            builder.setNegativeButton(R.string.cancel, DialogInterface.OnClickListener { dlg, which ->
                                return@OnClickListener
                            })
                            val dialog = builder.create()
                            dialog.setOnShowListener {
                                dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                            }
                            dialog.show()
                            dialog.setCanceledOnTouchOutside(false)
                        }
                        // failure: usually there is no system auth available
                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            centeredToast(requireContext(), "Authentication failed.", 3000)
                        }
                    })
                // exec auth
                biometricPrompt.authenticate(promptInfo)
                true
            }

            // action after click show folder encryption pwd
            val showPwdPref = findPreference("showEncryptionPassword") as Preference?
            // setting encryptPref is only available if a password for encryption is already set
            showPwdPref!!.isEnabled = pwdAvailable
            showPwdPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // build a prompt info structure
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("GrzLog Authentication")
                    .setSubtitle("Log in using system credential")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                // exec the actual prompt process
                val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(requireContext()),
                    // setup a few callbacks
                    object : BiometricPrompt.AuthenticationCallback() {
                        // error handling: usually CANCEL auth
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            centeredToast(requireContext(), "Authentication error: $errString", 3000)
                        }
                        // SUCCESS: auth did work
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            // read encrypted pwd from app preferences
                            val appPwdEnc = sharedPref.getString("app_pwd", "")!!
                            // decrypt pwd with app's private key from keystore
                            val keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
                            val appPwdClear = keyManager.decryptPwdPrv(appPwdEnc)
                            // copy final pwd to clipboard
                            val clipboard = requireActivity().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(getString(R.string.copiedText), appPwdClear)
                            clipboard.setPrimaryClip(clip)
                            centeredToast(requireActivity(), appPwdClear + " " + getString(R.string.copiedToClip), 50)
                            // leave a "keep pwd" note
                            okBox(
                                context,
                                getString(R.string.note),
                                appPwdClear + " <- " + getString(R.string.keep_pwd)
                            )
                        }
                        // failure: usually there is no system auth available
                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            centeredToast(requireContext(), "Authentication failed.", 3000)
                        }
                    }
                )
                // exec auth
                biometricPrompt.authenticate(promptInfo)
                true
            }

            // action after click set encryption password
            val allowed =
                ('a'..'z') + ('A'..'Z') + ('0'..'9') +
                listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '�', '/', '=', '?', '_', '-', ':', '.', ';', ',')
            val allowedStr = allowed.joinToString().replace(',', 0.toChar()) + " ,"
            fun allowedOk(str: String): Boolean {
                val arrStr = str.toCharArray()
                var allFound = true
                for (arrItem in arrStr) {
                    var hit = false
                    for (allowedItem in allowed) {
                        if (arrItem.equals(allowedItem)) {
                            hit = true
                            break
                        }
                    }
                    if (hit == false) {
                        allFound = false
                    }
                }
                return allFound
            }
            val setPwd = findPreference("setEncryptionPassword") as Preference?
            if (pwdAvailable) {
                setPwd!!.setSummary(requireContext().getString(R.string.pwd_is_set))
            } else {
                setPwd!!.setSummary(requireContext().getString(R.string.pwd_is_not_set))
            }
            setPwd!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // build a prompt info structure
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("GrzLog Authentication")
                    .setSubtitle("Log in using system credential")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                // exec the actual prompt process
                val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(requireContext()),
                    // setup a few callbacks
                    object : BiometricPrompt.AuthenticationCallback() {
                        // error handling: usually CANCEL auth
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            centeredToast(requireContext(), "Authentication error: $errString", 3000)
                        }
                        // SUCCESS: auth did work
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            // handle authentication success
                            var inputBuilderOne: AlertDialog.Builder? = null
                            inputBuilderOne = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog)
                            inputBuilderOne.setTitle(requireContext().getString(R.string.set_new_pwd))
                            val inputOne = EditText(requireContext())
                            inputOne.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            inputOne.typeface = Typeface.MONOSPACE
                            inputOne.setTextColor(android.graphics.Color.WHITE)
                            inputBuilderOne.setView(inputOne)
                            // input One dlg ok
                            inputBuilderOne.setPositiveButton(
                                requireContext().getString(R.string.ok),
                                DialogInterface.OnClickListener { dialog, which ->
                                    var textOne = inputOne.text.toString()
                                    // check pwd length empty == delete pwd
                                    if (textOne.length == 0) {
                                        // ask
                                        decisionBox(
                                            requireContext(),
                                            DECISION.YESNO,
                                            getString(R.string.note),
                                            getString(R.string.delete_pwd),
                                            {
                                                // save empty pwd in app preferences
                                                val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
                                                val spe = sharedPref.edit()
                                                spe.putString("app_pwd", "")
                                                spe.apply()
                                                // disable folder encryption
                                                spe.putBoolean("encryptProtectedFolders", false)
                                                spe.apply()
                                                encryptPref!!.isEnabled = false
                                                centeredToast(requireContext(), getString(R.string.encryptProtectedFoldersDisabled), 200)
                                                // the recently deleted pwd disables its show status
                                                showPwdPref!!.isEnabled = false
                                                setPwd!!.setSummary(requireContext().getString(R.string.pwd_is_not_set))
                                                // write the always unencrypted MainActivity.ds unencrypted to disk
                                                writeAppData(appStoragePath, MainActivity.ds, appName, false)
                                                // read unencrypted data from disk to unencrypted MainActivity.ds
                                                MainActivity.ds = readAppData(appStoragePath)
                                                // leave success note
                                                okBox(
                                                    context,
                                                    getString(R.string.success),
                                                    getString(R.string.success_pwd_delete),
                                                    {
                                                        if (sharedPref.getBoolean("BackupModeManually", false) == true) {
                                                            // leave warning about outdated backups
                                                            okBox(
                                                                context,
                                                                getString(R.string.note),
                                                                getString(R.string.make_backup)
                                                            )
                                                        } else {
                                                            // only exec backup in 'backup auto mode'
                                                            if (!createTxtBackup(requireContext(), downloadDir, ds)) {
                                                                okBox(requireContext(), "Note", "GrzLog.txt backup failed")
                                                            }
                                                            gBScontext = requireContext()
                                                            gBSsrcFolder = requireContext().getExternalFilesDir(null)!!.absolutePath
                                                            gBSoutFolder = downloadDir
                                                            gBSzipName = "$appName.zip"
                                                            gBSmaxProgress = countFiles(File(gBSsrcFolder))
                                                            // start BackupService, which prevents interrupting the backup
                                                            actionOnBackupService(requireContext(), BackupService.Companion.Actions.START)
                                                        }
                                                        // show GDrive backup as outdated
                                                        val outdated = requireContext().getString(R.string.gdrive_outdated)
                                                        spe.putString("BackupUploadGDrive", outdated)
                                                        spe.apply()
                                                        val bakGdrive = findPreference("BackupToGDrive") as Preference?
                                                        bakGdrive!!.summary = requireContext().getString(R.string.clickHere) + " " + outdated
                                                    }
                                                )
                                            },
                                            {}
                                        )
                                        return@OnClickListener
                                    }
                                    // check pwd other length
                                    if (textOne.length < 16) {
                                        centeredToast(requireContext(), requireContext().getString(R.string.pwd_too_short), 3000)
                                        return@OnClickListener
                                    }
                                    // check pwd is in allowed chars
                                    if (allowedOk(textOne) == false) {
                                        centeredToast(requireContext(), requireContext().getString(R.string.only_asci), 3000)
                                        return@OnClickListener
                                    }
                                    // compare new pwd with current one
                                    val appPwdEnc = sharedPref.getString("app_pwd", "")!!
                                    val keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
                                    val appPwdClear = keyManager.decryptPwdPrv(appPwdEnc)
                                    if (textOne.equals(appPwdClear) == true) {
                                        centeredToast(requireContext(), requireContext().getString(R.string.new_pwd_equals_current), 3000)
                                        return@OnClickListener
                                    }
                                    // build pwd confirmation dialog
                                    val inputBuilderTwo = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog)
                                    inputBuilderTwo.setTitle(requireContext().getString(R.string.repeat_new_pwd))
                                    val inputTwo = EditText(requireContext())
                                    inputTwo.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                    inputTwo.typeface = Typeface.MONOSPACE
                                    inputTwo.setTextColor(android.graphics.Color.WHITE)
                                    inputBuilderTwo.setView(inputTwo)
                                    // input Two dlg ok
                                    inputBuilderTwo.setPositiveButton(
                                        requireContext().getString(R.string.ok),
                                        DialogInterface.OnClickListener { dialog, which ->
                                            var textTwo = inputTwo.text.toString()
                                            // check match with 1st pwd input
                                            if (textTwo.equals(textOne) == false) {
                                                centeredToast(requireContext(), requireContext().getString(R.string.pwd_no_match), 3000)
                                                return@OnClickListener
                                            }
                                            // copy final pwd to clipboard
                                            val clipboard = requireActivity().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText(getString(R.string.copiedText), textTwo)
                                            clipboard.setPrimaryClip(clip)
                                            centeredToast(requireActivity(), textTwo + " " + getString(R.string.copiedToClip), 50)
                                            // leave a "keep pwd" note
                                            okBox(
                                                context,
                                                getString(R.string.note),
                                                getString(R.string.keep_pwd),
                                                {
                                                    // set the new password by simply overriding the old/existing pwd
                                                    val appPwdClear = textTwo
                                                    try {
                                                        // encrypt pwd with app's public key from keystore
                                                        val keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
                                                        val appPwdPubHere = keyManager.encryptPwdPub(appPwdClear)
                                                        // save encrypted pwd in app preferences
                                                        val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
                                                        val spe = sharedPref.edit()
                                                        spe.putString("app_pwd", appPwdPubHere)
                                                        spe.apply()
                                                        // re-read encrypted pwd from app preferences
                                                        MainActivity.Companion.appPwdPub = sharedPref.getString("app_pwd", "")!!
                                                        // check consistency
                                                        if (MainActivity.Companion.appPwdPub.equals(appPwdPubHere) == false) {
                                                            throw Exception()
                                                        }
                                                        // reflect the encryption password change immediately to disk
                                                        val encryptProtectedFolders = sharedPref.getBoolean("encryptProtectedFolders", false)
                                                        if (encryptProtectedFolders) {
                                                            // write the always unencrypted MainActivity.ds encrypted to disk
                                                            writeAppData(appStoragePath, MainActivity.ds, appName, true)
                                                            // read encrypted data from disk to decrypted MainActivity.ds
                                                            MainActivity.ds = readAppData(appStoragePath)
                                                        } else {
                                                            // write the always unencrypted MainActivity.ds unencrypted to disk
                                                            writeAppData(appStoragePath, MainActivity.ds, appName, false)
                                                            // read unencrypted data from disk to unencrypted MainActivity.ds
                                                            MainActivity.ds = readAppData(appStoragePath)
                                                        }
                                                        // the recently set pwd could now be shown
                                                        showPwdPref!!.isEnabled = true
                                                        // Settings --> encrypt folders is not grayed out anymore
                                                        encryptPref!!.isEnabled = true
                                                        // update pwd summary
                                                        setPwd!!.setSummary(requireContext().getString(R.string.pwd_is_set))
                                                        // leave success note
                                                        okBox(
                                                            context,
                                                            getString(R.string.success),
                                                            getString(R.string.success_pwd_change),
                                                            {
                                                                if (sharedPref.getBoolean("BackupModeManually", false) == true) {
                                                                    // leave warning about outdated backups
                                                                    okBox(
                                                                        context,
                                                                        getString(R.string.note),
                                                                        getString(R.string.make_backup)
                                                                    )
                                                                } else {
                                                                    // only exec backup in 'backup auto mode'
                                                                    if (!createTxtBackup(requireContext(), downloadDir, ds)) {
                                                                        okBox(requireContext(), "Note", "GrzLog.txt backup failed")
                                                                    }
                                                                    gBScontext = requireContext()
                                                                    gBSsrcFolder = requireContext().getExternalFilesDir(null)!!.absolutePath
                                                                    gBSoutFolder = downloadDir
                                                                    gBSzipName = "$appName.zip"
                                                                    gBSmaxProgress = countFiles(File(gBSsrcFolder))
                                                                    // start BackupService, which prevents interrupting the backup
                                                                    actionOnBackupService(requireContext(), BackupService.Companion.Actions.START)
                                                                }
                                                                // show GDrive backup as outdated
                                                                val outdated = requireContext().getString(R.string.gdrive_outdated)
                                                                spe.putString("BackupUploadGDrive", outdated)
                                                                spe.apply()
                                                                val bakGdrive = findPreference("BackupToGDrive") as Preference?
                                                                bakGdrive!!.summary = requireContext().getString(R.string.clickHere) + " " + outdated
                                                            }
                                                        )
                                                    } catch (E: Exception) {
                                                        // in case the unknown happens
                                                        okBox(context, getString(R.string.note), getString(R.string.error_pwd_change))
                                                        val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
                                                        val spe = sharedPref.edit()
                                                        spe.putString("app_pwd", "")
                                                        spe.putBoolean("encryptProtectedFolders", false)
                                                        spe.apply()
                                                        // Settings --> encrypt folders is grayed out as long as there is no valid pwd
                                                        encryptPref!!.isEnabled = false
                                                        // update 2x summary
                                                        encryptPref!!.setSummary(getString(R.string.noEncrypt))
                                                        setPwd!!.setSummary(requireContext().getString(R.string.pwd_is_not_set))
                                                    }
                                                }
                                            )
                                    })
                                    // input Two dlg cancel
                                    inputBuilderTwo.setNegativeButton(
                                        R.string.cancel,
                                        DialogInterface.OnClickListener { dialog, which ->
                                            return@OnClickListener
                                    })
                                    val dlgTwo = inputBuilderTwo.create()
                                    dlgTwo.setOnShowListener {
                                        dlgTwo.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                                    }
                                    dlgTwo.show()
                                    dlgTwo.getWindow()?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                                    dlgTwo.setCanceledOnTouchOutside(false)
                                }
                            )
                            // input One dlg cancel
                            inputBuilderOne.setNegativeButton(
                                R.string.cancel,
                                DialogInterface.OnClickListener { dialog, which ->
                                    return@OnClickListener
                                }
                            )
                            // input One dlg help
                            inputBuilderOne.setNeutralButton(
                                getString(R.string.title_activity_help),
                                DialogInterface.OnClickListener { dialog, which ->
                                    okBoxHelpPwd(
                                        requireContext(),
                                        "Allowed chars",
                                        allowedStr,
                                        { // runner CANCEL -> restart input One dlg
                                            (inputOne.parent as? ViewGroup)?.removeView(inputOne)
                                            val dlg = inputBuilderOne.create()
                                            dlg.setOnShowListener {
                                                dlg.getWindow()!!.setLayout(
                                                    WindowManager.LayoutParams.MATCH_PARENT,
                                                    WindowManager.LayoutParams.WRAP_CONTENT
                                                )
                                            }
                                            dlg.show()
                                            dlg.getWindow()?.setLayout(
                                                WindowManager.LayoutParams.MATCH_PARENT,
                                                WindowManager.LayoutParams.WRAP_CONTENT
                                            )
                                            dlg.setCanceledOnTouchOutside(false)
                                            inputOne.requestFocus()
                                        },
                                        { // runner NEUTRAL -> generate random pwd + restart input One dlg
                                            // suggest a random pwd
                                            val keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
                                            val randomPwd = keyManager.generateRandomPassword(16)
                                            // copy pwd to clipboard
                                            val clipboard = requireActivity().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText(getString(R.string.copiedText), randomPwd)
                                            clipboard.setPrimaryClip(clip)
                                            centeredToast(requireActivity(), getString(R.string.copiedToClip), 50)
                                            // before restarting input One, view needs removed
                                            (inputOne.parent as? ViewGroup)?.removeView(inputOne)
                                            // fill in the random pwd
                                            inputOne.setText(randomPwd)
                                            val dlg = inputBuilderOne.create()
                                            dlg.setOnShowListener {
                                                dlg.getWindow()!!.setLayout(
                                                    WindowManager.LayoutParams.MATCH_PARENT,
                                                    WindowManager.LayoutParams.WRAP_CONTENT
                                                )
                                            }
                                            dlg.show()
                                            dlg.getWindow()?.setLayout(
                                                WindowManager.LayoutParams.MATCH_PARENT,
                                                WindowManager.LayoutParams.WRAP_CONTENT
                                            )
                                            dlg.setCanceledOnTouchOutside(false)
                                            inputOne.requestFocus()
                                        },
                                        { // runner OK -> show allowed chars + restart input One dlg
                                            (inputOne.parent as? ViewGroup)?.removeView(inputOne)
                                            val dlg = inputBuilderOne.create()
                                            dlg.setOnShowListener {
                                                dlg.getWindow()!!.setLayout(
                                                    WindowManager.LayoutParams.MATCH_PARENT,
                                                    WindowManager.LayoutParams.WRAP_CONTENT
                                                )
                                            }
                                            dlg.show()
                                            dlg.getWindow()?.setLayout(
                                                WindowManager.LayoutParams.MATCH_PARENT,
                                                WindowManager.LayoutParams.WRAP_CONTENT
                                            )
                                            dlg.setCanceledOnTouchOutside(false)
                                            inputOne.requestFocus()
                                        }
                                    )
                                }
                            )
                            val dlgOne = inputBuilderOne.create()
                            dlgOne.setOnShowListener {
                                dlgOne.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                            }
                            dlgOne.show()
                            dlgOne.getWindow()?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                            dlgOne.setCanceledOnTouchOutside(false)
                            inputOne.requestFocus()
                        }
                        // failure: usually there is no system auth available
                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            centeredToast(requireContext(), "Authentication failed.", 3000)
                        }
                    })
                // exec auth
                biometricPrompt.authenticate(promptInfo)
                true
            }

            // show last update check date
            val startCheckPref = findPreference("AppAtStartCheckUpdateFlag") as SwitchPreferenceCompat?
            if (BuildConfig.DEBUG) {
                var lastCheck = sharedPref.getString("AppAtStartCheckUpdateDate", "1900-01-01")
                if (lastCheck == "1900-01-01") {
                    lastCheck = "?"
                }
                // resetting last check date allows a fresh startup check after status change
                startCheckPref?.setSummary(getString(R.string.checked) + " " + lastCheck)
                startCheckPref?.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        // reset the last check date
                        val spe = sharedPref.edit()
                        spe.putString("AppAtStartCheckUpdateDate", "1900-01-01")
                        spe.apply()
                        var lastCheck = sharedPref.getString("AppAtStartCheckUpdateDate", "1900-01-01")
                        if (lastCheck == "1900-01-01") {
                            lastCheck = "?"
                        }
                        startCheckPref.setSummary(getString(R.string.checked) + " " + lastCheck)
                        true
                    }
            } else {
                startCheckPref?.isVisible = false
            }

            // action after Import GrzLog Backup from Google Drive: let it restore data
            var restFromGoogle = findPreference("RestoreFromGDrive") as Preference?
            restFromGoogle!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.setType("*/*")
                intent.setPackage("com.google.android.apps.docs")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // call continues in onActivityResult, where the received file is handled
                requireActivity().startActivityForResult(Intent.createChooser(intent, "Import Backup"), MainActivity.PICK.ZIP)
                true
            }

            // action after Export Backup to Google Drive: get GrzLog.zip from Download and upload it
            var bakToGoogle = findPreference("BackupToGDrive") as Preference?
            bakToGoogle!!.summary = getString(R.string.clickHere) + getString(R.string.last) + sharedPref.getString("BackupUploadGDrive", "- ?? -")
            bakToGoogle.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // only one upload allowed
                if (gdriveUploadOngoing) {
                    okBox(requireActivity(), getString(R.string.Note), "Upload is ongoing")
                    return@OnPreferenceClickListener true
                }
                // ask
                decisionBox(
                    requireContext(),
                    DECISION.YESNO,
                    getString(R.string.note),
                    getString(R.string.switchToGdriveApp),
                    {
                        val appName = appContext!!.applicationInfo.loadLabel(appContext!!.packageManager).toString()
                        var bakFile = File(downloadDir, "$appName.zip")
                        var intent = Intent(Intent.ACTION_SEND)
                        intent.setType("*/*")
                        val fileURI = FileProvider.getUriForFile(
                            MainActivity.contextMainActivity,
                            "com.grzwolf.grzlog.provider",
                            File(bakFile.absolutePath)
                        )
                        intent.setPackage("com.google.android.apps.docs")
                        intent.putExtra(Intent.EXTRA_STREAM, fileURI)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // lame parameter transfer to MeterService
                        gMSpreference = bakToGoogle
                        gMScontext = requireContext()
                        gMSfileLength = bakFile.length()
                        // ugly workaround to virtually increase file length by a fake amount of 10%:
                        //      total tx bytes are metered, but it's unknown, how much else is transmitted
                        gMSmaxProgress = bakFile.length() + (bakFile.length() * 0.1f).toLong()
                        // start upload meter service in onActivityResult to ensure selection is done
                        requireActivity().startActivityForResult(Intent.createChooser(intent, "Export Backup"), MainActivity.PICK.DUMMY)
                    },
                    null
                )
                true
            }

            // action check app update available, only DEBUG builds will come from github
            val updateLinkPref = findPreference("UpdateLink") as Preference?
            val checkUpdatePref = findPreference("AppCheckUpdate") as Preference?
            if (BuildConfig.DEBUG) {
                checkUpdatePref?.setSummary(R.string.clickHere)
                checkUpdatePref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    // internet connection state
                    if (!isNetworkAvailable(MainActivity.contextMainActivity)) {
                        Toast.makeText(requireContext(), "Internet error", Toast.LENGTH_LONG).show()
                    }
                    try {
                        updateLinkPref?.setTitle("")
                        var appVer = getString(R.string.tag_version).substring(1)
                        httpCheckForUpdate(
                            MainActivity.contextMainActivity,
                            getString(R.string.githubGrzLog),
                            checkUpdatePref,
                            getString(R.string.appCheckUpdate),
                            appVer,
                            updateLinkPref!!)
                    } catch (e: Exception) {
                        checkUpdatePref.setTitle(getString(R.string.appCheckUpdate) + " - " + getString(R.string.error))
                    }
                    true
                }
            } else {
                updateLinkPref?.isVisible = false
                checkUpdatePref?.isVisible = false
            }
            // unconditionally check for updates
            if (BuildConfig.DEBUG) {
                checkUpdatePref?.performClick()
            }

            // action execute app update
            val execUpdatePref = findPreference("ExecUpdate") as Preference?
            if (BuildConfig.DEBUG) {
                execUpdatePref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    // if an update was found, use the real update file
                    var autoUpdateLink = updateLinkPref!!.summary.toString()
                    var autoUpdateFile = autoUpdateLink.substringAfterLast("/")
                    var title = getString(R.string.grzlog_update)
                    var choiceNegative = getString(R.string.check_for_update)
                    if (autoUpdateLink.length > 0) {
                        title = getString(R.string.grzlog_update_available)
                        choiceNegative = getString(R.string.automatic_update_recommended)
                    }
                    // two choices + cancel
                    twoChoicesDialog(
                        requireContext(),
                        title,
                        getString(R.string.what_to_do),
                        getString(R.string.manual_update),
                        choiceNegative,
                        { // runner CANCEL
                            null
                        },
                        { // runner MANUAL
                            // ask for using browser to check the GH website containing GrzLog releases
                            decisionBox(
                                requireContext(),
                                DECISION.YESNO,
                                getString(R.string.note),
                                getString(R.string.openBrowserForUpdate),
                                {
                                    // provide 'how to update this app'
                                    decisionBox(
                                        requireContext(),
                                        DECISION.YESNO,
                                        getString(R.string.InstalledAPK) + " " + getString(R.string.tag_version),
                                        getString(R.string.howToUpdate),
                                        {
                                            var uri = Uri.parse(getString(R.string.githubGrzLog))
                                            val builder = CustomTabsIntent.Builder()
                                            val customTabsIntent = builder.build()
                                            customTabsIntent.launchUrl(requireContext(), uri)
                                        },
                                        null
                                    )
                                },
                                null
                            )
                        },
                        { // runner AUTOMATIC / CHECK AGAIN
                            if (autoUpdateLink.length > 0) {
                                // an APK file for download is available
                                var granted = context?.getPackageManager()?.canRequestPackageInstalls()
                                if (granted != null) {
                                    if (!granted) {
                                        // ask for permission
                                        startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse(String.format("package:%s", context?.getPackageName()))), 1234)
                                    } else {
                                        // ask for exec update
                                        decisionBox(
                                            requireContext(),
                                            DECISION.OKCANCEL,
                                            getString(R.string.note),
                                            getString(R.string.continue_with_app_update),
                                            {
                                                var downloadController = com.grzwolf.grzlog.DownloadController(requireContext(), autoUpdateLink, autoUpdateFile)
                                                downloadController.enqueueDownload()
                                            },
                                            null
                                        )
                                    }
                                }
                            } else {
                                // check again for update
                                checkUpdatePref?.performClick()
                            }
                        }
                    )
                    true
                }
            } else {
                execUpdatePref?.isVisible = false
            }

            // tricky fake buttons in preferences: https://stackoverflow.com/questions/2697233/how-to-add-a-button-to-preferencescreen
            // action after backup
            val backup = findPreference("Backup") as Preference?
            backup!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { // does it make sense
                    var dataAvailable = false
                    for (i in MainActivity.ds.dataSection.indices) {
                        if (MainActivity.ds.dataSection[i].length > 0) {
                            dataAvailable = true
                        }
                    }
                    if (!dataAvailable) {
                        Toast.makeText(
                            Companion.getActivity(),
                            R.string.itemsEmpty,
                            Toast.LENGTH_LONG
                        ).show()
                        return@OnPreferenceClickListener true
                    }
                    // ... are you sure ...
                    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                    builder.setTitle(R.string.backupData)
                    val appName = appContext!!.applicationInfo.loadLabel(appContext!!.packageManager).toString()
                    var file = File(downloadDir, "$appName.zip")
                    var message = getString(R.string.wantBackupData) + file.toString() + " + GrzLog.txt"
                    message += if (file.exists()) {
                        getString(R.string.overWrite)
                    } else {
                        getString(R.string.fileCreate)
                    }
                    builder.setMessage(message)
                    // YES
                    builder.setPositiveButton(
                        R.string.yes,
                        DialogInterface.OnClickListener { dialog, which ->
                            // backup DataStore to text file in Downloads as a measure of last resort after a data crash
                            createTxtBackup(appContext!!, downloadDir, MainActivity.ds)
                            // backup DataStore to text file in app folder: optimal for rel <--> dbg switches
                            //    1) make unencrypted backup
                            //    2) switch build variant --> 1st start won't find data, which is normal
                            //    3) import from file list the bak from 1)
                            //    4) readAppData(..) from GrzLog.ser doesn't wotk, which is normal due to serialisation diffs
                            //    5) readAppData((..) --> upgradeFromLegacy(..) works well with GrzLog.txt
                            createTxtBackup(appContext!!, MainActivity.appStoragePath, MainActivity.ds)
                            // ensure GrzLog.ser is up to date in terms of encrypted data
                            //    Q: Why make a copy of MainActivity.ds?
                            //    A: MainActivity.writeAppData encrypts data if set so,
                            //       its parameter is a reference --> do not modify MainActivity.ds !!
                            //       That's why a deep copy of MainActivity.ds is needed here.
                            var dsCopy: DataStore = DataStore()
                            MainActivity.ds.namesSection.forEach() {
                                dsCopy.namesSection.add(it)
                            }
                            MainActivity.ds.timeSection.forEach() {
                                dsCopy.timeSection.add(it)
                            }
                            MainActivity.ds.dataSection.forEach() {
                                dsCopy.dataSection.add(it)
                            }
                            dsCopy.selectedSection = MainActivity.ds.selectedSection
                            // usually MainActivity.writeAppData is followed by MainActivity.readAppData
                            //   and refresh lvMain.arrayList, this is not needed here
                            MainActivity.writeAppData(MainActivity.appStoragePath, dsCopy, MainActivity.appName)
                            // data export into the zip is the real backup
                            val appPath = appContext!!.getExternalFilesDir(null)!!.absolutePath
                            val maxProgressCount = countFiles(File(appPath))
                            // distinguish backup in foreground vs. background
                            if (!sharedPref.getBoolean("backupForeground", false)) {
                                // backup in foreground
                                generateBackupProgress(
                                    requireContext(),
                                    appPath,
                                    downloadDir,
                                    "$appName.zip",
                                    maxProgressCount,
                                    backupInfo,
                                    getString(R.string.lastBackup)
                                )
                            } else {
                                // backup in background
                                if (!MainActivity.backupOngoing) {
                                    // lame parameter transfer to BackupService
                                    gBScontext = requireContext()
                                    gBSsrcFolder = appPath
                                    gBSoutFolder = downloadDir
                                    gBSzipName = "$appName.zip"
                                    gBSmaxProgress = maxProgressCount
                                    // start BackupService, which prevents interrupting the backup
                                    actionOnBackupService(requireContext(), BackupService.Companion.Actions.START)
                                } else {
                                    centeredToast(MainActivity.contextMainActivity, "GrzLog silent backup ongoing", 3000)
                                }
                            }
                            // done
                            dialog.dismiss()
                            return@OnClickListener
                        })
                    // NO
                    builder.setNegativeButton(
                        R.string.no,
                        DialogInterface.OnClickListener { dialog, which ->
                            dialog.dismiss()
                            return@OnClickListener
                        })
                    val alert = builder.create()
                    alert.setOnShowListener {
                        alert.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    alert.show()
                    true
                }

            // action after selecting restore
            val restore = findPreference("Restore") as Preference?
            restore!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { // does it make sense
                    val appName = appContext!!.applicationInfo.loadLabel(
                        appContext!!.packageManager
                    ).toString()
                    var zipName: String
                    var bakFile = File(downloadDir, "$appName.zip")
                    if (!bakFile.exists()) {
                        zipName = "LogBookPro.zip"
                        bakFile = File(downloadDir, zipName)
                        if (!bakFile.exists()) {
                            zipName = "LogBook.zip"
                            bakFile = File(downloadDir, zipName)
                            if (!bakFile.exists()) {
                                zipName = "QuickLogBook.zip"
                                bakFile = File(downloadDir, zipName)
                                if (!bakFile.exists()) {
                                    Toast.makeText(
                                        Companion.getActivity(),
                                        R.string.noBackupExisting,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@OnPreferenceClickListener true
                                }
                            }
                        }
                    }
                    val zipFilePath = bakFile.absolutePath
                    // get file dates of existing app data file and the backup file
                    val lastModDateBak = Date(bakFile.lastModified())
                    val bakFileInfo = lastModDateBak.toString()
                    val storagePathApp = appContext!!.getExternalFilesDir(null)!!.absolutePath
                    val appFile = File(storagePathApp, "$appName.ser")
                    var appFileInfo = getString(R.string.not_existing)
                    if (appFile.exists()) {
                        val lastModDateApp = Date(appFile.lastModified())
                        appFileInfo = lastModDateApp.toString()
                    }
                    // ... are you sure ...
                    dlgRestoreFromBackupYouSure(context, zipFilePath, appFileInfo, bakFileInfo, "")
                    true
                }

            // action after restore from file list
            val restoreFromFileList = findPreference("RestoreFromFiles") as Preference?
            restoreFromFileList!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // ask
                decisionBox(
                    requireContext(),
                    DECISION.OKCANCEL,
                    getString(R.string.pickBak),
                    getString(R.string.pickExplain),
                    // runner positive
                    {
                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                        intent.type = "*/*"
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        // https://stackoverflow.com/questions/10564474/wrong-requestcode-in-onactivityresult getActivity().startActivityForResult
                        requireActivity().startActivityForResult(intent, MainActivity.PICK.ZIP)
                    },
                    // runner negative
                    null
                )
                true
            }

            // action after show app gallery by date
            val showAppGalleryByDate = findPreference("ShowAppGalleryByDate") as Preference?
            showAppGalleryByDate!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // release moreDialog, otherwise it would popup after search dlg close
                if (MainActivity.folderMoreDialog != null) {
                    MainActivity.folderMoreDialog?.let { it.dismiss() }
                    MainActivity.folderMoreDialog = null
                }
                // generate a controlling intent to return to Settings, bc. from here the start was ignited
                MainActivity.intentSettings = Intent(MainActivity.contextMainActivity, SettingsActivity::class.java)
                // show gallery: 2x activity == FragmentActivity to be able to return to here
                MainActivity.showAppGallery(activity as Context, activity as Activity, false, null, true)
                true
            }

            // action after show app gallery by file date
            val showAppGalleryBySize = findPreference("ShowAppGalleryBySize") as Preference?
            showAppGalleryBySize!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // release moreDialog, otherwise it would popup after search dlg close
                if (MainActivity.folderMoreDialog != null) {
                    MainActivity.folderMoreDialog?.let { it.dismiss() }
                    MainActivity.folderMoreDialog = null
                }
                // generate a controlling intent to return to Settings, bc. from here the start was ignited
                MainActivity.intentSettings = Intent(MainActivity.contextMainActivity, SettingsActivity::class.java)
                // show gallery: 2x activity == FragmentActivity to be able to return to here
                MainActivity.showAppGallery(activity as Context, activity as Activity, false, null, false)
                true
            }

            // action after tidy orphans from app gallery
            val tidyOrphanes = findPreference("TidyOrphanes") as Preference?
            tidyOrphanes!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val appName = appContext!!.applicationInfo.loadLabel(appContext!!.packageManager).toString()
                val appAttachmentsPath = AttachmentStorage.pathList[AttachmentStorage.activeType.ordinal]
                MainActivity.generateTidyOrphansProgress(this.context as Context, appAttachmentsPath, appName)
                true
            }

            // action after rescale images in app gallery
            val rescaleImages = findPreference("RescaleImages") as Preference?
            rescaleImages!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // special handling for images to offer a GrzLog global downscaling option
                try {
                    // two choices: yes, no, help
                    twoChoicesDialog(requireContext(),
                        getString(R.string.rescale),
                        getString(R.string.yes_is_recommended),
                        getString(R.string.yes),
                        getString(R.string.title_activity_help),
                        { // runner CANCEL
                        },
                        { // runner YES
                            if (execImageScaling()) {
                                okBox(requireContext(), getString(R.string.image_scaling_done), "")
                            } else {
                                okBox(requireContext(), getString(R.string.image_scaling_failed), "")
                            }
                        },
                        { // runner HELP
                            okBox(requireContext(), getString(R.string.note),
                                getString(R.string.grzlog_gallery_images_before_version))
                        }
                    )
                } catch(e: Exception) {}
                true
            }

            // action after show linked images in a LinkedMedia activity
            val showLinkedMedia = findPreference("ShowLinkedMedia") as Preference?
            showLinkedMedia!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    // release an existing moreDialog, otherwise it would popup after search dlg close
                    if (MainActivity.folderMoreDialog != null) {
                        MainActivity.folderMoreDialog?.let { it.dismiss() }
                        MainActivity.folderMoreDialog = null
                    }
                    MainActivity.returningFromAppGallery = false
                    // generate a controlling intent to return to Settings --> LinkedMedia, bc. from here the start was ignited
                    MainActivity.intentSettings = Intent(MainActivity.contextMainActivity, SettingsActivity::class.java)
                    // start LinkedMedia activity
                    val LinkedMediaIntent = Intent(context, LinkedMedia::class.java)
                    requireContext().startActivity(LinkedMediaIntent)
                } catch(e: Exception) {
                    okBox(requireContext(), "Ups", e.message.toString())
                }
                true
            }

            // GrzLog attachments storage location
            val asl = findPreference<ListPreference>("attachmentsLocation")
            // until v1.1.55 we only had the private folder --> so this has to be the default value
            // that means, as soon as >= v1.1.56 is 1st time started, all begins with GrzLog private folder
            asl!!.setValueIndex(AttachmentStorage.activeType.ordinal)
            asl.summary = asl.entry
            asl.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    var msg = ""
                    // get to know old value
                    val oldValue = asl.value
                    var oldValBool = false
                    var oldValNdx = -1
                    var activeTypeOld: AttachmentStorage.Type = AttachmentStorage.Type.PRIVATE
                    // parse change
                    var newValBool = false
                    if (newValue == "private") {
                        newValBool = true
                        oldValBool = false
                        oldValNdx = 1
                        asl.setValueIndex(0)
                        msg = getString(R.string.PicturesToAppGallery)
                        AttachmentStorage.activeType = AttachmentStorage.Type.PRIVATE
                        activeTypeOld = AttachmentStorage.Type.PUBLIC
                    }
                    if (newValue == "public") {
                        newValBool = false
                        oldValBool = true
                        oldValNdx = 0
                        asl.setValueIndex(1)
                        msg = getString(R.string.AppGalleryToPictures)
                        AttachmentStorage.activeType = AttachmentStorage.Type.PUBLIC
                        activeTypeOld = AttachmentStorage.Type.PRIVATE
                    }
                    // update summary
                    asl.summary = asl.entry
                    // update shared preference
                    val spe = sharedPref.edit()
                    spe.putString(AttachmentStorage::class.simpleName, AttachmentStorage.activeType.name)
                    spe.apply()
                    // if there was a change, apply the related action
                    if (oldValue != newValue) {
                        // ask
                        decisionBox(
                            requireContext(),
                            DECISION.YESNO,
                            getString(R.string.note),
                            msg,
                            // YES
                            {
                                if (AttachmentStorage.activeType == AttachmentStorage.Type.PRIVATE) {
                                    MainActivity.moveExternalPicturesGrzLogToPrivateGallery(requireContext())
                                }
                                if (AttachmentStorage.activeType == AttachmentStorage.Type.PUBLIC) {
                                    MainActivity.movePrivateGalleryToExternalPicturesGrzLog(requireContext())
                                }
                            },
                            // NO
                            {
                                // reset selected index
                                asl.setValueIndex(oldValNdx)
                                // update summary
                                asl.summary = asl.entry
                                // update shared preference
                                val spe = sharedPref.edit()
                                spe.putString(AttachmentStorage::class.simpleName, activeTypeOld.name)
                                spe.apply()
                            }
                        )
                    }
                    true
                }

            // action after reset shared preferences
            val reset = findPreference("Reset") as Preference?
            reset!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { // ... are you sure ...
                    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                    builder.setTitle("Reset")
                    builder.setMessage(R.string.resetSharedPrefs)
                    // YES
                    builder.setPositiveButton(
                        R.string.yes,
                        DialogInterface.OnClickListener { dialog, which ->
                            // keep the app's own password
                            var appPwdPub = sharedPref.getString("app_pwd", "")!!
                            // keep the app's storage mode & location
                            val attStoreModeName = sharedPref.getString(AttachmentStorage::class.simpleName, AttachmentStorage.Type.PRIVATE.name)
                            // clear all shared preferences
                            try {
                                val spe = sharedPref.edit()
                                spe.clear()
                                spe.apply()
                                // restore the app's own pwd
                                spe.putString("app_pwd", appPwdPub)
                                // restore the app's storage mode & location
                                spe.putString(AttachmentStorage::class.simpleName, attStoreModeName)
                                spe.apply()
                            } catch (e: Exception) {
                            }
                            // definitely need to re read all data from scratch
                            MainActivity.reReadAppFileData = true
                            dialog.dismiss()
                            return@OnClickListener
                        })
                    // NO
                    builder.setNegativeButton(
                        R.string.no,
                        DialogInterface.OnClickListener { dialog, which ->
                            dialog.dismiss()
                            return@OnClickListener
                        })
                    val alert = builder.create()
                    alert.setOnShowListener {
                        alert.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    alert.show()
                    true
                }

            // action after quit services
            val stopSrvPref = findPreference("StopServices") as Preference?
            if (MainActivity.backupOngoing || gdriveUploadOngoing) {
                stopSrvPref!!.isEnabled = true
            } else {
                stopSrvPref!!.isEnabled = false
            }
            stopSrvPref.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { // ... are you sure ...
                    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                    builder.setTitle(getString(R.string.quit_active_services) + " \'" + stopSrvPref.summary + "\'")
                    var msg = getString(R.string.really)
                    builder.setMessage(msg)
                    // YES
                    builder.setPositiveButton(
                        R.string.yes,
                        DialogInterface.OnClickListener { dialog, which ->
                            // stop backup service
                            if (MainActivity.backupOngoing) {
                                actionOnBackupService(requireContext(), BackupService.Companion.Actions.STOP)
                            }
                            // stop gdrive upload service
                            if (gdriveUploadOngoing) {
                                actionOnMeterService(requireContext(), MeterService.Companion.Actions.STOP)
                            }
                            dialog.dismiss()
                            return@OnClickListener
                        })
                    // NO
                    builder.setNegativeButton(
                        R.string.no,
                        DialogInterface.OnClickListener { dialog, which ->
                            dialog.dismiss()
                            return@OnClickListener
                        })
                    val alert = builder.create()
                    alert.setOnShowListener {
                        alert.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    alert.show()
                    true
                }

            // action after set temporary theme
            val brightDlgPref = findPreference("TempDayNight") as Preference?
            brightDlgPref!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    // temporary set some other theme to fix potential MagicOS update bug
                    // --> MainActivity will override such theme after being back there
                    val spe = sharedPref.edit()
                    spe.putString(getString(R.string.chosenTheme), "tmpdaynight")
                    spe.apply()
                    // restart settings activity to apply the theme
                    requireActivity().startActivity(Intent(context, SettingsActivity::class.java))
                    // close current settings activity
                    (context as Activity).finish()
                    true
                }
        }
    }

    //
    //
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            return
        }

        // call comes from "bakToGoogle!!.onPreferenceClickListener"
        if (requestCode == MainActivity.PICK.DUMMY) {
            // start MeterService for Backup Export to GDrive, prevents killing the MeterService
            actionOnMeterService(gMScontext, MeterService.Companion.Actions.START)
            // note about boring gdrive
            okBox(gMScontext, getString(R.string.Note), getString(R.string.gdrive))
            return
        }

        // GrzLog data restore from a previously picked zip file: a) local zip OR b) Google Drive zip file
        if (requestCode == MainActivity.PICK.ZIP) {
            val uri = data!!.data ?: return

            // get zip file name
            val zipFileName = getPath(this, uri)

            // copy zip to app is needed, destination folder is app /Import folder
            val appDir = appContext!!.applicationContext.getExternalFilesDir(null)!!.parentFile
            val appStoragePath = appDir?.absolutePath
            val storagePathAppImport = "$appStoragePath/Import"
            val fileName = zipFileName!!.substring(zipFileName.lastIndexOf("/"))
            val outputFilePath = storagePathAppImport + fileName
            // create /Import folder if needed
            val folder = File(storagePathAppImport)
            if (!folder.exists()) {
                folder.mkdirs()
            }

            // check filename compliance
            val possibleBakNames = "/GrzLog.zip::/LogBookPro.zip::/LogBook.zip::/QuickLogBook.zip"
            if (!possibleBakNames.contains(fileName)) {
                okBox(
                    this,
                    appContext!!.getString(R.string.Failure),
                    getString(R.string.noValidFilename)
                )
                return
            }

            // make sure to use the same pwd
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.note),
                getString(R.string.make_sure_same_pwd),
                // an alien backup zip is not allowed to open in >= API 30: make alien backup zip to an app local file
                { copyFileToAppImport(this, uri, outputFilePath) },
                null
            )
        }
    }

    companion object {
        var appContext: Context? = null
            private set
        private var settingsActivity: AppCompatActivity? = null
        fun getActivity(): Context? {
            return settingsActivity
        }

        // need val in static context
        lateinit var backupInfo: Preference

        // GDrive Backup upload
        var gdriveUploadOngoing = false

        // settings activity visibility status
        @JvmField
        var isSettingsActivityInForeground = false

        // create backup progress dialog
        fun generateBackupProgress(
            context: Context,
            srcFolder: String?,
            outFolder: String,
            zipName: String,
            maxProgressCount: Int,
            backupInfo: Preference?,
            infoText: String
        ) {
            // generate progress window
            var progressWindow = ProgressWindow(context, context.getString(R.string.backupData) )
            progressWindow.absCount = maxProgressCount.toFloat()
            // dialog dismiss listener
            fun Dialog?.setOnDismissListener(success: Boolean) {
                if (success) {
                    okBox(
                        context,
                        context.getString(R.string.ZIPcreated) + " = " + context.getString(R.string.success),
                        "$zipName + GrzLog.txt"
                    )
                    // update last backup summary
                    val file = File("$outFolder/$zipName")
                    if (file.exists()) {
                        val lastModDate = Date(file.lastModified())
                        backupInfo!!.summary = file.toString() + "\n" +
                                               lastModDate.toString() + "\n" +
                                               bytesToHumanReadableSize(file.length().toDouble())
                    } else {
                        backupInfo!!.summary = context.getString(R.string.noBackupExisting)
                    }
                } else {
                    okBox(
                        context,
                        context.getString(R.string.ZIPcreated) + " = " + context.getString(R.string.Failure),
                        context.getString(R.string.manualDelete),
                        {
                            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                            context.startActivity(intent)
                        }
                    )
                }
            }
            // generate ZIP async in another thread
            try {
                // GrzLog.zip might not be writable, if it is a backup from another phone
                val dst = File("$outFolder/$zipName")
                if (dst.exists() && !dst.canWrite()) {
                    okBox(
                        context,
                        context.getString(R.string.ZIPcreated) + " = " + context.getString(R.string.Failure),
                        context.getString(R.string.manualDelete),
                        {
                            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                            context.startActivity(intent)
                        }
                    )
                    return;
                }
                // show progress window
                progressWindow.show()
                // real work
                Thread {
                    try {
                        var success = progressWindow.let {
                            createZipArchive(context, srcFolder!!, outFolder, zipName, it, null, null, 0)
                        }
                        // jump back to UI
                        (context as Activity).runOnUiThread(Runnable {
                            success.let { progressWindow.dialog.setOnDismissListener(it) }
                            progressWindow.close()
                        })
                    } catch (e: Exception) {}
                }.start()
            } catch (e: Exception) {
                okBox(
                    context,
                    context.getString(R.string.Failure),
                    context.getString(R.string.ZIPcouldnotbecreated)
                )
                e.printStackTrace()
                progressWindow.dialog.setOnDismissListener(false)
                progressWindow.close()
            }
        }

        // start backup in a service
        fun actionOnBackupService(context: Context, action: BackupService.Companion.Actions) {
            var state = BackupService.Companion.getServiceState(context)
            // stop service
            if (state == BackupService.Companion.ServiceState.STARTED && action == BackupService.Companion.Actions.STOP) {
                Intent(context, BackupService::class.java).also {
                    it.action = action.name
                    context.stopService(it)
                }
                return
            }
            // start service
            if (action == BackupService.Companion.Actions.START) {
                Intent(context, BackupService::class.java).also {
                    it.action = action.name
                    context.startForegroundService(it)
                }
                return
            }
        }
        // lame parameter transfer to helper for BackupService
        lateinit var gBScontext: Context
        lateinit var gBSsrcFolder: String
        lateinit var gBSoutFolder: String
        lateinit var gBSzipName: String
        var gBSmaxProgress: Int = 0
        // helper for BackupService to run backup silently
        fun generateBackupSilent() {
            generateBackupSilent(
                gBScontext,
                gBSsrcFolder,
                gBSoutFolder,
                gBSzipName,
                gBSmaxProgress
            )
        }
        // run backup silently
        fun generateBackupSilent(context: Context,
                                 srcFolder: String?,
                                 outFolder: String,
                                 zipName: String,
                                 maxProgress: Int) {
            try {
                // GrzLog.zip might not be writable, if it is a backup from another phone
                val dst = File("$outFolder/$zipName")
                if (dst.exists() && !dst.canWrite()) {
                    // stop backup service
                    actionOnBackupService(context, BackupService.Companion.Actions.STOP)
                    // info
                    okBox(
                        context,
                        context.getString(R.string.ZIPcreated) + " = " + context.getString(R.string.Failure),
                        context.getString(R.string.manualDelete),
                        {
                            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                            context.startActivity(intent)
                        }
                    )
                    return;
                }
                // show progress in notification bar
                var notificationManager = NotificationManagerCompat.from(MainActivity.contextMainActivity)
                val channelId = "GrzLog"
                val intent = Intent(MainActivity.contextMainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent: PendingIntent = getActivity(context, 0, intent, FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("GrzLog: backup is ongoing")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(maxProgress, 0, true)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                with(notificationManager) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        centeredToast(MainActivity.contextMainActivity, "GrzLog backup: no progress bar available", 3000)
                    }
                    notify(1, notification.build())
                }
                // real work
                Thread {
                    MainActivity.backupOngoing = true
                    var success = createZipArchive(context, srcFolder!!, outFolder, zipName, null, notificationManager, notification, maxProgress)
                    MainActivity.backupOngoing = false
                    // jump back to UI
                    (context as Activity).runOnUiThread(Runnable {
                        // stop backup service
                        actionOnBackupService(context, BackupService.Companion.Actions.STOP)
                        // prepare & show notification
                        if (success) {
                            // success notification
                            notification.setContentTitle(context.getString(R.string.grzlog_silent_backup_success))
                                        .setContentText("")
                                        .setProgress(maxProgress, maxProgress, false)
                            if (MainActivity.appIsInForeground) {
                                centeredToast(MainActivity.contextMainActivity, context.getString(R.string.grzlog_silent_backup_success), 3000)
                            } else {
                                Toast.makeText(context, context.getString(R.string.grzlog_silent_backup_success), Toast.LENGTH_LONG)
                                    .show()
                            }
                            // update backup file info
                            if (isSettingsActivityInForeground) {
                                // show backup data info
                                var file = getBackupFile(appContext!!)
                                if (file != null) {
                                    val lastModDate = Date(file.lastModified())
                                    backupInfo.summary = file.toString() + context.getString(R.string.lastBackup) + lastModDate.toString() + "\""
                                } else {
                                    backupInfo.summary = context.getString(R.string.noBackupExisting)
                                }
                            }
                        } else {
                            // fail notification
                            notification.setContentTitle(context.getString(R.string.grzlog_backup_error))
                            notification.setContentText(context.getString(R.string.something_went_wrong))
                            if (MainActivity.appIsInForeground) {
                                centeredToast(MainActivity.contextMainActivity, context.getString(R.string.grzlog_backup_error), 10000)
                            } else {
                                Toast.makeText(context, context.getString(R.string.grzlog_backup_error), Toast.LENGTH_LONG)
                                    .show()
                            }
                        }
                        notificationManager.notify(1, notification.build())
                    })
                }.start()
            } catch (e: Exception) {
                MainActivity.backupOngoing = false
                if (MainActivity.appIsInForeground) {
                    centeredToast(MainActivity.contextMainActivity, "GrzLog backup error: " + e.message.toString(), 3000)
                } else {
                    Toast.makeText(context, "GrzLog backup error: " + e.message.toString(), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }

        // monitor ZIP upload to GDrive:
        // b/c it's unknown, what other network traffic exists, this metering might be totally wrong
        fun monitorZipUpload(
            context: Context,
            nm: NotificationManagerCompat?,
            n: NotificationCompat.Builder?,
            fileLength: Long,
            maxProgress: Long
        ): Boolean {
            // the actual speed meter
            val txMeter = SpeedMeter()
            txMeter.initMeter()
            // locals
            var txDeltaNow: Long = 0
            var txDeltaMax: Long = 0
            var txSoFar: Long = 0
            var txSoFarPrev: Long = 0
            var errorCounter = 0
            var errorNote = ""
            var doErrorCheck = true
            var threadSleepMs :Long = 5000
            // notification permission
            var permissionGranted = false
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true
            }
            // loop upload progress
            while (txSoFar < maxProgress) {
                // srv was forced stopped
                if (MeterService.Companion.getServiceState(context) == MeterService.Companion.ServiceState.STOPPED) {
                    // app cannot quit a gdrive upload, so let google do whatever it does without further notice
                    return false
                }
                // upload meter update
                txSoFar = txMeter.getTxNow()
                // if uploaded bytes exceed 90% of fileLength, don't check for errors anymore
                if (txSoFar >= fileLength * 0.9f) {
                    doErrorCheck = false
                }
                // if uploaded bytes >= 2 * fileLength, ASSUME the upload is done
                if (txSoFar >= 2 * fileLength) {
                    return true
                }
                // latest upload chunk
                txDeltaNow = txSoFar - txSoFarPrev
                // memorize upload speed
                if (txDeltaNow > txDeltaMax) {
                    txDeltaMax = txDeltaNow
                }
                // if upload speed goes low: error OR upload is done
                if (doErrorCheck) {
                    // detect a possible error situation: user might hava cancelled upload in gdrive app OR network error
                    // criterium: upload speed drops down to 10% of its previous max.
                    if (txDeltaNow < txDeltaMax * 0.1f) {
                        errorCounter++
                        errorNote = " errors: " + errorCounter + "(10)"
                        threadSleepMs = 10000 // increase sleep time
                    } else {
                        // if upload speed resumes to normal, fully reset error count
                        errorCounter = 0
                        // if there are no errors anymore, don't show them
                        errorNote = ""
                        // back to normal thread sleep time
                        threadSleepMs = 5000
                    }
                    // empirical value of 10 errors are allowed, then get off
                    if (errorCounter > 10) {
                        return false
                    }
                } else {
                    // if no error check: low upload may reasonably assert, the upload is done
                    if (txDeltaNow < txDeltaMax * 0.1f) {
                        return true
                    }
                }
                // memorize current upload chunk size as the previous one
                txSoFarPrev = txSoFar
                // set progress via nm in notification bar
                if (nm != null) {
                    with(nm) {
                        if (permissionGranted) {
                            (context as Activity).runOnUiThread(Runnable {
                                n!!.setContentText(bytesToHumanReadableSize(txSoFar.toDouble())
                                        + " (" + bytesToHumanReadableSize(maxProgress.toDouble()) + ")"
                                        + " @ " + bytesToHumanReadableSize((txDeltaNow / 5f).toDouble())
                                        + "/s"
                                        + errorNote)
                                    .setProgress((maxProgress / 1000).toInt(), (txSoFar / 1000).toInt(), false)
                                notify(1, n.build())
                            })
                        }
                    }
                }
                // relax
                Thread.sleep(threadSleepMs)
            }
            return true
        }
        // start upload meter in a service
        fun actionOnMeterService(context: Context, action: MeterService.Companion.Actions) {
            var state = MeterService.Companion.getServiceState(context)
            // stop service
            if (state == MeterService.Companion.ServiceState.STARTED && action == MeterService.Companion.Actions.STOP) {
                Intent(context, MeterService::class.java).also {
                    it.action = action.name
                    context.stopService(it)
                }
                return
            }
            // start service
            if (action == MeterService.Companion.Actions.START) {
                Intent(context, MeterService::class.java).also {
                    it.action = action.name
                    context.startForegroundService(it)
                }
                return
            }
        }
        // lame parameter transfer to helper for MeterService
        lateinit var gMScontext: Context
        var gMSpreference: Preference? = null
        var gMSfileLength: Long = 0
        var gMSmaxProgress: Long = 0
        // helper for MeterService to run upload meter silently
        fun generateMeterSilent() {
            generateMeterSilent(
                gMScontext,
                gMSpreference,
                gMSfileLength,
                gMSmaxProgress
            )
        }
        // run upload meter silently
        fun generateMeterSilent(context: Context, preference: Preference?, fileLength: Long, maxProgress: Long) {
            try {
                // show progress in notification bar
                var notificationManager = NotificationManagerCompat.from(MainActivity.contextMainActivity)
                val channelId = "GrzLog"
                val intent = Intent(MainActivity.contextMainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent: PendingIntent = getActivity(context, 0, intent, FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(context.getString(R.string.grzlog_zip_gdrive_upload_is_ongoing))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress((maxProgress / 1000).toInt(), 0, true)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                with(notificationManager) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        centeredToast(MainActivity.contextMainActivity, "GrzLog meter service: no progress bar available", 3000)
                    }
                    notify(1, notification.build())
                }
                // real work
                Thread {
                    // run the blocking long term task in a separate thread
                    gdriveUploadOngoing = true
                    var success = monitorZipUpload(context, notificationManager, notification, fileLength, maxProgress)
                    gdriveUploadOngoing = false
                    // jump back to UI as soon as monitorZipUpload is ready
                    (context as Activity).runOnUiThread(Runnable {
                        // stop meter service
                        actionOnMeterService(context, MeterService.Companion.Actions.STOP)
                        // memorize result in shared preferences
                        var prefString = ""
                        // distinguish
                        if (success) {
                            // prepare & show notification
                            notification.setContentTitle(context.getString(R.string.grzlog_silent_backup_success))
                                .setContentText("")
                                .setProgress((maxProgress / 1000).toInt(), (maxProgress / 1000).toInt(), false)
                            if (MainActivity.appIsInForeground) {
                                centeredToast(
                                    MainActivity.contextMainActivity,
                                    context.getString(R.string.grzlog_upload_success),
                                    3000
                                )
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.grzlog_silent_backup_success),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            prefString = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now())
                        } else {
                            // fail notification
                            Toast.makeText(
                                context,
                                context.getString(R.string.grzlog_upload_error),
                                Toast.LENGTH_LONG
                            ).show()
                            if (MainActivity.appIsInForeground) {
                                okBox(
                                    MainActivity.contextMainActivity,
                                    context.getString(R.string.Note),
                                    context.getString(R.string.grzlog_upload_error)
                                )
                            }
                            if (SettingsActivity.isSettingsActivityInForeground) {
                                okBox(
                                    SettingsActivity.appContext,
                                    context.getString(R.string.Note),
                                    context.getString(R.string.grzlog_upload_error)
                                )
                            }
                            prefString = context.getString(R.string.upload_error)
                        }
                        // memorize result in a shared preference
                        val sharedPref = PreferenceManager.getDefaultSharedPreferences(appContext!!)
                        val spe = sharedPref.edit()
                        spe.putString("BackupUploadGDrive", prefString)
                        spe.apply()
                        // show result in provided preference
                        preference!!.summary = context.getString(R.string.clickHere) + context.getString(R.string.last) + prefString
                        // notification
                        notificationManager.notify(1, notification.build())
                    })
                }.start()
            } catch (e: Exception) {
                if (MainActivity.appIsInForeground) {
                    centeredToast(MainActivity.contextMainActivity, "GrzLog upload meter error: " + e.message.toString(), 3000)
                } else {
                    Toast.makeText(context, "GrzLog upload meter error: " + e.message.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }

        // create restore progress dialog
        fun generateRestoreProgress(
            context: Context,
            outPath: String,
            zipFilePath: String?,
            baseAppName: String?,
            maxProgressCount: Int,
            fileToDelete: String
        ) {

            // show progress window
            var progressWindow = ProgressWindow(context, context.getString(R.string.restoreZIP) )
            progressWindow.absCount = maxProgressCount.toFloat()
            progressWindow.show()
            
            // remember attachment storage location mode before Restore:
            // --> Restore will change it back to Type.PRIVATE
            //     reason: Backup keeps all attachments in one place, which is private .../Images
            var changedAttatchmentStoreLocation = false
            if (AttachmentStorage.activeType == AttachmentStorage.Type.PUBLIC) {
                changedAttatchmentStoreLocation = true
            }

            // dialog dismiss listener
            fun Dialog?.setOnDismissListener(success: Boolean) {
                // GrzLog gallery might have changed, so let it re read at next usage
                MainActivity.appGalleryAdapter = null
                // two possible outcomes
                if (success) {
                    // in case of success, delete temporary BAK folder
                    val bakFolder = File(outPath + "Bak")
                    deleteRecursive(bakFolder)
                    // msg
                    okBox(
                        context,
                        context.getString(R.string.ZIPrestored) + " = " + context.getString(R.string.success),
                        context.getString(R.string.returnToMain),
                        {
                            if (changedAttatchmentStoreLocation) {
                                // note: change attachments storage
                                okBox(
                                    context,
                                    context.getString(R.string.note),
                                    context.getString(R.string.restore_changed_attachments_storage),
                                    { settingsActivity!!.onBackPressed() }
                                )
                            } else {
                                // nothing else to do
                                settingsActivity!!.onBackPressed()
                            }    
                        }
                    )
                } else {
                    // if anything went wrong, delete the folder, which failed during restore
                    val oriFolder = File(outPath)
                    deleteRecursive(oriFolder)
                    // then rename the temporary BAK folder back to ORI
                    val bakFolder = File(outPath + "Bak")
                    bakFolder.renameTo(oriFolder)
                    // If 'file not found' (permission denied), it could have happened, that a foreign zip is processed.
                    // This is not allowed anymore in >= Android 11 and usage of SAF is needed.
                    // 'Allow management of all files' collides with PlayStore compliance rules for normal apps
                    okBox(
                        context,
                        context.getString(R.string.ZIPcouldnotberestored) + " = " + context.getString(
                            R.string.Failure
                        ),
                        context.getString(R.string.alienFile)
                    )
                }
                // delete local backup zip made from /Download zip backup
                if (fileToDelete.length != 0) {
                    val file = File(fileToDelete)
                    file.delete()
                }
            }

            //
            // temporarily rename outPath folder to have a fallback, if restore fails
            //
            val oriFolder = File(outPath)
            val bakFolder = File(outPath + "Bak")
            if (bakFolder.exists()) {
                deleteRecursive(bakFolder)
                if (bakFolder.exists()) {
                    progressWindow.close()
                    okBox(
                        context,
                        context.getString(R.string.Failure),
                        "cold not delete older bak folder"
                    )
                    return
                }
            }
            val renSuccess = oriFolder.renameTo(bakFolder)
            if (!renSuccess) {
                progressWindow.close()
                okBox(
                    context,
                    context.getString(R.string.Failure),
                    context.getString(R.string.errorRenameBakFolder)
                )
                return
            }
            // since oriFolder was renamed with ending BAK, it still has to exist
            if (!oriFolder.exists()) {
                if (!oriFolder.mkdir()) {
                    progressWindow.close()
                    okBox(
                        context,
                        context.getString(R.string.Failure),
                        context.getString(R.string.errorCreateInternalFolder)
                    )
                    return
                }
            }
            // restore ZIP async in another thread
            Thread {
                try {
                    var success = progressWindow.let {
                        unpackZipArchive(context, outPath, zipFilePath, it)
                    }
                    // jump back to UI
                    (context as Activity).runOnUiThread(Runnable {
                        success.let { progressWindow.dialog.setOnDismissListener(it) }
                        progressWindow.close()
                    })
                } catch (e: Exception) {
                    (context as Activity).runOnUiThread(Runnable {
                        e.printStackTrace()
                        progressWindow.dialog.setOnDismissListener(false)
                        progressWindow.close()
                    })
                }
            }.start()
        }

        // copy a file to GrzLog Import folder and return full filename: allows to import alien bak from Downloads
        fun copyFileToAppImport(context: Context, inputUri: Uri?, outputFilePath: String) {

            // show progress window
            var progressWindow = ProgressWindow(context, context.getString(R.string.copyZIP) )
            progressWindow.show()

            // dialog dismiss listener
            fun Dialog?.setOnDismissListener(success: Boolean) {
                if (success) {
                    // start restore process
                    dlgRestoreFromBackupYouSure(context, outputFilePath, " ", " ", outputFilePath)
                } else {
                    // If 'file not found' (permission denied), it could have happened, that an alien zip is processed.
                    // This is not allowed anymore in >= Android 11 and usage of SAF is needed
                    // >= Android 11: 'Allow management of all files' is against PlayStore compliance rules for 'normal' apps
                    okBox(
                        context,
                        context.getString(R.string.ZIPcouldNotBeCopied) + " = " + context.getString(
                            R.string.Failure
                        ),
                        context.getString(R.string.alienFile)
                    )
                    // try to delete the file in /Import
                    try {
                        val file = File(outputFilePath)
                        file.delete()
                    } catch (e: Exception) {
                    }
                }
            }

            // let run the lengthy task in an external thread
            Thread {
                var success = false
                try {
                    // alien file is not allowed to open in >= API 30, but works with getContentResolver().openInputStream(inputUri);
                    val inputStream = context.contentResolver.openInputStream(inputUri!!)
                    // progress based on file size
                    val bufferSize = 8 * 1024
                    val absProgressCount = inputStream!!.available() / bufferSize
                    progressWindow.absCount = absProgressCount.toFloat()
                    val outputStream = FileOutputStream(outputFilePath)
                    var read: Int
                    val buffers = ByteArray(bufferSize)
                    var ndxBufRead = 0
                    while (inputStream.read(buffers).also { read = it } != -1) {
                        // write
                        outputStream.write(buffers, 0, read)
                        // set progress
                        (context as Activity).runOnUiThread(Runnable {
                            progressWindow.incCount = ++ndxBufRead
                        })
                    }
                    inputStream.close()
                    outputStream.close()
                    success = true
                } catch (e: Exception) {
                    success = false
                }
                // jump back to UI
                (context as Activity).runOnUiThread(Runnable {
                    progressWindow.dialog.setOnDismissListener(success)
                    progressWindow.close()
                })
            }.start()
        }


        // restore from backup you sure
        fun dlgRestoreFromBackupYouSure(
            context: Context?,
            selectedFileName: String,
            appFileInfo: String,
            bakFileInfo: String,
            fileToDelete: String
        ) {
            val adYesNo = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            adYesNo.setTitle(context!!.getString(R.string.restoreFromBackup))
            adYesNo.setMessage(
                context.getString(R.string.restoreGrzLogData) +
                        appFileInfo +
                        context.getString(R.string.fromBackup) +
                        " " + selectedFileName +
                        " " + bakFileInfo +
                        context.getString(R.string.noUndo)
            )
            adYesNo.setPositiveButton(R.string.yes) { dialog, which ->
                // signal to re read complete app file data in MainActivity onCreate
                MainActivity.reReadAppFileData = true
                // signal to NOT generate the simple txt-backup in MainActivity onPause
                MainActivity.returningFromRestore = true
                // restore ini & images from zip backup
                val file = File(selectedFileName)
                var maxProgressCount = 100
                if (file.exists()) {
                    try {
                        val zipFile = ZipFile(file)
                        maxProgressCount = countZipFiles(zipFile)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    val appNameBase = appContext!!.applicationInfo.loadLabel(
                        context.packageManager
                    ).toString()
                    val appPath = appContext!!.getExternalFilesDir(null)!!
                        .absolutePath
                    generateRestoreProgress(
                        context,
                        appPath,
                        selectedFileName,
                        appNameBase,
                        maxProgressCount,
                        fileToDelete
                    )
                } else {
                    Toast.makeText(getActivity(), R.string.backupDisappeared, Toast.LENGTH_LONG)
                        .show()
                }
            }
            adYesNo.setNegativeButton(R.string.no) { dialog, which ->
                // delete local backup zip in /Import made from a /Download backup
                if (fileToDelete.length != 0) {
                    val file = File(fileToDelete)
                    file.delete()
                }
                dialog.cancel()
            }
            val alert = adYesNo.create()
            alert.setOnShowListener {
                alert.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            alert.show()
            alert.setCanceledOnTouchOutside(false)
        }
    }
}

