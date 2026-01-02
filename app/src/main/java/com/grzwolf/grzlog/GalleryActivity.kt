package com.grzwolf.grzlog

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Bundle
import android.text.Html
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.io.path.fileSize


class GalleryActivity : AppCompatActivity() {

    lateinit var gridView: GridView
    var adapter : ThumbGridAdapter? = null
    var galleryMenu: Menu? = null
    var returnPayload = true
    var showOrphans = false
    var stringOrphans: ArrayList<String>? = null
    var sortByDate: Boolean = false

    var prevSelGridItem = -1
    var gridItemSelected = false

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gallery_activity)
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // adding onBackPressed callback listener
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // quit local app reminders
        MainActivity.showAppReminders = false

        // return a selected attachment
        if (savedInstanceState == null) {
            val extras = intent.extras
            if (extras == null) {
                returnPayload = true
                showOrphans = false
                stringOrphans = null
                sortByDate = true
            } else {
                returnPayload = extras.getBoolean("ReturnPayload")
                showOrphans = extras.getBoolean("ShowOrphans")
                stringOrphans = extras.getStringArrayList("StringOrphans")
                sortByDate = extras.getBoolean("SortByDate")
            }
        } else {
            returnPayload = true
        }
        // gridView is the main Gallery UI component
        gridView = findViewById(R.id.galleryList)
        // click shall provide a larger image
        gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (adapter == null) {
                return@OnItemClickListener
            }
            if (adapter!!.list[position].fileName.isEmpty()) {
                return@OnItemClickListener
            }
            val fn = "/" + adapter!!.list[position].fileName
            showAppLinkOrAttachment(this, "", fn)
        }
        // long press shall select item
        gridView.setOnItemLongClickListener(AdapterView.OnItemLongClickListener { parent, view, position, id ->
            if (adapter!!.list[position].fileName.isEmpty()) {
                return@OnItemLongClickListener true
            }
            // either have one item selected as payload or allow multiple item selection to be able to delete it/them
            if (returnPayload) {
                // select one item as later payload
                if (prevSelGridItem != position) {
                    adapter!!.selGridItemChk = true
                } else {
                    adapter!!.selGridItemChk = !adapter!!.selGridItemChk
                }
                adapter!!.selGridItemPos = position
                adapter!!.notifyDataSetChanged()
                prevSelGridItem = position
                gridItemSelected = adapter!!.selGridItemChk
                // change active color of upload icon
                val itemUpload = galleryMenu!!.findItem(R.id.action_Payload)
                if (gridItemSelected) {
                    // individual action bar icon color: https://stackoverflow.com/questions/60412934/drawable-setcolorfilter-marked-as-deprecated
                    itemUpload.icon!!.setColorFilter(
                        BlendModeColorFilter(
                            getResources().getColor(R.color.yellow),
                            BlendMode.SRC_IN
                        )
                    )
                } else {
                    itemUpload.icon!!.setColorFilter(
                        BlendModeColorFilter(
                            getResources().getColor(R.color.lightgrey),
                            BlendMode.SRC_IN
                        )
                    )
                }
            } else {
                // allow multiple item selection
                adapter!!.list[position].selected = !adapter!!.list[position].selected
                // change active color of usages & delete icons
                val itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
                val itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
                // update availability signal color for menu items
                updateMenuStatus()
            }
            true
        })
        // GrzLog gallery allows to show orphans only generated on the fly
        if (showOrphans) {
            // modify Activity title
            this.setTitle(getString(R.string.grzlog_gallery_orphaned_items))
            // generate list of thumbnails from stringOrphans if showOrphans is set
            getAppGalleryThumbs(false)
        } else {
            // match font to space
            this.setTitle(Html.fromHtml("<small>" + this.title + "</small>"))
            // MainActivity silently fills full GrzLog gallery adapter data in background
            adapter = MainActivity.appGalleryAdapter
            // check need for reload
            if (MainActivity.appGallerySortedByDate != sortByDate || MainActivity.appGalleryAdapter == null) {
                prevSelGridItem = -1
                gridItemSelected = false
                getAppGalleryThumbs(sortByDate)
                MainActivity.appGallerySortedByDate = sortByDate
            }
            // only use existing adapter data for full gallery
            if (adapter != null) {
                adapter!!.selGridItemChk = false
                adapter!!.selGridItemPos = -1
                gridView.setAdapter(adapter)
                adapter!!.notifyDataSetChanged()
            }
        }
        // not supposed to happen: generate just dummy data
        if (adapter == null) {
            val listDummy = mutableListOf<GrzThumbNail>()
            val adapterDummy = ThumbGridAdapter(this@GalleryActivity, listDummy.toTypedArray())
            gridView.setAdapter(adapterDummy)
            adapterDummy.notifyDataSetChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        // do not care about orphans
        if (!showOrphans) {
            // GrzLog gallery adapter might have changed and GalleryActivity bypassed onCreate
            if (MainActivity.appGalleryAdapter == null || adapter == null) {
                getAppGalleryThumbs(true)
            }
        }
    }

    // update 'availability signal colors' = 'active status' of menu items
    fun updateMenuStatus() {
        var itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
        var itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
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
        } else {
            // no selection --> no usage search + no delete
            itemDelete.icon!!.setColorFilter(
                BlendModeColorFilter(
                    getResources().getColor(R.color.lightgrey),
                    BlendMode.SRC_IN
                )
            )
            itemUsages.icon!!.setColorFilter(
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
        // visibility of action menu items
        val itemUpload = galleryMenu!!.findItem(R.id.action_Payload)
        val itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
        val itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
        val itemRefresh = galleryMenu!!.findItem(R.id.action_Refresh)
        val itemToggle = galleryMenu!!.findItem(R.id.action_ToggleSelection)
        itemUpload.isVisible = returnPayload
        itemDelete.isVisible = !returnPayload
        itemUsages.isVisible = !returnPayload
        itemToggle.isVisible = !returnPayload
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
            val mItem = galleryMenu!!.findItem(R.id.action_Delete)
            mItem.isVisible = false
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
            adapter!!.notifyDataSetChanged()
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
        // refresh gallery data
        if (item.itemId == R.id.action_Refresh) {
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.appGalleryData),
                getString(R.string.refresh),
                {
                    prevSelGridItem = -1
                    gridItemSelected = false
                    adapter!!.selGridItemChk = false
                    adapter!!.selGridItemPos = -1
                    getAppGalleryThumbs(sortByDate)
                    adapter!!.notifyDataSetChanged()
                    updateMenuStatus()
                },
                { null }
            )
        }
        // take selection and go back to MainActivity
        if (item.itemId == R.id.action_Payload) {
            if (prevSelGridItem != -1 && gridItemSelected) {
                var fn = adapter!!.list[prevSelGridItem].fileName
                val appImagesPath = applicationContext.getExternalFilesDir(null)!!.absolutePath + "/Images/"
                val fullFilePath = appImagesPath + fn
                MainActivity.returnAttachmentFromAppGallery = fullFilePath
                MainActivity.returningFromAppGallery = true
                prevSelGridItem = -1
                gridItemSelected = false
                adapter!!.selGridItemChk = false
                adapter!!.selGridItemPos = -1
                finish()
            }
        }
        // take selection and delete files
        if (item.itemId == R.id.action_Delete) {
            if (adapter!!.list.all { it.selected == false }) {
                return super.onOptionsItemSelected(item)
            }
            // selection count
            var counter = 0
            for (item in adapter!!.list) {
                if (item.selected) {
                    counter++
                }
            }
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.appGalleryData),
                counter.toString()
                        + "("
                        + adapter!!.list.size.toString()
                        + ") "
                        + getString(R.string.deleteSelectedItemsFromApp),
                {
                    // loop adapter list
                    for (item in adapter!!.list) {
                        if (item.selected) {
                            // delete selected list element
                            val appImagesPath = applicationContext.getExternalFilesDir(null)!!.absolutePath + "/Images/"
                            val fullFilePath = appImagesPath + item.fileName
                            val delFile = File(fullFilePath)
                            if (delFile.exists()) {
                                delFile.delete()
                            }
                            // in case of dealing with orphans, delete corresponding entry too
                            if (stringOrphans != null) {
                                stringOrphans!!.remove(item.fileName)
                            }
                        }
                    }
                    // update GrzLog gallery
                    prevSelGridItem = -1
                    gridItemSelected = false
                    adapter!!.selGridItemChk = false
                    adapter!!.selGridItemPos = -1
                    getAppGalleryThumbs(sortByDate)
                    MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                    MainActivity.reReadAppFileData = true
                    adapter!!.notifyDataSetChanged()
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
            // pick the 1 selected item
            var searchText = ""
            for (aItem in adapter!!.list) {
                if (aItem.selected) {
                    searchText = aItem.fileName
                }
            }
            if (searchText.length > 0) {
                // find all item hits in DataStore
                val searchHitList: MutableList<MainActivity.GlobalSearchHit> = MainActivity.findTextInDataStore(this, searchText, MainActivity.lvMain)
                // nothing found --> get out
                if (searchHitList.size == 0) {
                    centeredToast(this, getString(R.string.nothingFound), 3000)
                } else {
                    // render search results in its own dialog
                    MainActivity.jumpToSearchHitInFolderDialog(this, searchHitList, -1, searchText)
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // Android back button & gesture detection
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // treat like cancel
            MainActivity.returningFromAppGallery = true
            MainActivity.returnAttachmentFromAppGallery = ""
            // return to MainActivity with "Cancel" shows either "Folder More Dialog" or "Input More Dialog"
            MainActivity.showFolderMoreDialog = !returnPayload
            finish()
        }
    }

    // GridView adapter
    class ThumbGridAdapter(private val context: Context, var list: Array<GrzThumbNail>) : BaseAdapter() {
        var inflater: LayoutInflater? = null
        var cv: View? = null
        var tv: TextView? = null
        var iv: ImageView? = null
        var selGridItemChk: Boolean = false
        var selGridItemPos: Int = -1
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
            if (inflater == null) {
                inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            }
            cv = inflater!!.inflate(R.layout.gallery_item, null)
            tv = cv!!.findViewById(R.id.galleryText) as TextView
            iv = cv!!.findViewById(R.id.galleryImage) as ImageView
            // item has two options: fileName == "" indicates a date entry, which needs to be shown
            if (list[position].fileName.isEmpty()) {
                // show the date as sort of HEADER
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
                // how to render an item depends on its selection status: single payload selection or multiple delete selection
                if ((position == selGridItemPos) && selGridItemChk) {
                    // handle item as payload select status, if selGridItemChk is true
                    cv!!.setBackgroundColor(Color.YELLOW)
                } else {
                    // treat multiple item selection for deletion
                    if (list[position].selected) {
                        cv!!.setBackgroundColor(Color.YELLOW)
                    } else {
                        cv!!.setBackgroundColor(Color.WHITE)
                    }
                }
            }
            return cv
        }
    }

    // data set consisting of filename and bitmap
    class GrzThumbNail constructor(fileName: String, fileDate: String, thumbNail: Drawable?, selected: Boolean = false, fileSize: Long = 0) {
        var fileName = fileName
        var fileDate = fileDate
        var thumbNail = thumbNail
        var selected = selected
        var fileSize = fileSize
    }

    // get list of thumbnail images
    fun getAppGalleryThumbs(sortByDate: Boolean, context: Context = this) {

        // very special handling in case of orphans
        if (showOrphans) {
            // create list from scratch
            var thumbsList = mutableListOf<GrzThumbNail>()
            for (str in stringOrphans!!) {
                var drawable: BitmapDrawable? = null
                var bmp: Bitmap? = null
                var file = File(applicationContext.getExternalFilesDir(null)!!.absolutePath + "/Images/" + str)
                val mimeExt = getFileExtension(str)
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
                                bmp = (resources.getDrawable(R.drawable.ic_pdf) as BitmapDrawable).bitmap
                            } else {
                                if (mimeExt.equals("txt", ignoreCase = true)) {
                                    bmp = (resources.getDrawable(android.R.drawable.ic_dialog_email) as BitmapDrawable).bitmap
                                }
                            }
                        }
                    }
                }
                if (bmp != null) {
                    drawable = BitmapDrawable(bmp)
                    thumbsList.add(GalleryActivity.GrzThumbNail(str, "", drawable))
                } else {
                    thumbsList.add(GalleryActivity.GrzThumbNail(str, "", null))
                }
            }
            // set adapter
            adapter = ThumbGridAdapter(context, thumbsList.toTypedArray())
            if (adapter != null) {
                // update view
                gridView.setAdapter(adapter)
                adapter!!.notifyDataSetChanged()
            }
            return
        }

        // show by date, show by size, get payload, delete, usage
        var success = false
        var thumbsList = mutableListOf<GrzThumbNail>()
        val pw = ProgressWindow(context, getString(R.string.scanAppGallery))
        pw.dialog?.setOnDismissListener {
                if (success) {
                    adapter = ThumbGridAdapter(this@GalleryActivity, thumbsList.toTypedArray())
                    if (adapter != null) {
                        // visibility of two action menu items
                        var itemUpload = galleryMenu!!.findItem(R.id.action_Payload)
                        var itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
                        var itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
                        itemUpload.isVisible = returnPayload
                        itemDelete.isVisible = !returnPayload
                        itemUpload.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                        itemDelete.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                        itemUsages.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                        // update view
                        gridView.setAdapter(adapter)
                        adapter!!.notifyDataSetChanged()
                        MainActivity.appGalleryAdapter = adapter
                    }
                }
        }
        pw.show()
        // generate thumbnails in another thread
        try {
            Thread {
                val appImagesPath = applicationContext.getExternalFilesDir(null)!!.absolutePath + "/Images/"
                val fileList: Array<File> = File(appImagesPath).listFiles()
                pw.absCount = fileList.size.toFloat() * 3 // keeps progress more agile
                pw.absFakeCount = fileList.size.toFloat() // count to show in pw
                val listGrzThumbNail = getFolderFiles(this, appImagesPath, sortByDate, fileList, pw)
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
                            // get file size
                            var file = File(appImagesPath + item.fileName)
                            fileSize = Paths.get(file.absolutePath).fileSize()
                            // now drawable
                            var drawable = item.thumbNail
                            if (drawable == null) {
                                var bmp: Bitmap? = null
                                // mime
                                val mimeExt = getFileExtension(item.fileName)
                                if (IMAGE_EXT.contains(mimeExt, ignoreCase = true)) {
                                    bmp = ThumbnailUtils.createImageThumbnail(
                                        file,
                                        Size(128, 128),
                                        null
                                    )
                                } else {
                                    if (VIDEO_EXT.contains(mimeExt, ignoreCase = true)) {
                                        bmp = ThumbnailUtils.createVideoThumbnail(
                                            file,
                                            Size(128, 128),
                                            null
                                        )
                                    } else {
                                        if (AUDIO_EXT.contains(mimeExt, ignoreCase = true)) {
                                            bmp = ThumbnailUtils.createAudioThumbnail(
                                                file,
                                                Size(128, 128),
                                                null
                                            )
                                        } else {
                                            if (mimeExt.equals("pdf", ignoreCase = true)) {
                                                bmp = (resources.getDrawable(R.drawable.ic_pdf) as BitmapDrawable).bitmap
                                            } else {
                                                if (mimeExt.equals("txt", ignoreCase = true)) {
                                                    bmp = (resources.getDrawable(android.R.drawable.ic_dialog_email) as BitmapDrawable).bitmap
                                                }
                                            }
                                        }
                                    }
                                }
                                drawable = BitmapDrawable(bmp)
                            }
                            // check date stamp: generate a date stamp header, if date changes
                            if (!lastDateStamp.equals(item.fileDate)) {
                                // insert fully empty entry, the one right beneath an image
                                if (index % 2 != 0) {
                                    thumbsList.add(
                                        GalleryActivity.GrzThumbNail(
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
                                    GalleryActivity.GrzThumbNail(
                                        "",
                                        sdfOutDate.format(date),
                                        getDrawable(android.R.drawable.gallery_thumb)!!
                                    )
                                )
                                index++
                                thumbsList.add(
                                    GalleryActivity.GrzThumbNail(
                                        "",
                                        " ",
                                        getDrawable(android.R.drawable.gallery_thumb)!!
                                    )
                                )
                                index++
                                lastDateStamp = item.fileDate
                            }
                            // set thumbnail
                            if (drawable != null) {
                                // covers all files providing a Bitmap thumbnail
                                thumbsList.add(
                                    GalleryActivity.GrzThumbNail(
                                        item.fileName,
                                        item.fileDate,
                                        drawable,
                                        false,
                                        fileSize
                                    )
                                )
                            } else {
                                // covers: pdf, txt
                                thumbsList.add(
                                    GalleryActivity.GrzThumbNail(
                                        item.fileName,
                                        item.fileDate,
                                        this.getDrawable(android.R.drawable.gallery_thumb)!!,
                                        false,
                                        fileSize
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            thumbsList.add(
                                GrzThumbNail(
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