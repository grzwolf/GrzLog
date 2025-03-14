package com.grzwolf.grzlog

import android.os.Bundle
import android.view.*
import android.widget.EditText
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
        "         multi line is ok\n" +
        "         weblinks are highlighted\n" +
        "   Attachment: 'Select attachment' dialog\n" +
        "         image from phone gallery\n" +
        "         files from GrzLog gallery\n" +
        "         capture camera image\n" +
        "         video from phone gallery\n" +
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
        "a) tap long item -> 'What to do ...' dialog\n" +
        "       Edit line\n" +
        "       Copy line\n" +
        "       Insert line above\n" +
        "       Insert line below\n" +
        "       Select items dialog\n" +
        "       Line as lockscreen reminder\n" +
        "       Toggle line as header\n" +
        "       Cut to clipboard\n" +
        "       Delete line\n" +
        "   Note: revert latest change tap Menu bar 'Undo circle'\n\n" +
        "b) tap item somewhere    -> show item content\n\n" +
        "c) double tap on left    -> show item content\n\n" +
        "d) double tap in middle  -> edit item\n\n" +
        "e) tap selection on left -> toggle item selection\n\n" +
        "f) tap selection middle  -> copy selection to clipboard\n\n" +
        "g) tap long selection    -> 'What to do ...' dialog\n" +
        "       Toggle item selection\n" +
        "       Select next 10 items\n" +
        "       Select items of day\n" +
        "       Select items of month\n" +
        "       Unselect all items\n" +
        "       Select all items\n" +
        "       Invert items selection\n" +
        "       Copy selection\n" +
        "       Cut selection\n" +
        "       Delete selection\n" +
        "   !revert latest change via Menu bar Undo!\n" +
        "\n" +
        "2.3 Menu bar\n" +
        "~~~~~~~~~~~~\n" +
        "a) tap Hamburger\n" +
        "       more icons will appear: Loupe, Folder, Share, Wrench\n" +
        "       more icons disappear after 10s\n\n" +
        "b) tap Gear -> Settings window\n" +
        "       About\n" +
        "       Developer contact data\n" +
        "       Help pages\n" +
        "       Check GrzLog update at start\n" +
        "       Check GrzLog update\n" +
        "       Execute GrzLog update\n" +
        "       Input placement: new at top (like email) or at bottom (like messengers)\n" +
        "       Tap widget: show GrzLog or jump to input\n" +
        "       Preview image auto close\n" +
        "       Dark mode on or off\n" +
        "       Use GCam (if installed) instead of default one\n" +
        "       Ask whether to autofill skipped dates\n" +
        "       Click on selection: copy to clipboard OR show attachment\n" +
        "       Click on search hit: edit line OR show attachment\n" +
        "       Backup data info\n" +
        "       Backup mode\n" +
        "              fully automated in background\n" +
        "              run backup manually\n" +
        "       Backup outdated reminder\n" +
        "       Backup data in background OR foreground\n" +
        "       Backup data to /Download\n" +
        "       Restore data from /Download\n" +
        "       Restore from file explorer: Android 13 limits file access\n" +
        "       Export Backup to Google Drive (needs log in to Google account)\n" +
        "       Import Backup from Google Drive (needs log in to Google account)\n" +
        "       Check Google Drive (needs log in to Google account)\n" +
        "       Show / edit GrzLog gallery\n" +
        "              find GrzLog gallery usages\n" +
        "              delete from GrzLog gallery\n" +
        "       Tidy orphaned files from GrzLog gallery\n" +
        "              show orphaned files\n" +
        "              delete orphaned files\n" +
        "       Reset app preferences (data are not touched)\n\n" +
        "c) tap Loupe -> dialog with three options\n" +
        "       search in current folder\n" +
        "              type search phrase + Enter" +
        "              current folder will be searched\n" +
        "              search hits are marked\n" +
        "              use up/down symbols to jump\n" +
        "              end search by tap on Loupe + 2x X\n" +
        "       search & replace in current folder\n" +
        "              type search phrase\n" +
        "              type replace phrase\n" +
        "              make selection & replace\n" +
        "              Note: search & replace is case sensitive\n" +
        "       search in all GrzLog folders\n" +
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
        "              Auto timestamp at input: HH:MM, HH:MM:SS, none  \n" +
        "              Search all folders\n" +
        "                   type a global search phrase\n" +
        "                   a list of search hits will appear\n" +
        "                   select & jump to hit\n" +
        "                   jump back to search hit list via red back button\n\n" +
        "              New folder\n" +
        "e) tap Share -> share / copy data dialog\n" +
        "       selected items\n" +
        "       current folder\n" +
        "       folder as PDF\n" +
        "       folder as RTF\n\n\n" +
        "3 Miscellaneous\n" +
        "~~~~~~~~~~~~~~~\n" +
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

    // Android back button detection
    override fun onBackPressed() {
        super.onBackPressed()
    }
}