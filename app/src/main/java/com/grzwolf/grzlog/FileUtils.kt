package com.grzwolf.grzlog

import android.annotation.SuppressLint
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.lang.NullPointerException
import java.lang.NumberFormatException
import java.util.*

//
// https://github.com/saparkhid/AndroidFileNamePicker/tree/main/javautil
//
class FileUtils(var context: Context) {
    companion object {
        var FALLBACK_COPY_FOLDER = "upload_part"
        private const val TAG = "FileUtils"
        private var contentUri: Uri? = null
        @JvmStatic
        fun getFile(context: Context, uri: Uri?): File? {
            if (uri != null) {
                var path: String? = null
                try {
                    path = getPath(context, uri).toString()
                } catch (e: Exception) {
                    path = null
                }
                if (path != null) {
                    return File(path)
                }
            }
            return null
        }

        @JvmStatic
        @SuppressLint("NewApi")
        fun getPath(context: Context, uri: Uri): String? {
            // check here to KITKAT or new version
            val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
            var selection: String? = null
            var selectionArgs: Array<String>? = null
            // DocumentProvider
            if (isKitKat) {
                // ExternalStorageProvider
                if (isExternalStorageDocument(uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":".toRegex()).toTypedArray()
                    var fullPath = getPathFromExtSD(split)
                    if (fullPath == null || !fileExists(fullPath)) {
                        fullPath = copyFileToInternalStorage(context, uri, FALLBACK_COPY_FOLDER)
                    }
                    return if (fullPath !== "") {
                        fullPath
                    } else {
                        null
                    }
                }


                // DownloadsProvider
                if (isDownloadsDocument(uri)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val id: String
                        var cursor: Cursor? = null
                        try {
                            cursor = context.contentResolver.query(
                                uri, arrayOf(
                                    MediaStore.MediaColumns.DISPLAY_NAME
                                ), null, null, null
                            )
                            if (cursor != null && cursor.moveToFirst()) {
                                val fileName = cursor.getString(0)
                                val path = Environment.getExternalStorageDirectory()
                                    .toString() + "/Download/" + fileName
                                if (!TextUtils.isEmpty(path)) {
                                    return path
                                }
                            }
                        } finally {
                            cursor?.close()
                        }
                        id = DocumentsContract.getDocumentId(uri)
                        if (!TextUtils.isEmpty(id)) {
                            if (id.startsWith("raw:")) {
                                return id.replaceFirst("raw:".toRegex(), "")
                            }
                            val contentUriPrefixesToTry = arrayOf(
                                "content://downloads/public_downloads",
                                "content://downloads/my_downloads"
                            )
                            for (contentUriPrefix in contentUriPrefixesToTry) {
                                return try {
                                    val contentUri = ContentUris.withAppendedId(
                                        Uri.parse(contentUriPrefix),
                                        java.lang.Long.valueOf(id)
                                    )
                                    getDataColumn(context, contentUri, null, null)
                                } catch (e: NumberFormatException) {
                                    //In Android 8 and Android P the id is not a number
                                    uri.path!!.replaceFirst("^/document/raw:".toRegex(), "")
                                        .replaceFirst("^raw:".toRegex(), "")
                                }
                            }
                        }
                    } else {
                        val id = DocumentsContract.getDocumentId(uri)
                        if (id.startsWith("raw:")) {
                            return id.replaceFirst("raw:".toRegex(), "")
                        }
                        try {
                            contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"),
                                java.lang.Long.valueOf(id)
                            )
                        } catch (e: NumberFormatException) {
                            e.printStackTrace()
                        }
                        if (contentUri != null) return getDataColumn(
                            context,
                            contentUri,
                            null,
                            null
                        )
                    }
                }


                // MediaProvider
                if (isMediaDocument(uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":".toRegex()).toTypedArray()
                    val type = split[0]
                    var contentUri: Uri? = null
                    if ("image" == type) {
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else if ("video" == type) {
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else if ("audio" == type) {
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    } else if ("document" == type) {
                        contentUri = MediaStore.Files.getContentUri(MediaStore.getVolumeName(uri))
                    }
                    selection = "_id=?"
                    selectionArgs = arrayOf(
                        split[1]
                    )
                    return getDataColumn(context, contentUri, selection, selectionArgs)
                }
                if (isGoogleDriveUri(uri)) {
                    return getDriveFilePath(context, uri)
                }
                if (isWhatsAppFile(uri)) {
                    return getFilePathForWhatsApp(context, uri)
                }
                if ("content".equals(uri.scheme, ignoreCase = true)) {
                    if (isGooglePhotosUri(uri)) {
                        return uri.lastPathSegment
                    }
                    if (isGoogleDriveUri(uri)) {
                        return getDriveFilePath(context, uri)
                    }
                    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // return the filename provided by copyFileToInternalStorage
                        copyFileToInternalStorage(context, uri, FALLBACK_COPY_FOLDER)
                    } else {
                        getDataColumn(context, uri, null, null)
                    }
                }
                if ("file".equals(uri.scheme, ignoreCase = true)) {
                    return uri.path
                }
            } else {
                if (isWhatsAppFile(uri)) {
                    return getFilePathForWhatsApp(context, uri)
                }
                if ("content".equals(uri.scheme, ignoreCase = true)) {
                    val projection = arrayOf(
                        MediaStore.Images.Media.DATA
                    )
                    var cursor: Cursor?
                    try {
                        cursor = context.contentResolver
                            .query(uri, projection, selection, selectionArgs, null)
                        val column_index =
                            cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        if (cursor.moveToFirst()) {
                            return cursor.getString(column_index)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return copyFileToInternalStorage(context, uri, FALLBACK_COPY_FOLDER)
        }

        private fun fileExists(filePath: String): Boolean {
            val file = File(filePath)
            return file.exists()
        }

        private fun getPathFromExtSD(pathData: Array<String>): String? {
            val type = pathData[0]
            val relativePath = File.separator + pathData[1]
            var fullPath : String?
            // so no "primary" type, but let the check here for other devices
            if ("primary".equals(type, ignoreCase = true)) {
                fullPath = Environment.getExternalStorageDirectory().toString() + relativePath
                if (fileExists(fullPath)) {
                    return fullPath
                }
            }
            if ("home".equals(type, ignoreCase = true)) {
                fullPath = "/storage/emulated/0/Documents$relativePath"
                if (fileExists(fullPath)) {
                    return fullPath
                }
            }

            // Environment.isExternalStorageRemovable() is `true` for external and internal storage
            // so we cannot relay on it.
            //
            // instead, for each possible path, check if file exists
            // we'll start with secondary storage as this could be our (physically) removable sd card
            fullPath = System.getenv("SECONDARY_STORAGE")?.plus(relativePath)
            if (fullPath?.let { fileExists(it) } == true) {
                return fullPath
            }
            fullPath = System.getenv("EXTERNAL_STORAGE")?.plus(relativePath)
            return if (fullPath?.let { fileExists(it) } == true) {
                fullPath
            } else null
        }

        private fun getDriveFilePath(context: Context, uri: Uri): String {
            val returnCursor = context.contentResolver.query(uri, null, null, null, null)
            /*
             * Get the column indexes of the data in the Cursor,
             *     * move to the first row in the Cursor, get the data,
             *     * and display it.
             */
            val nameIndex = returnCursor!!.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            returnCursor.moveToFirst()
            val name = returnCursor.getString(nameIndex)
            val file = File(context.cacheDir, name)
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val outputStream = FileOutputStream(file)
                var read: Int
                val maxBufferSize = 1 * 1024 * 1024
                val bytesAvailable = inputStream!!.available()

                //int bufferSize = 1024;
                val bufferSize = Math.min(bytesAvailable, maxBufferSize)
                val buffers = ByteArray(bufferSize)
                while (inputStream.read(buffers).also { read = it } != -1) {
                    outputStream.write(buffers, 0, read)
                }
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {
            }
            return file.path
        }

        /***
         * Used for Android Q+
         * @param uri
         * @param newDirName if you want to create a directory, you can set this variable
         * @return
         */
        fun copyFileToInternalStorage(context: Context, uri: Uri, newDirName: String): String {
            val returnCursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            /*
            * Get the column indexes of the data in the Cursor,
            *     * move to the first row in the Cursor, get the data,
            *     * and display it.
            * */
            try {
                val nameIndex = returnCursor!!.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                returnCursor.moveToFirst()
                val name = returnCursor.getString(nameIndex)
                val output: File
                output = if (newDirName != "") {
                    val random_collision_avoidance = UUID.randomUUID().toString()
                    val dir =
                        File(context.filesDir.toString() + File.separator + newDirName + File.separator + random_collision_avoidance)
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    File(context.filesDir.toString() + File.separator + newDirName + File.separator + random_collision_avoidance + File.separator + name)
                } else {
                    File(context.filesDir.toString() + File.separator + name)
                }
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val outputStream = FileOutputStream(output)
                    var read: Int
                    val bufferSize = 1024
                    val buffers = ByteArray(bufferSize)
                    while (inputStream!!.read(buffers).also { read = it } != -1) {
                        outputStream.write(buffers, 0, read)
                    }
                    inputStream.close()
                    outputStream.close()
                } catch (e: Exception) {
                }
                return output.path
            } catch(e:NullPointerException) {
                return ""
            }
        }

        private fun getFilePathForWhatsApp(context: Context, uri: Uri): String {
            return copyFileToInternalStorage(context, uri, "whatsapp")
        }

        private fun getDataColumn(
            context: Context,
            uri: Uri?,
            selection: String?,
            selectionArgs: Array<String>?
        ): String? {
            var cursor: Cursor? = null
            val column = "_data"
            val projection = arrayOf(
                column
            )
            try {
                cursor = context.contentResolver.query(
                    uri!!, projection,
                    selection, selectionArgs, null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(column)
                    return cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
            return null
        }

        private fun isExternalStorageDocument(uri: Uri): Boolean {
            return "com.android.externalstorage.documents" == uri.authority
        }

        private fun isDownloadsDocument(uri: Uri): Boolean {
            return "com.android.providers.downloads.documents" == uri.authority
        }

        private fun isMediaDocument(uri: Uri): Boolean {
            return "com.android.providers.media.documents" == uri.authority
        }

        private fun isGooglePhotosUri(uri: Uri): Boolean {
            return "com.google.android.apps.photos.content" == uri.authority
        }

        fun isWhatsAppFile(uri: Uri): Boolean {
            return "com.whatsapp.provider.media" == uri.authority
        }

        private fun isGoogleDriveUri(uri: Uri): Boolean {
            return "com.google.android.apps.docs.storage" == uri.authority || "com.google.android.apps.docs.storage.legacy" == uri.authority
        }
    }
}