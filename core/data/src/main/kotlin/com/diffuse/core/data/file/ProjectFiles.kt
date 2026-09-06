package com.diffuse.core.data.file

import java.io.File

/**
 * specs/persistence.md storage layout:
 * `filesDir/projects/<projectId>/{source.<ext>, document.json, thumb.png}`.
 */
class ProjectFiles(private val filesDir: File) {

    fun projectDir(id: String): File = File(File(filesDir, PROJECTS), id)

    fun documentFile(id: String): File = File(projectDir(id), "document.json")

    fun thumbFile(id: String): File = File(projectDir(id), "thumb.png")

    fun sourceFile(id: String, extension: String): File =
        File(projectDir(id), "source.$extension")

    /** specs/edit_model.md: one file per selection, named by the `Mask` op's id. */
    fun maskFile(projectId: String, maskId: String): File =
        File(projectDir(projectId), "mask_$maskId.png")

    /** The source keeps whatever extension it was written with; find it rather than guess. */
    fun findSource(id: String): File? =
        projectDir(id).listFiles { file -> file.nameWithoutExtension == "source" }?.firstOrNull()

    /**
     * specs/persistence.md: "Save is atomic: write document.json.tmp, then rename." A
     * half-written document is worse than a stale one, since the editor reloads from it.
     */
    fun writeAtomically(target: File, write: (File) -> Unit) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}$TEMP_SUFFIX")
        try {
            write(temporary)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val PROJECTS = "projects"
        const val TEMP_SUFFIX = ".tmp"
    }
}
