package com.grzwolf.grzlog

import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity


class HelpActivity : AppCompatActivity() {

    lateinit var textView: EditText

    val text =
        "1. User Interface Elements\n" +
        "==========================\n" +
        "1.1 Red + button\n" +
        "1.2 ListView with items of current folder\n" +
        "1.3 Menu bar with Hamburger icon\n" +
        "\n" +
        "2. User Interface Actions\n" +
        "=========================\n" +
        "2.1 Red plus button\n" +
        "~~~~~~~~~~~~~~~~~~~\n" +
        "a) tap red + button -> 'Write to GrzLog'\n" +
        "   Edit: type or paste text\n" +
        "         multi line input is ok\n" +
        "         topmost item is allowed to be a valid date in near future\n" +
        "         weblinks are highlighted\n" +
        "         double tap empty input opens a date picker dialog\n" +
        "   Attachment: 'Select attachment' dialog\n" +
        "         link (image, video) to phone gallery (max. allowed is 512)\n" +
        "         copy (image, video) from phone gallery\n" +
        "         link (image, video, file) to GrzLog gallery\n" +
        "         capture camera image\n" +
        "         audio from phone\n" +
        "         PDF file\n" +
        "         TXT file\n" +
        "         WWW link\n" +
        "         GrzLog folder\n" +
        "         My Location coordinates\n" +
        "         Edit existing attachment\n" +
        "   !revert latest change via Menu bar Undo!\n\n" +
        "b) tap long red + button -> 'Jump to ...'\n" +
        "        jump to top\n" +
        "        jump to bottom\n" +
        "\n" +
        "2.2 ListView item\n" +
        "~~~~~~~~~~~~~~~~~\n" +
        "a) tap long item -> 'What to do with the current line' dialog\n" +
        "       Edit line\n" +
        "       Insert line above\n" +
        "       Insert line below\n" +
        "       Select items dialog\n" +
        "       Line as GrzLog reminder (could be added to a Calendar too)\n" +
        "       Toggle line as header\n" +
        "       Copy line\n" +
        "       Cut to clipboard\n" +
        "       Delete line\n" +
        "   Note: revert latest change tap Menu bar 'Undo circle'\n\n" +
        "b) tap item somewhere    -> show item content\n\n" +
        "c) double tap on left    -> show item content\n\n" +
        "d) double tap in middle  -> edit item\n\n" +
        "e) tap selection on left -> toggle item selection\n\n" +
        "f) tap selection middle  -> copy selection to clipboard\n\n" +
        "g) tap long selection    -> 'What to do with selection?' dialog\n" +
        "       Toggle active item\n" +
        "       Select next 10 items\n" +
        "       Select items of day\n" +
        "       Select items of month\n" +
        "       Unselect all\n" +
        "       Select all\n" +
        "       Invert selection\n" +
        "       Copy to clipboard\n" +
        "       Search & replace\n" +
        "       Cut to clipboard\n" +
        "       Delete selection from list\n" +
        "   !revert latest change via Menu bar Undo!\n" +
        "\n" +
        "2.3 Menu bar\n" +
        "~~~~~~~~~~~~\n" +
        "a) tap Hamburger\n" +
        "       more icons will appear: Loupe, Folder, Share, Wrench\n" +
        "       more icons disappear after 10s\n\n" +
        "b) tap Gear -> Settings Window opens\n" +
        "   About GrzLog\n" +
        "       About\n" +
        "       Developer contact data\n" +
        "       Help pages\n" +
        "       GrzLog limitations\n" +
        "       Check GrzLog update at start\n" +
        "       Check GrzLog update\n" +
        "       Execute GrzLog update\n" +
        "   Extra\n" +
        "       Input placement: new at top (like email) or at bottom (like messengers)\n" +
        "       Tap widget: show GrzLog or jump to input\n" +
        "       Preview image auto close\n" +
        "       Dark mode on or off\n" +
        "       Use GCam (if installed) instead of default one\n" +
        "       Ask whether to autofill skipped dates\n" +
        "       Click on selection: copy to clipboard OR show attachment\n" +
        "       Click on search hit: edit line OR show attachment\n" +
        "       Encrypt protected folders\n" +
        "              protected & encrypted folder title is yellow\n" +
        "              protected & not encrypted folder title is magenta with a warning triangle\n" +
        "              not protected & not encrypted folder title is white\n" +
        "   Backup and Restore\n" +
        "       Backup File Information\n" +
        "              Note: folders in Backup Files are encrypted, if encryption is selected\n" +
        "       Backup mode\n" +
        "              fully automated in background\n" +
        "              run backup manually\n" +
        "       Backup outdated reminder (gray if automated)\n" +
        "       Backup execution in background OR foreground (gray if automated)\n" +
        "              Note: folders in Backup Files are encrypted, if encryption is selected\n" +
        "       Backup Data now --> to /Download (gray if automated)\n" +
        "       Backup compression\n" +
        "       Restore Data <-- from /Download\n" +
        "       Import backup from a file list\n" +
        "       Export Backup to Google Drive (needs log in to Google account)\n" +
        "       Import Backup from Google Drive (needs log in to Google account)\n" +
        "   GrzLog Gallery\n" +
        "       Show GrzLog gallery - by date\n" +
        "              find GrzLog gallery usages\n" +
        "              delete from GrzLog gallery\n" +
        "       Show GrzLog gallery - by size\n" +
        "              find GrzLog gallery usages\n" +
        "              delete from GrzLog gallery\n" +
        "       Tidy orphaned files from GrzLog gallery\n" +
        "              show orphaned files\n" +
        "              delete orphaned files\n" +
        "       Scale images to phone display\n" +
        "              pro: reduces storage consumption\n" +
        "       Show linked media (image, video)\n" +
        "              view, usages, upload, delete linked media\n" +
        "   Reset\n" +
        "       Reset app preferences (data are not touched)\n\n" +
        "   Quit active services\n" +
        "       If a backup or a GDrive upload seems to hang, stop them from here\n\n" +
        "c) tap Loupe -> dialog with three options\n" +
        "       search in current folder\n" +
        "              type search phrase + Enter" +
        "              current folder will be searched\n" +
        "              search hits are marked\n" +
        "              use up/down symbols to jump\n" +
        "              long tap a search hit grants further options\n" +
        "              end search by tap on Loupe + 2x X\n" +
        "       search & replace in current folder\n" +
        "              type search phrase\n" +
        "              type replace phrase\n" +
        "              make selection & replace\n" +
        "              Note: search & replace is case sensitive\n" +
        "       search in all GrzLog folders (protected folders are excluded)\n" +
        "             type a global search phrase\n" +
        "             a list of search hits will appear\n" +
        "             select & jump to hit\n" +
        "             jump back to search hit list via red back button\n\n" +
        "d) tap Folder -> 'Select folder'\n" +
        "       List of folders appears\n" +
        "       Button NEXT --> What to do with folder\n" +
        "              Open\n" +
        "              Export to PDF, TXT, clipboard  \n" +
        "              Rename\n" +
        "              Clear content\n" +
        "              Remove\n" +
        "              Move to top\n" +
        "              Move to bottom\n" +
        "              Property: no property, auto timestamp at input HH:MM, auto timestamp at input HH:MM:SS, folder protection requires system authorization\n" +
        "              Search all folders (protected folders are excluded)\n" +
        "                   type a global search phrase\n" +
        "                   a list of search hits will appear\n" +
        "                   select & jump to hit\n" +
        "                   jump back to search hit list via red back button\n\n" +
        "              New folder\n" +
        "e) tap Share -> share / copy data dialog\n" +
        "       selected items\n" +
        "       selected items as PDF\n" +
        "       current folder\n" +
        "       folder as PDF\n" +
        "       folder as RTF\n\n\n" +
        "3 Miscellaneous\n" +
        "~~~~~~~~~~~~~~~\n" +
        "Folder protection in GrzLog w/o encryption is very weak!\n" +
        "Protected folders can be encrypted in Settings.\n\n" +
        "Updates could be manually downloaded from\n" +
        "   https://github.com/grzwolf/GrzLog/tags\n\n" +
        "GrzLog uses the following permissions\n" +
        "   Camera - needed for attachments: photos, videos\n" +
        "   Install packages - needed for app update\n" +
        "   Internet - needed for app update\n" +
        "   Location - needed for attachment: location\n" +
        "   Media - needed for attachments: photos, videos, audio, docs etc.\n" +
        "   Notifications - needed for attachment: update available\n" +
        "   Notifications - needed for attachment: reminder\n" +
        "\n"

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

        // adding onBackPressed callback listener
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        textView = findViewById(R.id.helpView)
        textView.setText(text)
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