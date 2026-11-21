package io.github.nanomuse.sysfiles

import java.io.File

/** Metadata snapshot for a single regular file used to skip redundant disk reloads. */
data class FileStamp(
    val exists: Boolean,
    val length: Long,
    val lastModifiedMs: Long,
)

/** Metadata snapshot for one skill directory entry (`<id>/SKILL.md`). */
data class SkillDirEntryStamp(
    val id: String,
    val skillMd: FileStamp,
)

/**
 * Lightweight filesystem stat helpers used to verify whether disk-backed prompt fragments or
 * registries changed out-of-band without reading or parsing unchanged files.
 */
object DiskFreshness {
    fun fileStamp(file: File): FileStamp =
        if (file.isFile) FileStamp(exists = true, length = file.length(), lastModifiedMs = file.lastModified())
        else FileStamp(exists = false, length = -1L, lastModifiedMs = -1L)

    fun fileStamps(files: List<File>): List<FileStamp> = files.map(::fileStamp)

    fun skillsDirStamp(skillsDir: File): List<SkillDirEntryStamp> {
        val dirs = skillsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs
            .map { dir -> SkillDirEntryStamp(dir.name, fileStamp(File(dir, "SKILL.md"))) }
            .sortedBy { it.id }
    }
}
