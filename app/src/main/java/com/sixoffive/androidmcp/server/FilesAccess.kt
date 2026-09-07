package com.sixoffive.androidmcp.server

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

    fun read(ctx: Context, uriStr: String, maxBytes: Int = 100_000): String {
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return "invalid uri"
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
