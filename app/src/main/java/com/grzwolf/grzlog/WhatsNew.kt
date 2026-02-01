package com.grzwolf.grzlog

import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity


class WhatsNewActivity : AppCompatActivity() {

    lateinit var textView: EditText

    val text =
        "GrzLog: What's new?\n" +
        "===================\n\n" +
        "v1.1.56\n" +
        "~~~~~~~\n" +
        "fix: date parsing in a specific scenario\n" +
        "new: straight forward double tap detector for main input\n" +
        "fix: possible double date input via date picker\n\n" +
        "v1.1.55\n" +
		"~~~~~~~\n" +
        "convenience: double tap empty main input to open a date picker -> makes it easy to add a future date, see v1.1.53\n" +
        "this window :)\n\n" +
		"v1.1.54\n" +
		"~~~~~~~\n" +
        "unify image & video attachments for copies & links\n" +
        "fix: main input dialog height\n\n" +
		"v1.1.53\n" +
		"~~~~~~~\n" +
		"add future date items with red plus button --> requires date format 'YYY-MM-DD'\n\n" +
		"v1.1.52\n" +
		"~~~~~~~\n" +
        "'Line as GrzLog reminder' allows to add a new calendar event\n" +
        "show a note about folder protection w/o enabled encryption\n\n" +
		"v1.1.51\n" +
		"~~~~~~~\n" +
        "option to attach an image link to the phone's gallery -> no copy into app context saves storage space\n" +
		"protected folders need re authorisation after 5 min in background\n" +
        "option to share selected items as PDF\n\n" +
		"v1.1.50\n" +
		"~~~~~~~\n" +
        "option to attach an image link to the phone's gallery (no copy)\n" +
		"title of protected folder w/o encryption title appears in magenta\n" 

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