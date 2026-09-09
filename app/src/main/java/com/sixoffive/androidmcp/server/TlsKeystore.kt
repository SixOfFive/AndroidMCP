package com.sixoffive.androidmcp.server

import android.content.Context
import com.sixoffive.androidmcp.core.AuditLog
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import java.net.InetAddress
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
        val wanted = subjectAddresses()
        if (cf.exists() && kf.exists()) {
            val loaded = runCatching {
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
                        as java.security.cert.X509Certificate
                // Reuse only a cert that is still usable. Two ways the stored one goes stale:
                // it expired (ktor's default validity was 3 days — see below), or the device moved
                // to an address the cert has no SAN for, which fails hostname verification.
                cert.checkValidity()
                require(covers(cert, wanted)) { "cert has no SAN for ${wanted.joinToString()}" }
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                keystoreOf(key, arrayOf(cert))
            }.getOrNull()
            if (loaded != null) return loaded
            // Falling through means the stored cert is expired or no longer covers this device's
            // address, so it must be re-issued. Say so in the audit log rather than silently
            // changing the fingerprint the owner may have pinned in a client.
            AuditLog.record(
                "tls", "local", true,
                "re-issuing certificate (expired, or no SAN for ${wanted.joinToString()}) — " +
                    "the pinned SHA-256 will change",
            )
        }
        // Generate a fresh self-signed cert+key, persist the DER, and build the keystore.
        val gen = generate(wanted)
        val key = gen.getKey(ALIAS, PW) as PrivateKey
        val chain: Array<Certificate> = gen.getCertificateChain(ALIAS)
        // Write to temporaries and rename only once BOTH succeed. Writing the two files in place
        // left a window where a failure between them (ENOSPC, process death) produced a new cert
        // beside the old key — a pair that loads fine (`checkValidity` and `covers` both pass) but
        // can never complete a handshake, on every subsequent start, until deleted by hand.
        runCatching {
            val ct = File(cf.parentFile, "$CERT_FILE.tmp")
            val kt = File(kf.parentFile, "$KEY_FILE.tmp")
            ct.outputStream().use { it.write(chain[0].encoded) } // X.509 DER
            kt.outputStream().use { it.write(key.encoded) }       // PKCS8 DER
            check(ct.renameTo(cf) && kt.renameTo(kf)) { "could not commit TLS material" }
        }
        return keystoreOf(key, chain)
    }

    /**
     * Mint a self-signed cert covering [addresses].
     *
     * Every field here overrides a ktor 2.3.12 `CertificateBuilder` default that made the previous
     * cert unusable. Measured on a real device against the cert this app had actually generated:
     *
     *     Signature Algorithm: sha1WithRSAEncryption
     *     Public-Key: (1024 bit)
     *     Not Before: Sep  8 16:42:06 2026 GMT
     *     Not After : Sep 11 16:42:06 2026 GMT          <- three days
     *     DNS:localhost, DNS:127.0.0.1, IP Address:127.0.0.1
     *
     * So the "persistent, pinnable" cert expired after three days, used a key size and hash that
     * modern TLS stacks reject outright, and carried no SAN for the LAN or tailnet address — the
     * only binds where enabling TLS is worth anything.
     */
    internal fun generate(addresses: List<String>): KeyStore = buildKeyStore {
        certificate(ALIAS) {
            password = String(PW)
            domains = listOf("localhost")
            ipAddresses = addresses.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            daysValid = 3650
            keySizeInBits = 2048
            hash = HashAlgorithm.SHA256
        }
    }

    /** Every address this device can currently be reached on, so one cert covers all three binds. */
    private fun subjectAddresses(): List<String> =
        (listOf("127.0.0.1") + listOfNotNull(Net.lanAddress(), Net.tailnetAddress())).distinct()

    /** Does [cert]'s subjectAltName list cover every address in [wanted]? */
    private fun covers(cert: java.security.cert.X509Certificate, wanted: List<String>): Boolean {
        val sans = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
            .mapNotNull { it.getOrNull(1) as? String }.toSet()
        return wanted.all { it in sans }
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
