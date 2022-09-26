package com.grzwolf.grzlog

import android.os.Bundle
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity


class HelpActivity : AppCompatActivity() {

    lateinit var textView: EditText

    val text =
        "Help\n\n" +
        "1. UI Elements\n" +
        "==============\n" +
        "1.1 ListView with items\n" +
        "1.2 Red plus button\n" +
        "1.3 Menubar with Hamburger icon\n" +
        "\n" +
        "2. UI Actions\n" +
        "=============\n" +
        "2.1 ListView item\n" +
        "~~~~~~~~~~~~~~~~~\n" +
        "a) long press item -> current item input options dialog\n" +
        "    edit item\n" +
        "    copy item\n" +
        "    insert new item before current item\n" +
        "    insert new item after currentitem\n" +
        "    select items dialog\n" +
        "    use item as lockscreen reminder\n" +
        "    delete item\n" +
        "    ! all changes can be reverted by MenuBar Undo !\n" +
        "b) click item center screen -> show 'foo [attachment] bar' \n" +
        "c) click item left bound    -> select current item\n" +
        "d) long press selection     -> select options dialog\n" +
        "    unselect all items\n" +
        "    select all items\n" +
        "    invert items selection\n" +
        "    select items of the day\n" +
        "    copy selection\n" +
        "    cut selection\n" +
        "    delete selection\n" +
        "\n" +
        "2.2 Red plus button\n" +
        "~~~~~~~~~~~~~~~~~~~\n" +
        "a) click red plus button -> add new item dialog\n" +
        "    insert text\n" +
        "    add attachment -> attachment options dialog\n" +
        "        image from phone gallery\n" +
        "        all types file from GrzLog gallery\n" +
        "        capture camera image\n" +
        "        video from phone gallery\n" +
        "        audio from phone\n" +
        "        PDF file\n" +
        "        TXT file\n" +
        "b) long press red + button -> ListView jump\n" +
        "    jump to top\n" +
        "    jump to bottom\n" +
        "\n" +
        "2.3 Menubar\n" +
        "~~~~~~~~~~~\n" +
        "a) click MenuBar Hamburger\n" +
        "        more MenuBar icons appear\n" +
        "b) click again Hamburger -> settings window\n" +
        "        about\n" +
        "        developer contact data\n" +
        "        this help\n" +
        "        input placement: new at top (like email) or at bottom (like messengers)\n" +
        "        tap widget: show GrzLog or jump to input\n" +
        "        preview image auto close\n" +
        "        dark mode on or off\n" +
        "        use GCam (if installed) instead of default one\n" +
        "        backup data to /Download\n" +
        "        restore data from /Download\n" +
        "        import backup from explorer: Android 13 limits file access\n" +
        "        show GrzLog file gallery\n" +
        "        tidy orphaned files from GrzLog gallery\n" +
        "        reset app preferences (data are not touched)\n" +
        "c) click Share -> share options dialog\n" +
        "        share current item\n" +
        "        share surrounding day\n" +
        "        share current folder\n" +
        "        share current folder as PDF\n" +
        "        share current folder as RTF\n" +
        "d) click Folder -> folder options dialog\n" +
        "        list of folder \n" +
        "        more: Export to PDF, TXT, clipboard  \n" +
        "        more: new folder\n" +
        "        more: rename folder\n" +
        "        more: clear folder content\n" +
        "        more: remove/delete folder\n" +
        "        more: move folder up in list\n" +
        "        more: set input timestamp HH:MM, HH:MM:SS, none  \n" +
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