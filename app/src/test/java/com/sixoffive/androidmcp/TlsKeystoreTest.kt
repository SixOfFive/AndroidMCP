package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.server.TlsKeystore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence and re-issue behaviour of the self-signed TLS material.
 *
 * This is the half of `TlsKeystore` that has nothing to do with Android — it only ever needed
 * `Context` for `filesDir` — and it is where the interesting failures live: a fingerprint that
 * must stay stable for pinning to mean anything, a re-issue when the device's address changes,
 * and a two-file commit that must not leave a cert beside a mismatched key.
 *
 * Everything below was previously reachable only by a human toggling HTTPS on a device and
 * reading a fingerprint off the screen.
 */
class TlsKeystoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun certOf(ks: KeyStore) = ks.getCertificate(TlsKeystore.ALIAS) as X509Certificate
    private fun keyOf(ks: KeyStore) = ks.getKey(TlsKeystore.ALIAS, TlsKeystore.PW) as PrivateKey

    // ---- persistence ----

    @Test
    fun `first call mints and persists both cert and key`() {
        val dir = tmp.newFolder()
        assertNull(TlsKeystore.fingerprintSha256(dir), "nothing should exist before the first call")

        val ks = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        assertNotNull(certOf(ks))
        assertTrue(File(dir, TlsKeystore.CERT_FILE).length() > 0)
        assertTrue(File(dir, TlsKeystore.KEY_FILE).length() > 0)
        assertNotNull(TlsKeystore.fingerprintSha256(dir))
    }

    @Test
    fun `the fingerprint is stable across reloads — pinning depends on this`() {
        // The whole reason the material is persisted. It used to be regenerated on every server
        // start, which made the SHA-256 the app displays impossible to pin against.
        val dir = tmp.newFolder()
        val first = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        val second = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))

        assertTrue(certOf(first).encoded.contentEquals(certOf(second).encoded))
        assertEquals(TlsKeystore.fingerprintSha256(dir), TlsKeystore.fingerprintSha256(dir))
        // ...and the reported fingerprint really is of the cert being served.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(certOf(second).encoded).joinToString(":") { "%02X".format(it) }
        assertEquals(digest, TlsKeystore.fingerprintSha256(dir))
    }

    @Test
    fun `the private key is reloaded, not regenerated`() {
        val dir = tmp.newFolder()
        val a = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        val b = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        assertTrue(keyOf(a).encoded.contentEquals(keyOf(b).encoded))
    }

    // ---- re-issue ----

    @Test
    fun `a cert with no SAN for the current address is re-issued`() {
        // The device moves networks: DHCP hands out a new LAN address, or Tailscale comes up.
        // Reusing the old cert would fail hostname verification, so it must be re-minted.
        val dir = tmp.newFolder()
        val before = TlsKeystore.fingerprintSha256(dir)
        TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        val loopbackOnly = TlsKeystore.fingerprintSha256(dir)
        assertNull(before)
        assertNotNull(loopbackOnly)

        val withLan = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1", "192.168.1.50"))
        assertTrue(
            TlsKeystore.fingerprintSha256(dir) != loopbackOnly,
            "a new address must force a re-issue",
        )
        val sans = certOf(withLan).subjectAlternativeNames.orEmpty()
            .mapNotNull { it.getOrNull(1) as? String }.toSet()
        assertTrue("192.168.1.50" in sans)
        assertTrue("127.0.0.1" in sans)
    }

    @Test
    fun `a cert that already covers the address is NOT re-issued`() {
        // The mirror of the above — re-minting on every start is exactly the bug that made the
        // fingerprint unpinnable, so a superset must be reused.
        val dir = tmp.newFolder()
        TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1", "192.168.1.50"))
        val first = TlsKeystore.fingerprintSha256(dir)
        TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1", "192.168.1.50"))
        assertEquals(first, TlsKeystore.fingerprintSha256(dir))
    }

    // ---- the two-file commit ----

    @Test
    fun `a cert left beside a mismatched key is recovered from, not served`() {
        // The failure the temp-and-rename commit exists to prevent: writing the two DER files in
        // place left a window where a crash produced a NEW cert beside an OLD key. That pair
        // loads cleanly — validity and SAN checks both pass — but can never complete a handshake,
        // and it does so on every subsequent start until someone deletes the files by hand.
        val dirA = tmp.newFolder()
        val dirB = tmp.newFolder()
        TlsKeystore.loadOrCreate(dirA, listOf("127.0.0.1"))
        TlsKeystore.loadOrCreate(dirB, listOf("127.0.0.1"))

        // Splice B's cert next to A's key.
        File(dirB, TlsKeystore.CERT_FILE).copyTo(File(dirA, TlsKeystore.CERT_FILE), overwrite = true)

        val ks = TlsKeystore.loadOrCreate(dirA, listOf("127.0.0.1"))
        val cert = certOf(ks)
        val key = keyOf(ks)
        // Whatever it returns, the pair it hands back must actually belong together.
        assertTrue(
            keysMatch(cert, key),
            "loadOrCreate returned a certificate whose public key does not match its private key",
        )
    }

    @Test
    fun `the commit leaves no temporary files behind`() {
        val dir = tmp.newFolder()
        TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        val leftovers = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "temporary files left behind: $leftovers")
    }

    @Test
    fun `a stale temp file from a previous crash does not break the next mint`() {
        val dir = tmp.newFolder()
        File(dir, TlsKeystore.CERT_FILE + ".tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, TlsKeystore.KEY_FILE + ".tmp").writeBytes(byteArrayOf(4, 5, 6))
        val ks = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        assertNotNull(certOf(ks))
        assertTrue(keysMatch(certOf(ks), keyOf(ks)))
    }

    @Test
    fun `corrupt material is replaced rather than propagated`() {
        val dir = tmp.newFolder()
        File(dir, TlsKeystore.CERT_FILE).writeBytes("not a certificate".toByteArray())
        File(dir, TlsKeystore.KEY_FILE).writeBytes("not a key".toByteArray())
        val ks = TlsKeystore.loadOrCreate(dir, listOf("127.0.0.1"))
        assertNotNull(certOf(ks))
        assertTrue(keysMatch(certOf(ks), keyOf(ks)))
    }

    // ---- properties of the minted certificate ----

    @Test
    fun `a minted cert is usable by a verifying client`() {
        // ktor's CertificateBuilder defaults are daysValid=3, RSA-1024 and SHA-1; all three are
        // overridden. Measured on-device before that fix: a "persistent, pinnable" cert that
        // expired three days after minting.
        val c = certOf(TlsKeystore.loadOrCreate(tmp.newFolder(), listOf("127.0.0.1")))
        val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(c.notAfter.time - c.notBefore.time)
        assertTrue(days > 3000, "valid for only $days days")
        assertTrue((c.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength() >= 2048)
        assertTrue(c.sigAlgName.contains("SHA256", ignoreCase = true), "signed with ${c.sigAlgName}")
        c.checkValidity()
    }

    @Test
    fun `an unresolvable address is skipped rather than failing the mint`() {
        // subjectAddresses() can hand over whatever the interfaces report; a bad entry must not
        // stop the server getting a certificate at all.
        val ks = TlsKeystore.loadOrCreate(tmp.newFolder(), listOf("127.0.0.1", "not-an-address"))
        assertNotNull(certOf(ks))
    }

    /** Does this certificate's public key correspond to this private key? */
    private fun keysMatch(cert: X509Certificate, key: PrivateKey): Boolean {
        val data = "androidmcp-keypair-check".toByteArray()
        return runCatching {
            val sig = java.security.Signature.getInstance("SHA256withRSA")
            sig.initSign(key); sig.update(data)
            val signed = sig.sign()
            val ver = java.security.Signature.getInstance("SHA256withRSA")
            ver.initVerify(cert.publicKey); ver.update(data)
            ver.verify(signed)
        }.getOrDefault(false)
    }
}
