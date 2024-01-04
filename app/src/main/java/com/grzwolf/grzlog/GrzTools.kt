package com.grzwolf.grzlog

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.Window
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.grzwolf.grzlog.MainActivity.Companion.contextMainActivity
import java.io.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


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
    maxProgress: Int
): Boolean {
    val BUFFER = 2048
    try {
        var origin: BufferedInputStream? = null
        // need to make sure, a real backup is not interrupted
        val file = File("$outFolder/$zipName" + "_part")
        val dest = FileOutputStream(file)
        val out = ZipOutputStream(BufferedOutputStream(dest))
        val data = ByteArray(BUFFER)
        val subDir = File(srcFolder)
        val subdirList = subDir.list()
        subdirList!!.forEach { sd ->
            // get a list of files from current directory
            val f = File("$srcFolder/$sd")
            if (f.isDirectory) {
                val files = f.list()
                for (i: Int in files?.indices!!) {
                    // set progress via pw in foreground
                    if (pw != null) {
                        (context as Activity).runOnUiThread(Runnable {
                            pw.incCount++
                        })
                    }
                    // set progress via nm in notification bar
                    if (nm != null) {
                        with(nm) {
                            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                (context as Activity).runOnUiThread(Runnable {
                                    n!!.setContentText(i.toString() + "(" + maxProgress.toString() + ")")
                                       .setProgress(maxProgress, i, false)
                                    notify(1, n.build())
                                })
                            }
                        }
                    }
                    // streams
                    val fis = FileInputStream(srcFolder + "/" + sd + "/" + files[i])
                    origin = BufferedInputStream(fis, BUFFER)
                    val entry = ZipEntry(sd + "/" + files[i])
                    val fi = File(srcFolder + "/" + sd + "/" + files[i])
                    entry.time = fi.lastModified()
                    out.putNextEntry(entry)
                    var count: Int
                    while (origin!!.read(data, 0, BUFFER).also { count = it } != -1) {
                        out.write(data, 0, count)
                        out.flush()
                    }
                }
            } else {
                val fis = FileInputStream(f)
                origin = BufferedInputStream(fis, BUFFER)
                val entry = ZipEntry(sd)
                entry.time = f.lastModified()
                out.putNextEntry(entry)
                var count: Int
                while (origin!!.read(data, 0, BUFFER).also { count = it } != -1) {
                    out.write(data, 0, count)
                    out.flush()
                }
            }
        }
        origin!!.close()
        out.flush()
        out.close()
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
        if (parts[i] == key) {
            // we need to reject the first match and add the folder to DataStore at the next match
            if (ndx > 0) {
                dataStore.dataSection.add(collector)
            }
            ndx++
            collector = ""
            continue
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
    } catch (e: IOException) {
        e.printStackTrace()
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
    // folder text data
    for (i in 0 until DataStore.SECTIONS_COUNT) {
        if (i >= ds.dataSection.size) {
            break
        }
        txt += "[[" + i + "]]\n"
        txt += ds.dataSection[i]
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

// convert a date string "yyyy-MM-dd" into Name Day of Week
fun dayNameOfWeek(dateStr: String?): String {
    val date: Date
    val format: DateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    date = try {
        format.parse(dateStr.toString()) as Date
    } catch (ex: Exception) {
        Date()
    }
    return SimpleDateFormat(" EEE", Locale.ENGLISH).format(date)
}

// multipurpose AlertBuilder dialog boxes
fun okBox(context: Context?, title: String?, message: String) {
    var builder: AlertDialog.Builder?
    builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    } else {
        AlertDialog.Builder(context)
    }
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    builder.setPositiveButton(
        R.string.ok,
        DialogInterface.OnClickListener { dialog, which -> dialog.dismiss() })
    try {
        builder.show()
    } catch (e: Exception) {
    }
}

fun okBox(context: Context?, title: String?, message: String, runnerOk: Runnable?) {
    var builder: AlertDialog.Builder?
    builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    } else {
        AlertDialog.Builder(context)
    }
    builder.setTitle(title)
    builder.setMessage("\n" + message)
    builder.setPositiveButton(R.string.ok, DialogInterface.OnClickListener { dialog, which ->
        runnerOk?.run()
        dialog.dismiss()
    })
    try {
        builder.show()
    } catch (e: Exception) {
    }
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
    var builder: AlertDialog.Builder?
    builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    } else {
        AlertDialog.Builder(context)
    }
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
        builder.show()
    } catch (e: Exception) {
    }
}

// do not show again alert box
fun dontShowAgain(context: Context, title: String?, sharedPreferencesString: String?) {
    val sharedPref = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    if (!sharedPref.getBoolean(sharedPreferencesString, true)) {
        return
    }
    val items = arrayOf<CharSequence>(context.getString(R.string.dontShowAgain))
    var builder: AlertDialog.Builder?
    builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
    } else {
        AlertDialog.Builder(context)
    }
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
    builder.create().show()
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
        toast!!.setGravity(Gravity.CENTER, 0, 0)
        toast!!.duration = duration
        toast!!.view = layout
        toast!!.show()
    }

    @JvmStatic
    fun cancel() {
        suppressToast = true
        if (toast != null) {
            toast!!.cancel()
        }
        Handler().postDelayed({
            toast = null
            suppressToast = false
        }, 1000)
    }
}

// https://stackoverflow.com/questions/59351273/how-to-get-the-mimetype-of-a-file-with-special-character-in-android
fun getFileExtension(fileName: String?): String {
    val encoded: String?
    encoded = try {
        URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    } catch (e: UnsupportedEncodingException) {
        fileName
    }
    return MimeTypeMap.getFileExtensionFromUrl(encoded).lowercase(Locale.getDefault())
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
        // no close if click outside
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
        textTv.text = imagePath
        // button close handler
        val closeBtn = dialog.findViewById(R.id.btnCloseImgPop) as Button
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }
        // max allowed dialog dimensions based on display
        val widthMax = context.resources.displayMetrics.widthPixels
        var heightMax = context.resources.displayMetrics.heightPixels
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
        val butHeight = closeBtn.layoutParams.height
        var spaceTitleTextButton = (titHeight + txtHeight + butHeight) * 1.5f

        // calc space in pix for title, text, button, anxiety gauge
// TBD: ok Emulator dlgHeight - ivHeight + 100 -- FAIL Pixel 5 Android 12
//            var pixTitleTextButton = dlgHeight - ivHeight + 100
        // final image height based on dialog width and image aspect ratio with a max limit at dialog height - title, text, button
        var heightImg = Math.min((dlgHeight - spaceTitleTextButton).toFloat(), wantImgHeight).toInt()
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

    // at the beginning, Glide is used to get the image - only to learn its dimensions to obtain the aspect ratio
    Glide
        .with(context)
        .load(imageUri.path)
        .override(500, 500) // keeps aspect ratio, but drastically reduces load time
        .addListener(object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                okBox(context, context.getString(R.string.FileNotFound), imageUri.path.toString())
                return false
            }

            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                if (resource != null) {
                    // in the 2nd step, the dialog dimensions are adjusted and the image is rendered
                    show(resource)
                } else {
                    okBox(context, context.getString(R.string.FileNotFound), imageUri.path.toString())
                }
                return false
            }
        })
        .preload()

}

// show image in default gallery viewer: https://stackoverflow.com/questions/13209494/how-to-get-the-full-file-path-from-uri
fun showImageInAndroidGalleryViewer(context: Context, imageUri: Uri) {
    // get ext
    var type = "image/*"
    var mimeExt = getFileExtension(imageUri.path)
    if (mimeExt.equals("gif", ignoreCase = true)) {
        type = "image/gif"
    }
    // convert
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
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setDataAndType(uriFile, type)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        // run Android gallery viewer
        context.startActivity(intent)
    } catch (e: Exception) {
        okBox(context, "Ups", e.message!!)
    }
}

// pretty tricky way to get a file out of an uri
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

// show linked attachment
fun showAppLinkOrAttachment(context: Context, title: String, fileName: String?) {
    // sake of mind
    if (fileName!!.isEmpty()) {
        return
    }

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
        val builder = CustomTabsIntent.Builder()
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(context, uri)
        return
    }

    // only deal with app local attachments
    var appAttachmentStoragePath = context.getExternalFilesDir(null)!!.absolutePath + "/Images"
    var fullFileName = fileName
    if (fileName.startsWith("file") == false) {
        fullFileName = "file://" + appAttachmentStoragePath + fileName
    }

    // parse uri name text
    val uri = Uri.parse(fullFileName)

    // MIME type file extension
    val mimeExt = getFileExtension(uri.toString())

    // show images: 1st in a popup dialog, from there in default images viewer (if clicked)
    if (IMAGE_EXT.contains(mimeExt, ignoreCase = true)) {
        showImagePopup(context, fullFileName, title, uri)
        return
    }

    // common intent preparations
    val file = getFileFromUri(context, uri)
    if (file == null) {
        okBox(context, context.getString(R.string.FileNotFound), fullFileName)
        return
    }
    val uriFile = FileProvider.getUriForFile(
        context,
        BuildConfig.APPLICATION_ID + ".provider",
        file
    )
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
    var incCount = 0 // if total event number is large, incCount reduces the the progress bar render load by 1% steps
        set(value) {
            field = value
            eventCollector += 1f
            if (eventCollector >= onePercentTrigger) {
                progressBar?.progress = progressBar?.progress?.inc()!!
                progressText?.text = progressBar?.progress.toString() + "%      " + field.toString() + "/" + absCount.toInt().toString()
                eventCollector = eventCollector - onePercentTrigger
            }
        }
    var curCount = 0 // direct render a provided event
        set(value) {
            field = value
            var pct = (100.0f * field.toFloat() / absCount).toInt()
            progressBar?.progress = pct
            progressText?.text = pct.toString() + "%      " + field.toString() + "/" + absCount.toInt().toString()
        }

    init {
        // dialog
        dialog = Dialog(context)
        dialog!!.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog!!.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog!!.setContentView(R.layout.layout_progress_dialog)
        dialog!!.setCancelable(false)
        dialog!!.setCanceledOnTouchOutside(false)
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
        dialog!!.show()
    }

    fun close() {
        if (dialog != null) {
            dialog!!.dismiss()
            dialog = null
        }
    }
}

// get list of filenames in path, sorted descending by Date stamp
fun getFolderFiles(path: String): List<GalleryActivity.GrzThumbNail> {
    // retVal
    var retVal = mutableListOf<GalleryActivity.GrzThumbNail>()

    // attachment dir
    val attDir = File(path)
    if (attDir.isDirectory) {
        // make a list of files from the given dir
        val fileList: Array<File> = attDir.listFiles()

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
                retVal.add((GalleryActivity.GrzThumbNail(f.name, "", null)))
            }
        }

        // the date-stamp output format
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

        // loop loop retVal
        for (item in retVal) {

            // look up item.fileName in fileList
            val fileListHitNdx = fileList.indexOfFirst { it.name.equals(item.fileName) }

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
                        centeredToast(contextMainActivity, "GrzLog date parse: " + e.message.toString(), 3000)
                    }
                }
                // if no valid date string, get it from file as a fallback date
                if (fileDateStr.isEmpty()) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            var path = Paths.get(fileList[fileListHitNdx].absolutePath)
                            var fAttr = Files.readAttributes(path, BasicFileAttributes::class.java)
                            var fDate = fAttr.creationTime()
                            var fDateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(fDate.toString())
                            fileDateStr = SimpleDateFormat("yyyyMMdd").format(fDateStr)
                        } else {
                            var fDate = Date(fileList[fileListHitNdx].lastModified())
                            var fDateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(fDate.toString())
                            fileDateStr = SimpleDateFormat("yyyyMMdd").format(fDateStr)
                        }
                    } catch (e: Exception) {
                        centeredToast(contextMainActivity, "GrzLog filetime parse: " + e.message.toString(), 3000)
                    }
                }
                item.fileDate = fileDateStr
            }
        }
    }
    // sort list in descending order by date stamp
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val cmp = compareBy<GalleryActivity.GrzThumbNail> { LocalDate.parse(it.fileDate, DateTimeFormatter.ofPattern("yyyMMdd")) }
        retVal.sortWith(cmp.reversed())
    }
    // back
    return retVal
}

// get list of thumbnail images silently - called from MainActivity ideally before GalleryActivity is called
fun getAppGalleryThumbsSilent(context: Context) {
    try {
        Thread {
            val appImagesPath = context.getExternalFilesDir(null)!!.absolutePath + "/Images/"
            val listGrzThumbNail = getFolderFiles(appImagesPath)
            MainActivity.appScanTotal = listGrzThumbNail.size
            MainActivity.appScanCurrent = 0
            MainActivity.appGalleryScanning = true
            var list = mutableListOf<GalleryActivity.GrzThumbNail>()
            val sdfIn = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val sdfOutDate = SimpleDateFormat("yyyy MMM dd, EEE", Locale.getDefault())
            var lastDateStamp = ""
            var index = 0
            for (item in listGrzThumbNail) {
                MainActivity.appScanCurrent += 1
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            var drawable = item.thumbNail
                            if ( drawable == null) {
                                var file = File(appImagesPath + item.fileName)
                                var bmp: Bitmap? = null
                                val mimeExt = getFileExtension(item.fileName)
                                if (IMAGE_EXT.contains(mimeExt, ignoreCase = true)) {
                                    bmp = ThumbnailUtils.createImageThumbnail(file, Size(128, 128), null)
                                } else {
                                    if (VIDEO_EXT.contains(mimeExt, ignoreCase = true)) {
                                        bmp = ThumbnailUtils.createVideoThumbnail(file, Size(128, 128), null)
                                    } else {
                                        if (AUDIO_EXT.contains(mimeExt, ignoreCase = true)) {
                                            bmp = ThumbnailUtils.createAudioThumbnail(file, Size(128, 128), null)
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
                                    list.add(GalleryActivity.GrzThumbNail("", "", context.getDrawable(android.R.drawable.gallery_thumb)!!))
                                    index++
                                }
                                // next line shall show the current date (!!the item right next to it carries a " ")
                                var date = sdfIn.parse(item.fileDate)
                                list.add(GalleryActivity.GrzThumbNail("", sdfOutDate.format(date), context.getDrawable(android.R.drawable.gallery_thumb)!!))
                                index++
                                list.add(GalleryActivity.GrzThumbNail("", " ", context.getDrawable(android.R.drawable.gallery_thumb)!!))
                                index++
                                lastDateStamp = item.fileDate
                            }
                            // set real data
                            if (drawable != null) {
                                // covers all files providing a Bitmap thumbnail
                                list.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, drawable))
                            } else {
                                // covers: pdf, txt
                                list.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, context.getDrawable(android.R.drawable.gallery_thumb)!!))
                            }
                        } catch (e: Exception) {
                            list.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, context.getDrawable(android.R.drawable.gallery_thumb)!!))
                        }
                    } else {
                        list.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, context.getDrawable(android.R.drawable.gallery_thumb)!!))
                    }
                } else {
                    list.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, ContextCompat.getDrawable(context, android.R.drawable.gallery_thumb)!!))
                }
                // just the current index
                index++
            }
            MainActivity.appGalleryAdapter = GalleryActivity.ThumbGridAdapter(context, list.toTypedArray())
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