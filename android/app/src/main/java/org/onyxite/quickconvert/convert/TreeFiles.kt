package org.onyxite.quickconvert.convert

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/** Reads and writes folders the user picked with the system file picker. */
object TreeFiles {
    data class ListedFile(val uri: Uri, val name: String, val size: Long)

    /** Every file under [tree], including files in subfolders. */
    fun listFiles(resolver: ContentResolver, tree: Uri): List<ListedFile> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val files = ArrayList<ListedFile>()
        val seen = HashSet<String>()
        fun walk(documentId: String) {
            if (!seen.add(documentId)) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val rows = ArrayList<Child>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getString(idCol) ?: continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    rows += Child(id, name, mime, size)
                }
            }
            for (row in rows) {
                if (row.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(row.id)
                } else {
                    files += ListedFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, row.id),
                        name = row.name.ifBlank { "song" },
                        size = row.size,
                    )
                }
            }
        }
        walk(rootId)
        return files
    }

    private data class Child(val id: String, val name: String, val mime: String, val size: Long)

    fun write(resolver: ContentResolver, tree: Uri, relativePath: String, bytes: ByteArray) {
        writeTo(resolver, tree, relativePath) { it.write(bytes) }
    }

    fun writeTo(resolver: ContentResolver, tree: Uri, relativePath: String, write: (OutputStream) -> Unit) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw IllegalArgumentException("Missing output file name")
        for (dir in parts.dropLast(1)) {
            parent = findChild(resolver, tree, parent, dir)
                ?: create(resolver, parent, dir, DocumentsContract.Document.MIME_TYPE_DIR)
        }
        val name = parts.last()
        findChild(resolver, tree, parent, name)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, existing) }
        }
        val doc = create(resolver, parent, name, "application/octet-stream")
        resolver.openOutputStream(doc)?.use(write)
            ?: throw IllegalStateException("Could not open $name for writing")
    }

    private fun create(resolver: ContentResolver, parent: Uri, name: String, mime: String): Uri =
        DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IllegalStateException("Could not create $name in the output folder")

    private fun findChild(resolver: ContentResolver, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(parent),
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (idCol < 0 || nameCol < 0) continue
                if (cursor.getString(nameCol) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol))
                }
            }
        }
        return null
    }
}
