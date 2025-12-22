package ai.flovyn.sdk.common

/**
 * Semantic version following MAJOR.MINOR.PATCH format.
 *
 * @property major Major version - incremented for incompatible API changes
 * @property minor Minor version - incremented for backward-compatible functionality
 * @property patch Patch version - incremented for backward-compatible bug fixes
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {

    init {
        require(major >= 0) { "Major version must be non-negative" }
        require(minor >= 0) { "Minor version must be non-negative" }
        require(patch >= 0) { "Patch version must be non-negative" }
    }

    override fun toString(): String = "$major.$minor.$patch"

    override fun compareTo(other: SemanticVersion): Int {
        return compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    }

    companion object {
        val DEFAULT = SemanticVersion(1, 0, 0)

        /**
         * Parse a semantic version string.
         *
         * @throws IllegalArgumentException if the string is not a valid semantic version
         */
        fun parse(version: String): SemanticVersion {
            val parts = version.split(".")
            require(parts.size == 3) { "Invalid semantic version: $version" }
            return SemanticVersion(
                major = parts[0].toIntOrNull() ?: error("Invalid major version: ${parts[0]}"),
                minor = parts[1].toIntOrNull() ?: error("Invalid minor version: ${parts[1]}"),
                patch = parts[2].toIntOrNull() ?: error("Invalid patch version: ${parts[2]}")
            )
        }
    }
}
