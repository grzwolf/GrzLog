package com.grzwolf.grzlog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import java.io.*
import java.util.*
import java.util.zip.ZipFile

import com.grzwolf.grzlog.FileUtils.Companion.getPath


class SettingsActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {
    // listener for any pref change: https://stackoverflow.com/questions/2542938/sharedpreferences-onsharedpreferencechangelistener-not-being-called-consistently
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, s: String) {
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
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Settings menu top left back button detection
        if (item.itemId == android.R.id.home) {
            // we mimic the same behaviour, as if the Android back button were clicked
            super.onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    // Android back button detection
    override fun onBackPressed() {
        super.onBackPressed()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        @Suppress("unused")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            // show backup data info
            // default is GrzLog.zip but QuickLogBook.zip, LogBook.zip, LogBookPro.zip are allowed for backward compatibility
            val backupInfo = findPreference("BackupInfo") as Preference?
            val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var appZipName = appContext!!.applicationInfo.loadLabel(appContext!!.packageManager
            ).toString() + ".zip"
            var file = File(downloadDir, appZipName)
            if (file.exists()) {
                val lastModDate = Date(file.lastModified())
                backupInfo!!.summary =
                    file.toString() + getString(R.string.lastBackup) + lastModDate.toString()
            } else {
                appZipName = "LogBookPro.zip"
                file = File(downloadDir, appZipName)
                if (file.exists()) {
                    val lastModDate = Date(file.lastModified())
                    backupInfo!!.summary =
                        file.toString() + getString(R.string.lastBackup) + lastModDate.toString()
                } else {
                    appZipName = "LogBook.zip"
                    file = File(downloadDir, appZipName)
                    if (file.exists()) {
                        val lastModDate = Date(file.lastModified())
                        backupInfo!!.summary =
                            file.toString() + getString(R.string.lastBackup) + lastModDate.toString()
                    } else {
                        appZipName = "QuickLogBook.zip"
                        file = File(downloadDir, appZipName)
                        if (file.exists()) {
                            val lastModDate = Date(file.lastModified())
                            backupInfo!!.summary =
                                file.toString() + getString(R.string.lastBackup) + lastModDate.toString()
                        } else {
                            backupInfo!!.summary = getString(R.string.noBackup)
                        }
                    }
                }
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

            // tricky fake buttons in preferences: https://stackoverflow.com/questions/2697233/how-to-add-a-button-to-preferencescreen
            // action after backup
            val backup = findPreference("Backup") as Preference?
            backup!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { // does it make sense
                    var dataAvailable = false
                    for (i in MainActivity.ds!!.dataSection.indices) {
                        if (MainActivity.ds!!.dataSection[i].length > 0) {
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
//                    val downloadDir =
//                        "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val appName = appContext!!.applicationInfo.loadLabel(appContext!!.packageManager).toString()
                    file = File(downloadDir, "$appName.zip")
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
                            // data export into the zip is the real backup, which is done in background
                            val appPath = appContext!!.getExternalFilesDir(null)!!.absolutePath
                            val maxProgressCount = countFiles(File(appPath))
                            generateBackupProgress(
                                requireContext(),
                                appPath,
                                downloadDir,
                                "$appName.zip",
                                maxProgressCount,
                                backupInfo,
                                getString(R.string.lastBackup)
                            )
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
//                    val downloadDir =
//                        "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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
                MainActivity.showAppGallery(activity as Context, activity as Activity, null)
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
                    val builder =
                        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                    builder.setTitle("Reset")
                    builder.setMessage(R.string.resetSharedPrefs)
                    // YES
                    builder.setPositiveButton(
                        R.string.yes,
                        DialogInterface.OnClickListener { dialog, which -> // clear all shared preferences
                            val sharedPref = PreferenceManager.getDefaultSharedPreferences(
                                appContext!!
                            )
                            val spe = sharedPref.edit()
                            spe.clear()
                            spe.apply()
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

        // execute a restore from a file list
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
            // show progress window
            var progressWindow = ProgressWindow(context, context.getString(R.string.backupData) )
            progressWindow.absCount = maxProgressCount.toFloat()
            progressWindow.show()

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
                        context.getString(R.string.manualDelete)
                    )
                }
            }

            // generate ZIP async in another thread
            try {
                Thread {
                    var success = progressWindow.let {
                        createZipArchive(context, srcFolder!!, outFolder, zipName, it)
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
                    Log.e("Exception", e.message!!)
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

