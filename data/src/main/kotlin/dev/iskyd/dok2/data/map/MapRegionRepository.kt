package dev.iskyd.dok2.data.map

import dev.iskyd.dok2.data.prefs.SettingsRepository
import dev.iskyd.dok2.domain.map.PmtilesValidationResult
import dev.iskyd.dok2.domain.map.PmtilesValidator
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The outcome of importing a map region file. */
sealed interface MapRegionImportResult {

    /** The region was validated and copied successfully; [fileName] is the stored file name. */
    data class Imported(val fileName: String) : MapRegionImportResult

    /** The region was refused before anything was written; [reason] explains why. */
    data class Rejected(val reason: String) : MapRegionImportResult

    /** The import failed part-way; [error] explains why. No partial file is left behind. */
    data class Failed(val error: String) : MapRegionImportResult
}

/**
 * Validates, stores and removes the single active offline map region in `filesDir/maps/`.
 *
 * Pure JVM: it takes a [File] directory and an [InputStream], so the whole pipeline (header
 * validation, copy, partial-copy cleanup) is unit-testable without Android. The `:app` layer
 * resolves `File(context.filesDir, "maps")` and opens the content stream at wiring time.
 *
 * Note: [activeFileName] and [activeFile] are suspend (the plan drafted them non-suspend) because
 * [SettingsRepository] exposes only its `settingsFlow` with no synchronous getter — resolving the
 * current name requires a `settingsFlow.first()`.
 */
class MapRegionRepository(
    private val mapsDir: File,
    private val validator: PmtilesValidator,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Validates [input]'s PMTiles header, then copies the remainder of the stream to [mapsDir]
     * under a sanitized version of [displayName]. Nothing is written before the header validates,
     * and the setting is only updated after a complete copy, so a failed import never leaves a
     * partial file or a stale setting.
     *
     * Replacing the active region deletes the previous file only after the new copy has completed.
     */
    suspend fun import(displayName: String, input: InputStream): MapRegionImportResult =
        withContext(Dispatchers.IO) {
            if (!mapsDir.mkdirs() && !mapsDir.isDirectory) {
                return@withContext MapRegionImportResult.Failed("could not create map directory")
            }
            val sanitizedName = sanitizeFileName(displayName)
            if (sanitizedName == null) {
                return@withContext MapRegionImportResult.Rejected(
                    "invalid file name: keep only letters, digits, '.', '_' and '-'"
                )
            }
            // The validator consumes exactly the 127-byte header from the stream; rewind before
            // copying so the stored file is the complete archive (header + remainder) that MapLibre
            // can load, not a headerless tail. BufferedInputStream guarantees mark/reset even for a
            // content stream that does not support it.
            val buffered = BufferedInputStream(input)
            buffered.mark(PMTILES_HEADER_SIZE + 1)
            val validation = validator.validateHeader(buffered)
            if (validation is PmtilesValidationResult.Invalid) {
                return@withContext MapRegionImportResult.Rejected(validation.reason)
            }
            buffered.reset()
            val target = File(mapsDir, sanitizedName)
            try {
                FileOutputStream(target).use { output -> buffered.copyTo(output) }
            } catch (error: IOException) {
                // Never leave a partial copy for the startup existence check to find.
                deleteQuietly(target)
                return@withContext MapRegionImportResult.Failed(
                    "could not copy map file: ${error.message}"
                )
            }
            val previousName = settingsRepository.settingsFlow.first().activeMapFileName
            try {
                settingsRepository.setActiveMapFileName(sanitizedName)
            } catch (error: IOException) {
                // The copy succeeded but the setting could not be persisted (e.g. disk-full on a
                // DataStore write). Drop the copied file and keep the previous region untouched:
                // a failed import must not leave a file the startup existence check could pick up.
                deleteQuietly(target)
                return@withContext MapRegionImportResult.Failed(
                    "could not save map settings: ${error.message}"
                )
            }
            if (previousName != null && previousName != sanitizedName) {
                deleteQuietly(File(mapsDir, previousName))
            }
            MapRegionImportResult.Imported(sanitizedName)
        }

    /** The active region's file name from settings, or null when no region is configured. */
    suspend fun activeFileName(): String? =
        settingsRepository.settingsFlow.first().activeMapFileName

    /**
     * The active region file when it still exists on disk, else null. This is the startup existence
     * check: a setting pointing at a deleted file reads as "no region" rather than crashing.
     */
    suspend fun activeFile(): File? {
        val name = activeFileName() ?: return null
        return File(mapsDir, name).takeIf { it.isFile }
    }

    /** Deletes the active region file if present and clears the setting. */
    suspend fun remove() {
        withContext(Dispatchers.IO) {
            val name = settingsRepository.settingsFlow.first().activeMapFileName
            if (name != null) deleteQuietly(File(mapsDir, name))
            try {
                settingsRepository.setActiveMapFileName(null)
            } catch (error: IOException) {
                // The file is already gone; the stale setting reads as "no region" via the
                // isFile re-check in activeFile, so the next screen visit self-heals.
            }
        }
    }

    /**
     * Reduces [displayName] to a safe file name: keeps only letters, digits, '.', '_' and '-'
     * (stripping slashes, spaces and everything else), refuses names that are empty, contain ".."
     * or start with '.', and enforces the `.pmtiles` suffix. Returns null when the name is
     * unusable. A sanitized name contains no path separators, so it cannot escape [mapsDir].
     */
    private fun sanitizeFileName(displayName: String): String? {
        val sanitized = displayName.filter { it in VALID_FILE_NAME_CHARS }
        return when {
            sanitized.isEmpty() -> null
            ".." in sanitized -> null
            sanitized.startsWith(".") -> null
            sanitized.endsWith(PMTILES_SUFFIX, ignoreCase = true) -> sanitized
            else -> sanitized + PMTILES_SUFFIX
        }
    }

    /** Deletes a file, ignoring failure — a leftover is harmless because [activeFile] re-checks. */
    private fun deleteQuietly(file: File) {
        file.delete()
    }

    private companion object {
        const val VALID_FILE_NAME_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
        const val PMTILES_SUFFIX = ".pmtiles"
        const val PMTILES_HEADER_SIZE = 127
    }
}
