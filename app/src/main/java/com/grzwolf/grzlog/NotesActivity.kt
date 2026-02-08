package com.grzwolf.grzlog

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity


class NotesActivity : AppCompatActivity() {

    lateinit var textView: EditText

    val text =

    "GrzLog FAQ and notes\n" +
    "====================\n" +

    "\nNote:\nFloating keyboard (like Gboard pen input)\n" +
    "is not supported. 'Pen input' must be disabled.\n\n" +

    "Note:\nFolder protection w/o encryption is weak.\n" +
    "Protected folders could be encrypted in Settings.\n\n" +

    "Note:\nIf protection & encryption are selected, Backup files GrzLog.zip and GrzLog.txt contain encrypted data.\n" +
    "!! Disable folder encryption & execute a manual backup before restoring a Backup on another phone !!\n\n" +

    "How to get GrzLog and its data to a new phone?\n" +
    "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
    "On old phone\n" +
    "------------\n" +
    "1.) No need to change gallery/attachment location\n" +
    "    reason: any Backup contains the GrzLog gallery, no matter where they are is located\n" +
    "2.) Turn encryption off --> Settings --> Encrypt protected folders\n" +
    "    reason: new phone wil have another password set as the old phone \n" +
    "3.) Execute a manual backup --> Settings --> Backup Data now \n" +
    "4a.) copy GrzLog.zip from Download folder to a USB-Drive ...\n" +
    "4b.) ... OR Export Backup to Google Drive\n" +
    "On new phone\n" +
    "------------\n" +
    "5.) install GrzLog from https://github.com/grzwolf/GrzLog\n" +
    "6.) it doesn't matter, if encryption is enabled or not, both is fine: Settings --> Encrypt protected folders\n" +
    "7a.) copy GrzLog.zip from USB-Drive to Download folder and execute Restore ...\n" +
    "7b.) ... OR Import Backup from Google Drive\n" +
    "\n\n" +

    "What is the difference between GrzLog's private and public attachment folder?\n" +
    "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
    "... so far, it is not that much difference ...\n" +
    "private attachment folder (default on)\n" +
    "-------------------------\n" +
    "GrzLog data and its gallery are held in one place\n" +
    "pro: GrzLog data and its gallery are held in one place\n" +
    "     only GrzLog has access to its private folders\n" +
    "con: huge backup file, slow operation\n\n" +
    "public attachment folder\n" +
    "------------------------\n" +
    "pro: GrzLog gallery can be viewed with standard Android apps\n" +
    "     TBD: tiny backup file, fast operation\n" +
    "     TBD: backup frequency could be increased w/o performance issues\n" +
    "     TBD: live sync between phone & tablet could be established\n" +
    "con: TBD: a small backup does not contain attachments, but how likely is such a loss at all?\n" +
    "\n\n" +

    "How does private and public attachment folder setting affect Backup & Restore?\n" +
    "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
    "... almost like nothing ...\n" +
    "Settings --> Backup\n" +
    "-------------------\n" +
    "- includes all data and attachments stored in the private AND public folder\n" +
    "- means: any Backup looks like, as if it was made using the private folder, but also contains public attachments\n" +
    "\n" +
    "Settings --> Restore\n" +
    "--------------------\n" +
    "- restore data and all attachments are stored in the private folder\n" +
    "- clears/deletes the public attachments folder\n" +
    "- sets the Setting --> 'GrzLog gallery location' to the app's private folder\n" +
    "- afterwards user may switch back to public attachments folder at any time\n" +
    "\n\n" +

    "How folder protection and folder encryption work together?\n" +
    "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
    "folder protection (default off)\n" +
    "-----------------\n" +
    "- this is a folder property\n" +
    "- once enabled, access to such folder is given after authorization with the device unlock method\n" +
    "- could be set for folders individually\n" +
    "- such folders are not encrypted, unless ... see below\n" +
    "\n" +
    "folder encryption (default off)\n" +
    "-----------------\n" +
    "- folder encryption is an app wide setting --> Settings\n" +
    "- if no folder is protected, encryption won't do anything\n" +
    "- if a folder is protected w/o encryption, its title color is magenta, a warning may appear\n" +
    "- folders are symmetrically encrypted, which needs a strong password\n" +
    "- at the very first start of GrzLog, such a strong random 16 char password is generated\n" +
    "- this password will be kept as long as this installation exists\n" +
    "- that means, any installation of GrzLog has its own random password\n" +
    "- the password gets asymmetrically encrypted with the system keystore's public key\n" +
    "- the password is stored in the app's preferences, but no one could decipher it\n" +
    "- once a folder requests encryption/decryption, the stored password gets decrypted with the system keystore's private key\n" +
    "- Note: Folder encryption does not affect attachments!\n" +
    "  Why such effort?\n" +
    "  ----------------\n" +
    "  The main goal was to encrypt app data, w/o letting the user deal with passwords.\n" +
    "  Asymmetrical encryption/decryption is considered to be state of art and safe, private and public key\n" +
    "  come safely from the phones hardware keystore, w/o even knowing or to store them.\n" +
    "  Though larger text's performance is horribly slow.\n" +
    "  Symmetrical encryption/decryption is ten fold faster as asymmetrical encryption.\n" +
    "  But it needs a password, which the user has to provide each time (too boring ...).\n" +
    "  Or the password is stored somewhere on the phone, which is not really safe ...\n" +
    "  ... Unless the password itself is asymmetrically encrypted with the keystore's public key.\n" +
    "  To obtain the password, it gets decrypted with the keystore's private key.\n" +
    "  Asymmetrical encryption/decryption of just a 16 char password is not a performance issue at all.\n" +
    "  Huge advantage: The user doesn't need to provide a password and cannot forget it.\n" +
    "                  The password could only be obtained in a debug session of the specific GrzLog installation.\n" +
    "                  Even copying data and password to another phone do not fly, its keystore would provide a totally different key pair.\n" +
    "\n\n"

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.help_activity)
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // revert API36 insets for API < 35
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val view0 = findViewById(R.id.scrollView0) as HorizontalScrollView
            view0.margin(top = 0F)
            view0.margin(bottom = 0F)
            val view1 = findViewById(R.id.scrollView1) as ScrollView
            view1.margin(top = 0F)
            view1.margin(bottom = 0F)
        }

        // adjust margins
        setMargins(this.getResources().getConfiguration().orientation)

        // adding onBackPressed callback listener
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        textView = findViewById(R.id.helpView)
        textView.setText(text)
    }

    // have some margin to the left in landscape mode
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setMargins(newConfig.orientation)
    }

    // margin insets correction
    fun setMargins(orientation: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                val view0 = findViewById(R.id.scrollView0) as HorizontalScrollView
                view0.margin(top = 50F)
                val view = findViewById(R.id.helpView) as TextView
                view.margin(left = 70F)
            } else {
                if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                    val view0 = findViewById(R.id.scrollView0) as HorizontalScrollView
                    view0.margin(top = 100F)
                    val view = findViewById(R.id.helpView) as TextView
                    view.margin(left = 10F)
                }
            }
        }
    }

    // build a menu bar with options
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // back to MainActivity
        if (item.itemId == android.R.id.home) {
            onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    // Android back button & gesture detection
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }
}