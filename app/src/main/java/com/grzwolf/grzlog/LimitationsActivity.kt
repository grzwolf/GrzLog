package com.grzwolf.grzlog

import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity


class LimitationsActivity : AppCompatActivity() {

    lateinit var textView: EditText

    val text =
        "GrzLog limitations\n" +
        "==================\n" +
        "\nFloating keyboard (like Gboard pen input)\n" +
        "is not supported. 'Pen input' must be disabled.\n\n" +
        "Folder protection w/o encryption is weak.\n" +
        "Protected folders could be encrypted in Settings.\n\n" +
        "Note: Backup Files GrzLog.zip and GrzLog.txt contain encrypted folders, if protection & encryption are selected\n" +
        "!! Disable folder encryption & execute a manual backup before restoring a Backup on another phone !!\n"

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