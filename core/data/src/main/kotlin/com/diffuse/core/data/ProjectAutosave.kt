package com.diffuse.core.data

import com.diffuse.core.imaging.model.EditDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/** specs/persistence.md: 2s after the last operation. */
const val AUTOSAVE_DEBOUNCE_MS = 2_000L

/**
 * specs/persistence.md autosave. Debouncing lives here rather than in the ViewModel so the
 * timing is testable with virtual time and cannot drift per screen.
 */
class ProjectAutosave(
    private val repository: ProjectRepository,
    private val debounceMillis: Long = AUTOSAVE_DEBOUNCE_MS,
) {

    private var everSaved = false

    /** Collects until cancelled; each quiet period of [debounceMillis] triggers one save. */
    suspend fun run(documents: Flow<EditDocument>) {
        documents.debounce(debounceMillis).collect { document -> saveNow(document) }
    }

    /** specs/persistence.md: `onStop` saves immediately rather than waiting out the debounce. */
    suspend fun saveNow(document: EditDocument) {
        repository.save(document)
        everSaved = true
    }

    /**
     * specs/persistence.md: "a project whose document has zero ops **and** was never saved
     * is deleted on editor exit" — otherwise opening and backing out litters Browse.
     */
    suspend fun discardIfUntouched(document: EditDocument): Boolean {
        if (everSaved || document.operations.isNotEmpty()) return false
        repository.delete(document.id)
        return true
    }
}
