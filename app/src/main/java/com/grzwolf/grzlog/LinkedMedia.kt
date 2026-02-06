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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import com.grzwolf.grzlog.MainActivity.Companion.AttachmentStorage


class LinkedMedia : AppCompatActivity() {

    lateinit var gridView: GridView
    var adapter : GridViewAdapter? = null
    var galleryMenu: Menu? = null
    lateinit var contextLinkedMedia: Context

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
        contextLinkedMedia = this

        // quit local app reminders
        MainActivity.showAppReminders = false

        // gridView is the main Gallery UI component
        gridView = findViewById(R.id.galleryList)

        // revert API36 insets for API < 35
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            gridView.margin(top = 10F)
            gridView.margin(bottom = 0F)
        }

        // image click shall show a larger image
        gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (adapter == null) {
                return@OnItemClickListener
            }
            if (adapter!!.list[position].fileName.isEmpty()) {
                return@OnItemClickListener
            }
            val fn = adapter!!.list[position].pickerUri.toString()
            var type = GalleryInfo.MediaType.IS_UNKNOWN
            val idMediaStore = fn.substring(fn.lastIndexOf("/") + 1).toInt()
            val mediaInfo = GalleryInfo.getGalleryMediaInfo(MainActivity.contextMainActivity, idMediaStore)
            if (mediaInfo != null) {
                // specific case for linked images & videos
                if (mediaInfo.type == GalleryInfo.MediaType.IS_IMAGE) {
                    type = GalleryInfo.MediaType.IS_IMAGE
                }
                if (mediaInfo.type == GalleryInfo.MediaType.IS_VIDEO) {
                    type = GalleryInfo.MediaType.IS_VIDEO
                }
            }
            showAppLinkOrAttachment(this, "", fn, type)
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
            val adapterDummy = GridViewAdapter(this@LinkedMedia, listDummy.toTypedArray())
            gridView.setAdapter(adapterDummy)
            adapterDummy.notifyDataSetChanged()
        }
    }

    // update 'availability signal colors' = 'active status' of menu items
    fun updateMenuStatus() {
        if (adapter!!.list.any { it.selected == true }) {
            val selMap = adapter!!.list.groupingBy { it.selected == true && it.pickerUri.toString().isNotEmpty() && it.fileName.isNotEmpty() }.eachCount()
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
                    val checkedItems = BooleanArray(1) { true }
                    val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
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
                                contextLinkedMedia,
                                getString(R.string.reduces_data_storage_size),
                                1
                            )
                            Handler(Looper.getMainLooper()).postDelayed({
                                val dlgRestart = builder.create()
                                dlgRestart.setOnShowListener {
                                    dlgRestart.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                                }
                                dlgRestart.show()
                                dlgRestart.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                            }, 300)
                        })
                    dialog = builder.create()
                    dialog.setOnShowListener {
                        dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
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

            twoChoicesDialog(this,
                this.getString(R.string.title_linked_images),
                this.getString(R.string.remove_links),
                this.getString(R.string.delete_links),
                this.getString(R.string.removeUriPermission),
                { // runner CANCEL
                    null
                },
                { // runner delete links in DataStore
                    decisionBox(
                        this,
                        DECISION.YESNO,
                        getString(R.string.title_linked_images),
                        getString(R.string.remove_selected_links_folder_update),
                        {
                            searchAndDestroyLinks()
                        },
                        { null }
                    )
                },
                { // runner remove Uri permissions only
                    decisionBox(
                        this,
                        DECISION.YESNO,
                        getString(R.string.title_linked_images),
                        getString(R.string.remove_selected_links_only),
                        {
                            var removedUriPermissionCount = 0
                            // loop adapter list
                            for (item in adapter!!.list) {
                                if (item.selected && item.fileName.isNotEmpty()) {
                                    try {
                                        // remove uri permission of selected list element
                                        MainActivity.contextMainActivity.contentResolver.releasePersistableUriPermission(
                                            item.pickerUri!!,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        )
                                        removedUriPermissionCount++
                                    } catch (e: Exception) {
                                        // leave un handled
                                    }
                                }
                            }
                            if (removedUriPermissionCount == 0) {
                                centeredToast(this,getString(R.string.no_permission_released), 2000)
                            }
                            // update LinkedMedia gallery
                            getLinkGalleryThumbs()
                            MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                            MainActivity.reReadAppFileData = true
                            updateMenuStatus()
                        },
                        { null }
                    )
                }
            )
        }

        // take the selected item and show its usages in all folders
        if (item.itemId == R.id.action_Usages) {
            // anything to do ?
            if (adapter!!.list.all { it.selected == false }) {
                return super.onOptionsItemSelected(item)
            }
            // exactly 1 item selected ?
            val selMap = adapter!!.list.groupingBy { it.selected == true  && it.pickerUri.toString().isNotEmpty() && it.fileName.isNotEmpty() }.eachCount()
            if (selMap.getValue(true) != 1) {
                return super.onOptionsItemSelected(item)
            }
            // pick the selected item
            var pickerUri: Uri? = null
            var searchText = ""
            for (aItem in adapter!!.list) {
                if (aItem.selected && aItem.pickerUri.toString().isNotEmpty() && aItem.fileName.isNotEmpty()) {
                    pickerUri = aItem.pickerUri
                    searchText = aItem.fileName
                    break
                }
            }
            if (searchText.length > 0) {
                // to search in DataStore, which stores PickerUri --> so we need a "PhotoPicker Uri"
                val searchFinally = pickerUri.toString()
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

        // GrzLog absolute path
        val appStoragePath = MainActivity.contextMainActivity.getExternalFilesDir(null)!!.absolutePath

        // progress related
        var countOfCopiedFiles = 0
        var success = false
        val pw = ProgressWindow(this, getString(R.string.copyLinkedMedia))
        pw.dialog?.setOnDismissListener {
            if (success && countOfCopiedFiles > 0) {
                // write modified DataStore to disk
                MainActivity.writeAppData(appStoragePath, MainActivity.ds, MainActivity.appName)
                // update LinkedMedia gallery
                getLinkGalleryThumbs()
                MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                MainActivity.reReadAppFileData = true
                updateMenuStatus()
            }
            if (success && countOfCopiedFiles == 0) {
                runOnUiThread {
                    centeredToast(contextLinkedMedia,
                        getString(R.string.non_of_the_selected_links_could_be_copied), 3000)
                }
            }
        }
        pw.show()

        // do the lengthy work in another thread
        try {
            Thread {
                // prepare progress
                pw.incCount = 0
                pw.absCount = (MainActivity.ds.dataSection.size).toFloat()
                pw.absFakeCount = pw.absCount

                // loop linked images adapter list
                for (item in adapter!!.list) {

                    // item shall be selected and containing a valid file name
                    if (item.selected && item.fileName.isNotEmpty() && !item.fileDate.equals("19700101")) {

                        // prepare copy uri from "Android's Photo Picker" to app
                        val appImagesPath = AttachmentStorage.pathList[AttachmentStorage.activeType.ordinal]
                        val appImagesFolder = File(appImagesPath)
                        if (!appImagesFolder.exists()) {
                            appImagesFolder.mkdirs()
                        }
                        val appImageFileName = item.fileName.substring(item.fileName.lastIndexOf("/") + 1)
                        val appImageFullPath = appImagesPath + "/" + appImageFileName

                        // exec image copy
                        if (!copyPickerUriToAppFilesImages(
                                MainActivity.contextMainActivity,
                                item.pickerUri!!,
                                appImageFullPath
                            )
                        ) {
                            runOnUiThread {
                                centeredToast(contextLinkedMedia, "Error copy Picker Uri", 1000)
                            }
                        }

                        // find out if video vs. image
                        var extraInfo = ""
                        val mediaInfo = GalleryInfo.getGalleryMediaInfo(MainActivity.contextMainActivity, item.mediaId)
                        if (mediaInfo != null) {
                            if (mediaInfo.type == GalleryInfo.MediaType.IS_IMAGE) {
                                extraInfo = "::::image"
                            }
                            if (mediaInfo.type == GalleryInfo.MediaType.IS_VIDEO) {
                                extraInfo = "::::video"
                            }
                        }

                        // rescale image
                        if (attachmentImageScale && mediaInfo!!.type == GalleryInfo.MediaType.IS_IMAGE) {
                            if (!resizeImageAndSave(appImageFullPath, appImageFullPath)) {
                                runOnUiThread {
                                    centeredToast(contextLinkedMedia, "Error copy scaled image", 1000)
                                }
                            }
                        }

                        // build search text
                        val searchText = "::::" + item.pickerUri!!.toString() + extraInfo
                        // build replace text
                        val replaceText = "::::/" + appImageFileName
                        // update DataStore with progress
                        if (!MainActivity.insideDataStoreSearchAndReplace(
                                searchText,
                                replaceText,
                                pw,
                                contextLinkedMedia
                            )
                        ) {
                            runOnUiThread {
                                centeredToast(contextLinkedMedia, "Error DataStore", 1000)
                            }
                        }

                        countOfCopiedFiles++

                        // remove uri permission of selected list element
                        try {
                            MainActivity.contextMainActivity.contentResolver.releasePersistableUriPermission(
                                item.pickerUri!!,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            // it is acceptable to not handle this exception
//                            runOnUiThread {
//                                centeredToast(contextLinkedMedia, "Ups ...", 1000)
//                            }
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

    // execute link removal in DataStore in a separate thread
    fun searchAndDestroyLinks() {

        // GrzLog images folder is beyond of its absolute path
        val appStoragePath = MainActivity.contextMainActivity.getExternalFilesDir(null)!!.absolutePath

        // progress related
        var countOfRemovedLinks = 0
        var success = false
        val pw = ProgressWindow(this, getString(R.string.copyLinkedMedia))
        pw.dialog?.setOnDismissListener {
            if (success && countOfRemovedLinks > 0) {
                // write modified DataStore to disk
                MainActivity.writeAppData(appStoragePath, MainActivity.ds, MainActivity.appName)
                // update LinkedMedia gallery
                getLinkGalleryThumbs()
                MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                MainActivity.reReadAppFileData = true
                updateMenuStatus()
            }
            if (success && countOfRemovedLinks == 0) {
                runOnUiThread {
                    centeredToast(contextLinkedMedia,
                        getString(R.string.non_of_the_selected_links_could_be_deleted), 3000)
                }
            }
        }
        pw.show()

        // do the lengthy work in another thread
        try {
            Thread {
                // prepare progress
                pw.incCount = 0
                pw.absCount = (MainActivity.ds.dataSection.size).toFloat()
                pw.absFakeCount = pw.absCount

                // loop linked images adapter list
                for (item in adapter!!.list) {

                    // item shall be selected and containing a valid file name
                    if (item.selected && item.fileName.isNotEmpty()) {

                        // build search text
                        val searchText = "::::" + item.pickerUri!!.toString() + "]"
                        // build replace text
                        val replaceText = ""
                        // update DataStore with progress
                        if (!MainActivity.insideDataStoreSearchAndRemoveLink(
                                searchText,
                                pw,
                                contextLinkedMedia
                            )
                        ) {
                            runOnUiThread {
                                centeredToast(contextLinkedMedia, "Error DataStore", 1000)
                            }
                        }

                        countOfRemovedLinks++

                        // remove uri permission of selected list element
                        try {
                            MainActivity.contextMainActivity.contentResolver.releasePersistableUriPermission(
                                item.pickerUri!!,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            // it is acceptable to not handle this exception
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
    class GridViewAdapter(private val context: Context, var list: Array<GrzThumbNail>) : BaseAdapter() {

        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

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

            // if possible, get view set from memory
            if (list[position].cv == null) {
                // time-consuming tasks to be stored in view holder as part of data list
                list[position].cv = inflater.inflate(R.layout.gallery_item, null)
                list[position].iv = list[position].cv!!.findViewById(R.id.galleryImage) as ImageView
                list[position].tv = list[position].cv!!.findViewById(R.id.galleryText) as TextView
            }

            // item has two options: fileName == "" indicates a date entry, which needs to be shown
            if (list[position].fileName.isEmpty()) {
                // show the date as HEADER
                list[position].tv!!.text = list[position].fileDate
                // fileDate is not empty (it is empty in case of an item right next to an image in case of a date change)
                if (!list[position].fileDate.isEmpty()) {
                    list[position].iv!!.layoutParams.height = 80
                    list[position].tv!!.setTextSize(TypedValue.COMPLEX_UNIT_SP,16f)
                    list[position].tv!!.setTextColor(Color.BLACK)
                    list[position].tv!!.gravity = Gravity.CENTER
                    list[position].tv!!.setBackgroundColor(Color.WHITE)
                    if (list[position].fileDate.equals(" ")) {
                        list[position].tv!!.setAlpha(0f)
                    }
                } else {
                    list[position].tv!!.setAlpha(0f)
                }
            } else {
                // set text and thumbnail
                list[position].tv!!.text = buildString {
                    append(list[position].fileName)
                    append("\n")
                    append(bytesToHumanReadableSize(list[position].fileSize.toDouble()))
                }
                list[position].iv!!.setImageDrawable(list[position].thumbNail)
                // how to render an item depends on its selection status
                if (list[position].selected) {
                    list[position].cv!!.setBackgroundColor(Color.YELLOW)
                } else {
                    list[position].cv!!.setBackgroundColor(Color.WHITE)
                }
            }
            return list[position].cv
        }
    }

    // GrzThumbNail data set
    class GrzThumbNail(var pickerUri: Uri?,
                       var mediaId: Int,
                       var fileName: String,
                       var fileDate: String,
                       var thumbNail: Drawable?,
                       var cv: View?,      // adapter view holder as part data set
                       var tv: TextView?,  //  - " -
                       var iv: ImageView?, //  - " -
                       var selected: Boolean = false,
                       var fileSize: Long = 0)
    {}

    // show a list of thumbnail images
    fun getLinkGalleryThumbs() {
        var success = false
        val thumbsList = mutableListOf<GrzThumbNail>()
        val pw = ProgressWindow(this, getString(R.string.scanLinkedMedia))
        pw.dialog?.setOnDismissListener {
            if (success) {
                adapter = GridViewAdapter(this@LinkedMedia, thumbsList.toTypedArray())
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
                for (dsNdx in MainActivity.ds.dataSection.indices) {
                    lineCount += 1
                    lineCount += MainActivity.ds.dataSection[dsNdx].count { c -> c == '\n' }
                }
                pw.absCount = lineCount.toFloat()
                pw.absFakeCount = -1F
                // iterate all data sections of DataStore to search for the patterns of linked images & build a list
                val neededPermissionList: MutableList<Uri> = arrayListOf()
                for (dsNdx in MainActivity.ds.dataSection.indices) {
                    // set progress
                    runOnUiThread {
                        pw.incCount += 1
                    }
                    // get one data section as string
                    val sectionText = MainActivity.ds.dataSection[dsNdx]
                    // loop the split text line
                    val textLines = sectionText.split("\\n+".toRegex()).toTypedArray()
                    for (i in textLines.indices) {
                        // set progress
                        runOnUiThread {
                            pw.incCount += 1
                        }
                        // deal with one line containing a link pattern
                        val textLine = textLines[i]
                        val m = PATTERN.UriLink.matcher(textLine)
                        if (m.find()) {
                            val lnkFull = m.group()
                            // only deal with lines containing "::::content://media", which is a link to the phone gallery
                            // API<35 might provide a shorter version "content://media" vs. "content://media/picker"
                            if (lnkFull.contains("::::content://media")) {
                                // get link parts: key and uri
                                val lnkStr = lnkFull.substring(1, lnkFull.length - 1)
                                try {
                                    val lnkParts = lnkStr.split("::::".toRegex()).toTypedArray()
                                    if (lnkParts != null && lnkParts.size >= 2) {
                                        // get linked image uri
                                        val uri = Uri.parse(lnkParts[1])
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
                                        // OR build neededPermissionList in an alternative way
                                        if (!found) {
                                            try {
                                                // apply for permanent read permission
                                                MainActivity.contextMainActivity.contentResolver.takePersistableUriPermission(
                                                    uri,
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                )
                                                grantedPermissionList = MainActivity.contextMainActivity.contentResolver.getPersistedUriPermissions()
                                            } catch (e: SecurityException) {
                                                // if this happens, build neededPermissionList in an alternative way
                                                if (!neededPermissionList.contains(uri)) {
                                                    neededPermissionList.add(uri)
                                                }
                                            }
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
                    val fileSize: Long = 0
                    var index = 0
                    for (item in listGrzThumbNail) {
                        // set progress
                        runOnUiThread {
                            pw.incCount += 1
                        }
                        try {
                            // now drawable
                            val drawable = item.thumbNail
                            // check date stamp: generate a date stamp header, if date changes
                            if (!lastDateStamp.equals(item.fileDate)) {
                                // insert fully empty entry, the one right beneath an image
                                if (index % 2 != 0) {
                                    thumbsList.add(
                                        LinkedMedia.GrzThumbNail(
                                            null,
                                            -1,
                                            "",
                                            "",
                                            getDrawable(android.R.drawable.gallery_thumb)!!,
                                            null,
                                            null,
                                            null
                                        )
                                    )
                                    index++
                                }
                                // next line shall show the current date (!!the item right next to it carries a " ")
                                val date = sdfIn.parse(item.fileDate)
                                thumbsList.add(
                                    LinkedMedia.GrzThumbNail(
                                        null,
                                        -1,
                                        "",
                                        sdfOutDate.format(date),
                                        getDrawable(android.R.drawable.gallery_thumb)!!,
                                        null,
                                        null,
                                        null
                                    )
                                )
                                index++
                                thumbsList.add(
                                    LinkedMedia.GrzThumbNail(
                                        null,
                                        -1,
                                        "",
                                        " ",
                                        getDrawable(android.R.drawable.gallery_thumb)!!,
                                        null,
                                        null,
                                        null
                                    )
                                )
                                index++
                                lastDateStamp = item.fileDate
                            }
                            // set image thumbnail: covers all files providing a Bitmap thumbnail
                            thumbsList.add(
                                LinkedMedia.GrzThumbNail(
                                    item.pickerUri,
                                    item.mediaId,
                                    item.fileName,
                                    item.fileDate,
                                    if (drawable != null) drawable else this.getDrawable(android.R.drawable.gallery_thumb)!!,
                                    null,
                                    null,
                                    null,
                                    false,
                                    item.fileSize
                                )
                            )
                        } catch (e: Exception) {
                            thumbsList.add(
                                GrzThumbNail(
                                    null,
                                    -1,
                                    item.fileName,
                                    item.fileDate,
                                    this.getDrawable(android.R.drawable.gallery_thumb)!!,
                                    null,
                                    null,
                                    null,
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