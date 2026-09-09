package com.sixoffive.androidmcp.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sixoffive.androidmcp.core.ConfigStore

/** Read-only file access limited to the SAF folder trees the user has granted. */
object FilesAccess {

    fun listAll(ctx: Context, limit: Int = 150): String {
        val trees = ConfigStore.current.folders
        if (trees.isEmpty()) {
            return "no folders granted — add one in androidmcp (Shared folders → Add folder)"
        }
        val out = StringBuilder(); var n = 0
        for (t in trees) {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(t)) ?: continue
            out.append("# ${root.name ?: t}\n")
            n += walk(root, "", out, limit - n)
            if (n >= limit) { out.append("… (truncated at $limit entries)\n"); break }
        }
        return out.toString().trim().ifEmpty { "granted folders are empty" }
    }

    private fun walk(dir: DocumentFile, prefix: String, out: StringBuilder, remaining: Int): Int {
        if (remaining <= 0) return 0
        var n = 0
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        for (f in children) {
            if (n >= remaining) break
            val name = f.name ?: "?"
            if (f.isDirectory) {
                out.append("$prefix$name/\n"); n++
                n += walk(f, "$prefix$name/", out, remaining - n)
            } else {
                out.append("$prefix$name  ${f.length()}B  ${f.uri}\n"); n++
            }
        }
        return n
    }

    // ---- containment ----------------------------------------------------------------------
    //
    // Without a containment check, `read` handed the client's string straight to
    // `ContentResolver.openInputStream`, which resolves `file://` to a plain `FileInputStream` —
    // so `file:///proc/self/status` was readable while this class documented itself as confined to
    // the granted SAF trees. The first fix compared document ids by string prefix, which is only
    // sound for ExternalStorageProvider: document ids are OPAQUE provider strings, which is exactly
    // why `DocumentsProvider` exposes `isChildDocument()` as a callback. That version refused
    // perfectly good URIs from Drive/Dropbox and from pre-Android-11 volume-root grants — failing
    // closed, but refusing URIs `list_files` had itself just printed.
    //
    // The provider is now the authority, with the structural rules as a hard pre-filter and the
    // prefix rule kept only as a fallback for the one authority whose id layout is documented.

    /** What the owning provider says about containment. */
    internal enum class Verdict { CHILD, NOT_CHILD, UNKNOWN }

    /**
     * Authorities whose document ids are `volume:relative/path`, making a prefix test sound.
     *
     * Deliberately just the one. MediaDocumentsProvider (`image:42`) and DownloadStorageProvider
     * (`msf:42`, `raw:/…`) use flat ids with no prefix relation to their tree, so a prefix test
     * there would be meaningless — they must answer for themselves, and in AOSP they do.
     */
    private val PREFIX_ID_AUTHORITIES = setOf("com.android.externalstorage.documents")

    /**
     * The policy, kept pure so it can be unit-tested without a device.
     *
     * The provider is authoritative **in both directions**: a `NOT_CHILD` answer is a refusal even
     * if the ids look nested, because only the provider knows what its ids mean. The fallback is
     * reached only when the provider cannot answer at all, and then only for an authority whose id
     * layout is documented — anything else fails closed.
     */
    internal fun decide(structurallyInside: Boolean, verdict: Verdict, idSchemeUnderstood: Boolean): Boolean =
        when (verdict) {
            Verdict.CHILD -> true
            Verdict.NOT_CHILD -> false
            Verdict.UNKNOWN -> structurallyInside && idSchemeUnderstood
        }

    /**
     * Is [docId] the tree root [treeId], or beneath it, for a hierarchical id scheme?
     *
     * The separator is required so a grant of `primary:Docs` does not also authorise
     * `primary:Docs2` — except for a volume root, whose id already ends in `:` (`primary:`) and
     * whose children are `primary:DCIM` with no slash. That case is why a pre-Android-11
     * whole-volume grant used to be refused outright.
     */
    internal fun idInside(docId: String, treeId: String): Boolean = when {
        docId == treeId -> true
        treeId.endsWith(":") || treeId.endsWith("/") -> docId.startsWith(treeId)
        else -> docId.startsWith("$treeId/")
    }

    /**
     * May [uri] be read, given the trees the owner has granted?
     *
     * Takes an already-parsed [Uri] rather than a string on purpose: the check and the subsequent
     * `openInputStream` must agree on what the URI *is*. Validating a string with one parser and
     * opening it with another invites a parser differential where the check passes for one target
     * and the resolver fetches a different one.
     */
    internal fun allowed(ctx: Context, uri: Uri): Boolean {
        val target = SafUri.of(uri) ?: return false
        val docId = target.documentId ?: target.treeId ?: return false

        for (treeStr in ConfigStore.current.folders) {
            val treeUri = runCatching { Uri.parse(treeStr) }.getOrNull() ?: continue
            val tree = SafUri.of(treeUri) ?: continue
            if (!target.authority.equals(tree.authority, ignoreCase = true)) continue
            val treeId = tree.treeId ?: continue

            // Still holding the grant? The owner can revoke it in Settings long after we recorded
            // it. Checked against the TREE uri — a child derived from a tree grant is never itself
            // a persisted entry, so matching the child here would always fail.
            if (!stillGranted(ctx.contentResolver, treeUri)) continue

            if (docId == treeId) return true // the granted root itself

            val structural = idInside(docId, treeId)
            val verdict = askProvider(ctx, treeUri, uri)
            if (decide(structural, verdict, tree.authority.lowercase() in PREFIX_ID_AUTHORITIES)) return true
        }
        return false
    }

    private fun stillGranted(resolver: ContentResolver, treeUri: Uri): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == treeUri }
    }.getOrDefault(false)

    /** Ask the owning provider whether [child] lives under [treeUri]. Never throws. */
    private fun askProvider(ctx: Context, treeUri: Uri, child: Uri): Verdict = runCatching {
        // isChildDocument compares two *document* URIs, so the tree has to be expressed as the
        // document it points at.
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        if (DocumentsContract.isChildDocument(ctx.contentResolver, parentDoc, child)) {
            Verdict.CHILD
        } else {
            Verdict.NOT_CHILD
        }
    }.getOrElse {
        // UnsupportedOperationException (the provider does not implement it), a dead provider, a
        // missing document — none of these are a "no", so fall back rather than guess.
        Verdict.UNKNOWN
    }

    /**
     * The pieces of a Storage Access Framework URI.
     *
     * Shape: `content://<authority>/tree/<treeId>/document/<docId>`. [of] is what runs at runtime —
     * it reads an already-parsed platform [Uri], so it cannot disagree with the resolver. [parse]
     * exists for the JVM tests, which have no `android.net.Uri`; a test asserts the two agree on
     * every case it covers.
     */
    internal data class SafUri(val authority: String, val treeId: String?, val documentId: String?) {
        companion object {
            fun of(uri: Uri): SafUri? {
                if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return null
                val authority = uri.authority?.takeIf { it.isNotEmpty() } ?: return null
                val segments = runCatching { uri.pathSegments }.getOrNull().orEmpty()
                return fromSegments(authority, segments)
            }

            fun parse(s: String): SafUri? {
                val rest = s.removePrefix("content://")
                if (rest.length == s.length) return null // prefix absent → not a content URI
                if (!s.regionMatches(0, ContentResolver.SCHEME_CONTENT, 0, 7, ignoreCase = true)) return null
                val slash = rest.indexOf('/')
                val authority = if (slash < 0) rest else rest.substring(0, slash)
                if (authority.isEmpty()) return null
                val segments = if (slash < 0) emptyList() else
                    rest.substring(slash + 1).substringBefore('?').substringBefore('#')
                        .split('/').filter { it.isNotEmpty() }.map(::decode)
                return fromSegments(authority, segments)
            }

            private fun fromSegments(authority: String, segments: List<String>): SafUri? {
                // Reject traversal in the decoded ids rather than trying to normalise them.
                if (segments.any { it == ".." || it.contains("/../") }) return null
                fun after(k: String): String? =
                    segments.indexOf(k).takeIf { it >= 0 && it + 1 < segments.size }?.let { segments[it + 1] }
                return SafUri(authority, after("tree"), after("document"))
            }

            private fun decode(s: String): String =
                runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        }
    }

    // ---- reading --------------------------------------------------------------------------

    fun read(ctx: Context, uriStr: String, maxBytes: Int = 100_000): String {
        // Parse ONCE and use that same object for both the check and the open.
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
            ?: throw ToolArgError("invalid uri: $uriStr")
        if (!allowed(ctx, uri)) {
            // THROWN, not returned. Returning it made a refusal arrive as isError:false — a
            // "successful" call whose text happens to say "refused", which a model can easily read
            // as "the file says: refused". Caught on-device after the unit-test whitelist had
            // explicitly exempted this very string.
            throw ToolArgError(
                "refused: $uriStr is not inside a granted folder. Only content:// URIs printed " +
                    "by list_files (no argument) can be read — add the folder in androidmcp " +
                    "(Shared folders → Add folder) first.",
            )
        }
        val stream = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw ToolExecError("cannot open $uriStr (it may have been deleted, or the grant revoked)")
        return stream.use { s ->
            val buf = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val r = s.read(buf, read, maxBytes - read)
                if (r < 0) break
                read += r
            }
            val more = s.read() != -1
            val text = String(buf, 0, read, Charsets.UTF_8)
            val printable = text.count { it == '\n' || it == '\t' || it.code in 32..126 || it.code > 160 }
            if (read > 0 && printable < text.length * 0.7) {
                "[binary file, $read${if (more) "+" else ""} bytes — not shown as text]"
            } else {
                text + if (more) "\n… (truncated at $maxBytes bytes)" else ""
            }
        }
    }
}
