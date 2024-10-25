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
        "\nFloation keyboard (like Gboard pen input)\n" +
        "is not supported. 'Pen input' must be disabled.\n"

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