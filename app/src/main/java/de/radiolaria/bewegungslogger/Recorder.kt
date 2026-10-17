package de.radiolaria.bewegungslogger

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt Datensätze stundenweise in interne Dateien und kopiert abgeschlossene
 * Stunden in den gewählten Ordner. Eine exportierte Datei wird nie wieder verändert.
 * Dateiname mit UTC-Versatz, damit die doppelte Stunde bei der Zeitumstellung eindeutig bleibt:
 *   bewegung_2026-10-17_14+0200.jsonl
 */
class Recorder(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "pending").apply { mkdirs() }
    private val nameFmt = SimpleDateFormat("yyyy-MM-dd_HHZ", Locale.US)

    fun fileNameFor(ms: Long) = "bewegung_" + nameFmt.format(Date(ms)) + ".jsonl"

    fun append(nowMs: Long, line: String) {
        File(dir, fileNameFor(nowMs)).appendText(line + "\n")
    }

    fun pendingCount(nowMs: Long): Int {
        val cur = fileNameFor(nowMs)
        return dir.listFiles()?.count { it.name.endsWith(".jsonl") && it.name != cur } ?: 0
    }

    /**
     * Sofort-Export: Die laufende Stunde wird als Teildatei abgeschlossen
     * (bewegung_..._HH+0200_teil-HHmm.jsonl) und mit allen abgeschlossenen Stunden exportiert.
     * Spätere Datensätze derselben Stunde landen in der normalen Stundendatei.
     */
    fun exportNow(nowMs: Long): Int {
        val cur = File(dir, fileNameFor(nowMs))
        if (cur.exists() && Prefs.treeUri(ctx) != null) {
            val base = cur.name.removeSuffix(".jsonl") + "_teil-" + SimpleDateFormat("HHmm", Locale.US).format(Date(nowMs))
            var target = File(dir, "$base.jsonl")
            var i = 2
            while (target.exists()) target = File(dir, "$base-${i++}.jsonl")
            cur.renameTo(target)
        }
        return exportCompleted(nowMs)
    }

    /**
     * Exportiert alle abgeschlossenen Stunden.
     * @return Anzahl exportierter Dateien; -1 = kein Ordner gewählt.
     */
    fun exportCompleted(nowMs: Long): Int {
        val tree = Prefs.treeUri(ctx) ?: return -1
        val cur = fileNameFor(nowMs)
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") && it.name != cur }
            ?.sortedBy { it.name }
            ?: return 0
        if (files.isEmpty()) return 0

        val cr = ctx.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val existing = childNames(tree, parentId)
        var count = 0
        for (f in files) {
            if (f.name !in existing) {
                // octet-stream, damit der Anbieter keine Endung anhängt
                val doc: Uri = DocumentsContract.createDocument(cr, parent, "application/octet-stream", f.name)
                    ?: break
                try {
                    val out = cr.openOutputStream(doc) ?: throw IllegalStateException("kein Ausgabestrom")
                    out.use { o -> f.inputStream().use { it.copyTo(o) } }
                } catch (e: Exception) {
                    // Halbe Datei nicht liegen lassen, beim nächsten Mal erneut versuchen
                    runCatching { DocumentsContract.deleteDocument(cr, doc) }
                    throw e
                }
            }
            f.delete()
            count++
        }
        return count
    }

    private fun childNames(tree: Uri, parentId: String): Set<String> {
        val out = HashSet<String>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }
}
