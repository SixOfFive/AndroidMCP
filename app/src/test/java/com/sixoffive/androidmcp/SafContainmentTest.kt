package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.server.FilesAccess
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `list_files` containment.
 *
 * The defect this guards: `FilesAccess.read` used to hand the client's string straight to
 * `ContentResolver.openInputStream`, which resolves `file://` to a plain `FileInputStream`. So
 * `{"uri":"file:///proc/self/status"}` read a file far outside every granted folder, while the
 * class documented itself as "limited to the SAF folder trees the user has granted" and the gate
 * only ever checked that *some* folder had been granted.
 */
class SafContainmentTest {

    private val docsTree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    private val trees = setOf(docsTree)

    private fun contained(uri: String) = FilesAccess.containedIn(uri, trees)

    @Test
    fun `a document inside the granted tree is allowed`() {
        assertTrue(contained("$docsTree/document/primary%3ADocuments%2Fnotes.txt"))
        assertTrue(contained("$docsTree/document/primary%3ADocuments%2Fsub%2Fdeep.txt"))
    }

    @Test
    fun `the granted tree root itself is allowed`() {
        assertTrue(contained(docsTree))
    }

    @Test
    fun `file scheme is refused — the original hole`() {
        assertFalse(contained("file:///proc/self/status"))
        assertFalse(contained("file:///data/data/com.sixoffive.androidmcp/shared_prefs/x.xml"))
        assertFalse(contained("file:///sdcard/Documents/notes.txt"))
    }

    @Test
    fun `other schemes are refused`() {
        for (u in listOf(
            "/etc/passwd",
            "http://example.com/x",
            "android.resource://com.sixoffive.androidmcp/raw/x",
            "",
            "content://",
        )) assertFalse(contained(u), "should refuse: $u")
    }

    @Test
    fun `a different authority is refused even with a matching document id`() {
        assertFalse(contained(
            "content://com.example.evil.documents/tree/primary%3ADocuments/document/primary%3ADocuments%2Fx.txt"
        ))
    }

    @Test
    fun `a sibling tree with the granted tree as a string prefix is refused`() {
        // "primary:Documents" must not authorise "primary:Documents2" — prefix matching without a
        // separator would let a neighbouring folder through.
        assertFalse(contained(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments2/document/primary%3ADocuments2%2Fx.txt"
        ))
        assertFalse(contained(
            "content://com.android.externalstorage.documents/tree/primary%3ADownloads/document/primary%3ADownloads%2Fx.txt"
        ))
    }

    @Test
    fun `a document id outside the tree is refused even under a granted tree URI`() {
        // A crafted URI that keeps the granted tree segment but points the document elsewhere.
        assertFalse(contained("$docsTree/document/primary%3ADownloads%2Fsecret.txt"))
    }

    @Test
    fun `traversal in the document id is refused`() {
        assertFalse(contained("$docsTree/document/primary%3ADocuments%2F..%2FDownloads%2Fx.txt"))
        assertFalse(contained("$docsTree/document/.."))
    }

    @Test
    fun `nothing is contained when no folder has been granted`() {
        assertFalse(FilesAccess.containedIn("$docsTree/document/primary%3ADocuments%2Fx.txt", emptySet()))
    }

    @Test
    fun `multiple granted trees each authorise their own contents`() {
        val dl = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
        val both = setOf(docsTree, dl)
        assertTrue(FilesAccess.containedIn("$docsTree/document/primary%3ADocuments%2Fa.txt", both))
        assertTrue(FilesAccess.containedIn("$dl/document/primary%3ADownloads%2Fb.txt", both))
        assertFalse(FilesAccess.containedIn("$dl/document/primary%3APictures%2Fc.jpg", both))
    }
}
