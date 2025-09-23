package com.grzwolf.grzlog

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.security.auth.x500.X500Principal

//
// App data shall be stored in a simple text file.
// To encrypt/decrypt simple Strings, we need public and private keys.
//
class KeyManager(private val context: Context, keyStoreAlias: String, private val appName: String)
{
    private val alias: String = keyStoreAlias
    private val keyStoreName = "AndroidKeyStore"
    private val keyStore = KeyStore.getInstance(keyStoreName)
    private val tag = "$appName KeyManager"

    init {
        keyStore.load(null)
        createAppKeys()
    }

    private fun createAppKeys() {
        try {
            // only create a new key pair if needed
            if (!keyStore.containsAlias(alias)) {
                // the key pair is valid for 100 years :)
                val start: Calendar = Calendar.getInstance()
                val end: Calendar = Calendar.getInstance()
                end.add(Calendar.YEAR, 100)
                val spec = KeyPairGeneratorSpec.Builder(context)
                    .setAlias(alias)
                    .setSubject(X500Principal("CN=$appName, O=$appName Authority"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .build()
                val generator = KeyPairGenerator.getInstance("RSA", keyStoreName)
                generator.initialize(spec)
                generator.generateKeyPair()
            }
        } catch (e: java.lang.Exception) {
            Log.e(tag, Log.getStackTraceString(e))
        }
    }

    fun encryptString(initialText: String): String {
        try {
            val privateKeyEntry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val publicKey = privateKeyEntry.certificate.publicKey as RSAPublicKey

            val input = Cipher.getInstance("RSA/ECB/PKCS1Padding", "AndroidOpenSSL")
            input.init(Cipher.ENCRYPT_MODE, publicKey)

            val outputStream = ByteArrayOutputStream()
            val cipherOutputStream = CipherOutputStream(
                outputStream, input
            )
            cipherOutputStream.write(initialText.toByteArray(charset("UTF-8")))
            cipherOutputStream.close()

            val vals = outputStream.toByteArray()
            val crypted = Base64.encodeToString(vals, Base64.DEFAULT)
            return crypted

        } catch (e: java.lang.Exception) {
            Log.e(tag, Log.getStackTraceString(e))
            return "encrypt failed"
        }
    }

    fun decryptString(cryptText: String): String {
        try {
            val privateKeyEntry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val privateKey = privateKeyEntry.privateKey

            val output = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            output.init(Cipher.DECRYPT_MODE, privateKey)

            val cipherText: String = cryptText
            val cipherInputStream = CipherInputStream(
                ByteArrayInputStream(Base64.decode(cipherText, Base64.DEFAULT)), output
            )
            val values = ArrayList<Byte>()
            var nextByte: Int
            while ((cipherInputStream.read().also { nextByte = it }) != -1) {
                values.add(nextByte.toByte())
            }

            val bytes = ByteArray(values.size)
            for (i in bytes.indices) {
                bytes[i] = values[i]
            }

            val finalText = String(bytes, 0, bytes.size, charset("UTF-8"))
            return finalText

        } catch (e: java.lang.Exception) {
            Log.e(tag, Log.getStackTraceString(e))
            return "decrypt failed"
        }
    }
}


