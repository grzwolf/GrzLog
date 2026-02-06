package com.grzwolf.grzlog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentResolver
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.WindowMetrics
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.grzwolf.grzlog.DataStore.TIMESTAMP
import com.grzwolf.grzlog.MainActivity.Companion.AttachmentStorage
import com.grzwolf.grzlog.MainActivity.Companion.appPwdPub
import com.grzwolf.grzlog.MainActivity.Companion.contextMainActivity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.fileSize


// import a complete ZIP containing data + attached files into app's files folder
fun unpackZipArchive(
    context: Context,
    outPath: String,
    zipFilePath: String?,
    pw: ProgressWindow
): Boolean {
    var retVal = true
    val fis: InputStream
    val zis: ZipInputStream
    try {
        // for the sake of mind
        val outFolder = File(outPath)
        if (!outFolder.exists()) {
            if (!outFolder.mkdir()) {
                return false
            }
        }
        var filename: String
        fis = FileInputStream(zipFilePath)
        zis = ZipInputStream(BufferedInputStream(fis))
        var ze: ZipEntry?
        val buffer = ByteArray(1024)
        var count: Int
        while (zis.nextEntry.also { ze = it } != null) {
            // because ze.isDirectory() is unreliable (Images is always true, Pictures is not detected), another directory trigger is needed
            val zeGetName = ze?.name
            if (zeGetName?.contains("/") == true) {
                val parts = zeGetName.split("/".toRegex()).toTypedArray()
                val dir = File(outPath, parts[0])
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            filename = ze?.name.toString()
            // set progress
            (context as Activity).runOnUiThread(Runnable {
                pw.incCount++
            })
            // extract
            var fout: FileOutputStream?
            try {
                fout = FileOutputStream("$outPath/$filename")
                while (zis.read(buffer).also { count = it } != -1) {
                    fout.write(buffer, 0, count)
                }
                fout.close()
                retVal = true
                val fi = File("$outPath/$filename")
                ze?.let {
                    fi.setLastModified(it.time)
                }
            } catch (e: IOException) {
                e.printStackTrace()
                retVal = false
            } finally {
                zis.closeEntry()
            }
        }
        zis.close()
    } catch (e: IOException) {
        e.printStackTrace()
        return false
    } catch (e: NullPointerException) {
        e.printStackTrace()
        return false
    }

    // in case attachments are so far set to be stored in public folder --> handle it
    if (AttachmentStorage.activeType == AttachmentStorage.Type.PUBLIC) {
        try {
            // clear public attachments folder but leave it existing
            // reason: Restore always puts all attachments back to the private folder
            val directory = File(AttachmentStorage.pathList[AttachmentStorage.Type.PUBLIC.ordinal])
            if (directory.exists() && directory.isDirectory) {
                directory.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
                directory.delete()
            }
            // set attachment storage to private
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
            val spe = sharedPref.edit()
            spe.putString(AttachmentStorage::class.simpleName, AttachmentStorage.Type.PRIVATE.name)
            spe.apply()
            // all info regarding attachment mode & location is kept inside AttachmentStorage.activeType
            val storageModeName = sharedPref.getString(
                AttachmentStorage::class.simpleName,
                AttachmentStorage.Type.PRIVATE.name
            )
            AttachmentStorage.activeType = AttachmentStorage.Type.entries.find {
                it.name.equals(storageModeName, ignoreCase = true)
            }!!
        } catch (e: Exception) {
            retVal = false
        }
    }

    return retVal
}

// export a complete ZIP containing data + attachments into Download folder
fun createZipArchive(
    context: Context,
    srcFolder: String,
    outFolder: String,
    zipName: String,
    pw: ProgressWindow?,
    nm: NotificationManagerCompat?,
    n: NotificationCompat.Builder?,
    maxProgress: Int,
    includePublicAttachments: Boolean = true
): Boolean {
    val BUFFER = 2048
    try {
        var permissionGranted = false
        if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true
        }
        var origin: BufferedInputStream? = null
        // need to make sure, a real backup is not interrupted
        val file = File("$outFolder/$zipName" + "_part")
        val dest = FileOutputStream(file)
        val zipOut = ZipOutputStream(BufferedOutputStream(dest))
        // performance gain: most relevant data are jpg/mp4/pdf --> no need to compress
        if (!PreferenceManager.getDefaultSharedPreferences(contextMainActivity).getBoolean("backupCompression", false)) {
            zipOut.setLevel(Deflater.NO_COMPRESSION)
        }
        val data = ByteArray(BUFFER)
        val subDir = File(srcFolder)
        var subdirList = subDir.list()
        // in case attachments are stored in public folder, add it for full backup
        if (includePublicAttachments) {
            if (AttachmentStorage.activeType == AttachmentStorage.Type.PUBLIC) {
                if (subdirList != null) {
                    subdirList = subdirList.plus(AttachmentStorage.pathList[AttachmentStorage.Type.PUBLIC.ordinal])
                }
            }
        }
        subdirList!!.forEach { sdItem ->
            // srv was forced stopped; nm != null indicates service operation
            if (nm != null) {
                if (BackupService.Companion.getServiceState(context) == BackupService.Companion.ServiceState.STOPPED) {
                    return false
                }
            }
            // sdItem is a folder or file name
            var sdItemOut = sdItem
            var f: File? = null
            // special handling for public attachments
            if (sdItem.startsWith("/")) {
                // if sdItem starts with a "/", then it is a folder to the public attachment storage
                f = File(sdItem)
                // introduce a sdItemOut zo point for these public files to "Images"
                sdItemOut = "Images"
            } else {
                f = File("$srcFolder/$sdItem")
            }

            // get a list of files from current directory
            if (f.isDirectory) {
                // base foöder for later use
                var baseFolder = srcFolder + "/"
                // a list of file names
                val files = f.list()
                // increase number of file to operate
                if (!sdItem.equals(sdItemOut)) {
                    // set progress via pw in foreground
                    if (pw != null) {
                        (context as Activity).runOnUiThread(Runnable {
                            pw.absCount += files.size
                        })
                    }
                    // don't need that
                    baseFolder = ""
                }
                // loop file list
                for (i: Int in files?.indices!!) {
                    // srv was forced stopped; nm != null indicates service operation
                    if (nm != null) {
                        if (BackupService.Companion.getServiceState(context) == BackupService.Companion.ServiceState.STOPPED) {
                            return false
                        }
                    }
                    // set progress via pw in foreground
                    if (pw != null) {
                        (context as Activity).runOnUiThread(Runnable {
                            pw.incCount++
                        })
                    }
                    // set progress via nm in notification bar
                    if (nm != null) {
                        with(nm) {
                            if (permissionGranted) {
                                (context as Activity).runOnUiThread(Runnable {
                                    n!!.setContentText(i.toString() + "(" + maxProgress.toString() + ")")
                                       .setProgress(maxProgress, i, false)
                                    notify(1, n.build())
                                })
                            }
                        }
                    }
                    // streams
                    val fis = FileInputStream(baseFolder + sdItem + "/" + files[i])
                    origin = BufferedInputStream(fis, BUFFER)
                    val entry = ZipEntry(sdItemOut + "/" + files[i])
                    val fi = File(srcFolder + "/" + sdItemOut + "/" + files[i])
                    entry.time = fi.lastModified()
                    zipOut.putNextEntry(entry)
                    var count: Int
                    while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                        zipOut.write(data, 0, count)
                        zipOut.flush()
                    }
                    fis.close()
                }
            } else {
                val fis = FileInputStream(f)
                origin = BufferedInputStream(fis, BUFFER)
                val entry = ZipEntry(sdItemOut)
                entry.time = f.lastModified()
                zipOut.putNextEntry(entry)
                var count: Int
                while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                    zipOut.write(data, 0, count)
                    zipOut.flush()
                }
                fis.close()
            }
        }
        origin!!.close()
        zipOut.flush()
        zipOut.close()
        // the very last step is a hopefully quick file rename
        try {
            val src = File("$outFolder/$zipName" + "_part")
            val dst = File("$outFolder/$zipName")
            if (dst.exists()) {
                val path = Paths.get("$outFolder/$zipName")
                try {
                    Files.delete(path)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            if (src.exists()) {
                src.renameTo(dst)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e:NullPointerException){
            e.printStackTrace()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
    return true
}

// image scaling for existing images
fun execImageScaling(): Boolean {
    try {
        // GrzLog storage folder for attachments
        var appAttachmentStoragePath = AttachmentStorage.pathList[AttachmentStorage.activeType.ordinal]
        val subDir = File(appAttachmentStoragePath)
        // get a list of all files from directory and loop it
        var counter = 0
        val subdirList = subDir.list()
        subdirList!!.forEach { sd ->
            // only deal with image mimes
            var mime = getFileExtension("$appAttachmentStoragePath/$sd")
            if (!IMAGE_EXT.contains(mime, ignoreCase = true)) {
                return@forEach
            }
            // check file
            val f = File("$appAttachmentStoragePath/$sd")
            if (f.isFile) {
                resizeImageAndSave(f.absolutePath, f.absolutePath)
                counter++
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
    return true
}

// ------------------------------------------- legacy ----------------------------------------------
//
// upgrade app data from legacy versions
//
internal fun upgradeFromLegacy(storagePath: String?, deleteLegacyFile: Boolean): DataStore? {
    var dataStore: DataStore? = null
    // loop the possibly existing *.txt files
    val possibleNames = arrayOf("GrzLog.txt", "LogBookPro.txt", "LogBook.txt", "QuickLogBook.txt")
    var possibleNamesNdx = -1
    var file: File? = null
    for (i in possibleNames.indices) {
        file = File(storagePath, possibleNames[i])
        if (file.exists()) {
            // get data from legacy text file
            val appTextData = readAppFileData(file)
            // convert legacy text data to DatStore data
            if (appTextData.length > 0) {
                dataStore = convertAppFileTextToDataStore(appTextData)
            }
            // what txt file was chosen
            possibleNamesNdx = i
            break
        }
    }
    // a legacy txt file in app folder might be confusing
    if (deleteLegacyFile) {
        if (possibleNamesNdx > 0) {
            try {
                file!!.delete()
            } catch (e: Exception) {
            }
        }
    }
    return dataStore
}

// read data from file and handle them as UTF-8 text
private fun readAppFileData(file: File): String {
    var fileText = ""
    try {
        val byteArray = ByteArray(file.length().toInt())
        val fis = FileInputStream(file)
        var oneChar: Int
        var i = 0
        while (fis.read().also { oneChar = it } != -1) {
            byteArray[i++] = oneChar.toByte()
        }
        fis.close()
        fileText = String(byteArray, StandardCharsets.UTF_8)
    } catch (e: IOException) {
        e.printStackTrace()
    } finally {
        return fileText
    }
}

// convert text data to DataStore format
private fun convertAppFileTextToDataStore(text: String): DataStore {
    // retval
    val dataStore = DataStore()
    // split the whole file text into lines
    val parts = text.split("\\n+".toRegex()).toTypedArray()
    // search header for fileN: keywords + index:
    if (parts[0].startsWith("file0:")) {
        for (i in parts.indices) {
            val key = "file$i:"
            if (parts[i].startsWith(key)) {
                val keyVal = parts[i].split(":".toRegex()).toTypedArray()
                if (keyVal.size > 1 && keyVal[1].length > 0) {
                    dataStore.namesSection.add(keyVal[1])
                } else {
                    dataStore.namesSection.add(key.substring(0, key.length - 1))
                }
            } else {
                // next line after fileX: section shall contain the selected file index
                if (parts[i].startsWith("index:")) {
                    val keyVal = parts[i].split(":".toRegex()).toTypedArray()
                    if (keyVal.size > 1 && keyVal[1].length > 0) {
                        try {
                            dataStore.selectedSection = keyVal[1].toInt()
                        } catch (e: NumberFormatException) {
                            dataStore.selectedSection = 0
                        }
                    } else {
                        dataStore.selectedSection = 0
                    }
                } else {
                    // partial missing header
                    dataStore.selectedSection = 0
                }
                // after finding the file index, we simply stop
                break
            }
        }
    } else {
        // app data file does not have section headers --> only 1 active file section exists
        dataStore.namesSection.add("folder")
        dataStore.selectedSection = 0
    }
    // next search for keyboard type sections
    var ndx = 0
    for (i in parts.indices) {
        val key = "keyboard$ndx:"
        if (parts[i].startsWith(key)) {
            val keyVal = parts[i].split(":".toRegex()).toTypedArray()
            try {
                dataStore.tagSection.add(keyVal[1].toInt())
            } catch (e: NumberFormatException) {
                dataStore.tagSection.add(0)
            }
            ndx++
        }
    }
    // tagSection loop
    var len = dataStore.tagSection.size
    for (i in len until dataStore.namesSection.size) {
        dataStore.tagSection.add(-1)
    }
    // next search for timestamp type sections
    ndx = 0
    for (i in parts.indices) {
        val key = "timestamp$ndx:"
        if (parts[i].startsWith(key)) {
            val keyVal = parts[i].split(":".toRegex()).toTypedArray()
            try {
                dataStore.timeSection.add(keyVal[1].toInt())
            } catch (e: NumberFormatException) {
                dataStore.timeSection.add(DataStore.TIMESTAMP.OFF)
            }
            ndx++
        }
    }
    len = dataStore.timeSection.size
    for (i in len until dataStore.namesSection.size) {
        dataStore.timeSection.add(DataStore.TIMESTAMP.OFF)
    }
    // next search for real data sections !! ini file has UIDs in text !!
    var collector = ""
    ndx = 0
    for (i in parts.indices) {
        // check for begin of a new folder
        val key = "[[$ndx]]"
        if (parts[i].endsWith(key)) {
            // could be like this
            if (parts[i] == key) {
                // need to reject the first match and add the folder to DataStore at the next match
                if (ndx > 0) {
                    dataStore.dataSection.add(collector)
                }
                ndx++
                collector = ""
                continue
            }
            // it may happen, key is found at the end of parts[i] with leading data from the previous folder
            if (parts[i] != key) {
                // remove key from parts
                parts[i] = parts[i].dropLast(key.length)
                // add remaining data to collector
                collector += parts[i] + "\n"
                // add collector to dataStore, if index is not 0 ,aka 1st folder
                if (ndx > 0) {
                    dataStore.dataSection.add(collector)
                }
                // go ahead
                ndx++
                collector = ""
                continue
            }
        } else {
            // remove spaces from line ends
            var currLine = trimEndAll(parts[i])
            // skip if line is empty
            if (currLine.isNotEmpty()) {
                collector += currLine + "\n"
            }
        }
    }
    // add the last folder to DataStore
    dataStore.dataSection.add(collector)
    len = dataStore.dataSection.size
    // fill not existing data sections with empty strings
    for (i in len until dataStore.namesSection.size) {
        dataStore.dataSection.add("")
    }
    // return
    return dataStore
}

// write UTF8 text to file
fun writeFile(file: File, text: String): Boolean {
    var retVal = true
    try {
        if (!file.exists()) {
            file.createNewFile()
        }
    } catch (ioe: IOException) {
        retVal = false
    } catch (see: SecurityException) {
        retVal = false
    }
    var fos: FileOutputStream?
    try {
        fos = FileOutputStream(file)
        fos.write(text.toByteArray(StandardCharsets.UTF_8))
        fos.close()
    } catch (e: IOException) {
        e.printStackTrace()
        retVal = false
    }
    return retVal
}

// return data from DataStore class as a lengthy String
internal fun convertDataStoreToAppFileText(ds: DataStore): String {
    // folder names
    var txt = ""
    for (i in 0 until DataStore.SECTIONS_COUNT) {
        if (i >= ds.namesSection.size) {
            break
        }
        txt += "file" + i + ":" + ds.namesSection.get(i) + "\n"
    }
    // selected folder index
    txt += "index:" + ds.selectedSection + "\n"
    // tag section: store deleted item indexes from ListView  --> !!field 0 stores the first visible item index of the ListView
    for (i in 0 until ds.tagSection.size) {
        txt += "keyboard" + i + ":" + ds.tagSection.get(i) + "\n"
    }
    // folder timestamp types
    for (i in 0 until DataStore.SECTIONS_COUNT) {
        if (i >= ds.timeSection.size) {
            break
        }
        txt += "timestamp" + i + ":" + ds.timeSection.get(i) + "\n"
    }
    // protected folders have the option to get encrypted
    val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
    var encryptProtectedFolders = sharedPref.getBoolean("encryptProtectedFolders", false)
    // distinguish between encryption AND no encryption
    if (encryptProtectedFolders) {
        // encrypt DataStore.dataSection aka folder
        var keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
        for (i in ds.dataSection.indices) {
            if (i >= ds.dataSection.size) {
                break
            }
            // folder header
            txt += "[[" + i + "]]\n"
            // only encrypt protected folders
            if (ds.timeSection[i] == TIMESTAMP.AUTH) {
                txt += keyManager.encryptGCM(ds.dataSection[i], keyManager.decryptPwdPrv(appPwdPub))
            } else {
                // keep not protected folders as plain text
                txt += ds.dataSection[i]
            }
        }
    } else {
        // all folders as plain text data
        for (i in 0 until DataStore.SECTIONS_COUNT) {
            if (i >= ds.dataSection.size) {
                break
            }
            txt += "[[" + i + "]]\n"
            txt += ds.dataSection[i]
        }
    }
    return txt
}

// --------------------------------------- end legacy ----------------------------------------------
// data backup into a text file
internal fun createTxtBackup(context: Context, downloadDir: String?, dataStore: DataStore?): Boolean {
    if (dataStore == null) {
        return true
    }
    val text = convertDataStoreToAppFileText(dataStore)
    val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
    val file = File(downloadDir, "$appName.txt")
    var result = writeFile(file, text)
    return result
}

// trim trailing spaces of a string
fun trimEndAll(myString: String): String {
    var tmp = myString
    for (i in tmp.length - 1 downTo 0) {
        if (tmp[i] == ' ') {
            continue
        } else {
            tmp = tmp.substring(0, i + 1)
            break
        }
    }
    return tmp
}

// convert a date string "yyyy-MM-dd" into a day of week number (Sun = 1 ... Sat = 7)
fun dayNumberOfWeek(dateStr: String?): Int {
    val date: Date
    val format: DateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    date = try {
        dateStr?.let { format.parse(it) }!!
    } catch (ex: Exception) {
        Date()
    }
    val cal = Calendar.getInstance()
    cal.time = date
    return cal[Calendar.DAY_OF_WEEK]
}

// DataStore date header parser
fun dateHeaderParser(dateStr: String): LocalDate? {
    var headerDate: LocalDate? = null
    try {
        // build a date from dateStr
        headerDate = LocalDate.parse(
            dateStr,
            DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")
        )
    } catch(e: DateTimeParseException) {
        try {
            // it can happen, that header does not have a week day name or a wrong one
            headerDate = LocalDate.parse(
                dateStr.substring(0, 10),
                DateTimeFormatter.ofPattern("yyyy-MM-dd")
            )
        } catch(e: DateTimeParseException) {
            headerDate = null
        }
    }
    return headerDate
}

// convert a date string "yyyy-MM-dd" into Name Day of Week
fun dayNameOfWeek(dateStr: String?): String {
    val date: Date
    val format: DateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    date = try {
        format.parse(dateStr.toString()) as Date
    } catch (ex: Exception) {
        Date()
    }
    return SimpleDateFormat(" EEE", Locale.ENGLISH).format(date)
}

// https://stackoverflow.com/questions/3361423/android-get-listview-item-height
fun listviewHeight(list: ListView): Int {
    // surely overdoing ...
    var height = 0
    for (i in 0 until list.count) {
        val childView = list.adapter.getView(i, null, list)
        childView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        height += childView.measuredHeight
    }
    height += list.dividerHeight * list.count
    // return whatever is larger :)
    return Math.max(height, list.height)
}

// multipurpose AlertBuilder dialog boxes
fun okBox(context: Context?, title: String?, message: String) {
    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    builder.setPositiveButton(
        R.string.ok,
        DialogInterface.OnClickListener { dialog, which -> dialog.dismiss() })
    try {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    } catch (e: Exception) {
    }
}

fun okBox(context: Context?, title: String?, message: String, runnerOk: Runnable?) {
    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    builder.setPositiveButton(R.string.ok, DialogInterface.OnClickListener { dialog, which ->
        runnerOk?.run()
        dialog.dismiss()
    })
    try {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    } catch (e: Exception) {
    }
}

// dialog with two choices: a) or b) or cancel
fun twoChoicesDialog(
    context: Context,
    title: String,
    message: String,
    choicePositive: String,
    choiceNegative: String,
    runnerPositive: Runnable?,
    runnerNeutral: Runnable?,
    runnerNegative: Runnable?
) {
    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    // CANCEL
    builder.setPositiveButton(
        R.string.cancel,
        DialogInterface.OnClickListener { dialog, which ->
            runnerPositive?.run()
            return@OnClickListener
        })
    // ALTERNATIVE # ONE
    builder.setNeutralButton(
        choicePositive,
        DialogInterface.OnClickListener { dialog, which ->
            runnerNeutral?.run()
            dialog.dismiss()
        })
    // ALTERNATIVE # TWO
    builder.setNegativeButton(
        choiceNegative,
        DialogInterface.OnClickListener { dialog, which ->
            runnerNegative?.run()
            dialog.dismiss()
        })
    val dialog = builder.create()
    dialog.setOnShowListener {
        dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
    dialog.setCanceledOnTouchOutside(false)
    val buttonPositive: Button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    buttonPositive.setTextColor(Color.rgb(255, 100, 100))
    val buttonNeutral: Button = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
    buttonNeutral.isAllCaps = false
    val buttonNegative: Button = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
    buttonNegative.isAllCaps = false
}

enum class DECISION {
    YESNO, OKCANCEL
}

fun decisionBox(
    context: Context,
    decision: DECISION,
    title: String?,
    message: String,
    runnerPositive: Runnable?,
    runnerNegative: Runnable?
) {
    val decisionPositive = if (decision == DECISION.YESNO) context.getString(R.string.yes) else context.getString(R.string.ok)
    val decisionNegative = if (decision == DECISION.YESNO) context.getString(R.string.no) else context.getString(R.string.cancel)
    var builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    builder.setPositiveButton(
        decisionPositive,
        DialogInterface.OnClickListener { dialog, which ->
            runnerPositive?.run()
            dialog.dismiss()
        })
    builder.setNegativeButton(
        decisionNegative,
        DialogInterface.OnClickListener { dialog, which ->
            runnerNegative?.run()
            dialog.dismiss()
        })
    try {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    } catch (e: Exception) {
    }
}

fun decisionBoxCustom(
    context: Context,
    title: String?,
    message: String,
    decisionPositive: String,
    decisionNegative: String,
    runnerPositive: Runnable?,
    runnerNegative: Runnable?
) {
    var builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    builder.setPositiveButton(
        decisionPositive,
        DialogInterface.OnClickListener { dialog, which ->
            runnerPositive?.run()
            dialog.dismiss()
        })
    builder.setNegativeButton(
        decisionNegative,
        DialogInterface.OnClickListener { dialog, which ->
            runnerNegative?.run()
            dialog.dismiss()
        })
    try {
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false)
        dlg.setCanceledOnTouchOutside(false)
    } catch (e: Exception) {
    }
}

// do not show again alert box
fun dontShowAgain(context: Context, title: String?, sharedPreferencesString: String?) {
    val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
    if (!sharedPref.getBoolean(sharedPreferencesString, true)) {
        return
    }
    val items = arrayOf<CharSequence>(context.getString(R.string.dontShowAgain))
    val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    builder.setTitle(title)
    builder.setMultiChoiceItems(
        items,
        null,
        OnMultiChoiceClickListener { dialog, which, isChecked ->
            if (isChecked) {
                val spe = sharedPref.edit()
                spe.putBoolean(sharedPreferencesString, false)
                spe.apply()
            }
        })
    builder.setPositiveButton("Ok", DialogInterface.OnClickListener { dialog, which -> })
    val dialog = builder.create()
    dialog.setOnShowListener {
        dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}

// https://stackoverflow.com/questions/12616124/get-number-of-files-in-a-directory-and-its-subdirectories
fun countFiles(directory: File): Int {
    var count = 0
    try {
        for (file in directory.listFiles()!!) {
            if (file.isDirectory) {
                count += countFiles(file)
            }
            count++
        }
    } catch (e: Exception) {
    }
    return count
}

// https://stackoverflow.com/questions/33910406/count-the-number-of-files-in-archive-in-java
fun countZipFiles(zipFile: ZipFile): Int {
    val entries = zipFile.entries()
    var numRegularFiles = 0
    while (entries.hasMoreElements()) {
        if (!entries.nextElement().isDirectory) {
            ++numRegularFiles
        }
    }
    return numRegularFiles
}

// https://stackoverflow.com/questions/14930908/how-to-delete-all-files-and-folders-in-one-folder-on-android
fun deleteRecursive(fileOrDirectory: File) {
    try {
        if (fileOrDirectory.isDirectory) {
            for (child in fileOrDirectory.listFiles()!!) {
                deleteRecursive(child)
            }
        }
        fileOrDirectory.delete()
    } catch (e: Exception) {
    }
}

// centered toast partially based on: https://stackoverflow.com/questions/15321186/how-to-display-toast-at-center-of-screen
fun centeredToast(context: Context, message: String?, duration: Int) {
    val inflater = (context as Activity).layoutInflater
    val layout = inflater.inflate(
        R.layout.toast_layout,
        null
    )
    val tv = layout.findViewById<View>(R.id.toastText) as TextView
    tv.text = message
    tv.setBackgroundColor(Color.DKGRAY)
    tv.setTextColor(Color.YELLOW)
    val toast = Toast(context)
    toast.setGravity(Gravity.CENTER, 0, 0)
    toast.duration = duration
    toast.view = layout
    toast.show()
}

// cancellable centered toast
internal object CenteredCloseableToast {
    private var toast: Toast? = null
    private var suppressToast = false

    @JvmStatic
    fun show(context: Context, message: String?, duration: Int) {
        if (suppressToast) {
            suppressToast = false
            return
        }
        val inflater = (context as Activity).layoutInflater
        val layout = inflater.inflate(
            R.layout.toast_layout,
            null
        )
        val tv = layout.findViewById<View>(R.id.toastText) as TextView
        tv.text = message
        tv.setBackgroundColor(Color.DKGRAY)
        tv.setTextColor(Color.YELLOW)
        toast = Toast(context)
        toast?.setGravity(Gravity.CENTER, 0, 0)
        toast?.duration = duration
        toast?.view = layout
        toast?.show()
    }

    @JvmStatic
    fun cancel() {
        suppressToast = true
        if (toast != null) {
            toast?.cancel()
        }
        Handler().postDelayed({
            toast = null
            suppressToast = false
        }, 1000)
    }
}

// https://stackoverflow.com/questions/59351273/how-to-get-the-mimetype-of-a-file-with-special-character-in-android
fun getFileExtension(fileName: String?): String {
    try {
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return MimeTypeMap.getFileExtensionFromUrl(encoded).lowercase(Locale.getDefault())
    } catch (e: UnsupportedEncodingException) {
        return fileName!!
    }
}

// copy "Android's Photo Picker Uri" to fullFilePath, here:  GrzLog files images
fun copyPickerUriToAppFilesImages(context: Context, pickerUri: Uri, fullFilePath: String): Boolean {
    var retVal = true
    try {
        // input
        val inputStream = context.contentResolver.openInputStream(pickerUri)
        // output
        val appImageFile = File(fullFilePath)
        val outputStream = FileOutputStream(appImageFile)
        // copy stream
        inputStream?.copyTo(outputStream)
        // finish
        inputStream?.close()
        outputStream.close()
    } catch (e: Exception) {
        retVal = false
    }
    return retVal
}

// https://stackoverflow.com/questions/50809679/resize-and-save-images
//      1) Glide is twice as fast as BitmapFactory and allows to match to screen
//      2) fileInp MUST be accessible, works best with return value data from OnActivityResult
fun resizeImageAndSave(fileInp: String, fileOut: String): Boolean {
    var retval = true
    Glide
        .with(contextMainActivity)
        .asBitmap()
        .load(fileInp)
        .fitCenter()
        .into(object : SimpleTarget<Bitmap?>(
            contextMainActivity.resources.displayMetrics.widthPixels,
            contextMainActivity.resources.displayMetrics.heightPixels
        ) {
            override fun onLoadFailed(errorDrawable: Drawable?) {
                retval = false
                return
            }
            override fun onResourceReady(
                resource: Bitmap,
                @Nullable transition: Transition<in Bitmap?>?
            ) {
                try {
                    resource.compress(
                        Bitmap.CompressFormat.JPEG,
                        75,
                        FileOutputStream(fileOut)
                    )
                } catch (e: FileNotFoundException) {
                    retval = false
                }
            }
        })
    return retval
}
// https://stackoverflow.com/questions/11688982/pick-image-from-sd-card-resize-the-image-and-save-it-back-to-sd-card
//      Solution without OutOfMemoryException in Kotlin
//      -- 2x longer execution time as Glide
//      -- scaling in power of 2
fun resizeImageAndSaveBMF(context: Context, fileInp: String, fileOut: String): Boolean {
    var retval = false
    val bmOptions = BitmapFactory.Options()
    bmOptions.inJustDecodeBounds = true
    BitmapFactory.decodeFile(fileInp, bmOptions)
    val photoW = bmOptions.outWidth
    val photoH = bmOptions.outHeight
    var scaleFactor = 1
    // screen capabilities
    var screenW = 0
    var screenH = 0
    if (Build.VERSION.SDK_INT < 30) {
        screenW = Resources.getSystem().getDisplayMetrics().widthPixels
        screenH = Resources.getSystem().getDisplayMetrics().heightPixels
    } else {
        val deviceWindowMetrics: WindowMetrics =
            context.getSystemService(WindowManager::class.java).getMaximumWindowMetrics()
        screenW = deviceWindowMetrics.bounds.width()
        screenH = deviceWindowMetrics.bounds.height()
    }
    // scale down the image
    var scaleTo = Math.min(screenW, screenH)
    scaleFactor = Math.min(photoW / scaleTo, photoH / scaleTo)
    bmOptions.inJustDecodeBounds = false
    bmOptions.inSampleSize = scaleFactor
    // check decoding
    val resized = BitmapFactory.decodeFile(fileInp, bmOptions) ?: return false
    // create output stream
    var fos = FileOutputStream(fileOut)
    fos.use {
        resized.compress(Bitmap.CompressFormat.JPEG, 75, it)
        resized.recycle()
    }
    retval = true
    return retval
}

// show "embedded photo picker" as dialog
// defer photo picker implementation until min SDK >=34
// https://android-developers.googleblog.com/2026/01/httpsandroid-developers.googleblog.com202506android-embedded-photo-picker.html%20.html
//fun showEmbeddedPhotoPicker(context: Context) {
//    // have dialog
//    val dialog = Dialog(context)
//    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
//    // no close if click outside
//    dialog.setCancelable(false)
//    // show content of connected xml
//    dialog.setContentView(R.layout.embedded_photo_picker)
//    // dialog show
//    dialog.show()
//}

// common phone Gallery info helper
class GalleryInfo() {

    enum class MediaType {
        IS_UNKNOWN,
        IS_IMAGE,
        IS_VIDEO
    }

    companion object {

        // phone Gallery visual media info class
        class GalleryMediaInfo(
            var id: Int,
            var name: String,
            var uri: Uri,
            var bucket: String,
            var relativePath: String,
            var absolutePath: String,
            var dateAdded: String,
            var size: String,
            var type: MediaType
        ) {}

        // get info about all phone Gallery images
        fun getGalleryImagesInfo(context: Context): List<GalleryMediaInfo> {
            val list: MutableList<GalleryMediaInfo> = java.util.ArrayList()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            val cursor = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val imageUri = Uri.withAppendedPath(collection, id.toString())
                    val bucket = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
                    val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                    val dateAdded = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                    val absolutePath = getFileRealPath(context.contentResolver, imageUri, MediaType.IS_IMAGE)
                    val ndx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val size = if (cursor.getString(ndx) == null) "null" else cursor.getString(ndx)
                    list.add(
                        GalleryMediaInfo(
                            id,
                            name,
                            imageUri,
                            bucket,
                            relativePath,
                            absolutePath,
                            dateAdded,
                            size,
                            MediaType.IS_IMAGE
                        )
                    )
                }
                cursor.close()
            }
            return list
        }

        // get info about one phone Gallery image or video according to requested  ID
        fun getGalleryMediaInfo(context: Context, idMediaStore: Int): GalleryMediaInfo? {
            // return value
            var info: GalleryMediaInfo? = null
            // check all about images
            var collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            var projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            var cursor = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    if (id == idMediaStore) {
                        // the id was found
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                        val imageUri = Uri.withAppendedPath(collection, id.toString())
                        val bucket = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
                        val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                        val dateAdded = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                        val absolutePath = getFileRealPath(context.contentResolver, imageUri, MediaType.IS_IMAGE)
                        val ndx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        val size = if (cursor.getString(ndx) == null) "null" else cursor.getString(ndx)
                        info = GalleryMediaInfo(
                            id,
                            name,
                            imageUri,
                            bucket,
                            relativePath,
                            absolutePath,
                            dateAdded,
                            size,
                            MediaType.IS_IMAGE
                        )
                        break
                    }
                }
                cursor.close()
            }
            // check all about videos
            if (info == null) {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE
                )
                cursor = context.contentResolver.query(
                    collection,
                    projection,
                    null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                        if (id == idMediaStore) {
                            // the id was found
                            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                            val videoUri = Uri.withAppendedPath(collection, id.toString())
                            val bucket = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME))
                            val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH))
                            val dateAdded = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                            val absolutePath = getFileRealPath(context.contentResolver, videoUri, MediaType.IS_VIDEO)
                            var ndx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                            val size = if (cursor.getString(ndx) == null) "null" else cursor.getString(ndx)
                            info = GalleryMediaInfo(
                                id,
                                name,
                                videoUri,
                                bucket,
                                relativePath,
                                absolutePath,
                                dateAdded,
                                size,
                                MediaType.IS_VIDEO
                            )
                            break
                        }
                    }
                    cursor.close()
                }
            }
            return info
        }

        // get info about one phone Gallery image or video according to a provided "MediaStore Picker Uri"
        fun getGalleryMediaRealPath(context: Context, pickerUri: Uri): String {
            // return value
            var realPath = ""
            // get MediaStore ID
            val pickerUriString = pickerUri.toString()
            val idMediaStore = pickerUriString.substring(pickerUriString.lastIndexOf("/") + 1).toInt()
            // images
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID
                )
                val cursor = context.contentResolver.query(
                    collection,
                    projection,
                    null, null,
                    null
                )
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val id =
                            cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        // check, whether the id was found
                        if (id == idMediaStore) {
                            // get the real image uri: differs from uri provided as parameter
                            val imageUri = Uri.withAppendedPath(collection, id.toString())
                            // continue with a new inner cursor
                            val innerCursor =
                                context.contentResolver.query(imageUri, null, null, null, null)
                            if (innerCursor != null) {
                                if (innerCursor.moveToFirst()) {
                                    // MediaStore.Images.Media.DATA is read only
                                    var columnName = MediaStore.Images.Media.DATA
                                    // innerCursor column index
                                    val filePathColumnIndex = innerCursor.getColumnIndex(columnName)
                                    // innerCursor column value is what we are looking for
                                    realPath = innerCursor.getString(filePathColumnIndex)
                                }
                                innerCursor.close()
                            }
                            break
                        }
                    }
                    cursor.close()
                }
            // videos
            if (realPath.isEmpty()) {
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Video.Media._ID
                )
                val cursor = context.contentResolver.query(
                    collection,
                    projection,
                    null, null,
                    null
                )
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val id =
                            cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                        // check, whether the id was found
                        if (id == idMediaStore) {
                            // get the real image uri: differs from uri provided as parameter
                            val imageUri = Uri.withAppendedPath(collection, id.toString())
                            // continue with a new inner cursor
                            val innerCursor =
                                context.contentResolver.query(imageUri, null, null, null, null)
                            if (innerCursor != null) {
                                if (innerCursor.moveToFirst()) {
                                    // MediaStore.Images.Media.DATA is read only
                                    var columnName = MediaStore.Video.Media.DATA
                                    // innerCursor column index
                                    val filePathColumnIndex = innerCursor.getColumnIndex(columnName)
                                    // innerCursor column value is what we are looking for
                                    realPath = innerCursor.getString(filePathColumnIndex)
                                }
                                innerCursor.close()
                            }
                            break
                        }
                    }
                    cursor.close()
                }
            }
            return realPath
        }

        // get real path of a "content image": MediaStore or PhotoPicker start with "content://media"
        // API<35 might provide a shorter version "content://media" vs. "content://media/picker"
        private fun getFileRealPath(
            contentResolver: ContentResolver,
            uri: Uri,
            type: MediaType
        ): String {
            var filePath = ""
            if (type == MediaType.IS_IMAGE) {
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        // column name by uri type
                        var columnName = MediaStore.Images.Media.DATA
                        when (uri) {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> {
                                columnName = MediaStore.Images.Media.DATA
                            }
                        }
                        // column index
                        val filePathColumnIndex = cursor.getColumnIndex(columnName)
                        // column value is the uri related file local path
                        filePath = cursor.getString(filePathColumnIndex)
                    }
                    cursor.close()
                }
            }
            if (type == MediaType.IS_VIDEO) {
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        // column name by uri type
                        var columnName = MediaStore.Video.Media.DATA
                        when (uri) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> {
                                columnName = MediaStore.Video.Media.DATA
                            }
                        }
                        // column index
                        val filePathColumnIndex = cursor.getColumnIndex(columnName)
                        // column value is the uri related file local path
                        filePath = cursor.getString(filePathColumnIndex)
                    }
                    cursor.close()
                }
            }
            return filePath
        }
    }
}

// show a given image by its uri and its name in a customized alert dialog containing: title, message, image, close
fun showImagePopup(context: Context, imagePath: String, title: String, imageUri: Uri) {

    fun show(resource: Drawable) {
        // handle self close
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
        var selfClose: Boolean = sharedPref.getBoolean("autoClosePreview", false)
        var mimeExt = getFileExtension(imagePath)
        if (mimeExt.equals("gif", ignoreCase = true)) {
            selfClose = false
        }
        // get image original dimensions
        var bmpWidth = resource.intrinsicWidth
        var bmpHeight = resource.intrinsicHeight
        var aspectRatio = bmpWidth.toFloat() / bmpHeight.toFloat()
        // have dialog
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // no close dlg if click outside
        dialog.setCancelable(false)
        // show content of connected xml
        dialog.setContentView(R.layout.layout_image_popup)
        // set title
        val titleTv = dialog.findViewById(R.id.titleViewImgPop) as TextView
        if (title.length > 0) {
            titleTv.text = "[" + title + "]"
            titleTv.setOnClickListener() {
                okBox(context, "[" + title + "]", imagePath)
            }
        } else {
            titleTv.text = ""
        }
        // set text
        val textTv = dialog.findViewById(R.id.textViewImgPop) as TextView
        if (imagePath.isEmpty()) {
            // applies to all image links to Gallery: imageUri is a "PickerUri"
            val uriString = imageUri.toString()
            val idMediaStore = uriString.substring(uriString.lastIndexOf("/") + 1).toInt()
            val imageInfo = GalleryInfo.getGalleryMediaInfo(context, idMediaStore)
            if (imageInfo != null) {
                textTv.text = imageInfo.absolutePath + "\nMedia Store Id: " + idMediaStore.toString()
                // !! only if coming from linked images gallery !!
                if (titleTv.text.isEmpty()) {
                    titleTv.text = imageInfo.name
                }
            }
        } else {
            // applies to all images from app context
            textTv.text = imagePath
        }
        // button close handler
        val closeBtn = dialog.findViewById(R.id.btnCloseImgPop) as Button
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }
        // max allowed dialog dimensions based on display
        val widthMax = context.resources.displayMetrics.widthPixels
        var heightMax = context.resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            heightMax = context.resources.displayMetrics.heightPixels -
                    MainActivity.navigationBarInset.bottom -
                    MainActivity.statusBarInset.top
        }
        // would be a reasonable image height
        var wantImgHeight = widthMax / aspectRatio
        // temporarily set dialog layout and get its height back (as expected it's identical to heightMax)
        dialog.getWindow()!!.setLayout(widthMax, heightMax)
        var dlgHeight = dialog.getWindow()!!.attributes.height
        // get component's current height
        val imageView = dialog.findViewById(R.id.imageViewPop) as ImageView
        var ivHeight = imageView.layoutParams.height
        val titHeight = titleTv.layoutParams.height
        val txtHeight = textTv.layoutParams.height
        val butHeight = closeBtn.layoutParams.height + 20
        var spaceTitleTextButton = (titHeight + txtHeight + butHeight) * 1.5f
        // calc space in pix for title, text, button, anxiety gauge
        // final image height based on dialog width and image aspect ratio with a max limit at dialog height - title, text, button
        var heightImg = Math.min((dlgHeight - spaceTitleTextButton), wantImgHeight).toInt()
        imageView.layoutParams.height = heightImg
        // dialog height dimension shall match to image height
        var heightDlgMatched = heightImg + spaceTitleTextButton
        dialog.getWindow()!!.setLayout(widthMax, heightDlgMatched.toInt())
        if (mimeExt.equals("gif", ignoreCase = true)) {
            // load full image
            Glide.with(context)
                .load(imageUri.path)
                .override(widthMax, heightImg)
                .fitCenter()
                .into(imageView)
        } else {
            // render image in imageView with Glide, reuse previously loaded resource
            Glide.with(context)
                .load(resource)
                .override(widthMax, heightImg)
                .fitCenter()
                .into(imageView)
        }
        // image click handler
        imageView.setOnClickListener {
            try {
                showImageInAndroidGalleryViewer(context, imageUri)
            } catch (e: Exception) {
                centeredToast(contextMainActivity, "GrzLog Gallery Viewer: " + e.message.toString(), 3000)
            }
        }
        // dialog show
        dialog.show()
        // alert auto close feature
        if (selfClose) {
            object : CountDownTimer(3000, 200) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    dialog.dismiss()
                }
            }.start()
        }
    }

    // provide a Bitmap from uri: circumvents the need to copy a linked image locally
    var bmp = loadBitmapFromUri(context, imageUri)
    // prevent onLoadFailed(..) to be called twice: no clue why this happens at all & at the 1st fail only
    var loaded = false

    // at the beginning, Glide is used to get the image - only to learn its dimensions to obtain the aspect ratio
    Glide
        .with(context)
        .asBitmap()
        .load(bmp)
        .override(500, 500)
        .listener(object : RequestListener<Bitmap>{
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?, isFirstResource: Boolean): Boolean {
                // prevent onLoadFailed(..) to be called twice: no clue why this happens at all & at the 1st fail only
                if (!loaded) {
                    loaded = true
                    okBox(context, context.getString(R.string.FileNotFound), imageUri.path.toString())
                }
                return true
            }
            override fun onResourceReady(resource: Bitmap?, model: Any?, target: Target<Bitmap>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                if (resource != null) {
                    // in the 2nd step, the dialog dimensions are adjusted and the image is rendered
                    val d: Drawable = BitmapDrawable(context.resources, resource)
                    show(d)
                } else {
                    okBox(context, context.getString(R.string.FileNotFound), imageUri.path.toString())
                }
                return false
            }
        })
        .preload()

}

// helper to convert an uri to a temporary & "in memory" Bitmap
fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        // open inputStream from the uri
        val inputStream = context.contentResolver.openInputStream(uri)
        // decode inputStream into bitmap
        BitmapFactory.decodeStream(inputStream)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// helper to convert an uri to a temporary & "in memory" thumbnail Bitmap
fun loadThumbnailFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        // open inputStream from the uri
        val inputStream = context.contentResolver.openInputStream(uri)
        // options
        var options = BitmapFactory.Options()
        options.outWidth = 128
        options.outHeight = 128
        // decode inputStream into bitmap
        BitmapFactory.decodeStream(inputStream, null, options)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// show image in default gallery viewer: https://stackoverflow.com/questions/13209494/how-to-get-the-full-file-path-from-uri
fun showImageInAndroidGalleryViewer(context: Context, imageUri: Uri, mimeType: String = "image/*") {
    // get ext
    var type = mimeType
    val mimeExt = getFileExtension(imageUri.path)
    if (mimeExt.equals("gif", ignoreCase = true)) {
        type = "image/gif"
    }

    // basic intent
    val intent = Intent(Intent.ACTION_VIEW)

    // distinguish images from "Android Photo Picker" and all others
    // API<35 might provide a shorter version "content://media" vs. "content://media/picker"
    if (imageUri.toString().startsWith("content://media")) {
        // applies to image links to Gallery: convert "MediaStore Picker Uri" to "Google Photos Uri"
        val realPath = GalleryInfo.getGalleryMediaRealPath(context, imageUri)
        val realUri = FileProvider.getUriForFile(
            context,
            context.packageName + ".provider",
            File(realPath)
        )
        // finalize intent
        intent.setDataAndType(realUri, type)
    } else {
        // convert imageUri to the app local file
        val file = getFileFromUri(context.applicationContext, imageUri)
        if (file == null) {
            okBox(context, context.getString(R.string.FileNotFound), imageUri.path.toString())
            return
        }
        // prepare Android gallery viewer via intent
        val uriFile = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".provider",
            file
        )
        intent.setDataAndType(uriFile, type)
    }

    // start phone viewer app
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        MainActivity.showAppReminders = false
        MainActivity.temporaryBackground = true
        context.startActivity(intent)
    } catch (e: Exception) {
        okBox(context, "Ups", e.message!!)
    }
}

// pretty tricky way to get a file out of a uri
fun getFileFromUri(context: Context, imageUriOri: Uri): File? {
    var imageUri = imageUriOri
    var file: File?
    try {
        // a) works well for file  b) for content, if it ends with a real file  c) works well for content, if it end with an ID
        file = FileUtils.getFile(context, imageUri)
    } catch (fe: Exception) {
        // this works well for content, if it ends with a real file (was throwing exception)
        var imageStr = imageUri.toString()
        val contentStr = "content://com.grzwolf.grzlog.provider/external_storage_root/DCIM/GrzLog/"
        val fileStr = "file:///storage/emulated/0/DCIM/GrzLog/"
        if (imageStr.contains(contentStr, ignoreCase = true)) {
            imageStr = imageStr.replace(contentStr, fileStr)
            imageUri = Uri.parse(imageStr)
        }
        file = FileUtils.getFile(context, imageUri)
    }
    return file
}

// numerical version comparison
fun isUpdateDue(userVersionSplit: Array<String>, latestVersionSplit: Array<String>): Boolean {
    try {
        val majorUserVersion = userVersionSplit[0].toInt()
        val minorUserVersion = userVersionSplit[1].toInt()
        val patchUserVersion = userVersionSplit[2].toInt()
        val majorLatestVersion = latestVersionSplit[0].toInt()
        val minorLatestVersion = latestVersionSplit[1].toInt()
        val patchLatestVersion = latestVersionSplit[2].toInt()
        if (majorUserVersion <= majorLatestVersion) {
            if (majorUserVersion < majorLatestVersion) {
                return true
            } else {
                if (minorUserVersion <= minorLatestVersion) {
                    return if (minorUserVersion < minorLatestVersion) {
                        true
                    } else {
                        patchUserVersion < patchLatestVersion
                    }
                }
            }
        }
    } catch (ignored: java.lang.Exception) {
    }
    return false
}

//  internet availability
fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager
    val netInfo = cm.activeNetworkInfo
    return if (netInfo != null && netInfo.isConnectedOrConnecting) {
        true
    } else false
}

// a simple HEAD request checks, whether an url exists
fun urlExists(uri: String) : Boolean {
    val url = URL(uri)
    val huc = url.openConnection() as HttpURLConnection
    huc.setRequestMethod("HEAD")
    val responseCode = huc.getResponseCode()
    if (responseCode == 200) {
        return true
    } else {
        return false
    }
}

// show linked attachment
fun showAppLinkOrAttachment(context: Context,
                            title: String,
                            fileName: String?,
                            type: GalleryInfo.MediaType = GalleryInfo.MediaType.IS_UNKNOWN ) {
    // sake of mind
    if (fileName!!.isEmpty()) {
        return
    }

    //
    // media links to the phone Gallery provided by "Android Photo Picker"
    // API<35 might provide a shorter version "content://media" vs. "content://media/picker"
    //
    if (fileName.startsWith("content://media") == true) {
        // get uri from filename
        val uri = Uri.parse(fileName)
        // surely not to happen
        if (type == GalleryInfo.MediaType.IS_UNKNOWN) {
            // start popup with empty imagePath --> showImagePopup(..) converts this case into a real file name from uri
            showImagePopup(context, "", title, uri)
        }
        // Android Gallery linked image
        if (type == GalleryInfo.MediaType.IS_IMAGE) {
            // start popup with empty imagePath --> showImagePopup(..) converts this case into a real file name from uri
            showImagePopup(context, "", title, uri)
        }
        // Android Gallery linked video
        if (type == GalleryInfo.MediaType.IS_VIDEO) {
            showImageInAndroidGalleryViewer(context, uri, "video/*")
        }
        // all done, get out
        return
    }

    //
    // all images previously copied to app files
    //
    // fileName not starting with a '/' (emulator) AND 'file' (real device) is a www link: fire default browser and return
    if ((fileName.startsWith("/") == false) && (fileName.startsWith("file") == false)) {
        // take link as it is
        var uri = Uri.parse(fileName)
        // check link for https and replace it with http
        if (fileName.startsWith("https://") == true) {
            uri = Uri.parse(fileName.replace("https://", "http://"))
        } else {
            // link starts NOT with https and MIGHT NOT start with http: most likely starts with www or not even this
            if (fileName.startsWith("http://") == false) {
                uri = Uri.parse("http://$fileName")
            }
        }
        // open in browser tabs: https://stackoverflow.com/questions/7197133/android-open-browser-from-service-avoiding-multiple-tabs
        MainActivity.temporaryBackground = true
        val builder = CustomTabsIntent.Builder()
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(context, uri)
        return
    }

    //
    // all media previously copied to GrzLog attachments location
    //
    var fullFileName = fileName
    if (fileName.startsWith("file") == false) {
        fullFileName = "file://" + AttachmentStorage.pathList[AttachmentStorage.activeType.ordinal] + fileName
    }

    // parse uri from fullFileName
    var uri = Uri.parse(fullFileName)

    // MIME type file extension
    val mimeExt = getFileExtension(uri.toString())

    //
    // specific to show images: 1st in a popup dialog, from there in default images viewer (if clicked)
    //
    if (IMAGE_EXT.contains(mimeExt, ignoreCase = true)) {
        showImagePopup(context, fullFileName, title, uri)
        return
    }

    //
    // all other media are shown with their usually connected Android apps
    //
    var file = getFileFromUri(context, uri)
    if (file == null || !file.exists()) {
        // pdf, txt, audio cannot be copied to /sdcard/Pictures/GrzLog, so let's give it a try with the private attachments folder
        fullFileName = "file://" + AttachmentStorage.pathList[AttachmentStorage.Type.PRIVATE.ordinal] + fileName
        uri = Uri.parse(fullFileName)
        file = getFileFromUri(context, uri)
        if (file == null || !file.exists()) {
            // now give up
            okBox(context, context.getString(R.string.FileNotFound), fullFileName)
            return
        }
    }
    val uriFile = FileProvider.getUriForFile(
        context,
        BuildConfig.APPLICATION_ID + ".provider",
        file
    )
    // common intent preparations
    val intent = Intent(Intent.ACTION_VIEW)
    intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION
    // default video player
    if (VIDEO_EXT.contains(mimeExt, ignoreCase = true)) {
        intent.setDataAndType(uriFile, "video/*")
    } else {
        // default audio player
        if (AUDIO_EXT.contains(mimeExt, ignoreCase = true)) {
            intent.setDataAndType(uriFile, "audio/*")
        } else {
            // default PDF viewer
            if (mimeExt == "pdf") {
                intent.setDataAndType(uriFile, "application/pdf")
            } else {
                // default TXT viewer
                if (mimeExt == "txt") {
                    intent.setDataAndType(uriFile, "text/*")
                } else {
                    okBox(context, context.getString(R.string.missingFileAssociation), uriFile.path.toString(), null)
                    return
                }
            }
        }
    }
    // start intent
    try {
        MainActivity.temporaryBackground = true
        startActivity(context, intent, null)
    } catch (e: Exception) {
        okBox(context, "Ups", e.message!!)
    }
}

// replacement for deprecated ProgressDialog
class ProgressWindow(context: Context, message: String) {

    // private section
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var onePercentTrigger = 0f
    private var eventCollector = 0f

    // public section
    var dialog: Dialog? = null
    var absCount = 0f // init progress bar with total number of expected events
        set(value) {
            field = value
            onePercentTrigger = field / 100f
        }
    var absFakeCount = -1f // if != -1 show this a max. count
        set(value) {
            field = value
        }
    var incCount = 0 // if total event number is large, incCount reduces the progress bar render load by 1% steps
        set(value) {
            field = value
            eventCollector += 1f
            if (eventCollector >= onePercentTrigger) {
                progressBar?.progress = progressBar?.progress?.inc()!!
                if (absFakeCount != -1f) {
                    progressText?.text = progressBar?.progress.toString() + "%      " + field.toString() + "/" + absFakeCount.toInt().toString()
                } else {
                    progressText?.text = progressBar?.progress.toString() + "%      " + field.toString() + "/" + absCount.toInt().toString()
                }
                eventCollector = eventCollector - onePercentTrigger
            }
        }
    var curCount = 0 // direct render a provided event
        set(value) {
            field = value
            var pct = (100.0f * field.toFloat() / absCount).toInt()
            progressBar?.progress = pct
            if (absFakeCount != -1f) {
                progressText?.text = pct.toString() + "%      " + field.toString() + "/" + absFakeCount.toInt().toString()
            } else {
                progressText?.text = pct.toString() + "%      " + field.toString() + "/" + absCount.toInt().toString()
            }
        }

    init {
        // dialog
        dialog = Dialog(context)
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.setContentView(R.layout.layout_progress_dialog)
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(false)
        // progress bar
        progressBar = dialog!!.findViewById(R.id.progress_bar) as ProgressBar
        progressBar?.setVisibility(View.VISIBLE)
        progressBar?.setIndeterminate(false)
        progressBar?.max = 100
        // headline text
        val progressHead = dialog!!.findViewById(R.id.progress_head) as TextView
        progressHead.text = message
        progressHead.visibility = View.VISIBLE
        // progress text
        progressText = dialog!!.findViewById(R.id.progress_text) as TextView
        progressText?.visibility = View.VISIBLE
    }

    fun show() {
        dialog?.show()
    }

    fun close() {
        if (dialog != null) {
            dialog?.dismiss()
            dialog = null
        }
    }
}

// get list of linked phone files
fun getLinkedFilesInfo(
    context: Context,
    LinkedMediaList: MutableList<Uri>,
    pw: ProgressWindow? = null
): List<LinkedMedia.GrzThumbNail> {
    // retVal
    val retVal = mutableListOf<LinkedMedia.GrzThumbNail>()

    // create list from scratch
    for (pickerUri in LinkedMediaList) {
        // set progress
        if (pw != null) {
            (context as Activity).runOnUiThread {
                pw.incCount += 1
            }
        }

        // vars
        var mediaId = -1
        var fileName = ""
        var fileSize: Long = 0
        var fileDate = ""

        // get MediaStore ID from uri
        val uriString = pickerUri.toString()
        val id = uriString.substring(uriString.lastIndexOf("/") + 1).toInt()

        // get linked image info
        val galleryItem = GalleryInfo.getGalleryMediaInfo(context, id)
        if (galleryItem != null) {
            // MediaStore id
            mediaId = id
            // get file name
            fileName = galleryItem.absolutePath
            // get file size
            fileSize = galleryItem.size.toLong()
            // get date added
            fileDate = SimpleDateFormat("yyyyMMdd").format(Date(galleryItem.dateAdded.toLong() * 1000))
            // get thumbnail directly from uri
            var bmp = loadThumbnailFromUri(context, pickerUri)
            if (bmp == null) {
                // supposed to happen if uri is video
                bmp = ThumbnailUtils.createVideoThumbnail(File(fileName), Size(128, 128), null)
            }
            val dwb = BitmapDrawable(context.resources, bmp)
            // add to return value list retVal
            retVal.add((LinkedMedia.GrzThumbNail(pickerUri, mediaId, fileName, fileDate, dwb, null, null, null,false, fileSize)))
        } else {
            // add a broken link
            retVal.add((LinkedMedia.GrzThumbNail(pickerUri, id, uriString, "19700101", null, null, null, null,false, 0)))
        }
    }

    // sort list in descending order by date stamp
    val cmp = compareBy<LinkedMedia.GrzThumbNail> {
        LocalDate.parse(
            it.fileDate,
            DateTimeFormatter.ofPattern("yyyyMMdd")
        )
    }
    retVal.sortWith(cmp.reversed())

    // back
    return retVal
}

// get list of filenames in path, sorted descending by Date stamp
fun getFolderFiles(context: Context, path: String, sortByDate: Boolean, fileList: Array<File>, pw: ProgressWindow? = null): List<GalleryActivity.GrzThumbNail> {
    // retVal
    var retVal = mutableListOf<GalleryActivity.GrzThumbNail>()

    // either re use an already existing list, or create a new one
    if (MainActivity.appGalleryAdapter != null) {
        // re use
        retVal = MainActivity.appGalleryAdapter!!.list.toMutableList()
        // add to retVal files from fileList, which are missing in retVal
        for (f in fileList) {
            // look up f in retVal
            val itemHitNdx = retVal.indexOfFirst { it.fileName.equals(f.name) }
            // add missing files in retVal
            if (itemHitNdx == -1) {
                retVal.add((GalleryActivity.GrzThumbNail(f.name, "", null)))
            }
            // set progress
            if (pw != null) {
                (context as Activity).runOnUiThread {
                    pw.incCount += 1
                }
            }
        }
        // remove files from retVal, which are not in fileList (happens after tidy orphaned files)
        // https://stackoverflow.com/questions/48577158/remove-data-from-list-while-iterating-kotlin
        val iterator = retVal.iterator()
        while (iterator.hasNext()) {
            // get item in retVal
            val item = iterator.next()
            // look up f in fileList
            val itemHitNdx = fileList.indexOfFirst { it.name.equals(item.fileName) }
            // remove file from retVal, which has no hit in fileList
            if (itemHitNdx == -1) {
                iterator.remove()
            }
        }
        // remove elements with empty item.fileName, because they are just headers or Date stamps
        retVal.removeAll { it.fileName.isEmpty() }
    } else {
        // create list from scratch with filenames only
        for (f in fileList) {
            // set progress
            if (pw != null) {
                (context as Activity).runOnUiThread {
                    pw.incCount += 1
                }
            }
            retVal.add((GalleryActivity.GrzThumbNail(f.name, "", null, false, 0)))
        }
    }

    // the date-stamp output format
    val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    // loop retVal
    for (item in retVal) {

        // look up item.fileName in fileList
        val fileListHitNdx = fileList.indexOfFirst { it.name.equals(item.fileName) }

        // set progress
        if (pw != null) {
            (context as Activity).runOnUiThread {
                pw.incCount += 1
            }
        }

        // get file size
        var path = Paths.get(fileList[fileListHitNdx].absolutePath)
        item.fileSize = path.fileSize()

        // if retVal item does not have a valid fileDate, generate item.fileDate from item.fileName OR fileList[fileListHitNdx].absolutePath
        if (item.fileDate.isEmpty()) {
            // now parse the date from item.fileName
            var fileDateStr = ""
            var m = PATTERN.DatePattern.matcher(item.fileName) // out of experience: usual filename Pattern.compile("[_-]\\d{8}[_-]")
            if (m.find()) {
                try {
                    var group = m.group()
                    fileDateStr = group.substring(1, group.length - 1)
                    sdf.parse(fileDateStr)
                } catch (e: Exception) {
                    centeredToast(
                        contextMainActivity,
                        "GrzLog date parse: " + e.message.toString(),
                        3000
                    )
                }
            }
            // if no valid date string, get it from file as a fallback date
            if (fileDateStr.isEmpty()) {
                try {
                    var fAttr = Files.readAttributes(path, BasicFileAttributes::class.java)
                    var fDate = fAttr.creationTime()
                    var fDateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(fDate.toString())
                    fileDateStr = SimpleDateFormat("yyyyMMdd").format(fDateStr)
                } catch (e: Exception) {
                    centeredToast(
                        contextMainActivity,
                        "GrzLog filetime parse: " + e.message.toString(),
                        3000
                    )
                }
            }
            item.fileDate = fileDateStr
        }
    }

    if (sortByDate) {
        // sort list in descending order by date stamp
        val cmp = compareBy<GalleryActivity.GrzThumbNail> {
            LocalDate.parse(
                it.fileDate,
                DateTimeFormatter.ofPattern("yyyMMdd")
            )
        }
        retVal.sortWith(cmp.reversed())
    } else {
        // sort list in descending order by file size
        retVal.sortByDescending { it.fileSize }
    }

    // back
    return retVal
}

// move GrzLog gallery files from ... to ... and update DataStore attachment info
fun moveGrzLogGallery(context: Context, sourceFolder: File, destinationFolder: File, direction: AttachmentStorage.Type): MutableList<String> {
    val errorList: MutableList<String> = ArrayList()
    // destination folder
    if (!destinationFolder.exists()) {
        destinationFolder.mkdir();
    }
    // get files from source folder
    val fileList: Array<File> = sourceFolder.listFiles()
    // init progress
    var pw: ProgressWindow? = null
    var title = ""
    if (direction == AttachmentStorage.Type.PRIVATE) {
        title = context.getString(R.string.PicturesToAppGallery)
    }
    if (direction == AttachmentStorage.Type.PUBLIC) {
        title = context.getString(R.string.AppGalleryToPictures)
    }
    (context as Activity).runOnUiThread({
        pw = ProgressWindow(context, title)
        pw.absCount = fileList.size.toFloat()
        pw.dialog?.setCancelable(true)
        pw.show()
    })
    // 1st entry in errorList is total number of files to move
    errorList.add(fileList.size.toString())
    // loop file list
    for (item in fileList) {
        // build out file
        val outFile = File(destinationFolder, item.name)
        // exec copy
        val errorStr = copyFile(item, outFile, true)
        // check error
        if (errorStr.isNotEmpty()) {
            // keep name of rror file in list
            errorList.add(errorStr)
        } else {
            // if no error, delete file from GrzLog folder
            item.delete()
        }
        // update progress
        (context as Activity).runOnUiThread {
            pw!!.incCount += 1
        }
    }
    (context as Activity).runOnUiThread({
        pw!!.close()
    })
    return errorList
}
// copy file into an external folder
fun copyFile(appSourceFile: File, destinationFile: File, broadcastMediaChange: Boolean): String {
    var errorStr = ""
    try {
        // input
        var inpStream: InputStream? = FileInputStream(appSourceFile)
        // output
        var outStream: OutputStream? = FileOutputStream(destinationFile)
        // copy
        val buffer = ByteArray(1024)
        var read: Int
        if (inpStream != null) {
            while (inpStream.read(buffer).also { read = it } != -1) {
                outStream!!.write(buffer, 0, read)
            }
        }
        // finish
        if (inpStream != null) {
            inpStream.close()
        }
        inpStream = null
        outStream!!.flush()
        outStream.close()
        outStream = null
        // set file time stamp
        val date = parseFileDateFromFileName(destinationFile.name)
        val outPath = Paths.get(destinationFile.absolutePath)
        Files.setLastModifiedTime(outPath, date)
        if (broadcastMediaChange) {
            // let Gallery know about a change
            MainActivity.contextMainActivity.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destinationFile))
            )
        }
    } catch (e: Exception) {
        // add fails to error list
        errorStr = appSourceFile.name
    }
    return errorStr
}
// try to get a date out of the filename
fun parseFileDateFromFileName(fileName: String): FileTime {
    val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    var fileTime = FileTime.fromMillis(System.currentTimeMillis())
    val m = PATTERN.DatePattern.matcher(fileName)
    if (m.find()) {
        try {
            val group = m.group()
            val fileDateStr = group.substring(1, group.length - 1)
            fileTime = FileTime.fromMillis(sdf.parse(fileDateStr).time)
        } catch (e: Exception) {
            // ok to leave unhandled
        }
    }
    return fileTime
}

// get list of thumbnail images silently - called from MainActivity ideally before GalleryActivity is called
fun getAppGalleryThumbsSilent(context: Context, sortByDate: Boolean) {
    MainActivity.appGalleryScanning = true
    try {
        // GrzLog gallery can be in two different places
        val appImagesPath = MainActivity.Companion.AttachmentStorage.pathList[MainActivity.Companion.AttachmentStorage.activeType.ordinal]
        Thread {
            val fileList: Array<File> = File(appImagesPath).listFiles()
            val listGrzThumbNail = getFolderFiles(context, appImagesPath, sortByDate, fileList, null)
            MainActivity.appScanTotal = listGrzThumbNail.size
            MainActivity.appScanCurrent = 0
            var list = mutableListOf<GalleryActivity.GrzThumbNail>()
            val sdfIn = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val sdfOutDate = SimpleDateFormat("yyyy MMM dd, EEE", Locale.getDefault())
            var lastDateStamp = ""
            var index = 0
            for (item in listGrzThumbNail) {
                MainActivity.appScanCurrent += 1
                try {
                    var drawable = item.thumbNail
                    if (drawable == null) {
                        var file = File(appImagesPath, item.fileName)
                        var bmp: Bitmap? = null
                        val mimeExt = getFileExtension(item.fileName)
                        if (IMAGE_EXT.contains(mimeExt, ignoreCase = true)) {
                            bmp = ThumbnailUtils.createImageThumbnail(file, Size(128, 128), null)
                        } else {
                            if (VIDEO_EXT.contains(mimeExt, ignoreCase = true)) {
                                bmp =
                                    ThumbnailUtils.createVideoThumbnail(file, Size(128, 128), null)
                            } else {
                                if (AUDIO_EXT.contains(mimeExt, ignoreCase = true)) {
                                    bmp = ThumbnailUtils.createAudioThumbnail(
                                        file,
                                        Size(128, 128),
                                        null
                                    )
                                } else {
                                    if (mimeExt.equals("pdf", ignoreCase = true)) {
                                        bmp = (context.getDrawable(R.drawable.ic_pdf) as BitmapDrawable).bitmap
                                    } else {
                                        if (mimeExt.equals("txt", ignoreCase = true)) {
                                            bmp = (context.getDrawable(android.R.drawable.ic_dialog_email) as BitmapDrawable).bitmap
                                        }
                                    }
                                }
                            }
                        }
                        drawable = BitmapDrawable(bmp)
                    }
                    // introduce a date-stamp-header
                    if (!lastDateStamp.equals(item.fileDate)) {
                        // insert fully empty entry, the one right beneath an image
                        if (index % 2 != 0) {
                            list.add(
                                GalleryActivity.GrzThumbNail(
                                    "",
                                    "",
                                    context.getDrawable(android.R.drawable.gallery_thumb)!!
                                )
                            )
                            index++
                        }
                        // next line shall show the current date (!!the item right next to it carries a " ")
                        var date = sdfIn.parse(item.fileDate)
                        list.add(
                            GalleryActivity.GrzThumbNail(
                                "",
                                sdfOutDate.format(date),
                                context.getDrawable(android.R.drawable.gallery_thumb)!!
                            )
                        )
                        index++
                        list.add(
                            GalleryActivity.GrzThumbNail(
                                "",
                                " ",
                                context.getDrawable(android.R.drawable.gallery_thumb)!!
                            )
                        )
                        index++
                        lastDateStamp = item.fileDate
                    }
                    // set real data
                    if (drawable != null) {
                        // covers all files providing a Bitmap thumbnail
                        list.add(
                            GalleryActivity.GrzThumbNail(
                                item.fileName,
                                item.fileDate,
                                drawable,
                                false,
                                item.fileSize
                            )
                        )
                    } else {
                        // covers: pdf, txt
                        list.add(
                            GalleryActivity.GrzThumbNail(
                                item.fileName,
                                item.fileDate,
                                context.getDrawable(android.R.drawable.gallery_thumb)!!,
                                false,
                                item.fileSize
                            )
                        )
                    }
                } catch (e: Exception) {
                    list.add(
                        GalleryActivity.GrzThumbNail(
                            item.fileName,
                            item.fileDate,
                            context.getDrawable(android.R.drawable.gallery_thumb)!!,
                            false,
                            item.fileSize
                        )
                    )
                }
                // just the current index
                index++
            }
            MainActivity.appGalleryAdapter = GalleryActivity.GridAdapter(context, list.toTypedArray())
            MainActivity.appGalleryAdapter!!.notifyDataSetChanged()
            MainActivity.appGallerySortedByDate = sortByDate
            MainActivity.appGalleryScanning = false
        }.start()
    } catch (e: Exception) {
        centeredToast(contextMainActivity, "GrzLog silent: " + e.message.toString(), 3000)
    }
}

// EXIF data may contain something useful
fun getExifInterface(context: Context, uri: Uri): ExifInterface? {
    try {
        var path = uri.toString()
        if (path.startsWith("file://") && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ExifInterface(path)
        }
        if (path.startsWith("file://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            path = path.replace("file://", "content://")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (path.startsWith("content://")) {
                val inputStream = context.contentResolver.openInputStream(uri)
                var exif = ExifInterface(inputStream!!)
                return exif
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return null
}

// extract urls from string: https://stackoverflow.com/questions/5713558/detect-and-extract-url-from-a-string
fun getAllLinksFromString(text: String): ArrayList<String>? {
    val links: ArrayList<String> = ArrayList()
    // find text links
    var m = PATTERN.UrlsPattern.matcher(text)
    while (m.find()) {
        var hit = m.group()
        links.add(hit)
    }
    // find ip4:port links
    m = PATTERN.IP4PortPattern.matcher(text)
    while (m.find()) {
        var hit = m.group()
        links.add(hit)
    }
    return links
}

// https://stackoverflow.com/questions/48960945/getting-range-of-dates-from-two-different-dates
fun getDaysBetweenDates(startdate: Date, enddate: Date): List<String> {
    val dates = ArrayList<String>()
    val calendar = GregorianCalendar()
    calendar.time = startdate
    while (calendar.time.before(enddate)) {
        val result = calendar.time
        val formatter = SimpleDateFormat("yyyy-MM-dd EEE")
        val today = formatter.format(result)
        today.split("|")
        dates.add(today)
        calendar.add(Calendar.DATE, 1)
    }
    return dates
}

// get backup file - default is GrzLog.zip but QuickLogBook.zip, LogBook.zip, LogBookPro.zip are allowed for backward compatibility
fun getBackupFile(context: Context) : File? {
    val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    var appZipName = context.applicationInfo.loadLabel(context.packageManager).toString() + ".zip"
    var file : File?
    file = File(downloadDir, appZipName)
    if (!file.exists()) {
        appZipName = "LogBookPro.zip"
        file = File(downloadDir, appZipName)
        if (!file.exists()) {
            appZipName = "LogBook.zip"
            file = File(downloadDir, appZipName)
            if (!file.exists()) {
                appZipName = "QuickLogBook.zip"
                file = File(downloadDir, appZipName)
                if (!file.exists()) {
                    file = null
                }
            }
        }
    }
    return file
}

// https://stackoverflow.com/questions/59234916/how-to-convert-byte-size-into-human-readable-format-in-kotlin
fun bytesToHumanReadableSize(bytes: Double) = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1 shl 30))
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20))
    bytes >= 1 shl 10 -> "%.0f kB".format(bytes / (1 shl 10))
    else -> "$bytes bytes"
}

// inspired by https://stackoverflow.com/questions/67946980/im-trying-to-get-%d0%b0-real-traffic-stats-data-but-when-i-count-trafficstats-get
class SpeedMeter {
    private var txUptoNow: Long = 0
    fun getTxNow() : Long {
        return TrafficStats.getTotalTxBytes() - txUptoNow
    }
    fun initMeter() {
        txUptoNow = TrafficStats.getTotalTxBytes()
    }
}

// kudos: https://www.baeldung.com/kotlin/string-extract-numeric-value
fun extractNumbersUsingLoop(str: String): List<Int> {
    val numbers = mutableListOf<Int>()
    val currentNumber = StringBuilder()
    for (char in str) {
        if (char.isDigit()) {
            currentNumber.append(char)
        } else if (currentNumber.isNotEmpty()) {
            numbers.add(currentNumber.toString().toInt())
            currentNumber.clear()
        }
    }
    if (currentNumber.isNotEmpty()) {
        numbers.add(currentNumber.toString().toInt())
    }
    return numbers
}

// margin helper, kudos --> https://stackoverflow.com/questions/45411634/set-runtime-margin-to-any-view-using-kotlin
fun View.margin(left: Float? = null, top: Float? = null, right: Float? = null, bottom: Float? = null) {
    layoutParams<ViewGroup.MarginLayoutParams> {
        left?.run { leftMargin = dpToPx(this) }
        top?.run { topMargin = dpToPx(this) }
        right?.run { rightMargin = dpToPx(this) }
        bottom?.run { bottomMargin = dpToPx(this) }
    }
}
inline fun <reified T : ViewGroup.LayoutParams> View.layoutParams(block: T.() -> Unit) {
    if (layoutParams is T) block(layoutParams as T)
}
fun View.dpToPx(dp: Float): Int = context.dpToPx(dp)
fun Context.dpToPx(dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

// kudos: https://stackoverflow.com/questions/2799097/how-can-i-detect-when-an-android-application-is-running-in-the-emulator
fun isEmulator(): Boolean {
    return  (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.MODEL.contains("sdk", true)
            || Build.MODEL.contains("Emulator")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.PRODUCT.contains("sdk", true)
            || Build.PRODUCT.contains("vbox86p")
            || Build.PRODUCT.contains("emulator")
            || Build.PRODUCT.contains("simulator")
}

// kudos: https://umang91.medium.com/never-write-shell-scripts-again-use-kotlin-cb81b53ca1a1
fun executeShellCommandWithStringOutput(command: String): String {
    val process = ProcessBuilder("/bin/bash", "-c", command).start()
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    var line: String? = ""
    val builder = StringBuilder()
    while (reader.readLine().also { line = it } != null) {
        builder.append(line).append(System.getProperty("line.separator"))
    }
    // remove the extra new line added in the end while reading from the stream
    return builder.toString().trim()
}

// kudos: https://stackoverflow.com/questions/28158175/how-to-read-android-properties-with-java
fun getRoBuildFlavor(): String {
    var process: Process? = null
    try {
        process = ProcessBuilder().command("/system/bin/getprop")
            .redirectErrorStream(true).start()
    } catch (e: IOException) {
        return ""
    }
    val `in` = process!!.getInputStream()
    val bufferedReader = BufferedReader(InputStreamReader(process.getInputStream()))
    val log = java.lang.StringBuilder()
    var line: String?
    try {
        while ((bufferedReader.readLine().also { line = it }) != null) {
            if (line!!.contains("[ro.build.flavor]")) {
                log.append(line + "\n")
            }
        }
    } catch (e: IOException) {
        return ""
    }
    process.destroy()
    return log.toString()
}