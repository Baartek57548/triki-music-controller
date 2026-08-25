package pl.trikimusic.controller.domain.model

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val apkSha256: String?,
)

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val versionPattern = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+][0-9A-Za-z.-]+)?$")

        fun parse(value: String): SemanticVersion? {
            val match = versionPattern.matchEntire(value.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemanticVersion(major, minor, patch)
        }
    }
}
