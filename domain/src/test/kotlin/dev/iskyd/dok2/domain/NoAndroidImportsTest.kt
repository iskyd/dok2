package dev.iskyd.dok2.domain

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Enforces the hard module rule from AGENTS.md: `:domain` is pure Kotlin JVM and must contain zero
 * Android imports. This is what makes the replay harness a plain JVM test.
 *
 * Scans every `.kt` file under `domain/src/main` and fails if any contains an
 * `android.`/`androidx.` import, a fully-qualified `android.util.Log` use, or the `Context` type.
 * Do not weaken this check.
 */
class NoAndroidImportsTest {

    @Test
    fun `module has zero android imports`() {
        val root =
            listOf(File("src/main"), File("domain/src/main")).firstOrNull { it.isDirectory }
                ?: error("cannot locate the module's src/main directory")
        val offenders =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file -> hasAndroidUsage(file.readText()) }
                .map { it.path }
                .toList()
        assertThat(offenders).isEmpty()
    }

    private fun hasAndroidUsage(content: String): Boolean {
        if (content.contains("import android.") || content.contains("import androidx.")) return true
        if (content.contains("android.util.Log")) return true
        if (ANDROID_CONTEXT_REGEX.containsMatchIn(content)) return true
        return false
    }

    private companion object {
        val ANDROID_CONTEXT_REGEX = Regex("\\bContext\\b")
    }
}
