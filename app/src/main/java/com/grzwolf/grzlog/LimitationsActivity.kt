package com.grzwolf.grzlog

import android.os.Bundle
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity


class LimitationsActivity : AppCompatActivity() {

    lateinit var textView: EditText

    val text =
        "GrzLog limitations\n" +
        "==================\n" +
        "\nFloating keyboard (like Gboard pen input)\n" +
        "is not supported. 'Pen input' must be disabled.\n\n" +
        "Folder protection w/o encryption is weak.\n" +
        "Protected folders could be encrypted in Settings.\n" +
        "Note: Encryption of large folders will cause slower app performance.\n\n" +
        "Note: Backup Files GrzLog.zip and GrzLog.txt contain encrypted folders, if protection & encryption are selected\n" +
        "!! Disable folder encryption, before restoring a Backup on another phone !!\n"

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