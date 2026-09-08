package com.sixoffive.androidmcp.server

import android.content.Context
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/**
 * A **persistent** self-signed TLS keypair so the cert (and its SHA-256 fingerprint) is stable
 * across restarts and a client can pin it. The cert used to be regenerated on every server start.
 *
 * Persisted as raw DER — cert as X.509, key as PKCS8 — in the app's private files dir, then the
 * in-memory PKCS12 keystore is rebuilt from them. (KeyStore.store round-trips proved unreliable on
 * this Android/BouncyCastle stack, so we serialise the material directly.) Delete both files to
 * rotate the cert. RSA, per ktor buildKeyStore's default.
 */
object TlsKeystore {
    const val ALIAS = "androidmcp"
    val PW: CharArray = "androidmcp".toCharArray()
    private const val CERT_FILE = "mcp-tls.crt"
    private const val KEY_FILE = "mcp-tls.key"

    fun loadOrCreate(ctx: Context): KeyStore {
        val cf = File(ctx.filesDir, CERT_FILE)
        val kf = File(ctx.filesDir, KEY_FILE)
        if (cf.exists() && kf.exists()) {
            val loaded = runCatching {
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                keystoreOf(key, arrayOf(cert))
            }.getOrNull()
            if (loaded != null) return loaded
        }
        // Generate a fresh self-signed cert+key, persist the DER, and build the keystore.
        val gen = buildKeyStore {
            certificate(ALIAS) {
                password = String(PW)
                domains = listOf("localhost", "127.0.0.1")
            }
        }
        val key = gen.getKey(ALIAS, PW) as PrivateKey
        val chain: Array<Certificate> = gen.getCertificateChain(ALIAS)
        runCatching {
            cf.outputStream().use { it.write(chain[0].encoded) } // X.509 DER
            kf.outputStream().use { it.write(key.encoded) }       // PKCS8 DER
        }
        return keystoreOf(key, chain)
    }

    private fun keystoreOf(key: PrivateKey, chain: Array<Certificate>): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, key, PW, chain)
        }

    /** SHA-256 fingerprint (colon-hex) of the persisted cert, or null if not generated yet. */
    fun fingerprintSha256(ctx: Context): String? = runCatching {
        val cf = File(ctx.filesDir, CERT_FILE)
        if (!cf.exists()) return null
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
