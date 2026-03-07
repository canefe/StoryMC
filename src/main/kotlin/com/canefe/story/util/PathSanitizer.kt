package com.canefe.story.util

import java.io.File

object PathSanitizer {
    /**
     * Sanitizes a name used to construct file paths, preventing path traversal attacks.
     * Removes path separators and traversal sequences, keeping only safe characters.
     */
    fun sanitizeFileName(name: String): String =
        name
            .replace("..", "")
            .replace("/", "")
            .replace("\\", "")
            .replace(File.separator, "")
            .trim()

    /**
     * Validates that a resolved file is still within the expected base directory.
     * Returns the file if safe, or null if it escapes the base directory.
     */
    fun safeResolve(
        baseDir: File,
        relativePath: String,
        extension: String = ".yml",
    ): File? {
        val file = File(baseDir, "$relativePath$extension")
        val canonicalBase = baseDir.canonicalPath
        val canonicalFile = file.canonicalPath
        return if (canonicalFile.startsWith(canonicalBase)) file else null
    }
}
