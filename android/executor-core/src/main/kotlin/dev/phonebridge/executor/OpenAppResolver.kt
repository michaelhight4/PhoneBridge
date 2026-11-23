package dev.phonebridge.executor

/**
 * Resolves the `open_app` argument (a package name or a human label) to a launchable package.
 *
 * Matching order, all case-insensitive for labels:
 * 1. exact package name,
 * 2. exact label,
 * 3. unique label prefix,
 * 4. unique label substring.
 *
 * Anything ambiguous is reported as such instead of guessing, so the bot gets a `bad_args` error
 * listing the candidates and can ask again with a package name.
 */
object OpenAppResolver {

    sealed interface Result {
        data class Found(val packageName: String) : Result
        data class Ambiguous(val candidates: List<String>) : Result
        data object NotFound : Result
    }

    fun resolve(query: String, apps: List<InstalledApp>): Result {
        val needle = query.trim()
        if (needle.isEmpty()) return Result.NotFound

        apps.firstOrNull { it.packageName.equals(needle, ignoreCase = true) }
            ?.let { return Result.Found(it.packageName) }

        val byLabel = apps.filter { it.label.equals(needle, ignoreCase = true) }
        when (byLabel.size) {
            1 -> return Result.Found(byLabel[0].packageName)
            0 -> Unit
            else -> return Result.Ambiguous(byLabel.map { it.packageName }.sorted())
        }

        val prefixed = apps.filter { it.label.startsWith(needle, ignoreCase = true) }
        when (prefixed.size) {
            1 -> return Result.Found(prefixed[0].packageName)
            0 -> Unit
            else -> {
                // If every candidate has the same label the choice is still obvious.
                val labels = prefixed.map { it.label.lowercase() }.distinct()
                if (labels.size == 1) return Result.Found(prefixed[0].packageName)
                return Result.Ambiguous(prefixed.map { it.packageName }.sorted())
            }
        }

        val contained = apps.filter { it.label.contains(needle, ignoreCase = true) }
        return when (contained.size) {
            0 -> Result.NotFound
            1 -> Result.Found(contained[0].packageName)
            else -> Result.Ambiguous(contained.map { it.packageName }.sorted())
        }
    }
}
