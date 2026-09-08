package com.sixoffive.androidmcp.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

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

    /**
     * Is [uriStr] a SAF document inside one of the trees the user actually granted?
     *
     * Without this check `read` handed the client's string straight to `ContentResolver`, which
     * resolves `file://` to a plain `FileInputStream` — so `file:///proc/self/status` (and any
     * other path the app's own uid can open) was readable, despite this class claiming to be
     * "limited to the SAF folder trees the user has granted". The gate only ever checked that
     * *some* folder had been granted, never that the requested URI was inside one.
     *
     * Containment is by document-id prefix: SAF tree children carry the tree's document id as a
     * prefix (e.g. tree `primary:Docs` → child `primary:Docs/notes.txt`), which is how
     * `DocumentsContract` builds them.
     */
    internal fun containedIn(uriStr: String, trees: Set<String>): Boolean {
        val target = SafUri.parse(uriStr) ?: return false
        // The id a request addresses: its document id, or — for a bare tree URI — the tree root.
        val docId = target.documentId ?: target.treeId ?: return false
        return trees.any { t ->
            val tree = SafUri.parse(t) ?: return@any false
            if (!target.authority.equals(tree.authority, ignoreCase = true)) return@any false
            val treeId = tree.treeId ?: return@any false
            // Exact root, or a descendant. The separator is required so that a grant of
            // "primary:Docs" does not also authorise "primary:Docs2".
            docId == treeId || docId.startsWith(treeId.trimEnd('/') + "/")
        }
    }

    /**
     * The pieces of a Storage Access Framework URI, parsed as plain strings.
     *
     * Deliberately not `DocumentsContract` — its `isDocumentUri` needs a live `Context` to ask the
     * PackageManager whether the authority is a documents provider, which would make the
     * containment check untestable off-device and would fail open if the query threw. Shape:
     * `content://<authority>/tree/<treeId>/document/<docId>`, each id percent-encoded.
     */
    internal data class SafUri(val authority: String, val treeId: String?, val documentId: String?) {
        companion object {
            fun parse(s: String): SafUri? {
                // Only content:// — never file://, which ContentResolver happily resolves to a
                // plain FileInputStream anywhere the app's uid can read.
                val rest = s.removePrefix("content://")
                if (rest.length == s.length) return null // prefix absent → not a content URI
                if (!s.regionMatches(0, ContentResolver.SCHEME_CONTENT, 0, 7, ignoreCase = true)) return null
                val slash = rest.indexOf('/')
                val authority = if (slash < 0) rest else rest.substring(0, slash)
                if (authority.isEmpty()) return null
                val segments = if (slash < 0) emptyList() else
                    rest.substring(slash + 1).substringBefore('?').substringBefore('#')
                        .split('/').filter { it.isNotEmpty() }.map(::decode)
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

    fun read(ctx: Context, uriStr: String, maxBytes: Int = 100_000): String {
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return "invalid uri"
        if (!containedIn(uriStr, ConfigStore.current.folders)) {
            return "refused: $uriStr is not inside a granted folder. Only content:// URIs printed " +
                "by list_files (no argument) can be read — add the folder in androidmcp " +
                "(Shared folders → Add folder) first."
        }
        val stream = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return "cannot open $uriStr (is it inside a granted folder?)"
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
