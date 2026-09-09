package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.server.FilesAccess
import com.sixoffive.androidmcp.server.FilesAccess.Verdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `list_files` containment.
 *
 * The hole this guards: `FilesAccess.read` used to hand the client's string straight to
 * `ContentResolver.openInputStream`, which resolves `file://` to a plain `FileInputStream` — so
 * `file:///proc/self/status` was readable while the class documented itself as SAF-confined.
 *
 * The first fix compared document ids by string prefix. That is only sound for
 * ExternalStorageProvider: document ids are opaque provider strings, which is why
 * `DocumentsProvider` exposes `isChildDocument()` as a callback. The provider is now the authority;
 * these cover the parts that are decidable without one.
 */
class SafContainmentTest {

    private val EXTERNAL = "com.android.externalstorage.documents"

    // ---- the policy: who gets the last word ----

    @Test
    fun `the provider is authoritative when it answers yes`() {
        // Even where the ids look unrelated — a Drive document id bears no resemblance to its
        // tree's id, and that is exactly the case the prefix rule used to refuse.
        assertTrue(FilesAccess.decide(structurallyInside = false, Verdict.CHILD, idSchemeUnderstood = false))
        assertTrue(FilesAccess.decide(structurallyInside = false, Verdict.CHILD, idSchemeUnderstood = true))
    }

    @Test
    fun `the provider is authoritative when it answers no`() {
        // A "no" must stick even when the ids happen to look nested: only the provider knows what
        // its ids mean, so a structural coincidence must not override it.
        assertFalse(FilesAccess.decide(structurallyInside = true, Verdict.NOT_CHILD, idSchemeUnderstood = true))
        assertFalse(FilesAccess.decide(structurallyInside = true, Verdict.NOT_CHILD, idSchemeUnderstood = false))
    }

    @Test
    fun `an unanswerable provider falls back only where the id layout is documented`() {
        assertTrue(FilesAccess.decide(structurallyInside = true, Verdict.UNKNOWN, idSchemeUnderstood = true))
        // Unknown provider + no answer = refuse. Fail closed.
        assertFalse(FilesAccess.decide(structurallyInside = true, Verdict.UNKNOWN, idSchemeUnderstood = false))
        assertFalse(FilesAccess.decide(structurallyInside = false, Verdict.UNKNOWN, idSchemeUnderstood = true))
    }

    // ---- the hierarchical id rule (the fallback) ----

    @Test
    fun `a document under the tree is inside it`() {
        assertTrue(FilesAccess.idInside("primary:Documents/notes.txt", "primary:Documents"))
        assertTrue(FilesAccess.idInside("primary:Documents/sub/deep.txt", "primary:Documents"))
        assertTrue(FilesAccess.idInside("primary:Documents", "primary:Documents"))
    }

    @Test
    fun `a sibling sharing a string prefix is NOT inside`() {
        // "primary:Documents" must not authorise "primary:Documents2".
        assertFalse(FilesAccess.idInside("primary:Documents2/x.txt", "primary:Documents"))
        assertFalse(FilesAccess.idInside("primary:Documents2", "primary:Documents"))
        assertFalse(FilesAccess.idInside("primary:Downloads/x.txt", "primary:Documents"))
    }

    @Test
    fun `a whole-volume grant contains its children`() {
        // Pre-Android-11 grants can hand back the volume root, whose id ends in ':' and whose
        // children carry no slash after it. Requiring a separator refused every one of them —
        // the tree would list files it then refused to read.
        assertTrue(FilesAccess.idInside("primary:DCIM", "primary:"))
        assertTrue(FilesAccess.idInside("primary:DCIM/Camera/x.jpg", "primary:"))
        assertTrue(FilesAccess.idInside("primary:", "primary:"))
        // ...but a different volume is still outside.
        assertFalse(FilesAccess.idInside("secondary:DCIM", "primary:"))
    }

    @Test
    fun `a trailing-slash tree id behaves the same way`() {
        assertTrue(FilesAccess.idInside("abc/def", "abc/"))
        assertFalse(FilesAccess.idInside("abcdef", "abc/"))
    }

    // ---- structural parsing: what never even reaches the provider ----

    private fun parse(s: String) = FilesAccess.SafUri.parse(s)

    @Test
    fun `file scheme is refused — the original hole`() {
        assertNull(parse("file:///proc/self/status"))
        assertNull(parse("file:///data/data/com.sixoffive.androidmcp/shared_prefs/x.xml"))
        assertNull(parse("file:///sdcard/Documents/notes.txt"))
    }

    @Test
    fun `other schemes and malformed input are refused`() {
        for (u in listOf(
            "/etc/passwd", "http://example.com/x", "", "content://",
            "android.resource://com.sixoffive.androidmcp/raw/x",
        )) assertNull(parse(u), "should refuse: $u")
    }

    @Test
    fun `traversal in a decoded id is refused`() {
        assertNull(parse("content://$EXTERNAL/tree/primary%3ADocs/document/.."))
        assertNull(parse("content://$EXTERNAL/tree/primary%3ADocs/document/%2E%2E"))
    }

    @Test
    fun `tree and document ids are decoded`() {
        val u = parse("content://$EXTERNAL/tree/primary%3ADocuments/document/primary%3ADocuments%2Fnotes.txt")
        assertEquals(EXTERNAL, u!!.authority)
        assertEquals("primary:Documents", u.treeId)
        assertEquals("primary:Documents/notes.txt", u.documentId)
    }

    @Test
    fun `a bare tree URI parses with no document id`() {
        val u = parse("content://$EXTERNAL/tree/primary%3ADocuments")
        assertEquals("primary:Documents", u!!.treeId)
        assertNull(u.documentId)
    }

    @Test
    fun `authority is captured so a different provider can be rejected`() {
        val evil = parse("content://com.example.evil.documents/tree/primary%3ADocs/document/primary%3ADocs%2Fx")
        assertEquals("com.example.evil.documents", evil!!.authority)
        // The authority comparison happens in allowed(); this just pins that it is available.
        assertTrue(evil.authority != EXTERNAL)
    }
}
