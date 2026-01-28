package com.grzwolf.grzlog

// destroys for whatever reason the AlertDialog.Builder layout
//import androidx.appcompat.app.AlertDialog

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.ClipboardManager
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.pdf.PdfDocument
import android.location.LocationManager
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.*
import android.print.PrintAttributes
import android.print.PrintAttributes.Resolution
import android.print.pdf.PrintedPdfDocument
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.grzwolf.grzlog.DataStore.ACTION
import com.grzwolf.grzlog.DataStore.TIMESTAMP
import com.grzwolf.grzlog.FileUtils.Companion.getFile
import com.grzwolf.grzlog.FileUtils.Companion.getPath
import com.grzwolf.grzlog.MainActivity.GrzEditText
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.regex.Pattern
import kotlin.properties.Delegates


// permission constants
const val PERMISSION_REQUEST_CAMERA       = 0
const val PERMISSION_REQUEST_MEDIA        = 1
const val PERMISSION_REQUEST_AUDIO        = 2
const val PERMISSION_REQUEST_EXIFDATA     = 3
const val PERMISSION_REQUEST_NOTIFICATION = 4
const val PERMISSION_REQUEST_LOCATION     = 5

const val MS_TO_DAYS = 1.0 / 1000.0 / 60.0 / 60.0 / 24.0

// common file extensions
val IMAGE_EXT = "jpg.png.bmp.jpeg.gif"
val AUDIO_EXT = "mp3.m4a.aac.amr.flac.ogg.wav"
val VIDEO_EXT = "mp4.3gp.webm.mkv"
val ERROR_EXT = "file-error"

// https://stackoverflow.com/questions/31364540/how-to-add-section-header-in-listview-list-item
class MainActivity : AppCompatActivity(),
    ActivityCompat.OnRequestPermissionsResultCallback,
    LifecycleObserver {

    // fabPlus dialog height is tricky, if running LineageOS
    val isRoBuildFlavorLineage = getRoBuildFlavor().contains("lineage", true)

    // app is running in emulator
    var runInEmulator = isEmulator()

    // in app reminder
    var notificationPermissionGranted = false

    var fabPlus = FabPlus()                               // floating action button is the main UI data input control
    var menuItemsVisible = false                          // control visibility of menu items
    var mainMenuHandler = Handler(Looper.getMainLooper()) // menu action handler
    var searchView: SearchView? = null                    // search function
    var searchViewQuery = ""                              // search query string
    var menuSearchVisible = false                         // visibility flag of search input
    var jumpToPhrase = ""                                 // + button long click offers a 'jump to phrase' option
    var capturedPhotoUri: Uri? = null                     // needs to be global, bc there is no way a camara app returns uri in onActivityResult

    // fabBack is used to switch back to the previous folder, FabBackTag provides data for such functionality
    class FabBackTag(folderName: String, searchHitListGlobal: MutableList<GlobalSearchHit>, listNdx: Int, searchPhrase: String) {
        var folderName: String = folderName
        var searchHitListGlobal: MutableList<GlobalSearchHit> = searchHitListGlobal
        var listNdx: Int = listNdx
        var searchPhrase: String = searchPhrase
    }

    // different attachments
    internal object PICK {
        const val DUMMY   = 0
        const val MEDIA   = 1
        const val CAPTURE = 2
        const val AUDIO   = 3
        const val PDF     = 4
        const val TXT     = 5
        const val ZIP     = 6
    }

    // time when onPause is called
    var onPauseStartTime: Long = -1

    // mimic clipboard inside app
    var shareBody: String? = ""

    // geo location
    var fusedLocationClient : FusedLocationProviderClient? = null
    var locationPermissionDenied : Int = 0

    // global 'search dialog' to allow to restart it, if search phrase has no hit
    var searchDialog: AlertDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        // dark mode OR light mode; call before super.onCreate
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        // set theme
        var theme = sharedPref.getString(getString(R.string.chosenTheme), "?")
        when (theme) {
            "Dark"      -> setTheme(R.style.ThemeOverlay_AppCompat_Dark)
            "Light"     -> setTheme(R.style.ThemeOverlay_AppCompat_Light)
            else        -> {
                             // set default theme after app installation or preference reset
                             setTheme(R.style.ThemeOverlay_AppCompat_Dark)
                             val spe = sharedPref.edit()
                             spe.putString(getString(R.string.chosenTheme), "Dark")
                             spe.apply()
                           }
        }
        MainActivity.showAppReminders = true
        // register lock screen notification API26+: https://developer.android.com/training/notify-user/build-notification
        createNotificationChannels()
        // lock screen notification: register a receiver for "screen on" transitions --> start point for an "immediate notifier"
        // https://stackoverflow.com/questions/4208458/android-notification-of-screen-off-on
        // this is not needed in AOSP; but it's needed for the Huawei launcher, which does not allow to render lockscreen notifications directly and in advance
        val intentFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON && grzlogReminderList.size > 0) {
                    centeredToast(context, grzlogReminderList[0], 0)
                    for (i in 0 until grzlogReminderList.size) {
                        generateLockscreenNotification(grzlogReminderList[i])
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

        // gboard keyboard usage warning: pen input doesn't work
        //     gboard silently activated floating pen keyboard as default in API 35
        var gboardStr = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)
        if (gboardStr.contains("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")) {
            dontShowAgain(this, getString(R.string.no_pen_support), "gboardNote")
        }

        // make context static
        contextMainActivity = this

        // check existence of app's password
        appPwdPub = sharedPref.getString("app_pwd", "")!!
        if (appPwdPub.isEmpty()) {
            // generate a unique pwd
            var keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
            var appPwdClear = keyManager.generateRandomPassword(16)
            // encrypt pwd with app's public key
            appPwdPub = keyManager.encryptPwdPub(appPwdClear)
            // save encrypted pwd in prefs
            val spe = sharedPref.edit()
            spe.putString("app_pwd", appPwdPub)
            spe.apply()
        }

        // if installed version is < v1.1.50, there is a high chance, that data are PUB/PRV encrypted
        //    this is needed, just in case a downgrade was done
        appVersionMain = extractNumbersUsingLoop(getString(R.string.app_version))[2]
        if (appVersionMain < 50) {
            sharedPref.edit().remove("checkPUBPRV").commit()
        }

        // standard
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null) // black screen at start or returning from settings
        setContentView(R.layout.activity_main)

//        // if logging “A resource failed to call close.” is needed: https://wh0.github.io/2020/08/12/closeguard.html
//        StrictMode.setVmPolicy(VmPolicy.Builder(StrictMode.getVmPolicy())
//            .detectLeakedClosableObjects()
//            .build())

        // life cycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // application name is needed in many places
        appName = this.applicationInfo.loadLabel(this.packageManager).toString()

        // at app start check app update availability just once a day
        if (sharedPref.getBoolean("AppAtStartCheckUpdateFlag", false)) {
            // check if today was already checked
            var dateCheckLastStr = sharedPref.getString("AppAtStartCheckUpdateDate", "1900-01-01")
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
            val dateCheckLast = LocalDate.parse(dateCheckLastStr, formatter)
            val dateToday = LocalDate.now()
            if (dateToday.compareTo(dateCheckLast) != 0) {
                // since check is allowed for today, save today as last check date
                val spe = sharedPref.edit()
                spe.putString("AppAtStartCheckUpdateDate", formatter.format(dateToday))
                spe.apply()
                // internet connection state
                if (isNetworkAvailable(getApplicationContext())){
                    // check for app update
                    checkForAppUpdate()
                } else {
                    grzlogUpdateAvailable = false
                    Toast.makeText(getApplicationContext(), "Internet error", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // toolbar tap listener
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // toolbar tap should close undo
                ds.undoSection = ""
                ds.undoText = ""
                ds.undoAction = ACTION.UNDEFINED
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
        fabBack!!.tag = FabBackTag("", ArrayList(), -1, "")

        // API 36: set the icon/text colors in the status bar and nav bar
        when (theme) {
            "Dark"     -> {
                              WindowInsetsControllerCompat(
                                  window,
                                  window.decorView).isAppearanceLightStatusBars = false
                              WindowInsetsControllerCompat(
                                  window,
                                  window.decorView).isAppearanceLightNavigationBars = false
                           }
            "Light"     -> {
                              WindowInsetsControllerCompat(
                                  window,
                                  window.decorView).isAppearanceLightStatusBars = true
                              WindowInsetsControllerCompat(
                                  window,
                                  window.decorView).isAppearanceLightNavigationBars = true
            }
        }
        // API 36: set inset for all what comes below status bar = padding to avoid overlap
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
               statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
               navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
               captionBarInset = insets.getInsets(WindowInsetsCompat.Type.captionBar())
               systemBarInset = insets.getInsets(WindowInsetsCompat.Type.systemBars())
               view.setPadding(0, statusBarInset.top, 0, 0)
               insets
            }
        }

        // prevents onPause / onResume to make a text bak: reset flag in onCreate
        returningFromRestore = false

        // read complete GrzLog.ser into DataStore
        ds = readAppData(appStoragePath)

        // only check for folder protection, if protected folder is not already open
        MainActivity.folderIsAuthenticated = false
        if (ds.timeSection[ds.selectedSection] == TIMESTAMP.AUTH) {
            // authenticate before opening a protected folder: MainActivity.folderIsAuthenticated will be set inside the prompt
            showBiometricPromptAndOpenFolder(-1, ds.selectedSection, -1)
        } else {
            // open unprotected folder
            MainActivity.folderIsAuthenticated = true
            openFolder(-1, ds.selectedSection, -1)
        }

        // onCreate shall clear any undo data + set two ds tags to 0 (first and last deleted item positions)
        ds.undoAction = ACTION.UNDEFINED
        ds.undoText = ""
        ds.undoSection = ""
        ds.tagSection.clear()

        // only enter, if no backup is already ongoing
        if (!backupOngoing) {
            // check for a broken backup: may happen, if a backup was previously aborted by OS or user
            val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var file = File(downloadDir, "$appName.zip" + "_part")
            if (file.exists()) {
                try {
                    file.delete()
                } catch (e: IOException) {
                    e.printStackTrace()
                } catch (e:NullPointerException){
                    e.printStackTrace()
                }
                // note to user
                okBox(this, "Note", getString(R.string.partialBackup))
            }
            // only exec backup in 'backup auto mode'
            if (!sharedPref.getBoolean("BackupModeManually", true)) {
                // if backup file datestamp is outdated, exec backup only once a day at app start
                var lastModDate = LocalDate.of(1900, 1, 1)
                var file = getBackupFile(this)
                if (file != null) {
                    lastModDate = Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                val dateToday = LocalDate.now()
                if (dateToday.compareTo(lastModDate) != 0) {
                    // backup DataStore to text file in Downloads as a measure of last resort after a data crash
                    if (!createTxtBackup(this@MainActivity, downloadDir, ds)) {
                        okBox(this, "Note", "GrzLog.txt backup failed")
                    }
                    // lame parameter transfer to BackupService
                    SettingsActivity.gBScontext = this
                    SettingsActivity.gBSsrcFolder = this.getExternalFilesDir(null)!!.absolutePath
                    SettingsActivity.gBSoutFolder = downloadDir
                    SettingsActivity.gBSzipName = "$appName.zip"
                    SettingsActivity.gBSmaxProgress = countFiles(File(SettingsActivity.gBSsrcFolder))
                    // start BackupService, which prevents interrupting the backup
                    SettingsActivity.actionOnBackupService(this, BackupService.Companion.Actions.START)
                }
            }
        }

        // listview item touch listener determines the screen coordinates of the touch event
        (lvMain.listView)?.setOnTouchListener(OnTouchListener { listView, event ->
            // reset the "listview did scroll" flag
            if (menuSearchVisible) {
                lvMain.scrollWhileSearch = false
            }
            // DOWN event
            if (event.action == MotionEvent.ACTION_DOWN) {
                // select an item, if DOWN event happens in a range from x = {0 ... 200} pixels
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
            val DEBOUNCER_MS: Long = 1000
            // if condition for a double click event is met, the single click handler won't reached
            if (System.currentTimeMillis() - lvMain.itemLastClickTime < DOUBLECLICK_MS) {
                lvMain.singleClickHandler.removeCallbacksAndMessages(null)
                lvMain.itemLastClickTime = System.currentTimeMillis()
                // what to do after double click, depends on lvMain.touchSelectItem
                if (lvMain.touchSelectItem) {
                    // double click with touchSelectItem shall act as 'show attachment'
                    lvMainOnItemClick(adapterView, itemView, itemPosition, itemId)
                } else {
                    // double click without touchSelectItem shall execute edit ListView item
                    onLongClickEditItem(adapterView, itemView, itemPosition, itemId, true, null)
                }
                return@OnItemClickListener
            }
            // debounce single click events
            if (System.currentTimeMillis() - lvMain.itemLastClickTime < DEBOUNCER_MS) {
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
                        lvMain.arrayList[itemPosition].setSelected(!lvMain.arrayList[itemPosition].isSelected())
                        lvMain.adapter!!.notifyDataSetChanged()
                    } else {
                        // does the click happen on a selected item
                        if (lvMain.arrayList[itemPosition].isSelected() && sharedPref.getBoolean("clickSelectedItemsToClipboard", false) ) {
                            // copy to clipboard
                            shareBody = lvMain.folderSelectedItems
                            clipboard = shareBody
                            centeredToast(this, getString(R.string.copyClipboard), 50)
                        } else {
                            // does the click happen on a search hit item
                            if (lvMain.arrayList[itemPosition].isSearchHit() && sharedPref.getBoolean("clickSearchHitToEdit", false) ) {
                                // execute the click as edit ListView item
                                onLongClickEditItem(adapterView, itemView, itemPosition, itemId, true, null)
                            } else {
                                // show item attachment OR www text
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
            if (lvMain.arrayList[itemPosition].isSearchHit()) {
                whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
            } else {
                // does the long click happen on a selected item
                if (lvMain.arrayList[itemPosition].isSelected()) {
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
            fabPlusOnClick()
        })
        (fabPlus.button)?.setOnLongClickListener(OnLongClickListener { view ->
            fabPlusOnLongClick(view, lvMain.listView)
            true
        })

        // switch back to previous folder: a) after following an attachment to a GrzLog folder b) after following a search hit into another folder
        (fabBack)?.setOnClickListener(View.OnClickListener { view ->
            // fabBack always cancels undo
            ds.undoSection = ""
            ds.undoText = ""
            ds.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            // get data from button tag
            val fbt: FabBackTag = fabBack!!.tag as FabBackTag
            // adjust title and message
            var title = getString(R.string.switchFolder)
            var message = getString(R.string.appFolder) + " \"" + fbt.folderName + "\""
            if (fbt.folderName.equals(ds.namesSection[ds.selectedSection])) {
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
                            switchToFolderByName(fbt.folderName, false)
                        }
                        // plus jump back to the search hit list dialog
                        if (fbt.searchHitListGlobal.size > 0) {
                            jumpToSearchHitInFolderDialog(this, fbt.searchHitListGlobal, fbt.listNdx, fbt.searchPhrase)
                        }
                        // keep list in back button tag
                        fabBack!!.tag = FabBackTag("", fbt.searchHitListGlobal, -1, fbt.searchPhrase)
                    }
                },
                {
                    if (fabBack != null) {
                        fabBack!!.visibility = INVISIBLE
                        // keep list in back button tag
                        fabBack!!.tag = FabBackTag("", fbt.searchHitListGlobal, -1, fbt.searchPhrase)
                    }
                }
            )
        })

        // silently scan app gallery data
        getAppGalleryThumbsSilent(this, true)

        // memorize first app usage to make a backup reminder from it
        var deferredBakDate = sharedPref.getLong("deferredBak", 0)
        // 0 indicates the very first app usage, which has oc no backup
        if (deferredBakDate == 0L) {
            val spe = sharedPref.edit()
            spe.putLong("deferredBak", Date().time)
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
                        spe.putLong("deferredBak", Date().time)
                        spe.apply()
                    },
                    null
                )
            }
        }
    }

    // activity_lifecycle.png: onPause() is called, whenever the app goes into background
    override fun onPause() {
        // secure protected folders
        if (ds.timeSection[ds.selectedSection] == TIMESTAMP.AUTH) {
            // time when onPause is called
            onPauseStartTime = System.currentTimeMillis()
            // hide folder content
            Handler().postDelayed({
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            }, 2000)
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            centeredToast(this, "Pause", 3000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        }
        // restore from backup MUST not generate the simple txt-backup (would override local txt backup)
        if (!returningFromRestore) {
            // simple text backup file in download folder, ONLY USAGE in readAppData(): if GrzLog.ser is corrupted OR not existing
            val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            createTxtBackup(this@MainActivity, downloadDir, ds)
        }
        super.onPause()
    }

    // activity_lifecycle.png: onResume is called, even when coming back from settings via "Android Back Button"
    override fun onResume() {
        // show folder content
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(true)
        }

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

        // control menu items visibility
        if (appMenu != null) {
            if (!menuSearchVisible) {
                // show Hamburger only
                showMenuItems(false)
            }
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
                    val file = File(returnAttachmentFromAppGallery)
                    val uriOri = Uri.fromFile(file)
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = returnAttachmentFromAppGallery
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentImageScale = false // nothing to scale
                    fabPlus.attachmentImageLink = false  // no image link to phone gallery
                    fabPlus.attachmentVideoLink = false  // no video link to phone gallery
                    val mime = getFileExtension(returnAttachmentFromAppGallery)
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
            }
            super.onResume()
            return
        }

        // refresh ListView, just redraw
        if (lvMain.adapter != null) {
            lvMain.adapter!!.notifyDataSetChanged()
        }

        // settings shared preferences
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        lvMain.showOrder = if (sharedPref.getBoolean("newAtBottom", false)) SHOW_ORDER.BOTTOM else SHOW_ORDER.TOP

        // app toolbar control
        if (appMenu != null) {
            // show the app's standard menu, if NOT returning from background with previously activated search
            if (!menuSearchVisible) {
                // standard app menu
                showMenuItems(false)
                // onResume always cancels undo data, but not if fabPlus.editInsertLine is active (if returning from a canceled pick)
                if (!fabPlus.editInsertLine) {
                    ds.undoSection = ""
                    ds.undoText = ""
                    ds.undoAction = ACTION.UNDEFINED
                }
                showMenuItemUndo()
            }
        }

        // if widget calls input, simulate remote click on fabPlus button
        if (sharedPref.getBoolean("widgetJumpInput", false)) {
            if (sharedPref.getBoolean("doubleClickWidget", false)) {
                // clear previously set shared pref in widget: otherwise ANY onResume would fire
                val spe = sharedPref.edit()
                spe.putBoolean("doubleClickWidget", false)
                spe.apply()
                // double click action just opens GrzLog
//                Toast.makeText(baseContext, "app widget double click action", Toast.LENGTH_LONG).show()
            }
            if (sharedPref.getBoolean("clickFabPlus", false)) {
                // clear previously set shared pref in widget: otherwise ANY onResume would fire
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

        // if app was previously closed, an intent comes here
        handleSharedIntent(getIntent())

        // show in app reminders
        if (MainActivity.showAppReminders) {
            showGrzLogReminder()
        }

        // treat protected folders timeout auth
        if (ds.timeSection[ds.selectedSection] == TIMESTAMP.AUTH) {
            // if time when onPause was called > 5 min
            if (System.currentTimeMillis() - onPauseStartTime > 300000 ) {
                // clear folder content
                lvMain.adapter = LvAdapter(this@MainActivity, lvMain.makeArrayList("", lvMain.showOrder))
                lvMain.listView!!.adapter = lvMain.adapter!!
                // exec folder auth
                showBiometricPromptAndOpenFolder(ds.selectedSection, ds.selectedSection, -1)
            }
        }
        onPauseStartTime = -1

    }

    // life cycle observer knows, if app is in foreground or not
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppInForeground () {
        appIsInForeground = true
    }
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppInBackground() {
        // activate in app reminder, if app goes into background
        if (MainActivity.temporaryBackground) {
            MainActivity.showAppReminders = false
            MainActivity.temporaryBackground = false
        } else {
            MainActivity.showAppReminders = true
        }
        appIsInForeground = false
    }

    // if app is in foreground or doesn't go thru onResume: handle event incoming content 'share with'
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    // build new list of GrzLog reminders
    fun buildGrzLogReminderList(list: MutableList<String>) {
        // clean up old data
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        var notificationCount = sharedPref.getInt("notificationCount", 0)
        var spe = sharedPref.edit()
        for (i in 0 until notificationCount) {
            var key = "notification" + i.toString()
            spe.remove(key).commit()
        }
        spe.remove("notificationCount").commit()
        grzlogReminderList.clear()
        // save changed preferences and build a new grzlogReminderList
        for (i in 0 until list.size) {
            var key = "notification" + i.toString()
            spe.putString(key, list[i])
            grzlogReminderList.add(list[i])
        }
        spe.putInt("notificationCount", list.size)
        spe.apply()
        // highlight reminder in UI listview
        lvMain.adapter!!.notifyDataSetChanged()
    }

    // handle in app reminders
    fun showGrzLogReminder() {
        // show reminder notification directly in app
        grzlogReminderList.clear()
        // count of notifications
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        var notificationCount = sharedPref.getInt("notificationCount", 0)
        for (i in 0 until notificationCount) {
            var key = "notification" + i.toString()
            var notification = sharedPref.getString(key, "")
            grzlogReminderList.add(notification!!)
        }
        if (grzlogReminderList.size > 0) {
            var msg = ""
            grzlogReminderList.forEach() {
                msg += it + "\n\n"
            }
            // AlertDialog.Builder
            val builder: AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            // title
            val head = SpannableString(getString(R.string.reminder) + "\n\n" + getString(R.string.keep_selection))
            var pos = head.indexOf("\n")
            head.setSpan(RelativeSizeSpan(1.5F), 0, pos,0)
            var titleView: TextView = TextView(this)
            titleView.text = head
            builder.setCustomTitle(titleView)
            // relying on listView selection status didn't work: better use this bool array
            var checkedItems = BooleanArray(grzlogReminderList.size)
            Arrays.fill(checkedItems, true)
            builder.setMultiChoiceItems(grzlogReminderList.toTypedArray(), checkedItems,
                OnMultiChoiceClickListener { dialog, position, isChecked ->
                })
            // exec the final work according to the multi choice selection status
            builder.setNegativeButton(getString(R.string.keep)) { dialog, which ->
                var listLeftOver: MutableList<String> = ArrayList()
                // rely on checkedItems bool array rather than on listView
                for (i in 0 until checkedItems.size) {
                    // get all left over reminders
                    if (checkedItems.get(i)) {
                        listLeftOver.add(grzlogReminderList[i])
                    }
                }
                // build new list of GrzLog reminders
                buildGrzLogReminderList(listLeftOver)
            }
            // toggle selection status
            builder.setNeutralButton(getString(R.string.toggle_selection)) { dialog, which ->
                val listView = (dialog as AlertDialog?)?.listView
                listView?.let {
                    // toggle selection status
                    for (i in 0 until listView.count) {
                        var status = !listView.isItemChecked(i)
                        listView.setItemChecked(i, status)
                        listView.setSelection(i)    // only visible after view is updated
                        checkedItems.set(i, status) // important: reliable even w/o view update
                    }
                    // restart toggle dialog after toggling is done to show new status
                    Handler().postDelayed({
                        (titleView.parent as? ViewGroup)?.removeView(titleView)
                        val dlg = builder.create()
                        dlg.setOnShowListener {
                            dlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                        }
                        dlg.show()
                        dlg.getWindow()?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                        dlg.setCanceledOnTouchOutside(false)
                    }, 300)
                }
            }
            val dlg = builder.create()
            dlg.setOnShowListener {
                dlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            dlg.show()
            dlg.getWindow()?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
            dlg.setCanceledOnTouchOutside(false)
        }
    }

    // handle event incoming content 'share with'
    fun handleSharedIntent(intent: Intent?) {
        // limited handling
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }

        // if current folder is not authenticated
        if (!MainActivity.folderIsAuthenticated) {
            return
        }

        // suppress in app reminder
        MainActivity.showAppReminders = false

        // attachment picker dialog might be open after PICK.CAPTURE
        attachmentPickerDialog?.let {
            if (it.isShowing) {
                attachmentPickerDialog?.let { it.cancel() }
            }
        }

        // text only
        if ("text/plain" == intent.type) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                lvMain.editLongPress = false
                lvMain.selectedText = ""
                lvMain.selectedRow = 0
                lvMain.selectedRowNoSpacers = 0
                fabPlus.inputAlertText = it
                fabPlus.editInsertLine = false
                fabPlusOnClick()
            }
        }
        // images and if so text
        if (intent.type?.startsWith("image/") == true) {
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
                try {
                    // WA can send images and text simultaneously
                    var imgText = ""
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        imgText = it
                    }
                    // image receive handling
                    var uriOri = it
                    var uri = uriOri
                    var imageUriString: String? = uriOri.toString()
                    if (imageUriString!!.contains("com.google.android.apps.photos.contentprovider", ignoreCase = true)) {
                        // GooglePhoto needs extra care
                        val imagepath = FileUtils.getPath(this, uri)
                        val imagefile = File(imagepath.toString())
                        uri = Uri.fromFile(imagefile)
                        imageUriString = uri.toString()
                    } else {
                        // image from documents & content
                        if (imageUriString.startsWith("content://")) {
                            imageUriString = getPath(this, uri)
                        }
                    }
                    if (uri != null) {
                        // same handling as click on fabPlusButton with attachment
                        fabPlus.pickAttachment = true
                        fabPlus.attachmentUri = imageUriString
                        fabPlus.attachmentUriUri = uriOri
                        fabPlus.attachmentImageScale = true // since it is a new image --> rescale it
                        fabPlus.attachmentImageLink = false // definitely no media link here
                        fabPlus.attachmentVideoLink = false //           -  "  -
                        fabPlus.attachmentName = getString(R.string.image)
                        fabPlus.inputAlertTextSelStart = 0  // insert position for attachment link
                        lvMain.editLongPress = false
                        lvMain.selectedText = ""
                        lvMain.selectedRow = 0
                        lvMain.selectedRowNoSpacers = 0
                        fabPlus.inputAlertText = " " + imgText
                        fabPlus.editInsertLine = false
                        fabPlusOnClick()
                    }
                } catch (e: Exception) {
                    centeredToast(this, e.message.toString(), 3000)
                }
            }
        }
        // clean up intent
        intent.replaceExtras(Bundle())
        intent.setAction("")
        intent.setData(null)
        intent.setFlags(0)
    }

    // listener for the status of requested permissions
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        // suppress in app reminder
        MainActivity.showAppReminders = false

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
        if (requestCode == PERMISSION_REQUEST_LOCATION) {
            if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission has been granted
                Toast.makeText(baseContext, "Location data access granted", Toast.LENGTH_LONG).show()
            } else {
                // permission request was denied
                Toast.makeText(baseContext, "Location data access denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    // get geo location coordinates
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    fun getLastKnownLocation(attachmentAllowed: Boolean = true,
                             linkText: String = "",
                             adapterView: AdapterView<*>?,
                             itemView: View?,
                             itemPosition: Int,
                             itemId: Long,
                             returnToSearchHits: Boolean = false,
                             function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)? = null) : Boolean {
        if (verifyLocationPermission()) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient!!.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    // work with valid data
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = java.lang.String.format(Locale.ENGLISH, "geo:%f,%f", location.latitude, location.longitude)
                    fabPlus.attachmentUriUri = null
                    fabPlus.attachmentImageScale = false
                    fabPlus.attachmentImageLink = false
                    fabPlus.attachmentVideoLink = false
                    fabPlus.attachmentName = "[gps]"
                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                } else {
                    // is location service activated at all
                    val manager = getSystemService(LOCATION_SERVICE) as LocationManager
                    if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        // ask for turning on location service
                        decisionBox(this@MainActivity,
                            DECISION.YESNO,
                            getString(R.string.turnGpsOn),
                            getString(R.string.continueQuestion),
                            {
                                // yes means: turn location service on an continue with "add attachment"
                                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                            },
                            {
                                // no means: just continue with "add attachment"
                                startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                            }
                        )
                    } else {
                        // if 'GPS on' but 'no fix yet', an explicit data request might help
                        var locationRequest = LocationRequest()
                        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                        locationRequest.interval = 0
                        locationRequest.fastestInterval = 0
                        locationRequest.numUpdates = 1
                        fusedLocationClient!!.requestLocationUpdates(locationRequest, appLocationCallback, Looper.myLooper())
                        // note: no fix yet, then go exactly back there, where we came from
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.gpsNoFix),
                            { startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function) }
                        )
                    }
                }
            }
            return true
        } else {
            // if permission was not active before 1st try, but granted now, then retry is ok
            Toast.makeText(baseContext, getString(R.string.tryAgain), Toast.LENGTH_LONG).show()
            return false
        }
    }
    // the pure existence of such a callback is needed
    private val appLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
        }
    }

    // ListView click handler implementation shows the item's linked content
    fun lvMainOnItemClick(adapterView: AdapterView<*>?, itemView: View?, itemPosition: Int, itemId: Long) {
        // no view is a no-go
        if (itemView == null) {
            return
        }
        // show www links could be disabled via preferences
        var showLinksWWW = true
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        if (sharedPref.getBoolean("openLinks", false) == false) {
            showLinksWWW = false
        }

        //
        // 0. find what was clicked on
        //
        // text view is either title or section, dismiss spacer
        var tv: TextView
        try {
            tv = itemView.findViewById(R.id.tvItemTitle)
        } catch (e: Exception) {
            try {
                tv = itemView.findViewById(R.id.tvSectionTitle)
            } catch (e: Exception) {
                return
            }
        }
        // text line count: text could be wrapped w/o a new line, still it has multiple lines
        val lineCount = tv.getLineCount()
        // full item coordinates containing the wrapped lines
        val vTop = itemView.top
        val vHeight = itemView.height
        val vHeightPerLine = vHeight / lineCount
        // user touch position
        val touchX = lvMain.touchEventPoint.x.toFloat() - tv.left // x corrected, bc. header is X centered
        val touchY = lvMain.touchEventPoint.y.toFloat()
        // loop wrapped lines
        var text = ""
        var wrappedLineIndex = -1
        val wrappedLines : MutableList<String> = ArrayList()
        for (i in 0 until lineCount) {
            // character indexes of a line in the text are used to extract sequentially one wrapped line
            val lineBeg = tv.getLayout().getLineStart(i)
            val lineEnd = tv.getLayout().getLineEnd(i)
            val line = tv.getText().toString().substring(lineBeg, lineEnd)
            // memorize wrapped lines: needed later, to complete a partial www-link or attachment
            wrappedLines.add(line)
            // identify the extracted line matching to the Y touch event
            val top = vTop + vHeightPerLine * i
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
            val ofsPxl = tv.paint.measureText(text, 0, i) + iconWidth
            if (ofsPxl >= touchX) {
                ofs = i
                break;
            }
        }
        // obtain the word around the character offset position from text
        val word = getWordAtOffset(text, ofs)
        // get the real attachment name with both enclosing brackets
        val attachment = getAttachmentFromText(lvMain.arrayList[itemPosition].title.toString())

        // so far, we only know what word/link was clicked on, it's time to show the content
        var title = ""
        var fileName = ""
        try {
            // get the clicked item's full text
            val fullItemText = lvMain.arrayList[itemPosition].fullTitle
            // get the attachment (image, video, audio, txt, pdf, www, folder) in fullItemText
            val matchFull = fullItemText?.let { PATTERN.UriLink.matcher(it) }
            var matchFullResult = ""
            if (matchFull?.find() == true) {
                matchFullResult = matchFull.group()
            }
            // check, what was clicked on by user, if it is an attachment
            val matchLink = attachment.let { PATTERN.UriLink.matcher(it) }
            var matchLinkFind = matchLink.find()

            //
            // 1. handle geolocation coordinates
            //
            if (matchFullResult.contains("::::geo:") ) {
                // two choices: navigate to, show + cancel
                twoChoicesDialog(this,
                    getString(R.string.geo_location),
                    getString(R.string.navi_or_show),
                    getString(R.string.navigate),
                    getString(R.string.show),
                    { // runner CANCEL
                    },
                    { // runner 'navigate to with Goggle Maps'
                        MainActivity.temporaryBackground = true
                        var uriString = "google.navigation:q=" +
                                matchFullResult.substring(
                                    matchFullResult.indexOf("::::geo:") + 8,
                                    matchFullResult.lastIndexOf("]")) +
                                "&mode=w"
                        var uriIntent = Uri.parse(uriString)
                        val intent = Intent(Intent.ACTION_VIEW, uriIntent)
                        intent.setPackage("com.google.android.apps.maps")
                        startActivity(intent)
                    },
                    { // runner 'show location with app of choice'
                        MainActivity.temporaryBackground = true
                        var uri = matchFullResult.substring(
                            matchFullResult.indexOf("::::geo:") + 4,
                            matchFullResult.lastIndexOf("]"))
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                        startActivity(intent)
                    }
                )
                return
            }

            //
            // 2. handle attachment in an item's text
            //
            if (matchFullResult.isNotEmpty() && matchLinkFind) {
                // double match --> show attachment
                val key = matchFullResult.substring(1, matchFullResult.length - 1)
                val lnkParts = key.split("::::".toRegex()).toTypedArray()
                if (lnkParts != null && lnkParts.size >= 2) {
                    val linkNoBrackets = attachment.substring(1, attachment.length-1)
                    if (linkNoBrackets.endsWith(lnkParts[0])) {
                        title = lnkParts[0]
                        fileName = lnkParts[1]
                        if (fileName.startsWith("folder/") == true) {
                            // fileName starting with folder/ is an attachment link to a GrzLog folder
                            val folderName = fileName.substring(fileName.indexOf("folder/") + "folder/".length)
                            decisionBox(
                                this,
                                DECISION.YESNO,
                                getString(R.string.switchFolder),
                                folderName,
                                {
                                    if (fabBack != null) {
                                        val dsFolder = ds.namesSection[ds.selectedSection]
                                        val fbtOld = fabBack!!.tag as FabBackTag
                                        val fbt = FabBackTag(dsFolder, fbtOld.searchHitListGlobal, -1, fbtOld.searchPhrase)
                                        fabBack!!.tag = fbt
                                        fabBack!!.visibility = VISIBLE
                                    }
                                    dontShowChangeFolder = true // suppress change folder dialog
                                    switchToFolderByName(folderName, true)
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
                            var type = GalleryInfo.MediaType.IS_UNKNOWN
                            if (lnkParts.size > 2) {
                                // specific case for linked images & videos
                                if (lnkParts[2].equals("image")) {
                                    type = GalleryInfo.MediaType.IS_IMAGE
                                }
                                if (lnkParts[2].equals("video")) {
                                    type = GalleryInfo.MediaType.IS_VIDEO
                                }
                            }
                            showAppLinkOrAttachment(this, title, fileName, type)
                        }
                    }
                }
            } else {
                //
                // 3. find a regular clicked url in an item's text and show it in the default browser
                //
                // url was shown indicator
                var urlWasShown = false
                // get a list of potential urls / www-links
                var urls: ArrayList<String>? = getAllLinksFromString(fullItemText.toString())
                if (showLinksWWW && urls != null && urls.size > 0) {
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
                                // fragmented link: expand downwards with lower wrappedLines, until a ' ' appears
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
                // 4. not clicked attachment/link (aka click into the void)
                //
                if (!urlWasShown) {
                    // specific case: item has attachment but was not clicked in attachment AND not in a text link --> show attachment
                    if (matchFullResult.isNotEmpty()) {
                        val key = matchFullResult.substring(1, matchFullResult.length - 1)
                        val lnkParts = key.split("::::".toRegex()).toTypedArray()
                        if (lnkParts != null && lnkParts.size >= 2) {
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
                                            val dsFolder = ds.namesSection[ds.selectedSection]
                                            val fbtOld = fabBack!!.tag as FabBackTag
                                            val fbt = FabBackTag(dsFolder, fbtOld.searchHitListGlobal, -1, fbtOld.searchPhrase)
                                            fabBack!!.tag = fbt
                                            fabBack!!.visibility = VISIBLE
                                        }
                                        dontShowChangeFolder = true // suppress change folder dialog
                                        switchToFolderByName(folderName, true)
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
                        if (showLinksWWW && urls != null && urls.size == 1) {
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

    // get attachment link including enclosing brackets
    fun getAttachmentFromText(fullText: String) : String {
        // all plausible
        val lhsBr = fullText.indexOf("[")
        var rhsBr = fullText.indexOf("]")
        if (lhsBr == -1 && rhsBr == -1 || lhsBr >= rhsBr) {
            return ""
        }
        // one position further
        rhsBr++
        // start
        var tmp = fullText.substring(lhsBr, rhsBr)
        // final check
        if (!PATTERN.UriLink.matcher(tmp).find()) {
            return ""
        }
        return tmp
    }

    // extract the word around the character offset position in a given text
    fun getWordAtOffset(text: String, offset: Int) : String {
        var word = ""
        try {
            // sake of mind
            var ofs = Math.max(0, Math.min(offset, text.length - 1))
            // 1st char
            word = text[ofs].toString()
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
        } catch (ioobe: IndexOutOfBoundsException) {
            word = ""
        }
        return word
    }

    // shorten a given string
    fun shortenedText(text: String, maxLength: Int) : String {
        if (text.length < maxLength) {
            return text
        }
        var boundary = maxLength / 2 - 5
        val mid = " ... "
        val lead = text.substring(0, boundary)
        val tail = text.substring(text.length - boundary, text.length - 1)
        return lead + mid + tail
    }
    fun shortenedTextPixels(text: String, maxPixels: Int) : String {
        // simple return
        var tv: TextView = TextView(this)
        tv.setText(text)
        tv.measure(0, 0)
        var width = tv.measuredWidth
        if (width < maxPixels) {
            return text
        }
        // the upcoming middle part of the shortened string
        val mid = " ... "
        tv = TextView(this)
        tv.setText(mid)
        tv.measure(0, 0)
        val midWidth = tv.measuredWidth
        // calc the boundary length of lead & tail
        var boundary = maxPixels / 2 - midWidth
        // build the lead part of the shortened string
        var len = 10
        var lead = text.substring(0, len)
        tv = TextView(this)
        tv.setText(lead)
        tv.measure(0, 0)
        while (tv.measuredWidth < boundary) {
            len++
            lead = text.substring(0, len)
            tv = TextView(this)
            tv.setText(lead)
            tv.measure(0, 0)
        }
        // build the tail part of the shortened string
        len = 10
        var tail = text.substring(text.length - len, text.length)
        tv = TextView(this)
        tv.setText(tail)
        tv.measure(0, 0)
        while (tv.measuredWidth < boundary) {
            len++
            tail = text.substring(text.length - len, text.length)
            tv = TextView(this)
            tv.setText(tail)
            tv.measure(0, 0)
        }
        // return the now shortened string
        return lead + mid + tail
    }

    // after list reload, calc jump to position: correction needed, if item is at bottom position
    fun calcJumpToPosition(itemPos: Int) : Int {
        var jumpToPos = itemPos
        if (itemPos != lvMain.listView!!.adapter.count - 1) {
            jumpToPos = lvMain.listView!!.firstVisiblePosition
        }
        return jumpToPos
    }

    // ListView long click handler: show options dialog, how to edit the current item/line
    fun whatToDoWithLongClickItem(adapterView: AdapterView<*>,
                                  itemView: View?,
                                  itemPosition: Int,
                                  itemId: Long,
                                  returnToSearchHits: Boolean = false) {
        // this is what the user can click in UI
        val item = adapterView.getItemAtPosition(itemPosition) as ListViewItem
        // do not do anything, if spacer
        if (item.isSpacer) {
            return
        }
        // theme
        val builderItemMore = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        // since we want to deal with an item, scroll there (normally we are there, but not after return from selection dialog)
        if (itemPosition < lvMain.listView!!.firstVisiblePosition || itemPosition > lvMain.listView!!.lastVisiblePosition) {
            var ofs = (lvMain.listView!!.lastVisiblePosition - lvMain.listView!!.firstVisiblePosition) / 2
            if (lvMain.listView!!.lastVisiblePosition == -1) {
                ofs = 10
            }
            var scrollPos = itemPosition - ofs
            lvMain.listView!!.setSelection(Math.max(0, scrollPos))
        }
        Handler().postDelayed({
            lvMain.arrayList[itemPosition].setHighLighted(true)
            lvMain.adapter!!.notifyDataSetChanged()
        }, 10)
        // set custom multiline title: https://stackoverflow.com/questions/9107054/how-to-build-alert-dialog-with-a-multi-line-title
        val headPart = getString(R.string.Options)
        val headLine = SpannableString(headPart + "\n\n" + shortenedText(item.title!!, 200))
        headLine.setSpan(RelativeSizeSpan(1.35F),0, headPart.length,0)
        headLine.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.white)), 0, headPart.length, 0)
        headLine.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.yellow)), headPart.length, headLine.length, 0)
        var titleView: TextView = TextView(this)
        titleView.text = headLine
        builderItemMore.setCustomTitle(titleView)
        // the following options are related to the ListView item
        val charSequences: MutableList<CharSequence> = ArrayList()
        charSequences.add(getString(R.string.EditLine))                     // ITEM == 0
        charSequences.add(getString(R.string.InsertLineBefore))             // ITEM == 1
        charSequences.add(getString(R.string.InsertLineAfter))             // ITEM == 2
        charSequences.add(getString(R.string.SelectItems))           // ITEM == 3
        charSequences.add(getString(R.string.LockscreenReminder))   // ITEM == 4
        if (lvMain.arrayList[itemPosition].isSection) {
            charSequences.add(getString(R.string.ToggleLineAsText))       // ITEM == 5
        } else {
            charSequences.add(getString(R.string.ToggleLineAsHeader))     // ITEM == 5
        }
        charSequences.add("")                              // ITEM == 6
        charSequences.add(getString(R.string.CopyLine))                     // ITEM == 7
        charSequences.add("")                              // ITEM == 8
        charSequences.add(getString(R.string.cutToClipboard))              // ITEM == 9
        charSequences.add(getString(R.string.RemoveLine))                   // ITEM == 10
        val itemsMore = charSequences.toTypedArray()
        builderItemMore.setItems(
            itemsMore,
            DialogInterface.OnClickListener { dialog, whichOri ->
                var which = whichOri

                // ITEM == 0 edit line
                if (which == 0) {
                    // execute the long click as edit ListView item
                    onLongClickEditItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 1  'line insert before current line'
                if (which == 1) {
                    // reject 'insert line before': at pos == 0 if SHOW_ORDER.BOTTOM
                    if (itemPosition == 0 && lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.InsertNotAllowed),
                            null
                        )
                        fabPlus.button?.background?.setAlpha(130)
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
                        if (lvMain.arrayList[i].isSpacer) {
                            spacers++
                        }
                    }
                    lvMain.selectedRowNoSpacers = itemPosition - spacers
                    // add line before the selected line in ListView array, which is showOrder aligned
                    lvMain.arrayList.add(itemPosition, EntryItem(" ", "", ""))
                    // build finalStr from ListView array
                    var finalStr = lvMain.selectedFolder
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        finalStr = lvMain.toggleTextShowOrder(null, finalStr).str
                    }
                    // save undo data (status before line insert)
                    ds.undoSection = ds.dataSection[ds.selectedSection]
                    ds.undoText = getString(R.string.InsertRow)
                    ds.undoAction = ACTION.REVERTINSERT
                    showMenuItemUndo()
                    // clean up
                    fabPlus.pickAttachment = false
                    fabPlus.imageCapture = false
                    fabPlus.attachmentUri = ""
                    fabPlus.inputAlertText = ""
                    fabPlus.attachmentName = ""
                    // update DataStore dataSection
                    ds.dataSection[ds.selectedSection] = finalStr
                    // flag to indicate, where the call came from
                    lvMain.editLongPress = true
                    // flag: 1) insert line 2) do not override undo data
                    fabPlus.editInsertLine = true
                    // direct jump to line edit input
                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 2  'line insert after current line'
                if (which == 2) {
                    // add line after the selected line in ListView array, which is showOrder aligned
                    lvMain.arrayList.add(itemPosition + 1, EntryItem(" ", "", ""))
                    // build finalStr from ListView array
                    var finalStr = lvMain.selectedFolder
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        finalStr = lvMain.toggleTextShowOrder(null, finalStr).str
                    }
                    // fabPlus needs to work with the inserted index
                    lvMain.selectedRow = itemPosition + 1
                    // memorize affected row in array list MINUS spacer count for DataStore
                    var spacers = 0
                    for (i in 0 until itemPosition) {
                        if (lvMain.arrayList[i].isSpacer) {
                            spacers++
                        }
                    }
                    lvMain.selectedRowNoSpacers = itemPosition - spacers + 1
                    // save undo data
                    ds.undoSection = ds.dataSection[ds.selectedSection]
                    ds.undoText = getString(R.string.InsertRow)
                    ds.undoAction = ACTION.REVERTINSERT
                    showMenuItemUndo()
                    // clean up
                    fabPlus.pickAttachment = false
                    fabPlus.imageCapture = false
                    fabPlus.attachmentUri = ""
                    fabPlus.inputAlertText = ""
                    fabPlus.attachmentName = ""
                    // update DataStore
                    ds.dataSection[ds.selectedSection] = finalStr
                    // logic control flags
                    lvMain.editLongPress = true
                    fabPlus.editInsertLine = true
                    // direct jump to line edit input
                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 3  'select items dialog' - afterward return to here
                if (which == 3) {
                    // function as parameter: https://stackoverflow.com/questions/62935022/pass-function-with-parameters-in-extension-functionkotlin
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, ::whatToDoWithLongClickItem)
                }

                // ITEM == 4 'add an in-app reminder'
                if (which == 4) {
                    if (!notificationPermissionGranted) {
                        whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                    }
                    // cancel undo
                    if (!fabPlus.editInsertLine) {
                        ds.undoSection = ""
                        ds.undoText = ""
                        ds.undoAction = ACTION.UNDEFINED
                    }
                    showMenuItemUndo()
                    // reminder candidate
                    val message = lvMain.arrayList[itemPosition].title
                    // reminder candidate may already exist, or it is a new one
                    if (MainActivity.grzlogReminderList.firstOrNull {it == message} != null) {
                        // reminder already exists: offer option to keep ist or to remove it
                        var bld: AlertDialog.Builder? = null
                        bld = AlertDialog.Builder(
                            this@MainActivity,
                            android.R.style.Theme_Material_Dialog
                        )
                        bld.setTitle(getString(R.string.notification_is_already_active))
                        bld.setMessage("\'" + message + "\'")
                        // dlg Remove reminder + quit
                        bld.setNeutralButton(
                            getString(R.string.remove),
                            DialogInterface.OnClickListener { dialog, which ->
                                // get all leftover reminders
                                var listLeftOver: MutableList<String> = ArrayList()
                                for (i in 0 until grzlogReminderList.size) {
                                    // discard currently selected item
                                    if (grzlogReminderList.get(i) != message) {
                                        listLeftOver.add(grzlogReminderList[i])
                                    }
                                }
                                // build new list of GrzLog reminders
                                buildGrzLogReminderList(listLeftOver)
                                // get out
                                whatToDoWithLongClickItem(
                                    adapterView,
                                    itemView,
                                    itemPosition,
                                    itemId,
                                    returnToSearchHits
                                )
                            })
                        // dlg KEEP + quit
                        bld.setNegativeButton(
                            getString(R.string.keepIt),
                            DialogInterface.OnClickListener { dialog, which ->
                                // get back to where called from
                                whatToDoWithLongClickItem(
                                    adapterView,
                                    itemView,
                                    itemPosition,
                                    itemId,
                                    returnToSearchHits
                                )
                            })
                        val dlg = bld.create()
                        dlg.setCanceledOnTouchOutside(false)
                        dlg.setOnShowListener {
                            dlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                        }
                        dlg.show()
                    } else {
                        // new reminder
                        var reminderDlg: AlertDialog? = null
                        var reminderBld: AlertDialog.Builder? = null
                        reminderBld = AlertDialog.Builder(
                            this@MainActivity,
                            android.R.style.Theme_Material_Dialog
                        )
                        reminderBld.setTitle(R.string.ShowReminder)
                        reminderBld.setMessage(message)
                        // reminder dlg 'middle button' --> OK + quit
                        reminderBld.setNegativeButton(
                            R.string.grzlog_reminder,
                            DialogInterface.OnClickListener { dialog, which ->
                                // add reminder to list
                                grzlogReminderList.add(message!!)
                                // get count of notifications so far and add the recent one to shared prefs and list
                                val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                                var notificationCount = sharedPref.getInt("notificationCount", 0)
                                var spe = sharedPref.edit()
                                var key = "notification" + notificationCount.toString()
                                spe.putString(key, message)
                                spe.putInt("notificationCount", ++notificationCount)
                                spe.apply()
                                // get back to where called from
                                whatToDoWithLongClickItem(
                                    adapterView,
                                    itemView,
                                    itemPosition,
                                    itemId,
                                    returnToSearchHits
                                )
                            }
                        )
                        // reminder dlg "top button" allows to add a calendar event
                        reminderBld.setPositiveButton(
                            R.string.calendar_event,
                            DialogInterface.OnClickListener { dialog, which ->
                                // show date picker dialog
                                val cal = Calendar.getInstance()
                                val yNow = cal.get(Calendar.YEAR)
                                val mNow = cal.get(Calendar.MONTH)
                                val dNow = cal.get(Calendar.DAY_OF_MONTH)
                                val dpd = DatePickerDialog(
                                    this,
                                    AlertDialog.THEME_DEVICE_DEFAULT_DARK,
                                    DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth ->

                                }, yNow, mNow, dNow)
                                dpd.show()
                                // date picker dialog OK button listener
                                val okButton = dpd.getButton(DialogInterface.BUTTON_POSITIVE)
                                okButton.setOnClickListener {
                                    // get selected date
                                    val year = dpd.datePicker.year
                                    val month = dpd.datePicker.month
                                    val day = dpd.datePicker.dayOfMonth
                                    // close date picker
                                    dpd.dismiss()
                                    // start time picker
                                    val cal = Calendar.getInstance()
                                    // time picker listener catches ok (aka time is set)
                                    val timeSetListener = TimePickerDialog.OnTimeSetListener { timePicker, hour, minute ->
                                        cal.set(Calendar.HOUR_OF_DAY, hour)
                                        cal.set(Calendar.MINUTE, minute)
                                        // start phone calendar app
                                        val startMillis: Long = Calendar.getInstance().run {
                                            set(year, month, day, hour, minute)
                                            timeInMillis
                                        }
                                        val endMillis: Long = Calendar.getInstance().run {
                                            set(year, month, day, hour, minute)
                                            timeInMillis
                                        }
                                        val intent = Intent(Intent.ACTION_INSERT)
                                            .setData(CalendarContract.Events.CONTENT_URI)
                                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                                            .putExtra(CalendarContract.Events.TITLE, message)
                                            .putExtra(CalendarContract.Events.DESCRIPTION, "")
                                            .putExtra(CalendarContract.Events.EVENT_LOCATION, "")
                                            .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
//                                            .putExtra(Intent.EXTRA_EMAIL, "")
                                        startActivity(intent)
                                        // back to the item reminder dlg
                                        Handler().postDelayed({
                                            reminderDlg!!.setOnShowListener {
                                                reminderDlg!!.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                                            }
                                            reminderDlg!!.show()
                                        }, 300)
                                    }
                                    // show time picker dialog
                                    TimePickerDialog(
                                        this,
                                        AlertDialog.THEME_DEVICE_DEFAULT_DARK,
                                        timeSetListener,
                                        cal.get(Calendar.HOUR_OF_DAY),
                                        0,
                                        true
                                    ).show()
                                }
                            }
                        )
                        // reminder dlg "bottom button" --> CANCEL
                        reminderBld.setNeutralButton(
                            R.string.cancel,
                            DialogInterface.OnClickListener { dialog, which ->
                                whatToDoWithLongClickItem(
                                    adapterView,
                                    itemView,
                                    itemPosition,
                                    itemId,
                                    returnToSearchHits
                                )
                            }
                        )
                        reminderDlg = reminderBld.create()
                        reminderDlg.setCanceledOnTouchOutside(false)
                        reminderDlg.setOnShowListener {
                            reminderDlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                        }
                        reminderDlg.show()
                        reminderDlg.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
                        reminderDlg.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false)
                    }
                }

                // ITEM == 5 'set header manually'
                if (which == 5) {
                    // if item is date header, dismiss
                    if (PATTERN.DateDay.matcher(lvMain.arrayList[itemPosition].title.toString()).find()) {
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
                            // store position to later jump to
                            val jumpToPos = calcJumpToPosition(itemPosition)
                            // save undo data
                            lvMain.selectedRow = itemPosition
                            ds.undoSection = ds.dataSection[ds.selectedSection]
                            ds.undoText = charSequences[6].toString()
                            ds.undoAction = ACTION.REVERTEDIT
                            showMenuItemUndo()
                            // modify title and fullTitle
                            var isNowSection: Boolean
                            var newTitle: String
                            var fullTitle: String
                            if (lvMain.arrayList[itemPosition].isSection) {
                                // reset header flag
                                newTitle = lvMain.arrayList[itemPosition].title!!.substring(1)
                                fullTitle = lvMain.arrayList[itemPosition].fullTitle!!.substring(1)
                                isNowSection = false
                            } else {
                                // set header flag: Tab = 9
                                newTitle = 9.toChar() + lvMain.arrayList[itemPosition].title!!
                                fullTitle = 9.toChar() + lvMain.arrayList[itemPosition].fullTitle!!
                                isNowSection = true
                            }
                            lvMain.arrayList[itemPosition] = SectionItem(newTitle, fullTitle, lvMain.arrayList[itemPosition].uriStr)
                            // build finalStr from modified ListView array via getter selectedFolder
                            var finalStr = lvMain.selectedFolder
                            if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                                finalStr = lvMain.toggleTextShowOrder(null, finalStr).str
                            }
                            // save finalStr to DataStore, to GrzLog.ser and re-read saved data
                            ds.dataSection[ds.selectedSection] = finalStr                            // update DataStore dataSection
                            writeAppData(appStoragePath, ds, appName)                                // write DataStore
                            ds.clear()                                                               // clear DataStore
                            ds = readAppData(appStoragePath)                                         // read DataStore
                            val dsText = ds.dataSection[ds.selectedSection]                          // raw data from DataStore
                            title = ds.namesSection[ds.selectedSection]                              // set app title to folder Name
                            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)        // convert & format raw text to array
                            lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList)          // build adapter and populate main listview
                            lvMain.listView!!.adapter = lvMain.adapter                               // populate main listview via adapter
                            lvMain.adapter!!.notifyDataSetChanged()
                            // jump to, temporary highlight affected item and revert it to normal 3s later
                            lvMain.listView!!.setSelection(jumpToPos)
                            var posHighLight: Int
                            if (isNowSection) {
                                posHighLight = Math.min(itemPosition + 1, lvMain.arrayList.size-1)
                            } else {
                                posHighLight = Math.max(itemPosition - 1, 0)
                            }
                            lvMain.arrayList[posHighLight].setHighLighted(true)
                            lvMain.listView!!.postDelayed({
                                lvMain.arrayList[posHighLight].setHighLighted(false)
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

                // ITEM == 6 'empty space'
                if (which == 6) {
                    whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                }

                // ITEM == 7 copy line
                if (which == 7) {
                    shareBody = lvMain.arrayList[itemPosition].fullTitle
                    clipboard = shareBody
                    whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                }

                // ITEM == 8 'empty space'
                if (which == 8) {
                    whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                }

                // ITEM == 9 'cut line to clipboard'
                if (which == 9) {
                    val message = lvMain.arrayList[itemPosition].title
                    var youSureBld: AlertDialog.Builder?
                    youSureBld = AlertDialog.Builder(
                                this@MainActivity,
                                android.R.style.Theme_Material_Dialog)
                    youSureBld.setTitle(R.string.cutToClipboard)
                    youSureBld.setMessage(message)
                    // 'you sure' dlg OK with quit
                    youSureBld.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialog, which ->
                            // copy to clipboard
                            shareBody = lvMain.arrayList[itemPosition].fullTitle
                            clipboard = shareBody
                            // clean up
                            fabPlus.inputAlertText = ""
                            // select item and let range delete method do its job
                            lvMain.arrayList[itemPosition].setSelected(true)
                            deleteMarkedItems(0)  // type: 0 == delete selected item, type: 1 == delete highlighted search item
                            // was hidden during input
                            fabPlus.button?.background?.setAlpha(130)
                            fabPlus.button?.show()
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

                // ITEM == 10 'current line delete'
                if (which == 10) {
                    val message = lvMain.arrayList[itemPosition].title
                    var youSureBld: AlertDialog.Builder?
                    youSureBld = AlertDialog.Builder(
                                this@MainActivity,
                                android.R.style.Theme_Material_Dialog)
                    youSureBld.setTitle(R.string.RemoveLine)
                    youSureBld.setMessage(message)
                    // 'you sure' dlg OK with quit
                    youSureBld.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialog, which ->
                            // clean up
                            fabPlus.inputAlertText = ""
                            // select item and let range delete method do its job
                            lvMain.arrayList[itemPosition].setSelected(true)
                            deleteMarkedItems(0)  // type: 0 == delete selected item, type: 1 == delete highlighted search item
                            // was hidden during input
                            fabPlus.button?.background?.setAlpha(130)
                            fabPlus.button?.show()
                        })
                    // 'you sure' dlg CANCEL
                    youSureBld.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialog, which ->
                            whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, returnToSearchHits)
                        })
                    val youSureDlg = youSureBld.create()
                    youSureDlg.setCanceledOnTouchOutside(false)
                    youSureDlg.setOnShowListener {
                        youSureDlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    youSureDlg.show()
                }
            })
        // BUTTON RETURN
        builderItemMore.setPositiveButton(
            R.string.close,
            DialogInterface.OnClickListener { dialog, which ->
                Handler().postDelayed({
                    lvMain.arrayList[itemPosition].setHighLighted(false)
                    lvMain.adapter!!.notifyDataSetChanged()
                }, 1000)
                dialog.dismiss()
                // return to search hits dialog
                if (returnToSearchHits) {
                    whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                }
            }
        )
        var itemfolderMoreDialog = builderItemMore.create()
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
                        var curText = lvMain.arrayList[itemPosition].title.toString()
                        if (lvMain.arrayList[itemPosition].isSection && PATTERN.DateDay.matcher(curText).find()) {
                            child.setEnabled(false)
                            child.setOnClickListener(null)
                        }
                    }
                }
                override fun onChildViewRemoved(view: View?, view1: View?) {}
            })
        itemfolderMoreDialog.show()
        itemfolderMoreDialog.setCanceledOnTouchOutside(false)
    }

    // execute the long click as edit ListView item
    fun onLongClickEditItem(adapterView: AdapterView<*>,
                            itemView: View?,
                            itemPosition: Int,
                            itemId: Long,
                            returnToSearchHits: Boolean,
                            function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)?) {
        // flag indicates the usage of fabPlus input as an editor for a 'long press line' input
        lvMain.editLongPress = true
        // delete previous undo data
        ds.undoSection = ""
        ds.undoText = ""
        ds.undoAction = ACTION.UNDEFINED
        showMenuItemUndo()
        // memorize current scroller position
        lvMain.fstVisPos = lvMain.listView!!.firstVisiblePosition
        lvMain.lstVisPos = lvMain.listView!!.lastVisiblePosition
        // the full text of the selected ListView item is data input
        var lineInput = lvMain.arrayList[itemPosition].fullTitle
        // search for attachment link in 'long press input': if found, prepare fabPlus input data accordingly
        val m = lineInput?.let { PATTERN.UriLink.matcher(it.toString()) }
        if (m?.find() == true) {
            val result = m.group()
            val key = result.substring(1, result.length - 1)
            val lnkParts = key.split("::::".toRegex()).toTypedArray()
            var keyString = ""
            var uriString = ""
            if (lnkParts != null && lnkParts.size >= 2) {
                keyString = lnkParts[0]
                uriString = lnkParts[1]
            }
            lineInput = lineInput.replace(result, "[$keyString]")
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
            if (lvMain.arrayList[i].isSpacer) {
                spacers++
            }
        }
        lvMain.selectedRowNoSpacers = itemPosition - spacers
        // continue with regular user data input --> jump to fabPlus click handler
        fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
    }

    // items from ListView are selected OR a so far unselected item was 'long clicked' --> what to do now
    // function as param https://stackoverflow.com/questions/62935022/pass-function-with-parameters-in-extension-functionkotlin
    private fun whatToDoWithItemsSelection(adapterView: AdapterView<*>,
                                           itemView: View?,
                                           itemPosition: Int,
                                           itemId: Long,
                                           returnToSearchHits: Boolean,
                                           function: ((AdapterView<*>, View?, Int, Long) -> Unit?)?) {
        // build a dialog
        var builder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        // get a list of selected indexes including the included spacers
        var listSelPos = lvMain.getSelectedIndexList()
        // range format the list of selected indexes including the included spacers
        var rangeStr = getIntegerRangeFormatted(listSelPos)
        if (listSelPos.size == 1) {
            rangeStr = "Item: " + shortenedText(lvMain.arrayList[listSelPos[0]].title!!, 200)
        }
        // since we want to deal with a selection, scroll there, try to keep selection centered
        var ofs = (lvMain.listView!!.lastVisiblePosition - lvMain.listView!!.firstVisiblePosition) / 2
        var minRange = listSelPos.minOrNull() ?: 0
        var maxRange = listSelPos.maxOrNull() ?: 0
        var range = (maxRange - minRange) / 2
        var scrollPos = listSelPos.minOrNull() ?: itemPosition
        lvMain.listView!!.setSelection(Math.max(0, scrollPos - ofs + if (range < ofs) range else ofs))
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
            getString(R.string.toggleItemSelection) + itemPosition,
            getString(R.string.selectNextTen),
            getString(R.string.selectDay),
            getString(R.string.selectWeek),
            getString(R.string.selectMonth),
            getString(R.string.unselectAll),
            getString(R.string.selectAll),
            getString(R.string.invertSelection),
            "",
            getString(R.string.copyToClipboard),
            getString(R.string.search_replace_selection),
            "",
            getString(R.string.cutToClipboard),
            getString(R.string.deleteFromList)
        )
        builder.setItems(options, DialogInterface.OnClickListener { dialog, item ->
            when (item) {
                0 -> { // Toggle the current item's selection status
                    lvMain.arrayList[itemPosition].setSelected(!lvMain.arrayList[itemPosition].isSelected())
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
                    for (i in lvMain.arrayList.indices) {
                        lvMain.arrayList[i].setSelected(false)
                    }
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                6 -> { // Select all
                    for (i in lvMain.arrayList.indices) {
                        lvMain.arrayList[i].setSelected(true)
                    }
                    lvMain.adapter!!.notifyDataSetChanged()
                    dialog.dismiss()
                    whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                }
                7 -> { // Invert selection
                    for (i in lvMain.arrayList.indices) {
                        lvMain.arrayList[i].setSelected(!lvMain.arrayList[i].isSelected())
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
                10 -> { // Search & Replace in an active selection
                    folderSearchReplace(true, false)
                }
                11 -> { // empty space as separator
                    dialog.dismiss()
                    Handler().postDelayed({
                        whatToDoWithItemsSelection(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                    }, 100)
                }
                12 -> { // Cut to clipboard / shareBody
                    var itemsSelected = false
                    for (i in lvMain.arrayList.indices) {
                        if (lvMain.arrayList[i].isSelected()) {
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
                13 -> { // Delete from ListView
                    var itemsSelected = false
                    for (i in lvMain.arrayList.indices) {
                        if (lvMain.arrayList[i].isSelected()) {
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
                fabPlus.button?.background?.setAlpha(130)
                fabPlus.button?.show()
            }
        })
        val dialog = builder.create()
        val listView = dialog.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        dialog.show()
        listView.setScrollbarFadingEnabled(false)
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

    // https://stackoverflow.com/questions/28071349/the-specified-child-already-has-a-parent-you-must-call-removeview-on-the-chil
    // usage: myView.removeSelf()
    fun View?.removeSelf() {
        this ?: return
        val parentView = parent as? ViewGroup ?: return
        parentView.removeView(this)
    }

    // Search & Replace inside the active folder
    //    Applies to:
    //      a) whole folder with no pre selection
    //      b) part of folder with previously found search hits
    //      c) part of folder with selected items
    //    Workflow:
    //      1. input search phrase dialog
    //      2. input replace phrase dialog
    //      3. multi choice selection dialog for actual replacements
    fun folderSearchReplace(selectedOnly: Boolean, searchHitOnly: Boolean) {
        // a text input
        val sText = EditText(contextMainActivity)
        sText.inputType = InputType.TYPE_CLASS_TEXT
        sText.setText("")
        // get rid of menu search infra structure
        showEditTextContextMenu(sText, false)
        // input dialog builder for search phrase
        var searchBuilder: AlertDialog.Builder = AlertDialog.Builder(contextMainActivity)
        searchBuilder = AlertDialog.Builder(contextMainActivity, android.R.style.Theme_Material_Dialog)
        searchBuilder.setTitle(getString(R.string.type_in_the_search_phrase))
        searchBuilder.setIcon(R.drawable.match_case_24px)
        searchBuilder.setView(sText)
        // OK input search phrase
        searchBuilder.setPositiveButton(R.string.ok) { dialog, which ->
            // now we know, what to search for
            val searchText = sText.text.toString()
            // reset search hits, if search&replace is NOT executed on folder search
            if (!searchHitOnly) {
                lvMain.unselectSearchHits()
            }
            lvMain.searchHitListFolder.clear()
            // loop current folder's array for replace candidates and a list of hits
            var hitList: MutableList<Hit> = ArrayList()
            var choiceItems: MutableList<Spanned> = ArrayList()
            for (pos in lvMain.arrayList.indices) {
                val text = lvMain.arrayList[pos].title!!
                // only selected items are taken into account
                if (selectedOnly) {
                    // simply skip not selected items
                    if (!lvMain.arrayList[pos].isSelected()) {
                        continue
                    }
                }
                // only search hits are taken into account
                if (searchHitOnly) {
                    // simply skip no search hit items
                    if (!lvMain.arrayList[pos].isSearchHit()) {
                        continue
                    }
                }
                // item needs to comply to searchText
                if (text.contains(searchText, false)) {
                    // holds the original text and its position in lvMain.arrayList
                    hitList.add(Hit(text, pos))
                    // customize color of search string
                    var spanStr = SpannableString(lvMain.arrayList[pos].title!!)
                    var startIndex = 0
                    var start = text.indexOf(searchText, startIndex, false)
                    while (start != -1) {
                        val end = start + searchText.length
                        spanStr.setSpan(
                            ForegroundColorSpan(
                                ContextCompat.getColor(
                                    this,
                                    R.color.yellow
                                )
                            ), start, end, 0
                        )
                        spanStr.setSpan(
                            BackgroundColorSpan(
                                ContextCompat.getColor(
                                    this,
                                    R.color.lightseagreen
                                )
                            ), start, end, 0
                        )
                        // continue
                        startIndex = start + searchText.length
                        start = text.indexOf(searchText, startIndex, false)
                    }
                    // add to array to be used in builder.setMultiChoiceItems
                    choiceItems.add(spanStr)
                }
            }
            // check nothing found
            if (choiceItems.size.equals(0)) {
                centeredToast(
                    this@MainActivity,
                    "'" + searchText + "' " + getString(R.string.nowhere_found),
                    Toast.LENGTH_LONG
                )
                // close currently open input search phrase dialog
                dialog.dismiss()
                // restart input search phrase dialog: needs searchDialog to be global
                Handler().postDelayed({
                    searchDialog!!.setOnShowListener {
                        searchDialog!!.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    searchDialog!!.show()
                    searchDialog!!.setCanceledOnTouchOutside(false)
                    sText.requestFocus()
                    showKeyboard(sText, 0, sText.text.length, 300)
                }, 300)
                return@setPositiveButton
            }
            // get to know the replace phrase
            val rText = EditText(contextMainActivity)
            rText.setText("")
            var replaceBuilder: AlertDialog.Builder = AlertDialog.Builder(contextMainActivity)
            replaceBuilder = AlertDialog.Builder(contextMainActivity, android.R.style.Theme_Material_Dialog)
            replaceBuilder.setTitle(getString(R.string.type_in_the_replace_phrase))
            replaceBuilder.setIcon(R.drawable.match_case_24px)
            replaceBuilder.setView(rText)
            // replace phrase CANCEL button
            replaceBuilder.setNegativeButton(R.string.cancel) { dialog, which ->
                // hide all other menu items
                showMenuItems(false)
            }
            // replace phrase OK button
            replaceBuilder.setPositiveButton(R.string.ok) { dialog, which ->
                // now we know the replace text
                val replaceText = rText.text.toString()
                // hide keyboard
                hideKeyboard(rText)
                // upcoming list of choiceItems to replace
                var replaceList: MutableList<Hit> = ArrayList()
                // a 'replace phrase in choiceItems' AlertDialog.Builder
                val builder: AlertDialog.Builder = AlertDialog.Builder(contextMainActivity, android.R.style.Theme_Material_Dialog)
                // visible screen dimensions
                val widthWnd: Int = contextMainActivity.resources.displayMetrics.widthPixels
                var heightWnd: Int = contextMainActivity.resources.displayMetrics.heightPixels
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    heightWnd = contextMainActivity.resources.displayMetrics.heightPixels -
                            MainActivity.statusBarInset.top -
                            MainActivity.navigationBarInset.bottom
                }
                // title text consists of 4 text lines
                var ss1 = SpannableString(getString(R.string.mark_items_to_replace) + "\n")
                ss1.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.white
                        )
                    ), 0, ss1.length, 0
                )
                ss1.setSpan(RelativeSizeSpan(1.3f), 0, ss1.length, 0)
                var shortenedText = shortenedTextPixels(searchText, widthWnd - 200)
                var ss2 = SpannableString("'" + shortenedText + "'\n")
                ss2.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.yellow
                        )
                    ), 1, ss2.length-1, 0
                )
                ss2.setSpan(
                    BackgroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.lightseagreen
                        )
                    ), 1, ss2.length-1, 0
                )
                var ss3 = SpannableString(getString(R.string.with) + "\n")
                ss3.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.white
                        )
                    ), 0, ss3.length, 0
                )
                shortenedText = shortenedTextPixels(replaceText, widthWnd - 200)
                var ss4 = SpannableString("'" + shortenedText + "'")
                ss4.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.yellow
                        )
                    ), 1, ss4.length-1, 0
                )
                ss4.setSpan(
                    BackgroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.lightseagreen
                        )
                    ), 1, ss4.length-1, 0
                )
                var titleText = SpannableStringBuilder()
                titleText.append(ss1)
                titleText.append(ss2)
                titleText.append(ss3)
                titleText.append(ss4)
                // custom title allows 4 lines of text
                val customTextView = TextView(builder.context)
                customTextView.textSize = 16.0f
                customTextView.setText(titleText)
                customTextView.inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                customTextView.setSingleLine(false)
                customTextView.gravity = Gravity.LEFT or Gravity.TOP
                customTextView.removeSelf()
                builder.setCustomTitle(customTextView)
                // final height of dlg in pixels
                var dlgFinalHeight = 0
                // final height of embedded listview in pixels
                var listViewFinalHeight = 0
                // final height of dlg's title in pixels
                var titleTextLinesHeight = 0
                // relying on Builder's internal listView selection status didn't work: use this bool array
                var checkedItems = BooleanArray(choiceItems.size)
                builder.setMultiChoiceItems(choiceItems.toTypedArray(), checkedItems,
                    // listener updates the 'Replace' button status grayed vs. active
                    OnMultiChoiceClickListener { dialog, position, isChecked ->
                        // prepare scrolling to recently visible list part
                        val listView = (dialog as AlertDialog?)?.listView
                        val lastVisiblePos = Math.max(0, listView!!.lastVisiblePosition - 1)
                        // get out of old dialog
                        dialog.dismiss()
                        // restart dialog with updated 'Replace' button status
                        Handler().postDelayed({
                            customTextView.removeSelf()
                            var dlg = builder.create()
                            dlg.setOnShowListener {
                                dlg.listView.smoothScrollToPosition(lastVisiblePos)
                                // final dialog layout dimensions
                                val listView = dlg?.listView
                                listView!!.bottom += 100
                                listView.setPadding(0, 100, 0, 100)
                                listView.layout(0, 0, listView.width, listViewFinalHeight)
                                dlg.getWindow()?.setLayout(widthWnd + 20, Math.min(heightWnd, dlgFinalHeight))
                            }
                            dlg.show()
                            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
                            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false)
                            var anySelection = false
                            for (i in 0 until checkedItems.size) {
                                if (checkedItems.get(i)) {
                                    anySelection = true
                                    break
                                }
                            }
                            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = anySelection
                            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                            dlg.setCanceledOnTouchOutside(false)
                        }, 10)
                    })
                // exec the final replacements work according to the multi choice selection status
                builder.setNegativeButton(R.string.exec_replace) { dialog, which ->
                    // clear tagSection --> a fresh start for highlighting edited ds items
                    ds.tagSection.clear()
                    // rely on checkedItems bool array rather than on listView
                    for (i in 0 until checkedItems.size) {
                        if (checkedItems.get(i)) {
                            // ds.tagSection index 0 is used as "scroll to position"
                            if (ds.tagSection.isEmpty()) {
                                ds.tagSection.add(hitList[i].pos)
                            }
                            // memorize listview item position for later highlighting
                            ds.tagSection.add(hitList[i].pos)
                            // memorize affected row in array list MINUS spacer count for later use in DataStore
                            var spacers = 0
                            for (i in 0 until hitList[i].pos) {
                                if (lvMain.arrayList[i].isSpacer) {
                                    spacers++
                                }
                            }
                            hitList[i].pos = hitList[i].pos - spacers
                            // get hitList item and add it to replaceList
                            var hit = hitList[i]
                            replaceList.add(hit)
                        }
                    }
                    // finally do the replacement job
                    replacePhraseInItems(replaceText, searchText, replaceList)
                }
                // cancel selection status + quit replacement dialog
                builder.setPositiveButton(R.string.cancel) { dialog, which ->
                    // hide all other menu items
                    showMenuItems(false)
                    // get out of old dialog
                    dialog.dismiss()
                }
                // toggle selection status of search & replace choiceItems
                builder.setNeutralButton(getString(R.string.toggle_selection)) { dialog, which ->
                    val listView = (dialog as AlertDialog?)?.listView
                    listView?.let {
                        // toggle selection status of search&replace choiceItems
                        for (i in 0 until listView.count) {
                            var status = !listView.isItemChecked(i)
                            listView.setItemChecked(i, status)
                            listView.setSelection(i)    // only visible after view is updated
                            checkedItems.set(i, status) // important: reliable even w/o view update
                        }
                        // prepare scrolling to recently visible list part
                        val lastVisiblePos = Math.max(0, listView.lastVisiblePosition - 1)
                        // get out of old dialog
                        dialog.dismiss()
                        // restart toggle dialog after toggling is done to show new status
                        Handler().postDelayed({
                            customTextView.removeSelf()
                            var dlg = builder.create()
                            dlg.setOnShowListener {
                                dlg.listView.smoothScrollToPosition(lastVisiblePos)
                                // final dialog layout dimensions
                                val listView = dlg?.listView
                                listView!!.bottom += 100
                                listView.setPadding(0, 100, 0, 100)
                                listView.layout(0, 0, listView.width, listViewFinalHeight)
                                dlg.getWindow()?.setLayout(widthWnd + 20, Math.min(heightWnd, dlgFinalHeight))
                            }
                            dlg.show()
                            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
                            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false)
                            var anySelection = false
                            for (i in 0 until checkedItems.size) {
                                if (checkedItems.get(i)) {
                                    anySelection = true
                                    break
                                }
                            }
                            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = anySelection
                            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                            dlg.setCanceledOnTouchOutside(false)
                        }, 10)
                    }
                }
                val dlg = builder.create()
                dlg.setOnShowListener {
                    // scroll listview
                    dlg.listView.smoothScrollToPosition(0)
                    // final dialog layout dimensions
                    val alertTitle = builder.context.getResources().getIdentifier("alertTitle", "id", "android")
                    dlg.findViewById<TextView>(alertTitle)?.let {
                        titleTextLinesHeight = it.lineHeight
                    }
                    val listView = dlg?.listView
                    listView!!.bottom += 100
                    listView.setPadding(0, 100, 0, 100)
                    listViewFinalHeight = listviewHeight(listView) + 200
                    listView.layout(0, 0, listView.width, listViewFinalHeight)
                    dlgFinalHeight = 100 + 3*titleTextLinesHeight + (1.3f*titleTextLinesHeight).toInt() + listViewFinalHeight
                    dlg.getWindow()?.setLayout(widthWnd + 20, Math.min(heightWnd, dlgFinalHeight))
                }
                dlg.show()
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false)
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false // 1st show = no selection
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                dlg.setCanceledOnTouchOutside(false)
            }
            // END OF input replace phrase dialog
            var replaceDialog = replaceBuilder.create()
            replaceDialog!!.setOnShowListener {
                replaceDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            replaceDialog.show()
            replaceDialog.setCanceledOnTouchOutside(false)
            rText.requestFocus()
            showKeyboard(rText, 0, 0, 250)
        }
        // CANCEL input search phrase
        searchBuilder.setNegativeButton(R.string.cancel) { dialog, which ->
            // hide all other menu items
            showMenuItems(false)
        }
        // build + show input search phrase dialog + open keyboard
        searchDialog = searchBuilder.create()
        searchDialog!!.setOnShowListener {
            searchDialog!!.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        searchDialog!!.show()
        searchDialog!!.setCanceledOnTouchOutside(false)
        sText.requestFocus()
        showKeyboard(sText, 0, 0, 250)
    }

    // items from ListView are search hits, what to do with them as next
    fun whatToDoWithSearchHits(adapterView: AdapterView<*>, itemView: View?, itemPosition: Int, itemId: Long) {
        // build a dialog
        var builder: AlertDialog.Builder? = null
        builder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        builder.setTitle(getString(R.string.whatToDoWithSearchHits))
        // specific search hit item text option
        val leadOpt = getString(R.string.editCurrentItem)
        val itemOpt = SpannableString(leadOpt + "\n" + shortenedText(lvMain.arrayList[itemPosition].title!!, 200))
        itemOpt.setSpan(RelativeSizeSpan(0.8F), leadOpt.length, itemOpt.length,0)
        itemOpt.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.white)), 0, leadOpt.length, 0)
        itemOpt.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.yellow)), leadOpt.length, itemOpt.length, 0)
        // dialog OPTIONS
        val options = arrayOf<CharSequence>(
            getString(R.string.unselectAll),
            getString(R.string.invertSelection),
            getString(R.string.copyToClipboard),
            getString(R.string.search_replace_selection),
            itemOpt,
            "",
            getString(R.string.deleteFromList),
            "",
            getString(R.string.quitSearch),
        )
        builder.setItems(options, object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface, item: Int) {
                when (item) {
                    0 -> { // unselect all
                        for (i in lvMain.arrayList.indices) {
                            lvMain.arrayList[i].setSearchHit(false)
                        }
                        lvMain.adapter!!.notifyDataSetChanged()
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    1 -> { // invert selection
                        run {
                            for (i in lvMain.arrayList.indices) {
                                lvMain.arrayList[i].setSearchHit(!lvMain.arrayList[i].isSearchHit())
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
                    3 -> { // search & replace
                        folderSearchReplace(false, true)
                    }
                    4 -> { // edit current item
                        whatToDoWithLongClickItem(adapterView, itemView, itemPosition, itemId, true)
                    }
                    5 -> { // separator
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    6 -> { // delete search hits from ListView
                        decisionBox(this@MainActivity,
                            DECISION.YESNO,
                            getString(R.string.deleteSelectedItems),
                            getString(R.string.continueQuestion),
                            { deleteMarkedItems(1) },
                            { whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId) }
                        )
                    }
                    7 -> { // separator
                        whatToDoWithSearchHits(adapterView, itemView, itemPosition, itemId)
                    }
                    8 -> { // fully quit current folder search w/o going thru normal return logic
                        quitCurrentFolderSearch()
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

    // quit current folder search:
    fun quitCurrentFolderSearch() {
        menuSearchVisible = false
        //
        searchView!!.setQuery(searchViewQuery, false)
        searchViewQuery = ""
        // control visibility of menu items
        showMenuItems(false)
        // remove all previous jump to remains
        if (jumpToPhrase.length > 0) {
            jumpToPhrase = ""
            lvMain.unselectSearchHits()
            lvMain.searchHitListFolder.clear()
        }
        // allow to unselect all search hits in arraylist
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
        title = ds.namesSection[ds.selectedSection]
        // show fabPlus
        fabPlus.button?.background?.setAlpha(130)
        fabPlus.button?.show()
        // hide keyboard
        hideKeyboard()
        // hide search view icon
        var searchviewMenuItem = appMenu!!.findItem(R.id.action_search_view)
        searchviewMenuItem!!.isVisible = false
    }

    // delete previously marked, either selected = (type 0) or search hits = (type 1) ListView items
    fun deleteMarkedItems(type: Int) {
        try {
            // save undo data
            ds.undoSection = ds.dataSection[ds.selectedSection]
            ds.undoText = getString(R.string.deleteSelection)
            ds.undoAction = ACTION.REVERTDELETE
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
            ds.tagSection.clear()
            // memorize current listview scroll position at index 0
            ds.tagSection.add(lvMain.listView!!.firstVisiblePosition)
            // count spacers above deleted Headers
            var cntSpc = 0
            // memorize largest deleted item index
            var largestItemNdx = 0
            // memorize last deleted item's type
            var lstDelItemIsHeader = false
            // delete marked items from ListView / arrayList in DataStore tagSection
            for (i in lvMain.arrayList.indices.reversed()) {
                // distinguish between 'selected' and 'search hit'
                var condition = false
                if (type == 0) {
                    condition = lvMain.arrayList[i].isSelected()
                }
                if (type == 1) {
                    condition = lvMain.arrayList[i].isSearchHit()
                }
                if (condition) {
                    // reset bc wait for last deletion
                    lstDelItemIsHeader = false
                    // count spacers above deleted Headers
                    if (lvMain.arrayList[i].isSection and (i > 0)) {
                        if (lvMain.arrayList[i-1].isSpacer) {
                            cntSpc++
                            lstDelItemIsHeader = true
                        }
                    }
                    // now really remove item
                    lvMain.arrayList.removeAt(i)
                    // memorize deleted item positions
                    ds.tagSection.add(i)
                    largestItemNdx = Math.max(i, largestItemNdx)
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
                finalStr = lvMain.toggleTextShowOrder(null, finalStr).str
            }
            // save finalStr to DataStore, to GrzLog.ser and re-read saved data
            ds.dataSection[ds.selectedSection] = finalStr                            // update DataStore dataSection
            writeAppData(appStoragePath, ds, appName)                                // write DataStore
            ds.clear()                                                               // clear DataStore
            ds = readAppData(appStoragePath)                                         // read DataStore
            val dsText = ds.dataSection[ds.selectedSection]                          // raw data from DataStore
            title = ds.namesSection[ds.selectedSection]                              // set app title to folder Name
            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)        // convert & format raw text to array
            lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList)   // build adapter and populate main listview
            lvMain.listView!!.adapter = lvMain.adapter                               // populate main listview via adapter

            var above = ds.tagSection[ds.tagSection.size - 1] - 1 - corrAbove        // 'neighbour' above highlight position: tagSection[last] == 1st del pos
            above = Math.max(0, Math.min(above, lvMain.arrayList.size-1))
            var below = ds.tagSection[1] - (ds.tagSection.size - 1) + 1 - cntSpc     // 'neighbour' below highlight position: tagSection[1] == last del pos
            below = Math.max(0, Math.min(below, lvMain.arrayList.size-1))
            if (lvMain.arrayList[above].isSpacer) above -= 1
            if (lvMain.arrayList[below].isSpacer) below += 1

            // scroll
            lvMain.listView!!.setSelection(largestItemNdx - 10)                      // ListView scroll to largest selected item

            // highlight and revoke 3s later
            lvMain.arrayList[above].setHighLighted(true)                             // temp. highlighting
            lvMain.arrayList[below].setHighLighted(true)
            lvMain.adapter!!.notifyDataSetChanged()
            lvMain.listView!!.postDelayed({                                          // revoke temp. highlighting after timeout
                lvMain.arrayList[above].setHighLighted(false)
                lvMain.arrayList[below].setHighLighted(false)
                lvMain.adapter!!.notifyDataSetChanged()
            }, 3000)
        } catch ( e:Exception) {}
    }

    // dynamic size of fabPlus input dialog is called by "(fabPlus.mainDialog)?.setOnShowListener"
    fun setFabPlusDialogSize() {
        if (fabPlus.mainDialog != null) {
            // dialog shall consume max. 60% of the screen height
            val heightMax = resources.displayMetrics.heightPixels * 0.60f
            // fabPlus.mainDialogInitHeight in pixels is the initial dlg height
            val height = Math.min((fabPlus.inputAlertView!!.getLineCount() - 1) * fabPlus.inputAlertView!!.textSize + fabPlus.mainDialogInitHeight, heightMax)
            // set dialog layout dimensions
            fabPlus.mainDialog!!.getWindow()?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height.toInt())
        }
    }

    // input button click --> simple input of data
    private fun fabPlusOnClick(
        adapterView: AdapterView<*>? = null,
        itemView: View? = null,
        itemPosition: Int = -1,
        itemId: Long = -1,
        returnToSearchHits: Boolean = false,
        function: ((AdapterView<*>, View?, Int, Long, Boolean) -> Unit?)? = null,
        restartInputText: String = "")
    {
        // avoid multiple fabPlus instances (example: have one open + home + tap widget)
        if (fabPlus.inputAlertView != null && fabPlus.inputAlertView!!.isShown) {
            return
        }

        // get out, if current folder is not authorized
        if (!MainActivity.folderIsAuthenticated) {
            centeredToast(this, getString(R.string.folderNotAuthorized), 3000)
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
            ds.undoSection = ""
            ds.undoText = ""
            ds.undoAction = ACTION.UNDEFINED
        }
        showMenuItemUndo()

        // fabPlusOnClick was restarted with an input text to be corrected by user
        if (restartInputText.isNotEmpty()) {
            fabPlus.inputAlertText = restartInputText
        }

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
                // check is only needed, if an attachment is active
                if (fabPlus.attachmentName.length > 0) {
                    // check for existence of an attachment, it might have gotten destroyed
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
                        // restart fabPlus.mainDialog with the activated ability to insert a new attachment link
                        fabPlus.mainDialog?.dismiss()
                        Handler(Looper.getMainLooper()).postDelayed({
                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function, tmp)
                        }, 100)
                        return
                    } else {
                        // memorize potential changed fabPlus.attachmentName
                        fabPlus.attachmentName = matcher.group()
                    }
                }
            }
        })

        // this happens, when returning from 'fabPlusInput more' dialog with BACK button: more dlg cannot alter input text
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

        // onActivityResult fires fabPlus.performClick(), which brings us here to handle the selected attachment
        if (fabPlus.attachmentUri!!.length != 0) {
            fabPlus.inputAlertTextSelStart = Math.min(
                fabPlus.inputAlertText!!.length,
                Math.max(0, fabPlus.inputAlertTextSelStart)
            )
            // insert fabPlus.attachmentName in current input & keep spaces
            if (fabPlus.inputAlertText!!.contains(fabPlus.attachmentName)) {
                fabPlus.inputAlertView!!.tag = 1
                fabPlus.inputAlertView!!.setText(fabPlus.inputAlertText)
            } else {
                var oldText1 = fabPlus.inputAlertText!!.substring(0, fabPlus.inputAlertTextSelStart)
                if (oldText1.isNotEmpty()) {
                    if (oldText1.last() != ' ') {
                        oldText1 += " "
                    }
                }
                var oldText2 = fabPlus.inputAlertText!!.substring(fabPlus.inputAlertTextSelStart)
                if (oldText2.isNotEmpty()) {
                    if (oldText2.first() != ' ') {
                        oldText2 = " " + oldText2
                    }
                }
                val newText = oldText1 + fabPlus.attachmentName + oldText2
                fabPlus.inputAlertView!!.setText(newText)
                // set selection of the attachment's literal name to be able to edit it immediately
                var selStart = newText.indexOf('[') + 1
                var selStop = newText.indexOf(']')
                fabPlus.inputAlertView!!.setSelection(selStart, selStop)
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

        // highlight edited item
        if (itemPosition != -1) {
            lvMain.arrayList[itemPosition].setHighLighted(true)
        }

        // show a customized standard dialog to obtain a text input: https://stackoverflow.com/questions/10903754/input-text-dialog-android
        var fabPlusBuilder: AlertDialog.Builder?
        fabPlusBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        // set theme
        var theme = sharedPref.getString(getString(R.string.chosenTheme), "Dark")
        if (theme != "Dark") {
            fabPlus.inputAlertView!!.setTextColor(Color.WHITE)
        }
        //
        // fabPlus button NEUTRAL: the only option while editing an item is to insert an attachment
        //
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
                    val matchLnk = linkText.let { PATTERN.UriLink.matcher(it) }
                    if (matchLnk.find() == true) {
                        linkText = matchLnk.group()
                        linkTextNoBrackets = linkText.substring(1, linkText.length-1)
                    } else {
                        linkText = ""
                    }
                    if (itemPosition != -1) {
                        // clicked item's original full text: extract the part after :::: within the brackets
                        var fullLink = lvMain.arrayList[itemPosition].fullTitle
                        // ... but there could be a recently added and not yet saved attachment
                        if (fabPlus.attachmentUri!!.length > 0) {
                            fullLink = "[" + linkTextNoBrackets + "::::" + fabPlus.attachmentUri!! + "]"
                        }
                        val matchOri = fullLink?.let { PATTERN.UriLink.matcher(it) }
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
                        if (linkText.isNotEmpty() && oriLink.isNotEmpty()) {
                            linkText = linkText.substring(0, linkText.length - 1) + "::::" + oriLink + "]"
                        }
                    } else {
                        // edit an attachment, if it is not yet saved in lvMain.arrayList
                        linkText = trimEndAll(fabPlus.inputAlertView!!.text.toString())
                        if (linkText.contains(']')) {
                            linkText = linkText.substring(0, linkText.indexOf(']') + 1)
                        }
                        var uriLink = fabPlus.attachmentUri ?: ""
                        if (uriLink.length > 0) {
                            linkText = linkText.substring(0, linkText.length - 1) + "::::" + uriLink + "]"
                        }
                    }
                }
                // fire attachment picker dlg, which finally ends at onActivityResult
                dlg.dismiss()
                startFilePickerDialog(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
            }
        )
        //
        // fabPlus button OK
        //
        fabPlusBuilder.setPositiveButton(R.string.ok, DialogInterface.OnClickListener { dialog, which ->

            // sanity check
            if (ds.selectedSection == -1) {
                return@OnClickListener
            }

            // timestamp requested ?
            var timestampType = ds.timeSection[ds.selectedSection]

            // get input data from AlertDialog
            var newText = trimEndAll(fabPlus.inputAlertView!!.text.toString())

            // just in case, newText came in from a paste operation
            newText = newText.replace("\r\n", "\n")

            // empty input is only allowed, if timestamp is not empty
            if (newText.isEmpty() && (timestampType == TIMESTAMP.OFF)) {
                centeredToast(this, getString(R.string.inputOneSpace), 3000)
                fabPlus.button?.background?.setAlpha(130)
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

            // perhaps a media link was changed
            var linkedMediaWasChanged = false
            // search for an attachment in the input text
            val m = PATTERN.UriLink.matcher(newText)
            if (m.find()) {
                val result = m.group()
                val key = result.substring(1, result.length - 1)
                // any provided attachment file name indicates there is work to do
                if (fabPlus.attachmentUri!!.length != 0) {
                    // 1st) check for attachment of type "link to phone gallery"
                    if (lvMain.editLongPress && itemPosition != -1) {
                        // long press edit item could alter an image or video link
                        val fullItemInfo = lvMain.arrayList[itemPosition].fullTitle
                        if (fullItemInfo!!.endsWith("::::video]")) {
                            fabPlus.attachmentVideoLink = true
                        }
                        if (fullItemInfo!!.endsWith("::::image]")) {
                            fabPlus.attachmentImageLink = true
                        }
                    }
                    if (fabPlus.attachmentImageLink || fabPlus.attachmentVideoLink) {
                        var appUriFileString = fabPlus.attachmentUriUri.toString()
                        if (lvMain.editLongPress && fabPlus.attachmentUriUri == null) {
                            // long press edit item may not provide a genuine uri
                            appUriFileString = fabPlus.attachmentUri!!
                        }
                        if (fabPlus.attachmentImageLink) {
                            appUriFileString += "::::image"
                        }
                        if (fabPlus.attachmentVideoLink) {
                            appUriFileString += "::::video"
                        }
                        newText = newText.replace(result, "[$key::::$appUriFileString]")
                        linkedMediaWasChanged = true // set flag, because link could be renewed - which doesn't change any text
                    } else {
                        // 2nd) distinguish between www links and real attachments
                        if ((fabPlus.attachmentUri!!.startsWith("/") == false)
                             && (fabPlus.attachmentUri!!.startsWith("file") == false)) {
                            // supposed to be the www link case
                            val lnk = fabPlus.attachmentUri!!
                            newText = newText.replace(result, "[$key::::$lnk]")
                        } else {
                            // 3rd) handle a full filename: copy an attachment into GrzLog gallery
                            var fn = fabPlus.attachmentUri!!
                            // execute the file attachment copy
                            val appUriFileString = copyAttachmentToApp(
                                this,
                                fabPlus.attachmentUri!!,
                                fabPlus.attachmentUriUri,
                                fabPlus.attachmentImageScale,
                                appStoragePath + "/Images"
                            )
                            if (fabPlus.attachmentUri!!.endsWith(appUriFileString)) {
                                // success
                                newText = newText.replace(result, "[$key::::$appUriFileString]")
                            } else {
                                // something did fail
                                newText = newText.replace(result, "[$key --> $appUriFileString::::$fn]")
                            }
                            // silently scan app gallery data to show the recently added file
                            getAppGalleryThumbsSilent(this, true)
                        }
                    }
                }
                // any inserted text could contain links after paste from clipboard BUT with no attachmentUri
                if (newText.contains("::::") && fabPlus.attachmentUri!!.length == 0) {
                    // it is fine to NOT copy the file in the link to app local
                    var innerKey = ""
                    var innerUri = ""
                    val lnkParts = key.split("::::".toRegex()).toTypedArray()
                    if (lnkParts != null && lnkParts.size >= 2) {
                        innerKey = lnkParts[0]
                        innerUri = lnkParts[1]
                        fabPlus.attachmentUri = innerUri
                    }
                }
            }

            // get all original data from DataStore
            val oriText = ds.dataSection[ds.selectedSection]
            var oriParts: Array<String?> = oriText.split("\\n+".toRegex()).toTypedArray()
            if (oriParts.size == 0) {
                oriParts = arrayOf("")
            }

            // final string collector w/o skipped dates
            var finalStr = ""

            // alternative final string collector contains skipped dates
            var finalStrWithSkippedDates: String = ""
            var newTextWithSkippedDates: String = ""
            var numAutoFilledDates = 0
            var fillWithSkippedDates = sharedPref.getBoolean("askAutoFillSkippedDates", true)

            // will a date header be added
            var addDateHeader = false

            // index offset to highlight the inserted items
            var insertStartPos = 0

            // marker to indicate a valid last date before the current input date
            var dateLastIsValid = true

            // need to distinguish between 'edit line' (aka long press) AND 'new input' (aka + button)
            val plusButtonInput: Boolean
            if (lvMain.editLongPress) {
                //
                // long press line edit
                //
                plusButtonInput = false
                // ... but, an image link could be renewed, after permission was released --> linkedImageWasChanged
                if (!linkedMediaWasChanged) {
                    // oriText text is showOrder dependent
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        val botText = lvMain.toggleTextShowOrder(null, oriText).str
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
                                function(
                                    adapterView!!,
                                    itemView,
                                    itemPosition,
                                    itemId,
                                    returnToSearchHits
                                )
                            }
                        }, 500)
                        return@OnClickListener
                    }
                }
                // make the actual change: it's fine, if newText contains multiple \n
                oriParts[lvMain.selectedRowNoSpacers] = newText
                // build a full final text
                val tmpStr = TextUtils.join("\n", oriParts)
                finalStr = if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    lvMain.toggleTextShowOrder(null, tmpStr).str
                } else {
                    tmpStr
                }
                // save undo data: if a line was inserted, we already have undo data and don't want to override them, just extend undoAction
                if (fabPlus.editInsertLine) {
                    ds.undoText += ":\n\n'$newText'"
                    ds.undoAction = ACTION.REVERTINSERT
                } else {
                    ds.undoSection = ds.dataSection[ds.selectedSection]
                    ds.undoText = getString(R.string.EditRow) + ":\n\n'$newText'"
                    ds.undoAction = ACTION.REVERTEDIT
                }
                showMenuItemUndo()

            } else {
                //
                // normal "+ button" input
                //
                plusButtonInput = true

                // sake of mind
                lvMain.selectedRow = if (lvMain.showOrder == SHOW_ORDER.TOP) 0 else lvMain.arrayList.size - 1

                // allow action undo
                ds.undoSection = ds.dataSection[ds.selectedSection]
                ds.undoText = getString(R.string.EditRow) + ":\n\n'$newText'"
                ds.undoAction = ACTION.REVERTADD
                showMenuItemUndo()

                // check, if user put text on top of a date --> not allowed
                val newTextParts = newText.split("\\n+".toRegex()).toTypedArray()
                var newTextHeaderPos = -1
                for (i in 0 until newTextParts.size) {
                    if (PATTERN.DateDay.matcher(newTextParts[i]).find()) {
                        newTextHeaderPos = i
                        break
                    }
                }
                if (newTextHeaderPos > 0) {
                    // leave error note
                    okBox(
                        this@MainActivity,
                        getString(R.string.error),
                        getString(R.string.input_sequence) + ":\n\n'" + newText + "'\n\n" + getString(R.string.not_allowed),
                        {
                            // clear undo
                            ds.undoSection = ""
                            ds.undoText = ""
                            ds.undoAction = ACTION.UNDEFINED
                            localCancel(dialog, this)
                            // restart fabPlus.mainDialog to let the user correct his previous input
                            fabPlus.mainDialog?.dismiss()
                            Handler(Looper.getMainLooper()).postDelayed({
                                fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function, newText)
                            }, 100)
                        }
                    )
                    return@OnClickListener
                }

                //
                // user input follows the show order:
                //
                // SHOW_ORDER.TOP    --> most recent item is directly underneath today's date header
                // SHOW_ORDER.BOTTOM --> most recent item is directly underneath yesterday's date header
                val newTextOri = newText
                if (newTextParts.size > 1) {
                    // input might contain multiple lines or \n (from shareBody): if so, don't add a timestamp
                    timestampType = TIMESTAMP.OFF
                    // if input contains multiple lines, take care about their showOrder
                    if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                        newText = lvMain.toggleTextShowOrder(null, newText).str
                    }
                }

                // newText could be EITHER 'normal text' OR "yyyy-MM-dd" / "yyyy-MM-dd EEE"
                // distinguishing the two cases allows to add a 'future date header'
                var newTextIsDatePattern: Boolean
                var newTextDate: LocalDate? = null
                try {
                    // allowed user input is "yyyy-MM-dd", anything else throws
                    newTextDate = LocalDate.parse(newTextParts[0], DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    newTextIsDatePattern = true
                } catch (_: DateTimeParseException) {
                    try {
                        // alternative allowed user input is "yyyy-MM-dd EEE", anything else throws and is considered as text input
                        newTextDate = LocalDate.parse(newTextParts[0], DateTimeFormatter.ofPattern("yyyy-MM-dd EEE"))
                        newTextIsDatePattern = true
                    } catch (_: DateTimeParseException) {
                        newTextIsDatePattern = false
                    }
                }
                if (newTextIsDatePattern) {
                    //
                    // add a future date header
                    //

                    // prepare for skipped date headers
                    var skippedDatesList = listOf<String>()

                    // get topmost header date
                    // topMostHeaderDate keeps null, if it is a non date header
                    var topMostHeaderDate: LocalDate? = null
                    val m0 = PATTERN.DateDay.matcher(oriParts[0])
                    if (m0.find()) {
                        try {
                            // build a date from oriParts
                            topMostHeaderDate = LocalDate.parse(
                                m0.group(),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")
                            )
                        } catch(e: DateTimeParseException) {
                            try {
                                // it can happen, that topmost header does not have a week day name
                                topMostHeaderDate = LocalDate.parse(
                                    m0.group(),
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                )
                            } catch(e: DateTimeParseException) {
                                topMostHeaderDate = null
                            }
                        }
                    }

                    // search newText as header in oriParts
                    val newTextHeaderString = newText + dayNameOfWeek(newText)
                    val foundString = oriParts.find { it?.equals(newTextHeaderString) ?: false }

                    // if newText was found --> error duplicate and get out
                    if (foundString?.isNotEmpty() ?: false) {
                        // leave error note
                        okBox(
                            this@MainActivity,
                            getString(R.string.error),
                            "'" + foundString + "' " + getString(R.string.duplicate_item),
                            {
                                // clear undo
                                ds.undoSection = ""
                                ds.undoText = ""
                                ds.undoAction = ACTION.UNDEFINED
                                localCancel(dialog, this)
                                // restart fabPlus.mainDialog to let the user correct his previous input
                                fabPlus.mainDialog?.dismiss()
                                Handler(Looper.getMainLooper()).postDelayed({
                                    fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function, newTextOri)
                                }, 100)
                            }
                        )
                        return@OnClickListener
                    }

                    // if topmost header is a non date type, we are done
                    if (topMostHeaderDate == null) {

                        // newText goes straight to top position
                        finalStr = newText + "\n" + oriText
                        // in such case, no skipped dates
                        fillWithSkippedDates = false

                    } else {

                        //
                        // check newTextDate to be younger or older as topMostHeaderDate
                        //
                        if (newTextDate?.isAfter(topMostHeaderDate) ?: false) { // isAfter == isYounger

                            // if newTextDate is younger as topMostHeaderDate,
                            //    newText goes to the top position in ds
                            finalStr = newText + "\n" + oriText

                            // build a skipped dates list
                            skippedDatesList = getDaysBetweenDates(
                                java.sql.Date.valueOf(topMostHeaderDate.plusDays(1).toString()),
                                java.sql.Date.valueOf(newTextDate.toString())
                            ).asReversed()

                            // obviously a date input more than a year into the future
                            if (skippedDatesList.size > 365) {
                                // leave error note
                                okBox(
                                    this@MainActivity,
                                    getString(R.string.error),
                                    "'" + newText + "' " + getString(R.string.wrong_date),
                                    {
                                        // clear undo
                                        ds.undoSection = ""
                                        ds.undoText = ""
                                        ds.undoAction = ACTION.UNDEFINED
                                        localCancel(dialog, this)
                                        // restart fabPlus.mainDialog to let the user correct his previous input
                                        fabPlus.mainDialog?.dismiss()
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function, newTextOri)
                                        }, 100)
                                    }
                                )
                                return@OnClickListener
                            }

                            // skipped dates
                            if (skippedDatesList.size == 0) {
                                // no skipped dates
                                fillWithSkippedDates = false
                            } else {
                                // take care about skipped dates
                                finalStrWithSkippedDates = newText + "\n"
                                newTextWithSkippedDates = newText + "\n"
                                for (dateStr in skippedDatesList) {
                                    finalStrWithSkippedDates += dateStr + "\n"
                                    newTextWithSkippedDates += dateStr + "\n"
                                }
                                finalStrWithSkippedDates += oriText
                            }

                        } else {

                            // if newTextDate is older than topMostHeaderDate,
                            //    iterate oriParts to find an item, which date is older as newTextDate
                            //    newText goes to this matching position
                            var itemHeaderDate: LocalDate? = null
                            var fstOlderHeaderDate: LocalDate? = null
                            var fstOlderHeaderPos = -1
                            var fstNewerHeaderDate: LocalDate? = null
                            // loop oriParts
                            for (ndx in 0 until oriParts.size) {
                                // any date header contains a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                                val m1 = PATTERN.DateDay.matcher(oriParts[ndx])
                                if (m1.find()) {
                                    // index offset for highlighting inserted items
                                    insertStartPos++
                                    try {
                                        // build a date from oriParts
                                        itemHeaderDate = LocalDate.parse(
                                            m1.group(),
                                            DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")
                                        )
                                    } catch (e: DateTimeParseException) {
                                        // "yyyy-MM-dd" pattern, if data were not yet saved and reloaded
                                        try {
                                            itemHeaderDate = LocalDate.parse(
                                                m1.group(),
                                                DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                            )
                                        } catch (e: DateTimeParseException) {
                                            // no issue to leave it unhandled
                                        }
                                    }
                                    // compare itemHeaderDate date with newTextDate:
                                    //     isBefore == isOlder
                                    if (itemHeaderDate != null && itemHeaderDate.isBefore(newTextDate) ?: false) {
                                        // this is the 1st header date, which is older as our newTextDate
                                        //    aka lower index of skipped dates
                                        fstOlderHeaderDate = itemHeaderDate
                                        fstOlderHeaderPos = ndx
                                        // next look up from here the position of a younger header date
                                        //    this info gives us the upper index skipped dates
                                        val indexUpStart = Math.max(ndx - 1, 0)
                                        for (idx in indexUpStart downTo 0) {
                                            // check for date = header
                                            val m2 = PATTERN.DateDay.matcher(oriParts[idx])
                                            if (m2.find()) {
                                                // index offset for highlighting inserted items
                                                insertStartPos--
                                                try {
                                                    // build a date from oriParts
                                                    fstNewerHeaderDate = LocalDate.parse(
                                                        m2.group(),
                                                        DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")
                                                    )
                                                } catch (e: DateTimeParseException) {
                                                    // "yyyy-MM-dd" pattern, if data were not yet saved and reloaded
                                                    try {
                                                        fstNewerHeaderDate = LocalDate.parse(
                                                            m2.group(),
                                                            DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                                        )
                                                    } catch (e: DateTimeParseException) {
                                                        // no issue to leave it unhandled
                                                    }
                                                }
                                                // compare oriParts date with newTextDate:
                                                //     isAfter == isYounger
                                                if (fstNewerHeaderDate != null && fstNewerHeaderDate.isAfter(newTextDate)?: false) {
                                                    // we found a header date, which is younger as our newTextDate
                                                    break
                                                }
                                            } else {
                                                // index offset for highlighting inserted items
                                                insertStartPos--
                                            }
                                        }
                                        break
                                    }
                                } else {
                                    // index offset for highlighting inserted items
                                    insertStartPos++
                                }
                            }

                            // obviously a completely wrong date input: date too old
                            if (fstOlderHeaderDate == null) {
                                // leave error note
                                okBox(
                                    this@MainActivity,
                                    getString(R.string.error),
                                    "'" + newText + "' " + getString(R.string.wrong_date),
                                    {
                                        // clear undo
                                        ds.undoSection = ""
                                        ds.undoText = ""
                                        ds.undoAction = ACTION.UNDEFINED
                                        localCancel(dialog, this)
                                        // restart fabPlus.mainDialog to let the user correct his previous input
                                        fabPlus.mainDialog?.dismiss()
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function, newTextOri)
                                        }, 100)
                                    }
                                )
                                return@OnClickListener
                            }

                            // build a skipped dates list
                            skippedDatesList = getDaysBetweenDates(
                                java.sql.Date.valueOf(fstOlderHeaderDate.plusDays(1).toString()),
                                java.sql.Date.valueOf(newTextDate.toString())
                            ).asReversed()

                            // build finalStr and alternatives
                            finalStr = ""
                            for (i in 0 until oriParts.size) {

                                if (i == fstOlderHeaderPos) {
                                    //
                                    // here is an insert position match
                                    //
                                    // a) add newText to finalStr
                                    if (finalStr.isEmpty()) {
                                        finalStr += newText + "\n"
                                    } else {
                                        finalStr += "\n" + newText
                                    }
                                    // b) insert skipped dates
                                    if (fillWithSkippedDates) {
                                        // 1st add newText to newTextWithSkippedDates
                                        if (newTextWithSkippedDates.isEmpty()) {
                                            newTextWithSkippedDates += newText + "\n"
                                        } else {
                                            newTextWithSkippedDates += "\n" + newText
                                        }
                                        // 2nd add newText to finalStrWithSkippedDates
                                        if (finalStrWithSkippedDates.isEmpty()) {
                                            finalStrWithSkippedDates += newText + "\n"
                                        } else {
                                            finalStrWithSkippedDates += "\n" + newText
                                        }
                                        // 3rd add skipped dates to *WithSkippedDates
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
                                    // c) finally add oriParts[i] to finalStr after newText
                                    if (finalStr.isEmpty()) {
                                        finalStr += oriParts[i]
                                    } else {
                                        finalStr += "\n" + oriParts[i]
                                    }
                                    if (fillWithSkippedDates) {
                                        if (finalStrWithSkippedDates.isEmpty()) {
                                            finalStrWithSkippedDates += oriParts[i]
                                        } else {
                                            finalStrWithSkippedDates += "\n" + oriParts[i]
                                        }
                                    }
                                } else {
                                    //
                                    // normal loop
                                    //
                                    // finalStr
                                    if (finalStr.isEmpty()) {
                                        finalStr += oriParts[i]
                                    } else {
                                        finalStr += "\n" + oriParts[i]
                                    }
                                    // alternative final string with skipped dates
                                    if (fillWithSkippedDates) {
                                        if (finalStrWithSkippedDates.isEmpty()) {
                                            finalStrWithSkippedDates += oriParts[i]
                                        } else {
                                            finalStrWithSkippedDates += "\n" + oriParts[i]
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    //
                    // standard text input
                    //

                    //
                    // loop oriParts date headers to be able to insert newText for a today's date
                    // --> oriParts might contain date headers younger as today
                    //
                    var spacers = 0           // spacers needed to translate offset in ds into lvMain
                    var newTextInsertPos = -1 // offset in ds
                    val todayDate = LocalDate.now()
                    var itemHeaderDate: LocalDate? = null
                    for (ndx in 0 until oriParts.size) {
                        // any date header contains a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                        val m1 = PATTERN.DateDay.matcher(oriParts[ndx])
                        if (m1.find()) {
                            try {
                                // build a date from oriParts
                                itemHeaderDate = LocalDate.parse(
                                    m1.group(),
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")
                                )
                            } catch (e: Exception) {
                                // build a date from oriParts
                                itemHeaderDate = LocalDate.parse(
                                    m1.group(),
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                )
                            }
                            if (itemHeaderDate != null) {
                                // compare to find a date match
                                if (todayDate.year == itemHeaderDate.year && todayDate.dayOfYear == itemHeaderDate.dayOfYear) {
                                    newTextInsertPos = ndx         // ds offset to insert newText
                                    insertStartPos = ndx + spacers // lvMain pos for highlighting
                                    break
                                }
                                // for highlighting
                                spacers++
                            }
                        }
                    }
                    if (newTextInsertPos > 0) {
                        //
                        // newText goes right behind insertPos
                        //

                        for (ndx in 0 until oriParts.size) {
                            finalStr += oriParts[ndx] + "\n"
                            if (ndx == newTextInsertPos) {
                                finalStr += newText + "\n"
                            }
                        }
                        // no skipped dates
                        fillWithSkippedDates = false

                    } else {
                        //
                        // newText goes to top
                        //

                        // get today date stamp w/o time
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
                        val dateToday: Date? = try {
                            dateFormat.parse(dateFormat.format(Date()))
                        } catch (ex: Exception) {
                            Date()
                        }

                        // prepare for a date max. 1 day before
                        val cal = Calendar.getInstance()
                        val todayStr = dateFormat.format(dateToday)
                        cal.time = dateFormat.parse(todayStr)
                        cal.add(Calendar.DATE, -1)
                        val yesterdayStr = dateFormat.format(cal.time)
                        val yesterdayDate = dateFormat.parse(yesterdayStr)
                        // last entry's date
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
                            dateStr = SimpleDateFormat(
                                "yyyy-MM-dd EEE",
                                Locale.getDefault()
                            ).format(dateToday!!) + "\n"
                            combineNdx = 0
                            addDateHeader = true
                        }
                        // add time at the beginning of the newText
                        var timeStr = ""
                        if (timestampType != TIMESTAMP.OFF) {
                            if (timestampType == TIMESTAMP.HHMM) {
                                val timeFormat = "HH:mm"
                                timeStr = SimpleDateFormat(timeFormat).format(Date()) + " "
                            }
                            if (timestampType == TIMESTAMP.HHMMSS) {
                                val timeFormat = "HH:mm:ss"
                                timeStr = SimpleDateFormat(timeFormat).format(Date()) + " "
                            }
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
                        if (fillWithSkippedDates && dateLastIsValid) {
                            newTextWithSkippedDates = finalStr
                            // start date is one day after the last entry's date
                            val c = Calendar.getInstance()
                            c.time = dateFormat.parse(dateStringLast)
                            c.add(Calendar.DATE, 1)
                            val fromStr = dateFormat.format(c.time)
                            val fromDate = dateFormat.parse(fromStr)
                            // list contains all date strings between today and the last recorded entry
                            // !! reversed():
                            //    build & run < API 35 ok
                            //    build & run = API 35 ok
                            //    build API 35 + run < API 35 --> exception NoSuchMethodError
                            val skippedDatesList = getDaysBetweenDates(fromDate, dateToday!!).asReversed()
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
                }
            }

            // sake of mind
            if (!finalStr.endsWith("\n")) {
                finalStr += "\n"
                finalStrWithSkippedDates += "\n"
            }

            // finally ask for a decision regarding skipped dates
            if ( plusButtonInput && fillWithSkippedDates && dateLastIsValid ) {
                // insert skipped day at all ?
                decisionBox(
                    this@MainActivity,
                    DECISION.YESNO,
                    getString(R.string.autoFillSkippedDates),
                    getString(R.string.continueQuestion),
                    // "insert skipped day at all" YES = insert skipped days
                    {
                        // perhaps count of skipped days is very large, say > 1 year
                        if (numAutoFilledDates > 365) {
                            twoChoicesDialog(
                                this,
                                getString(R.string.many_skipped_dates),
                                getString(R.string.continue_with) + numAutoFilledDates.toString() + getString(R.string.days),
                                getString(R.string.continue_inserting_skipped_days),
                                getString(R.string.no_inserting_skipped_days),
                                // "skipped days is very large" CANCEL = quit all
                                {
                                    // clear undo
                                    ds.undoSection = ""
                                    ds.undoText = ""
                                    ds.undoAction = ACTION.UNDEFINED
                                    showMenuItemUndo()
                                    // quit all input
                                    localCancel(dialog, this)
                                },
                                // "skipped days is very large" YES = keep huge number of inserted days
                                {
                                    fabPlusOkFinale(
                                        finalStrWithSkippedDates,
                                        newTextWithSkippedDates,
                                        addDateHeader,
                                        plusButtonInput,
                                        numAutoFilledDates,
                                        insertStartPos
                                    )
                                },
                                // "skipped days is very large" NO = do not insert skipped days
                                {
                                    fabPlusOkFinale(
                                        finalStr,
                                        newText,
                                        addDateHeader,
                                        plusButtonInput,
                                        0,
                                        insertStartPos
                                    )
                                }
                            )
                        } else {
                            // skipped days count is within the range of one year
                            fabPlusOkFinale(
                                finalStrWithSkippedDates,
                                newTextWithSkippedDates,
                                addDateHeader,
                                plusButtonInput,
                                numAutoFilledDates,
                                insertStartPos
                            )
                        }
                    },
                    // "insert skipped day at all" NO = abandon inserting skipped days
                    { fabPlusOkFinale(
                        finalStr,
                        newText,
                        addDateHeader,
                        plusButtonInput,
                        0,
                        insertStartPos)
                    }
                )
            } else {
                // no skipped days existing
                fabPlusOkFinale(
                    finalStr,
                    newText,
                    addDateHeader,
                    plusButtonInput,
                    0,
                    insertStartPos)
            }

        })

        // fabPlus button CANCEL
        fabPlusBuilder.setNegativeButton(
            R.string.cancel,
            DialogInterface.OnClickListener { dialog, which ->
                // un highlight edited item
                if (itemPosition != -1) {
                    if (function == null) {
                        // only un highlight item, if call did not come from another calling dlg
                        lvMain.listView!!.postDelayed({
                            lvMain.arrayList[itemPosition].setHighLighted(false)
                            lvMain.adapter!!.notifyDataSetChanged()
                        }, 1000)
                    }
                }
                // standard cancel handling
                localCancel(dialog, this)
                // return to calling function
                if ((function != null) && (itemPosition != -1)) {
                    function(adapterView!!, itemView, itemPosition, itemId, returnToSearchHits)
                }
            }
        )

        // title
        val customTitleView = TextView(fabPlusBuilder.context)
        customTitleView.text = SpannableString(getString(R.string.writeGrzLog))
        customTitleView.setTextColor(Color.WHITE)
        customTitleView.setPadding(50, 50, 50, 50)
        fabPlusBuilder.setCustomTitle(customTitleView)
        // input editor
        fabPlus.inputAlertView!!.setBackgroundColor(Color.rgb(80, 80, 80))
        fabPlusBuilder.setView(fabPlus.inputAlertView)
        // >= Android 12: default filter limit is set to 10k chars --> remove filters
        fabPlus.inputAlertView!!.filters = arrayOf()
        // build AlertBuilder dialog
        fabPlus.mainDialog = fabPlusBuilder.create()
        // the listener matches the initial dialog width to the screen width & the box height to the text length
        (fabPlus.mainDialog)?.setOnShowListener {
            setFabPlusDialogSize()
        }
        // measure the initial dialog height with a one line text
        (fabPlus.mainDialog)?.window?.decorView?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // make it a one time measurement: fabPlus.mainDialogInitHeight init is 100000
            if (v.height < fabPlus.mainDialogInitHeight ) {
                // 3x emu, Honor have expected behavior: v.height
                fabPlus.mainDialogInitHeight = v.height
                // real Pixel 9a on LineageOS needs: v.height - 200
                if (isRoBuildFlavorLineage) {
                    fabPlus.mainDialogInitHeight = v.height - 200
                }
            }
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
        fabPlus.button?.hide()
        // tricky way to let the keyboard popup
        fabPlus.inputAlertView!!.requestFocus()
        showKeyboard(fabPlus.inputAlertView, fabPlus.inputAlertView!!.selectionStart, fabPlus.inputAlertView!!.selectionEnd, 250)
    }

    // final handling of fabPlusOk as a separate fun to allow option to autofill skipped dates
    fun fabPlusOkFinale(
        finalStr: String,
        newText: String,
        addDateHeader: Boolean,
        plusButtonInput: Boolean,
        numAutoFilledDates: Int,
        insertStartPos: Int = 0)
    {
        // memorize the inserted lines in tagSection
        ds.tagSection.clear()
        var numOfNewlines = newText.split("\n").size
        var markRange = false
        if (numOfNewlines > 0) {
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
                    if ((lvMain.selectedRow > 0) && lvMain.arrayList[lvMain.selectedRow].isSection) {
                        corrector = -1
                    }
                }
                if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    if ((lvMain.selectedRow < lvMain.arrayList.size - 1) && lvMain.arrayList[lvMain.selectedRow + 1].isSection) {
                        corrector = -1
                    }
                }
            }
            // plus button specific
            if (plusButtonInput) {
                // adjust positions to highlight
                if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                    if (addDateHeader) {
                        corrector = 2
                        numOfNewlines++
                    } else {
                        corrector = 1
                    }
                } else {
                    if (addDateHeader) {
                        corrector = 0
                        numOfNewlines++
                    } else {
                        corrector = 1
                    }
                }
                // each autofilled date is a header
                numOfNewlines += numAutoFilledDates
            }
            // now add the indexes to highlight
            for (i in 0 until numOfNewlines) {
                ds.tagSection.add(lvMain.selectedRow + i + corrector + insertStartPos)
            }
        }

        // make sure, selected item is visible
        var jumpToPos = lvMain.selectedRow
        if (!plusButtonInput) {
            // applies to edit an arbitrary item and place it somehow centered
            jumpToPos = calcJumpToPosition(lvMain.selectedRow)
        }

        // clean up
        fabPlus.pickAttachment = false
        lvMain.editLongPress = false
        fabPlus.editInsertLine = false
        fabPlus.imageCapture = false
        fabPlus.attachmentUri = ""
        fabPlus.inputAlertText = ""
        fabPlus.attachmentName = ""
        fabPlus.button?.background?.setAlpha(130)
        fabPlus.button?.show()
        // save and re-read saved data
        ds.dataSection[ds.selectedSection] = finalStr                          // update DataStore dataSection
        writeAppData(appStoragePath, ds, appName)                              // serialize DataStore to GrzLog.ser
        ds.clear()                                                             // clear DataStore
        ds = readAppData(appStoragePath)                                       // un serialize DataStore from GrzLog.ser
        val dsText = ds.dataSection[ds.selectedSection]                        // get raw data text from DataStore section/folder
        title = ds.namesSection[ds.selectedSection]                            // set app title to folder Name
        lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)      // convert & format raw text to array
        lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList) // set adapter and populate main listview
        lvMain.listView!!.adapter = lvMain.adapter

        // temporarily highlight edited items
        if (markRange) {
            for (i in 0 until ds.tagSection.size) {
                var index = ds.tagSection[i]
                if (index < lvMain.arrayList.size && index >= 0) {
                    lvMain.arrayList[ds.tagSection[i]].setHighLighted(true)
                }
            }
        }

        // ListView shall jump to last known 1st visible position to keep edited item steady
        lvMain.listView!!.setSelection(jumpToPos)

        // revoke temporary highlighting of edited items
        lvMain.listView!!.postDelayed({
            // un mark items
            if (markRange) {
                for (i in 0 until ds.tagSection.size) {
                    var index = ds.tagSection[i]
                    if (index < lvMain.arrayList.size && index >= 0) {
                        lvMain.arrayList[index].setHighLighted(false)
                    }
                }
            }
            ds.tagSection.clear()
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
            if (!ds.undoSection.isEmpty()) {
                ds.dataSection[ds.selectedSection] = ds.undoSection // update DataStore dataSection
                writeAppData(appStoragePath, ds, appName)
                ds.clear()
                ds = readAppData(appStoragePath)
                val dsText = ds.dataSection[ds.selectedSection]       // raw data from DataStore
                title = ds.namesSection[ds.selectedSection]           // set app title to folder Name
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
            ds.undoSection = ""
            ds.undoText = ""
            ds.undoAction = ACTION.UNDEFINED
        }
        showMenuItemUndo()
        // cancel shall delete a captured image
        if (fabPlus.imageCapture && fabPlus.attachmentUri!!.length > 0) {
            deleteCapturedImage(fabPlus.attachmentUri)
        }
        // was hidden during input
        fabPlus.button?.background?.setAlpha(130)
        fabPlus.button?.show()
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

    // fabPlus button long click action --> a new menu with options to jump
    private fun fabPlusOnLongClick(view: View, lv: ListView?) {
        // get out, if current folder is not authorized
        if (!MainActivity.folderIsAuthenticated) {
            centeredToast(this, getString(R.string.folderNotAuthorized), 3000)
            return
        }
        if (lv == null) {
            return
        }
        // estimate current view position
        var currentViewPct = (100.0 * lv.firstVisiblePosition / lv.adapter!!.count + 0.5).toInt()
        // prepare single choice item pre selection
        var checkItem = -1
        if (lv.firstVisiblePosition == 0) {
            checkItem = 0
        }
        if (lv.firstVisiblePosition == lv.adapter!!.count / 4) {
            checkItem = 1
        }
        if (lv.firstVisiblePosition == lv.adapter!!.count / 2) {
            checkItem = 2
        }
        if (lv.firstVisiblePosition == lv.adapter!!.count * 3 / 4) {
            checkItem = 3
        }
        if (lv.lastVisiblePosition == lv.adapter!!.count - 1) {
            checkItem = 4
            currentViewPct = 100
        }
        // single choice dialog
        val optionsBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        var title = getString(R.string.JumpTo) + " ( " + currentViewPct.toString() + " % )"
        optionsBuilder.setTitle(title)
        var optionsDialog: AlertDialog? = null
        optionsBuilder.setSingleChoiceItems(
            arrayOf(
                getString(R.string.top),
                getString(R.string.upper_quarter),
                getString(R.string.middle),
                getString(R.string.lower_quarter),
                getString(R.string.bottom),
                getString(R.string.search_phrase) + if (jumpToPhrase.length > 0) ": '" + jumpToPhrase + "'" else ""
            ),
            checkItem
        ) { dialog, which ->
            if (which == 0) {
                // jump to top
                lv.setSelection(0)
            }
            if (which == 1) {
                lv.setSelection(lv.adapter.count / 4)
            }
            if (which == 2) {
                lv.setSelection(lv.adapter.count / 2)
            }
            if (which == 3) {
                lv.setSelection(lv.adapter.count * 3 / 4)
            }
            if (which == 4) {
                // jump to bottom
                lv.setSelection(lv.adapter.count - 1)
            }
            if (which == 5) {
                // jump to a search phrase: repeating a search phrase on multiple hits, jumps further down
                val input = EditText(this)
                input.inputType = InputType.TYPE_CLASS_TEXT
                input.setText(jumpToPhrase)
                showEditTextContextMenu(input, false)
                // input text change listener resets jumpToPhrase string
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable) { jumpToPhrase = "" }
                })
                // AlertBuilder as UI control
                var inputBuilder: AlertDialog.Builder? = null
                inputBuilder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                inputBuilder.setTitle(getString(R.string.jump_to_phrase))
                inputBuilder.setView(input)
                // input dlg ok
                inputBuilder.setPositiveButton(
                    getString(R.string.ok),
                    DialogInterface.OnClickListener { dialogChange, which ->
                        var text = input.text.toString()
                        if (text.isEmpty()) {
                            // clean up and return
                            jumpToPhrase = text
                            lvMain.unselectSearchHits()
                            lvMain.searchHitListFolder.clear()
                            return@OnClickListener
                        }
                        // the following gets only executed, after the search phrase was altered
                        if (jumpToPhrase != text) {
                            // save phrase for later use
                            jumpToPhrase = text
                            // reset previous search hits
                            lvMain.unselectSearchHits()
                            lvMain.searchHitListFolder.clear()
                            // loop array for search hits
                            for (pos in lvMain.arrayList.indices) {
                                val itemText = lvMain.arrayList[pos].title
                                if (itemText!!.lowercase(Locale.getDefault()).contains(jumpToPhrase.lowercase(Locale.getDefault()))) {
                                    lvMain.searchHitListFolder.add(pos)
                                }
                            }
                            // note if nothing was found
                            if (lvMain.searchHitListFolder.size == 0) {
                                centeredToast(
                                    this@MainActivity,
                                    "'" + jumpToPhrase + "' " + getString(R.string.not_existing),
                                    Toast.LENGTH_LONG
                                )
                                return@OnClickListener
                            } else {
                                centeredToast(
                                    this@MainActivity,
                                    "'" + jumpToPhrase + "' " + lvMain.searchHitListFolder.size.toString() + getString(R.string.x_found),
                                    Toast.LENGTH_LONG
                                )
                            }
                            // signal a start position
                            lvMain.searchNdx = -1
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
                        lvMain.arrayList[ndx].setHighLighted(true)
                        // revoke highlighting after timeout
                        Handler().postDelayed({
                            lvMain.arrayList[ndx].setHighLighted(false)
                            lvMain.adapter!!.notifyDataSetChanged()
                        }, 2000)

                    })
                // input dlg cancel
                inputBuilder.setNegativeButton(
                    R.string.cancel,
                    DialogInterface.OnClickListener { dialogRename, which ->
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(input.windowToken, 0)
                    })
                // input dlg 'jump to phrase' show
                inputBuilder.show()
                // tricky way to let the keyboard popup
                input.requestFocus()
                showKeyboard(input, 0, 0, 250)
            }
            optionsDialog!!.dismiss()
        }
        // + button long click options dialog
        optionsBuilder.setNegativeButton(R.string.cancel) { dialog, which -> }
        optionsDialog = optionsBuilder.create()
        optionsDialog.setOnShowListener {
            optionsDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        optionsDialog?.show()
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
        // hide all other menu items
        showMenuItems(false)
        // handle action_search_view click in App-Menu-Toolbar
        val searchMenuItem = menu.findItem(R.id.action_search_view)
        searchMenuItem.expandActionView()
        searchView = searchMenuItem.actionView as SearchView?
        // hide action_search_view menu item
        searchMenuItem.isVisible = false
        // hide view
        searchView!!.isVisible = false
        // on get focus
        searchView!!.setOnQueryTextFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // clear any listview selection
                lvMain.unselectSelections()
                // starting a search shall quit the '10s auto clear' menu handler: aka search hides other menu items
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
                item = appMenu!!.findItem(R.id.action_Hambullet)
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
        // current folder search action closes
        searchView!!.setOnCloseListener { // close search query
            quitCurrentFolderSearch()
            false
        }
        // search action begins
        searchView!!.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                // remove all previous jump to remains
                if (jumpToPhrase.length > 0) {
                    jumpToPhrase = ""
                    lvMain.unselectSearchHits()
                    lvMain.searchHitListFolder.clear()
                }
                // handle older search hit remains
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
        for (pos in lvMain.arrayList.indices) {
            val itemText = lvMain.arrayList[pos].title
            if (itemText!!.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))) {
                lvMain.arrayList[pos].setSearchHit(true)
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
        // save query phrase for later use
        searchViewQuery = query
        // adapter refresh
        lvMain.adapter!!.notifyDataSetChanged()
        // show arrow keys in app menu
        val itemUp = appMenu!!.findItem(R.id.action_searchUp) as MenuItem
        itemUp.isVisible = true
        val itemDown = appMenu!!.findItem(R.id.action_searchDown) as MenuItem
        itemDown.isVisible = true
        // jump to search hits: to keep it easy, ALWAYS start from top
        if (lvMain.showOrder == SHOW_ORDER.TOP) {
            lvMain.searchNdx = -1
            // delayed action: needed for pixel 5 sdk 35 -- not needed Emu sdk35 and system < sdk35
            Handler().postDelayed({
                onOptionsItemSelected(itemDown)
            }, 100)
        } else {
            lvMain.searchNdx = lvMain.searchHitListFolder.size
            // delayed action: needed for pixel 5 sdk 35 -- not needed Emu sdk35 and system < sdk35
            Handler().postDelayed({
                onOptionsItemSelected(itemUp)
            }, 100)
        }
        // quit folder search
        searchView!!.onActionViewCollapsed()
        // keep search query in memory
        searchView!!.setQuery(query, false)
    }

    // show / hide menu items BUT Hamburger & Folder
    fun showMenuItems(showAll: Boolean) {
        try {
            var item: MenuItem
            if (showAll) {
                item = appMenu!!.findItem(R.id.action_Hamburger)
                item.isVisible = false
                item = appMenu!!.findItem(R.id.action_Hambullet)
                item.isVisible = false
                item = appMenu!!.findItem(R.id.action_Settings)
                item.isVisible = true
            } else {
                if (grzlogUpdateAvailable) {
                    item = appMenu!!.findItem(R.id.action_Hamburger)
                    item.isVisible = false
                    item = appMenu!!.findItem(R.id.action_Hambullet)
                    item.isVisible = true
                } else {
                    item = appMenu!!.findItem(R.id.action_Hamburger)
                    item.isVisible = true
                    item = appMenu!!.findItem(R.id.action_Hambullet)
                    item.isVisible = false
                }
                item = appMenu!!.findItem(R.id.action_Settings)
                item.isVisible = false
            }
            item = appMenu!!.findItem(R.id.action_ChangeFolder)
            item.isVisible = showAll
            item = appMenu!!.findItem(R.id.action_Share)
            item.isVisible = showAll
            item = appMenu!!.findItem(R.id.action_search)
            item.isVisible = showAll
            item = appMenu!!.findItem(R.id.action_searchUp)
            item.isVisible = false
            item = appMenu!!.findItem(R.id.action_searchDown)
            item.isVisible = false
            showMenuItemUndo()
        } catch (npe: NullPointerException) {
        }
    }

    // three choices dialog
    fun threeChoicesDialog(
        context: Context?,
        message: String,
        option0: String,
        option1: String,
        option2: String,
        runner0: Runnable?,
        runner1: Runnable?,
        runner2: Runnable?,
        runnerClose: Runnable?
    ) {
        // theme
        val builder: AlertDialog.Builder? = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
        // set title
        builder?.setTitle(message)
        // the three options
        val charSequences: MutableList<CharSequence> = ArrayList()
        charSequences.add(option0) // ITEM == 0
        charSequences.add(option1) // ITEM == 1
        charSequences.add(option2) // ITEM == 2
        val itemsMore = charSequences.toTypedArray()
        builder?.setItems(
            itemsMore,
            DialogInterface.OnClickListener { dialog, whichOri ->
                var which = whichOri
                // ITEM == 0
                if (which == 0) {
                    runner0?.run()
                    dialog.dismiss()
                }
                // ITEM == 1
                if (which == 1) {
                    runner1?.run()
                    dialog.dismiss()
                }
                // ITEM == 2
                if (which == 2) {
                    runner2?.run()
                    dialog.dismiss()
                }
            })
        // button close
        builder?.setPositiveButton(
            R.string.close,
            DialogInterface.OnClickListener { dialog, which ->
                runnerClose?.run()
                dialog.dismiss()
            }
        )
        // run dialog
        val dialog = builder?.create()
        val listView = dialog?.getListView()
        listView?.divider = ColorDrawable(Color.GRAY)
        listView?.dividerHeight = 2
        dialog?.setOnShowListener {
            dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog?.show()
        dialog?.setCanceledOnTouchOutside(false)
    }

    // data structure to hold a string and a position in lvMain.arrayList OR dataStore
    class Hit(text: String, pos: Int) {
        var text: String = text
        var pos: Int = pos
    }
    // replace an original phrase with a replace phrase in ListView items
    fun replacePhraseInItems(replacePhrase: String, originalPhrase: String, replaceList: MutableList<Hit> ) {
        try {
            // hide all other menu items
            showMenuItems(false)
            // save undo data
            ds.undoSection = ds.dataSection[ds.selectedSection]
            ds.undoText = getString(R.string.edited_items)
            ds.undoAction = ACTION.REVERTMULTIEDIT
            showMenuItemUndo()
            // clean up
            lvMain.editLongPress = false
            fabPlus.pickAttachment = false
            fabPlus.editInsertLine = false
            fabPlus.imageCapture = false
            fabPlus.attachmentUri = ""
            fabPlus.inputAlertText = ""
            fabPlus.attachmentName = ""
            // get original data from DataStore
            val oriText = ds.dataSection[ds.selectedSection]
            var oriParts: Array<String?> = oriText.split("\\n+".toRegex()).toTypedArray()
            if (oriParts.size == 0) {
                oriParts = arrayOf("")
            }
            // oriText text is showOrder dependent
            if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                val botText = lvMain.toggleTextShowOrder(null, oriText).str
                oriParts = botText.split("\\n+".toRegex()).toTypedArray()
                if (oriParts.size == 0) {
                    oriParts = arrayOf("")
                }
            }
            // final string collector
            var finalStr: String = ""
            // loop items from replaceList
            var sum = 0
            replaceList.forEach() {
                // exec requested replace: all occurrences need to match case
                var newText = it.text
                newText = newText.replace(originalPhrase, replacePhrase, false)
                // make the actual change
                oriParts[it.pos] = newText
                sum++
            }
            // build a temporary full final text
            val tmpStr = TextUtils.join("\n", oriParts)
            // care about show order for the "final final text"
            finalStr = if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
                lvMain.toggleTextShowOrder(null, tmpStr).str
            } else {
                tmpStr
            }
            // sake of mind
            if (!finalStr.endsWith("\n")) {
                finalStr += "\n"
            }
            // save and re-read saved data
            ds.dataSection[ds.selectedSection] = finalStr                          // update DataStore dataSection
            writeAppData(appStoragePath, ds, appName)                              // serialize DataStore to GrzLog.ser
            ds.clear()                                                             // clear DataStore
            ds = readAppData(appStoragePath)                                       // un serialize DataStore from GrzLog.ser
            val dsText = ds.dataSection[ds.selectedSection]                        // get raw data text from DataStore section/folder
            title = ds.namesSection[ds.selectedSection]                            // set app title to folder Name
            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)      // convert & format raw text to array
            lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList) // set adapter and populate main listview
            lvMain.listView!!.adapter = lvMain.adapter
            // temporarily highlight the edited items
            if (ds.tagSection.size > 0) {
                // jump/scroll to 1st modified item
                lvMain.listView!!.setSelection(ds.tagSection[0])
                // loop to temporarily highlight the edited items
                for (i in 0 until ds.tagSection.size) {
                    var index = ds.tagSection[i]
                    if (index < lvMain.arrayList.size && index >= 0) {
                        lvMain.arrayList[ds.tagSection[i]].setHighLighted(true)
                    }
                }
            }
            // revoke temporary highlighting of edited items
            lvMain.listView!!.postDelayed({
                // un mark edited items
                if (ds.tagSection.size > 0) {
                    for (i in 0 until ds.tagSection.size) {
                        var index = ds.tagSection[i]
                        if (index < lvMain.arrayList.size && index >= 0) {
                            lvMain.arrayList[index].setHighLighted(false)
                        }
                    }
                }
                // inform listview adapter about the changes
                lvMain.adapter!!.notifyDataSetChanged()
            }, 3000)
        } catch ( e:Exception) {}
    }

    // handle action bar item clicks
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // selector
        val id = item.itemId
        // HAMBURGER / GEAR: show settings activity
        if (id == R.id.action_Hamburger || id == R.id.action_Hambullet || id == R.id.action_Settings) {
            // Hamburger always cancels undo
            ds.undoSection = ""
            ds.undoText = ""
            ds.undoAction = ACTION.UNDEFINED
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

        // SEARCH: tap icon action_search, search actions: local vs. search&replace vs. global
        if (id == R.id.action_search) {
            // get out, if current folder is not authorized
            if (!MainActivity.folderIsAuthenticated) {
                centeredToast(this, getString(R.string.folderNotAuthorized), 3000)
                return super.onOptionsItemSelected(item)
            }
            // stop  menu '10s auto clear' mechanism
            menuItemsVisible = false
            mainMenuHandler.removeCallbacksAndMessages(null)
            // show the 'current folder' search view
            var runnable0 = Runnable {
                onPrepareOptionsMenu(appMenu)
                searchView!!.isIconified = false
                var searchMenuItem = appMenu!!.findItem(R.id.action_search_view)
                searchMenuItem.expandActionView()
                searchMenuItem.isVisible = true
                searchView!!.isVisible = true
                // hide loupe icon
                var loupeMenuItem = appMenu!!.findItem(R.id.action_search)
                loupeMenuItem!!.isVisible = false
            }
            // search & replace in the whole current folder, regardless if selected or not
            var runnable1 = Runnable {
                folderSearchReplace(false, false)
            }
            // search all GrzLog folders - but not, if current folder is protected
            var runnable2 = Runnable {
                // if current folder is protected, do not exec global search from here
                if (ds.timeSection[ds.selectedSection] == TIMESTAMP.AUTH) {
                    okBox(
                        this@MainActivity,
                        getString(R.string.note),
                        getString(R.string.noGlobalSearchFromProtectedFolder)
                    )
                    // simply return to main activity
                    showMenuItems(false)
                    searchView!!.setQuery(searchViewQuery, false)
                    searchViewQuery = ""
                    title = ds.namesSection[ds.selectedSection]
                    fabPlus.button?.background?.setAlpha(130)
                    fabPlus.button?.show()
                } else {
                    // hide menu items
                    showMenuItems(false)
                    // execute global search
                    val fbt = fabBack!!.tag as FabBackTag
                    if (fbt.searchHitListGlobal.size > 0) {
                        decisionBox(
                            this,
                            DECISION.YESNO,
                            getString(R.string.searchResultsAvailable),
                            getString(R.string.useExistingResults) + " " + fbt.searchPhrase,
                            { prepareGlobalSearch(fbt.searchHitListGlobal, fbt.searchPhrase) },
                            { prepareGlobalSearch(ArrayList(), "") }
                        )
                    } else {
                        prepareGlobalSearch(ArrayList(), "")
                    }
                }
            }
            var runnableClose = Runnable {
                // simply return to main activity
                showMenuItems(false)
                searchView!!.setQuery(searchViewQuery, false)
                searchViewQuery = ""
                title = ds.namesSection[ds.selectedSection]
                fabPlus.button?.background?.setAlpha(130)
                fabPlus.button?.show()
            }
            // three options: search current folder, search&replace current folder, search GrzLog, cancel
            threeChoicesDialog(
                this,
                getString(R.string.where_to_search),
                getString(R.string.search_in_folder) + "'" + ds.namesSection[ds.selectedSection] + "'",
                getString(R.string.search_replace_in_folder) + "'" + ds.namesSection[ds.selectedSection] + "'",
                getString(R.string.search_all_grzlog_folders),
                runnable0,
                runnable1,
                runnable2,
                runnableClose)
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
            var string = ds.namesSection[ds.selectedSection] + "  " +
                    (lvMain.searchNdx + 1).toString() +
                    "(" + lvMain.searchHitListFolder.size.toString() + ") " +
                    searchViewQuery
            var text: Spanned
            text = Html.fromHtml("<small>$string</small>", Html.FROM_HTML_MODE_LEGACY)
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
            var string = ds.namesSection[ds.selectedSection] + "  " +
                    (lvMain.searchNdx + 1).toString() +
                    "(" + lvMain.searchHitListFolder.size.toString() + ") " +
                    searchViewQuery
            var text: Spanned
            text = Html.fromHtml("<small>$string</small>", Html.FROM_HTML_MODE_LEGACY)
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
                getString(R.string.undo) + " " + ds.undoText,
                { execUndo() },
                null
            )
        }
        // SHARE: content share option
        if (id == R.id.action_Share) {
            // get out, if current folder is not authorized
            if (!MainActivity.folderIsAuthenticated) {
                centeredToast(this, getString(R.string.folderNotAuthorized), 3000)
                return super.onOptionsItemSelected(item)
            }
            // it looks awkward, if search stays open
            if (searchView != null) {
                searchView!!.onActionViewCollapsed()
            }
            //  provide options about what to share
            var shareBuilder: AlertDialog.Builder?
            shareBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
            val items = arrayOf<CharSequence>(
                getString(R.string.selectedItems),
                getString(R.string.selectedItemsAsPDF),
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
                    // 0 - share current items selection
                    if (selected == 0) {
                        var itemsSelected = false
                        for (i in lvMain.arrayList.indices) {
                            if (lvMain.arrayList[i].isSelected()) {
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
                    // 1 - share selected items as PDF
                    if (selected == 1) {
                        // is there a selection at all
                        var itemsAreSelected = false
                        for (i in lvMain.arrayList.indices) {
                            if (lvMain.arrayList[i].isSelected()) {
                                itemsAreSelected = true
                                break
                            }
                        }
                        if (!itemsAreSelected) {
                            okBox(
                                this@MainActivity,
                                getString(R.string.note),
                                getString(R.string.makeSelection)
                            )
                            return@OnClickListener
                        }
                        // get to know the PDF title
                        var pdfTitleBuilder = AlertDialog.Builder(contextMainActivity, android.R.style.Theme_Material_Dialog)
                        pdfTitleBuilder.setTitle(getString(R.string.type_in_the_pdf_title))
                        var pdfTitle = EditText(contextMainActivity)
                        pdfTitle.setText("<" + getString(R.string.type_in_the_pdf_title) + ">")
                        pdfTitle.setSelection(0, pdfTitle.text.length)
                        pdfTitleBuilder.setView(pdfTitle)
                        pdfTitleBuilder.setNegativeButton(R.string.cancel) { dialog, which ->
                            return@setNegativeButton
                        }
                        pdfTitleBuilder.setPositiveButton(R.string.ok) { dialog, which ->
                            // build PDF from selected items
                            generatePdf(pdfTitle.text.toString(), lvMain.folderSelectedItems, true, null)
                        }
                        val dialog = pdfTitleBuilder.create()
                        dialog.setOnShowListener {
                            dialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                        }
                        dialog.show()
                        pdfTitle.requestFocus()
                        showKeyboard(pdfTitle, 0, 0, 250)
                        pdfTitle.postDelayed({ pdfTitle.selectAll() }, 500)
                    }
                    // 2 - share complete folder as text
                    if (selected == 2) {
                        shareBody = lvMain.selectedFolder
                        clipboard = shareBody
                    }
                    // open share dialog
                    if (selected == 2 || selected == 0) {
                        // open share options
                        val sharingIntent = Intent(Intent.ACTION_SEND)
                        sharingIntent.type = "text/plain"
                        val shareSub = "GrzLog"
                        sharingIntent.putExtra(Intent.EXTRA_SUBJECT, shareSub)
                        sharingIntent.putExtra(Intent.EXTRA_TEXT, shareBody)
                        startActivity(Intent.createChooser(sharingIntent, ""))
                    }
                    // 3 - share folder as PDF
                    if (selected == 3) {
                        val folderName = ds.namesSection[ds.selectedSection]
                        generatePdf(folderName, ds.dataSection[ds.selectedSection], true, null)
                    }
                    // 4 - share folder as RTF
                    if (selected == 4) {
                        val folderName = ds.namesSection[ds.selectedSection]
                        generateRtf(folderName, ds.dataSection[ds.selectedSection], true, null)
                    }
                })
            shareBuilder.setNegativeButton(R.string.back, null)
            val shareDialog = shareBuilder.create()
            val listView = shareDialog.listView
            listView.divider = ColorDrawable(Color.GRAY)
            listView.dividerHeight = 2
            shareDialog.setOnShowListener {
                shareDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            shareDialog.show()
            shareDialog.setCanceledOnTouchOutside(false)
        }
        // change database folder
        if (id == R.id.action_ChangeFolder) {
            folderChangeDialog(item)
        }
        return super.onOptionsItemSelected(item)
    }

    // "Change Folder Dialog"
    fun folderChangeDialog(item: MenuItem) {
        // it looks awkward, if search stays open
        if (searchView != null) {
            searchView!!.onActionViewCollapsed()
        }
        // deactivate pending toolbar options
        showMenuItems(false)
        menuItemsVisible = false
        mainMenuHandler.removeCallbacksAndMessages(null)
        // reset visibility of the folder switch back button
        if (fabBack != null) {
            fabBack!!.visibility = INVISIBLE
            val fbtOld = fabBack!!.tag as FabBackTag
            val fbt = FabBackTag("", fbtOld.searchHitListGlobal, -1, fbtOld.searchPhrase)
            fabBack!!.tag = fbt
        }
        // change folder always cancels undo
        ds.undoSection = ""
        ds.undoText = ""
        ds.undoAction = ACTION.UNDEFINED
        showMenuItemUndo()
        // CHANGE FOLDER
        var changeFolderBuilder: AlertDialog.Builder? = null
        changeFolderBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        changeFolderBuilder.setTitle(R.string.selectFolder)
        var selectedSectionTemp = ds.selectedSection
        var lastClickTime = System.currentTimeMillis()
        var lastSelectedSection = ds.selectedSection
        // add a radio button list containing all the folder names from DataStore
        val charSequences: MutableList<CharSequence> = ArrayList()
        // change text color to yellow on black for protected folders
        for (pos in ds.namesSection.indices) {
            var spanStr = SpannableString(ds.namesSection[pos])
            if (ds.timeSection[pos] == TIMESTAMP.AUTH) {
                spanStr.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            this,
                            R.color.yellow
                        )
                    ), 0, spanStr.length, 0
                )
            }
            charSequences.add(spanStr)
        }
        val folderNamesArray = charSequences.toTypedArray()
        changeFolderBuilder.setSingleChoiceItems(
            folderNamesArray,
            ds.selectedSection,
            DialogInterface.OnClickListener { dialog, which ->
                // the current file selection is temporary, unless we confirm with OK
                selectedSectionTemp = which
                // check double click event: it shall behave like OK, means make the folder change permanent
                val nowTime = System.currentTimeMillis()
                val deltaTime = nowTime - lastClickTime
                if (deltaTime < 700 && lastSelectedSection == which) {
                    // now the folder selection becomes permanent
                    switchToFolderByNumber(ds.selectedSection, selectedSectionTemp, true)
                    dialog.cancel()
                }
                lastSelectedSection = which
                lastClickTime = nowTime
            })
        // CHANGE FOLDER selection OK
        changeFolderBuilder.setPositiveButton(
            R.string.ok,
            DialogInterface.OnClickListener { dialog, which ->
                // reset dirty flag
                MainActivity.changeFolderDialogIsDirty = false
                // now the folder selection becomes permanent
                switchToFolderByNumber(ds.selectedSection, selectedSectionTemp, true)
                dialog.cancel()
            })
        // CHANGE FOLDER selection Cancel
        if (MainActivity.changeFolderDialogIsDirty == false) {
            changeFolderBuilder.setNegativeButton(
                R.string.back,
                DialogInterface.OnClickListener { dialog, which ->
                    dialog.cancel()
                })
        }
        // CHANGE FILE selection MORE FILE OPTIONS
        changeFolderBuilder.setNeutralButton(
            R.string.next,
            DialogInterface.OnClickListener { dialog, which ->
                if (item != null) {
                    folderMoreDialog(changeFolderBuilder.context, selectedSectionTemp, item)
                }
            })
        // CHANGE FOLDER create dialog
        MainActivity.changeFolderDialog = changeFolderBuilder.create()
        val listView = MainActivity.changeFolderDialog?.getListView()
        listView?.divider = ColorDrawable(Color.GRAY)
        listView?.dividerHeight = 2
        // CHANGE FOLDER finally show change folder dialog
        try {
            MainActivity.changeFolderDialog?.setOnShowListener {
                MainActivity.changeFolderDialog?.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            MainActivity.changeFolderDialog?.show()
        } catch (ex: Exception) {
            // not nice, but no further handling needed
        }
    }

    // follow up dialog to "Change Folder Dialog" with options for one folder
    fun folderMoreDialog(changeFileBuilderContext: Context, selectedSectionTemp: Int, item: MenuItem) {
        val itemText = ds.namesSection[selectedSectionTemp]
        // MORE FOLDER OPTIONS
        val items = arrayOf<CharSequence>(     // x = FOLDER options
            getString(R.string.openFolder),                            // 0 Open
            getString(R.string.export),                          // 1 Export
            getString(R.string.renFolder),                          // 2 Rename
            getString(R.string.clearFolder),                   // 3 Clear
            getString(R.string.removeFolder),                          // 4 Remove
            getString(R.string.moveFolderTop),                     // 5 Move top
            getString(R.string.moveFolderBottom),                  // 6 Move top
            getString(R.string.moreSetting),                        // 7 Folder setting
            "",                                // 8 empty ITEM as separator
            getString(R.string.newFolder)              // 9 New
        )
        var folderMoreBuilder = AlertDialog.Builder(
                changeFileBuilderContext,
                android.R.style.Theme_Material_Dialog)
        val folderMoreBuilderContext = folderMoreBuilder.context
        folderMoreBuilder.setTitle(getString(R.string.whatTodoWithFolder) + itemText + "'")
        folderMoreBuilder.setItems(
            items,
            DialogInterface.OnClickListener { dialogRename, which ->
                // MORE FOLDER OPTIONS: open folder
                if (which == 0) {
                    // reset dirty flag
                    MainActivity.changeFolderDialogIsDirty = false
                    // now the folder selection becomes permanent
                    switchToFolderByNumber(ds.selectedSection, selectedSectionTemp, true)
                }
                //  MORE FILE OPTIONS: Export folder to PDF or RTF
                if (which == 1) {
                    if (ds.timeSection[selectedSectionTemp] == TIMESTAMP.AUTH && selectedSectionTemp != ds.selectedSection) {
                        // a protected folder must be open to allow to export it
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.openProtectedFolderBeforeAccess),
                            {folderMoreDialog?.show()}
                        )
                        return@OnClickListener
                    }
                    // EXPORT FOLDER to PDF or RTF
                    var exportBuilder: AlertDialog.Builder?
                    exportBuilder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
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
                                val folderName = ds.namesSection[selectedSectionTemp]
                                val rawText = ds.dataSection[selectedSectionTemp]
                                generatePdf(folderName, rawText, false, folderMoreBuilder)
                            }
                            //  EXPORT OPTIONS: RTF
                            if (tmpExportSelection == 1) {
                                val folderName = ds.namesSection[selectedSectionTemp]
                                val rawText = ds.dataSection[selectedSectionTemp]
                                generateRtf(folderName, rawText, false, folderMoreBuilder)
                            }
                            // EXPORT OPTIONS: copy folder to clipboard
                            if (tmpExportSelection == 2) {
                                shareBody = ds.dataSection[selectedSectionTemp]
                                clipboard = shareBody
                            }
                        })
                    // EXPORT OPTIONS back
                    exportBuilder.setNegativeButton(
                        R.string.back,
                        DialogInterface.OnClickListener { dialog, which -> folderMoreDialog?.show() })
                    // EXPORT OPTIONS show
                    exportBuilder.create().show()
                }
                // MORE FOLDER OPTIONS: Rename
                if (which == 2) {
                    if (ds.timeSection[selectedSectionTemp] == TIMESTAMP.AUTH && selectedSectionTemp != ds.selectedSection) {
                        // a protected folder must be open to allow to rename it
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.openProtectedFolderBeforeAccess),
                            {folderMoreDialog?.show()}
                        )
                        return@OnClickListener
                    }
                    // folder name rename dialog
                    val input = EditText(folderMoreBuilderContext)
                    input.inputType = InputType.TYPE_CLASS_TEXT
                    input.setText(
                        ds.namesSection[selectedSectionTemp],
                        TextView.BufferType.SPANNABLE
                    )
                    showEditTextContextMenu(input, false) // suppress edit context menu
                    var renameBuilder: AlertDialog.Builder? = null
                    renameBuilder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
                    renameBuilder.setTitle(R.string.changeFolderName)
                    renameBuilder.setView(input)
                    // folder name rename ok
                    renameBuilder.setPositiveButton(
                        getString(R.string.ok),
                        DialogInterface.OnClickListener { dialogChange, which ->
                            var text = input.text.toString()
                            if (text.isEmpty()) {
                                centeredToast(this, getString(R.string.emptyInput), 3000)
                                Handler().postDelayed({
                                    folderMoreDialog?.show()
                                }, 100)
                                return@OnClickListener
                            }
                            if (isDupeFolder(text)) {
                                centeredToast(this, getString(R.string.duplicateName), 3000)
                                Handler().postDelayed({
                                    folderMoreDialog?.show()
                                }, 100)
                                return@OnClickListener
                            }
                            // no back button in change folder dialog
                            MainActivity.changeFolderDialogIsDirty = true
                            // reset global search results
                            if (fabBack != null) {
                                fabBack!!.visibility = INVISIBLE
                                val fbt = FabBackTag("", ArrayList(), -1, "")
                                fabBack!!.tag = fbt
                            }
                            ds.namesSection[selectedSectionTemp] = text
                            ds.selectedSection = selectedSectionTemp
                            writeAppData(appStoragePath, ds, appName)
                            ds.clear()
                            ds = readAppData(appStoragePath)
                            // update app title bar
                            title = ds.namesSection[ds.selectedSection]
                            // close parent dialog
                            changeFolderDialog!!.cancel()
                            // but show more dialog
                            folderMoreDialog?.setTitle(getString(R.string.whatTodoWithFolder) + ds.namesSection[selectedSectionTemp] + "'")
                            folderMoreDialog?.show()
                        })
                    // folder name rename cancel
                    renameBuilder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialogRename, which ->
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(input.windowToken, 0)
                            folderMoreDialog?.show()
                        })
                    // folder name rename show
                    renameBuilder.show()
                    // tricky way to let the keyboard popup
                    input.requestFocus()
                    showKeyboard(input, 0, 0, 250)
                    // select input name
                    input.postDelayed({ input.selectAll() }, 500)
                }
                // MORE FOLDER OPTIONS: Clear folder content
                if (which == 3) {
                    if (ds.timeSection[selectedSectionTemp] == TIMESTAMP.AUTH && selectedSectionTemp != ds.selectedSection) {
                        // a protected folder must be open to allow to clear its content
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.openProtectedFolderBeforeDeletion),
                            {folderMoreDialog?.show()}
                        )
                        return@OnClickListener
                    }
                    var builder: AlertDialog.Builder? = null
                    builder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
                    builder.setTitle(getString(R.string.clearFolderData) + itemText + "' ?")
                    builder.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialogChange, which ->
                            // reset global search results
                            if (fabBack != null) {
                                fabBack!!.visibility = INVISIBLE
                                val fbt = FabBackTag("", ArrayList(), -1, "")
                                fabBack!!.tag = fbt
                            }
                            // no back button in change folder dialog
                            MainActivity.changeFolderDialogIsDirty = true
                            // cleanup
                            ds.dataSection[selectedSectionTemp] = ""
                            ds.selectedSection = selectedSectionTemp
                            writeAppData(appStoragePath, ds, appName)
                            ds.clear()
                            ds = readAppData(appStoragePath)
                            // close parent dialog
                            changeFolderDialog!!.cancel()
                            // restart with resume()
                            reReadAppFileData = true
                            onResume()
                        })
                    builder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialogRename, which -> folderMoreDialog?.show() })
                    builder.show()
                }
                //  MORE FOLDER OPTIONS: Remove folder
                if (which == 4) {
                    if (ds.timeSection[selectedSectionTemp] == TIMESTAMP.AUTH && selectedSectionTemp != ds.selectedSection) {
                        // a protected folder must be open to allow to remove it
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.openProtectedFolderBeforeDeletion),
                            {folderMoreDialog?.show()}
                        )
                        return@OnClickListener
                    }
                    var builder: AlertDialog.Builder? = null
                    builder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
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
                                    // no back button in change folder dialog
                                    MainActivity.changeFolderDialogIsDirty = true
                                    // reset global search results
                                    if (fabBack != null) {
                                        fabBack!!.visibility = INVISIBLE
                                        val fbt = FabBackTag("", ArrayList(), -1, "")
                                        fabBack!!.tag = fbt
                                    }
                                    // remove folder
                                    if (ds.namesSection.size > 1) {
                                        ds.namesSection.removeAt(
                                            selectedSectionTemp
                                        )
                                        ds.selectedSection = Math.max(selectedSectionTemp - 1, 0)
                                        ds.dataSection.removeAt(selectedSectionTemp)
                                    } else {
                                        ds.namesSection[selectedSectionTemp] = getString(R.string.folder)
                                        ds.selectedSection = selectedSectionTemp
                                        ds.dataSection[selectedSectionTemp] = ""
                                    }
                                    // save data
                                    writeAppData(appStoragePath, ds, appName)
                                    ds.clear()
                                    ds = readAppData(appStoragePath)
                                    // close parent dialog
                                    changeFolderDialog!!.cancel()
                                    // restart with resume()
                                    reReadAppFileData = true
                                    onResume()
                                }
                            ) { folderMoreDialog?.show() }
                        })
                    builder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialogRename, which -> folderMoreDialog?.show() })
                    builder.show()
                }
                //  MORE FOLDER OPTIONS: Move folder to top in list
                if (which == 5) {
                    if (ds.namesSection.size < 2 || selectedSectionTemp == 0) {
                        val folderName = ds.namesSection[selectedSectionTemp]
                        centeredToast(
                            this,
                            "'" + folderName + "' " + getString(R.string.folderAlreadyAtTop),
                            5000
                        )
                        onOptionsItemSelected(item)
                        return@OnClickListener
                    }
                    // force no back button in change folder dialog
                    MainActivity.changeFolderDialogIsDirty = true
                    // temporarily save current folder
                    val nameTmp = ds.namesSection[selectedSectionTemp]
                    val dataTmp = ds.dataSection[selectedSectionTemp]
                    val timeTmp = ds.timeSection[selectedSectionTemp]
                    // all folders from selectedSectionTemp - 1 till 0 move 1 level down
                    for (i in selectedSectionTemp downTo 1 step 1) {
                        ds.namesSection[i] = ds.namesSection[i-1]
                        ds.dataSection[i] = ds.dataSection[i-1]
                        ds.timeSection[i] = ds.timeSection[i-1]
                    }
                    // temporarily saved current folder goes to top
                    ds.namesSection[0] = nameTmp
                    ds.dataSection[0] = dataTmp
                    ds.timeSection[0] = timeTmp
                    // current folder went to top, so does the folder selection index
                    ds.selectedSection = 0
                    // make change permanent
                    writeAppData(appStoragePath, ds, appName)
                    ds.clear()
                    ds = readAppData(appStoragePath)
                    reReadAppFileData = true
                    MainActivity.folderIndexIsUnreliable = true
                    onOptionsItemSelected(item)
                }
                //  MORE FOLDER OPTIONS: Move folder to bottom of list
                if (which == 6) {
                    if (ds.namesSection.size < 2 || selectedSectionTemp == ds.namesSection.size - 1) {
                        val folderName = ds.namesSection[selectedSectionTemp]
                        centeredToast(
                            this,
                            "'" + folderName + "' " + getString(R.string.folderAlreadyAtBottom),
                            5000
                        )
                        onOptionsItemSelected(item)
                        return@OnClickListener
                    }
                    // force no back button in change folder dialog
                    MainActivity.changeFolderDialogIsDirty = true
                    // temporarily save current folder
                    val nameTmp = ds.namesSection[selectedSectionTemp]
                    val dataTmp = ds.dataSection[selectedSectionTemp]
                    val timeTmp = ds.timeSection[selectedSectionTemp]
                    // all folders from selectedSectionTemp till bottom move 1 level up
                    for (i in selectedSectionTemp..ds.namesSection.size - 2 step 1) {
                        ds.namesSection[i] = ds.namesSection[i+1]
                        ds.dataSection[i] = ds.dataSection[i+1]
                        ds.timeSection[i] = ds.timeSection[i+1]
                    }
                    // temporarily saved current folder goes to bottom
                    ds.namesSection[ds.namesSection.size - 1] = nameTmp
                    ds.dataSection[ds.namesSection.size - 1] = dataTmp
                    ds.timeSection[ds.namesSection.size - 1] = timeTmp
                    // current folder went to bottom, so does the folder selection index
                    ds.selectedSection = ds.namesSection.size - 1
                    // make change permanent
                    writeAppData(appStoragePath, ds, appName)
                    ds.clear()
                    ds = readAppData(appStoragePath)
                    reReadAppFileData = true
                    MainActivity.folderIndexIsUnreliable = true
                    onOptionsItemSelected(item)
                }
                // MORE FOLDER OPTIONS: Timestamp setting
                if (which == 7) {
                    if (ds.timeSection[selectedSectionTemp] == TIMESTAMP.AUTH && selectedSectionTemp != ds.selectedSection) {
                        // a protected folder must be open to access it
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.openProtectedFolderBeforeAccess),
                            {folderMoreDialog?.show()}
                        )
                        return@OnClickListener
                    }
                    val items = arrayOf<CharSequence>(
                        getString(R.string.noProperty),
                        "auto hh:mm",
                        "auto hh:mm:ss",
                        getString(R.string.folderProtect)
                    )
                    var newTimeSectionProp = ds.timeSection[selectedSectionTemp]
                    var prvTimeSectionProp = ds.timeSection[selectedSectionTemp]
                    var dialog: AlertDialog?
                    var builder: AlertDialog.Builder?
                    builder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
                    builder.setTitle(R.string.folderSetting)
                    builder.setSingleChoiceItems(
                        items,
                        newTimeSectionProp,
                        DialogInterface.OnClickListener { dialog, which ->
                            newTimeSectionProp = which
                        })
                    builder.setPositiveButton(
                        "Ok",
                        // ok takes over the recently selected option
                        DialogInterface.OnClickListener { dialog, which ->
                            // now there are tree scenarios: 1) 2) 3)
                            if (newTimeSectionProp == TIMESTAMP.AUTH) {
                                // 1) folder is about to switch to protected mode
                                decisionBox(
                                    this,
                                    DECISION.YESNO,
                                    getString(R.string.folderProtect),
                                    getString(R.string.handleFolderProtection),
                                    {
                                        changeFolderProtection(newTimeSectionProp, selectedSectionTemp)
                                        folderMoreDialog?.show()
                                    },
                                    {
                                        folderMoreDialog?.show()
                                    }
                                )
                            } else {
                                if (newTimeSectionProp != TIMESTAMP.AUTH && prvTimeSectionProp == TIMESTAMP.AUTH) {
                                    // 2) folder is about to switch from protected mode to unprotected
                                    decisionBox(
                                        this,
                                        DECISION.YESNO,
                                        getString(R.string.folderProtect),
                                        getString(R.string.revokeFolderProtection),
                                        {
                                            changeFolderProtection(newTimeSectionProp, selectedSectionTemp)
                                            folderMoreDialog?.show()
                                        },
                                        {
                                            folderMoreDialog?.show()
                                        }
                                    )
                                } else {
                                    // 3) folder only deals with timestamps
                                    changeFolderTimeStampProperty(newTimeSectionProp, selectedSectionTemp)
                                }
                            }
                        })
                    builder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialog, which -> folderMoreDialog?.show() })
                    dialog = builder.create()
                    val listView = dialog.listView
                    listView.divider = ColorDrawable(Color.GRAY)
                    listView.dividerHeight = 2
                    dialog.show()
                }
                // ITEM == 8 'empty space'
                if (which == 8) {
                    Handler().postDelayed({
                        folderMoreDialog?.show()
                    }, 100)
                }
                //  MORE FILE OPTIONS: New
                if (which == 9) {
                    // reject add item
                    if (ds.namesSection.size >= DataStore.SECTIONS_COUNT) {
                        var builder: AlertDialog.Builder? = null
                        builder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
                        builder.setTitle(R.string.note)
                        builder.setMessage(getString(R.string.folderLimit) + DataStore.SECTIONS_COUNT + getString(R.string.folders))
                        builder.setIcon(android.R.drawable.ic_dialog_alert)
                        builder.setPositiveButton(
                            R.string.ok,
                            DialogInterface.OnClickListener { dialog, which -> folderMoreDialog?.show() })
                        builder.show()
                        return@OnClickListener
                    }
                    // real add item
                    val input = EditText(folderMoreBuilderContext)
                    input.inputType = InputType.TYPE_CLASS_TEXT
                    input.setText(R.string.folder, TextView.BufferType.SPANNABLE)
                    showEditTextContextMenu(input, false)
                    var addBuilder: AlertDialog.Builder?
                    addBuilder = AlertDialog.Builder(folderMoreBuilderContext, android.R.style.Theme_Material_Dialog)
                    addBuilder.setTitle(R.string.folderNewName)
                    addBuilder.setView(input)
                    addBuilder.setPositiveButton(
                        R.string.ok,
                        DialogInterface.OnClickListener { dialogChange, which ->
                            var text = input.text.toString()
                            if (text.isEmpty()) {
                                centeredToast(this, getString(R.string.emptyInput), 3000)
                                Handler().postDelayed({
                                    folderMoreDialog?.show()
                                }, 100)
                                return@OnClickListener
                            }
                            if (isDupeFolder(text)) {
                                centeredToast(this, getString(R.string.duplicateName), 3000)
                                Handler().postDelayed({
                                    folderMoreDialog?.show()
                                }, 100)
                                return@OnClickListener
                            }
                            MainActivity.changeFolderDialogIsDirty = true
                            ds.dataSection.add("")
                            ds.namesSection.add(text)
                            ds.selectedSection = ds.namesSection.size - 1
                            ds.timeSection.add(ds.selectedSection, TIMESTAMP.OFF)
                            ds.tagSection = mutableListOf(-1, -1)
                            writeAppData(appStoragePath, ds, appName)
                            ds.clear()
                            ds = readAppData(appStoragePath)
                            reReadAppFileData = true
                            onResume()
                        })
                    addBuilder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialogRename, which ->
                            val imm =
                                getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(input.windowToken, 0)
                            folderMoreDialog?.show()
                        })
                    addBuilder.show()
                    // tricky way to let the keyboard popup
                    input.requestFocus()
                    showKeyboard(input, 0, 0, 250)
                    // preselect folder input name for easier override, <300ms don't work
                    input.postDelayed({ input.selectAll() }, 500)
                }
            }
        )
        // MORE FOLDER OPTIONS back/cancel
        folderMoreBuilder.setNegativeButton(R.string.back) { dialog, which ->
            onOptionsItemSelected(item)
        }
        // MORE FILE OPTIONS show
        folderMoreDialog = folderMoreBuilder.create()
        val listView = folderMoreDialog?.getListView()
        listView?.divider = ColorDrawable(Color.GRAY)
        listView?.dividerHeight = 2
        folderMoreDialog?.show( )
    }

    // folder's timestamp property is about to change
    fun changeFolderTimeStampProperty(newTimeStampProp: Int, selectedSectionTemp: Int) {
        MainActivity.changeFolderDialogIsDirty = true  // no back button in change folder dialog
        ds.timeSection[selectedSectionTemp] = newTimeStampProp
        writeAppData(appStoragePath, ds, appName)      // save data
        ds.clear()
        ds = readAppData(appStoragePath)
        folderMoreDialog?.show()                       // show more dlg again
    }

    // provide global search results;
    // note: use of contextMainActivity instead of this to allow to call it from companion object
    public fun prepareGlobalSearch(searchHitListGlobal: MutableList<GlobalSearchHit>, searchPhrase: String) {
        if (searchHitListGlobal.size > 0) {
            // re use global search hit list
            jumpToSearchHitInFolderDialog(contextMainActivity, searchHitListGlobal, -1, searchPhrase)
        } else {
            // input dialog for global search phrase
            val inputSearch = EditText(contextMainActivity)
            inputSearch.inputType = InputType.TYPE_CLASS_TEXT
            inputSearch.setText(searchPhrase)
            inputSearch.postDelayed({ inputSearch.selectAll() }, 500)
            var inputBuilderDialog: AlertDialog? = null
            var inputBuilder: AlertDialog.Builder = AlertDialog.Builder(contextMainActivity)
            inputBuilder = AlertDialog.Builder(contextMainActivity, android.R.style.Theme_Material_Dialog)
            inputBuilder.setTitle(contextMainActivity.getString(R.string.searchPhrase))
            inputBuilder.setView(inputSearch)
            inputBuilder.setPositiveButton(R.string.ok) { dialog, which ->
                val searchText = inputSearch.text.toString()
                // no input --> get out
                if (searchText.isEmpty()) {
                    inputBuilderDialog!!.dismiss()
                    prepareGlobalSearch(searchHitListGlobal, searchPhrase)
                    return@setPositiveButton
                }
                // find all search hits in DataStore
                var searchHitList: MutableList<GlobalSearchHit> = findTextInDataStore(contextMainActivity, searchText, lvMain)
                // nothing found --> get out
                if (searchHitList.size == 0) {
                    centeredToast(contextMainActivity, contextMainActivity.getString(R.string.nothingFound), 3000)
                    inputBuilderDialog!!.dismiss()
                    Handler().postDelayed({
                        prepareGlobalSearch(searchHitListGlobal, searchText)
                    }, 100)
                    return@setPositiveButton
                }
                // hide keyboard
                hideKeyboard(inputSearch)
                // render search hits and let user pick one to jump to
                Handler().postDelayed({
                    jumpToSearchHitInFolderDialog(contextMainActivity, searchHitList, -1, searchText)
                }, 50)
            }
            inputBuilder.setNegativeButton(R.string.cancel) { dialog, which ->
            }
            inputBuilderDialog = inputBuilder.create()
            val listView = folderMoreDialog?.getListView()
            listView?.divider = ColorDrawable(Color.BLACK)
            listView?.dividerHeight = 3
            inputBuilderDialog.setOnShowListener {
                inputBuilderDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            inputBuilderDialog.show()
            inputSearch.requestFocus()
            showKeyboard(inputSearch, 0, 0, 250)
        }
    }

    // check for duplicate folder names
    fun isDupeFolder(newName: String): Boolean {
        var dupe = false
        for (name in ds.namesSection) {
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
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                        ),
                        PERMISSION_REQUEST_MEDIA
                    )
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
        var retVal = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            retVal = true
        } else {
            requestPermissions(this, arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), PERMISSION_REQUEST_EXIFDATA)
            retVal = false
        }
        return retVal
    }
    fun verifyLocationPermission(): Boolean {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION ) == PackageManager.PERMISSION_GRANTED) {
            locationPermissionDenied = 0
            val spe = sharedPref.edit()
            spe.putInt("locationPermissionDenied", locationPermissionDenied)
            spe.apply()
            return true
        } else {
            if ( locationPermissionDenied < 3 ) {
                locationPermissionDenied++
                val spe = sharedPref.edit()
                spe.putInt("locationPermissionDenied", locationPermissionDenied)
                spe.apply()
            }
            requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_LOCATION )
            return false
        }
    }

    //
    // extend class EditText to receive 'paste from menu'
    //
    @SuppressLint("AppCompatCustomView")
    inner class GrzEditText(context: Context) : EditText(context) {
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

    // register a media (image or video) picker activity launcher
    val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // callback invoked after media is selected or picker activity closed
        if (uri != null) {
            // get permanent read permission: releasePersistableUriPermission(uri, flag) will revoke that
            try {
                // apply for permanent read permission
                MainActivity.contextMainActivity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // leave it not handled: happens, if uri permission was removed - get it again won't fly
            }
            // get the real file name of a "MediaStore Picker" Uri, which only provides a confusing MediaStore ID
            var uriString = uri.toString()
            var idMediaStore = uriString.substring(uriString.lastIndexOf("/") + 1).toInt()
            val imageInfo = GalleryInfo.getGalleryMediaInfo(this, idMediaStore)
            // handle the provided image link with fabPlus button
            fabPlus.pickAttachment = true
            // name as to be found via Gallery, Photos, TC
            fabPlus.attachmentUri = imageInfo?.name
            // 'MediaStore Picker Uri' goes to attachmentUriUri
            fabPlus.attachmentUriUri = uri 
            // distinguish two media types: image and video
            fabPlus.attachmentImageLink = false
            fabPlus.attachmentVideoLink = false
            if (imageInfo?.type == GalleryInfo.MediaType.IS_IMAGE) {
                fabPlus.attachmentImageLink = true
                fabPlus.attachmentName = getString(R.string.image)
            }
            if (imageInfo?.type == GalleryInfo.MediaType.IS_VIDEO) {
                fabPlus.attachmentVideoLink = true
                fabPlus.attachmentName = getString(R.string.video)
            }
            // nothing to scale
            fabPlus.attachmentImageScale = false
            fabPlusOnClick(
                ReturnToDialogData.adapterView,
                ReturnToDialogData.itemView,
                ReturnToDialogData.itemPosition,
                ReturnToDialogData.itemId!!,
                ReturnToDialogData.returnToSearchHits,
                ReturnToDialogData.function
            )
        } else {
            centeredToast(this, getString(R.string.photo_picker_no_media_selected), 5000)
            startFilePickerDialog()
        }
    }

    // attachment picker dlg for fabPlus
    fun startFilePickerDialog() {
        ReturnToDialogData.itemId?.let {
            startFilePickerDialog(
                ReturnToDialogData.attachmentAllowed,
                ReturnToDialogData.linkText,
                ReturnToDialogData.adapterView,
                ReturnToDialogData.itemView,
                ReturnToDialogData.itemPosition,
                it,
                ReturnToDialogData.returnToSearchHits,
                ReturnToDialogData.function
            )
        }
    }
    @SuppressLint("MissingPermission")
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
        // suppress in app reminder at return from picker
        MainActivity.showAppReminders = false
        // check media link count limit
        val currentMediaLinkCount = this.contentResolver.getPersistedUriPermissions().size
        var childLinkMediaIsEnabled = true
        // build a dialog
        var pickerBuilder: AlertDialog.Builder?
        pickerBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
        pickerBuilder.setTitle(R.string.Select)
        // attachment picker dialog OPTIONS
        val options = arrayOf<CharSequence>(
            getString(R.string.MediaLink),
            getString(R.string.MediaCopy),
            getString(R.string.GrzLogLink),
            getString(R.string.Camera),
            "Audio",
            "PDF <- /sdcard/Download",
            "TXT <- /sdcard/Download",
            "WWW",
            getString(R.string.appFolder),
            getString(R.string.location),
            getString(R.string.editExistingLink)
        )
        // static memorize the return data
        ReturnToDialogData(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
        // set ListView items
        pickerBuilder.setItems(options, DialogInterface.OnClickListener { dialog, item ->
            // common intent
            var intent: Intent?
            when (item) {
                0 -> { // GrzLog links MEDIA to Android gallery
                    if (childLinkMediaIsEnabled) {
                        if (verifyMediaPermission()) {
                            // launch the "Android Photo Picker" and let the user choose one image or video
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            //
                            // Note:
                            // PICK.LINK & intent & onActivityResult(..) do not provide a reusable
                            //    Uri for image linking.
                            //
                            // continue with pickMedia registerForActivityResult(..)
                            return@OnClickListener
                        } else {
                            dialog.dismiss()
                            startFilePickerDialog()
                            return@OnClickListener
                        }
                    } else {
                        okBox(
                            this@MainActivity,
                            getString(R.string.note),
                            getString(R.string.image_link_maximum_reached),
                            { startFilePickerDialog() }
                        )
                    }
                }
                1 -> { // copy MEDIA from Android gallery to GrzLog
                    if (verifyMediaPermission()) {
                        // resolves permission fail on documents, but generates issue with GooglePhoto
                        val getIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        getIntent.type = "*/*"
                        getIntent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        getIntent.addCategory(Intent.CATEGORY_OPENABLE)
                        // allow picking additional pictures from documents
                        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        pickIntent.type = "*/*"
                        pickIntent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        val chooserIntent = Intent.createChooser(getIntent, getString(R.string.pickImage))
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pickIntent))
                        startActivityForResult(chooserIntent, PICK.MEDIA)
                    } else {
                        dialog.dismiss()
                        startFilePickerDialog()
                        return@OnClickListener
                    }
                }
                2 -> { // link MEDIA to GrzLog gallery
                    // start app gallery activity, if scan process is finished - under normal conditions, it's done before someone comes here
                    if (appGalleryScanning) {
                        centeredToast(this, getString(R.string.waitForFinish), Toast.LENGTH_SHORT)
                        var pw = ProgressWindow(this, getString(R.string.waitForFinish))
                        pw.dialog?.setOnDismissListener {
                            startFilePickerDialog()
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
                                    // finally show, what was supposed to be shown
                                    val galleryIntent = Intent(this, GalleryActivity::class.java)
                                    startActivity(galleryIntent)
                                }
                            }.start()
                        } catch (e: Exception) {}
                        return@OnClickListener
                    } else {
                        val galleryIntent = Intent(this, GalleryActivity::class.java)
                        startActivity(galleryIntent)
                        return@OnClickListener
                    }
                }
                3 -> { // CAPTURE from camera
                    if (verifyCameraPermission()) {
                        capturedPhotoUri = makeCameraCaptureIntent()
                    } else {
                        dialog.dismiss()
                        startFilePickerDialog()
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
                        startFilePickerDialog()
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
                    tv.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
                    tv.inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    tv.setSingleLine(false)
                    tv.gravity = Gravity.LEFT or Gravity.TOP
                    var wwwBuilder: AlertDialog.Builder
                    wwwBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
                    // set theme
                    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                    var theme = sharedPref.getString(getString(R.string.chosenTheme), "Dark")
                    if (theme != "Dark") {
                        tv.setTextColor(Color.WHITE)
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
                            startFilePickerDialog()
                            return@OnClickListener
                        })
                    wwwBuilder.setView(tv)
                    wwwDialog = wwwBuilder.create()
                    wwwDialog.setOnShowListener {
                        wwwDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    wwwDialog.show()
                    wwwDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, 600)
                    wwwDialog.setCanceledOnTouchOutside(false)
                    tv.requestFocus()
                    showKeyboard(tv, 0, 0, 250)
                }
                8 -> { // link to a GrzLog folder
                    var getFolderBuilder: AlertDialog.Builder? = null
                    getFolderBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
                    getFolderBuilder.setTitle(R.string.selectFolder)
                    var selectedSectionTemp = ds.selectedSection
                    // add a radio button list containing all the folder names from DataStore
                    val charSequences: MutableList<CharSequence> = ArrayList()
                    // change text color to yellow on black for protected folders
                    for (pos in ds.namesSection.indices) {
                        var spanStr = SpannableString(ds.namesSection[pos])
                        if (ds.timeSection[pos] == TIMESTAMP.AUTH) {
                            spanStr.setSpan(
                                ForegroundColorSpan(
                                    ContextCompat.getColor(
                                        this,
                                        R.color.yellow
                                    )
                                ), 0, spanStr.length, 0
                            )
                        }
                        charSequences.add(spanStr)
                    }
                    val folderList = charSequences.toTypedArray()
                    getFolderBuilder.setSingleChoiceItems(
                        folderList,
                        ds.selectedSection,
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
                            fabPlus.attachmentName = " [" + folderList[selectedSectionTemp] + "]"
                            dialog.dismiss()
                            fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                            dialog.cancel()
                        })
                    // Cancel
                    getFolderBuilder.setNegativeButton(
                        R.string.cancel,
                        DialogInterface.OnClickListener { dialog, which ->
                            dialog.dismiss()
                            startFilePickerDialog()
                            return@OnClickListener
                        })
                    // CHANGE FOLDER finally show change folder dialog
                    var getFolderDialog = getFolderBuilder.create()
                    val listView = MainActivity.changeFolderDialog?.getListView()
                    listView?.divider = ColorDrawable(Color.GRAY)
                    listView?.dividerHeight = 2
                    getFolderDialog?.show()
                }
                9 -> { // geo location
                    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                    locationPermissionDenied = sharedPref.getInt("locationPermissionDenied", 0)
                    var execOk : Boolean = getLastKnownLocation(attachmentAllowed, linkText, adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
                    if (Build.VERSION.SDK_INT >= 30) {
                        if (!execOk && locationPermissionDenied >= 3) {
                            okBox(
                                this@MainActivity,
                                getString(R.string.note),
                                getString(R.string.twiceDeniedPerm),
                                { startFilePickerDialog() }
                            )
                        } else {
                            if (!execOk) {
                                startFilePickerDialog()
                            }
                        }
                    } else {
                        if (!execOk) {
                            startFilePickerDialog()
                        }
                    }
                }
                10 -> { // edit existing attachment
                    // edit dialog
                    var editLinkDialog: AlertDialog? = null
                    // text editor
                    var tv = GrzEditText(this)
                    tv.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
                    tv.inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    tv.setSingleLine(false)
                    tv.gravity = Gravity.LEFT or Gravity.TOP
                    // edit builder
                    var editLinkBuilder: AlertDialog.Builder
                    editLinkBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
                    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                    var theme = sharedPref.getString(getString(R.string.chosenTheme), "Dark")
                    if (theme != "Dark") {
                        tv.setTextColor(Color.WHITE)
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
                                if (lnkParts != null && lnkParts.size >= 2) {
                                    // prepare replace link internals in full string
                                    var oldReplace = ""
                                    var oldFullText = fabPlus.inputAlertText
                                    val old = oldFullText?.let { PATTERN.UriLink.matcher(it) }
                                    if (old?.find() == true) {
                                        oldReplace = old.group()
                                    }
                                    oldFullText = oldFullText!!.replace(oldReplace, "[" + lnkParts[0] + "]")
                                    fabPlus.inputAlertText = oldFullText
                                    fabPlus.attachmentName = "[" + lnkParts[0] + "]"
                                    // distinguish linked media and copied media
                                    if (lnkParts[1].startsWith("content://media")) {
                                        // get the real file name of a "MediaStore Picker" Uri, which only provides a confusing MediaStore ID
                                        var uriString = lnkParts[1]
                                        var idMediaStore = uriString.substring(uriString.lastIndexOf("/") + 1).toInt()
                                        val mediaInfo = GalleryInfo.getGalleryMediaInfo(this, idMediaStore)
                                        if (mediaInfo == null) {
                                            // no info, link is gone --> cancel
                                            centeredToast(this, getString(R.string.linkDestroyed), 3000)
                                            dialog.cancel()
                                            return@OnClickListener
                                        }
                                        // handle the provided image link with fabPlus button
                                        fabPlus.pickAttachment = true
                                        // name as to be found via Gallery, Photos, TC
                                        fabPlus.attachmentUri = mediaInfo?.name
                                        // 'MediaStore Picker Uri' goes to attachmentUriUri
                                        val testUri = Uri.parse(lnkParts[1])
                                        if (testUri != null) {
                                            fabPlus.attachmentUriUri = testUri
                                        } else {
                                            fabPlus.attachmentUriUri = mediaInfo?.uri
                                        }
                                        // distinguish two media types: image and video
                                        fabPlus.attachmentImageLink = false
                                        fabPlus.attachmentVideoLink = false
                                        if (mediaInfo?.type == GalleryInfo.MediaType.IS_IMAGE) {
                                            fabPlus.attachmentImageLink = true
                                        }
                                        if (mediaInfo?.type == GalleryInfo.MediaType.IS_VIDEO) {
                                            fabPlus.attachmentVideoLink = true
                                        }
                                        // nothing to scale
                                        fabPlus.attachmentImageScale = false
                                        fabPlusOnClick(
                                            adapterView,
                                            itemView,
                                            itemPosition,
                                            itemId,
                                            returnToSearchHits,
                                            function
                                        )
                                    } else {
                                        // no media links, only copied files
                                        fabPlus.attachmentUri = lnkParts[1]
                                        fabPlus.attachmentUriUri = null
                                        fabPlus.pickAttachment = true
                                        fabPlusOnClick(
                                            adapterView,
                                            itemView,
                                            itemPosition,
                                            itemId,
                                            returnToSearchHits,
                                            function
                                        )
                                    }
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
                    editLinkDialog.setOnShowListener {
                        editLinkDialog.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    editLinkDialog.show()
                    editLinkDialog.setCanceledOnTouchOutside(false)
                    tv.setText(linkText)
                    tv.requestFocus()
                    showKeyboard(tv, 0, 0, 250)
                    // detect cancel: Android back button OR editLinkBuilder NegativeButton
                    editLinkDialog.setOnCancelListener { dlg ->
                        // restart
                        startFilePickerDialog()
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
        pickerBuilder.create()?.let {attachmentPickerDialog = it}
        val listView = attachmentPickerDialog!!.listView
        listView.divider = ColorDrawable(Color.GRAY)
        listView.dividerHeight = 2
        // certain items shall be disabled, depending on the flag 'attachmentAllowed' or link count limit
        listView!!.setOnHierarchyChangeListener(
            object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View) {
                    // 'attachmentAllowed' handling
                    val text = (child as AppCompatTextView).text
                    val itemIndex: Int = options.indexOf(text)
                    child.setEnabled(true)
                    if (attachmentAllowed) {
                        if (itemIndex == 10) {
                            child.setEnabled(false)
                            child.setOnClickListener(null)
                        }
                    } else {
                        if (itemIndex < 10) {
                            child.setEnabled(false)
                            child.setOnClickListener(null)
                        }
                    }
                    // media link limit of 512 is reached --> disable it
                    if (currentMediaLinkCount >= 512) {
                        if (itemIndex == 0) {
                            child.setEnabled(false)
                            childLinkMediaIsEnabled = false
                        }
                    }
                }
                override fun onChildViewRemoved(view: View?, view1: View?) {}
            })
        attachmentPickerDialog?.setOnShowListener {
            val widthWnd: Int = contextMainActivity.resources.displayMetrics.widthPixels
            attachmentPickerDialog?.getWindow()?.setLayout(widthWnd, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        attachmentPickerDialog?.let { it.show() }
        listView.setScrollbarFadingEnabled(false)
        attachmentPickerDialog?.let { it.setCanceledOnTouchOutside(false) }
        // detect cancel: Android back button OR editLinkBuilder NegativeButton
        attachmentPickerDialog?.let {
            it.setOnCancelListener { dialog ->
                // back to main input
                fabPlusOnClick(adapterView, itemView, itemPosition, itemId, returnToSearchHits, function)
            }
        }
    }

    // startFilePickerDialog() ends up here (!! not for image links !!!): Manifest --> android:launchMode="singleInstance"
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // registerForActivityResult calls onActivityResult for unknown reasons -> reject such case
        if (requestCode > PICK.ZIP) {
            return
        }

        // some init
        fabPlus.imageCapture = false         // camera capture needs a separate handling
        fabPlus.attachmentImageLink = false  // handled in pickMedia = registerForActivityResult
        fabPlus.attachmentVideoLink = false  // handled in pickMedia = registerForActivityResult
        fabPlus.attachmentImageScale = false // transports the above rescale decision into fabPlusOnClick

        // catch nonsense
        if (resultCode != RESULT_OK) {
            startFilePickerDialog()
            return
        }

        // handle requests
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
                startFilePickerDialog()
                return
            }
        }
        if (requestCode == PICK.MEDIA) {
            try {
                val uriOri = data!!.data
                var uri = uriOri
                // get a usable uri
                var mediaUriString: String? = uriOri.toString()
                if (mediaUriString!!.contains("com.google.android.apps.photos.contentprovider", ignoreCase = true)) {
                    // GooglePhoto needs extra care
                    val imagepath = FileUtils.getPath(this, uri!!)
                    val imagefile = File(imagepath.toString())
                    uri = Uri.fromFile(imagefile)
                    mediaUriString = uri.toString()
                } else {
                    // documents & content & Gallery
                    if (mediaUriString.startsWith("content://")) {
                        mediaUriString = getPath(this, uri!!)
                    }
                }
                if (uri != null) {
                    // prepare final handling in fabPlusOnClick()
                    var mimeType: String? = contentResolver.getType(uri)
                    if (mimeType == null) {
                        // GooglePhoto doesn't provide a mimeType --> needs extra care
                        val ext = getFileExtension(mediaUriString)
                        if (IMAGE_EXT.contains(ext, ignoreCase = true)) {
                            mimeType = "image/"
                        }
                        if (VIDEO_EXT.contains(ext, ignoreCase = true)) {
                            mimeType = "video/"
                        }
                    }
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = mediaUriString
                    fabPlus.attachmentUriUri = uriOri
                    if (mimeType?.contains("video/") == true) {
                        fabPlus.attachmentName = getString(R.string.video)
                    }
                    if (mimeType?.contains("image/") == true) {
                        fabPlus.attachmentName = getString(R.string.image)
                    }
                } else {
                    centeredToast(this, getString(R.string.no_media_info), 3000)
                    startFilePickerDialog()
                    return
                }
            } catch (e: Exception) {
                centeredToast(this, e.message.toString(), 3000)
                startFilePickerDialog()
                return
            }
        }
        if (requestCode == PICK.AUDIO) {
            val uriOri = data!!.data
            val uriPath = getPath(this, uriOri!!)
            val file = File(uriPath.toString())
            val uri = Uri.fromFile(file)
            if (uri != null) {
                fabPlus.pickAttachment = true
                fabPlus.attachmentUri = uri.toString()
                fabPlus.attachmentUriUri = uriOri
                fabPlus.attachmentName = getString(R.string.audio)
            } else {
                startFilePickerDialog()
                return
            }
        }
        if (requestCode == PICK.PDF) {
            val uriOri = data!!.data
            try {
                val uriPath = getPath(this, uriOri!!)
                val file = File(uriPath.toString())
                val uriNew = Uri.fromFile(file)
                if (uriNew != null) {
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = uriNew.toString()
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentName = "[" + file.name + "]"
                } else {
                    startFilePickerDialog()
                    return
                }
            }
            catch (e: Exception) {
                centeredToast(this, "PICK.PDF" + e.message.toString(), 3000)
                startFilePickerDialog()
                return
            }
            catch (e: FileNotFoundException) {
                centeredToast(this, "PICK.PDF" + e.message.toString(), 3000)
                startFilePickerDialog()
                return
            }
        }
        if (requestCode == PICK.TXT) {
            val uriOri = data!!.data
            try {
                val uriPath = getPath(this, uriOri!!)
                val file = File(uriPath.toString())
                val uriNew = Uri.fromFile(file)
                if (uriNew != null) {
                    fabPlus.pickAttachment = true
                    fabPlus.attachmentUri = uriNew.toString()
                    fabPlus.attachmentUriUri = uriOri
                    fabPlus.attachmentName = "[" + file.name + "]"
                } else {
                    startFilePickerDialog()
                    return
                }
            }
            catch (e: Exception) {
                centeredToast(this, "PICK.TXT" + e.message.toString(), 3000)
                startFilePickerDialog()
                return
            }
            catch (e: FileNotFoundException) {
                centeredToast(this, "PICK.TXT" + e.message.toString(), 3000)
                startFilePickerDialog()
                return
            }
        }

        //
        // call main input dialog fabPlusOnClick()
        //
        if (fabPlus.attachmentName.equals(getString(R.string.image)) || fabPlus.attachmentName.equals(getString(R.string.capture))) {
            // special handling for images to offer a case by case downscaling option
            var dialog: AlertDialog? = null
            val items = arrayOf<CharSequence>(getString(R.string.match_to_phone_s_screen))
            val checkedItems = BooleanArray(1) { true }
            val builder = AlertDialog.Builder(contextMainActivity, android.R.style.Theme_Material_Dialog)
            builder.setTitle(getString(R.string.scale_image))
            builder.setMultiChoiceItems(
                items,
                checkedItems,
                OnMultiChoiceClickListener { dlg, which, isChecked ->
                    checkedItems[which] = isChecked
                })
            // let fapPlus handle the copy image scenario
            builder.setPositiveButton("Ok", DialogInterface.OnClickListener { dlg, which ->
                // just a yes or no scaling
                fabPlus.attachmentImageScale = checkedItems[0]
                fabPlusOnClick(
                    ReturnToDialogData.adapterView,
                    ReturnToDialogData.itemView,
                    ReturnToDialogData.itemPosition,
                    ReturnToDialogData.itemId!!,
                    ReturnToDialogData.returnToSearchHits,
                    ReturnToDialogData.function
                )
            })
            // return to the attachment picker
            builder.setNegativeButton(
                R.string.cancel,
                DialogInterface.OnClickListener { dlg, which ->
                    startFilePickerDialog()
                })
            // provide help info
            builder.setNeutralButton(
                R.string.title_activity_help,
                DialogInterface.OnClickListener { dlg, which ->
                    centeredToast(
                        contextMainActivity,
                        getString(R.string.reduces_data_storage_size),
                        1
                    )
                    Handler().postDelayed({
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
        } else {
            // all other picks but image
            fabPlusOnClick(
                ReturnToDialogData.adapterView,
                ReturnToDialogData.itemView,
                ReturnToDialogData.itemPosition,
                ReturnToDialogData.itemId!!,
                ReturnToDialogData.returnToSearchHits,
                ReturnToDialogData.function
            )
        }
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
        // create the file, where the photo should go: the ONLY use case is AOSP camera
        try {
            uri = createImageUri()
            // weird: delete the recently generated but empty file - otherwise we would have two images a)an empty one + b)the real one
            val file = File(uri.toString())
            file.delete()
        } catch (ex: IOException) {
            return uri
        }
        if (uri != null) {
            // Intent captures image via camera app + stores image in gallery
            takePictureIntent.putExtra("return-data", true) // needed at all ??
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)   // image to gallery
            startActivityForResult(takePictureIntent, PICK.CAPTURE)
        } else {
            // in failure case, jump back to fabPlus dialog
            centeredToast(this, getString(R.string.noCameraOp), Toast.LENGTH_LONG)
            fabPlus.button!!.performClick()
        }
        return uri
    }

    //
    // "com.google.android.GoogleCamera", "org.lineageos.aperture" do not directly return an image
    // --> share the captured image from cam UI to GrzLog
    // AOSP camera will return an image to the calling app
    //
    // >= Android 11 camera apps are not added during ACTION_IMAGE_CAPTURE scanning
    // list of possible camera app candidates
    val CAMERA_SPECIFIC_APPS = arrayOf(
        "com.google.android.GoogleCamera",
        "org.lineageos.aperture",
        "best.camera",
        "net.sourceforge.opencamera",
        "tools.photo.hd.camera",
        "com.simplemobiletools.camera",
        "com.flipcamera",
        "org.witness.sscphase1",
        "com.lun.chin.aicamera",
        "com.lightbox.android.camera",
    )
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
            val packageManager = this@MainActivity.packageManager
            val intentTmp = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            var listCam = packageManager.queryIntentActivities(intentTmp, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // >= Android 11 camera apps are not added during ACTION_IMAGE_CAPTURE scanning
                listCam.addAll(getCameraSpecificAppsInfo(this@MainActivity))
            }
            // loop cam candidates and pick GCam if selected
            for (res in listCam) {
                if (useGoogleCamera) {
                    if (res.activityInfo.packageName == "com.google.android.GoogleCamera") {
                        camPackageName = res.activityInfo.packageName
                        break
                    }
                } else {
                    if (res.activityInfo.packageName != "com.google.android.GoogleCamera") {
                        camPackageName = res.activityInfo.packageName
                    }
                }
            }
            // build camera activity intent
            intentRet =
                // fallback to AOSP camera, !! it doesn't work if its package name is used !!
                if (camPackageName.isEmpty() || camPackageName.contains("com.android.camera")) {
                    // AOSP cam returns an image to the calling app
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                } else {
                    // GCam, Aperture do not directly return an image after capture
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

    @Throws(IOException::class)
    private fun createImageUri(): Uri? {
        var imageUri: Uri?
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date()) // Create an image file name
        val imageFileName = "JPEG_$timeStamp.jpg"
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
        return imageUri
    }

    //
    // tricky way to show soft keyboard: https://stackoverflow.com/questions/4597690/how-to-set-timer-in-android
    // uses contextMainActivity instead of this to allow to call it from companion object
    private fun showKeyboard_alt(et: EditText?, selectStart: Int, selectStop: Int, msDelay: Int) {
        if (et!!.requestFocus()) {
            Handler().postDelayed({
                et.setSelection(selectStart, selectStop)
                WindowCompat.getInsetsController(window, et).show(WindowInsetsCompat.Type.ime())
            }, msDelay.toLong())
        }
    }
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
            contextMainActivity.windowManager.defaultDisplay.getMetrics(displayMetrics)
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
            val imm = contextMainActivity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = contextMainActivity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // if image capture input is canceled, we need to remove/delete the previously captured image
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
        if (ds.undoSection.isEmpty() && ds.undoAction == ACTION.UNDEFINED) {
            ds.undoText = ""
            showMenuItemUndo()
            return
        }
        // execute undo, save and re-read saved data
        val lvCurrFstVisPos = lvMain.listView!!.firstVisiblePosition

        ds.dataSection[ds.selectedSection] = ds.undoSection                    // update DataStore dataSection
        ds.undoSection = ""                                                    // reset is time critical: make change + highlight + undo --> postDelay 'no highlight' might screw up
        writeAppData(appStoragePath, ds, appName)
        ds.clear()
        ds = readAppData(appStoragePath)
        val dsText = ds.dataSection[ds.selectedSection]                        // raw data from DataStore
        title = ds.namesSection[ds.selectedSection]                            // set app title to folder Name
        lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)      // convert & format raw text to array
        lvMain.adapter = LvAdapter(this@MainActivity, lvMain.arrayList) // set adapter and populate main listview
        lvMain.listView!!.adapter = lvMain.adapter

        if (lvMain.arrayList.size == 0) {                                      // get out here, if list is empty
            fabPlus.button?.background?.setAlpha(130)
            fabPlus.button?.show()
            ds.undoSection = ""
            ds.undoText = ""
            ds.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            return
        }

        // calc highlight positions
        var listSize = lvMain.arrayList.size
        var posAbove = Math.max(0, Math.min(lvMain.selectedRow, listSize - 1))
        var posBelow = Math.max(0, Math.min(lvMain.selectedRow - 1, listSize - 1))
        // ListView scroll position
        var posScrol = if (lvCurrFstVisPos == lvMain.fstVisPos) lvCurrFstVisPos else posAbove
        // an added item has special rules
        if (ds.undoAction == ACTION.REVERTADD) {
            ds.undoAction = ACTION.UNDEFINED
            if (lvMain.showOrder == SHOW_ORDER.TOP) {
                // revert add in SHOW_ORDER.TOP, always leaves a header and the item below the reverted item
                posAbove = 0
                posBelow = if (lvMain.arrayList[1].isSpacer) 2 else 1
            } else {
                // revert add in SHOW_ORDER.BOTTOM, always removes last item
                posBelow = posAbove
            }
            posScrol = posAbove
        }
        // revert insert: highlight neighbour too
        if (ds.undoAction == ACTION.REVERTINSERT) {
            ds.undoAction = ACTION.UNDEFINED
            posScrol = posBelow
            lvMain.arrayList[posBelow].setHighLighted(true)
        }
        // if tag section has useful data, aka delete a range
        var markRange = false
        if ((ds.tagSection.size > 0) and ((ds.undoAction == ACTION.REVERTDELETE) or (ds.undoAction == ACTION.REVERTMULTIEDIT)) ) {
            // try to keep restored items centered on screen
            posScrol = ds.tagSection[0]
            var maxPos = 0
            var minPos = Int.MAX_VALUE
            for (i in 1 until ds.tagSection.size) {
                minPos = Math.min(minPos, ds.tagSection[i])
                maxPos = Math.max(maxPos, ds.tagSection[i])
            }
            var deltaPos = (maxPos - minPos) / 2
            posScrol = minPos + if (deltaPos < 10) deltaPos else 10
            markRange = true
        }
        // highlight all previously deleted items stored in tag section
        try {
            if (markRange) {
                for (i in 1 until ds.tagSection.size) {
                    var index = ds.tagSection[i]
                    if (index < lvMain.arrayList.size && index >= 0) {
                        lvMain.arrayList[index].setHighLighted(true)
                    }
                }
            } else {
                lvMain.arrayList[posAbove].setHighLighted(true)
                lvMain.arrayList[posBelow].setHighLighted(true)
            }
        } catch (e: Exception) {
            centeredToast(this, e.message, 3000)
        }
        // scroll listview
        lvMain.listView!!.setSelection(posScrol - 10)

        // revoke temp. highlighting after timeout
        lvMain.listView!!.postDelayed({
            try {
                // de select
                if (markRange) {
                    for (i in 1 until ds.tagSection.size) {
                        var index = ds.tagSection[i]
                        if (index < lvMain.arrayList.size && index >= 0) {
                            lvMain.arrayList[index].setHighLighted(false)
                        }
                    }
                } else {
                    lvMain.arrayList[posAbove].setHighLighted(false)
                    lvMain.arrayList[posBelow].setHighLighted(false)
                }
                ds.tagSection.clear()
                // jump
                lvMain.listView!!.setSelection(posScrol - 10)
                // inform listview adapter about the changes
                lvMain.adapter!!.notifyDataSetChanged()
                // finally reset undo action
                ds.undoAction = ACTION.UNDEFINED
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
            fabPlus.button?.background?.setAlpha(130)
            fabPlus.button?.show()
        }

        // delete undo data but not undo action
        ds.undoSection = ""
        ds.undoText = ""
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
        var builder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
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

    private fun formatRtfText(text: String): Array<String> {
        // split input to lines
        var parts: Array<String> = text.split("\\n+".toRegex()).toTypedArray()
        // arrange data array according to show order
        if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
            parts = lvMain.toggleTextShowOrder(parts, text).arr
        }
        return parts
    }

    fun buildRTF(
        textLines: Array<String>,
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
            if (textLine.let { Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", it) } == true) {
                textLine = "\\line\n" + trimEndAll(textLine)
                if (textLine.length == 10) { // if week day name is missing, add it
                    textLine += dayNameOfWeek(textLine)
                }
            }
            // render an RTF line text directly to file (avoids utf8 / ANSI mess)
            // java String textLine utf8 (2 bytes) is converted to ANSI (1 byte) - ok für German Umlauts
            val ascii8Bytes = textLine.toByteArray(StandardCharsets.ISO_8859_1)
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
                    sb.append(getString(R.string.mediaFailure)).append("\\line\n")
                    sb.append("\\par\n")
                } catch (ex: OutOfMemoryError) {
                    sb.append("}\\line\n")
                    sb.append(getString(R.string.mediaFailure)).append("\\line\n")
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
        var builder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog)
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
                    "'" + folderName + ".pdf'\n" + getString(R.string.manualDeletePDF),
                    {
                        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                        this@MainActivity.startActivity(intent)
                    }
                )
            }
        }
        // generate PDF async in another thread
        try {
            runOnUiThread(Runnable {
                pw.show()
            })
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
        var parts: Array<String> = text.split("\\n+".toRegex()).toTypedArray()
        // arrange data array according to show order
        if (lvMain.showOrder == SHOW_ORDER.BOTTOM) {
            parts = lvMain.toggleTextShowOrder(parts, text).arr
        }
        // loop
        val len = parts.size
        for (i in 0 until len) {
            // header: a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu OR 9.toChar()
            if (Pattern.matches("\\d{4}-\\d{2}-\\d{2}.*", parts[i]) || parts[i].startsWith(9.toChar())) {
                // no spaces at end of the row - ONLY in timestamp part, NOT in any other part
                parts[i] = trimEndAll(parts[i])
                // headers shall be bold
                var ssPart = SpannableString(trimEndAll(parts[i]) + "\n")
                if (parts[i].startsWith(9.toChar())) {
                    // header via 9.toChar()
                    ssPart.setSpan(
                        BackgroundColorSpan(0x66777777),
                        0,
                        ssPart.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    // if week day name is missing, add it
                    if (parts[i].length == 10) {
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
                val ssPart = SpannableString(trimEndAll(parts[i]) + "\n")
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
                if (lnkParts != null && lnkParts.size >= 2) {
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
                            // if no bmp, try video file
                            if (VIDEO_EXT.contains(getFileExtension(uriString), ignoreCase = true)) {
                                // video file
                                try {
                                    val retriever = MediaMetadataRetriever()
                                    retriever.setDataSource(uriString)
                                    val bitmap = retriever.getFrameAtTime(0)
                                    tn.bmp = getDpiBitmap(bitmap, dpi)
                                } catch (iae: IllegalArgumentException) {
                                    tn.bmp = null
                                }
                            } else {
                                if (lnkParts.size > 2 && lnkParts[2].equals("video")) {
                                    // video link to phone gallery
                                    try {
                                        val retriever = MediaMetadataRetriever()
                                        val uri = Uri.parse(uriString)
                                        retriever.setDataSource(
                                            MainActivity.contextMainActivity,
                                            uri
                                        )
                                        val bitmap = retriever.getFrameAtTime(0)
                                        tn.bmp = getDpiBitmap(bitmap, dpi)
                                    } catch (e: Exception) {
                                        tn.bmp = null
                                    }
                                }
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

        // OUTER LOOP: text lines with image links
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

            // EITHER line with header OR simple line
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
                    tn = ThumbNail() // reset image link afterward is actually not needed
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

    // lock screen notification: generate message
    fun generateLockscreenNotification(message: String?) {
        // notification number must be unique
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        var notificationNumber = sharedPref.getInt("notificationNumber", 0)
        // prepare notification
        val intentShow = Intent(applicationContext, MainActivity::class.java)
        val taskStackBuilder = TaskStackBuilder.create(applicationContext)
        taskStackBuilder.addParentStack(MainActivity::class.java)
        taskStackBuilder.addNextIntent(intentShow)
        val pendingIntent = taskStackBuilder.getPendingIntent(
            100,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // render lock screen info
        val builder = NotificationCompat.Builder(applicationContext, "grzlog")
            .setSmallIcon(R.mipmap.ic_grzlog)
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

    // http request to communicate with GitHub GrzLog release site
    fun checkForAppUpdate() {
        grzlogUpdateAvailable = false
        val queue = Volley.newRequestQueue(this)
        var appVer = getString(R.string.tag_version).substring(1)
        val stringRequest = StringRequest(Request.Method.GET, getString(R.string.githubGrzLog),
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
                        var tagVersion = ""
                        for (tagVer in listTags) {
                            if (isUpdateDue(appVer.split('.').toTypedArray(), tagVer.split('.').toTypedArray())) {
                                tagVersion = tagVer
                                grzlogUpdateAvailable = true
                                break
                            }
                        }
                        if (grzlogUpdateAvailable) {
                            // change menu bar hamburger icon to hamburger with bullet
                            showMenuItems(false)
                            // make notification about available app update
                            val intent = Intent(applicationContext, MainActivity::class.java)
                            showNotification(MainActivity.contextMainActivity, "GrzLog", getString(R.string.availableUpdate) + " v" + tagVersion, intent, 1)
                        }
                    }
                }
            },
            object : Response.ErrorListener {
                override fun onErrorResponse(error: VolleyError?) {
                }
            })
        queue.add(stringRequest)
    }
    fun showNotification(context: Context, title: String?, message: String?, intent: Intent?, reqCode: Int) {
        val pendingIntent = PendingIntent.getActivity(context, reqCode, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val CHANNEL_ID = "GrzLog-Update-Check"
        val notificationBuilder: NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_grzlog)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = "GrzLog Update Check"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
        notificationManager.createNotificationChannel(mChannel)
        notificationManager.notify(reqCode, notificationBuilder.build()) // 0 is the request code, it should be unique id
    }


    // find text in all dataSections (folders) and return a list
    class GlobalSearchHit(
        var textCombined: SpannableString,
        var lineNdx: Int,
        var folderName: String
    )
    {}

    // static components accessible from other fragments / activities
    companion object {
        @JvmField
        // app name
        var appName = ""
        // app version main number
        var appVersionMain = 0
        // app has a menu bar: Search, Folder, Share, Settings
        var appMenu: Menu? = null
        // app external storage location
        var appStoragePath = ""
        // ListView shows data according to SHOW_ORDER
        @JvmField
        var lvMain = GrzListView()
        // global search: show a 'return to origin' button
        var fabBack: FloatingActionButton? = null
        // intent controls whether to jump back to Settings
        var intentSettings: Intent? = null
        // DataStore holds all data "new at top"; after onCreate() it is under no circumstances null
        @JvmField
        var ds: DataStore = DataStore()
        // this flag controls, whether onResume continues with onCreate()
        @JvmField
        var reReadAppFileData = false
        // returning from settings -> restore data shall not generate simple txt-backup in onPause()
        @JvmField
        var returningFromRestore = false
        // return a filename from app gallery picker
        @JvmField
        var attachmentPickerDialog: AlertDialog? = null
        var folderMoreDialog: AlertDialog? = null
        var changeFolderDialog: AlertDialog? = null // needs static, bc called from static
        var changeFolderDialogIsDirty = false       // controls appearance of back button in folder dlg
        var returningFromAppGallery = false
        var showFolderMoreDialog = false
        var returnAttachmentFromAppGallery = ""
        var appGalleryAdapter:  GalleryActivity.GridAdapter? = null
        var appGalleryScanning = false
        var appGallerySortedByDate = false
        var appScanCurrent = 0
        var appScanTotal = 0
        // backup ongoing
        @JvmField
        var backupOngoing = false
        // app visibility status
        @JvmField
        var appIsInForeground = false

        // API 36: get insets for UI bars
        lateinit var statusBarInset : Insets
        lateinit var navigationBarInset : Insets
        lateinit var captionBarInset : Insets
        lateinit var systemBarInset : Insets

        // if a folder index was moved, it might happen multiple times, so its index is unreliable
        var folderIndexIsUnreliable = false

        // flag to suppress the change folder dialog: TRUE after following a folder attachment
        // why this? showing the 'change folder dialog' deletes the back button data
        var dontShowChangeFolder = false

        // flag indicating GrzLog folder is authenticated
        var folderIsAuthenticated by Delegates.observable(false) { property, oldValue, newValue ->
            // discard folder change dialog, if folder is authenticated
            if (newValue == true) {
                try {
                    MainActivity.changeFolderDialog?.cancel()
                } catch (ex: Exception) {
                    // not nice, but no further handling needed
                }
            }
            // if folder is not authenticated, show change folder dialog
            if (newValue == false) {
                // the 'normal' use case is to show the change folder dialog
                if (!dontShowChangeFolder) {
                    appMenu?.performIdentifierAction(R.id.action_ChangeFolder, 0)
                }
                dontShowChangeFolder = false
            }
        }

        // Huawei launcher does not show lockscreen notifications generated in advance (though AOSP does), therefore build a list and show it at wakeup/screen on
        var grzlogReminderList: MutableList<String> = ArrayList()

        @JvmField
        // returning from other GrzLog activities shall not show reminders again
        var showAppReminders = true
        var temporaryBackground = false

        @JvmField
        // the app's password encrypted with app's public key
        var appPwdPub = ""

        @JvmField
        // flag indicating a GrzLog update is available
        var grzlogUpdateAvailable = false

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
        fun jumpToSearchHitInFolderDialog(context: Context, searchHitListGlobal: MutableList<GlobalSearchHit>, listNdx: Int, searchText: String) {
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
                // index sanity check
                if (hitsNdx in 0 until hits.size == false) {
                    centeredToast(context, contextMainActivity.getString(R.string.chooseSearchHit), 3000)
                    Handler().postDelayed({
                        jumpToSearchHitInFolderDialog(context, searchHitListGlobal, listNdx, searchText)
                    }, 100)
                    return@setPositiveButton
                }
                // don't jump to protected folders
                if (searchHitListGlobal[hitsNdx].folderName.equals("protected")) {
                    centeredToast(context, contextMainActivity.getString(R.string.forbidden), 3000)
                    Handler().postDelayed({
                        jumpToSearchHitInFolderDialog(context, searchHitListGlobal, listNdx, searchText)
                    }, 100)
                    return@setPositiveButton
                }
                // make sure, MainActivity is active (doesn't harm, if MainActivity is already active )
                val mainIntent = Intent(contextMainActivity, MainActivity::class.java)
                contextMainActivity.startActivity(mainIntent)
                // allow to jump back to search hit list dialog
                if (fabBack != null) {
                    val dsFolder = ds.namesSection[ds.selectedSection]
                    fabBack!!.tag = FabBackTag(dsFolder, searchHitListGlobal, hitsNdx, searchText)
                    fabBack!!.visibility = VISIBLE
                }
                // switch to the selected search hit in its folder
                var folderName = searchHitListGlobal[hitsNdx].folderName
                switchToFolderByName(folderName, false, searchHitListGlobal[hitsNdx].lineNdx)
            }
            jumpFolderBuilder.setNegativeButton(R.string.close) { dialog, which ->
                // allow to jump back to search hit list dialog
                if (fabBack != null) {
                    val dsFolder = ds.namesSection[ds.selectedSection]
                    fabBack!!.tag = FabBackTag(dsFolder, searchHitListGlobal, hitsNdx, searchText)
                    fabBack!!.visibility = INVISIBLE
                }
                if (intentSettings != null) {
                    // return to settings, if call came from 'show GrzLog gallery'
                    contextMainActivity.startActivity(intentSettings)
                } else {
                    // otherwise return to calling search dialog
                    // call a class method from companion object: https://stackoverflow.com/questions/43179402/access-methods-outside-companion-object-kotlin
                    MainActivity().prepareGlobalSearch(ArrayList(), "")
                }
            }
            jumpFolderDialog = jumpFolderBuilder.create()
            jumpFolderDialog.show()
        }

        // find & remove linkText in all dataSections (folders) and return success
        fun insideDataStoreSearchAndRemoveLink(linkText: String, pw: ProgressWindow, context: Context): Boolean {
            var success = true
            try {
                // iterate all data sections of DataStore
                for (dsNdx in ds.dataSection.indices) {
                    // set progress
                    if (pw != null) {
                        (context as Activity).runOnUiThread {
                            pw.incCount += 1
                        }
                    }
                    // get text from DataStore folder
                    var sectionText = ds.dataSection[dsNdx]
                    // find linkText: it is a fragmented full link, it misses the starting [ and literal description of the link
                    var pos = sectionText.indexOf(linkText)
                    while (pos != -1) {
                        // beginning from pos move lhs, find the next occurrence of "["
                        var i = 0
                        while (!sectionText[pos - i].equals('[')) {
                            i++
                            // sanity check, although not supposed to happen
                            if (i == pos) {
                                break
                            }
                        }
                        // foundLink shall now contain the full link info: [bla::::bla]
                        val foundLink = sectionText.substring(pos - i, pos + linkText.length)
                        // exec replacement, leave <X> as a visual leftover
                        sectionText = sectionText.replaceFirst(foundLink, "<X>")
                        // find next occurrence of linkText in DataStore
                        pos = sectionText.indexOf(linkText)
                    }
                    // put replaced data back into DataStore
                    ds.dataSection[dsNdx] = sectionText
                }
            } catch (e: Exception) {
                success = false
            }
            return success
        }

        // find & replace text in all dataSections (folders) and return success
        fun insideDataStoreSearchAndReplace(searchText: String, replaceText: String, pw: ProgressWindow, context: Context): Boolean {
            var success = true
            try {
                // iterate all data sections of DataStore
                for (dsNdx in ds.dataSection.indices) {
                    // set progress
                    if (pw != null) {
                        (context as Activity).runOnUiThread {
                            pw.incCount += 1
                        }
                    }
                    // get text from DataStore folder
                    var sectionText = ds.dataSection[dsNdx]
                    // search & replace action
                    sectionText = sectionText.replace(searchText, replaceText, false)
                    // put replaced data back into DataStore
                    ds.dataSection[dsNdx] = sectionText
                }
            } catch (e: Exception) {
                success = false
            }
            return success
        }

        // find text in all dataSections (folders) and return a list
        fun findTextInDataStore(context: Context, searchText: String, lvMain: GrzListView): MutableList<GlobalSearchHit> {
            var hitList: MutableList<GlobalSearchHit> = ArrayList()
            // iterate all data sections of DataStore
            for (dsNdx in ds.dataSection.indices) {
                // text from DataStore folder
                var sectionText = ds.dataSection[dsNdx]
                // take show order into account
                var arrayList: ArrayList<ListViewItem> = lvMain.makeArrayList(sectionText, lvMain.showOrder)
                // loop arrayList
                var sectionName = ""
                for (i in arrayList.indices) {
                    // save most current section name: it will be used, if there is a search hit
                    if (PATTERN.DateDay.matcher(arrayList[i].title.toString()).find() || arrayList[i].title.toString().startsWith(9.toChar())) {
                        sectionName = arrayList[i].title.toString()
                    }
                    //
                    var inspectStr = arrayList[i].title.toString()
                    if (arrayList[i].fullTitle!!.length > 0) {
                        inspectStr = arrayList[i].fullTitle.toString()
                    }
                    // save search hits data: the text where the search phrase occurs, its line index, its section name, its folder index
                    if (inspectStr.contains(searchText, ignoreCase = true)) {
                        val folderName = ds.namesSection[dsNdx]
                        val textCombined = inspectStr + "\n(" + sectionName + " / " + folderName + ")"
                        val spanCombined = SpannableString(textCombined)
                        var startIndex = 0
                        var searchTextStart = inspectStr.indexOf(searchText, startIndex, ignoreCase = true)
                        while (searchTextStart != -1 ) {
                            spanCombined.setSpan(
                                BackgroundColorSpan(
                                    ContextCompat.getColor(
                                        context,
                                        R.color.yellow
                                    )
                                ), searchTextStart, searchTextStart + searchText.length, 0
                            )
                            spanCombined.setSpan(RelativeSizeSpan(0.9F), 0, inspectStr.length, 0)
                            spanCombined.setSpan(
                                RelativeSizeSpan(0.7F),
                                inspectStr.length,
                                textCombined.length,
                                0
                            )
                            startIndex = searchTextStart + searchText.length
                            searchTextStart = inspectStr.indexOf(searchText, startIndex, ignoreCase = true)
                        }
                        if (ds.timeSection[dsNdx] == TIMESTAMP.AUTH) {
                            // global search is not allowed in protected folders
                            hitList.add(GlobalSearchHit(SpannableString("protected"), i, "protected"))
                        } else {
                            // searchable folder
                            hitList.add(GlobalSearchHit(spanCombined, i, folderName))
                        }
                    }
                }
            }
            return hitList
        }

        // show / hide menu item Undo; ds.undoAction == DataStore.ACTION.REVERTADD is special case, if DataStore is empty --> Add --> Cancel
        fun showMenuItemUndo() {
            val show = ds.undoSection.length > 0 || ds.undoAction == ACTION.REVERTADD || ds.undoAction == ACTION.REVERTINSERT
            if (appMenu != null) {
                val item = appMenu!!.findItem(R.id.action_Undo)
                item.isVisible = show
            }
        }

        // authentication
        private fun changeFolderProtection(newTimeStampProp: Int, selectedSectionTemp: Int) {
            // build a prompt info structure
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("GrzLog Authentication")
                .setSubtitle("Log in using system credential")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            // exec the actual prompt process
            val biometricPrompt = BiometricPrompt(contextMainActivity, ContextCompat.getMainExecutor(contextMainActivity),
                // setup a few callbacks
                object : BiometricPrompt.AuthenticationCallback() {
                    // error handling: usually CANCEL auth
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        showMessage("Authentication error: $errString")
                    }
                    // SUCCESS: auth did work
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        // handle authentication success
                        showMessage("Authentication succeeded!")
                        MainActivity.changeFolderDialogIsDirty = true // no back button in change folder dialog
                        ds.timeSection[selectedSectionTemp] = newTimeStampProp
                        writeAppData(appStoragePath, ds, appName)     // save data
                        ds.clear()
                        ds = readAppData(appStoragePath)
                        folderMoreDialog?.show()                      // show more dlg again
                    }
                    // failure: usually there is no system auth available
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        showMessage("Authentication failed.")
                    }
                })
            // exec auth
            biometricPrompt.authenticate(promptInfo)
        }
        private fun showBiometricPromptAndOpenFolder(prvFolderNumber: Int, newFolderNumber: Int, highLightPos: Int = -1) {
            // build a prompt info structure
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("GrzLog Authentication")
                .setSubtitle("Log in using system credential")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            // exec the actual prompt process
            val biometricPrompt = BiometricPrompt(contextMainActivity, ContextCompat.getMainExecutor(contextMainActivity),
                // setup a few callbacks
                object : BiometricPrompt.AuthenticationCallback() {
                    // error handling: usually CANCEL auth
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        showMessage("Authentication error: $errString")
                        // revoke folder access auth
                        MainActivity.folderIsAuthenticated = false
                        // in case, the previously active folder is unprotected, grant auth to it
                        if (prvFolderNumber != -1 && ds.timeSection[prvFolderNumber] != TIMESTAMP.AUTH) {
                            MainActivity.folderIsAuthenticated = true
                        }
                    }
                    // SUCCESS: auth did work
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        // handle authentication success
                        showMessage("Authentication succeeded!")
                        MainActivity.folderIsAuthenticated = true
                        openFolder(prvFolderNumber, newFolderNumber, highLightPos)
                    }
                    // failure: usually there is no system auth available
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        showMessage("Authentication failed.")
                        // revoke folder access auth
                        MainActivity.folderIsAuthenticated = false
                        // in case, the previously active folder is unprotected, grant auth to it
                        if (prvFolderNumber != -1 && ds.timeSection[prvFolderNumber] != TIMESTAMP.AUTH) {
                            MainActivity.folderIsAuthenticated = true
                        }
                    }
                })
            // exec auth
            biometricPrompt.authenticate(promptInfo)
        }
        private fun showMessage(message: String) {
            Toast.makeText(contextMainActivity, message, Toast.LENGTH_SHORT).show()
        }

        // switch to a folder helpers
        //   prvFolderNumber - previous folder number
        //   newFolderNumber - new selected  folder number
        //   checkReAuth     - re authorization needed (not needed, if jumping back and forth in global search hits)
        //   highLightPos    - a global search hit shall be placed somehow vertically centered
        fun switchToFolderByNumber(prvFolderNumber: Int, newFolderNumber: Int, checkReAuth: Boolean, highLightPos: Int = -1) {
            // sanity check
            if (newFolderNumber < 0 || newFolderNumber >= ds.dataSection.size) {
                if (fabBack != null) {
                    fabBack!!.visibility = INVISIBLE
                    val fbt = FabBackTag("", ArrayList(), -1, "")
                    fabBack!!.tag = fbt
                }
                okBox(contextMainActivity,
                    contextMainActivity.getString(R.string.folder_not_found),
                    contextMainActivity.getString(R.string.fix_it_by_edit_existing_attachment))
                return
            }
            // switch folder always cancels undo
            ds.undoSection = ""
            ds.undoText = ""
            ds.undoAction = ACTION.UNDEFINED
            showMenuItemUndo()
            // reset auth flag for folder:
            //   1) if folder number is about to change
            //   2) if folder index/number might be fishy (only happens after moving a folder in the appearance list)
            //   3) if folder re auth is requested AND 1) OR 2) is true
            if ( (ds.selectedSection != newFolderNumber || MainActivity.folderIndexIsUnreliable) && checkReAuth) {
                MainActivity.folderIsAuthenticated = false
                MainActivity.folderIndexIsUnreliable = false
            }
            // only auth folder access, if protected folder is not already authenticated
            if (ds.timeSection[newFolderNumber] == TIMESTAMP.AUTH && !MainActivity.folderIsAuthenticated && checkReAuth) {
                // authenticate before opening a protected folder
                showBiometricPromptAndOpenFolder(prvFolderNumber, newFolderNumber, highLightPos)
            } else {
                // opening folder doesn't need auth
                if (checkReAuth) {
                    MainActivity.folderIsAuthenticated = true
                }
                openFolder(prvFolderNumber, newFolderNumber, highLightPos)
            }
        }
        fun switchToFolderByName(name: String, checkReAuth: Boolean, scrollPos: Int = -1) {
            var prvFolderNumber = ds.selectedSection
            var newFolderNumber = -1
            val folderList = ds.namesSection.toTypedArray()
            for (i in folderList.indices) {
                if (folderList[i].equals(name)) {
                    newFolderNumber = i
                    break
                }
            }
            switchToFolderByNumber(prvFolderNumber, newFolderNumber, checkReAuth, scrollPos)
        }
        // full infra to open a DataStore folder
        fun openFolder(prvFolderNumber: Int, newFolderNumber: Int, highLightPos: Int) {
            // new active folder
            ds.selectedSection = newFolderNumber
            // write such info to disk and re read data from disk
            if (prvFolderNumber != -1 && prvFolderNumber != newFolderNumber ) {
                writeAppData(appStoragePath, ds, appName)
                ds.clear()
                ds = readAppData(appStoragePath)
            }
            val dsText = ds.dataSection[ds.selectedSection]
            lvMain.arrayList = lvMain.makeArrayList(dsText, lvMain.showOrder)
            lvMain.adapter = LvAdapter(contextMainActivity, lvMain.arrayList)
            lvMain.listView!!.adapter = lvMain.adapter
            contextMainActivity.title = ds.namesSection[ds.selectedSection]
            // protected folders title appearance
            val toolbar: Toolbar = contextMainActivity.findViewById<Toolbar>(R.id.toolbar)
            toolbar.setTitleTextColor(Color.WHITE)
            if (ds.timeSection[ds.selectedSection] == TIMESTAMP.AUTH) {
                val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
                var encryptProtectedFolders = sharedPref.getBoolean("encryptProtectedFolders", false)
                if (encryptProtectedFolders) {
                    toolbar.setTitleTextColor(Color.YELLOW)
                } else {
                    // warning triangle
                    var spanStr = SpannableString(ds.namesSection[ds.selectedSection] + "   ")
                    val drawable = AppCompatResources.getDrawable(MainActivity.contextMainActivity, android.R.drawable.ic_dialog_alert)
                    drawable!!.setBounds(0, 0, 50, 50)
                    val icon = ImageSpan(drawable, ImageSpan.ALIGN_CENTER)
                    spanStr.setSpan(icon, spanStr.length-2, spanStr.length-1, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
                    contextMainActivity.title = spanStr
                    // color
                    toolbar.setTitleTextColor(Color.MAGENTA)
                }
            }
            // 'normal' scroll to position in listview
            var scrollPos = if (lvMain.showOrder == SHOW_ORDER.TOP) 0 else Math.max(lvMain.arrayList.size - 1, 0)
            // if presenting a global search hit, place it somehow vertically centered
            if (highLightPos != -1) {
                fabBack!!.visibility = VISIBLE
                scrollPos = Math.max(0, highLightPos - 8)
            }
            // now scroll the ListView
            lvMain.scrollToItemPos(scrollPos)
            // temporary highlight item
            if (highLightPos != -1 && lvMain.arrayList.size > 0) {
                lvMain.arrayList[highLightPos].setHighLighted(true)
                // revoke temp. highlighting after timeout
                Handler().postDelayed({
                    lvMain.arrayList[highLightPos].setHighLighted(false)
                    lvMain.adapter!!.notifyDataSetChanged()
                }, 3000)
            }
        }

        //
        // verify attachment links, make attachments app local (if needed) and delete orphaned files
        //
        fun generateTidyOrphansProgress(
            context: Context,
            appAttachmentsPath: String,
            appName: String
        ) {
            // init vars
            var numOrphaned = 0
            var stringOrphans = ArrayList<String>()
            val filePath = File(appAttachmentsPath)
            val attachmentsList = filePath.listFiles()
            val fileUsed = arrayOfNulls<Boolean>(attachmentsList!!.size)
            Arrays.fill(fileUsed, false)
            // get list with app's uri permissions
            var grantedPermissionList = MainActivity.contextMainActivity.contentResolver.getPersistedUriPermissions()
            // build a uri list with links, which need a permission
            var neededPermissionList: MutableList<Uri> = arrayListOf()
            // generate a progress window
            var progressWindow = ProgressWindow(context, context.getString(R.string.searchOrphans) )
            var maxProgressCount = 0
            for (dsNdx in ds.dataSection.indices) {
                maxProgressCount += ds.dataSection[dsNdx].length
            }
            progressWindow.absCount = maxProgressCount.toFloat() / 30.0f // very, very coarse estimate
            // local progress window dialog dismiss listener is called, after blocking task finished
            fun setOnDismissListener(success: Boolean) {
                if (success) {
                    //
                    // remove granted image link permissions, if not needed anymore
                    //
                    // loop granted items
                    try {
                        for (itemGranted in grantedPermissionList) {
                            // have a found flag for granted permissions in the needed list
                            var grantedFound = false
                            // loop the needed permission list
                            for (itemNeeded in neededPermissionList) {
                                // if "granted permission" appears in "needed permission" --> all fine
                                if (itemGranted.uri == itemNeeded) {
                                    grantedFound = true
                                    break
                                }
                            }
                            // if "granted permission" appears NOT in "needed permission" --> revoke permission
                            if (!grantedFound) {
                                MainActivity.contextMainActivity.contentResolver.releasePersistableUriPermission(
                                    itemGranted.uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // happens, if grantedPermissionList and/or releasePersistableUriPermission is not supported
                        // --> leave it not handled
                    }
                    //
                    // ask to show or delete unused files in app folder
                    //
                    try {
                        // two choices: navigate to, show + cancel
                        twoChoicesDialog(context,
                            context.getString(R.string.CleanupFiles) + ": " + numOrphaned,
                            context.getString(R.string.show_or_delete),
                            context.getString(R.string.show),
                            context.getString(R.string.delete),
                            { // runner CANCEL
                            },
                            { // runner show orphans
                                showAppGallery(context, contextMainActivity as Activity, true, stringOrphans, false)
                            },
                            { // runner delete orphans
                                deleteOrphans(context, attachmentsList, fileUsed)
                            }
                        )
                    } catch(e: Exception) {
                        centeredToast(context, e.message, Toast.LENGTH_LONG)
                    }
                } else {
                    okBox(context, context.getString(R.string.Failure), context.getString(R.string.repeatSearch), { null })
                }
            }
            // search orphans async in another thread
            try {
                // show progress window
                progressWindow.show()
                // real work
                Thread {
                    progressWindow.let { it
                        // clear app cache before doing anything
                        deleteAppDataCache(MainActivity.contextMainActivity)
                        var oriText: String
                        var newText: String
                        // iterate all data section of DataStore
                        for (dsNdx in ds.dataSection.indices) {
                            // get one data section as string
                            oriText = ds.dataSection[dsNdx]
                            newText = ""
                            // loop the split text line
                            val textLines = oriText.split("\\n+".toRegex()).toTypedArray()
                            for (i in textLines.indices) {
                                // update progress via progressWindow ("it") in foreground
                                (context as Activity).runOnUiThread(Runnable {
                                    it.incCount++
                                })
                                // deal with one line containing a link pattern
                                var textLine = textLines[i]
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
                                                        // leave it unhandled
                                                    }
                                                    grantedPermissionList = MainActivity.contextMainActivity.contentResolver.getPersistedUriPermissions()
                                                }
                                            }
                                        } finally {
                                        }
                                    }
                                    // only deal with lines containing "::::/", which is a file, not a www link
                                    if (lnkFull.contains("::::/")) {
                                        // get link parts: key and uri
                                        val lnkStr = lnkFull.substring(1, lnkFull.length - 1)
                                        var keyStrOri = ""
                                        var uriStrOri = ""
                                        var uriLocOri = ""
                                        try {
                                            val lnkParts = lnkStr.split("::::".toRegex()).toTypedArray()
                                            if (lnkParts != null && lnkParts.size >= 2) {
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
                                        val localUriStr = copyAttachmentToApp(context, uriStrOri, null, false, appAttachmentsPath)
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
                                ds.dataSection[dsNdx] = newText
                            }
                        }
                        // write DataStore to app file
                        var appPath = File(appAttachmentsPath).parent
                        if (appPath != null) {
                            writeAppData(appStoragePath, ds, appName)
                            ds.clear()
                            ds = readAppData(appStoragePath)
                        }
                        // get number of orphaned files and fill list with orphans
                        for (j in fileUsed.indices) {
                            if (!fileUsed[j]!!) {
                                numOrphaned++
                                stringOrphans.add(attachmentsList[j].name)
                            }
                        }
                    }
                    // jump back to UI and tell success
                    (context as Activity).runOnUiThread(Runnable {
                        setOnDismissListener(true)
                        progressWindow.close()
                    })
                }.start()
            } catch (e: Exception) {
                // tell UI, a failure happened
                e.printStackTrace()
                setOnDismissListener(false)
                progressWindow.close()
            }
        }
        // delete unused files from app folder /Images
        fun deleteOrphans(context: Context, fileList: Array<File>, fileUsed: Array<Boolean?>) {
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
                getAppGalleryThumbsSilent(contextMainActivity, true)
            }
            // show deletion result
            okBox(
                context,
                context.getString(R.string.CleanupFiles),
                filesDeleted.toString() + " " + context.getString(R.string.unusedFilesDeleted)
            )
        }

        // if storagePath contains something else than GrzLog.ser --> delete it
        // - "GrzLog.txt" may exist after "Backup now" + "Restore from files", it gets outdated over time
        // - sometimes an empty .ser may exist
        private fun deleteAppStorageGarbageFiles() {
            val files = File(appStoragePath).listFiles()?.filter { it.isFile }
            files?.forEach { file ->
                if (file.name != "GrzLog.ser") {
                    file.delete()
                }
            }
        }

        // DataStore serialization read: return value is under no circumstances null
        private fun readAppData(storagePath: String): DataStore {
            var dataStore: DataStore? = null
            val appName = contextMainActivity.applicationInfo.loadLabel(contextMainActivity.packageManager).toString()
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
            //        b) any change to DataStore and de-serialization afterward will fail
            // Fix: restore GrzLog.ser from GrzLog.txt: 1) local GrzLog.txt  2) /Download GrzLog.txt
            //
            if (dataStore == null) {
                var restoreSuccess = false
                // note
                centeredToast(
                    contextMainActivity,
                    contextMainActivity.getString(R.string.tryingTxtAppData),
                    3000
                )
                // TRY #1: upgrade from app storage path *log*.txt etc --> GrzLog.ser
                dataStore = upgradeFromLegacy(storagePath, true)
                // try to get data from a legacy backup
                if (dataStore == null) {
                    // note
                    centeredToast(
                        contextMainActivity,
                        contextMainActivity.getString(R.string.tryingTxtBackupData),
                        3000
                    )
                    // TRY #2: upgrade from legacy backup in Downloads *log*.txt etc --> GrzLog.ser
                    val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dataStore = upgradeFromLegacy(downloadDir, false)
                    // finally give up ...
                    if (dataStore == null) {
                        // finally give up and create an empty DataStore
                        dataStore = DataStore()
                        dataStore.namesSection.add(contextMainActivity.getString(R.string.folder))
                        dataStore.dataSection.add("")
                        dataStore.tagSection = mutableListOf(-1, -1)
                        dataStore.timeSection.add(TIMESTAMP.HHMM)
                        dataStore.selectedSection = 0
                        okBox(
                            contextMainActivity,
                            contextMainActivity.getString(R.string.note),
                            contextMainActivity.getString(R.string.noAppDataFound)
                        )
                    } else {
                        restoreSuccess = true
                    }
                } else {
                    restoreSuccess = true
                    deleteAppStorageGarbageFiles()
                }
                // somehow success
                if (restoreSuccess && dataStore != null) {
                    // make simple text backup file in download folder, ONLY USAGE in readAppData(): if GrzLog.ser is corrupted OR not existing
                    val downloadDir = "" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    createTxtBackup(contextMainActivity, downloadDir, dataStore)
                    // did somehow work
                    okBox(
                        contextMainActivity,
                        contextMainActivity.getString(R.string.success),
                        contextMainActivity.getString(R.string.appDataWereRestored)
                    )
                }
                // save the DataStore to GrzLog.ser
                //      Keep in mind: legacy backups are no way encrypted.
                //                    So keep folders as clear texts.
                writeAppData(appStoragePath, dataStore, appName, false)
            } else {
                deleteAppStorageGarbageFiles()
            }

            // prepare check outdated PUBPRV
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
            var checkPUBPRV = sharedPref.getBoolean("checkPUBPRV", true)

            // decrypt DataStore.dataSection aka folder
            var keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
            for (i in dataStore.dataSection.indices) {
                // only decrypt protected folders
                if (dataStore.timeSection[i] == TIMESTAMP.AUTH) {
                    var warning = (i == dataStore.selectedSection)
                    // 1)  check for GCM
                    // 2a) if not empty, update dataStore.dataSection             --> done
                    // 2b) if empty, check for PUBPRV
                    // 3a) if not empty, update dataStore.dataSection with chunks --> done
                    // 3b) if empty --> no encryption at all                      --> done
                    var clearData = keyManager.decryptGCM(dataStore.dataSection[i], keyManager.decryptPwdPrv(appPwdPub), warning)
                    if (clearData.isNotEmpty()) {
                        dataStore.dataSection[i] = clearData
                    } else {
                        // only check outdated PUBPRV until 1st full encrypted write folders is done
                        if (checkPUBPRV) {
                            // although outdated, we need to check for PUBPRV:
                            //    this case catches the update from v49 PUBPRV  --> v50 GCM
                            //    encrypted chunks are delimited by a white space
                            var chunks: MutableList<String> = dataStore.dataSection[i].split(" ").toMutableList()
                            if (keyManager.isEncryptedWithPub(chunks[0])) {
                                // reset folder data
                                dataStore.dataSection[i] = ""
                                // decrypt chunks into folder
                                for (j in chunks.indices) {
                                    if (chunks[j].length > 0) {
                                        dataStore.dataSection[i] += keyManager.decryptPub(chunks[j])
                                    } else {
                                        Log.e("GrzLog readAppData", "chunk length 0")
                                    }
                                }
                            } else {
                                // data are not encrypted at all:
                                //    happens if 'set folder protection' was set
                                //    and encryption/decryption was not yet available.
                                //    Which is the case by switch from <= v45 ---> >= v46
                                continue
                            }
                        } else{
                            // data are not encrypted at all:
                            //    a) happens if 'set folder protection' was set
                            //       and encryption/decryption was not yet available.
                            //       Which is the case by switch from <= v45 ---> >= v46
                            //    b) always happens, if encrypt/decrypt is turned off from Settings
                            //       AND a manual backup was forced
                            continue
                        }
                    }
                }
            }

            return dataStore
        }

        // DataStore serialization write to disk
        fun writeAppData(storagePath: String, dataStore: DataStore, appName: String, doEncrypt: Boolean = true) {

            var sharedPref = PreferenceManager.getDefaultSharedPreferences(contextMainActivity)
            var checkPUBPRV = sharedPref.getBoolean("checkPUBPRV", true)

            // mostly standard --> true
            if (doEncrypt) {

                // protected folders have the option to encrypt/decrypt
                var encryptProtectedFolders = sharedPref.getBoolean("encryptProtectedFolders", false)

                // encrypt DataStore.dataSection aka folder
                if (encryptProtectedFolders) {
                    var keyManager = KeyManager(contextMainActivity, "GrzLogAlias", "GrzLog")
                    // loop all folders
                    for (i in dataStore.dataSection.indices) {
                        // only encrypt protected folders
                        if (dataStore.timeSection[i] == TIMESTAMP.AUTH) {
                            var tmp = keyManager.encryptGCM(dataStore.dataSection[i], keyManager.decryptPwdPrv(appPwdPub))
                            dataStore.dataSection[i] = tmp
                        }
                    }
                }
            }

            // write to disk
            try {
                val file = File(storagePath, "$appName.ser")
                val fos = FileOutputStream(file)
                val oos = ObjectOutputStream(fos)
                oos.writeObject(dataStore)
                oos.close()
                fos.close()
                // after the 1st full write operation with >= v1.1.50, we can be sure PUBPRV is now gone
                if (checkPUBPRV) {
                    if (appVersionMain >= 50) {
                        val spe = sharedPref.edit()
                        spe.putBoolean("checkPUBPRV", false)
                        spe.apply()
                    }
                }
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
                for (i in children!!.indices) {
                    val success = deleteDir(File(dir, children[i]!!))
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

        // copy a file named by uriString to GrzLog Images folder
        //     return just the filename, no need for more, because app storage path is fix & known
        fun copyAttachmentToApp(context: Context,
                                uriString: String,
                                uri: Uri?,
                                attachmentImageScale: Boolean,
                                appAttachmentPath: String): String {
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
            var outputPath = appAttachmentPath + fileName
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
            // make a copy to GrzLog gallery
            if (attachmentImageScale) {
                // images to rescale
                if (!resizeImageAndSave(inputPath, outputPath)) {
                    return uriString + "." + ERROR_EXT
                }
                return outputPath.substring(outputPath.lastIndexOf("/"))
            } else {
                // all attachments but images AND images not to rescale
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
                } catch (fnfe: FileNotFoundException) {
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
                success = false
            }
            return success
        }

        //
        // show app gallery
        //
        fun showAppGallery(
            context: Context,
            activity: Activity,
            showOrphans: Boolean = false,
            stringOrphans: ArrayList<String>? = null,
            sortByDate: Boolean) {
            // start app gallery activity, if scan process is finished - under normal conditions, it's done before someone comes here
            if (appGalleryScanning) {
                // show a progress window of gallery scanning, show gallery when scan finished
                centeredToast(context, context.getString(R.string.waitForFinish), Toast.LENGTH_SHORT)
                var pw = ProgressWindow(context, context.getString(R.string.waitForFinish))
                try {
                    pw.dialog?.setOnDismissListener {
                        if (folderMoreDialog != null) {
                            folderMoreDialog?.show()
                        }
                    }
                    pw.show()
                    pw.absCount = appScanTotal.toFloat()
                    pw.curCount = appScanCurrent
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
                            // finally show, what was supposed to be shown
                            val galleryIntent = Intent(context, GalleryActivity::class.java)
                            galleryIntent.putExtra("ReturnPayload", false)
                            galleryIntent.putExtra("ShowOrphans", showOrphans)
                            galleryIntent.putExtra("StringOrphans", stringOrphans)
                            galleryIntent.putExtra("SortByDate", sortByDate)
                            activity.startActivity(galleryIntent)
                        }
                    }.start()
                } catch (e: Exception) {}
            } else {
                val galleryIntent = Intent(context, GalleryActivity::class.java)
                galleryIntent.putExtra("ReturnPayload", false)
                galleryIntent.putExtra("ShowOrphans", showOrphans)
                galleryIntent.putExtra("StringOrphans", stringOrphans)
                galleryIntent.putExtra("SortByDate", sortByDate)
                activity.startActivity(galleryIntent)
            }
        }
    }

} // ------------------------------------------ MainActivity -----------------------------------------

//
// ListView wrapper class takes care about showOrder
//
class GrzListView {

    internal var arrayList = ArrayList<ListViewItem>()       // ListView array
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
    var touchEventPoint = Point(-1, -1)                      // point coordinates of the latest touch event: needed to detect scrolling AND get word in string
    var singleClickHandler = Handler(Looper.getMainLooper()) // needed to distinguish between item's single and double click
    var itemLastClickTime: Long = 0                          // needed to distinguish between item's single and double click

    // generate ArrayList from a DataStore raw text to later populate the listview
    internal fun makeArrayList(rawText: String, lvShowOrder: SHOW_ORDER): ArrayList<ListViewItem> {
        // retval
        val arrayList = ArrayList<ListViewItem>()
        // split rawText by \n and ignore empty lines and arrange data array according to show order
        var parts: Array<String> = rawText.split("\\n+".toRegex()).toTypedArray()
        if (lvShowOrder == SHOW_ORDER.BOTTOM) {
            parts = toggleTextShowOrder(parts, rawText).arr
        }
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
                    if (lnkParts != null && lnkParts.size >= 2) {
                        keyString = lnkParts[0]
                        uriString = lnkParts[1]
                        fullTitle = parts[i]
                    }
                } finally {
                }
                itemTitle = parts[i].replace(lnkString, keyString)
            }
            // distinguish header & item: regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
            if (PATTERN.DateDay.matcher(itemTitle).find()) {
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

    // class is used as return value for toggleTextShowOrder(...)
    class RevData(arr: Array<String>, str: String) {
        var arr: Array<String>
        var str: String
        init {
            this.arr = arr
            this.str = str
        }
    }
    // method returns a reversed SHOW_ORDER, it toggles from TOP to BOTTOM or from BOTTOM to TOP
    fun toggleTextShowOrder(arrToReverse: Array<String>? = null, strToReverse: String): RevData {
        // if input data array != null, then it contains lines split by "\n"
        var parts: Array<String>? = arrToReverse
        // if input data array == null, then build it now from input string
        if (parts == null) {
            parts = strToReverse.split("\\n+".toRegex()).toTypedArray()
        }
        // arrange data array according to show order in a temp list
        val tmpList: ArrayList<String> = ArrayList()
        // loop input
        var headerExists = false
        for (i in parts.indices) {
            val m = parts[i].let { PATTERN.DateDay.matcher(it) }
            if (((m != null) && m.find() && parts[i].startsWith(m.group())) || parts[i].startsWith(9.toChar())) {
                // as soon as a header appears, add it to the TOP of the temp list
                tmpList.add(0, parts[i])
                headerExists = true
            } else {
                if (headerExists) {
                    // supposed to be the usual case
                    tmpList.add(1, parts[i])
                } else {
                    // a sequence of items with no header is allowed to insert
                    tmpList.add(0, parts[i])
                }
            }
        }
        // transfer temp list to parts
        parts = tmpList.toTypedArray();
        // only build output string by concatenating parts, if input array == null
        var reversedStr = ""
        if (arrToReverse == null) {
            for (i in parts.indices) {
                var tmpStr = trimEndAll(parts[i])
                if (tmpStr.isNotEmpty()) {
                    reversedStr += tmpStr + "\n"
                }
            }
            reversedStr = reversedStr.removeSuffix("\n")
        }
        // return data both as reversed array and as reversed string
        return RevData(parts, reversedStr)
    }

    // refresh/rebuild search hit list
    fun restoreFolderSearchHitStatus(searchText: String?) {
        try {
            val hitList: MutableList<Int> = ArrayList()
            for (i in arrayList.indices) {
                if (arrayList[i].fullTitle!!.lowercase(Locale.getDefault()).contains(searchText!!, ignoreCase = true)) {
                    arrayList[i].setSearchHit(true)
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
            for (pos in arrayList.indices) {
                arrayList[pos].setSearchHit(false)
            }
            // refresh ListView
            adapter!!.notifyDataSetChanged()
        } catch(e: Exception) {}
    }

    // unselect all items' selection status
    fun unselectSelections() {
        // remove search hits status
        try {
            for (pos in arrayList.indices) {
                arrayList[pos].setSelected(false)
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
            for (i in 0 until arrayList.size) {
                if (arrayList[i].isSelected()) {
                    lastSelectedItemPos = i
                }
            }
            // if nothing is so far selected, use argument as start, otherwise the last selected item pos
            var startPos = -1
            if (lastSelectedItemPos != -1) {
                // if position is not already selected. take it as start
                if (!arrayList[position].isSelected()) {
                    startPos = Math.min(arrayList.size - 1, position)
                } else {
                    startPos = Math.min(arrayList.size - 1, lastSelectedItemPos + 1)
                }
            } else {
                if (!arrayList[position].isSelected()) {
                    startPos = Math.min(arrayList.size - 1, position)
                } else {
                    startPos = Math.min(arrayList.size - 1, position + 1)
                }
            }

            // select next 10 items after given position
            var endPos = Math.min(arrayList.size, startPos + 11)
            var i = startPos
            while (i < endPos) {
                arrayList[i].setSelected(true)
                if (arrayList[i].isSpacer) {
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
            var selectStatus = !arrayList[position].isSelected()

            var dayStart = 0
            var dayEnd = -1
            // climb ListView up until a valid date is found
            for (i in position downTo 0) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu OR header via 9.toChar()
                val curText = arrayList[i].title
                if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(9.toChar())) {
                    dayStart = i
                    break
                }
            }
            // climb ListView down until a valid date is found
            for (i in position until arrayList.size) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList[i].title
                if (i > position) {
                    dayEnd = i
                    // stop climbing at Spacer
                    if (arrayList[i].isSpacer) {
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
                dayEnd = arrayList.size - 1
            }
            // select items according to the given range
            for (i in dayStart..dayEnd) {
                arrayList[i].setSelected(selectStatus)
            }
        } catch(e: Exception) {
        }
    }

    // toggle selection of a complete week in ListView
    fun toggleSelectGivenWeek(position: Int) {
        try {
            // get select toggle status
            var selectStatus = !arrayList[position].isSelected()

            var dayChanges = 0
            var dayStart = 0
            var dayEnd = -1
            // climb ListView up until a Sunday is found
            for (i in position downTo 0) {
                // check '2020-03-03 Thu' pattern
                if ( arrayList[i].isSection ) {
                    // a regex pattern for "yyyy-mm-dd
                    val curText = arrayList[i].title
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
            for (i in position until arrayList.size) {
                // check '2020-03-03 Thu' pattern and extract name of day
                if ( arrayList[i].isSection ) {
                    var splitArr = arrayList[i].title?.split(" ")
                    if (splitArr?.size == 2) {
                        // a regex pattern for "yyyy-mm-dd
                        val curText = arrayList[i].title
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
                dayEnd = arrayList.size - 1
            }
            // select items according to the given range
            for (i in dayStart..dayEnd) {
                arrayList[i].setSelected(selectStatus)
            }
        } catch(e: Exception) {
        }
    }

    // toggle selection of a complete month in ListView
    fun toggleSelectGivenMonth(position: Int) {
        try {
            // get select toggle status
            var selectStatus = !arrayList[position].isSelected()

            var monthStartPos = 0
            var monthEndPos = -1
            var todayYearMonthStr = ""
            // climb ListView up until a date pattern is found, which is set to "today"
            for (i in position downTo 0) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList[i].title
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
                val curText = arrayList[i].title
                // only check headers for a valid date pattern
                if (arrayList[i].isSection && PATTERN.DateDay.matcher(curText.toString()).find()) {
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
            for (i in position until arrayList.size) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList[i].title
                // only check headers for a valid date pattern
                if (arrayList[i].isSection && PATTERN.DateDay.matcher(curText.toString()).find()) {
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
                monthEndPos = arrayList.size - 1
            }

            // select items according to the above calculated range
            for (i in monthStartPos..monthEndPos) {
                arrayList[i].setSelected(selectStatus)
            }

        } catch(e: Exception) {
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
                val curText = arrayList[i].title
                if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(9.toChar())) {
                    dayStart = i
                    break
                }
            }
            // climb ListView down until a valid date/header is found
            for (i in position until arrayList.size) {
                // a regex pattern for "yyyy-mm-dd EEE", sample 2020-03-03 Thu
                val curText = arrayList[i].title
                // stop climbing at Date or Header or Spacer
                if (PATTERN.DateDay.matcher(curText.toString()).find() || curText.toString().startsWith(9.toChar()) || arrayList[i].isSpacer) {
                    dayEnd = if (i > position) i - 1 else i
                    break
                }
            }
            if (dayEnd == -1) {
                dayEnd = arrayList.size - 1
            }
            // collect items according to the given range as String
            for (i in dayStart..dayEnd) {
                retVal += arrayList[i].fullTitle + if (i < dayEnd) "\n" else ""
            }
        } catch(e: Exception) {}
        retVal = retVal.trimEnd('\n')
        return retVal
    }

    // return a list of selected indexes including the enclosed spacers
    fun getSelectedIndexList() : MutableList<Int> {
        var listSelPos: MutableList<Int> = ArrayList()
        for (i in 0 until arrayList.size) {
            if (arrayList[i].isSelected()) {
                listSelPos.add(i)
            }
            if (arrayList[i].isSpacer) {
                var posBefore = Math.max(0, i - 1)
                var posAfter = Math.min(arrayList.size - 1, i + 1)
                if (arrayList[posBefore].isSelected() && arrayList[posAfter].isSelected()) {
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
            for (i in arrayList.indices) {
                // collect everything but spacer
                if (!arrayList[i].isSpacer) {
                    retVal += arrayList[i].fullTitle + "\n"
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
                for (i in arrayList.indices) {
                    if (arrayList[i].isSelected()) {
                        retVal += arrayList[i].fullTitle + "\n"
                    }
                }
            } catch(e: Exception) {}
            retVal = retVal.trimEnd('\n')
            return retVal
        }

    // return search hit items from ListView array as String --> copy/paste
    val folderSearchHits: String
        // variable is accessed by its getter
        get() {
            // collect search hit items
            var retVal = ""
            try {
                for (i in arrayList.indices) {
                    if (arrayList[i].isSearchHit()) {
                        retVal += arrayList[i].fullTitle + "\n"
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
    private var pathGrzLogImages = MainActivity.appStoragePath + "/Images/"

    constructor() : super() {}
    constructor(context: Context?, items: ArrayList<ListViewItem>?) {
        this.context = context
        this.items = items
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context!!)
        if (sharedPref.getString("chosenTheme", "Dark") == "Dark") {
            this.iconColor = R.color.lightskyblue
        } else {
            this.iconColor = R.color.royalblue
        }
        listUriPermissionsGranted = context.contentResolver.getPersistedUriPermissions()
    }

    var listUriPermissionsGranted: List<UriPermission>? = null

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
        // highlight a reminder
        if (MainActivity.grzlogReminderList.size > 0) {
            if (MainActivity.grzlogReminderList.firstOrNull {it == tv.text} != null) {
                tv.setTextColor(ContextCompat.getColor(context!!, R.color.red))
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
            // get attachment file name
            var fileNameString = items!![position].fullTitle!!.substring(
                items!![position].fullTitle!!.indexOf("::::"),
                items!![position].fullTitle!!.lastIndexOf("]")
            )
            // get mime type
            var mime = ""
            try {
                if (stop != -1) {
                    // insert a " " as icon placeholder
                    text = text?.substring(0, start!!) + " " + text?.substring(start!!)
                    spanStr = SpannableString(text)
                    // set icon via spannable
                    res = android.R.drawable.ic_dialog_alert
                    // title could contain a mime, so exec mime detection on the last part of fullTitle
                    mime = getFileExtension(fileNameString)
                }
            } catch (ioobe: IndexOutOfBoundsException ) {
                mime = ""
            }
            // very quick check for a linked image
            if (fileNameString[4] == 'c') {
                // next check for picker uri
                // API<35 might provide a shorter version "content://media" vs. "content://media/picker"
                val pos = fileNameString.indexOf("content://media")
                if (pos != -1) {
                    var uri: Uri
                    // media type either image or video
                    res = android.R.drawable.ic_dialog_alert
                    val lnkParts = fileNameString.split("::::".toRegex()).toTypedArray()
                    if (lnkParts.size == 3) {
                        // back to normal uri string
                        fileNameString = lnkParts[1]
                        uri = Uri.parse(fileNameString)
                    } else {
                        uri = Uri.parse(fileNameString.substring(pos))
                    }
                    // uri permission check
                    var uriPermitted = false
                    for (item in listUriPermissionsGranted!!) {
                        if (item.uri == uri) {
                            uriPermitted = true
                            break
                        }
                    }
                    //if (listUriPermissionsGranted?.any { it.uri == uri } == true) {
                    if (uriPermitted) {
                        if (lnkParts.size > 2) {
                            if (lnkParts[2].equals("image")) {
                                res = android.R.drawable.ic_menu_camera
                            }
                            if (lnkParts[2].equals("video")) {
                                res = R.drawable.ic_video
                            }
                        } else {
                            res = android.R.drawable.ic_menu_camera
                        }
                    } else {
                        // set a default: error --> uri not permitted or not found
                        res = android.R.drawable.ic_dialog_alert
                        // check for media id (just a number after the last '/'): special handling API < 35
                        val posSlash = fileNameString.lastIndexOf('/')
                        if (posSlash != -1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            val mediaId = fileNameString.substring(posSlash + 1)
                            mediaId.toIntOrNull()?.let {
                                if (it > 0) {
                                    res = android.R.drawable.ic_menu_camera
                                }
                            }
                        }
                    }
                }
            } else {
                if (mime.length > 0) {
                    if (IMAGE_EXT.contains(mime, ignoreCase = true)) {
                        if (File(pathGrzLogImages + fileNameString.substring(5)).exists()) {
                            res = android.R.drawable.ic_menu_camera
                        } else {
                            res = android.R.drawable.ic_dialog_alert
                        }
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
                                            // www attachment link, geo coordinates, GrzLog folder go here
                                            val fullItemText = items!![position].fullTitle
                                            val m = fullItemText?.let { PATTERN.UriLink.matcher(it) }
                                            if (m?.find() == true) { // www attachment link OR geo coordinates OR GrzLog folder
                                                val result = m.group()
                                                val key = result.substring(1, result.length - 1)
                                                val lnkParts = key.split("::::".toRegex()).toTypedArray()
                                                if (lnkParts != null && lnkParts.size >= 2) {
                                                    var fileName = lnkParts[1]
                                                    if (fileName.startsWith("/") == false) {
                                                        if (fileName.startsWith("geo:")) {
                                                            res = R.drawable.location
                                                        } else {
                                                            res = android.R.drawable.ic_menu_compass
                                                        }
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
                    // check for folder attachment link OR geo , if the folder name does not contain a .
                    if (start != -1 && stop != -1) {
                        val fullItemText = items!![position].fullTitle
                        val m = fullItemText?.let { PATTERN.UriLink.matcher(it) }
                        if (m?.find() == true) {
                            val result = m.group()
                            val key = result.substring(1, result.length - 1)
                            val lnkParts = key.split("::::".toRegex()).toTypedArray()
                            if (lnkParts != null && lnkParts.size >= 2) {
                                var fileName = lnkParts[1]
                                if (fileName.startsWith("folder/")) {
                                    res = android.R.drawable.ic_menu_agenda
                                }
                                if (fileName.startsWith("geo:")) {
                                    res = R.drawable.location
                                }
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
        // www links in item text: change color & underline !!reuse: text and spanStr as above, bc. of the potentially added " "
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
    var attachmentImageScale = false         // attachment is an image to scale down to screen dimensions
    var attachmentImageLink = false          // attachment is a link to an image from the phone gallery
    var attachmentVideoLink = false          // attachment is a link to a video from the phone gallery
    var attachmentUriUri: Uri? = null        // attachment as original Uri from onActivityResult
    var inputAlertTextSelStart = -1          // insert position for attachment link
    var pickAttachment = false               // flag indicates, fabPlus was called from onActivityResult
    var imageCapture = false                 // flag indicates, an image was captured
    var mainDialog: AlertDialog? = null      // main edit dlg after click on button +
    var mainDialogInitHeight = 100000        // main edit dlg initial height with one line text
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
        UNDEFINED, REVERTEDIT, REVERTDELETE, REVERTINSERT, REVERTADD, REVERTMULTIEDIT
    }

    // timestamp type and folder protection
    internal object TIMESTAMP {
        const val OFF = 0
        const val HHMM = 1
        const val HHMMSS = 2
        const val AUTH = 3
    }

    var namesSection: MutableList<String> = ArrayList()
    @JvmField
    var dataSection: MutableList<String> = ArrayList()
    var tagSection: MutableList<Int> = ArrayList()
    // note:
    // a) timeSection covers auto timestamp behaviour (off, auto hh:mm, auto hh:mm:ss) and folder protection
    // b) folder protection excludes timestamps
    // c) timestamps exclude folder protection
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
        const val SECTIONS_COUNT = 50
    }
}