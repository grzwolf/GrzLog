package com.grzwolf.grzlog

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File


class DownloadController(private val context: Context, private val url: String, private val fileName: String) {

    companion object {
        private const val FILE_NAME = "grzlog.apk"
        private const val FILE_BASE_PATH = "file://"
        private const val MIME_TYPE = "application/vnd.android.package-archive"
        private const val PROVIDER_PATH = ".provider"
        private const val APP_INSTALL_PATH = "\"application/vnd.android.package-archive\""
    }

    fun enqueueDownload() {
        // app Download folder: sdcard/Android/data/com.grzwolf.grzlog/files/Download
        var destination = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toString() + "/"
        destination += FILE_NAME
        val uri = Uri.parse("$FILE_BASE_PATH$destination")
        val file = File(destination)
        if (file.exists()) {
            file.delete()
        }

        // download manager
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.setMimeType(MIME_TYPE)
        request.setTitle(context.getString(R.string.app_name))
        request.setDescription("downloading")

        // set destination
        request.setDestinationUri(uri)

        // ask for installation
        showInstallOption(destination, uri)

        // enqueue a new download as referenced and toast the action
        val downloadId: Long = downloadManager.enqueue(request)
        Toast.makeText(context, "Downloading " + fileName, Toast.LENGTH_LONG)
            .show()

        // create & show progress window
        var progressWindow = ProgressWindow(context, "download progress" )
        progressWindow.dialog?.setCancelable(true)
        progressWindow.show()
        Thread {
            val q = DownloadManager.Query()
            q.setFilterById(downloadId)
            while (true) {
                val cursor: Cursor = downloadManager.query(q)
                cursor.moveToFirst()
                var ndxCur = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                var ndxTot = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                var ndxStat = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (ndxCur != -1 && ndxTot != -1 && ndxStat != -1) {
                    val bytesDownloaded = cursor.getInt(ndxCur) / 1000.0f
                    val bytesTotal = cursor.getInt(ndxTot) / 1000.0f
                    if (cursor.getInt(ndxStat) == DownloadManager.STATUS_SUCCESSFUL) {
                        break
                    }
                    if (cursor.getInt(ndxStat) == DownloadManager.STATUS_FAILED) {
                        break
                    }
                    (context as Activity).runOnUiThread(Runnable {
                        progressWindow.absCount = bytesTotal.toFloat()
                        progressWindow.curCount = bytesDownloaded.toInt()
                    })
                }
                cursor.close()
            }
            // jump back to UI
            (context as Activity).runOnUiThread(Runnable {
                progressWindow.close()
            })
        }.start()

    }

    private fun showInstallOption(
        destination: String,
        uri: Uri
    ) {
        // set BroadcastReceiver to install app when .apk is downloaded
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val contentUri = FileProvider.getUriForFile(
                            context,
                            BuildConfig.APPLICATION_ID + PROVIDER_PATH,
                            File(destination)
                        )
                        val install = Intent(Intent.ACTION_VIEW)
                        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        install.data = contentUri
                        context.startActivity(install)
                        context.unregisterReceiver(this)
                    } else {
                        val install = Intent(Intent.ACTION_VIEW)
                        install.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        install.setDataAndType(
                            uri,
                            APP_INSTALL_PATH
                        )
                        context.startActivity(install)
                        context.unregisterReceiver(this)
                    }
                } catch (e: Exception) {
                    val data = e.message.toString()
                    Log.e("Exception: ", data)
                    Toast.makeText(context, "download failed", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
}