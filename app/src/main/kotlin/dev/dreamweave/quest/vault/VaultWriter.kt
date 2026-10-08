// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.VaultMarkdown
import dev.dreamweave.quest.protocol.SnapshotCodec
import dev.dreamweave.quest.protocol.SnapshotRead
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A vault problem with a message fit to show the user. */
class VaultException(message: String) : Exception(message)

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A `.md` file found in `Quests/`. */
private class ExistingNote(val file: DocumentFile, val name: String, val bytes: ByteArray?) {
    val hash: String? = bytes?.let { sha256(it) }
    val id: String? = bytes?.let { VaultMarkdown.idOf(it.decodeToString()) }

    /** Lowercased name without `.md`: the key VaultMarkdown.names() uses. */
    val key: String = name.dropLast(3).lowercase()
}

/**
 * One-way mirror into a folder the user picked (an Obsidian vault, a Syncthing folder…).
 * Writes `Quests/<title>.md` per quest and daily snapshots to `Quests/.backups/` (D-401).
 *
 * It never overwrites or deletes a file it didn't write:
 * - a note is overwritten only if its front matter `id:` is the quest's own id, or it is
 *   exactly what the last mirror wrote (the manifest). A quest whose name is taken by any
 *   other file gets a suffixed name instead, and wikilinks follow.
 * - a note is deleted (quest renamed) only if the manifest says the app wrote it and its
 *   content hasn't changed since: copies and edited notes stay.
 * - a snapshot for a day is replaced only by one that holds every event it holds.
 */
class VaultWriter(private val context: Context) {
    data class MirrorResult(val written: Int, val removed: Int, val keptForeign: Int = 0)

    private fun root(treeUri: Uri): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw VaultException("The vault folder is no longer available.")
        // Without the grant, DocumentFile calls fail quietly (empty listings, null results).
        if (!root.canWrite()) throw VaultException("Folder access lost. Choose the vault folder again in Settings.")
        return root
    }

    private fun dir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
            ?: throw VaultException("Couldn't create “$name” in the vault folder.")

    private fun write(file: DocumentFile, bytes: ByteArray) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: throw VaultException("Couldn't write ${file.name}.")
    }

    private fun readBytes(file: DocumentFile): ByteArray? =
        runCatching { context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } }.getOrNull()

    private fun read(file: DocumentFile): String? = readBytes(file)?.decodeToString()

    // ---- notes ------------------------------------------------------------------------------

    fun mirror(treeUri: Uri, state: QuestState, zone: TimeZone): MirrorResult = mirror(root(treeUri), state, zone)

    fun mirror(rootDir: DocumentFile, state: QuestState, zone: TimeZone): MirrorResult {
        val folder = dir(rootDir, FOLDER)
        val backups = dir(folder, BACKUPS)
        val manifest = readManifest(backups)
        val existing = folder.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (!f.isFile || !name.endsWith(".md", ignoreCase = true)) return@mapNotNull null
            // An unreadable note would look foreign and push its quest to a suffixed name for
            // good; better to skip this run and retry than to fork the note.
            ExistingNote(f, name, readBytes(f) ?: throw VaultException("Couldn't read “$name” in the vault folder."))
        }

        // Unchanged since the app wrote it: ours to overwrite or delete.
        fun ours(e: ExistingNote): Boolean = e.hash != null && manifest[e.name] == e.hash

        // Names held by files we may not simply replace, with the quest id they carry (if any).
        val occupied: Map<String, String?> = existing.filterNot { ours(it) }.groupBy { it.key }
            .mapValues { (_, files) -> files.map { it.id }.distinct().singleOrNull() }
        val notes = VaultMarkdown.render(state, zone, occupied)
        val byKey = existing.groupBy { it.key }
        val newManifest = LinkedHashMap<String, String>()
        var written = 0
        var keptForeign = 0
        for (note in notes) {
            val bytes = note.content.toByteArray(Charsets.UTF_8)
            val hash = sha256(bytes)
            val matches = byKey[note.fileName.dropLast(3).lowercase()].orEmpty()
            val target = matches.firstOrNull { it.name == note.fileName } ?: matches.firstOrNull()
            if (target != null) {
                // names() already steps aside; this is the safety net: never clobber another file.
                if (!ours(target) && target.id != note.questId) {
                    keptForeign++
                    continue
                }
                val current = target.bytes
                if (current == null || !current.contentEquals(bytes)) {
                    write(target.file, bytes)
                    written++
                }
                newManifest[target.name] = hash
                continue
            }
            val file = folder.createFile("text/markdown", note.fileName.removeSuffix(".md")) ?: continue
            // Some providers append ".md" themselves, some don't: normalise the name.
            if (file.name != note.fileName && folder.findFile(note.fileName) == null) file.renameTo(note.fileName)
            write(file, bytes)
            written++
            newManifest[file.name ?: note.fileName] = hash
        }
        // Notes the app wrote under a name no longer wanted (renamed quest), still untouched.
        val wanted = notes.map { it.fileName.lowercase() }.toSet()
        var removed = 0
        for (e in existing) {
            if (e.name.lowercase() in wanted || e.name in newManifest || !ours(e)) continue
            if (e.file.delete()) removed++ else e.hash?.let { newManifest[e.name] = it }
        }
        if (newManifest != manifest) writeManifest(backups, newManifest)
        return MirrorResult(written, removed, keptForeign)
    }

    /** File name → SHA-256 of what the last mirror wrote. Missing or unreadable: empty (delete nothing). */
    private fun readManifest(backups: DocumentFile): Map<String, String> {
        val text = backups.findFile(MANIFEST)?.let { read(it) } ?: return emptyMap()
        return runCatching {
            val files = (CoreJson.parseToJsonElement(text) as? JsonObject)?.get("files") as? JsonObject
            files.orEmpty().mapNotNull { (name, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeManifest(backups: DocumentFile, files: Map<String, String>) {
        val json = buildJsonObject {
            put("version", 1)
            putJsonObject("files") { files.forEach { (name, hash) -> put(name, hash) } }
        }.toString()
        val file = backups.findFile(MANIFEST)
            ?: backups.createFile("application/octet-stream", MANIFEST)
            ?: throw VaultException("Couldn't create $MANIFEST.")
        if (file.name != MANIFEST && backups.findFile(MANIFEST) == null) file.renameTo(MANIFEST)
        write(file, json.toByteArray(Charsets.UTF_8))
    }

    // ---- snapshots --------------------------------------------------------------------------

    /**
     * Writes `Quests/.backups/snapshot-<date>.json`. An existing snapshot for that date is
     * replaced only if [json] holds every event it holds; otherwise the new one is written
     * next to it as `snapshot-<date>-<time>.json`. Returns the file name written.
     */
    fun writeSnapshot(treeUri: Uri, json: String, date: String, time: String = nowTime()): String =
        writeSnapshot(root(treeUri), json, date, time)

    fun writeSnapshot(rootDir: DocumentFile, json: String, date: String, time: String = nowTime()): String {
        val backups = dir(dir(rootDir, FOLDER), BACKUPS)
        recoverTemps(backups)
        val plain = "snapshot-$date.json"
        val current = backups.findFile(plain)
        val replace = current != null && supersedes(json, current)

        // 1. The whole file under a temporary name: a failed write never truncates a good copy.
        val tmp = backups.createFile("application/octet-stream", "$TMP_PREFIX$date-$time-${System.nanoTime()}")
            ?: throw VaultException("Couldn't create a backup file in “$BACKUPS”.")
        try {
            write(tmp, json.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        // 2. A unique snapshot name (renames never replace an existing file on SAF). If even
        //    that fails, the complete temp file becomes a snapshot on a later run.
        if (!tmp.renameTo(unique(backups, "snapshot-$date-$time"))) {
            throw VaultException("Couldn't name the backup file in “$BACKUPS”.")
        }
        // 3. Take the day's plain name when it is free, or held by a subset of this snapshot.
        if (current == null || (replace && current.delete())) tmp.renameTo(plain)
        val name = tmp.name.orEmpty()
        if (!name.matches(SNAPSHOT_NAME)) tmp.renameTo(unique(backups, "snapshot-$date-$time"))
        prune(backups)
        return tmp.name ?: name
    }

    /** True if [existing] is empty, or every event in it is also in [json]. */
    private fun supersedes(json: String, existing: DocumentFile): Boolean {
        val old = read(existing) ?: return false
        if (old.isBlank()) return true
        val oldIds = (SnapshotCodec.decode(old) as? SnapshotRead.Ok)?.snapshot?.events?.map { it.id } ?: return false
        val newIds = (SnapshotCodec.decode(json) as? SnapshotRead.Ok)?.snapshot?.events?.mapTo(HashSet()) { it.id } ?: return false
        return newIds.containsAll(oldIds)
    }

    private fun unique(backups: DocumentFile, base: String): String {
        var name = "$base.json"
        var n = 2
        while (backups.findFile(name) != null) name = "$base-${n++}.json"
        return name
    }

    /** Temp files left by an interrupted run: complete snapshots are kept, the rest removed. */
    private fun recoverTemps(backups: DocumentFile) {
        val cutoff = System.currentTimeMillis() - TEMP_GRACE_MS
        for (f in backups.listFiles()) {
            val name = f.name ?: continue
            if (!name.startsWith(TMP_PREFIX) || f.lastModified() > cutoff) continue
            val text = read(f) ?: continue
            if (SnapshotCodec.decode(text) is SnapshotRead.Ok) {
                // ".tmp-snapshot-<date>-<time>-<nanos>[.ext]" → "snapshot-<date>-<time>.json"
                val stamp = name.removePrefix(TMP_PREFIX).substringBeforeLast('-')
                if (Regex("""\d{4}-\d{2}-\d{2}-\d{6}""").matches(stamp)) f.renameTo(unique(backups, "snapshot-$stamp"))
            } else {
                f.delete()
            }
        }
    }

    /** Keeps the newest [KEEP] snapshots, plus the one with the most events if it is older. */
    private fun prune(backups: DocumentFile) {
        val snaps = backups.listFiles()
            .filter { it.name?.matches(SNAPSHOT_NAME) == true }
            .sortedByDescending { sortKey(it.name.orEmpty()) }
        if (snaps.size <= KEEP) return
        val counts = snaps.associateWith { f ->
            read(f)?.let { (SnapshotCodec.decode(it) as? SnapshotRead.Ok)?.snapshot?.events?.size } ?: -1
        }
        val keep = snaps.take(KEEP)
        val older = snaps.drop(KEEP)
        val keptMax = keep.maxOf { counts.getValue(it) }
        val biggest = older.maxByOrNull { counts.getValue(it) }
            ?.takeIf { counts.getValue(it) > 0 && counts.getValue(it) > keptMax }
        older.filter { it != biggest }.forEach { it.delete() }
    }

    companion object {
        const val FOLDER = "Quests"
        const val BACKUPS = ".backups"
        const val MANIFEST = ".mirror-manifest.json"
        const val KEEP = 8
        private const val TMP_PREFIX = ".tmp-snapshot-"
        private const val TEMP_GRACE_MS = 60 * 60 * 1000L

        /** `snapshot-<date>.json`, or `snapshot-<date>-<HHmmss>[-n].json` when a day has more than one. */
        val SNAPSHOT_NAME = Regex("""snapshot-(\d{4}-\d{2}-\d{2})(?:-(\d{6})(?:-(\d+))?)?\.json""")

        /** Date, then time (the plain daily name first within its day), then counter. */
        private fun sortKey(name: String): String {
            val m = SNAPSHOT_NAME.matchEntire(name) ?: return ""
            val (date, time, n) = m.destructured
            return "$date-${time.ifEmpty { "000000" }}-${n.ifEmpty { "0" }.padStart(6, '0')}"
        }

        private fun nowTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
    }
}
