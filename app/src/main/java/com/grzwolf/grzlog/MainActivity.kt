package com.grzwolf.grzlog

// destroys for whatever reason the AlertDialog.Builder layout
//import androidx.appcompat.app.AlertDialog

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.*
import android.print.PrintAttributes
import android.print.PrintAttributes.Resolution
import android.print.pdf.PrintedPdfDocument
import android.provider.MediaStore
import android.text.*
import android.text.style.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.View.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView.OnItemLongClickListener
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.grzwolf.grzlog.DataStore.ACTION
import com.grzwolf.grzlog.DataStore.TIMESTAMP
import com.grzwolf.grzlog.FileUtils.Companion.getFile
import com.grzwolf.grzlog.FileUtils.Companion.getPath
import com.grzwolf.grzlog.MainActivity.GrzEditText
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.regex.Pattern


// permission constants
const val PERMISSION_REQUEST_CAMERA       = 0
const val PERMISSION_REQUEST_MEDIA        = 1
const val PERMISSION_REQUEST_AUDIO        = 2
const val PERMISSION_REQUEST_EXIFDATA     = 3
const val PERMISSION_REQUEST_NOTIFICATION = 4

const val MS_TO_DAYS = 1.0 / 1000.0 / 60.0 / 60.0 / 24.0

// common file extensions
val IMAGE_EXT = "jpg.png.bmp.jpeg.gif"
val AUDIO_EXT = "mp3.m4a.aac.amr.flac.ogg.wav"
val VIDEO_EXT = "mp4.3gp.webm.mkv"
val ERROR_EXT = "file-error"

// https://stackoverflow.com/questions/31364540/how-to-add-section-header-in-listview-list-item
class MainActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback {

    // Huawei launcher does not show lockscreen notifications generated in advance (though AOSP does), therefore build a list and show it at wakeup/screen on
    var lockScreenMessageList: MutableList<String> = ArrayList()
    var notificationPermissionGranted = false

    var fabPlus = FabPlus()                               // floating action button is the main UI data input control
    var menuItemsVisible = false                          // control visibility of menu items
    var mainMenuHandler = Handler(Looper.getMainLooper()) // menu action handler
    var searchView: SearchView? = null                    // search function
    var searchViewQuery = ""                              // search query string
    var menuSearchVisible = false                         // visibility flag of search input
    var capturedPhotoUri: Uri? = null                     // needs to be global, bc there is no way a camara app returns uri in onActivityResult

    // fabBack is used to switch back to the previous folder, FabBackTag provides data for such functionality
    class FabBackTag(folderName: String, searchHitListGlobal: MutableList<GlobalSearchHit>, listNdx: Int) {
        var folderName: String = folderName
        var searchHitListGlobal: MutableList<GlobalSearchHit> = searchHitListGlobal
        var listNdx: Int = listNdx
    }

    // different attachments
    internal object PICK {
        const val IMAGE   = 1
        const val CAPTURE = 2
        const val VIDEO   = 3
        const val AUDIO   = 4
        const val PDF     = 5
        const val TXT     = 6
        const val ZIP     = 7
    }

    var shareBody: String? = "" // mimic clipboard inside app

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        // dark mode OR light mode; call before super.onCreate
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        if (sharedPref.getBoolean("darkMode", false)) {
            setTheme(R.style.ThemeOverlay_AppCompat_Dark)
        } else {
            setTheme(R.style.ThemeOverlay_AppCompat_Light)
        }

        // register lock screen notification API26+: https://developer.android.com/training/notify-user/build-notification
        createNotificationChannels()
        // lock screen notification: register a receiver for "screen on" transitions --> start point for an "immediate notifier"
        // https://stackoverflow.com/questions/4208458/android-notification-of-screen-off-on
        // this is not needed in AOSP; but it's needed for the Huawei launcher, which does not allow to render lockscreen notifications directly and in advance
        val intentFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON && lockScreenMessageList.size > 0) {
                    while (lockScreenMessageList.size > 0) {
                        generateLockscreenNotification(lockScreenMessageList[0])
                        lockScreenMessageList.removeAt(0)
                    }
                }
            }
        }, intentFilter)

        // notification permission must be called from NOT RESUMED - aka, not from a onClick handler
        notificationPermissionGranted = verifyNotificationPermission()

        // if camera app returns an image, the app needs a common directory to store it
        // from this place, the image is later copied to GrzLog local
        val appImagesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"GrzLog")
        if (!appImagesDir.exists()) {
            if (!appImagesDir.mkdir()) {
                okBox(this, "Note", "Cannot create image dir")
            }
        }

        // make context static
        contextMainActivity = this

        // standard
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null) // black screen at start or returning from settings
        setContentView(R.layout.activity_main)

        // check for a broken backup: happens, if silent backup is aborted by OS or user
        val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        appName = this.applicationInfo.loadLabel(this.packageManager).toString()
        var file = File(downloadDir, "$appName.zip" + "_part")
        if (file.exists()) {
            okBox(this, "Note", getString(R.string.partialBackup))
        }

        // toolbar tap listener
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // toolbar tap should close undo
                ds!!.undoSection = ""
                ds!!.undoText = ""
                ds!!.undoAction = ACTION.UNDEFINED
                showMenuItemUndo()
                // close menu items after tap Hamburger + NO search operation is ongoing + NOT edit mode
                if (menuItemsVisible && !menuSearchVisible) {
                    showMenuItems(false)
                }
                menuItemsVisible = false
                mainMenuHandler.removeCallbacksAndMessages(null)
            }
            true
        }

        // GrzLog files folder is initially generated during installation; if folder was removed for whatever reason, the app is in trouble & crashes
        appStoragePath = applicationContext.getExternalFilesDir(null)!!.absolutePath
        var folder = File(appStoragePath)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        // GrzLog has its own Attachments folder: need to make sure it exists, no matter what else happened
        val storagePathAppAttachments = "$appStoragePath/Images"
        folder = File(storagePathAppAttachments)
        if (!folder.exists()) {
            folder.mkdirs()
        }

        // we need these controls in so many places ...
        lvMain.listView = findViewById(R.id.lvMain)
        lvMain.showOrder = if (sharedPref.getBoolean("newAtBottom", false)) SHOW_ORDER.BOTTOM else SHOW_ORDER.TOP
        fabPlus.button = findViewById(R.id.fabPlus)
        fabBack = findViewById(R.id.fabBack)
        fabBack!!.tag = FabBackTag("", ArrayList(), -1)

        // prevents onPause / onResume to make a text bak: reset flag in onCreate
        returningFromRestore = false

        //
        // this is the full workflow sequence from app data file up to show data in ListView
        //
        ds = readAppData(appStoragePath)                                                                   // read complete GrzLog.ser into DataStore
        val sectionText = ds!!.dataSection[ds!!.selectedSection]                                           // get active section text (folder) from DataStore
        lvMain.arrayList = lvMain.makeArrayList(sectionText, lvMain.showOrder)                             // generate ListView array list data
        lvMain.adapter = LvAdapter(this, lvMain.arrayList)                                          // make ListView adapter
        (lvMain.listView)?.setAdapter(lvMain.adapter)                                                      // set ListView adapter
        title = ds!!.namesSection[ds!!.selectedSection]                                                    // set app title
        lvMain.scrollToItemPos(if (lvMain.showOrder == SHOW_ORDER.TOP) 0 else lvMain.arrayList!!.size - 1) // scroll ListView

        // onCreate shall clear any undo data + set two ds tags to 0 (first and last deleted item positions)
        ds!!.undoAction = ACTION.UNDEFINED
        ds!!.undoText = ""
        ds!!.undoSection = ""
        ds!!.tagSection.clear()

        // listview item touch listener determines the screen coordinates of the touch event
        (lvMain.listView)?.setOnTouchListener(OnTouchListener { listView, event ->
            // reset the "listview did scroll" flag
            if (menuSearchVisible) {
                lvMain.scrollWhileSearch = false
            }
            // DOWN event
            if (event.action == MotionEvent.ACTION_DOWN) {
                lvMain.touchSelectItem = event.x < 200
                // memorize the touch event coordinates
                lvMain.touchEventPoint = Point(event.x.toInt(), event.y.toInt())
            }
            // UP event
            if (event.action == MotionEvent.ACTION_UP) {
                if (event.y < lvMain.touchEventPoint.y - 10 || event.y > lvMain.touchEventPoint.y + 10) {
                    // revert 'select item mode', if scroll did happen, aka y positions at touch up differs too much from down event
                    lvMain.touchSelectItem = false
                    // scrolling did happen while search menu is active --> allows to skip over search hits
                    if (menuSearchVisible) {
                        lvMain.scrollWhileSearch = true
                    }
                }
            }
            false
        })

        // listview item 'double click' and 'single click'
        (lvMain.listView)?.setOnItemClickListener(OnItemClickListener { adapterView, itemView, itemPosition, itemId ->
            val DOUBLECLICK_MS: Long = 300
            // if condition for a double click event is met, the single click handler gets disabled
            if (System.currentTimeMillis() - lvMain.itemLastClickTime < DOUBLECLICK_MS) {
                lvMain.singleClickHandler.removeCallbacksAndMessages(null)
                // what to do after double click, depends on lvMain.touchSelectItem
                if (lvMain.touchSelectItem) {
                    // double click with touchSelectItem shall act as 'show attachment/link'
                    lvMainOnItemClick(adapterView, itemView, itemPosition, itemId)
                } else {
                    // double click with touchSelectItem shall execute edit ListView item
                    onLongClickEditItem(adapterView, itemView, itemPosition, itemId, true, null)
                }
                return@OnItemClickListener
            }
            // store the time of the item's click event
            lvMain.itemLastClickTime = System.currentTimeMillis()
            // prevent to fire a single click event twice: https://stackoverflow.com/questions/16078378/how-to-check-if-handler-has-an-active-task
            if (lvMain.singleClickHandler.hasMessages(0)) {
                // never happened in any test scenario
                return@OnItemClickListener
            } else {
                // the single click handler only fires, if it is not deactivated by a double click event
                lvMain.singleClickHandler.postDelayed({
                    if (lvMain.touchSelectItem && !menuSearchVisible) {
                        // handle click on item as item select
                        lvMain.touchSelectItem = false
                        lvMain.arrayList!![itemPosition].setSelected(!lvMain.arrayList!![itemPosition].isSelected())
                        lvMain.adapter!!.notifyDataSetChanged()
                    } else {
                        // does the click happen on a selected item
                        if (lvMain.arrayList!![itemPosition].isSelected() && sharedPref.getBoolean("clickSelectedItemsToClipboard", false) ) {
                            // copy to clipboard
                            shareBody = lvMain.folderSelectedItems
                            clipboard = shareBody
                            centeredToast(this, getString(R.string.copyClipboard), 50)
                        } else {
                            // does the click happen on a search hit item
                            if (lvMain.arrayList!![itemPosition].isSearchHit() && sharedPref.getBoolean("clickSearchHitToEdit", false) ) {
                                // execute the click as edit ListView item
                                onLongClickEditItem(adapterView, itemView, itemPosition, itemId, true, null)
                            } else {
                                // show item attachment OR www text link
                                lvMainOnItemClick(adapterView, itemView, itemPosition, itemId)
                            }
                        }
                    }
                }, DOUBLECLICK_MS)
            }
        })

        // listview item long press
        (lvMain.listView)?.setOnItemLongClickListener(OnItemLongClickListener { adapterView, itemView, itemPosition, itemId ->
            // any long press action cancels 'select item mode'
            lvMain.touchSelectItem = false
            // does the long click happen on a search hit item
            if (lvMain.arrayList!![itemPosition].isSearchHit()) {
                whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
            } else {
                // does the long click happen on a selected item
                if (lvMain.arrayList!![itemPosition].isSelected()) {
                    // handle long click on a selection as 'what to do with selection ?'
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, false, null)
                } else {
                    // handle long click as 'more edit option dialog'
                    whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, false)
                }
            }
            true
        })

        // user input button has two use scenarios: click and long press
        (fabPlus.button)?.setOnClickListener(View.OnClickListener { view ->
            fabPlusOnClick(null, null, -1, -1, false, null)
        })
        (fabPlus.button)?.setOnLongClickListener(OnLongClickListener { view ->
            fabPlusOnLongClick(view, lvMain.listView)
            true
        })

        // switch back to previous folder: a) after following an attachment link to a GrzLog folder b) after following a search hit into another folder
        (fabBack)?.setOnClickListener(View.OnClickListener { view ->
            // fabBack always cancels undo
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            // get data from button tag
            val fbt: FabBackTag = fabBack!!.tag as FabBackTag
            // adjust title and message
            var title = getString(R.string.switchFolder)
            var message = getString(R.string.appFolder) + " \"" + fbt.folderName + "\""
            if (fbt.folderName.equals(ds!!.namesSection[ds!!.selectedSection])) {
                if (fbt.searchHitListGlobal.size > 0) {
                    title = getString(R.string.switchToSearchList)
                    message = ""
                }
            } else {
                if (fbt.searchHitListGlobal.size > 0) {
                    message += getString(R.string.searchHitList)
                }
            }
            // make a decision
            decisionBox(
                this,
                DECISION.YESNO,
                title,
                message,
                {
                    if (fabBack != null) {
                        // make button invisible
                        fabBack!!.visibility = INVISIBLE
                        // switch simply back to previous folder
                        if (fbt.folderName.isNotEmpty()) {
                            switchToFolderByName(fbt.folderName)
                        }
                        // plus jump back to the search hit list dialog
                        if (fbt.searchHitListGlobal.size > 0) {
                            jumpToSearchHitInFolderDialog(this, fbt.searchHitListGlobal, fbt.listNdx)
                        }
                        // keep list in back button tag
                        fabBack!!.tag = FabBackTag("", fbt.searchHitListGlobal, -1)
                    }
                },
                {
                    if (fabBack != null) {
                        fabBack!!.visibility = INVISIBLE
                        // keep list in back button tag
                        fabBack!!.tag = FabBackTag("", fbt.searchHitListGlobal, -1)
                    }
                }
            )
        })

        // silently scan app gallery data
        getAppGalleryThumbsSilent(this)

        // memorize first app usage to make a backup reminder from it
        var deferredBakDate = sharedPref.getLong("deferredBak", 0)
        // 0 indicates the very first app usage, which has oc no backup
        if (deferredBakDate == 0L) {
            val spe = sharedPref.edit()
            spe.putLong("deferredBak", Date().time.toLong())
            spe.apply()
        }
        // have a missing/outdated backup reminder as root preference
        if (sharedPref.getBoolean("backupReminder", true)) {
            // read the potential bak reminder deferred time
            deferredBakDate = sharedPref.getLong("deferredBak", 0)
            // trigger a backup reminder decision
            var askForDefer = false
            // check for existing backup file
            var file = getBackupFile(this)
            // prepare reminder decision
            if (file != null) {
                // weekly backup reminder: file is outdated AND deferred date is due
                var diffInDaysBak = (Date().time - file.lastModified()).toDouble() * MS_TO_DAYS
                val diffInDaysUse = (Date().time - deferredBakDate.toDouble()) * MS_TO_DAYS
                if (diffInDaysBak > 7 && diffInDaysUse > 7) {
                    askForDefer = true
                }
            } else {
                // backup reminder 7 days after first app usage, if no backup exists
                val diffInDaysUse = (Date().time - deferredBakDate).toDouble() * MS_TO_DAYS
                if (diffInDaysUse > 7) {
                    askForDefer = true
                }
            }
            // if triggered, ask for bak reminder decision
            if (askForDefer) {
                decisionBox(
                    this,
                    DECISION.YESNO,
                    getString(R.string.makeBackup),
                    getString(R.string.nextReminderOneWeek),
                    {
                        // defer bak reminder for one week
                        val spe = sharedPref.edit()
                        spe.putLong("deferredBak", Date().time.toLong())
                        spe.apply()
                    },
                    null
                )
            }
        }
    }

    // activity_lifecycle.png: onPause() is called, whenever the app goes into background
    override fun onPause() {
        // restore from backup MUST not generate the simple txt-backup (would override local txt backup)
        if (!returningFromRestore) {
            // simple text backup file in app path folder, ONLY USAGE in readAppData(): if GrzLog.ser is corrupted OR not existing
            val storagePathApp = getExternalFilesDir(null)!!.absolutePath
            createTxtBackup(this@MainActivity, storagePathApp, ds)
        }
        super.onPause()
    }

    // activity_lifecycle.png: onResume is called, even when coming back from settings via "Android Back Button"
    override fun onResume() {
        // continue with onCreate(..), if flag reReadAppFileData is true --> needed for changing the theme
        if (reReadAppFileData) {
            reReadAppFileData = false
            // https://stackoverflow.com/questions/2482848/how-to-change-current-theme-at-runtime-in-android  Yuriy Yunikov
            TaskStackBuilder.create(this)
                .addNextIntent(Intent(this, MainActivity::class.java))
                .addNextIntent(this.intent)
                .startActivities() // --> continues with onCreate(..)
            super.onResume()
            return
        }

        // switch to matching dialog: either 'folder more dialog' OR 'fabPlus input' OR 'file picker dialog'
        if (returningFromAppGallery) {
            returningFromAppGallery = false
            // return from simple show app gallery - back to folder options
            if (showFolderMoreDialog) {
                showFolderMoreDialog = false
                folderMoreDialog?.show()
            } else {
                // return from show app gallery with attachment picked - back to fabPlus input or file picker dialog
                if (returnAttachmentFromAppGallery.length > 0) {
                    var file = File(returnAttachmentFromAppGallery)
                    var uriOri = Uri.fromFile(file)
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = returnAttachmentFromAppGallery
                    fabPlus.attachmentUriUri = uriOri
                    var mime = getFileExtension(returnAttachmentFromAppGallery)
                    returnAttachmentFromAppGallery = ""
                    if (IMAGE_EXT.contains(mime, ignoreCase = true)) {
                        fabPlus.attachmentName = getString(R.string.image)
                    } else {
                        if (VIDEO_EXT.contains(mime, ignoreCase = true)) {
                            fabPlus.attachmentName = getString(R.string.video)
                        } else {
                            if (AUDIO_EXT.contains(mime, ignoreCase = true)) {
                                fabPlus.attachmentName = getString(R.string.audio)
                            } else {
                                if (mime.equals("pdf", ignoreCase = true)) {
                                    fabPlus.attachmentName = "[" + file.name + "]"
                                } else {
                                    if (mime.equals("txt", ignoreCase = true)) {
                                        fabPlus.attachmentName = "[" + file.name + "]"
                                    }
                                }
                            }
                        }
                    }
                    fabPlus.mainDialog?.dismiss()
                    // back to main input with the option to return there to the calling dialog
                    fabPlusOnClick(
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                } else {
                    // back to file picker
                    startFilePickerDialog(
                        ReturnToDialogData.attachmentAllowed,
                        ReturnToDialogData.linkText,
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                }
                // reset memorized data
                ReturnToDialogData.reset()
            }
            super.onResume()
            return
        }

        // refresh ListView, just redraw
        lvMain.adapter!!.notifyDataSetChanged()

        // settings shared preferences
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        lvMain.showOrder = if (sharedPref.getBoolean("newAtBottom", false)) SHOW_ORDER.BOTTOM else SHOW_ORDER.TOP

        // app toolbar control
        if (appMenu != null) {
            // show the app's standard menu, if NOT returning from background with previously activated search
            if (!menuSearchVisible) {
                // standard app menu
                showMenuItems(false)
                // onResume always cancels undo data, but not if fabPlus.editInsertLine is active (if returning from a cancelled pick)
                if (!fabPlus.editInsertLine) {
                    ds!!.undoSection = ""
                    ds!!.undoText = ""
                    ds!!.undoAction = ACTION.UNDEFINED
                }
                showMenuItemUndo()
            }
        }

        // if widget calls input, simulate remote click on fabPlus button
        if (sharedPref.getBoolean("widgetJumpInput", false)) {
            if (sharedPref.getBoolean("clickFabPlus", false)) {
                // clear previously set shared pref in widget: otherwise ANY onResume would fire jump to input
                val spe = sharedPref.edit()
                spe.putBoolean("clickFabPlus", false)
                spe.apply()
                // goto input
                fabPlus.button!!.performClick()
            }
        } else {
            // anyway clear clickPlus
            val spe = sharedPref.edit()
            spe.putBoolean("clickFabPlus", false)
            spe.apply()
        }
        super.onResume()
    }

    // listener for the status of requested permissions
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            // Request for camera permission.
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission has been granted. Start camera preview Activity.
                Toast.makeText(baseContext, "camera access granted", Toast.LENGTH_LONG).show()
            } else {
                // Permission request was denied.
                Toast.makeText(baseContext, "camera access denied", Toast.LENGTH_LONG).show()
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                    Toast.makeText(baseContext, "need camera permission", Toast.LENGTH_LONG).show()
                }
                // request again
//                requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CAMERA )
            }
        }
        if (requestCode == PERMISSION_REQUEST_MEDIA) {
            // Request for media permission.
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission has been granted. Start camera preview Activity.
                Toast.makeText(baseContext, "media access granted", Toast.LENGTH_LONG).show()
            } else {
                // Permission request was denied.
                Toast.makeText(baseContext, "media access denied", Toast.LENGTH_LONG).show()
//                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
//                    Toast.makeText(baseContext, "need media access permission", Toast.LENGTH_LONG).show()
//                }
            }
        }
        if (requestCode == PERMISSION_REQUEST_AUDIO) {
            // Request for camera permission.
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission has been granted. Start camera preview Activity.
                Toast.makeText(baseContext, "audio access granted", Toast.LENGTH_LONG).show()
            } else {
                // Permission request was denied.
                Toast.makeText(baseContext, "audio access denied", Toast.LENGTH_LONG).show()
//                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
//                    Toast.makeText(baseContext, "need write permission", Toast.LENGTH_LONG).show()
//                }
            }
        }
        if (requestCode == PERMISSION_REQUEST_NOTIFICATION) {
            // Request for notification permission
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission has been granted. Start camera preview Activity.
                Toast.makeText(baseContext, "notification access granted", Toast.LENGTH_LONG).show()
            } else {
                // Permission request was denied.
                Toast.makeText(baseContext, "notification access denied", Toast.LENGTH_LONG).show()
//                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
//                    Toast.makeText(baseContext, "need write permission", Toast.LENGTH_LONG).show()
//                }
            }
        }
        if (requestCode == PERMISSION_REQUEST_EXIFDATA) {
            // Request for image location permission from EXIF data
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission has been granted. Start camera preview Activity.
                Toast.makeText(baseContext, "EXIF data access granted", Toast.LENGTH_LONG).show()
            } else {
                // Permission request was denied.
                Toast.makeText(baseContext, "EXIF data access denied", Toast.LENGTH_LONG).show()
//                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
//                    Toast.makeText(baseContext, "need write permission", Toast.LENGTH_LONG).show()
//                }
            }
        }
    }

    // ListView click handler implementation shows the item's linked content
    fun lvMainOnItemClick(adapterView: AdapterView<*>?, itemView: View?, itemPosition: Int, itemId: Long) {
        // no view is a no go
        if (itemView == null) {
            return
        }
        // show links could be disabled via preferences
        var showLinks = true
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        if (sharedPref.getBoolean("openLinks", false) == false) {
            centeredToast(this, getString(R.string.wwwDisabled), 50)
            showLinks = false
        }

        //
        // 0. find what was clicked on
        //
        // text view is either title or section, dismiss spacer
        var tv: TextView
        try {
            tv = itemView!!.findViewById(R.id.tvItemTitle)
        } catch (e: Exception) {
            try {
                tv = itemView!!.findViewById(R.id.tvSectionTitle)
            } catch (e: Exception) {
                return
            }
        }
        // text line count: text could be wrapped w/o a new line, still it has multiple lines
        var lineCount = tv.getLineCount()
        // full item coordinates containing the wrapped lines
        var vTop = itemView.top
        var vHeight = itemView.height
        var vHeightPerLine = vHeight / lineCount
        // user touch position
        var touchX = lvMain.touchEventPoint.x.toFloat() - tv.left // x corrected, bc. header is X centered
        var touchY = lvMain.touchEventPoint.y.toFloat()
        // loop wrapped lines
        var text = ""
        var wrappedLineIndex = -1
        var wrappedLines : MutableList<String> = ArrayList()
        for (i in 0 until lineCount) {
            // character indexes of a line in the text are used to extract sequentially one wrapped line
            var lineBeg = tv.getLayout().getLineStart(i)
            var lineEnd = tv.getLayout().getLineEnd(i)
            var line = tv.getText().toString().substring(lineBeg, lineEnd)
            // memorize wrapped lines: needed later, to complete a partial www-link or attachment
            wrappedLines.add(line)
            // identify the extracted line matching to the Y touch event
            var top = vTop + vHeightPerLine * i
            if ((top <= touchY) && (top + vHeightPerLine >= touchY)) {
                text = line
                wrappedLineIndex = i
            }
        }
        // if there is an attachment link, ofsPxl needs correction due the icon pixel width
        val iconWidth = if (PATTERN.UriLink.matcher(text).find()) 35 else 0
        // get the character offset at the X touch coordinate
        var ofs = text.length
        for (i in 1 until text.length) {
            // extend the measured text until the touchX coordinate is hit
            var ofsPxl = tv.paint.measureText(text, 0, i) + iconWidth
            if (ofsPxl >= touchX) {
                ofs = i
                break;
            }
        }
        // obtain the word around the character offset position from text
        var word = getWordAtOffset(text, ofs)
        // word could be a fragmented attachment, get the real attachment name with both enclosing brackets
        var attachment = getAttachmentFromText(lvMain.arrayList!![itemPosition].title.toString(), word)

        // so far, we only know what word/link was clicked on, it's time to show the content
        var title = ""
        var fileName = ""
        try {
            // get the clicked item's full text
            val fullItemText = lvMain.arrayList!![itemPosition].fullTitle
            // get the attachment (image, video, audio, txt, pdf, www, folder) in fullItemText
            val matchFull = fullItemText?.let { PATTERN.UriLink.matcher(it.toString()) }
            var matchFullResult = ""
            if (matchFull?.find() == true) {
                matchFullResult = matchFull.group()
            }
            // check, what was clicked on by user, if it is an attachment
            val matchLink = attachment.let { PATTERN.UriLink.matcher(it.toString()) }

            //
            // 1. handle a clicked attachment in an item's text
            //
            if (matchFullResult.isNotEmpty() && (matchLink.find() == true)) {
                // if there is a double match --> attachment was clicked, so show attachment
                val key = matchFullResult.substring(1, matchFullResult.length - 1)
                val lnkParts = key.split("::::".toRegex()).toTypedArray()
                if (lnkParts != null && lnkParts.size == 2) {
                    var linkNoBrackets = attachment.substring(1, attachment.length-1)
                    if (linkNoBrackets.endsWith(lnkParts[0])) {
                        title = lnkParts[0]
                        fileName = lnkParts[1]
                        if (fileName.startsWith("folder/") == true) {
                            // fileName starting with folder/ is an attachment link to a GrzLog folder
                            var folderName = fileName.substring(fileName.indexOf("folder/") + "folder/".length)
                            decisionBox(
                                this,
                                DECISION.YESNO,
                                getString(R.string.switchFolder),
                                folderName,
                                {
                                    if (fabBack != null) {
                                        val dsFolder = ds!!.namesSection[ds!!.selectedSection]
                                        val fbtOld = fabBack!!.tag as FabBackTag
                                        val fbt = FabBackTag(dsFolder, fbtOld.searchHitListGlobal, -1)
                                        fabBack!!.tag = fbt
                                        fabBack!!.visibility = VISIBLE
                                    }
                                    switchToFolderByName(folderName)
                                },
                                null
                            )
                            return
                        } else {
                            // exif perm missing
                            if (!verifyExifPermission()) {
                                centeredToast(this, getString(R.string.mayNotWork), 3000)
                            }
                            // all attachments other than folder
                            showAppLinkOrAttachment(this, title, fileName)
                        }
                    }
                }
            } else {
                //
                // 2. find a regular clicked url in an item's text and show it in the default browser
                //
                // url was shown indicator
                var urlWasShown = false
                // get a list of potential urls / www-links
                var urls: ArrayList<String>? = getAllLinksFromString(fullItemText.toString())
                if (showLinks && urls != null && urls.size > 0) {
                    // loop to only show clicked www links
                    for (url in urls) {
                        // open www link, if one of the potential urls contains at least partially, what was clicked on (!! multiple lines!!)
                        if (url.contains(word.trim())) {
                            var tmp = ""
                            if (url.equals(word.trim())) {
                                // provided link is good to go, no need to care about fragmentation
                                tmp = url
                            } else {
                                // fragmented link: expand link upwards with upper wrappedLines, until a ' ' appears
                                var posSpaceBeforeLink = wrappedLines[wrappedLineIndex].indexOf(" ")
                                var posLink = wrappedLines[wrappedLineIndex].indexOf(word)
                                if (posSpaceBeforeLink != -1 && posSpaceBeforeLink < posLink) {
                                    for (ndx in 0..wrappedLineIndex) {
                                        tmp += wrappedLines[ndx]
                                    }
                                } else {
                                    for (ndx in 0 until wrappedLineIndex) {
                                        tmp += wrappedLines[ndx]
                                    }
                                    tmp += word.trim()
                                }
                                tmp = tmp.trim()
                                var last = tmp.lastIndexOf(" ")
                                if (last != -1) {
                                    tmp = tmp.substring(last)
                                } else {
                                    tmp = word.trim()
                                }
                                // fragmented link: expand downwards with lower warppedLines, until a ' ' appears
                                var posLastSpace = wrappedLines[wrappedLineIndex].lastIndexOf(" ")
                                if (posLastSpace < posLink) {
                                    for (ndx in wrappedLineIndex + 1 until wrappedLines.size) {
                                        tmp += wrappedLines[ndx]
                                    }
                                }
                                tmp = tmp.trim()
                                var first = tmp.indexOf(" ")
                                if (first != -1) {
                                    tmp = tmp.substring(0, first)
                                }
                            }
                            // show www link
                            urlWasShown = true
                            var urlComplete = tmp
                            centeredToast(this, urlComplete, 50)
                            showAppLinkOrAttachment(this, urlComplete, urlComplete)
                        }
                    }
                }
                //
                // 3. not clicked attachment/link (aka click into the void)
                //
                if (!urlWasShown) {
                    // specific case: item has attachment but was not clicked in attachment AND not in a text link --> show attachment
                    if (matchFullResult.isNotEmpty()) {
                        val key = matchFullResult.substring(1, matchFullResult.length - 1)
                        val lnkParts = key.split("::::".toRegex()).toTypedArray()
                        if (lnkParts != null && lnkParts.size == 2) {
                            title = lnkParts[0]
                            fileName = lnkParts[1]
                            if (!verifyExifPermission()) {
                                centeredToast(this, getString(R.string.mayNotWork), 3000)
                            }
                            if (fileName.startsWith("folder/") == true) {
                                // fileName starting with folder/ is an attachment link to a GrzLog folder
                                var folderName = fileName.substring(fileName.indexOf("folder/") + "folder/".length)
                                decisionBox(
                                    this,
                                    DECISION.YESNO,
                                    getString(R.string.switchFolder),
                                    folderName,
                                    {
                                        if (fabBack != null) {
                                            val dsFolder = ds!!.namesSection[ds!!.selectedSection]
                                            val fbtOld = fabBack!!.tag as FabBackTag
                                            val fbt = FabBackTag(dsFolder, fbtOld.searchHitListGlobal, -1)
                                            fabBack!!.tag = fbt
                                            fabBack!!.visibility = VISIBLE
                                        }
                                        switchToFolderByName(folderName)
                                    },
                                    null
                                )
                                return
                            } else {
                                // all other attachments but folder
                                showAppLinkOrAttachment(this, title, fileName)
                            }
                        }
                    } else {
                        // specific case: no attachment but exactly one www text link, which was not clicked on
                        if (showLinks && urls != null && urls.size == 1) {
                            centeredToast(this, urls[0], 50)
                            showAppLinkOrAttachment(this, urls[0], urls[0])
                        } else {
                            centeredToast(this, word, 50)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(baseContext, e.message, Toast.LENGTH_LONG).show()
        }
    }

    // complete a fragmented attachment link, so that it contains the pair of enclosing brackets
    fun getAttachmentFromText(fullText: String, attachmentFragment: String) : String {
        // plausible to search
        if (fullText.indexOf("[") == -1 && fullText.indexOf("]") == -1) {
            return ""
        }
        // start
        var tmp = attachmentFragment.trim()
        // expand to left
        var currPos = fullText.indexOf(tmp)
        while (tmp.indexOf("[") == -1 && --currPos >= 0) {
            if (fullText[currPos] == ']') {
                return ""
            }
            tmp = fullText[currPos] + tmp
        }
        if (tmp.indexOf("[") == -1) {
            return ""
        }
        // expand to right
        currPos = fullText.indexOf(tmp) + tmp.length-1
        while (tmp.lastIndexOf("]") == -1 && ++currPos < fullText.length) {
            if (fullText[currPos] == '[') {
                return ""
            }
            tmp = tmp + fullText[currPos]
        }
        // final check
        if (!PATTERN.UriLink.matcher(tmp).find()) {
            return ""
        }
        return tmp
    }

    // extract the word around the character offset position in a given text
    fun getWordAtOffset(text: String, offset: Int) : String {
        // sake of mind
        var ofs = Math.max(0, Math.min(offset, text.length-1))
        // 1st char
        var word = text[ofs].toString()
        // don't expand word to the right, if ' ' is already in place
        var expandToRight = if (text[ofs] == ' ') false else true
        // expand word to the left, stop at ' '
        var left = ofs - 1
        while ((left >= 0) && (text[left] != ' ')) {
            word = text[left] + word
            left--
        }
        // expand word to the right
        if (expandToRight) {
            var right = ofs + 1
            while ((right < text.length) && (text[right] != ' ')) {
                word = word + text[right]
                right++
            }
        }
        return word
    }

    // ListView long click handler: show options dialog, how to edit the current item/line
    fun whatToDoWithLongClickItem(adapterView: AdapterView<*>, itemView: View?, itemPosition: Int, itemId: Long, returnToSearchHits: Boolean = false) {
        // this is what the user can click in UI
        val item = adapterView.getItemAtPosition(itemPosition) as ListViewItem
        // do not do anything, if spacer
        if (item.isSpacer) {
            return
        }
        // theme
        val builderItemMore: AlertDialog.Builder? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
            } else {
                AlertDialog.Builder(this@MainActivity)
            }
        // set custom multiline title: https://stackoverflow.com/questions/9107054/how-to-build-alert-dialog-with-a-multi-line-title
        val headPart = getString(R.string.Options)
        val headLine = SpannableString(headPart + "\n\n" + item.title)
        headLine.setSpan(RelativeSizeSpan(1.35F),0, headPart.length,0)
        headLine.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.white)), 0, headPart.length, 0)
        headLine.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.yellow)), headPart.length, headLine.length, 0)
        var titleView: TextView = TextView(this)
        titleView.text = headLine
        builderItemMore?.setCustomTitle(titleView)
        // the following options are related to the ListView item
        val charSequences: MutableList<CharSequence> = ArrayList()
        charSequences.add(getString(R.string.EditLine))                     // ITEM == 0
        charSequences.add(getString(R.string.CopyLine))                     // ITEM == 1
        charSequences.add(getString(R.string.InsertLineBefore))            // ITEM == 2
        charSequences.add(getString(R.string.InsertLineAfter))             // ITEM == 3
        charSequences.add(getString(R.string.SelectItems))           // ITEM == 4
        charSequences.add(getString(R.string.LockscreenReminder))   // ITEM == 5
        if (lvMain.arrayList!![itemPosition].isSection) {
            charSequences.add(getString(R.string.ToggleLineAsText))       // ITEM == 6
        } else {
            charSequences.add(getString(R.string.ToggleLineAsHeader))     // ITEM == 6
        }
        charSequences.add("")                              // ITEM == 7
        charSequences.add(getString(R.string.RemoveLine))                   // ITEM == 8
        val itemsMore = charSequences.toTypedArray()
        builderItemMore?.setItems(
            itemsMore,
            DialogInterface.OnClickListener { dialog, whichOri ->
                var which = whichOri

                // ITEM == 0 edit line
                if (which == 0) {
                    // execute the long click as edit ListView item
                    onLongClickEditItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 1 copy line
                if (which == 1) {
                    shareBody = lvMain.arrayList!![itemPosition].fullTitle
                    clipboard = shareBody
                    whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                }

                // ITEM == 2  'line insert before current line'
                if (which == 2) {
                    // reject 'insert line before': at pos == 0 if SHOW_ORDER.BOTTOM
                    if (itemPosition == 0 && lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.InsertNotAllowed),
                            null
                        )
                        fabPlus.button?.show()
                        return@OnClickListener
                    }
                    // handle 'insert line before' as fabPlus click: at pos == 0 if SHOW_ORDER.TOP
                    if (itemPosition == 0 && lvMain.showOrder == SHOW_ORDER.TOP) {
                        lvMain.editLongPress = false
                        lvMain.selectedText = ""
                        lvMain.selectedRow = 0
                        lvMain.selectedRowNoSpacers = 0
                        fabPlus.inputAlertText = ""
                        fabPlus.editInsertLine = false
                        fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                        return@OnClickListener
                    }
                    lvMain.selectedRow = itemPosition
                    // memorize affected row in array list MINUS spacer count for DataStore
                    var spacers = 0
                    for (i in 0 until itemPosition) {
                        if (lvMain.arrayList!![i].isSpacer) {
                            spacers++
                        }
                    }
                    lvMain.selectedRowNoSpacers = itemPosition - spacers
                    // add line before the selected line in ListView array, which is showOrder aligned
                    lvMain.arrayList!!.add(itemPosition, EntryItem(" ", "", ""))
                    // build finalStr from ListView array
                    var finalStr = lvMain.selectedFolder
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        finalStr = toggleTextShowOrder(finalStr)
                    }
                    // save undo data (status before line insert)
                    ds!!.undoSection = ds!!.dataSection[ds!!.selectedSection]
                    ds!!.undoText = getString(R.string.InsertRow)
                    ds!!.undoAction = ACTION.REVERTINSERT
                    showMenuItemUndo()
                    // clean up
                    fabPlus.pickAttachment = false
                    fabPlus.imageCapture = false
                    fabPlus.attachmentUri = ""
                    fabPlus.inputAlertText = ""
                    fabPlus.attachmentName = ""
                    // update DataStore dataSection
                    ds!!.dataSection[ds!!.selectedSection] = finalStr
                    // flag to indicate, where the call came from
                    lvMain.editLongPress = true
                    // flag: 1) insert line 2) do not override undo data
                    fabPlus.editInsertLine = true
                    // direct jump to line edit input
                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 3  'line insert after current line'
                if (which == 3) {
                    // add line after the selected line in ListView array, which is showOrder aligned
                    lvMain.arrayList!!.add(itemPosition + 1, EntryItem(" ", "", ""))
                    // build finalStr from ListView array
                    var finalStr = lvMain.selectedFolder
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        finalStr = toggleTextShowOrder(finalStr)
                    }
                    // fabPlus needs to work with the inserted index
                    lvMain.selectedRow = itemPosition + 1
                    // memorize affected row in array list MINUS spacer count for DataStore
                    var spacers = 0
                    for (i in 0 until itemPosition) {
                        if (lvMain.arrayList!![i].isSpacer) {
                            spacers++
                        }
                    }
                    lvMain.selectedRowNoSpacers = itemPosition - spacers + 1
                    // save undo data
                    ds!!.undoSection = ds!!.dataSection[ds!!.selectedSection]
                    ds!!.undoText = getString(R.string.InsertRow)
                    ds!!.undoAction = ACTION.REVERTINSERT
                    showMenuItemUndo()
                    // clean up
                    fabPlus.pickAttachment = false
                    fabPlus.imageCapture = false
                    fabPlus.attachmentUri = ""
                    fabPlus.inputAlertText = ""
                    fabPlus.attachmentName = ""
                    // update DataStore
                    ds!!.dataSection[ds!!.selectedSection] = finalStr
                    // logic control flags
                    lvMain.editLongPress = true
                    fabPlus.editInsertLine = true
                    // direct jump to line edit input
                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 4  'select items dialog' - afterwards return to here
                if (which == 4) {
                    // function as parameter: https://stackoverflow.com/questions/62935022/pass-function-with-parameters-in-extension-functionkotlin
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 5 'lock screen notification'
                if (which == 5) {
                    if (!notificationPermissionGranted) {
                        whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                    }
                    val message = lvMain.arrayList!![itemPosition].title
                    var youSureBld: AlertDialog.Builder? = null
                    youSureBld =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            AlertDialog.Builder(
                                this@MainActivity,
                                android.R.style.Theme_Material_Dialog
                            )
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                        }
                    youSureBld.setTitle(R.string.ShowOnLockscreen)
                    youSureBld.setMessage(message)
                    // 'you sure' dlg OK + quit
                    youSureBld.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialog, which ->
                            Toast.makeText(
                                applicationContext,
                                message + " " + getString(R.string.willShow),
                                Toast.LENGTH_LONG
                            ).show()
                            // lock screen notification: Huawei launcher does not allow to render lsm directly, only via a screen-on receiver, which needs a list of notifications
                            lockScreenMessageList.add(message.toString())
                            whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                        })
                    // 'you sure' dlg CANCEL
                    youSureBld.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialog, which ->
                            whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                        })
                    val youSureDlg = youSureBld.create()
                    youSureDlg.setCanceledOnTouchOutside(false)
                    youSureDlg.show()
                }

                // ITEM == 6 'set header manually'
                if (which == 6) {
                    // if item is date header, dismiss
                    if (PATTERN.DateDay.matcher(lvMain.arrayList!![itemPosition].title.toString()).find()) {
                        centeredToast(this, "Line is already header", 3000)
                        dialog.dismiss()
                        return@OnClickListener
                    }
                    decisionBox(
                        this,
                        DECISION.YESNO,
                        getString(R.string.ToggleLineAsHeader),
                        getString(R.string.LineOrHeader),
                        {
                            // save undo data
                            lvMain.selectedRow = itemPosition
                            ds!!.undoSection = ds!!.dataSection[ds!!.selectedSection]
                            ds!!.undoText = charSequences[6].toString()
                            ds!!.undoAction = ACTION.REVERTEDIT
                            showMenuItemUndo()
                            // modify title and fullTitle
                            var isNowSection: Boolean
                            var newTitle: String
                            var fullTitle: String
                            if (lvMain.arrayList!![itemPosition].isSection) {
                                // reset header flag
                                newTitle = lvMain.arrayList!![itemPosition].title!!.substring(1)
                                fullTitle = lvMain.arrayList!![itemPosition].fullTitle!!.substring(1)
                                isNowSection = false
                            } else {
                                // set header flag: Tab = 9
                                newTitle = 9.toChar() + lvMain.arrayList!![itemPosition].title!!
                                fullTitle = 9.toChar() + lvMain.arrayList!![itemPosition].fullTitle!!
                                isNowSection = true
                            }
                            lvMain.arrayList!![itemPosition] = SectionItem(newTitle, fullTitle, lvMain.arrayList!![itemPosition].uriStr)
                            // build finalStr from modified ListView array via getter selectedFolder
                            var finalStr = lvMain.selectedFolder
                            if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                                finalStr = toggleTextShowOrder(finalStr)
                            }
                            // save finalStr to DataStore, to GrzLog.ser and re-read saved data
                            ds!!.dataSection[ds!!.selectedSection] = finalStr                        // update DataStore dataSection
                            writeAppData(appStoragePath, ds, appName)                                // write DataStore
                            ds!!.clear()                                                             // clear DataStore
                            ds = readAppData(appStoragePath)                                         // read DataStore
                            val dsText = ds!!.dataSection[ds!!.selectedSection]                      // raw data from DataStore
                            title = ds!!.namesSection[ds!!.selectedSection]                          // set app title to folder Name
                            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)        // convert & format raw text to array
                            lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList)   // build adapter and populate main listview
                            lvMain.listView!!.adapter = lvMain.adapter                               // populate main listview via adapter
                            lvMain.adapter!!.notifyDataSetChanged()
                            // temporary highlight affected item and revert it to normal 3s later
                            var posHighLight: Int
                            if (isNowSection) {
                                posHighLight = Math.min(itemPosition + 1, lvMain.arrayList!!.size-1)
                            } else {
                                posHighLight = Math.max(itemPosition - 1, 0)
                            }
                            lvMain.arrayList!![posHighLight].setHighLighted(true)
                            lvMain.listView!!.postDelayed({
                                lvMain.arrayList!![posHighLight].setHighLighted(false)
                                lvMain.adapter!!.notifyDataSetChanged()
                            }, 3000)
                            // get out
                            dialog.dismiss()
                        },
                        {
                            whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                        }
                    )
                }

                // ITEM == 7 'empty space'
                if (which == 7) {
                    whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                }

                // ITEM == 8 'current line delete'
                if (which == 8) {
                    val message = lvMain.arrayList!![itemPosition].title
                    var youSureBld: AlertDialog.Builder?
                    youSureBld =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            AlertDialog.Builder(
                                this@MainActivity,
                                android.R.style.Theme_Material_Dialog
                            )
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                        }
                    youSureBld.setTitle(R.string.RemoveLine)
                    youSureBld.setMessage(message)
                    // 'you sure' dlg OK with quit
                    youSureBld.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialog, which ->
                            // clean up
                            fabPlus.inputAlertText = ""
                            // select item and let range delete method do its job
                            lvMain.arrayList!![itemPosition].setSelected(true)
                            deleteMarkedItems(0)  // type: 0 == delete selected item, type: 1 == delete highlighted search item
                            // was hidden during input
                            fabPlus.button!!.show()
                        })
                    // 'you sure' dlg CANCEL
                    youSureBld.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialog, which ->
                            whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                        })
                    val youSureDlg = youSureBld.create()
                    youSureDlg.setCanceledOnTouchOutside(false)
                    youSureDlg.show()
                }
            })
        // BUTTON RETURN
        builderItemMore?.setPositiveButton(
            R.string.close,
            DialogInterface.OnClickListener { dialog, which ->
                dialog.dismiss()
                // return to search hits dialog
                if (returnToSearchHits) {
                    whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                }
            }
        )
        var itemfolderMoreDialog = builderItemMore?.create()
        val listView = itemfolderMoreDialog?.getListView()
        listView?.divider = ColorDrawable(Color.GRAY)
        listView?.dividerHeight = 2
        // item 6 shall be disabled, if item is already header
        listView!!.setOnHierarchyChangeListener(
            object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View) {
                    child.setEnabled(true)
                    // disable option number 6, if line is a date header
                    val text = (child as AppCompatTextView).text
                    val itemIndex: Int = charSequences.indexOf(text)
                    if (itemIndex == 6) {
                        var curText = lvMain.arrayList!![itemPosition].title.toString()
                        if (lvMain.arrayList!![itemPosition].isSection && PATTERN.DateDay.matcher(curText).find()) {
                            child.setEnabled(false)
                            child.setOnClickListener(null)
                        }
                    }
                }
                override fun onChildViewRemoved(view: View?, view1: View?) {}
            })
        itemfolderMoreDialog?.show()
        itemfolderMoreDialog?.setCanceledOnTouchOutside(false)
    }

    // execute the long click as edit ListView item
    fun onLongClickEditItem(adapterView: AdapterView<*>, itemView: View?, itemPosition: Int, itemId: Long, returnToSearchHits: Boolean, function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)?) {
        // flag indicates the usage of fabPlus input as an editor for a 'long press line' input
        lvMain.editLongPress = true
        // delete previous undo data
        ds!!.undoSection = ""
        ds!!.undoText = ""
        ds!!.undoAction = ACTION.UNDEFINED
        showMenuItemUndo()
        // memorize current scroller position
        lvMain.fstVisPos = lvMain.listView!!.firstVisiblePosition
        lvMain.lstVisPos = lvMain.listView!!.lastVisiblePosition
        // the full text of the selected ListView item is data input
        var lineInput = lvMain.arrayList!![itemPosition].fullTitle
        // search for attachment link in 'long press input': if found, prepare fabPlus input data accordingly
        val m = lineInput?.let { PATTERN.UriLink.matcher(it.toString()) }
        if (m?.find() == true) {
            val result = m.group()
            val key = result.substring(1, result.length - 1)
            val lnkParts = key.split("::::".toRegex()).toTypedArray()
            var keyString = ""
            var uriString = ""
            if (lnkParts != null && lnkParts.size == 2) {
                keyString = lnkParts[0]
                uriString = lnkParts[1]
            }
            lineInput = lineInput!!.replace(result, "[$keyString]")
            fabPlus.attachmentUri = uriString
            fabPlus.attachmentName = keyString
            fabPlus.inputAlertText = lineInput
        } else {
            fabPlus.inputAlertText = lineInput
        }
        lvMain.selectedText = lineInput
        // memorize affected row in array list
        lvMain.selectedRow = itemPosition
        // memorize affected row in array list MINUS spacer count for DataStore
        var spacers = 0
        for (i in 0 until itemPosition) {
            if (lvMain.arrayList!![i].isSpacer) {
                spacers++
            }
        }
        lvMain.selectedRowNoSpacers = itemPosition - spacers
        // continue with regular user data input --> jump to fabPlus click handler
        fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
    }

    // items from ListView are selected OR a so far unselected item was 'long clicked' --> what to do now
    // function as param https://stackoverflow.com/questions/62935022/pass-function-with-parameters-in-extension-functionkotlin
    private fun whatToDoWithItemsSelection(adapterView: AdapterView<*>, itemView: View?, itemPosition: Int, itemId: Long, returnToSearchHits: Boolean, function: ((AdapterView<*>, View?, Int, Long) -> Unit?)?) {
        // build a dialog
        var builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        } else {
            AlertDialog.Builder(this@MainActivity)
        }
        // get a list of selected indexes including the included spacers
        var listSelPos = lvMain.getSelectedIndexList()
        // range format the list of selected indexes including the included spacers
        var rangeStr = getIntegerRangeFormatted(listSelPos)
        // set custom multiline title: https://stackoverflow.com/questions/9107054/how-to-build-alert-dialog-with-a-multi-line-title
        val headPart = getString(R.string.whatToDoWithItems)
        val headLine = SpannableString(headPart + "\n\n" + rangeStr)
        headLine.setSpan(RelativeSizeSpan(1.35F),0, headPart.length,0)
        headLine.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.white)), 0, headPart.length, 0)
        headLine.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.yellow)), headPart.length, headLine.length, 0)
        var titleView: TextView = TextView(this)
        titleView.text = headLine
        builder.setCustomTitle(titleView)
        // dialog OPTIONS
        val options = arrayOf<CharSequence>(
            getString(R.string.toggleItemSelection) + " " + itemPosition,
            getString(R.string.selectNextTen),
            getString(R.string.selectDay),
            getString(R.string.selectWeek),
            getString(R.string.selectMonth),
            getString(R.string.unselectAll),
            getString(R.string.selectAll),
            getString(R.string.invertSelection),
            "",
            getString(R.string.copyToClipboard),
            "",
            getString(R.string.cutToClipboard),
            getString(R.string.deleteFromList)
        )
        builder.setItems(options, DialogInterface.OnClickListener { dialog, item ->
            when (item) {
                0 -> { // Toggle the current item's selection status
                    lvMain.arrayList!![itemPosition].setSelected(!lvMain.arrayList!![itemPosition].isSelected())
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                1 -> { // Select next 10 items
                    lvMain.selectNextTenEntries(itemPosition)
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                2 -> { // Select items of today
                    lvMain.toggleSelectGivenDay(itemPosition)
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                3 -> { // Select items of week
                    lvMain.toggleSelectGivenWeek(itemPosition)
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                4 -> { // Select items of month
                    lvMain.toggleSelectGivenMonth(itemPosition)
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                5 -> { // Unselect all
                    for (i in lvMain.arrayList!!.indices) {
                        lvMain.arrayList!![i].setSelected(false)
                    }
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                6 -> { // Select all
                    for (i in lvMain.arrayList!!.indices) {
                        lvMain.arrayList!![i].setSelected(true)
                    }
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                7 -> { // Invert selection
                    for (i in lvMain.arrayList!!.indices) {
                        lvMain.arrayList!![i].setSelected(!lvMain.arrayList!![i].isSelected())
                    }
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                8 -> { // empty space as separator
                    dialog.dismiss()
                    Handler().postDelayed({
                        whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                    }, 100)
                }
                9 -> { // Copy to clipboard / shareBody
                    shareBody = lvMain.folderSelectedItems
                    clipboard = shareBody
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                10 -> { // empty space as separator
                    dialog.dismiss()
                    Handler().postDelayed({
                        whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                    }, 100)
                }
                11 -> { // Cut to clipboard / shareBody
                    var itemsSelected = false
                    for (i in lvMain.arrayList!!.indices) {
                        if (lvMain.arrayList!![i].isSelected()) {
                            itemsSelected = true
                            break
                        }
                    }
                    if (itemsSelected) {
                        decisionBox(this@MainActivity,
                            DECISION.YESNO,
                            getString(R.string.cutSelectedItems) + " " + rangeStr,
                            getString(R.string.continueQuestion),
                            {
                                shareBody = lvMain.folderSelectedItems
                                clipboard = shareBody
                                deleteMarkedItems(0)
                            },
                            { whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function) }
                        )
                    } else {
                        whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        centeredToast(this, getString(R.string.noSelection), 3000 )
                    }
                }
                12 -> { // Delete from ListView
                    var itemsSelected = false
                    for (i in lvMain.arrayList!!.indices) {
                        if (lvMain.arrayList!![i].isSelected()) {
                            itemsSelected = true
                            break
                        }
                    }
                    if (itemsSelected) {
                        decisionBox(this@MainActivity,
                            DECISION.YESNO,
                            getString(R.string.deleteSelectedItems) + " " + rangeStr,
                            getString(R.string.continueQuestion),
                            { deleteMarkedItems(0) },
                            { whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function) }
                        )
                    } else {
                        whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        centeredToast(this, getString(R.string.noSelection), 3000 )
                    }
                }

            }
        })
        // CANCEL button
        builder.setPositiveButton(R.string.close, DialogInterface.OnClickListener { dialog, which ->
            dialog.dismiss()
            if (function != null) {
                function(adapterView, itemView, itemPosition, itemId)
            } else {
                fabPlus.button?.show()
            }
        })
        val dialog = builder.create()
        val listView = dialog.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        dialog.show()
        dialog.setCanceledOnTouchOutside(false)
    }
    // https://stackoverflow.com/questions/5744572/is-there-a-java-library-that-will-create-a-number-range-from-a-list-of-numbers
    private fun getIntegerRangeFormatted(listPos : List<Int>) : String {
        var rangeStr = "Items: "
        var start: Int? = null
        var end: Int? = null
        for (num in listPos) {
            //initialize
            if (start == null || end == null) {
                start = num
                end = num
            } else if (end == num - 1) {
                end = num
            } else {
                //range length 1
                if (start == end) {
                    rangeStr += "$start,"

                } else if (start == end - 1) {
                    rangeStr += "$start,$end,"
                } else {
                    rangeStr += "$start-$end,"
                }
                start = num
                end = num
            }
        }
        if (start == end) {
            rangeStr += start.toString()
        } else if (start == end!! - 1) {
            rangeStr += "$start,$end"
        } else {
            rangeStr += start.toString() + "-" + end.toString()
        }
        return rangeStr
    }

    // items from ListView are search hits, what to do with them as next
    fun whatToDoWithSearchHits(adapterView: AdapterView<*>, itemView: View?, itemPosition: Int, itemId: Long) {
        // build a dialog
        var builder: AlertDialog.Builder? = null
        builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        } else {
            AlertDialog.Builder(this@MainActivity)
        }
        builder.setTitle(getString(R.string.whatToDoWithSearchHits))
        // dialog OPTIONS
        val options = arrayOf<CharSequence>(
            getString(R.string.unselectAll),
            getString(R.string.invertSelection),
            getString(R.string.copyToClipboard),
            getString(R.string.editCurrentItem),
            "",
            getString(R.string.deleteFromList),
        )
        builder.setItems(options, object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface, item: Int) {
                when (item) {
                    0 -> { // unselect all
                        for (i in lvMain.arrayList!!.indices) {
                            lvMain.arrayList!![i].setSearchHit(false)
                        }
                        lvMain.adapter!!.notifyDataSetChanged()
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    1 -> { // invert selection
                        run {
                            for (i in lvMain.arrayList!!.indices) {
                                lvMain.arrayList!![i].setSearchHit(!lvMain.arrayList!![i].isSearchHit())
                            }
                        }
                        lvMain.adapter!!.notifyDataSetChanged()
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    2 -> { // copy search hits to clipboard & shareBody
                        shareBody = lvMain.folderSearchHits
                        clipboard = shareBody
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    3 -> { // edit current item
                        whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, true)
                    }
                    4 -> { // separator
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    5 -> { // delete search hits from ListView
                        decisionBox(this@MainActivity,
                            DECISION.YESNO,
                            getString(R.string.deleteSelectedItems),
                            getString(R.string.continueQuestion),
                            { deleteMarkedItems(1) },
                            { whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId) }
                        )
                    }
                }
            }
        })
        // CANCEL button
        builder.setPositiveButton(
            R.string.close,
            DialogInterface.OnClickListener { dialog, which ->
                dialog.dismiss()
            }
        )
        val dialog = builder.create()
        val listView = dialog.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        dialog.show()
        dialog.setCanceledOnTouchOutside(false)
    }

    // delete previously marked, either selected = (type 0) or search hits = (type 1) ListView items
    fun deleteMarkedItems(type: Int) {
        try {
            // save undo data
            ds!!.undoSection = ds!!.dataSection[ds!!.selectedSection]
            ds!!.undoText = getString(R.string.deleteSelection)
            ds!!.undoAction = ACTION.REVERTDELETE
            showMenuItemUndo()
            // clean up
            lvMain.editLongPress = false
            fabPlus.pickAttachment = false
            fabPlus.editInsertLine = false
            fabPlus.imageCapture = false
            fabPlus.attachmentUri = ""
            fabPlus.inputAlertText = ""
            fabPlus.attachmentName = ""
            // clear tags = fresh start
            ds!!.tagSection.clear()
            // memorize current listview scroll position at index 0
            ds!!.tagSection.add(lvMain.listView!!.firstVisiblePosition)
            // count spacers above deleted Headers
            var cntSpc = 0
            // memorize last deleted item's type
            var lstDelItemIsHeader = false
            // delete marked items from ListView / arrayList in DataStore tagSection
            for (i in lvMain.arrayList!!.indices.reversed()) {
                // distinguish between 'selected' and 'search hit'
                var condition = false
                if (type == 0) {
                    condition = lvMain.arrayList!![i].isSelected()
                }
                if (type == 1) {
                    condition = lvMain.arrayList!![i].isSearchHit()
                }
                if (condition) {
                    // reset bc wait for last deletion
                    lstDelItemIsHeader = false
                    // count spacers above deleted Headers
                    if (lvMain.arrayList!![i].isSection and (i > 0)) {
                        if (lvMain.arrayList!![i-1].isSpacer) {
                            cntSpc++
                            lstDelItemIsHeader = true
                        }
                    }
                    // now really remove item
                    lvMain.arrayList!!.removeAt(i)
                    // memorize deleted item positions
                    ds!!.tagSection.add(i)
                }
            }
            // if topmost deleted item at pos > 0 is Header, correct 'above neighbour' position by 1 BEFORE reloading data
            var corrAbove = 0
            if (lstDelItemIsHeader) {
                corrAbove = 1
            }
            // build finalStr from modified ListView
            var finalStr = lvMain.selectedFolder
            if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                finalStr = toggleTextShowOrder(finalStr)
            }
            // save finalStr to DataStore, to GrzLog.ser and re-read saved data
            ds!!.dataSection[ds!!.selectedSection] = finalStr                        // update DataStore dataSection
            writeAppData(appStoragePath, ds, appName)                                // write DataStore
            ds!!.clear()                                                             // clear DataStore
            ds = readAppData(appStoragePath)                                         // read DataStore
            val dsText = ds!!.dataSection[ds!!.selectedSection]                      // raw data from DataStore
            title = ds!!.namesSection[ds!!.selectedSection]                          // set app title to folder Name
            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)        // convert & format raw text to array
            lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList)   // build adapter and populate main listview
            lvMain.listView!!.adapter = lvMain.adapter                               // populate main listview via adapter

            var above = ds!!.tagSection[ds!!.tagSection.size - 1] - 1 - corrAbove    // 'neighbour' above highlight position: tagSection[last] == 1st del pos
            var below = ds!!.tagSection[1] - (ds!!.tagSection.size - 1) + 1 - cntSpc // 'neighbour' below highlight position: tagSection[1] == last del pos
            if (lvMain.arrayList!![above].isSpacer) above -= 1
            if (lvMain.arrayList!![below].isSpacer) below += 1

            lvMain.listView!!.setSelection(ds!!.tagSection[0])                       // ListView scroll to last known scroll position: tagSection[0] == scroll pos

            lvMain.arrayList!![above].setHighLighted(true)                           // temp. highlighting
            lvMain.arrayList!![below].setHighLighted(true)
            lvMain.adapter!!.notifyDataSetChanged()
            lvMain.listView!!.postDelayed({                                          // revoke temp. highlighting after timeout
                lvMain.arrayList!![above].setHighLighted(false)
                lvMain.arrayList!![below].setHighLighted(false)
                lvMain.adapter!!.notifyDataSetChanged()
            }, 3000)
        } catch ( e:Exception) {}
    }

    // initial size of fabPlus input dialog is called by "(fabPlus.mainDialog)?.setOnShowListener"
    fun setFabPlusDialogSize() {
        val fontSize = fabPlus.inputAlertView!!.textSize
        val lineSpacingExtra = Math.max(fabPlus.inputAlertView!!.lineSpacingExtra, 25f)
        val lineSpacingMultiplier = fabPlus.inputAlertView!!.lineSpacingMultiplier
        val lineHeight = fontSize * lineSpacingMultiplier + lineSpacingExtra
        val heightMax = resources.displayMetrics.heightPixels * 0.50f
        if (fabPlus.mainDialog != null) {
            val wnd = (fabPlus.mainDialog)?.getWindow()!!
            if (wnd != null) {
                val corr = Math.min((fabPlus.inputAlertView!!.getLineCount() - 1) * lineHeight + 600, heightMax)
                wnd.setLayout(WindowManager.LayoutParams.MATCH_PARENT, corr.toInt())
            }
        }
    }

    // input button click --> simple input of data
    private fun fabPlusOnClick(
        adapterView: AdapterView<*>? = null,
        itemView: View? = null,
        itemPosition: Int = -1,
        itemId: Long = -1,
        returnToSearchHits: Boolean = false,
        function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)?)
    {
        // avoid multiple fabPlus instances (example: have one open + home + tap widget)
        if (fabPlus.inputAlertView != null && fabPlus.inputAlertView!!.isShown) {
            return
        }

        // access prefs
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

        // create main editor object
        fabPlus.inputAlertView = GrzEditText(this)
        fabPlus.inputAlertView?.inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
        fabPlus.inputAlertView?.setSingleLine(false)
        fabPlus.inputAlertView?.gravity = Gravity.LEFT or Gravity.TOP

        // delete undo data with condition
        if (!fabPlus.editInsertLine) {
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
        }
        showMenuItemUndo()

        // it looks awkward, if search stays open
        if (searchView != null) {
            searchView!!.onActionViewCollapsed()
        }

        // this happens, when input dlg was opened + user pushed 'Android Home' + "!" again
        if (fabPlus.inputAlertView != null && fabPlus.inputAlertView!!.isShown) {
            return
        }

        // always restore input from memory
        fabPlus.inputAlertView!!.setText(fabPlus.inputAlertText)

        // have a EditText text change listener
        fabPlus.inputAlertView!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                fabPlus.inputAlertTextSelStart = Math.min(s.length, Math.max(fabPlus.inputAlertTextSelStart, start))
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                fabPlus.inputAlertTextSelStart = Math.min(s.length, Math.max(fabPlus.inputAlertTextSelStart, start))
            }
            override fun afterTextChanged(s: Editable) {
                // dynamically increase/shrink height of EditText surrounding AlertDialog
                setFabPlusDialogSize()
                // don't do anything, if tag is set
                if (fabPlus.inputAlertView!!.tag != null) {
                    fabPlus.inputAlertView!!.tag = null
                    return
                }
                // check is only needed, if an attachment link is active
                if (fabPlus.attachmentName.length > 0) {
                    // check for existence of an attachment link, it might have gotten destroyed
                    val matcher = PATTERN.UriLink.matcher(s.toString())
                    if (!matcher.find()) {
                        // attachment link is gone
                        centeredToast(this@MainActivity,getString(R.string.attachmentLink) + fabPlus.attachmentName + getString(R.string.notValid), 3000)
                        fabPlus.attachmentUri = ""
                        fabPlus.attachmentName = ""
                        // since link is gone, restarted fabPlus dialog shall show corrected content
                        var tmp = fabPlus.inputAlertView!!.text.toString()
                        tmp = tmp.replace("[", "")
                        tmp = tmp.replace("]", "")
                        fabPlus.inputAlertText = tmp
                        // restart fabPlus.mainDialog with the activated ability to insert a new attachment link
                        fabPlus.mainDialog?.dismiss()
                        Handler(Looper.getMainLooper()).postDelayed({
                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        }, 100)
                        return
                    } else {
                        // memorize potential changed fabPlus.attachmentName
                        fabPlus.attachmentName = matcher.group()
                    }
                }
            }
        })

        // this happens, when returning from 'fabPlusInput more' dialog with BACK button: more dlg cannot not alter input text
        val test = fabPlus.inputAlertView!!.text.toString()
        if (fabPlus.inputAlertText == test) {
            if (fabPlus.inputAlertText!!.length > 0 && fabPlus.attachmentUri!!.length == 0) {
                fabPlus.inputAlertView!!.tag = 1
                fabPlus.inputAlertView!!.setText(fabPlus.inputAlertText)
                // restore caret position in fabPlus.inputAlertView
                fabPlus.inputAlertView!!.postDelayed({
                    if (fabPlus.inputAlertView!!.selectionStart == 0) {
                        fabPlus.inputAlertView!!.requestFocus()
                        fabPlus.inputAlertView!!.setSelection(0, 0)
                        fabPlus.inputAlertView!!.isCursorVisible = true
                    }
                }, 2000)
            }
        }

        // onActivityResult fires fabPlus.performClick(), which brings us here to handle the selected link to an attachment
        if (fabPlus.attachmentUri!!.length != 0) {
            fabPlus.inputAlertTextSelStart = Math.min(
                fabPlus.inputAlertText!!.length,
                Math.max(0, fabPlus.inputAlertTextSelStart)
            )
            // insert fabPlus.attachmentName in current input
            if (fabPlus.inputAlertText!!.contains(fabPlus.attachmentName)) {
                fabPlus.inputAlertView!!.tag = 1
                fabPlus.inputAlertView!!.setText(fabPlus.inputAlertText)
            } else {
                val oldText1 = fabPlus.inputAlertText!!.substring(0, fabPlus.inputAlertTextSelStart)
                val oldText2 = fabPlus.inputAlertText!!.substring(fabPlus.inputAlertTextSelStart)
                val newText = oldText1 + fabPlus.attachmentName + oldText2
                fabPlus.inputAlertView!!.setText(newText)
            }
            showEditTextContextMenu(fabPlus.inputAlertView, false)
            if (fabPlus.attachmentName.length > 0) {
                // set text selection
                fabPlus.inputAlertView!!.postDelayed({ // set selection to abcd from foo[abcd]bar to allow direct overwrite
                    if (fabPlus.pickAttachment) {
                        fabPlus.pickAttachment = false
                        val text = fabPlus.inputAlertView!!.text.toString()
                        val m = PATTERN.UriLink.matcher(text)
                        if (m.find()) {
                            val selBeg = m.start() + 1
                            val selEnd = m.end() - 1
                            fabPlus.inputAlertView!!.requestFocus()
                            fabPlus.inputAlertView!!.setSelection(selBeg, selEnd)
                            fabPlus.inputAlertView!!.isCursorVisible = true
                        }
                    }
                }, 1000)
            }
        }

        // show a customized standard dialog to obtain a text input: https://stackoverflow.com/questions/10903754/input-text-dialog-android
        var fabPlusBuilder: AlertDialog.Builder?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fabPlusBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
            if (!sharedPref.getBoolean("darkMode", false)) {
                fabPlus.inputAlertView!!.setTextColor(Color.WHITE)
            }
        } else {
            fabPlusBuilder = AlertDialog.Builder(this@MainActivity)
            // force always black color in EditText input of AlertDialog, otherwise input is white in dark theme
            fabPlus.inputAlertView!!.setTextColor(Color.BLACK)
        }
        // title
        val head = SpannableString(getString(R.string.writeGrzLog))
        head.setSpan(RelativeSizeSpan(0.7F),0,head.length,0)
        fabPlusBuilder.setTitle(head)

        // fabPlus button NEUTRAL: the only option while editing an item is to insert an attachment
        fabPlusBuilder.setNeutralButton(
            R.string.InsertFile,
            DialogInterface.OnClickListener { dlg, which ->
                // save current content of input
                fabPlus.inputAlertTextSelStart = Selection.getSelectionStart(fabPlus.inputAlertView!!.text)
                fabPlus.inputAlertText = fabPlus.inputAlertView!!.text.toString()
                // don't go ahead, if permissions are not granted
                if (!verifyMediaPermission()) {
                    centeredToast(this, getString(R.string.noMediaPermission), 3000)
                    Handler(Looper.getMainLooper()).postDelayed({
                        fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                    }, 100)
                    dlg.dismiss()
                    return@OnClickListener
                }
                // allow to attach a file or not
                val attachmentAllowed = !PATTERN.UriLink.matcher(fabPlus.inputAlertView!!.text).find()
                var linkText = ""
                // only if attachment is not allowed, there is something to edit: prepare input for attachment link editor
                if (!attachmentAllowed) {
                    // get current text from input editor: extract the part with the brackets bla[foo]bla
                    linkText = fabPlus.inputAlertText!!
                    var linkTextNoBrackets = ""
                    val matchLnk = linkText.let { PATTERN.UriLink.matcher(it.toString()) }
                    if (matchLnk.find() == true) {
                        linkText = matchLnk.group()
                        linkTextNoBrackets = linkText.substring(1, linkText.length-1)
                    } else {
                        linkText = ""
                    }
                    // clicked item's original full text: extract the part after :::: within the brackets
                    var fullLink = lvMain.arrayList!![itemPosition].fullTitle
                    // ... but there could be a recently added and not yet saved attachment
                    if (fabPlus.attachmentUri!!.length > 0) {
                        fullLink = "[" + linkTextNoBrackets + "::::" + fabPlus.attachmentUri!! + "]"
                    }
                    val matchOri = fullLink?.let { PATTERN.UriLink.matcher(it.toString()) }
                    var oriLink = ""
                    if (matchOri?.find() == true) {
                        var noBrackets = matchOri.group()
                        noBrackets = noBrackets.substring(1, noBrackets.length - 1)
                        var parts = noBrackets.split("::::")
                        if (parts != null && parts.size > 1) {
                            oriLink = parts[1]
                        }
                    }
                    // add oriLink to linkText
                    if (linkText.toString().isNotEmpty() && oriLink.isNotEmpty()) {
                        linkText = linkText.substring(0, linkText.length-1) + "::::" + oriLink + "]"
                    }
                }
                // fire attachment picker dlg, which finally ends at onActivityResult
                dlg.dismiss()
                startFilePickerDialog(attachmentAllowed, linkText.toString(), adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
            })

        // fabPlus button OK
        //
        fabPlusBuilder.setPositiveButton(R.string.ok, DialogInterface.OnClickListener { dialog, which ->

            // timestamp requested ?
            var timestampType = ds!!.timeSection[ds!!.selectedSection]

            // get input data from AlertDialog
            var newText = trimEndAll(fabPlus.inputAlertView!!.text.toString())

            // just in case, such thing came in from paste operation
            newText = newText.replace("\r\n", "\n")

            // empty input is only allowed, if timestamp is not empty
            if (newText.isEmpty() and (timestampType == TIMESTAMP.OFF)) {
                centeredToast(this, getString(R.string.inputOneSpace), 3000)
                fabPlus.button?.show()
                dialog.dismiss()
                // return to calling function
                if ((function != null) && (itemPosition != -1)) {
                    function(adapterView!!, itemView, itemPosition, itemId, returnToSearchHits)
                }
                return@OnClickListener
            }

            // search regex pattern for [[n]]]\n --> forbidden input - would interfere with data sections, which begin with [[n]]\n
            if (Pattern.compile("\\[\\[\\d*\\]\\]\\n").matcher(newText).find()) {
                newText = newText.replace("[[", "((")
                okBox(
                    this@MainActivity,
                    getString(R.string.note),
                    getString(R.string.sequenceNotAllowed)
                )
            }

            // search for attachment link in input
            val m = PATTERN.UriLink.matcher(newText)
            if (m.find()) {
                val result = m.group()
                val key = result.substring(1, result.length - 1)
                // any attachment file is needed to copy app local
                if (fabPlus.attachmentUri!!.length != 0) {
                    // distinguish between www links and real attachments
                    if ((fabPlus.attachmentUri!!.startsWith("/") == false) && (fabPlus.attachmentUri!!.startsWith("file") == false)) {
                        val lnk = fabPlus.attachmentUri!!
                        newText = newText.replace(result, "[$key::::$lnk]")
                    } else {
                        // full filename
                        var fn = fabPlus.attachmentUri!!
                        // execute the file attachment copy
                        val appUriFile = copyAttachmentToApp(this, fabPlus.attachmentUri!!, fabPlus.attachmentUriUri, appStoragePath + "/Images")
                        if (fabPlus.attachmentUri!!.endsWith(appUriFile)) {
                            newText = newText.replace(result, "[$key::::$appUriFile]")
                        } else {
                            newText = newText.replace(result, "[$key --> $appUriFile::::$fn]")
                        }
                        // silently scan app gallery data to show the recently added file
                        getAppGalleryThumbsSilent(this)
                    }
                }
                // any inserted text could contain links after paste from clipboard BUT with no attachmentUri
                if (newText.contains("::::") && fabPlus.attachmentUri!!.length == 0) {
                    // it is fine to NOT copy the file in the link to app local
                    var innerKey = ""
                    var innerUri = ""
                    val lnkParts = key.split("::::".toRegex()).toTypedArray()
                    if (lnkParts != null && lnkParts.size == 2) {
                        innerKey = lnkParts[0]
                        innerUri = lnkParts[1]
                        fabPlus.attachmentUri = innerUri
                    }
                }
            }

            // get original data from DataStore
            val oriText = ds!!.dataSection[ds!!.selectedSection]
            var oriParts: Array<String?> = oriText.split("\\n+".toRegex()).toTypedArray()
            if (oriParts.size == 0) {
                oriParts = arrayOf("")
            }
            // final string collector
            var finalStr: String
            // alternative final string collector with skipped dates
            var finalStrWithSkippedDates: String = ""
            var newTextWithSkippedDates: String = ""
            var numAutoFilledDates = 0
            var fillWithSkippedDates = sharedPref.getBoolean("askAutoFillSkippedDates", true)
            // will a date / time stamp be added
            var addTimeStamp = false
            // marker to indicate a valid last date before the current input date
            var dateLastIsValid = true
            // need to distinguish between 'edit line' (aka long press) AND 'new input' (aka + button)
            val plusButtonInput: Boolean
            if (lvMain.editLongPress) {
                // long press line edit
                plusButtonInput = false
                // oriText text is showOrder dependent
                if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    val botText = toggleTextShowOrder(oriText)
                    oriParts = botText.split("\\n+".toRegex()).toTypedArray()
                    if (oriParts.size == 0) {
                        oriParts = arrayOf("")
                    }
                }
                // specific case: one line edit & no change to text & user pushed ok
                if (oriParts[lvMain.selectedRowNoSpacers].equals(newText) && !fabPlus.editInsertLine) {
                    // if nothing was changed, we leave here to avoid to show the undo icon
                    localCancel(dialog, this)
                    centeredToast(this, getString(R.string.noChange), 3000)
                    // return to calling function: toast would be overlapped w/o delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        if ((function != null) && (itemPosition != -1)) {
                            function(adapterView!!, itemView, itemPosition, itemId, returnToSearchHits)
                        }
                    }, 500)
                    return@OnClickListener
                }
                // make the actual change: it's fine, if newText contains multiple \n
                oriParts[lvMain.selectedRowNoSpacers] = newText
                // build a full final text
                val tmpStr = TextUtils.join("\n", oriParts)
                finalStr = if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    toggleTextShowOrder(tmpStr)
                } else {
                    tmpStr
                }
                // save undo data: if a line was inserted, we already have undo data and don't want to override them, just extend undoAction
                if (fabPlus.editInsertLine) {
                    ds!!.undoText += ":\n'$newText'"
                    ds!!.undoAction = ACTION.REVERTINSERT
                } else {
                    ds!!.undoSection = ds!!.dataSection[ds!!.selectedSection]
                    ds!!.undoText = getString(R.string.EditRow) + ":\n'$newText'"
                    ds!!.undoAction = ACTION.REVERTEDIT
                }
                showMenuItemUndo()
            } else {
                // normal "+ button" input
                plusButtonInput = true
                // sake of mind
                lvMain.selectedRow = if (lvMain.showOrder == SHOW_ORDER.TOP) 0 else lvMain.arrayList!!.size - 1
                // allow undo
                ds!!.undoSection = ds!!.dataSection[ds!!.selectedSection]
                ds!!.undoText = getString(R.string.EditRow) + ":\n'$newText'"
                ds!!.undoAction = ACTION.REVERTADD
                showMenuItemUndo()
                // input might contain multiple lines or \n (from shareBody): don't add a timestamp
                if (newText.contains("\n")) {
                    timestampType = TIMESTAMP.OFF
                    // if input contains multiple lines, take care about their showOrder
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        newText = toggleTextShowOrder(newText)
                    }
                }
                // a common date format string
                val dateFormat = SimpleDateFormat("yyyy-MM-dd")

                // get today date stamp w/o time
                val dateToday: Date?
                dateToday = try {
                    dateFormat.parse(dateFormat.format(Date()))
                } catch (ex: Exception) {
                    Date()
                }
                // prepare for a date max. 1 day before
                val cal = Calendar.getInstance()
                var todayStr = dateFormat.format(dateToday)
                cal.time = dateFormat.parse(todayStr)
                cal.add(Calendar.DATE, -1)
                val yesterdayStr = dateFormat.format(cal.time)
                val yesterdayDate = dateFormat.parse(yesterdayStr)
                // last entry date
                val dateStringLast = if (oriParts[0] != null) {
                    oriParts[0]
                } else {
                    yesterdayStr
                }
                val dateLast: Date
                dateLast = try {
                    dateFormat.parse(dateStringLast.toString())!!
                } catch (ex: Exception) { //  ... if no data is found, we start over with yesterday
                    dateLastIsValid = false
                    yesterdayDate
                }
                // keep latest date stamp always on top: 0 = add today at top / 1 = don't add today at top
                var combineNdx = 1
                var dateStr = dateStringLast + "\n"
                // if topmost date is not today, we need to add the today's date stamp
                if (dateLast.compareTo(dateToday) != 0) {
                    dateStr = SimpleDateFormat("yyyy-MM-dd EEE").format(dateToday!!) + "\n"
                    combineNdx = 0
                    addTimeStamp = true
                }
                // add time at the beginning of the newText
                var timeStr = ""
                if (timestampType != TIMESTAMP.OFF) {
                    var timeFormat = "HH:mm"
                    if (timestampType == TIMESTAMP.HHMMSS) {
                        timeFormat = "HH:mm:ss"
                    }
                    timeStr = SimpleDateFormat(timeFormat).format(Date()) + " "
                }
                if (combineNdx == 0) {
                    newText = timeStr + newText + "\n"
                } else {
                    newText = timeStr + newText
                }
                // build final string after input
                finalStr = dateStr
                finalStr += newText
                // alternative finalStr
                finalStrWithSkippedDates = finalStr
                // prepare auto fill skipped dates
                if ( fillWithSkippedDates && dateLastIsValid ) {
                    newTextWithSkippedDates = finalStr
                    // start date is one day after the last entry's date
                    val c = Calendar.getInstance()
                    c.time = dateFormat.parse(dateStringLast)
                    c.add(Calendar.DATE, 1)
                    val fromStr = dateFormat.format(c.time)
                    val fromDate = dateFormat.parse(fromStr)
                    // list contains all date strings between today and the last recorded entry
                    val skippedDatesList = getDaysBetweenDates(fromDate, dateToday!!).reversed()
                    numAutoFilledDates = skippedDatesList.size
                    if (skippedDatesList.size > 0) {
                        for (dateStr in skippedDatesList) {
                            finalStrWithSkippedDates += "\n" + dateStr
                            newTextWithSkippedDates += dateStr + "\n"
                        }
                    } else {
                        fillWithSkippedDates = false
                        finalStrWithSkippedDates = ""
                        newTextWithSkippedDates = ""
                    }
                }

                // finally append all original entries to finalStr
                for (i in combineNdx until oriParts.size) {
                    finalStr += "\n" + oriParts[i]
                    if (fillWithSkippedDates) {
                        finalStrWithSkippedDates += "\n" + oriParts[i]
                    }
                }
            }

            // sake of mind
            if (!finalStr.endsWith("\n")) {
                finalStr += "\n"
                finalStrWithSkippedDates += "\n"
            }

            // finale asks for a decision regarding skipped dates
            if ( plusButtonInput && fillWithSkippedDates && dateLastIsValid ) {
                decisionBox(
                    this@MainActivity,
                    DECISION.YESNO,
                    getString(R.string.autoFillSkippedDates),
                    getString(R.string.continueQuestion),
                    { fabPlusOkFinale(finalStrWithSkippedDates, newTextWithSkippedDates, addTimeStamp, plusButtonInput, numAutoFilledDates) },
                    { fabPlusOkFinale(finalStr, newText, addTimeStamp, plusButtonInput, 0) }
                )
            } else {
                fabPlusOkFinale(finalStr, newText, addTimeStamp, plusButtonInput, 0)
            }

        })

        // fabPlus button CANCEL
        fabPlusBuilder.setNegativeButton(
            R.string.cancel,
            DialogInterface.OnClickListener { dialog, which ->
                // standard cancel handling
                localCancel(dialog, this)
                // return to calling function
                if ((function != null) && (itemPosition != -1)) {
                    function(adapterView!!, itemView, itemPosition, itemId, returnToSearchHits)
                }
            }
        )

        // handle editor
        fabPlusBuilder.setView(fabPlus.inputAlertView)
        // >= Android 12: default filter limit is set to 10k chars --> remove filters
        fabPlus.inputAlertView!!.filters = arrayOf()
        // build AlertBuilder dialog
        fabPlus.mainDialog = fabPlusBuilder.create()
        // the listener matches the initial dialog width to the screen width & the box height to the text length
        (fabPlus.mainDialog)?.setOnShowListener {
            setFabPlusDialogSize()
        }
        // show AlertBuilder dialog
        (fabPlus.mainDialog)?.show()
        // prevent dlg from disappear, when tap outside: https://stackoverflow.com/questions/42254443/alertdialog-disappears-when-touch-is-outside-android
        (fabPlus.mainDialog)?.setCanceledOnTouchOutside(false)
        // to detect Alert Dialog cancel: Android back button OR dlg quit
        (fabPlus.mainDialog)?.setOnCancelListener { dialog ->
            localCancel(dialog, this)
        }
        // button shall be hidden when AlertDialog.Builder is shown
        fabPlus.button!!.hide()
        // tricky way to let the keyboard popup
        fabPlus.inputAlertView!!.requestFocus()
        val startSel = fabPlus.inputAlertView!!.selectionStart
        val stopSel = fabPlus.inputAlertView!!.selectionEnd
        showKeyboard(fabPlus.inputAlertView, startSel, stopSel, 250)
    }

    // final handling of fabPlusOk as a separate fun to allow option to auto fill skipped dates
    fun fabPlusOkFinale(finalStr: String, newText: String, plusButtonInput: Boolean, addTimeStamp: Boolean, numAutoFilledDates: Int ) {

        // memorize the inserted lines in tagSection
        ds!!.tagSection.clear()
        var numOfNewlines = newText.split("\n").size
        var markRange = false
        if (numOfNewlines > 1) {
            // there is a range
            markRange = true
            // the magic newline
            if (newText.endsWith("\n")) {
                numOfNewlines--
            }
            // corrections:
            var corrector = 0
            // insert line before a Header / Date section, needs to take the Spacer above into account
            if (!plusButtonInput) {
                if (lvMain.showOrder == SHOW_ORDER.TOP) {
                    if ((lvMain.selectedRow > 0) && lvMain.arrayList!![lvMain.selectedRow].isSection) {
                        corrector = -1
                    }
                }
                if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    if ((lvMain.selectedRow < lvMain.arrayList!!.size - 1) && lvMain.arrayList!![lvMain.selectedRow + 1].isSection) {
                        corrector = -1
                    }
                }
            }
            // plus button specific
            if (plusButtonInput) {
                if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    if (addTimeStamp) {
                        corrector = 2
                        numOfNewlines++
                    } else {
                        corrector = 1
                    }
                }
                // each auto filled date is a header
                numOfNewlines += numAutoFilledDates
            }
            // now highlight
            for (i in 0 until numOfNewlines) {
                ds!!.tagSection.add(lvMain.selectedRow + i + corrector)
            }
        }

        // clean up
        fabPlus.pickAttachment = false
        lvMain.editLongPress = false
        fabPlus.editInsertLine = false
        fabPlus.imageCapture = false
        fabPlus.attachmentUri = ""
        fabPlus.inputAlertText = ""
        fabPlus.attachmentName = ""
        fabPlus.button!!.show()
        // save and re-read saved data
        ds!!.dataSection[ds!!.selectedSection] = finalStr                      // update DataStore dataSection
        writeAppData(appStoragePath, ds, appName)                              // serialize DataStore to GrzLog.ser
        ds!!.clear()                                                           // clear DataStore
        ds = readAppData(appStoragePath)                                       // un serialize DataStore from GrzLog.ser
        val dsText = ds!!.dataSection[ds!!.selectedSection]                    // get raw data text from DataStore section/folder
        title = ds!!.namesSection[ds!!.selectedSection]                        // set app title to folder Name
        lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)      // convert & format raw text to array
        lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList) // set adapter and populate main listview
        lvMain.listView!!.adapter = lvMain.adapter
        var jumpToPos = lvMain.fstVisPos                                       // prepare scroll & highlight ListView
        if (plusButtonInput) {
            // adjust ListView jump to position for normal PlusButton input
            if (lvMain.showOrder == SHOW_ORDER.TOP) {
                lvMain.selectedRow = 1
                jumpToPos = 0
            } else {
                lvMain.selectedRow = lvMain.arrayList!!.size - 1
                jumpToPos = lvMain.selectedRow
            }
        } else {
            // adjust ListView jump to position for 'insert line' input
            if (ds!!.undoAction == ACTION.REVERTINSERT) {
                if (lvMain.selectedRow >= lvMain.lstVisPos) {
                    jumpToPos = lvMain.selectedRow
                }
                if (lvMain.selectedRow <= lvMain.fstVisPos) {
                    jumpToPos = lvMain.selectedRow
                }
            }
        }

        // temporarily select edited item
        if (markRange) {
            for (i in 0 until ds!!.tagSection.size) {
                lvMain.arrayList!![ds!!.tagSection[i]].setHighLighted(true)
            }
        } else {
            lvMain.arrayList!![lvMain.selectedRow].setHighLighted(true)
        }
        // ListView shall jump to last known 1st visible  position
        lvMain.listView!!.setSelection(jumpToPos)

        // revoke temporary selection of edited item
        lvMain.listView!!.postDelayed({
            // un mark items
            if (markRange) {
                for (i in 0 until ds!!.tagSection.size) {
                    lvMain.arrayList!![ds!!.tagSection[i]].setHighLighted(false)
                }
            } else {
                lvMain.arrayList!![lvMain.selectedRow].setHighLighted(false)
            }
            ds!!.tagSection.clear()
            // ListView jump
            lvMain.listView!!.setSelection(jumpToPos)
            // inform listview adapter about the changes
            lvMain.adapter!!.notifyDataSetChanged()
        }, 3000)

        // finale
        if (menuSearchVisible && searchViewQuery.length > 0) {
            // edit item while search menu is open, shall restore the search hit selection status of all items
            lvMain.restoreFolderSearchHitStatus(searchViewQuery.lowercase(Locale.getDefault()))
        }
    }


    // fabPlus dialog cancel action
    private fun localCancel(dialog: DialogInterface, context: Context?) {
        // after 'insert line' + cancel, we need to restore the status 'before insert line'
        if (fabPlus.editInsertLine) {
            // restore DataStore from undo data
            if (!ds!!.undoSection.isEmpty()) {
                ds!!.dataSection[ds!!.selectedSection] = ds!!.undoSection // update DataStore dataSection
                writeAppData(appStoragePath, ds, appName)
                ds!!.clear()
                ds = readAppData(appStoragePath)
                val dsText = ds!!.dataSection[ds!!.selectedSection]       // raw data from DataStore
                title = ds!!.namesSection[ds!!.selectedSection]           // set app title to folder Name
                lvMain.arrayList = lvMain.makeArrayList(
                    dsText,
                    lvMain.showOrder
                ) // convert & format raw text to array
                lvMain.adapter = LvAdapter(
                    this@MainActivity,
                    lvMain.arrayList
                ) // set adapter and populate main listview
                lvMain.listView!!.adapter = lvMain.adapter
            }
            // clear undo section
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
        }
        showMenuItemUndo()
        // cancel shall delete a captured image
        if (fabPlus.imageCapture && fabPlus.attachmentUri!!.length > 0) {
            deleteCapturedImage(fabPlus.attachmentUri)
        }
        // was hidden during input
        fabPlus.button!!.show()
        // reset attachment uri & clean up
        fabPlus.pickAttachment = false
        lvMain.editLongPress = false
        fabPlus.editInsertLine = false
        fabPlus.imageCapture = false
        fabPlus.attachmentName = ""
        fabPlus.attachmentUri = ""
        fabPlus.inputAlertText = ""
        fabPlus.inputAlertTextSelStart = -1
        fabPlus.inputAlertView!!.setText("")
        dialog.cancel()
    }

    // fabPlus button long click action --> a new menu with options to jump to top / bottom
    private fun fabPlusOnLongClick(view: View, lv: ListView?) {
        val optionsBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        optionsBuilder.setTitle(R.string.JumpTo)
        var optionsDialog: AlertDialog? = null
        optionsBuilder.setSingleChoiceItems(
            arrayOf(
                getString(R.string.top),
                getString(R.string.bottom)
            ), -1
        ) { dialog, which ->
            if (which == 0) {
                lv!!.setSelection(0)
            }
            if (which == 1) {
                lv!!.setSelection(lv.adapter.count - 1)
            }
            optionsDialog!!.dismiss()
        }
        optionsBuilder.setNegativeButton(R.string.cancel) { dialog, which -> }
        optionsDialog = optionsBuilder.create()
        optionsDialog?.show()
    }

    // DataStore serialization read
    private fun readAppData(storagePath: String): DataStore {
        var dataStore: DataStore? = null
        val appName = applicationInfo.loadLabel(packageManager).toString()
        val file = File(storagePath, "$appName.ser")
        if (file.exists()) {
            try {
                val fis = FileInputStream(file)
                val ois = ObjectInputStream(fis)
                dataStore = ois.readObject() as DataStore
                ois.close()
                fis.close()
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (ex: ClassNotFoundException) {
                ex.printStackTrace()
            }
        }
        //
        // In case GrzLog.ser does not exist
        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        // supposed to happen:
        //        a) install after previously running GrzLog0.0.1 or any "grzlog" legacy app
        //        b) any change to DataStore and de-serialization afterwards will fail
        // Fix: restore GrzLog.ser from GrzLog.txt: 1) local GrzLog.txt  2) /Download GrzLog.txt
        //
        if (dataStore == null) {
            var restoreSuccess = false
            // note
            centeredToast(this, getString(R.string.tryingTxtAppData), 3000)
            // TRY #1: upgrade from app storage path *log*.txt etc --> GrzLog.ser
            dataStore = upgradeFromLegacy(storagePath, true)
            // try to get data from a legacy backup
            if (dataStore == null) {
                // note
                centeredToast(this, getString(R.string.tryingTxtBackupData), 3000)
                // TRY #2: upgrade from legacy backup in Downloads *log*.txt etc --> GrzLog.ser
                val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dataStore = upgradeFromLegacy(downloadDir, false)
                // finally give up ...
                if (dataStore == null) {
                    // finally give up and create an empty DataStore
                    dataStore = DataStore()
                    dataStore.namesSection.add(getString(R.string.folder))
                    dataStore.dataSection.add("")
                    dataStore.tagSection = mutableListOf(-1, -1)
                    dataStore.timeSection.add(TIMESTAMP.HHMM)
                    dataStore.selectedSection = 0
                    okBox(this, getString(R.string.note), getString(R.string.noAppDataFound))
                } else {
                    restoreSuccess = true
                }
            } else {
                restoreSuccess = true
            }
            // somehow success
            if (restoreSuccess && dataStore != null) {
                // make simple text backup file in app path folder, ONLY USAGE in readAppData(): if GrzLog.ser is corrupted OR not existing
                val storagePathApp = getExternalFilesDir(null)!!.absolutePath
                createTxtBackup(this@MainActivity, storagePathApp, dataStore)
                // did somehow work
                okBox(this, getString(R.string.success), getString(R.string.appDataWereRestored))
            }
            // save the DataStore to GrzLog.ser
            writeAppData(appStoragePath, dataStore, appName)
        }
        return dataStore
    }

    // method returns a reversed SHOW_ORDER, it toggles from TOP to BOTTOM or from BOTTOM to TOP
    fun toggleTextShowOrder(toReverse: String): String {
        // input data array contains split by "\n" lines
        var parts: Array<String> = toReverse.split("\\n+".toRegex()).toTypedArray()
        // arrange data array according to show order in a temp list
        val tmpList: ArrayList<String> = ArrayList()
        // loop input
        for (i in parts.indices) {
            val m = parts[i].let { PATTERN.DateDay.matcher(it) }
            if (m != null) {
                if (m.find() && parts[i].startsWith(m.group())) {
                    // as soon as a date timestamp appears, add it to the TOP of the temp list
                    tmpList.add(0, parts[i])
                } else {
                    // ascii '\t = 9 as almost invisible marker for header item (it acts like a single space)
                    if (parts[i].startsWith(9.toChar())) {
                        // as soon as a header line appears, add it to the TOP of the temp list
                        tmpList.add(0, parts[i])
                    } else {
                        if (tmpList.size > 0) {
                            tmpList.add(1, parts[i])
                        } else {
                            // this should be an exceptional case: a non date item before a date / Header / Section item
                            tmpList.add(0, parts[i])
                        }
                    }
                }
            }
        }
        // transfer temp list to parts
        parts = tmpList.toTypedArray();
        // build the output string by concatenating parts
        var reversedStr = ""
        for (i in parts.indices) {
            var tmpStr = trimEndAll(parts[i])
            if (tmpStr.isNotEmpty()) {
                reversedStr += tmpStr + "\n"
            }
        }
        reversedStr = reversedStr.removeSuffix("\n")
        return reversedStr
    }

    // region action bar
    //
    // action bar
    //
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        // needed to work in conjunction with onResume
        appMenu = menu
        // hide menu items
        showMenuItems(false)

        // handle Search Icon click action in App-Menu-Toolbar
        val myActionMenuItem = menu.findItem(R.id.action_search)
        myActionMenuItem.expandActionView()
        searchView = myActionMenuItem.actionView as SearchView?
        // on get focus
        searchView!!.setOnQueryTextFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // clear any listview selection
                lvMain.unselectSelections()
                // starting a search shall quit the auto menu handler: aka search hides other menu items
                menuSearchVisible = true
                mainMenuHandler.removeCallbacksAndMessages(null)
                // let kbd popup
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(view, 0)
                // prevents start Settings after Hamburger click
                menuItemsVisible = false
                // hide irrelevant menu items
                var item = appMenu!!.findItem(R.id.action_Hamburger)
                item.isVisible = false
                item = appMenu!!.findItem(R.id.action_Settings)
                item.isVisible = false
                item = appMenu!!.findItem(R.id.action_ChangeFolder)
                item.isVisible = false
                item = appMenu!!.findItem(R.id.action_Share)
                item.isVisible = false
                // hide search arrows up & down
                item = appMenu!!.findItem(R.id.action_searchUp)
                item.isVisible = false
                item = appMenu!!.findItem(R.id.action_searchDown)
                item.isVisible = false
                // show query in editor
                searchView!!.setQuery(searchViewQuery, false)
            }
        }
        // search action closes
        searchView!!.setOnCloseListener { // close search query
            menuSearchVisible = false
            //
            searchView!!.setQuery(searchViewQuery, false)
            searchViewQuery = ""
            // control visibility of menu items
            showMenuItems(false)
            // unselect all search hits in arraylist
            if (lvMain.searchHitListFolder.size > 0) {
                // ask for decision
                decisionBox(
                    this@MainActivity,
                    DECISION.YESNO,
                    getString(R.string.unselectSearchHits),
                    getString(R.string.continueQuestion),
                    {
                        lvMain.unselectSearchHits()
                        lvMain.searchHitListFolder.clear()
                    },
                    null
                )
            }
            // show normal app title
            title = ds!!.namesSection[ds!!.selectedSection]
            // show fabPlus
            fabPlus.button!!.show()
            // hide keyboard
            hideKeyboard()
            false
        }
        // search action begins
        searchView!!.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (lvMain.searchHitListFolder.size > 0) {
                    if (!query.equals(searchViewQuery, ignoreCase = true)) {
                        // if there is a new query, ask for decision, whether to unselect previous search hits
                        decisionBox(this@MainActivity,
                            DECISION.YESNO,
                            getString(R.string.unselectSearchHits),
                            getString(R.string.continueQuestion),
                            { onQueryTextSubmitHandler(query, true) }
                        ) { onQueryTextSubmitHandler(query, false) }
                    } else {
                        // if query is the same as the previous one, start a fresh search
                        onQueryTextSubmitHandler(query, true)
                    }
                } else {
                    // it doesn't harm to start a fresh search, even if the hitlist is empty
                    onQueryTextSubmitHandler(query, true)
                }
                return false
            }

            override fun onQueryTextChange(s: String): Boolean {
                return false
            }
        })
        return true
    }

    // runnable helper needed for decision box
    fun onQueryTextSubmitHandler(query: String, clearHitList: Boolean) {
        // reset search hits
        if (clearHitList) {
            lvMain.unselectSearchHits()
            lvMain.searchHitListFolder.clear()
        }
        // loop array just once for search hits
        for (pos in lvMain.arrayList!!.indices) {
            val itemText = lvMain.arrayList!![pos].title
            if (itemText!!.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))) {
                lvMain.arrayList!![pos].setSearchHit(true)
                lvMain.searchHitListFolder.add(pos)
            }
        }
        // note if nothing was found
        if (lvMain.searchHitListFolder.size == 0) {
            var item = appMenu!!.findItem(R.id.action_searchUp)
            item.isVisible = false
            item = appMenu!!.findItem(R.id.action_searchDown)
            item.isVisible = false
            centeredToast(
                this@MainActivity,
                "'" + query + "' " + getString(R.string.not_existing),
                Toast.LENGTH_LONG
            )
            return
        }
        // save query globally for for later use
        searchViewQuery = query
        // adapter refresh
        lvMain.adapter!!.notifyDataSetChanged()
        // show arrow keys in app menu
        val itemUp = appMenu!!.findItem(R.id.action_searchUp)
        itemUp.isVisible = true
        val itemDown = appMenu!!.findItem(R.id.action_searchDown)
        itemDown.isVisible = true
        // jump to search hits
        if (lvMain.showOrder == SHOW_ORDER.TOP) {
            lvMain.searchNdx = -1
            onOptionsItemSelected(itemDown)
        } else {
            lvMain.searchNdx = lvMain.searchHitListFolder.size
            onOptionsItemSelected(itemUp)
        }
        // quit search
        searchView!!.onActionViewCollapsed()
        // keep search query in memory
        searchView!!.setQuery(query, false)
    }

    // show / hide menu items BUT Hamburger & Folder
    fun showMenuItems(show: Boolean?) {
        var item: MenuItem
        if (show!!) {
            item = appMenu!!.findItem(R.id.action_Hamburger)
            item.isVisible = false
            item = appMenu!!.findItem(R.id.action_Settings)
            item.isVisible = true
        } else {
            item = appMenu!!.findItem(R.id.action_Hamburger)
            item.isVisible = true
            item = appMenu!!.findItem(R.id.action_Settings)
            item.isVisible = false
        }
        item = appMenu!!.findItem(R.id.action_ChangeFolder)
        item.isVisible = show!!
        item = appMenu!!.findItem(R.id.action_Share)
        item.isVisible = show
        item = appMenu!!.findItem(R.id.action_search)
        item.isVisible = show
        item = appMenu!!.findItem(R.id.action_searchUp)
        item.isVisible = false
        item = appMenu!!.findItem(R.id.action_searchDown)
        item.isVisible = false
        showMenuItemUndo()
    }

    // handle action bar item clicks
    var changeFolderDialog: AlertDialog? = null
    var folderMoreBuilder: AlertDialog.Builder? = null
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // selector
        val id = item.itemId
        // HAMBURGER / GEAR: show settings activity
        if (id == R.id.action_Hamburger || id == R.id.action_Settings) {
            // Hamburger always cancels undo
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            // it looks awkward, if search stays open
            if (searchView != null) {
                searchView!!.onActionViewCollapsed()
            }
            // visible menu items mean, Hamburger is allowed to start Settings
            if (menuItemsVisible) {
                // cancel callback handler, which reduces menu items to folder & hamburger
                menuItemsVisible = false
                mainMenuHandler.removeCallbacksAndMessages(null)
                // start settings activity, they return to onResume
                val settingsIntent = Intent(this, SettingsActivity::class.java)
                startActivity(settingsIntent)
            } else {
                // invisible menu items mean, they shall toggle to visible
                menuItemsVisible = true
                // show menu items for now ...
                showMenuItems(true)
                // ... and auto hide menu items after 10s
                mainMenuHandler.postDelayed({ // menu items must be visible (they are not in Settings) + NO search operation is ongoing + NOT edit mode
                    if (menuItemsVisible && !menuSearchVisible) {
                        showMenuItems(false)
                    }
                    menuItemsVisible = false
                }, 10000)
            }
            return true
        }
        // SEARCH: down
        if (id == R.id.action_searchDown) {
            // return if empty
            if (lvMain.searchHitListFolder.size == 0) {
                return super.onOptionsItemSelected(item)
            }
            // calculate index selected item in hit list
            if (lvMain.searchNdx + 1 < lvMain.searchHitListFolder.size) {
                lvMain.searchNdx++
            } else {
                lvMain.searchNdx = 0
                centeredToast(
                    this@MainActivity,
                    "'" + searchViewQuery + "' " + getString(R.string.WrapAround),
                    Toast.LENGTH_SHORT
                )
            }
            // scroll did happen, which shall skip the over scrolled search hits
            if (lvMain.scrollWhileSearch) {
                lvMain.scrollWhileSearch = false
                val fstItemNdx = lvMain.listView!!.firstVisiblePosition
                // find next larger search hit index
                var nxtSearchHit = lvMain.searchHitListFolder.size - 1
                for (i in lvMain.searchHitListFolder.indices) {
                    if (lvMain.searchHitListFolder[i] > fstItemNdx) {
                        nxtSearchHit = i
                        break
                    }
                }
                lvMain.searchNdx = nxtSearchHit
            }
            // convert hit list index to listview array index
            val ndx = lvMain.searchHitListFolder[lvMain.searchNdx]
            // finally jump/scroll to selected item
            lvMain.listView!!.setSelection(ndx)
            // modify app title
            var string = ds!!.namesSection[ds!!.selectedSection] + "  " +
                    (lvMain.searchNdx + 1).toString() +
                    "(" + lvMain.searchHitListFolder.size.toString() + ") " +
                    searchViewQuery
            var text: Spanned
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                text = Html.fromHtml("<small>$string</small>", Html.FROM_HTML_MODE_LEGACY)
            } else {
                text = Html.fromHtml("<small>$string</small>")
            }
            title = text
        }
        // SEARCH: up
        if (id == R.id.action_searchUp) {
            // return if empty
            if (lvMain.searchHitListFolder.size == 0) {
                return super.onOptionsItemSelected(item)
            }
            // calculate index selected item in hit list
            if (lvMain.searchNdx - 1 >= 0) {
                lvMain.searchNdx--
            } else {
                lvMain.searchNdx = lvMain.searchHitListFolder.size - 1
                centeredToast(
                    this@MainActivity,
                    "'" + searchViewQuery + "' " + getString(R.string.WrapAround),
                    Toast.LENGTH_SHORT
                )
            }
            // scroll did happen, which shall skip the over scrolled search hits
            if (lvMain.scrollWhileSearch) {
                lvMain.scrollWhileSearch = false
                val fstItemNdx = lvMain.listView!!.firstVisiblePosition
                // find the next smaller search hit index
                var nxtSearchHit = 0
                for (i in lvMain.searchHitListFolder.indices.reversed()) {
                    if (lvMain.searchHitListFolder[i] < fstItemNdx) {
                        nxtSearchHit = i
                        break
                    }
                }
                lvMain.searchNdx = nxtSearchHit
            }
            // convert hit list index to array index
            val ndx = lvMain.searchHitListFolder[lvMain.searchNdx]
            // finally jump/scroll to selected item
            lvMain.listView!!.setSelection(ndx)
            // modify app title
            var string = ds!!.namesSection[ds!!.selectedSection] + "  " +
                    (lvMain.searchNdx + 1).toString() +
                    "(" + lvMain.searchHitListFolder.size.toString() + ") " +
                    searchViewQuery
            var text: Spanned
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                text = Html.fromHtml("<small>$string</small>", Html.FROM_HTML_MODE_LEGACY)
            } else {
                text = Html.fromHtml("<small>$string</small>")
            }
            title = text
        }
        // UNDO: reverse change after long press edit
        if (id == R.id.action_Undo) {
            // it looks awkward, if search stays open
            if (searchView != null) {
                searchView!!.onActionViewCollapsed()
            }
            // exec undo
            decisionBox(
                this@MainActivity,
                DECISION.YESNO,
                getString(R.string.continueQuestion),
                getString(R.string.undo) + " " + ds!!.undoText,
                { execUndo() },
                null
            )
        }
        // SHARE: content share option
        if (id == R.id.action_Share) {
            // it looks awkward, if search stays open
            if (searchView != null) {
                searchView!!.onActionViewCollapsed()
            }
            //  provide options about what to share
            var shareBuilder: AlertDialog.Builder?
            shareBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
            } else {
                AlertDialog.Builder(this@MainActivity)
            }
            val items = arrayOf<CharSequence>(
                getString(R.string.selectedItems),
                getString(R.string.currentFolder),
                getString(R.string.folderAsPDF),
                getString(R.string.folderAsRTF)
            )
            shareBuilder.setTitle(R.string.shareSelection)
            var selected = 0
            shareBuilder.setSingleChoiceItems(
                items,
                0,
                DialogInterface.OnClickListener { dialog, which ->
                    selected = which
                })
            shareBuilder.setPositiveButton(
                R.string.ok,
                DialogInterface.OnClickListener { dialog, which ->
                    // share current items selection
                    if (selected == 0) {
                        var itemsSelected = false
                        for (i in lvMain.arrayList!!.indices) {
                            if (lvMain.arrayList!![i].isSelected()) {
                                itemsSelected = true
                                break
                            }
                        }
                        if (itemsSelected) {
                            shareBody = lvMain.folderSelectedItems
                            clipboard = shareBody
                        } else {
                            okBox(
                                this@MainActivity,
                                getString(R.string.note),
                                getString(R.string.makeSelection)
                            )
                            return@OnClickListener
                        }
                    }
                    // share complete folder as text
                    if (selected == 1) {
                        shareBody = lvMain.selectedFolder
                        clipboard = shareBody
                    }
                    if (selected < 2) {
                        // open share options
                        val sharingIntent = Intent(Intent.ACTION_SEND)
                        sharingIntent.type = "text/plain"
                        val shareSub = "GrzLog"
                        sharingIntent.putExtra(Intent.EXTRA_SUBJECT, shareSub)
                        sharingIntent.putExtra(Intent.EXTRA_TEXT, shareBody)
                        startActivity(Intent.createChooser(sharingIntent, ""))
                    }
                    // share folder as PDF
                    if (selected == 2) {
                        val folderName = ds!!.namesSection[ds!!.selectedSection]
                        generatePdf(folderName, ds!!.dataSection[ds!!.selectedSection], true, null)
                    }
                    // share folder as RTF
                    if (selected == 3) {
                        val folderName = ds!!.namesSection[ds!!.selectedSection]
                        generateRtf(folderName, ds!!.dataSection[ds!!.selectedSection], true, null)
                    }
                })
            shareBuilder.setNegativeButton(R.string.back, null)
            val shareDialog = shareBuilder.create()
            val listView = shareDialog.listView
            listView.divider = ColorDrawable(Color.GRAY)
            listView.dividerHeight = 2
            shareDialog.show()
            shareDialog.setCanceledOnTouchOutside(false)
        }

        // change data base folder
        if (id == R.id.action_ChangeFolder) {
            // it looks awkward, if search stays open
            if (searchView != null) {
                searchView!!.onActionViewCollapsed()
            }
            // reset visibility of the folder switch back button
            if (fabBack != null) {
                fabBack!!.visibility = INVISIBLE
                val fbtOld = fabBack!!.tag as FabBackTag
                val fbt = FabBackTag("", fbtOld.searchHitListGlobal, -1)
                fabBack!!.tag = fbt
            }
            // change folder always cancels undo
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            // CHANGE FOLDER
            var changeFileBuilder: AlertDialog.Builder? = null
            changeFileBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
            } else {
                AlertDialog.Builder(this@MainActivity)
            }
            val changeFileBuilderContext = changeFileBuilder.context
            changeFileBuilder.setTitle(R.string.selectFolder)
            var selectedSectionTemp = ds!!.selectedSection
            var lastClickTime = System.currentTimeMillis()
            var lastSelectedSection = ds!!.selectedSection
            // add a radio button list containing all the folder names from DataStore
            val array = ds!!.namesSection.toTypedArray()
            changeFileBuilder.setSingleChoiceItems(
                array,
                ds!!.selectedSection,
                DialogInterface.OnClickListener { dialog, which ->
                    // the current file selection is temporary, unless we confirm with OK
                    selectedSectionTemp = which
                    // check double click event: it shall behave like OK, means make the folder change permanent
                    val nowTime = System.currentTimeMillis()
                    val deltaTime = nowTime - lastClickTime
                    if (deltaTime < 700 && lastSelectedSection == which) {
                        // now the file selection becomes permanent
                        switchToFolderByNumber(selectedSectionTemp)
                        dialog.cancel()
                    }
                    lastSelectedSection = which
                    lastClickTime = nowTime
                })
            // CHANGE FOLDER selection OK
            changeFileBuilder.setPositiveButton(
                R.string.ok,
                DialogInterface.OnClickListener { dialog, which ->
                    // now the file selection becomes permanent
                    switchToFolderByNumber(selectedSectionTemp)
                    dialog.cancel()
                })
            // CHANGE FOLDER selection Cancel
            changeFileBuilder.setNegativeButton(R.string.back, null)
            // CHANGE FILE selection MORE FILE OPTIONS
            changeFileBuilder.setNeutralButton(
                R.string.more,
                DialogInterface.OnClickListener { dialogMore, which ->
                    val itemText = ds!!.namesSection[selectedSectionTemp]
                    // MORE FOLDER OPTIONS
                    val items = arrayOf<CharSequence>(     // x = FOLDER options
                        getString(R.string.export),                          // 0 Export
                        getString(R.string.newFolder),                      // 1 New
                        getString(R.string.renFolder),                          // 2 Rename
                        getString(R.string.clearFolder),                   // 3 Clear
                        getString(R.string.removeFolder),                          // 4 Remove
                        getString(R.string.moveFolderUp),                 // 5 Move up
                        getString(R.string.useTimestamp),                       // 6 Timestamp
                        getString(R.string.searchFolders)               // 7 search folders
                    )
                    folderMoreBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AlertDialog.Builder(
                            changeFileBuilderContext,
                            android.R.style.Theme_Material_Dialog
                        )
                    } else {
                        AlertDialog.Builder(changeFileBuilderContext)
                    }
                    val folderMoreBuilderContext = folderMoreBuilder!!.context
                    folderMoreBuilder!!.setTitle(getString(R.string.whatTodoWithFolder) + itemText + "'")
                    folderMoreBuilder!!.setItems(
                        items,
                        DialogInterface.OnClickListener { dialogRename, which ->
                            //  MORE FILE OPTIONS: Export folder to PDF or RTF
                            if (which == 0) {
                                // EXPORT FOLDER to PDF or RTF
                                var exportBuilder: AlertDialog.Builder?
                                exportBuilder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        AlertDialog.Builder(
                                            this@MainActivity,
                                            android.R.style.Theme_Material_Dialog
                                        )
                                    } else {
                                        AlertDialog.Builder(this@MainActivity)
                                    }
                                var tmpExportSelection = 0
                                exportBuilder.setTitle(R.string.exportFolder)
                                exportBuilder.setSingleChoiceItems(
                                    arrayOf(
                                        getString(R.string.GeneratePDF),
                                        getString(R.string.GenerateRTF),
                                        getString(R.string.copyToClip)
                                    ),
                                    0,
                                    DialogInterface.OnClickListener { dialog, which ->
                                        tmpExportSelection = which
                                    })
                                // EXPORT OPTIONS ok
                                exportBuilder.setPositiveButton(
                                    R.string.ok,
                                    DialogInterface.OnClickListener { dialog, which ->
                                        //  EXPORT OPTIONS: PDF
                                        if (tmpExportSelection == 0) {
                                            val folderName = ds!!.namesSection[selectedSectionTemp]
                                            val rawText = ds!!.dataSection[selectedSectionTemp]
                                            generatePdf(folderName, rawText, false, folderMoreBuilder)
                                        }
                                        //  EXPORT OPTIONS: RTF
                                        if (tmpExportSelection == 1) {
                                            val folderName = ds!!.namesSection[selectedSectionTemp]
                                            val rawText = ds!!.dataSection[selectedSectionTemp]
                                            generateRtf(folderName, rawText, false, folderMoreBuilder)
                                        }
                                        // EXPORT OPTIONS: copy folder to clipboard
                                        if (tmpExportSelection == 2) {
                                            shareBody = ds!!.dataSection[selectedSectionTemp]
                                            clipboard = shareBody
                                        }
                                    })
                                // EXPORT OPTIONS back
                                exportBuilder.setNegativeButton(
                                    R.string.back,
                                    DialogInterface.OnClickListener { dialog, which -> folderMoreDialog!!.show() })
                                // EXPORT OPTIONS show
                                exportBuilder.create().show()
                            }
                            //  MORE FILE OPTIONS: New
                            if (which == 1) {
                                // reject add item
                                if (ds!!.namesSection.size >= DataStore.SECTIONS_COUNT) {
                                    var builder: AlertDialog.Builder? = null
                                    builder =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                            AlertDialog.Builder(
                                                folderMoreBuilderContext,
                                                android.R.style.Theme_Material_Dialog
                                            )
                                        } else {
                                            AlertDialog.Builder(folderMoreBuilderContext)
                                        }
                                    builder.setTitle(R.string.note)
                                    builder.setMessage(getString(R.string.folderLimit) + DataStore.SECTIONS_COUNT + getString(R.string.folders))
                                    builder.setIcon(android.R.drawable.ic_dialog_alert)
                                    builder.setPositiveButton(
                                        R.string.ok,
                                        DialogInterface.OnClickListener { dialog, which -> folderMoreDialog!!.show() })
                                    builder.show()
                                    return@OnClickListener
                                }
                                // real add item
                                val input = EditText(folderMoreBuilderContext)
                                input.inputType = InputType.TYPE_CLASS_TEXT
                                input.setText(R.string.folder, TextView.BufferType.SPANNABLE)
                                showEditTextContextMenu(input, false)
                                var addBuilder: AlertDialog.Builder?
                                addBuilder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        AlertDialog.Builder(
                                            folderMoreBuilderContext,
                                            android.R.style.Theme_Material_Dialog
                                        )
                                    } else {
                                        AlertDialog.Builder(folderMoreBuilderContext)
                                    }
                                addBuilder.setTitle(R.string.folderNewName)
                                addBuilder.setView(input)
                                addBuilder.setPositiveButton(
                                    R.string.ok,
                                    DialogInterface.OnClickListener { dialogChange, which ->
                                        var text = input.text.toString()
                                        if ( text.isEmpty()) {
                                            centeredToast(this, getString(R.string.emptyInput), 3000)
                                            Handler().postDelayed({
                                                folderMoreDialog!!.show()
                                            }, 100)
                                            return@OnClickListener
                                        }
                                        if (isDupeFolder(text)) {
                                            centeredToast(this, getString(R.string.duplicateName), 3000)
                                            Handler().postDelayed({
                                                folderMoreDialog!!.show()
                                            }, 100)
                                            return@OnClickListener
                                        }
                                        ds!!.dataSection.add("")
                                        ds!!.namesSection.add(text)
                                        ds!!.selectedSection = ds!!.namesSection.size - 1
                                        ds!!.timeSection.add(ds!!.selectedSection, TIMESTAMP.OFF)
                                        ds!!.tagSection = mutableListOf(-1, -1)
                                        writeAppData(appStoragePath, ds, appName)
                                        reReadAppFileData = true
                                        onResume()
                                    })
                                addBuilder.setNegativeButton(
                                    R.string.cancel,
                                    DialogInterface.OnClickListener { dialogRename, which ->
                                        val imm =
                                            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.hideSoftInputFromWindow(input.windowToken, 0)
                                        folderMoreDialog!!.show()
                                    })
                                addBuilder.show()
                                // tricky way to let the keyboard popup
                                input.requestFocus()
                                showKeyboard(input, 0, 0, 250)
                                // preselect folder input name for easier override, <300ms don't work
                                input.postDelayed({ input.selectAll() }, 500)
                            }
                            // MORE FOLDER OPTIONS: Rename
                            if (which == 2) {
                                // folder name rename dialog
                                val input = EditText(folderMoreBuilderContext)
                                input.inputType = InputType.TYPE_CLASS_TEXT
                                input.setText(
                                    ds!!.namesSection[selectedSectionTemp],
                                    TextView.BufferType.SPANNABLE
                                )
                                showEditTextContextMenu(input, false) // suppress edit context menu
                                var renameBuilder: AlertDialog.Builder? = null
                                renameBuilder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        AlertDialog.Builder(
                                            folderMoreBuilderContext,
                                            android.R.style.Theme_Material_Dialog
                                        )
                                    } else {
                                        AlertDialog.Builder(folderMoreBuilderContext)
                                    }
                                renameBuilder.setTitle(R.string.changeFolderName)
                                renameBuilder.setView(input)
                                // folder name rename ok
                                renameBuilder.setPositiveButton(
                                    getString(R.string.ok),
                                    DialogInterface.OnClickListener { dialogChange, which ->
                                        var text = input.text.toString()
                                        if ( text.isEmpty()) {
                                            centeredToast(this, getString(R.string.emptyInput), 3000)
                                            Handler().postDelayed({
                                                folderMoreDialog!!.show()
                                            }, 100)
                                            return@OnClickListener
                                        }
                                        if (isDupeFolder(text)) {
                                            centeredToast(this, getString(R.string.duplicateName), 3000)
                                            Handler().postDelayed({
                                                folderMoreDialog!!.show()
                                            }, 100)
                                            return@OnClickListener
                                        }
                                        // reset global search results
                                        if (fabBack != null) {
                                            fabBack!!.visibility = INVISIBLE
                                            val fbt = FabBackTag("", ArrayList(), -1)
                                            fabBack!!.tag = fbt
                                        }
                                        ds!!.namesSection[selectedSectionTemp] = text.toString()
                                        ds!!.selectedSection = selectedSectionTemp
                                        writeAppData(appStoragePath, ds, appName)
                                        // call menu item programmatically: https://stackoverflow.com/questions/30002471/how-to-programmatically-trigger-click-on-a-menuitem-in-android
                                        findViewById<View>(R.id.action_ChangeFolder).callOnClick()
                                        // update app title bar
                                        title = ds!!.namesSection[ds!!.selectedSection]
                                        // close parent dialog
                                        changeFolderDialog!!.cancel()
                                        // but show more dialog
                                        folderMoreDialog!!.setTitle(getString(R.string.whatTodoWithFolder) + ds!!.namesSection[selectedSectionTemp] + "'")
                                        folderMoreDialog!!.show()
                                    })
                                // folder name rename cancel
                                renameBuilder.setNegativeButton(
                                    R.string.cancel,
                                    DialogInterface.OnClickListener { dialogRename, which ->
                                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.hideSoftInputFromWindow(input.windowToken, 0)
                                        folderMoreDialog!!.show()
                                    })
                                // folder name rename show
                                val renameDialog: Dialog = renameBuilder.show()
                                // tricky way to let the keyboard popup
                                input.requestFocus()
                                showKeyboard(input, 0, 0, 250)
                                // select input name
                                input.postDelayed({ input.selectAll() }, 500)
                            }
                            // MORE FOLDER OPTIONS: Clear folder content
                            if (which == 3) {
                                var builder: AlertDialog.Builder? = null
                                builder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        AlertDialog.Builder(
                                            folderMoreBuilderContext,
                                            android.R.style.Theme_Material_Dialog
                                        )
                                    } else {
                                        AlertDialog.Builder(folderMoreBuilderContext)
                                    }
                                builder.setTitle(getString(R.string.clearFolderData) + itemText + "' ?")
                                builder.setPositiveButton(
                                    R.string.ok,
                                    DialogInterface.OnClickListener { dialogChange, which ->
                                        // reset global search results
                                        if (fabBack != null) {
                                            fabBack!!.visibility = INVISIBLE
                                            val fbt = FabBackTag("", ArrayList(), -1)
                                            fabBack!!.tag = fbt
                                        }
                                        // cleanup
                                        ds!!.dataSection[selectedSectionTemp] = ""
                                        ds!!.selectedSection = selectedSectionTemp
                                        writeAppData(appStoragePath, ds, appName)
                                        // close parent dialog
                                        changeFolderDialog!!.cancel()
                                        // restart with resume()
                                        reReadAppFileData = true
                                        onResume()
                                    })
                                builder.setNegativeButton(
                                    R.string.cancel,
                                    DialogInterface.OnClickListener { dialogRename, which -> folderMoreDialog!!.show() })
                                builder.show()
                            }
                            //  MORE FOLDER OPTIONS: Remove folder
                            if (which == 4) {
                                var builder: AlertDialog.Builder? = null
                                builder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        AlertDialog.Builder(
                                            folderMoreBuilderContext,
                                            android.R.style.Theme_Material_Dialog
                                        )
                                    } else {
                                        AlertDialog.Builder(folderMoreBuilderContext)
                                    }
                                builder.setTitle(getString(R.string.deleteFolder) + " '" + itemText + "' ?")
                                builder.setPositiveButton(
                                    R.string.ok,
                                    DialogInterface.OnClickListener { dialogChange, which ->
                                        decisionBox(
                                            this@MainActivity,
                                            DECISION.YESNO,
                                            getString(R.string.deleteFolder) + " - " + getString(R.string.continueQuestion),
                                            getString(R.string.noUndo),
                                            {
                                                // reset global search results
                                                if (fabBack != null) {
                                                    fabBack!!.visibility = INVISIBLE
                                                    val fbt = FabBackTag("", ArrayList(), -1)
                                                    fabBack!!.tag = fbt
                                                }
                                                // remove folder
                                                if (ds!!.namesSection.size > 1) {
                                                    ds!!.namesSection.removeAt(
                                                        selectedSectionTemp
                                                    )
                                                    ds!!.selectedSection = Math.max(selectedSectionTemp - 1, 0)
                                                    ds!!.dataSection.removeAt(selectedSectionTemp)
                                                } else {
                                                    ds!!.namesSection[selectedSectionTemp] = getString(R.string.folder)
                                                    ds!!.selectedSection = selectedSectionTemp
                                                    ds!!.dataSection[selectedSectionTemp] = ""
                                                }
                                                // save data
                                                writeAppData(appStoragePath, ds, appName)
                                                // close parent dialog
                                                changeFolderDialog!!.cancel()
                                                // restart with resume()
                                                reReadAppFileData = true
                                                onResume()
                                            }
                                        ) { folderMoreDialog!!.show() }
                                    })
                                builder.setNegativeButton(
                                    R.string.cancel,
                                    DialogInterface.OnClickListener { dialogRename, which -> folderMoreDialog!!.show() })
                                builder.show()
                            }
                            //  MORE FOLDER OPTIONS: Move folder up in list
                            if (which == 5) {
                                if (ds!!.namesSection.size < 2 || selectedSectionTemp == 0) {
                                    val folderName = ds!!.namesSection[selectedSectionTemp]
                                    centeredToast(
                                        this,
                                        "'" + folderName + "' " + getString(R.string.folderAlreadyAtTop),
                                        5000
                                    )
                                    onOptionsItemSelected(item)
                                    return@OnClickListener
                                }
                                // tmp save current folder -1
                                val nameTmp = ds!!.namesSection[selectedSectionTemp - 1]
                                ds!!.selectedSection = selectedSectionTemp - 1
                                val selectionTmp = ds!!.selectedSection
                                val dataTmp = ds!!.dataSection[selectedSectionTemp - 1]
                                // copy current folder one level up
                                ds!!.namesSection[selectedSectionTemp - 1] = ds!!.namesSection[selectedSectionTemp]
                                ds!!.selectedSection = selectedSectionTemp - 1
                                ds!!.dataSection[selectedSectionTemp - 1] = ds!!.dataSection[selectedSectionTemp]
                                // copy tmp to current
                                ds!!.namesSection[selectedSectionTemp] = nameTmp
                                ds!!.selectedSection = selectionTmp
                                ds!!.dataSection[selectedSectionTemp] = dataTmp
                                // make change permanent
                                writeAppData(appStoragePath, ds, appName)
                                reReadAppFileData = true
                                onOptionsItemSelected(item)
                            }
                            // MORE FOLDER OPTIONS: Timestamp setting
                            if (which == 6) {
                                val items = arrayOf<CharSequence>(
                                    getString(R.string.noTimestamp),
                                    "hh:mm",
                                    "hh:mm:ss"
                                )
                                var selection = ds!!.timeSection[selectedSectionTemp]
                                var dialog: AlertDialog?
                                var builder: AlertDialog.Builder?
                                builder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        AlertDialog.Builder(
                                            folderMoreBuilderContext,
                                            android.R.style.Theme_Material_Dialog
                                        )
                                    } else {
                                        AlertDialog.Builder(folderMoreBuilderContext)
                                    }
                                builder.setTitle(R.string.timestampSetting)
                                builder.setSingleChoiceItems(
                                    items,
                                    selection,
                                    DialogInterface.OnClickListener { dialog, which ->
                                        selection = which
                                    })
                                builder.setPositiveButton(
                                    "Ok",
                                    // ok takes over the recently selected option
                                    DialogInterface.OnClickListener { dialog, which ->
                                        ds!!.timeSection[selectedSectionTemp] = selection
                                        writeAppData(appStoragePath, ds, appName) // save data
                                        folderMoreDialog!!.show()                       // show more dlg again
                                    })
                                builder.setNegativeButton(
                                    R.string.cancel,
                                    DialogInterface.OnClickListener { dialog, which -> folderMoreDialog!!.show() })
                                dialog = builder.create()
                                val listView = dialog.listView
                                listView.divider = ColorDrawable(Color.GRAY)
                                listView.dividerHeight = 2
                                dialog.show()
                            }
                            // MORE FOLDER OPTIONS: search all folders
                            if (which == 7) {
                                // does fabBack has got useful data
                                val fbt = fabBack!!.tag as FabBackTag
                                if (fbt.searchHitListGlobal.size > 0) {
                                    decisionBox(
                                        this,
                                        DECISION.YESNO,
                                        getString(R.string.searchResultsAvailable),
                                        getString(R.string.useExistingResults),
                                        { prepareGlobalSearch(fbt.searchHitListGlobal) },
                                        { prepareGlobalSearch(ArrayList()) }
                                    )
                                } else {
                                    prepareGlobalSearch(ArrayList())
                                }
                            }
                        })

                    // MORE FOLDER OPTIONS back/cancel
                    folderMoreBuilder!!.setNegativeButton(R.string.back) { dialog, which -> changeFolderDialog!!.show() }
                    // MORE FILE OPTIONS show
                    folderMoreDialog = folderMoreBuilder!!.create()
                    val listView = folderMoreDialog?.getListView()
                    listView?.divider = ColorDrawable(Color.GRAY)
                    listView?.dividerHeight = 2
                    folderMoreDialog?.show( )
                })
            // CHANGE FOLDER finally show change folder dialog
            changeFolderDialog = changeFileBuilder.create()
            val listView = changeFolderDialog?.getListView()
            listView?.divider = ColorDrawable(Color.GRAY)
            listView?.dividerHeight = 2
            changeFolderDialog?.show()
        }
        return super.onOptionsItemSelected(item)
    }

    // provide global search results
    fun prepareGlobalSearch(searchHitListGlobal: MutableList<GlobalSearchHit>) {
        if (searchHitListGlobal.size > 0) {
            // re use global search hit list
            jumpToSearchHitInFolderDialog(this, searchHitListGlobal, -1)
        } else {
            // input dialog for global search phrase
            val inputSearch = EditText(this)
            inputSearch.inputType = InputType.TYPE_CLASS_TEXT
            inputSearch.setText("")
            showEditTextContextMenu(inputSearch, false)
            var inputBuilderDialog: AlertDialog? = null
            var inputBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
            inputBuilder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AlertDialog.Builder(
                        this,
                        android.R.style.Theme_Material_Dialog
                    )
                } else {
                    AlertDialog.Builder(this)
                }
            inputBuilder.setTitle(getString(R.string.searchPhrase))
            inputBuilder.setView(inputSearch)
            inputBuilder.setPositiveButton(R.string.ok) { dialog, which ->
                val searchText = inputSearch.text.toString()
                // no input --> get out
                if (searchText.isEmpty()) {
                    inputBuilderDialog!!.dismiss()
                    folderMoreDialog!!.show()
                    return@setPositiveButton
                }
                // find all search hits in DataStore
                var searchHitList: MutableList<GlobalSearchHit> = findTextInDataStore(this, searchText, lvMain)
                // nothing found --> get out
                if (searchHitList.size == 0) {
                    centeredToast(this, getString(R.string.nothingFound), 3000)
                    inputBuilderDialog!!.dismiss()
                    Handler().postDelayed({
                        folderMoreDialog!!.show()
                    }, 100)
                    return@setPositiveButton
                }
                // hide keyboard
                hideKeyboard(inputSearch)
                // render search hits and let user pick one to jump to
                Handler().postDelayed({
                    jumpToSearchHitInFolderDialog(this, searchHitList, -1)
                }, 50)
            }
            inputBuilder.setNegativeButton(R.string.cancel) { dialog, which ->
                folderMoreDialog?.show()
            }
            inputBuilderDialog = inputBuilder.create()
            val listView = folderMoreDialog?.getListView()
            listView?.divider = ColorDrawable(Color.BLACK)
            listView?.dividerHeight = 3
            inputBuilderDialog.show()
            inputSearch.requestFocus()
            showKeyboard(inputSearch, 0, 0, 250)
        }
    }

    // check for duplicate folder names
    fun isDupeFolder(newName: String): Boolean {
        var dupe = false
        for (name in ds!!.namesSection) {
            if (name.equals(newName)) {
                dupe = true
                break
            }
        }
        return dupe
    }

    fun verifyNotificationPermission(): Boolean {
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(baseContext, "Notifications are granted", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(baseContext, "Notifications are denied", Toast.LENGTH_LONG).show()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
//                Toast.makeText(baseContext, "Notifications were already granted", Toast.LENGTH_LONG).show()
                return true
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        } else {
            return true
        }
    }
    fun verifyMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
//                    Toast.makeText(baseContext, "Access media was already granted", Toast.LENGTH_LONG).show()
                    true
                } else {
                    requestPermissions(this,
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
                        PERMISSION_REQUEST_MEDIA)
                    false
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
//                    Toast.makeText(baseContext, "Access external storage was already granted", Toast.LENGTH_LONG).show()
                    true
                } else {
                    requestPermissions(this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        PERMISSION_REQUEST_MEDIA)
                    false
                }
            }
        } else { //permission is automatically granted on sdk<23 upon installation
            true
        }
    }
    fun verifyAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            if (Build.VERSION.SDK_INT >= 33) {
                // API >= 33
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
//                    Toast.makeText(baseContext, "Access audio was already granted", Toast.LENGTH_LONG).show()
                    true
                } else {
                    requestPermissions(this,
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                        PERMISSION_REQUEST_AUDIO)
                    false
                }
            } else {
                // API < 33
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
//                    Toast.makeText(baseContext, "Access external storage was already granted", Toast.LENGTH_LONG).show()
                    true
                } else {
                    requestPermissions(this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        PERMISSION_REQUEST_AUDIO)
                    false
                }
            }
        } else {
            // permission is automatically granted on sdk<23 upon installation
            true
        }
    }
    fun verifyCameraPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA ) == PackageManager.PERMISSION_GRANTED) {
//            Toast.makeText(baseContext, "Camera access was already granted", Toast.LENGTH_LONG).show()
            true
        } else {
            requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CAMERA )
            false
        }
    }
    fun verifyExifPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//            Toast.makeText(baseContext, "Access EXIF data was already granted", Toast.LENGTH_LONG).show()
                true
            } else {
                requestPermissions(this, arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), PERMISSION_REQUEST_EXIFDATA)
                false
            }
        } else {
            true
        }
    }

    //
    // extend class EditText to receive 'paste from menu'
    //
    @SuppressLint("AppCompatCustomView")
    inner class GrzEditText(context: Context) : EditText(
        context
    ) {
        override fun onTextContextMenuItem(id: Int): Boolean {
            val consumed = super.onTextContextMenuItem(id)
            when (id) {
                android.R.id.paste -> {
//// this is called AFTER the paste operation, this will override any text in editor
//                    val clpText = clipboard!!
//                    if (clpText.length > 0) {
//                        this.setText(clpText)
//                    } else {
//                        this.setText(shareBody)
//                    }
                }
            }
            return consumed
        }
    }

    // EditText has a context menu. It is sometimes annoying, when it pops up in an AlertBuilder.
    // https://stackoverflow.com/questions/41673185/disable-edittext-context-menu
    fun showEditTextContextMenu(input: EditText?, show: Boolean) {
        input!!.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // to keep the text selection capability available (selection cursor)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (!show) {
                    menu.clear()
                }
                return false
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return false
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    // file picker dlg for fabPlus
    fun startFilePickerDialog(
        attachmentAllowed: Boolean = true,
        linkText: String = "",
        adapterView: AdapterView<*>?,
        itemView: View?,
        itemPosition: Int,
        itemId: Long,
        returnToSearchHits: Boolean = false,
        function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)? = null)
    {
        // build a dialog
        var pickerBuilder: AlertDialog.Builder?
        pickerBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        } else {
            AlertDialog.Builder(this@MainActivity)
        }
        pickerBuilder.setTitle(R.string.Select)
        // file picker dialog OPTIONS
        val options = arrayOf<CharSequence>(
            getString(R.string.Image),
            getString(R.string.ImageApp),
            getString(R.string.Camera),
            "Video",
            "Audio",
            "PDF",
            "TXT",
            "WWW",
            getString(R.string.appFolder),
            getString(R.string.editExistingLink)
        )
        pickerBuilder.setItems(options, DialogInterface.OnClickListener { dialog, item ->
            // static memorize the return data
            ReturnToDialogData(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
            // common intent
            var intent: Intent?
            when (item) {
                0 -> { // IMAGE from Android
                    if (verifyMediaPermission()) {
                        // resolves permission fail on documents, but generates issue with GooglePhoto
                        val getIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        getIntent.type = "image/*"
                        getIntent.addCategory(Intent.CATEGORY_OPENABLE)
                        // allow picking additional pictures from documents
                        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        pickIntent.type = "image/*"
                        val chooserIntent = Intent.createChooser(getIntent, getString(R.string.pickImage))
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pickIntent))
                        startActivityForResult(chooserIntent, PICK.IMAGE)
                    } else {
                        dialog.dismiss()
                        startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        return@OnClickListener
                    }
                }
                1 -> { // IMAGE from GrzLog gallery
                    // start app gallery activity, if scan process is finished - under normal conditions, it's done before someone comes here
                    if (appGalleryScanning) {
                        centeredToast(this, getString(R.string.waitForFinish), Toast.LENGTH_SHORT)
                        var pw = ProgressWindow(this, getString(R.string.waitForFinish))
                        pw.dialog?.setOnDismissListener {
                            startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        }
                        pw.show()
                        pw.absCount = appScanTotal.toFloat()
                        pw.curCount = appScanCurrent
                        try {
                            Thread {
                                try {
                                    while (pw.curCount < pw.absCount) {
                                        runOnUiThread {
                                            pw.curCount = appScanCurrent
                                        }
                                        Thread.sleep(100)
                                    }
                                }
                                catch (ex: Exception) {}
                                catch (ex: OutOfMemoryError) {}
                                runOnUiThread {
                                    pw.close()
                                }
                            }.start()
                        } catch (e: Exception) {}
                        return@OnClickListener
                    } else {
                        val galleryIntent = Intent(this, GalleryActivity::class.java)
                        startActivity(galleryIntent)
                    }
                }
                2 -> { // CAPTURE from camera
                    if (verifyCameraPermission()) {
                        capturedPhotoUri = makeCameraCaptureIntent()
                    } else {
                        dialog.dismiss()
                        startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        return@OnClickListener
                    }
                }
                3 -> { // VIDEO
                    if (verifyMediaPermission()) {
                        // resolves permission fail on documents, but generates issue with GooglePhoto
                        val getIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        getIntent.type = "video/*"
                        getIntent.addCategory(Intent.CATEGORY_OPENABLE)
                        // allow picking additional pictures from documents
                        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        pickIntent.type = "video/*"
                        val chooserIntent = Intent.createChooser(getIntent, getString(R.string.pickImage))
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pickIntent))
                        startActivityForResult(chooserIntent, PICK.VIDEO)
                    } else {
                        dialog.dismiss()
                        startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        return@OnClickListener
                    }
                }
                4 -> { // AUDIO
                    if (verifyAudioPermission()) {
                        intent = Intent(Intent.ACTION_GET_CONTENT)
                        intent.type = "audio/*"
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        startActivityForResult(intent, PICK.AUDIO)
                    } else {
                        dialog.dismiss()
                        startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        return@OnClickListener
                    }
                }
                5 -> { // PDF
                    intent = Intent(Intent.ACTION_GET_CONTENT)
                    intent.type = "application/pdf"
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(intent, PICK.PDF)
                }
                6 -> { // TXT
                    intent = Intent(Intent.ACTION_GET_CONTENT)
                    intent.type = "text/plain"
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(intent, PICK.TXT)
                }
                7 -> { // WWW link
                    var wwwDialog: AlertDialog? = null
                    var tv = GrzEditText(this)
                    tv.inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    tv.setSingleLine(false)
                    tv.gravity = Gravity.LEFT or Gravity.TOP
                    tv.addTextChangedListener(object : TextWatcher {
                        // modify text input window according to text length
                        val fontSize = tv.textSize
                        val lineSpacingExtra = Math.max(tv.lineSpacingExtra, 25f)
                        val lineSpacingMultiplier = tv.lineSpacingMultiplier
                        val lineHeight = fontSize * lineSpacingMultiplier + lineSpacingExtra
                        val heightMax = resources.displayMetrics.heightPixels * 0.50f
                        fun setParentSize() {
                            if (wwwDialog != null) {
                                val wnd = (wwwDialog)?.getWindow()!!
                                if (wnd != null) {
                                    val corr = Math.min((tv.getLineCount() - 1) * lineHeight + 600, heightMax)
                                    wnd.setLayout(WindowManager.LayoutParams.MATCH_PARENT, corr.toInt())
                                }
                            }
                        }
                        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        }
                        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                        }
                        override fun afterTextChanged(s: Editable) {
                            setParentSize()
                        }
                    })
                    var wwwBuilder: AlertDialog.Builder
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        wwwBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
                        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                        if (!sharedPref.getBoolean("darkMode", false)) {
                            tv.setTextColor(Color.WHITE)
                        }
                    } else {
                        wwwBuilder = AlertDialog.Builder(this@MainActivity)
                        tv.setTextColor(Color.BLACK)
                    }
                    wwwBuilder.setTitle(getString(R.string.wwwLink))
                    wwwBuilder.setPositiveButton(
                        getString(R.string.ok),
                        DialogInterface.OnClickListener { dlg, which ->
                            fabPlus.pickAttachment = true
                            fabPlus.attachmentUri = tv.text.toString()
                            fabPlus.attachmentUriUri = null
                            fabPlus.attachmentName = "[www]"
                            dlg.dismiss()
                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                        })
                    wwwBuilder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dlg, which ->
                            dlg.dismiss()
                            startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                            return@OnClickListener
                        })
                    wwwBuilder.setView(tv)
                    wwwDialog = wwwBuilder.create()
                    wwwDialog.show()
                    wwwDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, 600)
                    wwwDialog.setCanceledOnTouchOutside(false)
                    tv.requestFocus()
                    showKeyboard(tv, 0, 0, 250)
                }
                8 -> { // link to a GrzLog folder
                    var getFolderBuilder: AlertDialog.Builder? = null
                    getFolderBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                    }
                    getFolderBuilder.setTitle(R.string.selectFolder)
                    var selectedSectionTemp = ds!!.selectedSection
                    // add a radio button list containing all the folder names from DataStore
                    val folderList = ds!!.namesSection.toTypedArray()
                    getFolderBuilder.setSingleChoiceItems(
                        folderList,
                        ds!!.selectedSection,
                        DialogInterface.OnClickListener { dialog, which ->
                            // the current file selection is temporary, unless we confirm with OK
                            selectedSectionTemp = which
                        })
                    // OK
                    getFolderBuilder.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialog, which ->
                            fabPlus.pickAttachment = true
                            fabPlus.attachmentUri = "folder/" + folderList[selectedSectionTemp]
                            fabPlus.attachmentUriUri = null
                            fabPlus.attachmentName = "[" + getString(R.string.folder) + "]"
                            dialog.dismiss()
                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                            dialog.cancel()
                        })
                    // Cancel
                    getFolderBuilder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialog, which ->
                            dialog.dismiss()
                            startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                            return@OnClickListener
                        })
                    // CHANGE FOLDER finally show change folder dialog
                    var getFolderDialog = getFolderBuilder.create()
                    val listView = changeFolderDialog?.getListView()
                    listView?.divider = ColorDrawable(Color.GRAY)
                    listView?.dividerHeight = 2
                    getFolderDialog?.show()
                }
                9 -> { // edit existing link
                    // edit dialog
                    var editLinkDialog: AlertDialog? = null
                    // text editor
                    var tv = GrzEditText(this)
                    tv.inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    tv.setSingleLine(false)
                    tv.gravity = Gravity.LEFT or Gravity.TOP
                    tv.addTextChangedListener(object : TextWatcher {
                        // modify text input window dimensions according to text length
                        val fontSize = tv.textSize
                        val lineSpacingExtra = Math.max(tv.lineSpacingExtra, 25f)
                        val lineSpacingMultiplier = tv.lineSpacingMultiplier
                        val lineHeight = fontSize * lineSpacingMultiplier + lineSpacingExtra
                        val heightMax = resources.displayMetrics.heightPixels * 0.50f
                        fun setParentSize() {
                            if (editLinkDialog != null) {
                                val wnd = (editLinkDialog)?.getWindow()!!
                                if (wnd != null) {
                                    val corr = Math.min((tv.getLineCount() - 1) * lineHeight + 700, heightMax)
                                    wnd.setLayout(WindowManager.LayoutParams.MATCH_PARENT, corr.toInt())
                                }
                            }
                        }
                        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        }
                        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                        }
                        override fun afterTextChanged(s: Editable) {
                            setParentSize()
                        }
                    })
                    // edit builder
                    var editLinkBuilder: AlertDialog.Builder
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        editLinkBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
                        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                        if (!sharedPref.getBoolean("darkMode", false)) {
                            tv.setTextColor(Color.WHITE)
                        }
                    } else {
                        editLinkBuilder = AlertDialog.Builder(this@MainActivity)
                        tv.setTextColor(Color.BLACK)
                    }
                    // edit builder title
                    editLinkBuilder.setTitle(getString(R.string.editExistingLink))
                    // edit builder positive button
                    editLinkBuilder.setPositiveButton(
                        getString(R.string.ok),
                        DialogInterface.OnClickListener { dlg, which ->
                            // get edited text
                            var newLink = tv.text
                            // prepare input for fabPlus edit dialog
                            val m = newLink?.let { PATTERN.UriLink.matcher(it.toString()) }
                            if (m?.find() == true) {
                                val result = m.group()
                                val key = result.substring(1, result.length - 1)
                                val lnkParts = key.split("::::".toRegex()).toTypedArray()
                                if (lnkParts != null && lnkParts.size == 2) {
                                    // prepare replace link internals in full string
                                    var oldReplace = ""
                                    var oldFullText = fabPlus.inputAlertText
                                    val old = oldFullText?.let { PATTERN.UriLink.matcher(it.toString()) }
                                    if (old?.find() == true) {
                                        oldReplace = old.group()
                                    }
                                    oldFullText = oldFullText!!.replace(oldReplace, "[" + lnkParts[0] + "]")
                                    fabPlus.inputAlertText = oldFullText
                                    fabPlus.attachmentName = "[" + lnkParts[0] + "]"
                                    fabPlus.attachmentUri = lnkParts[1]
                                    fabPlus.attachmentUriUri = null
                                    fabPlus.pickAttachment = true
                                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                                } else {
                                    // no link --> cancel
                                    centeredToast(this, getString(R.string.linkDestroyed), 3000)
                                    dialog.cancel()
                                    return@OnClickListener
                                }
                            } else {
                                // no link --> cancel
                                centeredToast(this, getString(R.string.linkDestroyed), 3000)
                                dialog.cancel()
                                return@OnClickListener
                            }
                        })
                    // edit builder Negative button
                    editLinkBuilder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dlg, which ->
                            dlg.cancel()
                            return@OnClickListener
                        })
                    // edit builder show
                    editLinkBuilder.setView(tv)
                    editLinkDialog = editLinkBuilder.create()
                    editLinkDialog.show()
                    editLinkDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, 700)
                    editLinkDialog.setCanceledOnTouchOutside(false)
                    tv.setText(linkText)
                    tv.requestFocus()
                    showKeyboard(tv, 0, 0, 250)
                    // detect cancel: Android back button OR editLinkBuilder NegativeButton
                    editLinkDialog.setOnCancelListener { dlg ->
                        // restart
                        startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                    }
                }
            }
            // close picker dialog
            dialog.dismiss()
        })
        // picker CANCEL button
        pickerBuilder.setPositiveButton(
            R.string.cancel,
            DialogInterface.OnClickListener { dialog, which ->
                dialog.cancel()
            })
        val pickerDialog = pickerBuilder.create()
        val listView = pickerDialog.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        // certain items shall be disabled, depending on the flag 'attachmentAllowed'
        listView!!.setOnHierarchyChangeListener(
            object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View) {
                    val text = (child as AppCompatTextView).text
                    val itemIndex: Int = options.indexOf(text)
                    child.setEnabled(true)
                    if (attachmentAllowed) {
                        if (itemIndex == 9) {
                            child.setEnabled(false)
                            child.setOnClickListener(null)
                        }
                    } else {
                        if (itemIndex < 9) {
                            child.setEnabled(false)
                            child.setOnClickListener(null)
                        }
                    }
                }
                override fun onChildViewRemoved(view: View?, view1: View?) {}
            })
        pickerDialog.show()
        pickerDialog.setCanceledOnTouchOutside(false)
        // detect cancel: Android back button OR editLinkBuilder NegativeButton
        pickerDialog.setOnCancelListener { dialog ->
            // reset static memorize the return data
            ReturnToDialogData.reset()
            // back to main input
            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
        }
    }

    // startFilePickerDialog() will end up here, Manifest --> android:launchMode="singleInstance"
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        fabPlus.imageCapture = false
        if (resultCode != RESULT_OK) {
            startFilePickerDialog(
                true,
                ReturnToDialogData.linkText,
                ReturnToDialogData.adapterView,
                ReturnToDialogData.itemView,
                ReturnToDialogData.itemPosition,
                ReturnToDialogData.itemId!!,
                ReturnToDialogData.returnToSearchHits,
                ReturnToDialogData.function
            )
            ReturnToDialogData.reset()
            return
        }
        if (requestCode == PICK.CAPTURE) {
            // force gallery scan
            galleryForceScan()
            // need to use global Uri from intent, bc camera app does not return uri to onActivityResult
            if (capturedPhotoUri != null) {
                fabPlus.imageCapture = true
                fabPlus.pickAttachment = true
                fabPlus.attachmentUri = FileUtils.getPath(this, capturedPhotoUri!!)
                fabPlus.attachmentName = getString(R.string.capture)
            } else {
// !! not going here after GCam usage !!
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
                ReturnToDialogData.reset()
                return
            }
        }
        if (requestCode == PICK.IMAGE) {
            try {
                var uriOri = data!!.data
                var uri = uriOri
                var imageUriString: String? = uriOri.toString()
                if (imageUriString!!.contains("com.google.android.apps.photos.contentprovider", ignoreCase = true)) {
                    // GooglePhoto needs extra care
                    val imagepath = FileUtils.getPath(this, uri!!)
                    val imagefile = File(imagepath.toString())
                    uri = Uri.fromFile(imagefile)
                    imageUriString = uri.toString()
                } else {
                    // documents & content
                    if (imageUriString.startsWith("content://")) {
                        imageUriString = getPath(this, uri!!)
                    }
                }
                if (uri != null) {
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = imageUriString
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentName = getString(R.string.image)
                } else {
                    startFilePickerDialog(
                        true,
                        ReturnToDialogData.linkText,
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                    ReturnToDialogData.reset()
                    return
                }
            } catch (e: Exception) {
                centeredToast(this, e.message.toString(), 3000)
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
                ReturnToDialogData.reset()
                return
            }
        }
        if (requestCode == PICK.VIDEO) {
            try {
                var uriOri = data!!.data
                var uri = uriOri
                var imageUriString: String? = uriOri.toString()
                if (imageUriString!!.contains("com.google.android.apps.photos.contentprovider", ignoreCase = true)) {
                    // GooglePhoto needs extra care
                    val imagepath = FileUtils.getPath(this, uri!!)
                    val imagefile = File(imagepath.toString())
                    uri = Uri.fromFile(imagefile)
                    imageUriString = uri.toString()
                } else {
                    // documents & content
                    if (imageUriString.startsWith("content://")) {
                        imageUriString = getPath(this, uri!!)
                    }
                }
                if (uri != null) {
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = imageUriString
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentName = getString(R.string.video)
                } else {
                    startFilePickerDialog(
                        true,
                        ReturnToDialogData.linkText,
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                    ReturnToDialogData.reset()
                    return
                }
            } catch (e: Exception) {
                centeredToast(this, e.message.toString(), 5000)
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    okBox(
                        this,
                        getString(R.string.androidIssue),
                        getString(R.string.useFilesPicker),
                        {
                            startFilePickerDialog(
                                true,
                                ReturnToDialogData.linkText,
                                ReturnToDialogData.adapterView,
                                ReturnToDialogData.itemView,
                                ReturnToDialogData.itemPosition,
                                ReturnToDialogData.itemId!!,
                                ReturnToDialogData.returnToSearchHits,
                                ReturnToDialogData.function
                            )
                        }
                    )
                } else {
                    startFilePickerDialog(
                        true,
                        ReturnToDialogData.linkText,
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                }
                ReturnToDialogData.reset()
                return
            }
        }
        if (requestCode == PICK.AUDIO) {
            var uriOri = data!!.data
            val uriPath = getPath(this, uriOri!!)
            val file = File(uriPath.toString())
            var uri = Uri.fromFile(file)
            if (uri != null) {
                fabPlus.pickAttachment = true
                fabPlus.attachmentUri = uri.toString()
                fabPlus.attachmentUriUri = uriOri
                fabPlus.attachmentName = getString(R.string.audio)
            } else {
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
                ReturnToDialogData.reset()
                return
            }
        }
        if (requestCode == PICK.PDF) {
            var uriOri = data!!.data
            try {
                val uriPath = getPath(this, uriOri!!)
                val file = File(uriPath.toString())
                var uriNew = Uri.fromFile(file)
                if (uriNew != null) {
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = uriNew.toString()
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentName = "[" + file.name + "]"
                } else {
                    startFilePickerDialog(
                        true,
                        ReturnToDialogData.linkText,
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                    ReturnToDialogData.reset()
                    return
                }
            }
            catch (e: Exception) {
                centeredToast(this, "PICK.PDF" + e.message.toString(), 3000)
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
                ReturnToDialogData.reset()
                return
            }
            catch (e: FileNotFoundException) {
                centeredToast(this, "PICK.PDF" + e.message.toString(), 3000)
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
                ReturnToDialogData.reset()
                return
            }
        }
        if (requestCode == PICK.TXT) {
            var uriOri = data!!.data
            try {
                val uriPath = getPath(this, uriOri!!)
                val file = File(uriPath.toString())
                var uriNew = Uri.fromFile(file)
                if (uriNew != null) {
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = uriNew.toString()
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentName = "[" + file.name + "]"
                } else {
                    startFilePickerDialog(false, "", null, null, -1, -1)
                    ReturnToDialogData(
                        true,
                        ReturnToDialogData.linkText,
                        ReturnToDialogData.adapterView,
                        ReturnToDialogData.itemView,
                        ReturnToDialogData.itemPosition,
                        ReturnToDialogData.itemId!!,
                        ReturnToDialogData.returnToSearchHits,
                        ReturnToDialogData.function
                    )
                    return
                }
            }
            catch (e: Exception) {
                centeredToast(this, "PICK.TXT" + e.message.toString(), 3000)
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
                ReturnToDialogData.reset()
                return
            }
            catch (e: FileNotFoundException) {
                centeredToast(this, "PICK.TXT" + e.message.toString(), 3000)
                startFilePickerDialog(
                    true,
                    ReturnToDialogData.linkText,
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function)
                ReturnToDialogData.reset()
                return
            }
        }

        // back to main input with the option to return there to the calling dialog
        fabPlusOnClick(
            ReturnToDialogData.adapterView,
            ReturnToDialogData.itemView,
            ReturnToDialogData.itemPosition,
            ReturnToDialogData.itemId!!,
            ReturnToDialogData.returnToSearchHits,
            ReturnToDialogData.function
        )
        ReturnToDialogData.reset()
    }

    // provide an intent to start the selected camera app
    fun makeCameraCaptureIntent(): Uri? {
        // ret val
        var uri: Uri? = null
        // capture camera picture
        val takePictureIntent = imageCaptureIntent
        // to go ahead, we need a camera app installed; manifest needs some <queries> for API >= Q
        if (takePictureIntent!!.resolveActivity(packageManager) == null) {
            Toast.makeText(this@MainActivity, R.string.AppMissing, Toast.LENGTH_SHORT).show()
            return uri
        }
        // create the file, where the photo should go
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val photoFile = createImageFile()
                uri = FileProvider.getUriForFile(
                    Objects.requireNonNull(
                        applicationContext
                    ), BuildConfig.APPLICATION_ID + ".provider", photoFile!!
                )
            } else {
                uri = createImageUri()
                // weird: delete the recently generated but empty file - otherwise we would have two images a)an empty one + b)the real one
                val file = File(uri.toString())
                file.delete()
            }
        } catch (ex: IOException) {
            return uri
        }
        if (uri != null) {
            // Intent captures image via camera app + stores image in gallery
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri) // <-- stores image in gallery
            startActivityForResult(takePictureIntent, PICK.CAPTURE)
        } else {
            // in failure case, jump back to fabPlus dialog
            centeredToast(this, getString(R.string.noCameraOp), Toast.LENGTH_LONG)
            fabPlus.button!!.performClick()
        }
        return uri
    }

    // "com.google.android.GoogleCamera" does not return an image after capture, the captured image needs to be selected from Gallery
    // AOSP camera will return an image to the calling app
    // >= Android 11 camera apps are not added during ACTION_IMAGE_CAPTURE scanning
    // access GCam camera
    val CAMERA_SPECIFIC_APPS = arrayOf("com.google.android.GoogleCamera")
    private fun getCameraSpecificAppsInfo(context: Context): List<ResolveInfo> {
        val resolveInfo: MutableList<ResolveInfo> = ArrayList()
        val pm = context.packageManager
        for (packageName in CAMERA_SPECIFIC_APPS) {
            resolveInfo.addAll(getCameraSpecificAppInfo(packageName, pm))
        }
        return resolveInfo
    }
    private fun getCameraSpecificAppInfo(
        packageName: String,
        pm: PackageManager
    ): List<ResolveInfo> {
        val specificCameraApp = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        specificCameraApp.setPackage(packageName)
        return pm.queryIntentActivities(specificCameraApp, 0)
    }
    val imageCaptureIntent: Intent?
        get() {
            var intentRet: Intent?
            var camPackageName = ""
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
            val useGoogleCamera = sharedPref.getBoolean("useGoogleCamera", false)
            if (useGoogleCamera) {
                val packageManager = this@MainActivity.packageManager
                val intentTmp = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val listCam = packageManager.queryIntentActivities(intentTmp, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // >= Android 11 camera apps are not added during ACTION_IMAGE_CAPTURE scanning
                    listCam.addAll(getCameraSpecificAppsInfo(this@MainActivity))
                }
                for (res in listCam) {
                    if (res.activityInfo.packageName == "com.google.android.GoogleCamera") {
                        camPackageName = res.activityInfo.packageName
                        break
                    }
                }
            }
            intentRet = if (camPackageName.isEmpty()) {
                // AOSP camera will return an image to the calling app
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            } else {
                // "com.google.android.GoogleCamera" does not return an image after capture, the captured image needs to be selected from Gallery
                packageManager.getLaunchIntentForPackage(camPackageName)
            }
            return intentRet
        }

    // force gallery scan https://stackoverflow.com/questions/4144840/how-can-i-refresh-the-gallery-after-i-inserted-an-image-in-android/9096984
    private fun galleryForceScan() {
        MediaScannerConnection.scanFile(
            this, arrayOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString() + "/GrzLog"
            ),
            null
        ) { path, uri ->
            runOnUiThread {
                Toast.makeText(this@MainActivity, path + "\n\n" + uri, Toast.LENGTH_LONG).show()
            }
        }
    }

    // create image file name, important: use the same directory here and in galleryForceScan(..) - I tried to obtain dir from photoPath but no success
    @Throws(IOException::class)
    private fun createImageFile(): File? {
        var image: File?
        val timeStamp =
            SimpleDateFormat("yyyyMMdd_HHmmss").format(Date()) // Create an image file name
        val imageFileName = "JPEG_$timeStamp.jpg"
        image = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = applicationContext.contentResolver
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, imageFileName)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
            contentValues.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + "GrzLog"
            )
            val imageUri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create new MediaStore record.")
            imageUri.path?.let { File(it) }
        } else {
            val storageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"GrzLog")
            File(storageDir, imageFileName)
        }
        return image
    }

    @Throws(IOException::class)
    private fun createImageUri(): Uri? {
        var imageUri: Uri?
        val timeStamp =
            SimpleDateFormat("yyyyMMdd_HHmmss").format(Date()) // Create an image file name
        val imageFileName = "JPEG_$timeStamp.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = applicationContext.contentResolver
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, imageFileName)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
            contentValues.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + "GrzLog"
            )
            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri == null) {
                throw IOException("Failed to create new MediaStore record.")
            }
        } else {
            val storageDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "GrzLog"
            )
            imageUri = Uri.fromFile(File(storageDir, imageFileName))
        }
        return imageUri
    }

    //
    // tricky way to show soft keyboard: https://stackoverflow.com/questions/4597690/how-to-set-timer-in-android
    //
    private fun showKeyboard(et: EditText?, selectStart: Int, selectStop: Int, msDelay: Int) {
        // show soft keyboard
        Handler().postDelayed({
            et!!.dispatchTouchEvent(
                MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN,
                    0f,
                    0f,
                    0
                )
            )
            et.dispatchTouchEvent(
                MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    0f,
                    0f,
                    0
                )
            )
            et.setSelection(selectStart, selectStop)
        }, msDelay.toLong())
        // sometimes the soft keyboard doesn't show up, check keyboard visibility 1000ms after the 1st show command
        Handler().postDelayed({ // check app window height
            val windowRect = Rect()
            lvMain.listView!!.getWindowVisibleDisplayFrame(windowRect)
            // check display height
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            // the lame 70% height comparison works well in portrait mode
            if (windowRect.height().toFloat() > displayMetrics.heightPixels * 0.7f) {
                et!!.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_DOWN,
                        0f,
                        0f,
                        0
                    )
                )
                et.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
                var start = Math.max(0, Math.min(selectStart, et.text.length))
                var stop = Math.max(0, Math.min(selectStop, et.text.length))
                et.setSelection(start, stop)
            }
        }, (msDelay + 1000).toLong())
    }
    private fun hideKeyboard(view: View) {
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // if image capture input is cancelled, we need to remove/delete the previously captured image
    fun deleteCapturedImage(uriString: String?) {
        if (uriString!!.length == 0) {
            return
        }
        val inputPath = getFileFromUri(this, Uri.parse(uriString))!!.absoluteFile.toString()
        val file = File(inputPath)
        if (file.exists()) {
            file.delete()
        }
    }

    // clipboard helpers
    private var clipboard: String?
        get() {
            val clipBoard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipBoard.primaryClip
            val item = clipData!!.getItemAt(0)
            return item.text.toString()
        }
        set(text) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.copiedText), text)
            clipboard.setPrimaryClip(clip)
        }

    // runnable helper for function pointer: https://programming.guide/java/function-pointers-in-java.html
    fun execUndo() {
        // clean up
        fabPlus.pickAttachment = false
        lvMain.editLongPress = false
        fabPlus.editInsertLine = false
        fabPlus.attachmentUri = ""
        fabPlus.inputAlertText = ""
        fabPlus.attachmentName = ""
        // return if empty undo data
        if (ds!!.undoSection.isEmpty() && ds!!.undoAction == ACTION.UNDEFINED) {
            ds!!.undoText = ""
            showMenuItemUndo()
            return
        }
        // execute undo, save and re-read saved data
        val lvCurrFstVisPos = lvMain.listView!!.firstVisiblePosition

        ds!!.dataSection[ds!!.selectedSection] = ds!!.undoSection              // update DataStore dataSection
        ds!!.undoSection = ""                                                  // reset is time critical: make change + highlight + undo --> postDelay 'no highlight' might screw up
        writeAppData(appStoragePath, ds, appName)
        ds!!.clear()
        ds = readAppData(appStoragePath)
        val dsText = ds!!.dataSection[ds!!.selectedSection]                    // raw data from DataStore
        title = ds!!.namesSection[ds!!.selectedSection]                        // set app title to folder Name
        lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)      // convert & format raw text to array
        lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList) // set adapter and populate main listview
        lvMain.listView!!.adapter = lvMain.adapter

        if (lvMain.arrayList!!.size == 0) {                                    // get out here, if list is empty
            fabPlus.button!!.show()
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            return
        }

        // calc highlight positions
        var posAbove = Math.max(0, Math.min(lvMain.selectedRow, lvMain.arrayList!!.size - 1))
        val posBelow = Math.max(0, Math.min(lvMain.selectedRow - 1, lvMain.arrayList!!.size - 1))
        // ListView scroll position
        var posScrol = if (lvCurrFstVisPos == lvMain.fstVisPos) lvCurrFstVisPos else posAbove
        // an added item has special rules
        if (ds!!.undoAction == ACTION.REVERTADD) {
            ds!!.undoAction = ACTION.UNDEFINED
            if (lvMain.showOrder == SHOW_ORDER.TOP) {
                posAbove = 0
            } else {
                posAbove = lvMain.arrayList!!.size - 1
            }
            posScrol = posAbove
        }
        // revert insert: highlight neighbour too
        if (ds!!.undoAction == ACTION.REVERTINSERT) {
            ds!!.undoAction = ACTION.UNDEFINED
            posScrol = posBelow
            lvMain.arrayList!![posBelow].setHighLighted(true)
        }
        // if tag section has useful data, aka delete a range
        var markRange = false
        if ((ds!!.tagSection.size > 0) and (ds!!.undoAction == ACTION.REVERTDELETE)) {
            posScrol = ds!!.tagSection[0]
            markRange = true
        }
        // highlight all previously deleted items stored in tag section
        try {
            if (markRange) {
                for (i in 1 until ds!!.tagSection.size) {
                    lvMain.arrayList!![ds!!.tagSection[i]].setHighLighted(true)
                }
            } else {
                lvMain.arrayList!![posAbove].setHighLighted(true)
            }
        } catch (e: Exception) {
            centeredToast(this, e.message, 3000)
        }
        // scroll listview
        lvMain.listView!!.setSelection(posScrol)

        // revoke temp. highlighting after timeout
        lvMain.listView!!.postDelayed({
            try {
                // de select
                if (markRange) {
                    for (i in 1 until ds!!.tagSection.size) {
                        lvMain.arrayList!![ds!!.tagSection[i]].setHighLighted(false)
                    }
                } else {
                    lvMain.arrayList!![posAbove].setHighLighted(false)
                    lvMain.arrayList!![posBelow].setHighLighted(false)
                }
                ds!!.tagSection.clear()
                // jump
                lvMain.listView!!.setSelection(posScrol)
                // inform listview adapter about the changes
                lvMain.adapter!!.notifyDataSetChanged()
                // finally reset undo action
                ds!!.undoAction = ACTION.UNDEFINED
            } catch (e: Exception) {
                centeredToast(this, e.message, 3000)
            }
        }, 3000)

        // final tasks
        if (menuSearchVisible && searchViewQuery.length > 0) {
            // UNDO op while search menu is open, shall restore the search hit selection status of all items
            lvMain.restoreFolderSearchHitStatus(searchViewQuery.lowercase(Locale.getDefault()))
        } else {
            // was hidden during input
            fabPlus.button!!.show()
        }

        // delete undo data but not undo action
        ds!!.undoSection = ""
        ds!!.undoText = ""
        showMenuItemUndo()
    }

    // generate RTF with some UI interaction
    fun generateRtf(
        folderName: String,
        rawText: String,
        share: Boolean,
        callingBuilder: AlertDialog.Builder?
    ) {
        var dpi = 300.0f
        val items = arrayOf<CharSequence>("150dpi", "200dpi", "300dpi --> 250kb/jpg", "400dpi", "600dpi")
        var selection = 2
        var dialog: AlertDialog?
        var builder: AlertDialog.Builder?
        builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        } else {
            AlertDialog.Builder(this@MainActivity)
        }
        builder.setTitle(getString(R.string.GenerateRTF) + " - " + getString(R.string.ChoosePrintQuality))
        builder.setSingleChoiceItems(
            items,
            selection,
            DialogInterface.OnClickListener { dlg, which -> selection = which })
        builder.setPositiveButton("Ok", DialogInterface.OnClickListener { dlg, which ->
            dpi = dpiSelection(selection)
            generateRtfFile(folderName, rawText, share, dpi)
        })
        builder.setNegativeButton(
            R.string.cancel,
            DialogInterface.OnClickListener { dlg, which ->
                callingBuilder?.show()
                return@OnClickListener
            })
        dialog = builder.create()
        val listView = dialog.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        dialog.show()
        dialog.setCanceledOnTouchOutside(false)
    }

    // RTF actions wrapper
    fun generateRtfFile(folderName: String, rawText: String, share: Boolean, dpi: Float) {
        // convert folder name into a valid file name
        val fileName = folderName.replace("[^a-zA-Z0-9\\.\\-]".toRegex(), "_")
        // custom progress dialog window
        var success = false
        var theme = android.R.style.Theme_DeviceDefault_Light
        val pw = ProgressWindow(this@MainActivity, getString(R.string.generatingRTF))
        pw.dialog?.setOnDismissListener {
            if (!share) {
                if (success) {
                    val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val fileAbsPath = "$downloadDir/$fileName.rtf"
                    okBox(
                        this@MainActivity,
                        getString(R.string.RTFcreated) + " = " + getString(R.string.success),
                        fileAbsPath
                    )
                } else {
                    okBox(
                        this@MainActivity,
                        getString(R.string.RTFcreated) + " = " + getString(R.string.Failure),
                        ""
                    )
                }
            }
        }
        pw.show()
        // format input regarding TOP / BOTTOM
        val textLines = formatRtfText(rawText)
        // set progress limit
        pw.absCount = (textLines.size - 1).toFloat()
        // generate RTF async in another thread
        try {
            Thread {
                try {
                    success = buildRTF(textLines, folderName, fileName, pw, share, dpi)
                } catch (ex: Exception) {
                    runOnUiThread {
                        okBox(
                            this@MainActivity,
                            "Memory",
                            "Try again with less image resolution or smaller image count."
                        )
                    }
                } catch (ex: OutOfMemoryError) {
                    runOnUiThread {
                        okBox(
                            this@MainActivity,
                            "Memory",
                            "Try again with less image resolution or smaller image count."
                        )
                    }
                }
                runOnUiThread {
                    pw.close()
                }
            }.start()
        } catch (e: Exception) {
            okBox(
                this@MainActivity,
                getString(R.string.Failure),
                getString(R.string.RTFcouldnotbecreated)
            )
        }
    }

    private fun formatRtfText(text: String): Array<String?> {
        // split input to lines
        var parts: Array<String?> = text.split("\\n+".toRegex()).toTypedArray()
        // arrange data array according to show order
        val tmpList: ArrayList<String?> = ArrayList()
        if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
            for (i in parts.indices) {
                if (Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", parts[i].toString())) {
                    tmpList.add(0, parts[i])
                } else {
                    // ascii '\t = 9 as almost invisible marker for header item (it acts like a single space)
                    if (parts[i].toString().startsWith(9.toChar())) {
                        // as soon as a header line appears, add it to the TOP of the temp list
                        tmpList.add(0, parts[i])
                    } else {
                        if (parts[i]!!.length == 0) {
                            tmpList.add(0, parts[i])
                        } else {
                            if (tmpList.size > 0) {
                                tmpList.add(1, parts[i])
                            } else {
                                tmpList.add(0, parts[i])
                            }
                        }
                    }
                }
            }
            parts = tmpList.toTypedArray()
        }
        return parts
    }

    fun buildRTF(
        textLines: Array<String?>,
        folderName: String?,
        fileName: String,
        pw: ProgressWindow,
        share: Boolean,
        dpi: Float
    ): Boolean {
        // basics
        val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val fileAbsPath = "$downloadDir/$fileName.rtf"
        val fos: FileOutputStream? =
            try {
                FileOutputStream(fileAbsPath)
            } catch (fnfe: FileNotFoundException) {
                return false
            }
        // create RTF
        val sb = StringBuilder("{\\rtf1\\ansi\n")
        // render headline: folder name
        sb.append(folderName).append("\\line\\par\n")
        // loop text lines
        for (ndx in textLines.indices) {
            // set progress
            runOnUiThread {
                pw.incCount = ndx
            }
            // current text line
            var textLine = textLines[ndx]
            // check for a date header in the current line
            if (textLine?.let { Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", it) } == true) {
                textLine = trimEndAll(textLine)
                if (textLine.length == 10) { // if week day name is missing, add it
                    textLine += dayNameOfWeek(textLine)
                }
            }
            // render a RTF line text directly to file (avoids utf8 / ANSI mess)
            // java String textLine utf8 (2 bytes) is converted to ANSI (1 byte) - ok für German Umlauts
            val ascii8Bytes = textLine!!.toByteArray(StandardCharsets.ISO_8859_1)
            try {
                // write to file all previous content from sb
                fos?.write(sb.toString().toByteArray())
                sb.setLength(0)
                // write to file ANSI text
                fos?.write(ascii8Bytes)
            } catch (ioe: IOException) {
            }
            // render RTF eol
            sb.append("\\line\n")
            // check for image link in current text line and embed it into RTF
            val m = PATTERN.UriLink.matcher(textLine)
            if (m.find()) {
                try {
                    val lnkString = m.group()
                    // reduce embedded image size to a size which is ok for 300dpi
                    val tn = getThumbNailData(lnkString, 140.0f, dpi)
                    // embed image int RTF
                    sb.append("{\\pict")
                        .append("\\picscalex49")
                        .append("\\picscaley49")
                        .append("\\picw").append(tn.bmpRenderWidth.toInt())
                        .append("\\pich").append(tn.bmpRenderHeight.toInt())
                        .append("\\picwgoal").append(tn.bmpRenderWidth.toInt() * 10)
                        .append("\\pichgoal").append(tn.bmpRenderHeight.toInt() * 10)
                        .append("\\pngblip\n") // for PNG images use pngblip - jpegblipn
                    // convert bmp byte stream into string
                    val stream = ByteArrayOutputStream()
                    tn.bmp!!.compress(Bitmap.CompressFormat.PNG, 0 /*ignored for PNG*/, stream)
                    val `is`: InputStream = ByteArrayInputStream(stream.toByteArray())
                    var count = 0
                    var hexStr = ""
                    var intVal = 0
                    while (true) {
                        intVal = `is`.read()
                        if (intVal == -1) {
                            break
                        }
                        hexStr = Integer.toHexString(intVal)
                        if (hexStr.length == 1) {
                            hexStr = "0$hexStr"
                        }
                        count += 2
                        sb.append(hexStr)
                        if (count == 128) {
                            count = 0
                            sb.append("\n")
                        }
                    }
                    // end of image
                    sb.append("}")
                    // ad a new paragraph
                    sb.append("\\par\n")
                } catch (ex: Exception) {
                    sb.append("}\\line\n")
                    sb.append(getString(R.string.imageFailure)).append("\\line\n")
                    sb.append("\\par\n")
                } catch (ex: OutOfMemoryError) {
                    sb.append("}\\line\n")
                    sb.append(getString(R.string.imageFailure)).append("\\line\n")
                    sb.append("\\par\n")
                }
                // partial write operation
                try {
                    if (sb.length > 5000000) {
                        fos?.write(sb.toString().toByteArray())
                        sb.setLength(0)
                    }
                } catch (ioe: IOException) {
                    return false
                }
            }
        }
        // RTF document end
        sb.append("}")
        // write RTF file into download folder
        try {
            fos?.write(sb.toString().toByteArray())
            fos?.close()
            // open share options
            if (share) {
                val sharingIntent = Intent(Intent.ACTION_SEND)
                sharingIntent.type = "application/rtf"
                val shareSub = "GrzLog"
                sharingIntent.putExtra(Intent.EXTRA_SUBJECT, shareSub)
                sharingIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(fileAbsPath))
                sharingIntent.putExtra(Intent.EXTRA_SUBJECT, "Sharing File...")
                sharingIntent.putExtra(Intent.EXTRA_TEXT, "Sharing File...")
                startActivity(Intent.createChooser(sharingIntent, "Share File"))
            }
        } catch (e: Exception) {
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
        return true
    }

    // BMP helper
    fun dpiSelection(selection: Int): Float {
        var dpi: Float
        dpi = when (selection) {
            0 -> 300.0f
            1 -> 400.0f
            2 -> 600.0f
            3 -> 800.0f
            4 -> 1200.0f
            else -> 300.0f
        }
        return dpi
    }

    // generate PDF with some UI interaction
    fun generatePdf(
        folderName: String,
        rawText: String,
        share: Boolean,
        callingBuilder: AlertDialog.Builder?
    ) {
        var dpi = 300.0f
        val items = arrayOf<CharSequence>("150dpi", "200dpi", "300dpi --> 250kb/jpg", "400dpi", "600dpi")
        var selection = 2
        var dialog: AlertDialog?
        var builder: AlertDialog.Builder?
        builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        } else {
            AlertDialog.Builder(this@MainActivity)
        }
        builder.setTitle(getString(R.string.GeneratePDF) + " - " + getString(R.string.ChoosePrintQuality))
        builder.setSingleChoiceItems(
            items,
            selection,
            DialogInterface.OnClickListener { dlg, which -> selection = which })
        builder.setPositiveButton("Ok", DialogInterface.OnClickListener { dlg, which ->
            dpi = dpiSelection(selection)
            generatePdfProgress(folderName, rawText, share, dpi, false)
        })
        builder.setNeutralButton(
            getString(R.string.ShowPDF),
            DialogInterface.OnClickListener { dlg, which ->
                dpi = dpiSelection(selection)
                generatePdfProgress(folderName, rawText, share, dpi, true)
            })
        builder.setNegativeButton(
            R.string.cancel,
            DialogInterface.OnClickListener { dlg, which ->
                callingBuilder?.show()
                return@OnClickListener
            })
        dialog = builder.create()
        val listView = dialog.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        dialog.show()
        dialog.setCanceledOnTouchOutside(false)
    }

    // PDF progress dialog
    fun generatePdfProgress(
        folderName: String,
        rawText: String,
        share: Boolean,
        dpi: Float,
        show: Boolean
    ) {
        var success = false
        val pw = ProgressWindow(this@MainActivity, getString(R.string.generatingPDF))
        pw.dialog?.setOnDismissListener {
            if (!share) {
                if (success) {
                    // convert folder name into a valid file name
                    val fileName = folderName.replace("[^a-zA-Z0-9\\.\\-]".toRegex(), "_")
                    val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val fileAbsPath = "$downloadDir/$fileName.pdf"
                    okBox(
                        this@MainActivity,
                        getString(R.string.PDFcreated) + " = " + getString(R.string.success),
                        fileAbsPath
                    )
                }
            }
            if (!success) {
                okBox(
                    this@MainActivity,
                    getString(R.string.PDFcreated) + " = " + getString(R.string.Failure),
                    "'" + folderName + ".pdf'\n" + getString(R.string.manualDeletePDF)
                )
            }
        }
        // generate PDF async in another thread
        try {
            pw.show()
            Thread {
                success = generatePdfFile(folderName, rawText, share, dpi, show, pw)
                runOnUiThread(Runnable {
                    pw.close()
                })
            }.start()
        } catch (e: Exception) {
            success = false
            runOnUiThread(Runnable {
                pw.close()
            })
            okBox(
                this@MainActivity,
                getString(R.string.Failure),
                getString(R.string.PDFcouldnotbecreated)
            )
        }
    }

    private fun formatPdfText(text: String): SpannableString {
        // ret val
        val ssb = SpannableStringBuilder()
        // prepare for spaces
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val width = displayMetrics.widthPixels * 0.9f
        // data array with links based on key
        var parts: Array<String?> = text.split("\\n+".toRegex()).toTypedArray()
        // arrange data array according to show order
        val tmpList: ArrayList<String?> = ArrayList()
        if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
            for (i in parts.indices) {
                if (Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", parts[i].toString())) {
                    tmpList.add(0, parts[i])
                } else {
                    // ascii '\t = 9 as almost invisible marker for header item (it acts like a single space)
                    if (parts[i].toString().startsWith(9.toChar())) {
                        // as soon as a header line appears, add it to the TOP of the temp list
                        tmpList.add(0, parts[i])
                    } else {
                        if (parts[i]!!.length == 0) {
                            tmpList.add(0, parts[i])
                        } else {
                            if (tmpList.size > 0) {
                                tmpList.add(1, parts[i])
                            } else {
                                tmpList.add(0, parts[i])
                            }
                        }
                    }
                }
            }
            parts = tmpList.toTypedArray();
        }
        // loop
        val len = parts.size
        for (i in 0 until len) {
            // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
            if (Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", parts[i].toString()) || parts[i].toString().startsWith(9.toChar())) {
                // no spaces at end of the row - ONLY in timestamp part, NOT in any other part
                parts[i] = trimEndAll(parts[i]!!)
                // headers shall be bold
                var ssPart = SpannableString(trimEndAll(parts[i].toString()) + "\n")
                if (parts[i].toString().startsWith(9.toChar())) {
                    // header via 9.toChar()
                    ssPart.setSpan(
                        BackgroundColorSpan(0x66777777),
                        0,
                        ssPart.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    // if week day name is missing, add it
                    if (parts[i]!!.length == 10) {
                        parts[i] += dayNameOfWeek(parts[i])
                    }
                    // the days Sat and Sun have a different background color
                    val dow = dayNumberOfWeek(parts[i])
                    if (dow == 1 || dow == 7) {
                        ssPart.setSpan(
                            BackgroundColorSpan(0x55FF5555),
                            0,
                            ssPart.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    } else {
                        ssPart.setSpan(
                            BackgroundColorSpan(0x66777777),
                            0,
                            ssPart.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                ssb.append(ssPart)
            } else {
                val ssPart = SpannableString(trimEndAll(parts[i].toString()) + "\n")
                ssb.append(ssPart)
            }
        }
        // remove last "\n" from final string
        if (ssb.length > 0) {
            ssb.delete(ssb.length - 1, ssb.length)
        }
        return SpannableString.valueOf(ssb)
    }

    // PDF generate execution
    fun generatePdfFile(
        folderName: String,
        rawText: String,
        share: Boolean,
        dpi: Float,
        show: Boolean,
        pw: ProgressWindow
    ): Boolean {
        // format input regarding TOP / BOTTOM
        val input = formatPdfText(rawText).toString()
        // convert folder name into a valid file name
        val fileName = folderName.replace("[^a-zA-Z0-9\\.\\-]".toRegex(), "_")
        // render PDF
        val document = renderPdf(input, folderName, dpi, pw)
        // save PDF file
        val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, "/$fileName.pdf")
        try {
            val fos = FileOutputStream(file)
            document.writeTo(fos)
            document.close()
            fos.close()
        } catch (e: IOException) {
            return false
        }
        // open share options
        if (share) {
            try {
                val sharingIntent = Intent(Intent.ACTION_SEND)
                sharingIntent.type = "application/pdf"
                val shareSub = "GrzLog"
                sharingIntent.putExtra(Intent.EXTRA_SUBJECT, shareSub)
                sharingIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(file.absolutePath))
                sharingIntent.putExtra(Intent.EXTRA_SUBJECT, "Sharing File...")
                sharingIntent.putExtra(Intent.EXTRA_TEXT, "Sharing File...")
                startActivity(Intent.createChooser(sharingIntent, "Share File"))
            } catch (e: Exception) {
                return false
            }
        }
        // in case of show
        return if (show) {
            try {
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    BuildConfig.APPLICATION_ID + ".provider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(uri, "application/pdf")
                // FLAG_GRANT_READ_URI_PERMISSION is needed on API 24+ so the activity opening the file can read it
                intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        } else true
    }

    // return renderable ThumbNail data from a text string, if it contains image data
    fun getThumbNailData(text: String, maxBmpHeight: Float, dpi: Float): ThumbNail {
        var tn = ThumbNail()
        tn.tag = text
        val m = PATTERN.UriLink.matcher(text)
        if (m.find()) {
            var lnkString = m.group()
            lnkString = lnkString.substring(1, lnkString.length - 1)
            var keyString: String = ""
            var uriString: String
            try {
                val lnkParts = lnkString.split("::::".toRegex()).toTypedArray()
                if (lnkParts != null && lnkParts.size == 2) {
                    keyString = lnkParts[0]
                    uriString = lnkParts[1]
                    if (uriString.startsWith("/")) {
                        val storagePathAppImages = "file://$appStoragePath/Images"
                        uriString = storagePathAppImages + uriString
                    }
                    if (uriString.length > 0) {
                        // try image first
                        val uri = Uri.parse(uriString)
                        tn = getThumbnailBitmap(uri)
                        tn.uriString = uriString
                        if (tn.bmp == null) {
                            // if no bmp, try video
                            if (VIDEO_EXT.contains(getFileExtension(uriString), ignoreCase = true)) {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(uriString)
                                val bitmap = retriever.getFrameAtTime(0)
                                tn.bmp = getDpiBitmap(bitmap, dpi)
                            }
                        } else {
                            // normal image
                            tn.bmp = getDpiBitmap(tn.bmp, dpi)
                        }
                        // render bmp regardless whether from image OR from video frame OR unrecognized
                        if (tn.bmp == null) {
                            tn.bmpRenderWidth = 10f
                            tn.bmpRenderHeight = 10f
                        } else {
                            var aspectRatio = tn.bmp!!.width.toFloat() / tn.bmp!!.height.toFloat()
                            tn.bmpRenderWidth = maxBmpHeight * aspectRatio
                            tn.bmpRenderHeight = maxBmpHeight
                        }
                    }
                }
            } finally {
            }
            tn.tag = text.replace(lnkString, keyString)
        }
        return tn
    }

    // draw image in PDF renderer
    fun drawPdfImage(tn: ThumbNail, painter: Paint, canvas: Canvas, xPos: Float, yPos: Float) {
        if (tn.bmp == null) {
            // empty bmp with cross
            tn.bmp = Bitmap.createBitmap(
                tn.bmpRenderWidth.toInt(),
                tn.bmpRenderHeight.toInt(),
                Bitmap.Config.ARGB_8888
            )
            canvas.drawBitmap(tn.bmp!!, xPos, yPos, painter)
            canvas.drawLine(xPos, yPos, xPos + tn.bmpRenderWidth, yPos, painter)
            canvas.drawLine(
                xPos,
                yPos + tn.bmpRenderHeight,
                xPos + tn.bmpRenderWidth,
                yPos + tn.bmpRenderHeight,
                painter
            )
            canvas.drawLine(xPos, yPos, xPos, yPos + tn.bmpRenderHeight, painter)
            canvas.drawLine(
                xPos + tn.bmpRenderWidth,
                yPos,
                xPos + tn.bmpRenderWidth,
                yPos + tn.bmpRenderHeight,
                painter
            )
            canvas.drawLine(
                xPos,
                yPos,
                xPos + tn.bmpRenderWidth,
                yPos + tn.bmpRenderHeight,
                painter
            )
            canvas.drawLine(
                xPos,
                yPos + tn.bmpRenderHeight,
                xPos + tn.bmpRenderWidth,
                yPos,
                painter
            )
        } else {
            val rcOut = Rect(
                xPos.toInt(),
                yPos.toInt(),
                (xPos + tn.bmpRenderWidth).toInt(),
                (yPos + tn.bmpRenderHeight).toInt()
            )
            // https://stackoverflow.com/questions/59781937/trying-to-crop-bitmap-using-mask-throws-illegalargumentexception/59785944#59785944
            var bmp = tn.bmp!!.copy(Bitmap.Config.ARGB_8888, true)
            canvas.drawBitmap(
                bmp,
                null,
                rcOut,
                painter
            ) // HIRES downscaled, but NOT reduced like canvas.drawBitmap(tn.bmp, posX, posY, painter);
        }
        // write image name
        painter.textSize = 4.0f
        var fn: String?
        fn = try {
            getPath(this@MainActivity, Uri.parse(tn.uriString))
        } catch (e: Exception) {
            tn.fileName
        }
        val pos = fn!!.lastIndexOf("/")
        if (pos != -1) {
            fn = ".." + fn.substring(pos)
        }
        canvas.drawText(fn, xPos, yPos + tn.bmpRenderHeight + 5, painter)
        painter.textSize = 14.0f
    }

    // final processing of a PDF page
    fun renderPdfFooter(
        document: PdfDocument,
        page: PdfDocument.Page?,
        painter: Paint,
        canvas: Canvas,
        pageNoOri: Int,
        marginL: Int,
        pageWidth: Int,
        pageHeight: Int
    ): Int {
        var pageNo = pageNoOri
        canvas.drawText(
            "- $pageNo -",
            (pageWidth / 2).toFloat(),
            (pageHeight - 10).toFloat(),
            painter
        )
        val appName =
            this@MainActivity.applicationInfo.loadLabel(this@MainActivity.packageManager).toString()
        painter.textSize = 8.0f
        canvas.drawText(
            getString(R.string.created_with) + appName + "\"",
            marginL.toFloat(),
            (pageHeight - 4).toFloat(),
            painter
        )
        document.finishPage(page)
        return ++pageNo
    }

    // a PDF renderer
    fun renderPdf(input: String, folderName: String?, dpi: Float, pw: ProgressWindow): PdfDocument {

        // create a blank, printable PDF document in memory
        val printAttrs = PrintAttributes.Builder().setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(Resolution("id", PRINT_SERVICE, 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        val document: PdfDocument = PrintedPdfDocument(this, printAttrs)

        // image dimension height is a FIXED value
        val BMP_HEIGHT = 140.0f

        // page description
        var pageNo = 1
        val pageWidth = 595 // A4  width in 1/72th of an inch
        val pageHeight = 842 // A4 height in 1/72th of an inch
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create()
        var page = document.startPage(pageInfo)

        // create canvas & painter
        var canvas = page.canvas
        val painter = Paint(Paint.ANTI_ALIAS_FLAG)

        // magic numbers
        val header = 60
        val marginL = 80
        val marginR = 10
        val lineHeight = 19
        painter.textSize = 25.0f

        // every page begins with the folder name
        canvas.drawText(folderName!!, marginL.toFloat(), 35f, painter)
        canvas.drawLine(marginL.toFloat(), 37f, (pageWidth - marginR).toFloat(), 37f, painter)
        painter.textSize = 14.0f

        // y render start position
        var yPos = header

        // text lines with image links
        val lines = input.split("\\n+".toRegex()).toTypedArray()

        // init progress
        runOnUiThread(Runnable {
            pw.absCount = (lines.size - 1).toFloat()
        })

        // OUTER LOOP: text lines fwith image links
        for (i in lines.indices) {

            // set progress
            runOnUiThread(Runnable {
                pw.incCount = i
            })

            // get current physical line
            var line = lines[i]

            // take a potential image link into account AND memorize the related thumbnail data
            var tn = getThumbNailData(line, BMP_HEIGHT, dpi)
            line = tn.tag

            // check whether to make a new page: EITHER one line OR one line + image height shall fit to the page
            var nextHeightOfs = if (tn.uriString.length > 0 // has current line an image?
            ) yPos + 5 + lineHeight + tn.bmpRenderHeight //     if line has an image
            else (yPos + 5 + 3 * lineHeight).toFloat() //     if line has no image
            nextHeightOfs += 4.0f
            if (nextHeightOfs.toInt() >= pageHeight) {
                // do final processing of the current page
                pageNo = renderPdfFooter(
                    document,
                    page,
                    painter,
                    canvas,
                    pageNo,
                    marginL,
                    pageWidth,
                    pageHeight
                )
                // create a page description
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                painter.textSize = 25.0f
                // every page begins with the folder name
                canvas.drawText(folderName, marginL.toFloat(), 35f, painter)
                canvas.drawLine(
                    marginL.toFloat(),
                    37f,
                    (pageWidth - marginR).toFloat(),
                    37f,
                    painter
                )
                painter.textSize = 14.0f
                // reset yPos
                yPos = header
            }

            // EITHER line with day header OR simple line: a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
            if (Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", line) || line.startsWith(9.toChar()) ) {
                var bgColor = Color.LTGRAY
                if (line.startsWith(9.toChar())) {
                    bgColor = Color.LTGRAY
                } else {
                    line = trimEndAll(line)
                    // if week day name is missing, add it
                    if (line.length == 10) {
                        line += dayNameOfWeek(line)
                    }
                    // timestamp headlines shall have different background colors
                    val dow = dayNumberOfWeek(line)
                    bgColor = if (dow == 1) 0x55DD1111 else if (dow == 7) 0x55FF5555 else Color.LTGRAY
                }
                painter.color = bgColor
                // normal text in timestamp headline
                yPos += lineHeight
                if (yPos >= pageHeight - 10 - lineHeight) {
                    painter.color = Color.BLACK
                    break
                }
                // render day header text line
                canvas.drawRect(
                    marginL.toFloat(),
                    (yPos - lineHeight).toFloat(),
                    (pageWidth - marginR).toFloat(),
                    (yPos - lineHeight + 22).toFloat(),
                    painter
                )
                painter.color = Color.BLACK
                canvas.drawText(line, marginL.toFloat(), yPos.toFloat(), painter)
                // render image belonging to a day header
                if (tn.uriString.length > 0) {
                    yPos += 5
                    drawPdfImage(tn, painter, canvas, marginL.toFloat(), yPos.toFloat())
                    tn = ThumbNail() // reset image link afterwards is actually not needed
                    yPos += lineHeight * 9 // increase yPos by height of image = 9 * line
                } else {
                    yPos += lineHeight // increase yPos just by one line
                }
            } else {
                // one physical line is allowed to be longer than the page width, so split a physical line by " " into words and build new printable lines
                var words = line.split(" ".toRegex()).toTypedArray()
                words = if (words.size > 0) words else arrayOf("")

                // we have two possible line widths: with image AND without image
                val lineWidthImage = pageWidth - marginL - 30 - tn.bmpRenderWidth.toInt()
                val lineWidthNoImage = pageWidth - marginL - 30

                // INNER LOOP: render printable lines from one physical line, split into words
                var lineNdx = 0
                var wordsNdx = 0
                val yPosImage = yPos + 5 // image y begins with the 2nd text line
                do {
                    // check whether to make a new page
                    nextHeightOfs = yPos + lineHeight + 5 + 4.0f
                    if (nextHeightOfs.toInt() >= pageHeight) {
                        // do final processing of the current page
                        pageNo = renderPdfFooter(
                            document,
                            page,
                            painter,
                            canvas,
                            pageNo,
                            marginL,
                            pageWidth,
                            pageHeight
                        )
                        // create a page description
                        pageInfo =
                            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create()
                        page = document.startPage(pageInfo)
                        canvas = page.canvas
                        painter.textSize = 25.0f
                        // every page begins with the folder name
                        canvas.drawText(folderName, marginL.toFloat(), 35f, painter)
                        canvas.drawLine(
                            marginL.toFloat(),
                            37f,
                            (pageWidth - marginR).toFloat(),
                            37f,
                            painter
                        )
                        painter.textSize = 14.0f
                        // reset yPos
                        yPos = header
                    }

                    // calculate current line width: long in 1st line; short from 2 ... 9; long for the rest
                    var maxLineLength = lineWidthNoImage
                    if (tn.uriString.length > 0 && lineNdx > 0 && lineNdx < 9) {
                        maxLineLength = lineWidthImage
                    }

                    // current xPos depends on whether we need to spare out the place for the image or not
                    var xPos = marginL
                    if (tn.uriString.length > 0 && lineNdx > 0) {
                        xPos = marginL + tn.bmpRenderWidth.toInt() + 5
                    }

                    // render the image as soon as floating text line 8 is done
                    if (tn.uriString.length > 0 && lineNdx == 8) {
                        drawPdfImage(tn, painter, canvas, marginL.toFloat(), yPosImage.toFloat())
                        tn = ThumbNail()
                    }

                    // collect words in printableLine until the currently selected line width limit is reached
                    var printableLine = ""
                    do {
                        printableLine += words[wordsNdx++] + " "
                        if (wordsNdx == words.size) {
                            break
                        }
                        if (words[wordsNdx - 1].length == 0) {
                            break
                        }
                    } while (painter.measureText(printableLine) < maxLineLength)
                    if (wordsNdx < words.size && words[wordsNdx - 1].length != 0) {
                        // step one word back
                        wordsNdx--
                        // remove last word from printable line
                        printableLine = trimEndAll(printableLine)
                        val end = printableLine.lastIndexOf(words[wordsNdx])
                        printableLine = printableLine.substring(0, end)
                    }

                    // render the current printable line
                    printableLine = trimEndAll(printableLine)
                    canvas.drawText(printableLine, xPos.toFloat(), yPos.toFloat(), painter)
                    lineNdx++

                    // render the image: aka no floating text == end of printable line is reached
                    if (tn.uriString.length > 0 && wordsNdx == words.size) {
                        drawPdfImage(tn, painter, canvas, marginL.toFloat(), yPosImage.toFloat())
                        tn = ThumbNail()
                        yPos =
                            yPosImage - 5 + lineHeight * 9 // yPos shall continue just below the image
                    } else {
                        // increase yPos
                        yPos += lineHeight
                    }
                } while (wordsNdx < words.size)
            }
        }

        // final processing of the last page
        renderPdfFooter(document, page, painter, canvas, pageNo, marginL, pageWidth, pageHeight)

        // return rendered PDF document
        return document
    }

    // get a per given dpi scaled image
    fun getDpiBitmap(source: Bitmap?, dpi: Float): Bitmap {
        val height = source!!.height
        val width = source.width
        val scaler = dpi / height
        return Bitmap.createScaledBitmap(
            source,
            (width.toFloat() * scaler).toInt(),
            (height.toFloat() * scaler).toInt(),
            true
        )
    }

    // generate a thumbnail bitmap from uri with correct image orientation
    class ThumbNail {
        var bmp: Bitmap? = null
        var fileName = ""
        var uriString = ""
        var bmpRenderWidth = 0.0f
        var bmpRenderHeight = 0.0f
        var tag = ""
    }

    fun getThumbnailBitmap(imageUriOri: Uri): ThumbNail {
        var imageUri = imageUriOri
        val tn = ThumbNail()
        var thumbBitmap: Bitmap? = null
        var bitmap: Bitmap?
        try {
            // 1st get full bitmap
            bitmap = when {
                Build.VERSION.SDK_INT < 28 -> MediaStore.Images.Media.getBitmap(
                    this.contentResolver,
                    imageUri
                )
                else -> {
                    val source = ImageDecoder.createSource(this.contentResolver, imageUri)
                    ImageDecoder.decodeBitmap(source)
                }
            }
        } catch (e: Exception) {
            // get bitmap may fail, if imageUri is document content with ID: content://com.android.providers.media.documents/document/image%3A157
            var file: File?
            try {
                // works well for file:// but may sometimes throw for content:// if it ends with a real file
                file = getFile(this@MainActivity, imageUri)
            } catch (fe: Exception) {
                // works well for content:// if it ends with a real file
                var imageStr = imageUri.toString()
                val contentStr = "content://com.grzwolf.grzlog.provider/external_storage_root/DCIM/GrzLog/"
                val fileStr = "file:///storage/emulated/0/DCIM/GrzLog/"
                if (imageStr.contains(contentStr, ignoreCase = true)) {
                    imageStr = imageStr.replace(contentStr, fileStr)
                    imageUri = Uri.parse(imageStr)
                }
                file = getFile(this@MainActivity, imageUri)
            }
            if (file == null) {
                // give up
                return tn
            }
            try {
                // now we have a new uri
                imageUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    BuildConfig.APPLICATION_ID + ".provider",
                    file
                )
                // try again if we get a bitmap
                bitmap = when {
                    Build.VERSION.SDK_INT < 28 -> MediaStore.Images.Media.getBitmap(
                        this.contentResolver,
                        imageUri
                    )
                    else -> {
                        val source = ImageDecoder.createSource(this.contentResolver, imageUri)
                        ImageDecoder.decodeBitmap(source)
                    }
                }
                
            } catch (exc: Exception) {
                // give up
                return tn
            }
        }
        try {
            // keep aspect ratio of original bitmap
            val ratio = bitmap!!.width.toFloat() / bitmap.height.toFloat()
            // App runs always Portrait --> bitmap width is the driving thing, aka display width - 200 pixels
            val display = windowManager.defaultDisplay
            val width = display.width - 200
            // reduce full bitmap to a thumbnail
            thumbBitmap = ThumbnailUtils.extractThumbnail(bitmap, width, (width.toFloat() / ratio).toInt())
            // try to get image orientation via exif and rotate accordingly
            val exifInterface = getExifInterface(this@MainActivity, imageUri)
            if (exifInterface == null) {
                // simple/awkward orientation fallback, if there is no exif data
                if (ratio > 1) {
                    val matrix = Matrix()
                    matrix.postRotate(90.0f)
                    thumbBitmap = Bitmap.createBitmap(
                        thumbBitmap,
                        0,
                        0,
                        thumbBitmap.width,
                        thumbBitmap.height,
                        matrix,
                        false
                    )
                }
            } else {
                // image orientation via exif
                val orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                var angle: Float
                if (orientation != 0) {
                    angle = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        ExifInterface.ORIENTATION_NORMAL -> 0f
                        ExifInterface.ORIENTATION_UNDEFINED -> -1f
                        else -> -1f
                    }
                    if (angle != -1f) {
                        val matrix = Matrix()
                        matrix.postRotate(angle)
                        thumbBitmap = Bitmap.createBitmap(
                            thumbBitmap,
                            0,
                            0,
                            thumbBitmap.width,
                            thumbBitmap.height,
                            matrix,
                            false
                        )
                    }
                }
            }
        } catch (ex: Exception) {
//            handled by caller
        }
        tn.bmp = thumbBitmap
        tn.fileName = imageUri.toString()
        return tn
    }

    //
    // lock screen notification
    //
    // create the NotificationChannel, only available on >= API 26+
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // channel 1 is the lockscreen reminder
            val name1: CharSequence = "grzlog"
            val description1 = "grzlog"
            val importance1 = NotificationManager.IMPORTANCE_DEFAULT
            val channel1 = NotificationChannel("grzlog", name1, importance1)
            channel1.description = description1
            val notificationManager1 = getSystemService(
                NotificationManager::class.java
            )
            notificationManager1.createNotificationChannel(channel1)
            // channel 2 is the silent backup progress indicator
            val name2: CharSequence = "GrzLog"
            val description2 = "Backup is ongoing"
            val importance2 = NotificationManager.IMPORTANCE_LOW
            val channel2 = NotificationChannel("GrzLog", name2, importance2)
            channel2.description = description2
            val notificationManager2 = getSystemService(
                NotificationManager::class.java
            )
            notificationManager2.createNotificationChannel(channel2)
        }
    }

    // lock screen notification: generate message
    fun generateLockscreenNotification(message: String?) {
        // notification number must be unique
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(
            applicationContext
        )
        var notificationNumber = sharedPref.getInt("notificationNumber", 0)
        // prepare notification
        val intentShow = Intent(applicationContext, MainActivity::class.java)
        val taskStackBuilder = TaskStackBuilder.create(
            applicationContext
        )
        taskStackBuilder.addParentStack(MainActivity::class.java)
        taskStackBuilder.addNextIntent(intentShow)
        val pendingIntent = taskStackBuilder.getPendingIntent(
            100,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // render lock screen info
        val builder = NotificationCompat.Builder(applicationContext, "grzlog")
            .setSmallIcon(R.mipmap.grz_launcher)
            .setContentTitle(getString(R.string.Reminder))
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setLights(Color.WHITE, 2000, 3000)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationNumber, builder.build())
        // notification number must be unique
        val spe = sharedPref.edit()
        spe.putInt("notificationNumber", ++notificationNumber)
        spe.apply()
    }

    // find text in all dataSections (folders) and return a list
    class GlobalSearchHit(textCombined: SpannableString, lineNdx: Int, folderName: String) {
        var textCombined = textCombined
        var lineNdx = lineNdx
        var folderName = folderName
    }

    // static components accessible from other fragments / activities
    companion object {
        @JvmField
        // app name
        var appName = ""
        // app has a menu bar: Search, Folder, Share, Settings
        var appMenu: Menu? = null
        // app external storage location
        var appStoragePath = ""
        // ListView shows data according to SHOW_ORDER
        @JvmField
        var lvMain = GrzListView()
        // global search: show a 'return to origin' button
        var fabBack: FloatingActionButton? = null
        // intennt controls whether to jump back to Settings
        var intentSettings: Intent? = null
        // DataStore holds all data "new at top"
        @JvmField
        var ds: DataStore? = null
        // this flag controls, whether onResume continues with onCreate()
        @JvmField
        var reReadAppFileData = false
        // returning from settings -> restore data shall not generate simple txt-backup in onPause()
        @JvmField
        var returningFromRestore = false
        // return a filename from app gallery picker
        @JvmField
        var folderMoreDialog: AlertDialog? = null
        var returningFromAppGallery = false
        var showFolderMoreDialog = false
        var returnAttachmentFromAppGallery = ""
        var appGalleryAdapter : GalleryActivity.ThumbGridAdapter? = null
        var appGalleryScanning = false
        var appScanCurrent = 0
        var appScanTotal = 0
        // backup ongoing
        @JvmField
        var backupOngoing = false

        // make context accessible from everywhere
        lateinit var contextMainActivity: MainActivity
            private set

        // static storage, if activity was left and then returned to it
        class ReturnToDialogData {
            companion object {
                var attachmentAllowed: Boolean = true
                var linkText: String = ""
                var adapterView: AdapterView<*>? = null
                var itemView: View? = null
                var itemPosition: Int = -1
                var itemId: Long? = -1
                var returnToSearchHits: Boolean = false
                var function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)? = null

                fun reset() {
                    ReturnToDialogData(false, "", null, null, -1, -1, false, null)
                }
            }

            constructor(attachmentAllowed: Boolean,
                        linkText: String,
                        adapterView: AdapterView<*>?,
                        itemView: View?,
                        itemPosition: Int,
                        itemId: Long?,
                        returnToSearchHits: Boolean,
                        function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)?) {
                ReturnToDialogData.attachmentAllowed = attachmentAllowed
                ReturnToDialogData.linkText = linkText
                ReturnToDialogData.adapterView = adapterView
                ReturnToDialogData.itemView = itemView
                ReturnToDialogData.itemPosition = itemPosition
                ReturnToDialogData.itemId = itemId
                ReturnToDialogData.returnToSearchHits = returnToSearchHits
                ReturnToDialogData.function = function
            }
        }

        // render search hits and let user pick one to jump to
        fun jumpToSearchHitInFolderDialog(context: Context, searchHitListGlobal: MutableList<GlobalSearchHit>, listNdx: Int) {
            // show search results and let user pick one to jump to
            val themedContext = ContextThemeWrapper(context, android.R.style.Theme_Holo_Light_Dialog_NoActionBar)
            val jumpFolderBuilder = AlertDialog.Builder(themedContext)
            var jumpFolderDialog: AlertDialog? = null
            jumpFolderBuilder.setTitle(contextMainActivity.getString(R.string.chooseSearchHit))
            var hitsNdx = listNdx
            var lastClickTime = System.currentTimeMillis()
            var lastSelectedSection = hitsNdx
            val hits = searchHitListGlobal.map(GlobalSearchHit::textCombined).toTypedArray()
            if (listNdx == -1 && hits.size == 1) {
                hitsNdx = 0
            }
            jumpFolderBuilder.setSingleChoiceItems(
                hits,
                hitsNdx,
                DialogInterface.OnClickListener { dialog, which ->
                    // double click shall act like ok button
                    hitsNdx = which
                    val nowTime = System.currentTimeMillis()
                    val deltaTime = nowTime - lastClickTime
                    if (deltaTime < 700 && lastSelectedSection == which) {
                        // programmatically click ok button
                        jumpFolderDialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick()
                    }
                    lastSelectedSection = which
                    lastClickTime = nowTime
                }
            )
            jumpFolderBuilder.setPositiveButton(R.string.jump) { dialog, which ->
                // sanity check
                if (hitsNdx in 0 until hits.size == false) {
                    centeredToast(context, contextMainActivity.getString(R.string.chooseSearchHit), 3000)
                    Handler().postDelayed({
                        jumpToSearchHitInFolderDialog(context, searchHitListGlobal, listNdx)
                    }, 100)
                    return@setPositiveButton
                }
                // make sure, MainActivity is is active (doesn't harm, if MainActivity is already active )
                val mainIntent = Intent(contextMainActivity, MainActivity::class.java)
                contextMainActivity.startActivity(mainIntent)
                // allow to jump back to search hit list dialog
                if (fabBack != null) {
                    val dsFolder = ds!!.namesSection[ds!!.selectedSection]
                    fabBack!!.tag = FabBackTag(dsFolder, searchHitListGlobal, hitsNdx)
                    fabBack!!.visibility = VISIBLE
                }
                // switch to the selected search hit in its folder
                var folderName = searchHitListGlobal[hitsNdx].folderName
                switchToFolderByName(folderName, searchHitListGlobal[hitsNdx].lineNdx)
            }
            jumpFolderBuilder.setNegativeButton(R.string.close) { dialog, which ->
                if (folderMoreDialog != null) {
                    folderMoreDialog!!.show()
                }
                if (intentSettings != null) {
                    contextMainActivity.startActivity(intentSettings)
                }
            }
            jumpFolderDialog = jumpFolderBuilder.create()
            jumpFolderDialog.show()
        }

        // find text in all dataSections (folders) and return a list
        fun findTextInDataStore(context: Context, searchText: String, lvMain: GrzListView): MutableList<GlobalSearchHit> {
            var hitList: MutableList<GlobalSearchHit> = ArrayList()
            // iterate all data sections of DataStore
            for (dsNdx in ds!!.dataSection.indices) {
                // text from DataStore folder
                var sectionText = ds!!.dataSection[dsNdx]
                // take show order into account
                var arrayList: ArrayList<ListViewItem> = lvMain.makeArrayList(sectionText, lvMain.showOrder)
                // loop arrayList
                var sectionName = ""
                for (i in arrayList.indices) {
                    // save most current section name: it will be used, if there is a search hit
                    if (PATTERN.DateDay.matcher(arrayList[i].title.toString()).find() || arrayList[i].title.toString().startsWith(8.toChar())) {
                        sectionName = arrayList[i].title.toString()
                    }
                    //
                    var inspectStr = arrayList[i].title.toString()
                    if (arrayList[i].fullTitle!!.length > 0) {
                        inspectStr = arrayList[i].fullTitle.toString()
                    }
                    // save search hits data: the text where the search phrase occurs, its line index, its section name, its folder index
                    if (inspectStr.contains(searchText, ignoreCase = true)) {
                        val folderName = ds!!.namesSection[dsNdx]
                        val textCombined = inspectStr + "\n(" + sectionName + " / " + folderName + ")"
                        val spanCombined = SpannableString(textCombined)
                        val searchTextStart = inspectStr.indexOf(searchText, ignoreCase = true)
                        spanCombined.setSpan(BackgroundColorSpan(ContextCompat.getColor(context, R.color.yellow)), searchTextStart, searchTextStart + searchText.length, 0)
                        spanCombined.setSpan(RelativeSizeSpan(0.9F), 0, inspectStr.length,0)
                        spanCombined.setSpan(RelativeSizeSpan(0.7F), inspectStr.length, textCombined.length,0)
                        hitList.add(GlobalSearchHit(spanCombined, i, folderName))
                    }
                }
            }
            return hitList
        }

        // show / hide menu item Undo; ds.undoAction == DataStore.ACTION.REVERTADD is special case, if DataStore is empty --> Add --> Cancel
        fun showMenuItemUndo() {
            val show = ds!!.undoSection.length > 0 || ds!!.undoAction == ACTION.REVERTADD || ds!!.undoAction == ACTION.REVERTINSERT
            if (appMenu != null) {
                val item = appMenu!!.findItem(R.id.action_Undo)
                item.isVisible = show
            }
        }

        // switch to a folder helpers
        fun switchToFolderByNumber(number: Int, highLightPos: Int = -1) {
            // sanity check
            if (number < 0 || number >= ds!!.dataSection.size) {
                if (fabBack != null) {
                    fabBack!!.visibility = INVISIBLE
                    val fbt = FabBackTag("", ArrayList(), -1)
                    fabBack!!.tag = fbt
                }
                centeredToast(contextMainActivity, "Index out of range", 3000)
                return
            }
            // switch folder always cancels undo
            ds!!.undoSection = ""
            ds!!.undoText = ""
            ds!!.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            // full infra to switch to a DataStore folder
            ds!!.selectedSection = number
            writeAppData(appStoragePath, ds, appName)
            val dsText = ds!!.dataSection[ds!!.selectedSection]
            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)
            lvMain.adapter = LvAdapter(contextMainActivity, lvMain. arrayList)
            lvMain.listView!!.adapter = lvMain.adapter
            contextMainActivity.title = ds!!.namesSection[ds!!.selectedSection]
            var scrollPos = if (lvMain.showOrder == SHOW_ORDER.TOP) 0 else lvMain.arrayList!!.size - 1
            // if presenting a global search hit, place it somehow vertically centered
            if (highLightPos != -1) {
                scrollPos = Math.max(0, highLightPos - 8)
            }
            // just scroll ListView
            lvMain.scrollToItemPos(scrollPos)
            // temporary highlight item
            if (highLightPos != -1) {
                lvMain.arrayList!![highLightPos].setHighLighted(true)
                // revoke temp. highlighting after timeout
                Handler().postDelayed({
                    lvMain.arrayList!![highLightPos].setHighLighted(false)
                    lvMain.adapter!!.notifyDataSetChanged()
                }, 3000)
            }
        }
        fun switchToFolderByName(name: String, scrollPos: Int = -1) {
            var folderNumber = -1
            val folderList = ds!!.namesSection.toTypedArray()
            for (i in folderList.indices) {
                if (folderList[i].equals(name)) {
                    folderNumber = i
                    break
                }
            }
            switchToFolderByNumber(folderNumber, scrollPos)
        }

        //
        // verify attachment links, make attachments app local (if needed) and delete orphaned files
        //
        fun tidyOrphanedFiles(context: Context, appAttachmentsPath: String, appName: String) {
            // clear app cache before doing anything
            deleteAppDataCache(MainActivity.contextMainActivity)
            val filePath = File(appAttachmentsPath)
            val attachmentsList = filePath.listFiles()
            val fileUsed = arrayOfNulls<Boolean>(attachmentsList!!.size)
            Arrays.fill(fileUsed, false)
            var oriText: String
            var newText: String
            // iterate all data section of DataStore
            for (dsNdx in ds!!.dataSection.indices) {

                // get one data section as string
                oriText = ds!!.dataSection[dsNdx]
                newText = ""

                // loop the split text line
                val textLines = oriText.split("\\n+".toRegex()).toTypedArray()
                for (i in textLines.indices) {
                    var textLine = textLines[i]
                    val m = PATTERN.UriLink.matcher(textLine)
                    // deal with a line containing a link pattern
                    if (m.find()) {
                        val lnkFull = m.group()
                        // only deal with lines containing "::::/", which is a file, not a www link
                        if (lnkFull.contains("::::/")) {
                            // get link parts: key and uri
                            val lnkStr = lnkFull.substring(1, lnkFull.length - 1)
                            var keyStrOri = ""
                            var uriStrOri = ""
                            var uriLocOri = ""
                            try {
                                val lnkParts = lnkStr.split("::::".toRegex()).toTypedArray()
                                if (lnkParts != null && lnkParts.size == 2) {
                                    keyStrOri = lnkParts[0]
                                    uriStrOri = Uri.parse(lnkParts[1]).path!!
                                    uriLocOri = uriStrOri
                                    if (uriStrOri.startsWith("/")) {
                                        uriLocOri = uriStrOri
                                        uriStrOri = "file://$appAttachmentsPath$uriStrOri"
                                    }
                                }
                            } finally {
                            }
                            // copy file to local app storage, or skip copy if file is already there
                            val localUriStr = copyAttachmentToApp(context, uriStrOri, null, appAttachmentsPath)
                            // set new link in current line (only needed if compared strings deviate)
                            if (localUriStr != uriLocOri) {
                                textLine = textLine.replace(lnkFull, "[$keyStrOri::::$localUriStr]")
                            }
                            // check file usage
                            for (j in attachmentsList.indices) {
                                val fn = attachmentsList[j].absolutePath
                                if (fn.endsWith(localUriStr)) {
                                    fileUsed[j] = true
                                    break
                                }
                            }
                        }
                    }
                    // all data in a single line
                    newText += trimEndAll(textLine) + "\n"
                    // write line back to DataStore
                    ds!!.dataSection[dsNdx] = newText
                }
            }

            // write DataStore to app file
            var appPath = File(appAttachmentsPath).parent
            if (appPath != null) {
                writeAppData(appPath.toString(), ds, appName)
            }

            // get number of orphaned files
            var numOrphaned = 0
            for (j in fileUsed.indices) {
                if (!fileUsed[j]!!) {
                    numOrphaned++
                }
            }

            // ask to delete unused files in app folder
            try {
                decisionBox(context,
                    DECISION.YESNO,
                    context.getString(R.string.CleanupFiles) + ": " + numOrphaned,
                    context.getString(R.string.continueQuestion),
                    { deleteOrphanes(context, attachmentsList, fileUsed) },
                    { null }
                )
            } catch(e: Exception) {
                var i = 5
            }
        }
        // delete unused files from app folder /Images
        fun deleteOrphanes(context: Context, fileList: Array<File>, fileUsed: Array<Boolean?>) {
            var filesDeleted = 0
            for (j in fileUsed.indices) {
                if (!fileUsed[j]!!) {
                    val fdelete = fileList[j]
                    if (fdelete.exists()) {
                        fdelete.delete()
                        filesDeleted++
                    }
                }
            }
            // silently scan app gallery data to show the recent change
            if (filesDeleted > 0) {
                getAppGalleryThumbsSilent(contextMainActivity)
            }
            // show result
            okBox(
                context,
                context.getString(R.string.CleanupFiles),
                filesDeleted.toString() + " " + context.getString(R.string.unusedFilesDeleted)
            )
        }
        // DataStore serialization write
        fun writeAppData(storagePath: String, data: DataStore?, appName: String) {
            try {
                val file = File(storagePath, "$appName.ser")
                val fos = FileOutputStream(file)
                val oos = ObjectOutputStream(fos)
                oos.writeObject(data)
                oos.close()
                fos.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        // needed after app gallery file deletion: https://stackoverflow.com/questions/23908189/clear-cache-in-android-application-programmatically
        fun deleteAppDataCache(context: Context) {
            try {
                val dir = context.cacheDir
                deleteDir(dir)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
        fun deleteDir(dir: File?): Boolean {
            return if (dir != null && dir.isDirectory) {
                val children = dir.list()
                for (i in children.indices) {
                    val success = deleteDir(File(dir, children[i]))
                    if (!success) {
                        return false
                    }
                }
                dir.delete()
            } else if (dir != null && dir.isFile) {
                dir.delete()
            } else {
                false
            }
        }

        // copy a file named by uriString to GrzLog Images folder and return just the filename (we don't need more than the filename, because we know the app storage path)
        fun copyAttachmentToApp(context: Context, uriString: String, uri: Uri?, appAttachmentPath: String): String {
            // file path
            var inputPath: String
            try {
                var testFile = getFileFromUri(context, Uri.parse(uriString))!!
                if (testFile.exists()) {
                    inputPath = testFile.absoluteFile.toString()
                } else {
                    // measure of last resort: if uriString is a real file
                    inputPath = uriString
                }
            } catch (ex: Exception) {
                // measure of last resort: if uriString is a real file
                inputPath = uriString
            }
            var fileName = ""
            var pos = inputPath.lastIndexOf("/")
            if (pos != -1) {
                fileName = inputPath.substring(pos)
            } else {
                return uriString + "." + ERROR_EXT
            }
            val outputPath = appAttachmentPath + fileName
            // no override if file already exists
            val file = File(outputPath)
            if (file.exists()) {
                // return the filename of the existing file with a leading /
                return fileName
            }
            // create /Images folder if needed
            val folder = File(appAttachmentPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            // make a copy
            var ins: InputStream?
            var out: OutputStream?
            try {
                ins = FileInputStream(inputPath)
                out = FileOutputStream(outputPath)
                val buffer = ByteArray(1024)
                var read: Int
                if (ins != null) {
                    while (ins.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
                if (ins != null) {
                    ins.close()
                }
                ins = null
                // write the output file
                out.flush()
                out.close()
                out = null
            }
            catch (fnfe: FileNotFoundException) {
                // measure of last resort: alien PDF files didn't copy to Images, perhaps this method helps
                var secondTryOk = false
                if (uri != null) {
                    secondTryOk = copyUriToAppImages(context, uri, outputPath)
                }
                if (!secondTryOk) {
                    return uriString + "." + ERROR_EXT
                }
            } catch (e: Exception) {
                return uriString + "." + ERROR_EXT
            }
            // return the filename of the copied file with a leading /
            return uriString.substring(uriString.lastIndexOf("/"))
        }
        // copy a file based on SAF Uri to GrzLog Images
        fun copyUriToAppImages(context: Context, inputUri: Uri?, outputFilePath: String): Boolean {
            var success = false
            try {
                // alien file is not allowed to open in >= API 30, but works with getContentResolver().openInputStream(inputUri);
                val inputStream = context.contentResolver.openInputStream(inputUri!!)
                // progress based on file size
                val bufferSize = 8 * 1024
                val outputStream = FileOutputStream(outputFilePath)
                var read: Int
                val buffers = ByteArray(bufferSize)
                var ndxBufRead = 0
                while (inputStream?.read(buffers).also { read = it!! } != -1) {
                    outputStream.write(buffers, 0, read)
                }
                inputStream?.close()
                outputStream.close()
                success = true
            } catch (e: Exception) {
                Log.d("copyUriToAppImages", e.message!!)
                success = false
            }
            return success
        }

        //
        // show app gallery
        //
        fun showAppGallery(context: Context, activity: Activity) {
            // start app gallery activity, if scan process is finished - under normal conditions, it's done before someone comes here
            if (appGalleryScanning) {
                // show a progress window of gallery scanning, when finished, return to provided AlertDialog
                centeredToast(context, context.getString(R.string.waitForFinish), Toast.LENGTH_SHORT)
                var pw = ProgressWindow(context, context.getString(R.string.waitForFinish))
                pw.dialog?.setOnDismissListener {
                    if (folderMoreDialog != null ) {
                        folderMoreDialog?.let { it.show() }
                    }
                }
                pw.show()
                pw.absCount = appScanTotal.toFloat()
                pw.curCount = appScanCurrent
                try {
                    Thread {
                        try {
                            while (pw.curCount < pw.absCount) {
                                activity.runOnUiThread {
                                    pw.curCount = appScanCurrent
                                }
                                Thread.sleep(100)
                            }
                        }
                        catch (ex: Exception) {}
                        catch (ex: OutOfMemoryError) {}
                        activity.runOnUiThread {
                            pw.close()
                        }
                    }.start()
                } catch (e: Exception) {}
            } else {
                val galleryIntent = Intent(context, GalleryActivity::class.java)
                galleryIntent.putExtra("ReturnPayload", false)
                activity.startActivity(galleryIntent)
            }
        }
    }

} // ------------------------------------------ MainActivity -----------------------------------------

//
// ListView wrapper class takes care about showOrder
//
class GrzListView {

    internal var arrayList : ArrayList<ListViewItem>? = null // ListView array
    internal var adapter : LvAdapter? = null                 // ListView adapter
    internal var showOrder = SHOW_ORDER.TOP                  // ListView show order: new on top -- vs. -- new at bottom

    var listView : ListView? = null                          // ListView itself
    var selectedText: String? = ""                           // selected text from listview after long press
    var selectedRow = -1                                     // selected row from listview after long press
    var selectedRowNoSpacers = -1                            // selected row from listview after long press MINUS Spacer items --> needed for DataStore index
    var editLongPress = false                                // flag indicates the usage of fabPlus input as a line editor
    var searchHitListFolder: MutableList<Int> = ArrayList()  // search hit list derived from ListView array for one folder
    var searchNdx = 0                                        // currently shown search hit index
    var fstVisPos = 0                                        // memorize 1st item position
    var lstVisPos = 0                                        // memorize last item position
    var touchSelectItem = false                              // touch event x < 200 allows to select an item at single click
    var scrollWhileSearch = false                            // allows to skip search hits when scrolling before Up / Down
    var touchEventPoint = Point(-1, -1)                // point coordinates of the latest touch event: needed to detect scrolling AND get word in string
    var singleClickHandler = Handler(Looper.getMainLooper()) // needed to distinguish between item's single and double click
    var itemLastClickTime: Long = 0                          // needed to distinguish between item's single and double click

    // generate ArrayList from a DataStore raw text to later populate the listview
    internal fun makeArrayList(rawText: String, lvShowOrder: SHOW_ORDER): ArrayList<ListViewItem> {
        // retval
        val arrayList = ArrayList<ListViewItem>()
        // split by \n and ignore empty lines and arrange data array according to show order
        val parts = splitDataStoreStringWithShowOrder(rawText, lvShowOrder)
        // loop parts and format items and headers accordingly
        for (i in parts.indices) {
            if (parts[i].length == 0) {
                continue
            }
            var itemTitle = parts[i]
            var fullTitle: String? = ""
            var uriString = ""
            // if a link is found, modify itemTitle to only show key name
            val lnkMatch = itemTitle.let { PATTERN.UriLink.matcher(it) }
            if (lnkMatch.find() == true) {
                var lnkString = lnkMatch.group()
                lnkString = lnkString.substring(1, lnkString.length - 1)
                var keyString = ""
                try {
                    val lnkParts = lnkString.split("::::".toRegex()).toTypedArray()
                    if (lnkParts != null && lnkParts.size == 2) {
                        keyString = lnkParts[0]
                        uriString = lnkParts[1]
                        fullTitle = parts[i]
                    }
                } finally {
                }
                itemTitle = parts[i].replace(lnkString, keyString)
            }
            // distinguish header & item: regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
            if (PATTERN.DateDay.matcher(itemTitle.toString()).find()) {
                // if week day name is missing, add it
                itemTitle = trimEndAll(itemTitle)
                if (itemTitle.length == 10) {
                    itemTitle += dayNameOfWeek(itemTitle)
                }
                // add spacer item before header, but not in the very first row of the list
                if (arrayList.size > 0) {
                    arrayList.add(SpacerItem())
                }
                // add listview header
                arrayList.add(SectionItem(itemTitle, fullTitle, uriString))
            } else {
                // ascii '\t = 9 as almost invisible marker for header item (it acts like a single space)
                if (itemTitle.startsWith(9.toChar())) {
                    // add spacer item before header, but not in the very first row of the list
                    if (arrayList.size > 0) {
                        arrayList.add(SpacerItem())
                    }
                    // add listview header
                    arrayList.add(SectionItem(itemTitle, fullTitle, uriString))
                } else {
                    // add normal listview item
                    arrayList.add(EntryItem(itemTitle, fullTitle, uriString))
                }
            }
        }
        return arrayList
    }

    // split a DataStore data string by \n, ignore empty lines, adjust to listview show order
    internal fun splitDataStoreStringWithShowOrder(dsText: String, lvShowOrder: SHOW_ORDER): Array<String> {
        var parts: Array<String> = dsText.split("\\n+".toRegex()).toTypedArray()
        if (lvShowOrder == SHOW_ORDER.BOTTOM) {
            var tmpList: ArrayList<String> = ArrayList()
            for (i in parts.indices) {
                val m = PATTERN.DateDay.matcher(parts[i])
                if (m.find() && parts[i].startsWith(m.group())) {
                    // header bc of date
                    tmpList.add(0, parts[i])
                } else {
                    // ascii '\t = 9 as almost invisible marker for header item (it acts like a single space)
                    if (parts[i].startsWith(9.toChar())) {
                        // as soon as a header line appears, add it to the TOP of the temp list
                        tmpList.add(0, parts[i])
                    } else {
                        var tmpStr = trimEndAll(parts[i])
                        if (tmpStr.isNotEmpty()) {
                            if (tmpList.size > 0) {
                                tmpList.add(1, tmpStr)
                            } else {
                                tmpList.add(0, tmpStr)
                            }
                        }
                    }
                }
            }
            parts = tmpList.toTypedArray();
        }
        return parts
    }

    // refresh/rebuild search hit list
    fun restoreFolderSearchHitStatus(searchText: String?) {
        try {
            val hitList: MutableList<Int> = ArrayList()
            for (i in arrayList!!.indices) {
                if (arrayList!![i].fullTitle!!.lowercase(Locale.getDefault()).contains(searchText!!, ignoreCase = true)) {
                    arrayList!![i].setSearchHit(true)
                    hitList.add(i)
                }
            }
            searchHitListFolder = hitList
            adapter!!.notifyDataSetChanged()
        } catch(e: Exception) {}
    }

    // unselect items runnable helper
    fun unselectSearchHits() {
        // remove search hits status
        try {
            for (pos in arrayList!!.indices) {
                arrayList!![pos].setSearchHit(false)
            }
            // refresh ListView
            adapter!!.notifyDataSetChanged()
        } catch(e: Exception) {}
    }

    // unselect all items' selection status
    fun unselectSelections() {
        // remove search hits status
        try {
            for (pos in arrayList!!.indices) {
                arrayList!![pos].setSelected(false)
            }
            // refresh ListView
            adapter!!.notifyDataSetChanged()
        } catch(e: Exception) {}
    }

    // select 10 more entries in ListView
    fun selectNextTenEntries(position: Int) {
        try {
            // get index of the so far last selected item
            var lastSelectedItemPos = -1
            for (i in 0 until arrayList!!.size) {
                if (arrayList!![i].isSelected()) {
                    lastSelectedItemPos = i
                }
            }
            // if nothing is so far selected, use argument as start, otherwise the last selected item pos
            var startPos = -1
            if (lastSelectedItemPos != -1) {
                // if position is not already selected. take it as start
                if (!arrayList!![position].isSelected()) {
                    startPos = Math.min(arrayList!!.size - 1, position)
                } else {
                    startPos = Math.min(arrayList!!.size - 1, lastSelectedItemPos + 1)
                }
            } else {
                if (!arrayList!![position].isSelected()) {
                    startPos = Math.min(arrayList!!.size - 1, position)
                } else {
                    startPos = Math.min(arrayList!!.size - 1, position + 1)
                }
            }

            // select next 10 items after given position
            var endPos = Math.min(arrayList!!.size - 1, startPos + 11)
            var i = startPos
            while (i < endPos) {
                arrayList!![i].setSelected(true)
                if (arrayList!![i].isSpacer) {
                    endPos++
                }
                i++
            }
        } catch(e: Exception) {}
    }

    // toggle selection of a complete day in ListView
    fun toggleSelectGivenDay(position: Int) {
        try {
            // get select toggle status
            var selectStatus = !arrayList!![position].isSelected()

            var dayStart = 0
            var dayEnd = -1
            // climb ListView up until a valid date is found
            for (i in position downTo 0) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu OR header via 9.toChar()
                val curText = arrayList!![i].title
                if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(9.toChar())) {
                    dayStart = i
                    break
                }
            }
            // climb ListView down until a valid date is found
            for (i in position until arrayList!!.size) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList!![i].title
                if (i > position) {
                    dayEnd = i
                    // stop climbing at Spacer
                    if (arrayList!![i].isSpacer) {
                        dayEnd = if (i > position) i - 1 else i
                        break
                    } else {
                        // stop climbing at Date
                        if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(9.toChar())) {
                            dayEnd = if (i > position) i - 1 else i
                            break
                        }
                    }
                } else {
                    dayEnd = i
                }
            }
            if (dayEnd == -1) {
                dayEnd = arrayList!!.size - 1
            }
            // select items according to the given range
            for (i in dayStart..dayEnd) {
                arrayList!![i].setSelected(selectStatus)
            }
        } catch(e: Exception) {
            Log.d("selectGivenDay", e.message!!)
        }
    }

    // toggle selection of a complete week in ListView
    fun toggleSelectGivenWeek(position: Int) {
        try {
            // get select toggle status
            var selectStatus = !arrayList!![position].isSelected()

            var dayChanges = 0
            var dayStart = 0
            var dayEnd = -1
            // climb ListView up until a Sunday is found
            for (i in position downTo 0) {
                // check '2020-03-03 Thu' pattern
                if ( arrayList!![i].isSection ) {
                    // a regex pattern for "yyyy-mm-dd
                    val curText = arrayList!![i].title
                    var m = PATTERN.Date.matcher(curText.toString())
                    if (m.find()) {
                        // care about skipped days
                        dayChanges++
                        if (dayChanges > 7) {
                            dayStart = position
                            break
                        }
                        // check for Sunday: Mon = 1 ... Sun = 7
                        var curr = m.group()
                        val date = LocalDate.parse(curr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        if (date.dayOfWeek.value == 7) {
                            dayStart = i
                            break
                        }
                    }
                }
            }
            // climb ListView down until a valid date is found
            dayChanges = 0
            for (i in position until arrayList!!.size) {
                // check '2020-03-03 Thu' pattern and extract name of day
                if ( arrayList!![i].isSection ) {
                    var splitArr = arrayList!![i].title?.split(" ")
                    if (splitArr?.size == 2) {
                        // a regex pattern for "yyyy-mm-dd
                        val curText = arrayList!![i].title
                        var m = PATTERN.Date.matcher(curText.toString())
                        if (m.find()) {
                            // care about skipped days
                            dayChanges++
                            if (dayChanges > 7) {
                                dayEnd = position
                                break
                            }
                            // check for Sunday: Mon = 1 ... Sun = 7
                            var curr = m.group()
                            val date = LocalDate.parse(curr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            if (date.dayOfWeek.value == 7) {
                                dayEnd = i - 1
                                break
                            }
                        }
                    }
                }
            }
            if (dayEnd == -1) {
                dayEnd = arrayList!!.size - 1
            }
            // select items according to the given range
            for (i in dayStart..dayEnd) {
                arrayList!![i].setSelected(selectStatus)
            }
        } catch(e: Exception) {
            Log.d("selectGivenWeek", e.message!!)
        }
    }

    // toggle selection of a complete month in ListView
    fun toggleSelectGivenMonth(position: Int) {
        try {
            // get select toggle status
            var selectStatus = !arrayList!![position].isSelected()

            var monthStartPos = 0
            var monthEndPos = -1
            var todayYearMonthStr = ""
            // climb ListView up until a date pattern is found, which is set to "today"
            for (i in position downTo 0) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList!![i].title
                if (PATTERN.DateDay.matcher(curText.toString()).find()) {
                    var m = PATTERN.DateMonth.matcher(curText.toString())
                    if (m.find()) {
                        todayYearMonthStr = m.group()
                    }
                    break
                }
            }
            if (todayYearMonthStr.length == 0) {
                return
            }

            // climb ListView up until the today's year-month pattern is NOT found
            for (i in position downTo 0) {
                // a regex pattern for "yyyy-mm-", sample 2020-03- OR header via 9.toChar()
                val curText = arrayList!![i].title
                // only check headers for a valid date pattern
                if (arrayList!![i].isSection && PATTERN.DateDay.matcher(curText.toString()).find()) {
                    // if the current year-month pattern differs from today's year-month patter, there is a match
                    var m = PATTERN.DateMonth.matcher(curText.toString())
                    if (m.find()) {
                        var curr = m.group()
                        if (curr != todayYearMonthStr) {
                            monthStartPos = i
                            break
                        }
                    }
                }
            }

            // climb ListView down until the today year-month pattern is NOT found
            for (i in position until arrayList!!.size) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList!![i].title
                // only check headers for a valid date pattern
                if (arrayList!![i].isSection && PATTERN.DateDay.matcher(curText.toString()).find()) {
                    // if the current year-month pattern differs from today's year-month patter, there is a match
                    var m = PATTERN.DateMonth.matcher(curText.toString())
                    if (m.find()) {
                        var curr = m.group()
                        if (curr != todayYearMonthStr) {
                            monthEndPos = Math.max(0, i-1)
                            break
                        }
                    }
                }
            }
            // if no other year-month pattern below today was found, select the list down zo its end
            if (monthEndPos == -1) {
                monthEndPos = arrayList!!.size - 1
            }

            // select items according to the above calculated range
            for (i in monthStartPos..monthEndPos) {
                arrayList!![i].setSelected(selectStatus)
            }

        } catch(e: Exception) {
            Log.d("selectGivenMonth", e.message!!)
        }
    }

    // return a complete day/section from ListView array as String
    fun getSelectedDay(position: Int): String {
        var retVal = ""
        try {
            var dayStart = 0
            var dayEnd = -1
            // climb ListView up until a valid date or the header sign 9.toChar() is found
            for (i in position downTo 0) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList!![i].title
                if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(9.toChar())) {
                    dayStart = i
                    break
                }
            }
            // climb ListView down until a valid date/header is found
            for (i in position until arrayList!!.size) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList!![i].title
                // stop climbing at Date or Header or Spacer
                if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(8.toChar()) || arrayList!![i].isSpacer) {
                    dayEnd = if (i > position) i - 1 else i
                    break
                }
            }
            if (dayEnd == -1) {
                dayEnd = arrayList!!.size - 1
            }
            // collect items according to the given range as String
            for (i in dayStart..dayEnd) {
                retVal += arrayList!![i].fullTitle + if (i < dayEnd) "\n" else ""
            }
        } catch(e: Exception) {}
        retVal = retVal.trimEnd('\n')
        return retVal
    }

    // return a list of selected indexes including the enclosed spacers
    fun getSelectedIndexList() : MutableList<Int> {
        var listSelPos: MutableList<Int> = ArrayList()
        for (i in 0 until arrayList!!.size) {
            if (arrayList!![i].isSelected()) {
                listSelPos.add(i)
            }
            if (arrayList!![i].isSpacer) {
                var posBefore = Math.max(0, i - 1)
                var posAfter = Math.min(arrayList!!.size - 1, i + 1)
                if (arrayList!![posBefore].isSelected() && arrayList!![posAfter].isSelected()) {
                    listSelPos.add(i)
                }
            }
        }
        return listSelPos
    }

    // return a complete folder from ListView array w/o spacers as String
    val selectedFolder: String
        // variable is accessed by its getter
        get() {
            // collect data with file links
            var retVal = ""
            for (i in arrayList!!.indices) {
                // collect everything but spacer
                if (!arrayList!![i].isSpacer) {
                    retVal += arrayList!![i].fullTitle + "\n"
                }
            }
            retVal = retVal.trimEnd('\n')
            return retVal
        }

    // return selected items from ListView array as String --> copy/paste
    val folderSelectedItems: String
        // variable is accessed by its getter
        get() {
            // collect selected items
            var retVal = ""
            try {
                for (i in arrayList!!.indices) {
                    if (arrayList!![i].isSelected()) {
                        retVal += arrayList!![i].fullTitle + "\n"
                    }
                }
            } catch(e: Exception) {}
            retVal = retVal.trimEnd('\n')
            return retVal
        }// collect data with file links

    // return search hit items from ListView array as String --> copy/paste
    val folderSearchHits: String
        // variable is accessed by its getter
        get() {
            // collect search hit items
            var retVal = ""
            try {
                for (i in arrayList!!.indices) {
                    if (arrayList!![i].isSearchHit()) {
                        retVal += arrayList!![i].fullTitle + "\n"
                    }
                }
            } catch(e: Exception) {}
            retVal = retVal.trimEnd('\n')
            return retVal
        }

    // scroll main listview to an item position
    fun scrollToItemPos(pos: Int) {
        try {
            listView!!.postDelayed({
                listView!!.adapter = adapter
                listView!!.setSelection(pos)
            }, 50)
        } catch(e: Exception) {}
    }
}

// ListView item interface
internal interface ListViewItem : Serializable {
    val isSection: Boolean
    val isSpacer: Boolean
    val title: String?
    val fullTitle: String?
    val uriStr: String?
    fun setSearchHit(setVal: Boolean)
    fun isSearchHit(): Boolean
    fun setSelected(setVal: Boolean)
    fun isSelected(): Boolean
    fun setHighLighted(setVal: Boolean)
    fun isHighLighted(): Boolean
}

// ListView item section header
internal class SectionItem(
    title: String,
    fullTitle: String?,
    uriStr: String?
) : ListViewItem {

    override var title: String? = title
        get() = field
    override var fullTitle: String? = fullTitle
        get() = if (field?.isNotEmpty() == true) field else title
    override var uriStr: String? = uriStr
        get() = field

    internal var searchHit = false
    internal var selected = false
    internal var highLighted = false

    override fun setSearchHit(setVal: Boolean) {
        searchHit = setVal
    }

    override fun isSearchHit(): Boolean {
        return searchHit
    }

    override fun setSelected(setVal: Boolean) {
        selected = setVal
    }

    override fun isSelected(): Boolean {
        return selected
    }

    override fun setHighLighted(setVal: Boolean) {
        highLighted = setVal
    }

    override fun isHighLighted(): Boolean {
        return highLighted
    }

    override val isSection: Boolean
        get() = true
    override val isSpacer: Boolean
        get() = false
}

// ListView item normal entry
internal class EntryItem(
    title: String?,
    fullTitle: String?,
    uriStr: String?
) : ListViewItem {

    override var title: String? = title
        get() = field
    override var fullTitle: String? = fullTitle
        get() = if (field?.isNotEmpty() == true) field else title
    override var uriStr: String? = uriStr
        get() = field
    internal var searchHit = false
    internal var selected = false
    internal var highLighted = false

    override fun setSearchHit(setVal: Boolean) {
        searchHit = setVal
    }

    override fun isSearchHit(): Boolean {
        return searchHit
    }

    override fun setSelected(setVal: Boolean) {
        selected = setVal
    }

    override fun isSelected(): Boolean {
        return selected
    }

    override fun setHighLighted(setVal: Boolean) {
        highLighted = setVal
    }

    override fun isHighLighted(): Boolean {
        return highLighted
    }

    override val isSection: Boolean
        get() = false
    override val isSpacer: Boolean
        get() = false
}

// ListView item spacer between last item and header
internal class SpacerItem : ListViewItem {
    override val title: String
        get() = " "
    override val fullTitle: String
        get() = ""
    override val uriStr: String
        get() = ""

    override fun setSearchHit(setVal: Boolean) {}
    override fun isSearchHit(): Boolean {
        return false
    }

    override fun setSelected(setVal: Boolean) {}
    override fun isSelected(): Boolean {
        return false
    }

    override fun setHighLighted(setVal: Boolean) {}
    override fun isHighLighted(): Boolean {
        return false
    }

    override val isSection: Boolean
        get() = false
    override val isSpacer: Boolean
        get() = true
}

// adapter for ListView
internal class LvAdapter : BaseAdapter {
    private var context: Context? = null
    private var items: ArrayList<ListViewItem>? = null
    private var iconColor: Int = 0
    private var res = android.R.drawable.ic_dialog_alert

    constructor() : super() {}
    constructor(context: Context?, items: ArrayList<ListViewItem>?) {
        this.context = context
        this.items = items
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context!!)
        if (sharedPref.getBoolean("darkMode", false)) {
            this.iconColor = R.color.lightskyblue
        } else {
            this.iconColor = R.color.royalblue
        }
    }

    var lp = RelativeLayout.LayoutParams(
        RelativeLayout.LayoutParams.WRAP_CONTENT,
        RelativeLayout.LayoutParams.WRAP_CONTENT
    )

    override fun getCount(): Int {
        return items!!.size
    }

    override fun getItem(position: Int): Any {
        return items!![position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var cv : View
        val inflater = context!!.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val tv: TextView
        if (items!![position].isSection) {
            // if section header
            cv = inflater.inflate(R.layout.layout_section, parent, false)
            tv = cv.findViewById(R.id.tvSectionTitle)
            tv.text = items!![position].title
            if (items!![position].isSelected()) {
                val checkView = cv.findViewById<View>(R.id.check) as ImageView
                checkView.visibility = VISIBLE
            }
        } else {
            if (items!![position].isSpacer) {
                // if spacer
                cv = inflater.inflate(R.layout.layout_spacer, parent, false)
                tv = cv.findViewById(R.id.tvItemSpacer)
                tv.text = items!![position].title
            } else {
                // if item
                cv = inflater.inflate(R.layout.layout_item, parent, false)
                tv = cv.findViewById(R.id.tvItemTitle)
                tv.text = items!![position].title
                if (items!![position].isSelected()) {
                    // set checkbox icon
                    val checkView = cv.findViewById<View>(R.id.check) as ImageView
                    checkView.visibility = VISIBLE
                    // move text right to icon
                    lp.setMargins(70, 0, 0, 0)
                    tv.layoutParams = lp
                }
            }
        }
        // if item is a search hit
        if (items!![position].isSearchHit()) {
            tv.setTextColor(ContextCompat.getColor(context!!, R.color.contrastgreen))
        }
        // if item is selected
        if (items!![position].isSelected()) {
            tv.setTextColor(Color.BLACK)
            cv.setBackgroundColor(Color.YELLOW)
        }
        // if item is temporarily highlighted
        if (items!![position].isHighLighted()) {
            tv.setTextColor(Color.BLACK)
            cv.setBackgroundColor(Color.GREEN)
        }
        // handle attachment links and their icons
        var text = items!![position].title
        var spanStr = SpannableString(text)
        if (items!![position].title?.contains("[") == true) {
            // place icon left to key and after the opening bracket
            val start = text?.indexOf('[')?.plus(1)
            val stop = text?.indexOf(']')?.plus(1)
            var mime = ""
            if ( stop != -1 ) {
                // insert a " " as icon placeholder
                text = text?.substring(0, start!!) + " " + text?.substring(start!!)
                spanStr = SpannableString(text)
                // set icon via spannable
                res = android.R.drawable.ic_dialog_alert
                // title could contain a mime, so exec mime detection on the last part of fullTitle
                mime = getFileExtension(
                    items!![position].fullTitle!!.substring(
                        items!![position].fullTitle!!.indexOf("::::"),
                        items!![position].fullTitle!!.lastIndexOf("]")
                    ))
            }
            if (mime.length > 0) {
                if (IMAGE_EXT.contains(mime, ignoreCase = true)) {
                    res = android.R.drawable.ic_menu_camera
                } else {
                    if (AUDIO_EXT.contains(mime, ignoreCase = true)) {
                        res = android.R.drawable.ic_lock_silent_mode_off
                    } else {
                        if (VIDEO_EXT.contains(mime, ignoreCase = true)) {
                            res = R.drawable.ic_video
                        } else {
                            if (mime.equals("pdf", ignoreCase = true)) {
                                res = R.drawable.ic_pdf
                            } else {
                                if (mime.equals("txt", ignoreCase = true)) {
                                    res = android.R.drawable.ic_dialog_email
                                } else {
                                    // mime could be .file-error due to a lost attachment file
                                    if (!mime.equals(ERROR_EXT, ignoreCase = true)) {
                                        // www attachment link goes here, bc www mime is not empty
                                        val fullItemText = items!![position].fullTitle
                                        val m = fullItemText?.let { PATTERN.UriLink.matcher(it.toString()) }
                                        if (m?.find() == true) { // www attachment link
                                            val result = m.group()
                                            val key = result.substring(1, result.length - 1)
                                            val lnkParts = key.split("::::".toRegex()).toTypedArray()
                                            if (lnkParts != null && lnkParts.size == 2) {
                                                var fileName = lnkParts[1]
                                                if (fileName.startsWith("/") == false) {
                                                    res = android.R.drawable.ic_menu_compass
                                                }
                                                // in case a folder name contains a .
                                                if (fileName.startsWith("folder/")) {
                                                    res = android.R.drawable.ic_menu_agenda
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // check for folder attachment link, if folder name does not contain a .
                if ( start != -1 && stop != -1 ) {
                    val fullItemText = items!![position].fullTitle
                    val m = fullItemText?.let { PATTERN.UriLink.matcher(it.toString()) }
                    if (m?.find() == true) {
                        val result = m.group()
                        val key = result.substring(1, result.length - 1)
                        val lnkParts = key.split("::::".toRegex()).toTypedArray()
                        if (lnkParts != null && lnkParts.size == 2) {
                            var fileName = lnkParts[1]
                            if (fileName.startsWith("folder/")) {
                                res = android.R.drawable.ic_menu_agenda
                            }
                        }
                    }
                }
            }
            val drawable = AppCompatResources.getDrawable(context!!, res)
            drawable?.setBounds(0, 0, tv.lineHeight, tv.lineHeight)
            drawable?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(ContextCompat.getColor(context!!, iconColor), BlendModeCompat.SRC_ATOP)
            val icon = ImageSpan(drawable!!, ImageSpan.ALIGN_BOTTOM)
            if (stop != null) {
                spanStr.setSpan(icon, start!!, start + 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
                tv.text = spanStr
            }
        }
        // www links in item text: change color & underline !!reuse: text and spanStr as above, bc. of the pontentially added " "
        var urls: ArrayList<String>? = getAllLinksFromString(text.toString())
        if (urls != null && urls.size > 0) {
            for (url in urls) {
                spanStr.apply {
                    var start = text!!.indexOf(url)
                    if (start != -1) {
                        setSpan(ForegroundColorSpan(ContextCompat.getColor(context!!, R.color.cadetblue)), start, start + url.length, 0)
                        setSpan(UnderlineSpan(), start, start + url.length, 0)
                    }
                }
            }
            tv.text = spanStr
        }

        return cv
    }
} //

// floating action button wrapper
//
class FabPlus {
    var button: FloatingActionButton? = null // user data input button
    var inputAlertView: GrzEditText? = null  // edit control inside of the AlertDialog.Builder
    var editInsertLine = false               // flag indicates the insertion of a line
    var inputAlertText: String? = ""         // last content of edit control inside of the AlertDialog.Builder
    var attachmentUri: String? = ""          // attachment link uri as String
    var attachmentName = ""                  // attachment link readable name
    var attachmentUriUri: Uri? = null        // attachment as original Uri from onActivityResult
    var inputAlertTextSelStart = -1          // insert position for attachment link
    var pickAttachment = false               // flag indicates, fabPlus was called from onActivityResult
    var imageCapture = false                 // flag indicates, an image was captured
    var mainDialog: AlertDialog? = null      // main edit dlg after click on button +
}

// compile regex patterns once in advance to detect: "blah[uriLink]blah", "YYYY-MM-DD", "YYYY-MM-DD Mon"
internal object PATTERN {
    private val URLS_REGEX = "((http:\\/\\/|https:\\/\\/)?(www.)?(([a-zA-Z0-9-]){2,2083}\\.){1,4}([a-zA-Z]){2,6}(\\/(([a-zA-Z-_\\/\\.0-9#:?=&;,]){0,2083})?){0,2083}?[^ \\n]*)"
    private val IP4PORT_REGEX = "\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?"
    val UriLink = Pattern.compile("\\[(.*?)\\]")                       // uri link is enclosed in []
    val Date = Pattern.compile("\\d{4}-\\d{2}-\\d{2}")
    val DateDay = Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*")            // header section with date
    val DateMonth = Pattern.compile("\\d{4}-\\d{2}-")                  // header section with year and month
    val DatePattern = Pattern.compile("[_-]\\d{8}[_-]")                // file date stamp pattern
    val UrlsPattern = Pattern.compile(URLS_REGEX, Pattern.CASE_INSENSITIVE)  // find urls in string
    val IP4PortPattern = Pattern.compile(IP4PORT_REGEX)                      // find urls in string
}

// showOrder
internal enum class SHOW_ORDER {
    TOP, BOTTOM
}

//
// data store in RAM: GrzLog.ser (new on top) <--> DataStore (new on top) <--> GrzListView.ListView (new OR old on top)
//
class DataStore : Serializable {
    // undo actions
    enum class ACTION {
        UNDEFINED, REVERTEDIT, REVERTDELETE, REVERTINSERT, REVERTADD
    }

    // timestamp
    internal object TIMESTAMP {
        const val OFF = 0
        const val HHMM = 1
        const val HHMMSS = 2
    }

    var namesSection: MutableList<String> = ArrayList()
    @JvmField
    var dataSection: MutableList<String> = ArrayList()
    var tagSection: MutableList<Int> = ArrayList()
    var timeSection: MutableList<Int> = ArrayList()
    var selectedSection = 0
    var undoSection = ""
    var undoText = ""
    var undoAction = ACTION.UNDEFINED
    fun clear() {
        namesSection.clear()
        dataSection.clear()
        timeSection.clear()
        selectedSection = 0
    }

    companion object {
        // number of folders allowed
        const val SECTIONS_COUNT = 30
    }
}