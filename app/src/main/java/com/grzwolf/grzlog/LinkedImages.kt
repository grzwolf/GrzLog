package com.grzwolf.grzlog

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.grzwolf.grzlog.MainActivity.Companion.ds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale


class LinkedImages : AppCompatActivity() {

    lateinit var gridView: GridView
    var adapter : ThumbGridAdapter? = null
    var galleryMenu: Menu? = null
    lateinit var contextLinkedImages: Context

    // prepare visibility of action menu items
    lateinit var itemUpload: MenuItem
    lateinit var itemDelete: MenuItem
    lateinit var itemUsages: MenuItem
    lateinit var itemRefresh: MenuItem
    lateinit var itemToggle: MenuItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gallery_activity) // re uses Gallery layout
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // adding onBackPressed callback listener
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // needed in a sub thread
        contextLinkedImages = this

        // quit local app reminders
        MainActivity.showAppReminders = false

        // gridView is the main Gallery UI component
        gridView = findViewById(R.id.galleryList)

        // image click shall show a larger image
        gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (adapter == null) {
                return@OnItemClickListener
            }
            if (adapter!!.list[position].fileName.isEmpty()) {
                return@OnItemClickListener
            }
            val fn = adapter!!.list[position].pickerUri.toString()
            showAppLinkOrAttachment(this, "", fn)
        }

        // long press shall select image item
        gridView.setOnItemLongClickListener(AdapterView.OnItemLongClickListener { parent, view, position, id ->
            if (adapter!!.list[position].fileName.isEmpty()) {
                return@OnItemLongClickListener true
            }
            // allow multiple item selection
            adapter!!.list[position].selected = !adapter!!.list[position].selected
            // update availability signal color for menu items
            updateMenuStatus()
            true
        })

        // match font to space
        this.setTitle(Html.fromHtml("<small>" + this.title + "</small>"))

        // init adapter
        getLinkGalleryThumbs()

        // not supposed to happen: generate just dummy data
        if (adapter == null) {
            val listDummy = mutableListOf<GrzThumbNail>()
            val adapterDummy = ThumbGridAdapter(this@LinkedImages, listDummy.toTypedArray())
            gridView.setAdapter(adapterDummy)
            adapterDummy.notifyDataSetChanged()
        }
    }

    // update 'availability signal colors' = 'active status' of menu items
    fun updateMenuStatus() {
        if (adapter!!.list.any { it.selected == true }) {
            val selMap = adapter!!.list.groupingBy { it.selected == true }.eachCount()
            if (selMap.getValue(true) == 1) {
                // 1 selection --> usage search allowed
                itemUsages.icon!!.setColorFilter(
                    BlendModeColorFilter(
                        getResources().getColor(R.color.yellow),
                        BlendMode.SRC_IN
                    )
                )
            } else {
                // >1 selection --> no usage search allowed
                itemUsages.icon!!.setColorFilter(
                    BlendModeColorFilter(
                        getResources().getColor(R.color.lightgrey),
                        BlendMode.SRC_IN
                    )
                )
            }
            // any selection --> delete allowed
            itemDelete.icon!!.setColorFilter(
                BlendModeColorFilter(
                    getResources().getColor(R.color.yellow),
                    BlendMode.SRC_IN
                )
            )
            // any selection --> upload allowed
            itemUpload.icon!!.setColorFilter(
                BlendModeColorFilter(
                    getResources().getColor(R.color.yellow),
                    BlendMode.SRC_IN
                )
            )
        } else {
            // no selection --> no usage search
            itemDelete.icon!!.setColorFilter(
                BlendModeColorFilter(
                    getResources().getColor(R.color.lightgrey),
                    BlendMode.SRC_IN
                )
            )
            // no selection --> no delete
            itemUsages.icon!!.setColorFilter(
                BlendModeColorFilter(
                    getResources().getColor(R.color.lightgrey),
                    BlendMode.SRC_IN
                )
            )
            // no selection --> no upload
            itemUpload.icon!!.setColorFilter(
                BlendModeColorFilter(
                    getResources().getColor(R.color.lightgrey),
                    BlendMode.SRC_IN
                )
            )
        }
        adapter!!.notifyDataSetChanged()
    }

    // build a menu bar with options
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // inflate the menu adds items to the action bar
        menuInflater.inflate(R.menu.menu_gallery, menu)
        // needed to work in onOptionsItemSelected
        galleryMenu = menu
        // prepare visibility of action menu items
        itemUpload = galleryMenu!!.findItem(R.id.action_Payload)
        itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
        itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
        itemRefresh = galleryMenu!!.findItem(R.id.action_Refresh)
        itemToggle = galleryMenu!!.findItem(R.id.action_ToggleSelection)
        // visibility of action menu items
        itemUpload.isVisible = true
        itemDelete.isVisible = true
        itemUsages.isVisible = true
        itemToggle.isVisible = true
        itemUpload.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
        itemDelete.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
        itemUsages.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
        itemRefresh.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.yellow), BlendMode.SRC_IN))
        itemToggle.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.yellow), BlendMode.SRC_IN))
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // back to MainActivity
        if (item.itemId == android.R.id.home) {
            for (aItem in adapter!!.list) {
                aItem.selected = false
            }
            itemDelete.isVisible = false
            onBackPressed()
        }

        // toggle selection status of gallery items
        if (item.itemId == R.id.action_ToggleSelection) {
            // selection count & real entries
            var counter = 0
            var entries = 0
            // loop adapter list
            for (item in adapter!!.list) {
                item.selected = !item.selected
                if (item.fileName.isNotEmpty()) {
                    entries++
                    if (item.selected) {
                        counter++
                    }
                }
            }
            updateMenuStatus()
            // warning
            if (counter > entries / 2) {
                okBox(
                    this,
                    getString(R.string.warning),
                    getString(R.string.you_selected)
                            + counter.toString()
                            + "("
                            + entries.toString()
                            + ") "
                            + getString(R.string.items)
                )
            }
        }

        // refresh linked images data
        if (item.itemId == R.id.action_Refresh) {
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.title_linked_images),
                getString(R.string.refresh),
                {
                    getLinkGalleryThumbs()
                    updateMenuStatus()
                },
                { null }
            )
        }

        // take selection: copy linked images to GrzLog Gallery & update affected item texts in DataStore
        if (item.itemId == R.id.action_Payload) {
            if (adapter!!.list.all { it.selected == false }) {
                return super.onOptionsItemSelected(item)
            }

            // 1st Dialog: ask whether to continue at all
            var counter = 0
            var selected = 0
            for (item in adapter!!.list) {
                if (item.fileName.isNotEmpty()) {
                    counter++
                    if (item.selected) {
                        selected++
                    }
                }
            }
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.title_linked_images),
                selected.toString()
                        + "("
                        + counter.toString()
                        + ") "
                        + getString(R.string.copyToGrzLogGallery),
                {
                    //
                    // 1st Dialog runnerPositive opens a ...
                    //
                    // ... 2nd Dialog: offer image downscaling option before copy
                    var dialog: AlertDialog? = null
                    val items = arrayOf<CharSequence>(getString(R.string.match_to_phone_s_screen))
                    var checkedItems = BooleanArray(1) { true }
                    var builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    builder.setTitle(getString(R.string.scale_image))
                    builder.setMultiChoiceItems(
                        items,
                        checkedItems,
                        DialogInterface.OnMultiChoiceClickListener { dlg, which, isChecked ->
                            checkedItems[which] = isChecked
                        })
                    //
                    // 2nd Dialog OK --> handle the copy image scenario
                    //
                    builder.setPositiveButton("Ok", DialogInterface.OnClickListener { dlg, which ->
                        searchAndReplace(checkedItems[0])
                    })
                    //
                    // 2nd Dialog CANCEL --> get out
                    //
                    builder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dlg, which ->
                            return@OnClickListener
                        })
                    //
                    // 2nd Dialog HELP --> provide help info
                    //
                    builder.setNeutralButton(
                        R.string.title_activity_help,
                        DialogInterface.OnClickListener { dlg, which ->
                            centeredToast(
                                contextLinkedImages,
                                getString(R.string.reduces_data_storage_size),
                                1
                            )
                            Handler().postDelayed({
                                var dlgRestart = builder.create()
                                dlgRestart.show()
                                dlgRestart.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                            }, 300)
                        })
                    dialog = builder.create()
                    dialog.show()
                    dialog.setCanceledOnTouchOutside(false)
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                },
                //
                // 1st Dialog runnerNegative --> does nothing
                //
                { null }
            )
        }

        // take selection and releasePersistableUriPermission of affected uris
        if (item.itemId == R.id.action_Delete) {
            if (adapter!!.list.all { it.selected == false }) {
                return super.onOptionsItemSelected(item)
            }
            // selection count
            var counter = 0
            var selected = 0
            for (item in adapter!!.list) {
                if (item.fileName.isNotEmpty()) {
                    counter++
                    if (item.selected) {
                        selected++
                    }
                }
            }
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.title_linked_images),
                selected.toString()
                        + "("
                        + counter.toString()
                        + ") "
                        + getString(R.string.removeUriPermission),
                {
                    // loop adapter list
                    for (item in adapter!!.list) {
                        if (item.selected && item.fileName.isNotEmpty()) {
                            try {
                                // remove uri permission of selected list element
                                MainActivity.contextMainActivity.contentResolver.releasePersistableUriPermission(
                                    item.pickerUri!!,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: Exception) {
                                centeredToast(this,"Ups ...", 1000)
                            }
                        }
                    }
                    // update LinkedImages gallery
                    getLinkGalleryThumbs()
                    MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                    MainActivity.reReadAppFileData = true
                    updateMenuStatus()
                },
                { null }
            )
        }

        // take last selected item and show its usages in all folders
        if (item.itemId == R.id.action_Usages) {
            // anything to do ?
            if (adapter!!.list.all { it.selected == false }) {
                return super.onOptionsItemSelected(item)
            }
            // exactly 1 item selected ?
            val selMap = adapter!!.list.groupingBy { it.selected == true }.eachCount()
            if (selMap.getValue(true) != 1) {
                return super.onOptionsItemSelected(item)
            }
            // pick the 1st selected item
            var pickerUri: Uri? = null
            var searchText = ""
            for (aItem in adapter!!.list) {
                if (aItem.selected) {
                    pickerUri = aItem.pickerUri
                    searchText = aItem.fileName
                    break
                }
            }
            if (searchText.length > 0) {
                // to search in DataStore, which stores PickerUri --> so we need a "PhotoPicker Uri"
                var searchFinally = pickerUri.toString()
                // find all search hits in DataStore
                val searchHitList: MutableList<MainActivity.GlobalSearchHit> = MainActivity.findTextInDataStore(this, searchFinally, MainActivity.lvMain)
                // nothing found --> get out
                if (searchHitList.size == 0) {
                    centeredToast(this, getString(R.string.nothingFound), 3000)
                } else {
                    // render search results in its own dialog
                    MainActivity.jumpToSearchHitInFolderDialog(this, searchHitList, -1, searchFinally)
                }
            }
        }

        // standard end of fun
        return super.onOptionsItemSelected(item)
    }

    // Android back button & gesture detection
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // treat like cancel
            MainActivity.returningFromAppGallery = false
            MainActivity.returnAttachmentFromAppGallery = ""
            // return to MainActivity with "Cancel" shows either "Folder More Dialog" or "Input More Dialog"
            MainActivity.showFolderMoreDialog = false
            finish()
        }
    }

    // execute link copy to GrzLog locally & search and replace DataStore in a separate thread
    fun searchAndReplace(attachmentImageScale: Boolean) {

        // GrzLog images folder is beyond of its absolute path
        var appStoragePath = MainActivity.contextMainActivity.getExternalFilesDir(null)!!.absolutePath

        // progress related
        var success = false
        val pw = ProgressWindow(this, getString(R.string.copyLinkedImages))
        pw.dialog?.setOnDismissListener {
            if (success) {
                // write modified DataStore to disk
                MainActivity.writeAppData(appStoragePath, MainActivity.ds, MainActivity.appName)
                // update LinkedImages gallery
                getLinkGalleryThumbs()
                MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                MainActivity.reReadAppFileData = true
                updateMenuStatus()
            }
        }
        pw.show()

        // do the lengthy work in another thread
        try {
            Thread {
                // prepare progress
                pw.incCount = 0
                pw.absCount = (ds.dataSection.size).toFloat()
                pw.absFakeCount = pw.absCount

                // loop linked images adapter list
                for (item in adapter!!.list) {

                    // item shall be selected and containing a valid file name
                    if (item.selected && item.fileName.isNotEmpty()) {

                        // prepare copy uri from "Android's Photo Picker" to app
                        var appImagesPath = appStoragePath + "/Images"
                        var appImagesFolder = File(appImagesPath)
                        if (!appImagesFolder.exists()) {
                            appImagesFolder.mkdirs()
                        }
                        var appImageFileName = item.fileName.substring(item.fileName.lastIndexOf("/") + 1)
                        val appImageFullPath = appImagesPath + "/" + appImageFileName

                        // exec image copy
                        if (!copyPickerUriToAppFilesImages(
                                MainActivity.contextMainActivity,
                                item.pickerUri!!,
                                appImageFullPath
                            )
                        ) {
                            runOnUiThread {
                                centeredToast(contextLinkedImages, "Error copy Picker Uri", 1000)
                            }
                        }

                        // rescale image
                        if (attachmentImageScale) {
                            if (!resizeImageAndSave(appImageFullPath, appImageFullPath)) {
                                runOnUiThread {
                                    centeredToast(contextLinkedImages, "Error copy scaled", 1000)
                                }
                            }
                        }

                        // build search text
                        var searchText = "::::" + item.pickerUri!!.toString()
                        // build replace text
                        var replaceText = "::::/" + appImageFileName
                        // update DataStore with progress
                        if (!MainActivity.insideDataStoreSearchAndReplace(
                                searchText,
                                replaceText,
                                pw,
                                contextLinkedImages
                            )
                        ) {
                            runOnUiThread {
                                centeredToast(contextLinkedImages, "Error DataStore", 1000)
                            }
                        }

                        // remove uri permission of selected list element
                        try {
                            MainActivity.contextMainActivity.contentResolver.releasePersistableUriPermission(
                                item.pickerUri!!,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            centeredToast(contextLinkedImages, "Ups ...", 1000)
                        }
                    }
                }
                success = true
                runOnUiThread {
                    pw.close()
                }
            }.start()
        } catch (e: Exception) {
        }
    }

    // GridView adapter
    class ThumbGridAdapter(private val context: Context, var list: Array<GrzThumbNail>) : BaseAdapter() {
        var inflater: LayoutInflater? = null
        var cv: View? = null
        var tv: TextView? = null
        var iv: ImageView? = null
        override fun getCount(): Int {
            return list.size
        }
        override fun getItem(position: Int): Any? {
            return null
        }
        override fun getItemId(position: Int): Long {
            return 0
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View? {
            // this var doesn't change, no need to get again and again
            if (inflater == null) {
                inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            }
            cv = inflater!!.inflate(R.layout.gallery_item, null)
            tv = cv!!.findViewById(R.id.galleryText) as TextView
            iv = cv!!.findViewById(R.id.galleryImage) as ImageView
            // item has two options: fileName == "" indicates a date entry, which needs to be shown
            if (list[position].fileName.isEmpty()) {
                // show the date as HEADER
                tv!!.text = list[position].fileDate
                // fileDate is not empty (it is empty in case of an item right next to an image in case of a date change)
                if (!list[position].fileDate.isEmpty()) {
                    iv!!.layoutParams.height = 80
                    tv!!.setTextSize(TypedValue.COMPLEX_UNIT_SP,16f)
                    tv!!.setTextColor(Color.BLACK)
                    tv!!.gravity = Gravity.CENTER
                    tv!!.setBackgroundColor(Color.WHITE)
                    if (list[position].fileDate.equals(" ")) {
                        tv!!.setAlpha(0f)
                    }
                } else {
                    tv!!.setAlpha(0f)
                }
            } else {
                // set text and thumbnail
                tv!!.text = list[position].fileName + "\n" + bytesToHumanReadableSize(list[position].fileSize.toDouble())
                iv!!.setImageDrawable(list[position].thumbNail)
                // how to render an item depends on its selection status
                if (list[position].selected) {
                    cv!!.setBackgroundColor(Color.YELLOW)
                } else {
                    cv!!.setBackgroundColor(Color.WHITE)
                }
            }
            return cv
        }
    }

    // GrzThumbNail data set
    class GrzThumbNail(pickerUri: Uri?, mediaId: Int, fileName: String, fileDate: String, thumbNail: Drawable?, selected: Boolean = false, fileSize: Long = 0) {
        var pickerUri = pickerUri
        var mediaId   = mediaId
        var fileName  = fileName
        var fileDate  = fileDate
        var thumbNail = thumbNail
        var selected  = selected
        var fileSize  = fileSize
    }

    // show a list of thumbnail images
    fun getLinkGalleryThumbs() {
        var success = false
        var thumbsList = mutableListOf<GrzThumbNail>()
        val pw = ProgressWindow(this, getString(R.string.scanLinkedImages))
        pw.dialog?.setOnDismissListener {
            if (success) {
                adapter = ThumbGridAdapter(this@LinkedImages, thumbsList.toTypedArray())
                if (adapter != null) {
                    // appearance of action menu items
                    itemUpload.isVisible = true
                    itemDelete.isVisible = true
                    itemUsages.isVisible = true
                    itemUpload.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                    itemDelete.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                    itemUsages.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                    // update view
                    gridView.setAdapter(adapter)
                    adapter!!.notifyDataSetChanged()
                }
            }
        }
        pw.show()
        // generate thumbnails in another thread
        try {
            Thread {
                // have a granted permission list of images linked to the phone gallery
                var grantedPermissionList = MainActivity.contextMainActivity.contentResolver.getPersistedUriPermissions()
                // prepare 1st progress
                pw.incCount = 0
                var lineCount = 0
                for (dsNdx in ds.dataSection.indices) {
                    lineCount += 1
                    lineCount += ds.dataSection[dsNdx].count { c -> c == '\n' }
                }
                pw.absCount = lineCount.toFloat()
                pw.absFakeCount = -1F
                // iterate all data sections of DataStore to search for the patterns of linked images & build a list
                var neededPermissionList: MutableList<Uri> = arrayListOf()
                for (dsNdx in ds.dataSection.indices) {
                    // set progress
                    runOnUiThread {
                        pw.incCount += 1
                    }
                    // get one data section as string
                    var sectionText = ds.dataSection[dsNdx]
                    // loop the split text line
                    val textLines = sectionText.split("\\n+".toRegex()).toTypedArray()
                    for (i in textLines.indices) {
                        // set progress
                        runOnUiThread {
                            pw.incCount += 1
                        }
                        // deal with one line containing a link pattern
                        var textLine = textLines[i]
                        val m = PATTERN.UriLink.matcher(textLine)
                        if (m.find()) {
                            val lnkFull = m.group()
                            // only deal with lines containing "::::content://media/picker", which is a link to the phone gallery
                            if (lnkFull.contains("::::content://media/picker")) {
                                // get link parts: key and uri
                                val lnkStr = lnkFull.substring(1, lnkFull.length - 1)
                                try {
                                    val lnkParts = lnkStr.split("::::".toRegex()).toTypedArray()
                                    if (lnkParts != null && lnkParts.size == 2) {
                                        // get linked image uri
                                        var uri = Uri.parse(lnkParts[1])
                                        // check "needed" to be found in "granted"
                                        var found = false
                                        for (item in grantedPermissionList) {
                                            if (item.uri == uri) {
                                                // set found flag
                                                found = true
                                                // add uri to needed list if not yet contained
                                                if (!neededPermissionList.contains(uri)) {
                                                    neededPermissionList.add(uri)
                                                }
                                            }
                                        }
                                        // if not found, try to get permission & retrieve permission list again
                                        if (!found) {
                                            try {
                                                // apply for permanent read permission
                                                MainActivity.contextMainActivity.contentResolver.takePersistableUriPermission(
                                                    uri,
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                )
                                            } catch (e: SecurityException) {
                                                // leave it not handled: happens, if uri remission was removed - get it again won't fly
                                            }
                                            grantedPermissionList = MainActivity.contextMainActivity.contentResolver.getPersistedUriPermissions()
                                        }
                                    }
                                } finally {
                                }
                            }
                        }
                    }
                }

                // prepare a list of <GrzThumbNail> of linked images
                val listGrzThumbNail = getLinkedFilesInfo(this, neededPermissionList, pw)
                // prepare 2nd progress
                pw.incCount = 0
                pw.absCount = listGrzThumbNail.size.toFloat()
                pw.absFakeCount = pw.absCount
                try {
                    val sdfIn = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                    val sdfOutDate = SimpleDateFormat("yyyy MMM dd, EEE", Locale.getDefault())
                    var lastDateStamp = ""
                    var fileSize: Long = 0
                    var index = 0
                    for (item in listGrzThumbNail) {
                        // set progress
                        runOnUiThread {
                            pw.incCount += 1
                        }
                        try {
                            // now drawable
                            var drawable = item.thumbNail
                            // check date stamp: generate a date stamp header, if date changes
                            if (!lastDateStamp.equals(item.fileDate)) {
                                // insert fully empty entry, the one right beneath an image
                                if (index % 2 != 0) {
                                    thumbsList.add(
                                        LinkedImages.GrzThumbNail(
                                            null,
                                            -1,
                                            "",
                                            "",
                                            getDrawable(android.R.drawable.gallery_thumb)!!
                                        )
                                    )
                                    index++
                                }
                                // next line shall show the current date (!!the item right next to it carries a " ")
                                var date = sdfIn.parse(item.fileDate)
                                thumbsList.add(
                                    LinkedImages.GrzThumbNail(
                                        null,
                                        -1,
                                        "",
                                        sdfOutDate.format(date),
                                        getDrawable(android.R.drawable.gallery_thumb)!!
                                    )
                                )
                                index++
                                thumbsList.add(
                                    LinkedImages.GrzThumbNail(
                                        null,
                                        -1,
                                        "",
                                        " ",
                                        getDrawable(android.R.drawable.gallery_thumb)!!
                                    )
                                )
                                index++
                                lastDateStamp = item.fileDate
                            }
                            // set image thumbnail
                            if (drawable != null) {
                                // covers all files providing a Bitmap thumbnail
                                thumbsList.add(
                                    LinkedImages.GrzThumbNail(
                                        item.pickerUri,
                                        item.mediaId,
                                        item.fileName,
                                        item.fileDate,
                                        drawable,
                                        false,
                                        item.fileSize
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            thumbsList.add(
                                GrzThumbNail(
                                    null,
                                    -1,
                                    item.fileName,
                                    item.fileDate,
                                    this.getDrawable(android.R.drawable.gallery_thumb)!!,
                                    false,
                                    fileSize
                                )
                            )
                        }
                        // just the current index
                        index++
                    }
                    success = true
                } catch (ex: Exception) {
                } catch (ex: OutOfMemoryError) {
                }
                runOnUiThread {
                    pw.close()
                }
            }.start()
        } catch (e: Exception) {
        }
    }
}