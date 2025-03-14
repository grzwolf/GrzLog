package com.grzwolf.grzlog

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.getActivity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.Settings
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.grzwolf.grzlog.FileUtils.Companion.getPath
import com.grzwolf.grzlog.MainActivity.Companion.appIsInForeground
import com.grzwolf.grzlog.MainActivity.Companion.lvMain
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.regex.Pattern
import java.util.zip.ZipFile


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
        if (s == "newAtBottom") {
            MainActivity.reReadAppFileData = true
        }
        if (s == "darkMode") {
            MainActivity.reReadAppFileData = true
        }
        if (s == "AppAtStartCheckUpdateFlag") {
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
        if (sharedPref.getBoolean("darkMode", false)) {
            setTheme(R.style.ThemeOverlay_AppCompat_Dark)
        } else {
            setTheme(R.style.ThemeOverlay_AppCompat_Light)
        }
        super.onCreate(savedInstanceState)

        // life cycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // make volatile data static
        appContext = applicationContext
        activity = this
        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

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
            super.onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    // Android back button detection
    override fun onBackPressed() {
        // release controlling intent, if Settings are left
        MainActivity.intentSettings = null
        super.onBackPressed()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // http request to communicate with a website
        fun httpCheckForUpdate(context: Context, url: String, updateCheckPref: Preference, updateCheckTitle: String, appVer: String, updateLinkPref: Preference ) {
            val queue = Volley.newRequestQueue(context)
            val stringRequest = StringRequest(Request.Method.GET, url,
                object : Response.Listener<String?> {
                    override fun onResponse(response: String?) {
                        if (response != null) {
                            var listTags: MutableList<String> = ArrayList()
                            val m = Pattern.compile("tag/v\\d+.\\d+.\\d+").matcher(response!!)
                            while (m?.find() == true) {
                                var group = m?.group()!!
                                if (group.startsWith("tag/v")) {
                                    var result = m?.group()!!.substring(5)
                                    if (!listTags.contains(result!!)) {
                                        listTags.add(result!!)
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

        @Suppress("unused")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(appContext!!)
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

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
                    backupInfo!!.summary = file.toString() + getString(R.string.lastBackup) + lastModDate.toString() + "\""
                } else {
                    backupInfo!!.summary = getString(R.string.noBackupExisting)
                }
            } catch (ise: IllegalStateException) {
                centeredToast(appContext!!, "Info error: " + ise.message.toString(), 3000)
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

            // action after show GrzLog limitations
            showHelp = findPreference("AppLimitations") as Preference?
            showHelp!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    requireActivity().startActivity(Intent(context, LimitationsActivity::class.java))
                } catch (e: Exception) {
                    centeredToast(appContext!!, "Limitations error: " + e.message.toString(), 3000)
                }
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
                        startCheckPref?.setSummary(getString(R.string.checked) + " " + lastCheck)
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
            bakToGoogle!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
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
                        gMScontext = requireContext()
                        gMSsrcFolder = "appPath"
                        gMSoutFolder = downloadDir
                        gMSzipName = "$appName.zip"
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

            // action after check Google Drive
            var checkGDrive = findPreference("CheckGDrive") as Preference?
            checkGDrive!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.setType("*/*")
                intent.setPackage("com.google.android.apps.docs")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "Check GDrive"))
                true
            }

            // action check app update available
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
                            checkUpdatePref!!,
                            getString(R.string.appCheckUpdate),
                            appVer,
                            updateLinkPref!!)
                    } catch (e: Exception) {
                        checkUpdatePref?.setTitle(getString(R.string.appCheckUpdate) + " - " + getString(R.string.error))
                    }
                    true
                }
            } else {
                updateLinkPref?.isVisible = false
                checkUpdatePref?.isVisible = false
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
                    if (autoUpdateLink!!.length > 0) {
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
                            if (autoUpdateLink!!.length > 0) {
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

            //
            if (BuildConfig.DEBUG) {
                checkUpdatePref?.performClick()
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
                            // data export into the zip is the real backup
                            val appPath = appContext!!.getExternalFilesDir(null)!!.absolutePath
                            val maxProgressCount = countFiles(File(appPath))
                            // distinguish backup in foreground vs. background
                            if (sharedPref.getBoolean("backupForeground", false)) {
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
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "*/*"
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                // https://stackoverflow.com/questions/10564474/wrong-requestcode-in-onactivityresult getActivity().startActivityForResult
                requireActivity().startActivityForResult(intent, MainActivity.PICK.ZIP)
                true
            }

            // action after show app gallery
            val showAppGallery = findPreference("ShowAppGallery") as Preference?
            showAppGallery!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                // release moreDialog, otherwise it would popup after search dlg close
                if (MainActivity.folderMoreDialog != null) {
                    MainActivity.folderMoreDialog?.let { it.dismiss() }
                    MainActivity.folderMoreDialog = null
                }
                // generate a controlling intent to return to Settings, bc. from there the usages search was ignited
                MainActivity.intentSettings = Intent(MainActivity.contextMainActivity, SettingsActivity::class.java)
                // show gallery
                MainActivity.showAppGallery(activity as Context, activity as Activity)
                true
            }

            // action after tidy orphans from app gallery
            val tidyOrphanes = findPreference("TidyOrphanes") as Preference?
            tidyOrphanes!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val appName = appContext!!.applicationInfo.loadLabel(appContext!!.packageManager).toString()
                val appAttachmentsPath = appContext!!.getExternalFilesDir(null)!!.absolutePath + "/Images"
                MainActivity.tidyOrphanedFiles(this.context as Context, appAttachmentsPath, appName)
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
                            // clear all shared preferences
                            try {
                                val spe = sharedPref.edit()
                                spe.clear()
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
                    alert.show()
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

            // an alien backup zip is not allowed to open in >= API 30: make alien backup zip to an app local file
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.note),
                getString(R.string.copyFileToApp),
                { copyFileToAppImport(this, uri, outputFilePath) },
                null
            )
        }
    }

    companion object {
        var appContext: Context? = null
            private set
        private var activity: AppCompatActivity? = null
        fun getActivity(): Context? {
            return activity
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
                    // update last backup time stamp
                    val file = File("$outFolder/$zipName")
                    if (file.exists()) {
                        val lastModDate = Date(file.lastModified())
                        backupInfo!!.summary = file.toString() + infoText + lastModDate.toString()
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
                    var success = progressWindow.let {
                        createZipArchive(context, srcFolder!!, outFolder, zipName, it, null, null, 0)
                    }
                    // jump back to UI
                    (context as Activity).runOnUiThread(Runnable {
                        success.let { progressWindow.dialog.setOnDismissListener(it) }
                        progressWindow.close()
                    })
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
                val channelId = "GrzLog" as String
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
                                    backupInfo!!.summary = file.toString() + context.getString(R.string.lastBackup) + lastModDate.toString() + "\""
                                } else {
                                    backupInfo!!.summary = context.getString(R.string.noBackupExisting)
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

        // monitor ZIP upload to GDrive
        fun monitorZipUpload(
            context: Context,
            srcFolder: String,
            outFolder: String,
            zipName: String,
            pw: ProgressWindow?,
            nm: NotificationManagerCompat?,
            n: NotificationCompat.Builder?,
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
            // permissions
            var permissionGranted = false
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true
            }
            // loop upload progress
            while (txSoFar < maxProgress) {
                // upload progress update
                txSoFar = txMeter.getTxNow()
                // do some stats
                txDeltaNow = txSoFar - txSoFarPrev
                if (txDeltaNow > txDeltaMax) {
                    txDeltaMax = txDeltaNow
                }
                // detect a possible error situation: user might hava cancelled upload in gdrive app OR network error
                if (txDeltaNow < txDeltaMax * 0.1f) {
                    errorCounter++
                    errorNote = " errors: " + errorCounter + "(10)"
                } else {
                    if (errorCounter > 0) {
                        errorCounter--
                    } else {
                        errorNote = ""
                    }
                }
                if (errorCounter > 10) {
                    return false
                }
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
                Thread.sleep(5000)
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
        lateinit var gMSsrcFolder: String
        lateinit var gMSoutFolder: String
        lateinit var gMSzipName: String
        var gMSmaxProgress: Long = 0
        // helper for MeterService to run upload meter silently
        fun generateMeterSilent() {
            generateMeterSilent(
                gMScontext,
                gMSsrcFolder,
                gMSoutFolder,
                gMSzipName,
                gMSmaxProgress
            )
        }
        // run upload meter silently
        fun generateMeterSilent(context: Context,
                                 srcFolder: String?,
                                 outFolder: String,
                                 zipName: String,
                                 maxProgress: Long) {
            try {
                // show progress in notification bar
                var notificationManager = NotificationManagerCompat.from(MainActivity.contextMainActivity)
                val channelId = "GrzLog" as String
                val intent = Intent(MainActivity.contextMainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent: PendingIntent = getActivity(context, 0, intent, FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("GrzLog: GDrive upload is ongoing")
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
                    var success = monitorZipUpload(context, srcFolder!!, outFolder, zipName, null, notificationManager, notification, maxProgress)
                    gdriveUploadOngoing = false
                    // jump back to UI as soon as monitorZipUpload is ready
                    (context as Activity).runOnUiThread(Runnable {
                        // stop meter service
                        actionOnMeterService(context, MeterService.Companion.Actions.STOP)
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
                        }
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

            // dialog dismiss listener
            fun Dialog?.setOnDismissListener(success: Boolean) {
                if (success) {
                    // in case of success, delete temporary BAK folder
                    val bakFolder = File(outPath + "Bak")
                    deleteRecursive(bakFolder)
                    // msg
                    okBox(
                        context,
                        context.getString(R.string.ZIPrestored) + " = " + context.getString(R.string.success),
                        ""
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
            try {
                Thread {
                    var success = progressWindow.let {
                        unpackZipArchive(context, outPath, zipFilePath, it)
                    }
                    // jump back to UI
                    (context as Activity).runOnUiThread(Runnable {
                        success.let { progressWindow.dialog.setOnDismissListener(it) }
                        progressWindow.close()
                    })
               }.start()
            } catch (e: Exception) {
                e.printStackTrace()
                progressWindow.dialog.setOnDismissListener(false)
                progressWindow.close()
            }
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
            adYesNo.setPositiveButton(R.string.yes) { dialog, which -> // signal to re read complete app file data in MainActivity onCreate
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
            adYesNo.setNegativeButton(R.string.no) { dialog, which -> // delete local backup zip in /Import made from a /Download backup
                if (fileToDelete.length != 0) {
                    val file = File(fileToDelete)
                    file.delete()
                }
                dialog.cancel()
            }
            val alert = adYesNo.create()
            alert.show()
            alert.setCanceledOnTouchOutside(false)
        }
    }
}

