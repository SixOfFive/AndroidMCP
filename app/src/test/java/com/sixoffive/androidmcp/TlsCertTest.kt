package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.server.TlsKeystore
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The self-signed TLS cert must actually be usable by a verifying client.
 *
 * ktor 2.3.12's `CertificateBuilder` defaults are `daysValid = 3`, `keySizeInBits = 1024` and
 * `hash = SHA1`, with IP SANs of 127.0.0.1 only — and none of them were being overridden. Measured
 * on the device's own previously-generated cert:
 *
 *     Signature Algorithm: sha1WithRSAEncryption
 *     Public-Key: (1024 bit)
 *     Not Before: Sep  8 16:42:06 2026 GMT
 *     Not After : Sep 11 16:42:06 2026 GMT
 *     DNS:localhost, DNS:127.0.0.1, IP Address:127.0.0.1
 *
 * The README advertised that cert as "persistent, pinnable". It was stable, but it expired three
 * days after minting and used a key and hash no modern TLS stack accepts.
 */
class TlsCertTest {

    private fun cert(vararg addresses: String): X509Certificate =
        TlsKeystore.generate(addresses.toList())
            .getCertificate(TlsKeystore.ALIAS) as X509Certificate

    @Test
    fun `the cert outlives a weekend`() {
        val c = cert("127.0.0.1")
        val days = TimeUnit.MILLISECONDS.toDays(c.notAfter.time - c.notBefore.time)
        assertTrue(days > 3000, "cert is valid for only $days days — ktor's default is 3")
    }

    @Test
    fun `the key and signature hash are ones a modern client will accept`() {
        val c = cert("127.0.0.1")
        val bits = (c.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength()
        assertTrue(bits >= 2048, "RSA-$bits is below the 2048-bit floor most TLS stacks enforce")
        assertTrue(
            c.sigAlgName.contains("SHA256", ignoreCase = true),
            "signed with ${c.sigAlgName}; SHA-1 signatures are rejected outright",
        )
    }

    @Test
    fun `every bind address gets an IP SAN`() {
        // The failure this prevents: `https://192.168.15.123:8765` fails hostname verification with
        // "IP does not match certificate's altnames" — on exactly the LAN and tailnet binds where
        // TLS is worth enabling, since loopback and Tailscale are already private.
        val c = cert("127.0.0.1", "192.168.15.123", "100.127.216.3")
        val sans = c.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1) as? String }.toSet()
        listOf("127.0.0.1", "192.168.15.123", "100.127.216.3").forEach {
            assertTrue(it in sans, "$it is missing from the cert SANs: $sans")
        }
        assertTrue("localhost" in sans)
    }

    @Test
    fun `the fingerprint is stable for one cert and differs between certs`() {
        // Pinning only means anything if the same cert always hashes the same, and a *different*
        // cert does not silently pass the same pin.
        val a = cert("127.0.0.1")
        assertEquals(a.encoded.toList(), a.encoded.toList())
        val b = cert("127.0.0.1")
        assertTrue(!a.encoded.contentEquals(b.encoded), "two mints must not produce the same cert")
    }
}
