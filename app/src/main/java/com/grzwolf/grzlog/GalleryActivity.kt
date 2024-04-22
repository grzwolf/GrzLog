package com.grzwolf.grzlog

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


class GalleryActivity : AppCompatActivity() {

    lateinit var gridView: GridView
    var adapter : ThumbGridAdapter? = null
    var galleryMenu: Menu? = null
    var returnPayload = true

    var prevSelGridItem = -1
    var gridItemSelected = false

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gallery_activity)
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // return a selected attachment
        if (savedInstanceState == null) {
            val extras = intent.extras
            if (extras == null) {
                returnPayload = true
            } else {
                returnPayload = extras.getBoolean("ReturnPayload")
            }
        } else {
            returnPayload = true
        }
        // GridView is the main UI component
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
                var itemUpload = galleryMenu!!.findItem(R.id.action_Payload)
                if (gridItemSelected) {
                    // individual action bar icon color: https://stackoverflow.com/questions/60412934/drawable-setcolorfilter-marked-as-deprecated
                    itemUpload.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.yellow), BlendMode.SRC_IN))
                } else {
                    itemUpload.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                }
            } else {
                // allow multiple item selection
                adapter!!.list[position].selected = !adapter!!.list[position].selected
                // change active color of usages & delete icons
                var itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
                var itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
                if (adapter!!.list.any { it.selected == true }) {
                    itemDelete.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.yellow), BlendMode.SRC_IN))
                    itemUsages.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.yellow), BlendMode.SRC_IN))
                } else {
                    itemDelete.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                    itemUsages.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
                }
                adapter!!.notifyDataSetChanged()
            }
            true
        })

        // check whether adapter data are available
        adapter = MainActivity.appGalleryAdapter

        // have a choice to either use existing data or to re generate them
        if (adapter != null) {
            adapter!!.selGridItemChk = false
            adapter!!.selGridItemPos = -1
            gridView.setAdapter(adapter)
            adapter!!.notifyDataSetChanged()
        } else {
            // generate list of thumbnails from fileNameList
            getAppGalleryThumbs()
            // just dummy data
            val listDummy = mutableListOf<GrzThumbNail>()
            var adapterDummy = ThumbGridAdapter(this@GalleryActivity, listDummy.toTypedArray())
            gridView.setAdapter(adapterDummy)
            adapterDummy.notifyDataSetChanged()
        }
    }

    // build a menu bar with options
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // inflate the menu adds items to the action bar
        menuInflater.inflate(R.menu.menu_gallery, menu)
        // needed to work in onOptionsItemSelected
        galleryMenu = menu
        // visibility of two action menu items
        var itemUpload = galleryMenu!!.findItem(R.id.action_Payload)
        var itemDelete = galleryMenu!!.findItem(R.id.action_Delete)
        var itemUsages = galleryMenu!!.findItem(R.id.action_Usages)
        var itemRefresh = galleryMenu!!.findItem(R.id.action_Refresh)
        itemUpload.isVisible = returnPayload
        itemDelete.isVisible = !returnPayload
        itemUsages.isVisible = !returnPayload
        itemUpload.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
        itemDelete.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
        itemUsages.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.lightgrey), BlendMode.SRC_IN))
        itemRefresh.icon!!.setColorFilter(BlendModeColorFilter(getResources().getColor(R.color.yellow), BlendMode.SRC_IN))
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // back to MainActivity
        if (item.itemId == android.R.id.home) {
            for (item in adapter!!.list) {
                item.selected = false
            }
            var item = galleryMenu!!.findItem(R.id.action_Delete)
            item.isVisible = false
            onBackPressed()
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
                    getAppGalleryThumbs()
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
            decisionBox(
                this,
                DECISION.YESNO,
                getString(R.string.appGalleryData),
                getString(R.string.deleteSelectedItemsFromApp),
                {
                    for (item in adapter!!.list) {
                        if (item.selected) {
                            val appImagesPath = applicationContext.getExternalFilesDir(null)!!.absolutePath + "/Images/"
                            val fullFilePath = appImagesPath + item.fileName
                            val delFile = File(fullFilePath)
                            if (delFile.exists()) {
                                delFile.delete()
                            }
                        }
                    }
                    getAppGalleryThumbs()
                    MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                    MainActivity.reReadAppFileData = true
                },
                { null }
            )
        }
        // take last selected item and show its usages in all folders
        if (item.itemId == R.id.action_Usages) {
            // something to do ?
            if (adapter!!.list.all { it.selected == false }) {
                return super.onOptionsItemSelected(item)
            }
            // pick the last selected item: allows to add items to the selection AND search usages
            var searchText = ""
            for (item in adapter!!.list) {
                if (item.selected) {
                    searchText = item.fileName
                }
            }
            if (searchText.length > 0) {
                // find all item hits in DataStore
                var searchHitList: MutableList<MainActivity.GlobalSearchHit> = MainActivity.findTextInDataStore(this, searchText, MainActivity.lvMain)
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

    // Android back button detection
    override fun onBackPressed() {
        // treat like cancel
        MainActivity.returningFromAppGallery = true
        MainActivity.returnAttachmentFromAppGallery = ""
        // return to MainActivity with "Cancel" shows either "Folder More Dialog" or "Input More Dialog"
        MainActivity.showFolderMoreDialog = !returnPayload
        super.onBackPressed()
    }

    // GridView adapter
    class ThumbGridAdapter(private val context: Context, val list: Array<GrzThumbNail>) : BaseAdapter() {
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
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val cv = inflater.inflate(R.layout.gallery_item, null)
            val tv = cv.findViewById(R.id.galleryText) as TextView
            val iv = cv.findViewById(R.id.galleryImage) as ImageView
            // item has two options: fileName == "" indicates a date entry, which needs to be shown
            if (list[position].fileName.isEmpty()) {
                // show the date as sort of HEADER
                tv.text = list[position].fileDate
                // fileDate is not empty (it is empty in case of an item right next to an image in case of a date change)
                if (!list[position].fileDate.isEmpty()) {
                    iv.layoutParams.height = 80
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP,16f)
                    tv.setTextColor(Color.BLACK)
                    tv.gravity = Gravity.CENTER
                    tv.setBackgroundColor(Color.WHITE)
                    if (list[position].fileDate.equals(" ")) {
                        tv.setAlpha(0f)
                    }
                } else {
                    tv.setAlpha(0f)
                }
            } else {
                // set text and thumbnail
                tv.text = list[position].fileName
                iv.setImageDrawable(list[position].thumbNail)
                // how to render an item depends on its selection status: single payload selection or multiple delete selection
                if ((position == selGridItemPos) && selGridItemChk) {
                    // handle item as payload select status, if selGridItemChk is true
                    cv.setBackgroundColor(Color.YELLOW)
                } else {
                    // treat multiple item selection for deletion
                    if (list[position].selected) {
                        cv.setBackgroundColor(Color.YELLOW)
                    } else {
                        cv.setBackgroundColor(Color.WHITE)
                    }
                }
            }
            return cv
        }
    }

    // data set consisting of filename and bitmap
    class GrzThumbNail constructor(fileName: String, fileDate: String, thumbNail: Drawable?, selected: Boolean = false) {
        var fileName = fileName
        var fileDate = fileDate
        var thumbNail = thumbNail
        var selected = selected
    }

    // get list of thumbnail images
    fun getAppGalleryThumbs() {
        var success = false
        var thumbsList = mutableListOf<GrzThumbNail>()
        val appImagesPath = applicationContext.getExternalFilesDir(null)!!.absolutePath + "/Images/"
        val listGrzThumbNail = getFolderFiles(appImagesPath)
        val pw = ProgressWindow(this@GalleryActivity, getString(R.string.scanAppGallery))
        pw.absCount = listGrzThumbNail.size.toFloat()
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
                try {
                    val sdfIn = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                    val sdfOutDate = SimpleDateFormat("yyyy MMM dd, EEE", Locale.getDefault())
                    var lastDateStamp = ""
                    var index = 0
                    for (item in listGrzThumbNail) {
                        // set progress
                        runOnUiThread {
                            pw.incCount += 1
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try {
                                    var drawable = item.thumbNail
                                    if ( drawable == null) {
                                        var bmp: Bitmap? = null
                                        var file = File(appImagesPath + item.fileName)
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
                                            thumbsList.add(GalleryActivity.GrzThumbNail("", "", getDrawable(android.R.drawable.gallery_thumb)!!))
                                            index++
                                        }
                                        // next line shall show the current date (!!the item right next to it carries a " ")
                                        var date = sdfIn.parse(item.fileDate)
                                        thumbsList.add(GalleryActivity.GrzThumbNail("", sdfOutDate.format(date), getDrawable(android.R.drawable.gallery_thumb)!!))
                                        index++
                                        thumbsList.add(GalleryActivity.GrzThumbNail("", " ", getDrawable(android.R.drawable.gallery_thumb)!!))
                                        index++
                                        lastDateStamp = item.fileDate
                                    }
                                    // set thumbnail
                                    if (drawable != null) {
                                        // covers all files providing a Bitmap thumbnail
                                        thumbsList.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, drawable))
                                    } else {
                                        // covers: pdf, txt
                                        thumbsList.add(GalleryActivity.GrzThumbNail(item.fileName, item.fileDate, this.getDrawable(android.R.drawable.gallery_thumb)!!))
                                    }
                                } catch (e: Exception) {
                                    Log.d("GrzLog thumbs", e.message.toString())
                                    thumbsList.add(GrzThumbNail(item.fileName, item.fileDate, this.getDrawable(android.R.drawable.gallery_thumb)!!))
                                }
                            } else {
                                thumbsList.add(GrzThumbNail(item.fileName, item.fileDate, this.getDrawable(android.R.drawable.gallery_thumb)!!))
                            }
                        } else {
                            thumbsList.add(GrzThumbNail(item.fileName, item.fileDate, ContextCompat.getDrawable(this, android.R.drawable.gallery_thumb)!!))
                        }
                        // just the current index
                        index++
                    }
                    success = true
                } catch (ex: Exception) {
                    Log.d("GrzLog thumbs", ex.message.toString())
                } catch (ex: OutOfMemoryError) {
                    Log.d("GrzLog thumbs", ex.message.toString())
                }
                runOnUiThread {
                    pw.close()
                }
            }.start()
        } catch (e: Exception) {
            Log.d("GrzLog thumbs", e.message.toString())
        }
    }

}