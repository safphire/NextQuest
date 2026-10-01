// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.VaultMarkdown
import kotlinx.datetime.TimeZone

/**
 * One-way mirror into a folder the user picked (an Obsidian vault, a Syncthing folder…).
 * Writes `Quests/<title>.md` per quest and daily snapshots to `Quests/.backups/`.
 * Only touches files this app wrote (recognised by the `id:` front matter or the
 * snapshot file name pattern); everything else in the folder is left alone.
 */
class VaultWriter(private val context: Context) {
    data class MirrorResult(val written: Int, val removed: Int)

    private fun root(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: throw IllegalStateException("The vault folder is no longer available.")

    private fun dir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
            ?: throw IllegalStateException("Couldn't create “$name” in the vault folder.")

    private fun write(file: DocumentFile, text: String) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Couldn't write ${file.name}.")
    }

    private fun read(file: DocumentFile): String? =
        runCatching { context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

    fun mirror(treeUri: Uri, state: QuestState, zone: TimeZone): MirrorResult = mirror(root(treeUri), state, zone)

    fun mirror(rootDir: DocumentFile, state: QuestState, zone: TimeZone): MirrorResult {
        val folder = dir(rootDir, FOLDER)
        val notes = VaultMarkdown.render(state, zone)
        val wanted = notes.associateBy { it.fileName }
        var removed = 0
        // Notes we wrote earlier under another name (the quest was renamed) are replaced.
        for (f in folder.listFiles()) {
            val name = f.name ?: continue
            if (!f.isFile || !name.endsWith(".md") || name in wanted) continue
            val id = read(f)?.let(VaultMarkdown::idOf) ?: continue
            if (id in state.quests) {
                f.delete()
                removed++
            }
        }
        var written = 0
        for (note in notes) {
            val existing = folder.findFile(note.fileName)
            if (existing != null && read(existing) == note.content) continue
            val file = existing ?: folder.createFile("text/markdown", note.fileName.removeSuffix(".md")) ?: continue
            // Some providers append ".md" themselves, some don't: normalise the name.
            if (file.name != note.fileName) file.renameTo(note.fileName)
            write(file, note.content)
            written++
        }
        return MirrorResult(written, removed)
    }

    /** Writes `Quests/.backups/snapshot-<date>.json` and keeps the newest [KEEP]. */
    fun writeSnapshot(treeUri: Uri, json: String, date: String): String = writeSnapshot(root(treeUri), json, date)

    fun writeSnapshot(rootDir: DocumentFile, json: String, date: String): String {
        val backups = dir(dir(rootDir, FOLDER), BACKUPS)
        val name = "snapshot-$date.json"
        val file = backups.findFile(name) ?: backups.createFile("application/json", name.removeSuffix(".json"))
            ?: throw IllegalStateException("Couldn't create $name.")
        if (file.name != name) file.renameTo(name)
        write(file, json)
        backups.listFiles()
            .filter { it.name?.matches(SNAPSHOT_NAME) == true }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { it.delete() }
        return name
    }

    companion object {
        const val FOLDER = "Quests"
        const val BACKUPS = ".backups"
        const val KEEP = 8
        val SNAPSHOT_NAME = Regex("""snapshot-\d{4}-\d{2}-\d{2}\.json""")
    }
}
