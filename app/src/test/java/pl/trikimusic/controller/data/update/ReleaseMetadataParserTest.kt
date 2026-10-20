package pl.trikimusic.controller.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import pl.trikimusic.controller.domain.model.SemanticVersion

class ReleaseMetadataParserTest {
    private val installed = requireNotNull(SemanticVersion.parse("3.1.6"))
    private val assetName = "triki-music-controller-android-v3.1.7-release.apk"
    private val repository = "https://github.com/Baartek57548/triki-music-controller"

    private fun metadata(digest: String = "sha256:" + "a".repeat(64), tag: String = "v3.1.7", extraAsset: String = ""): String =
        """{"tag_name":"$tag","html_url":"$repository/releases/tag/$tag","assets":[
            {"name":"$assetName","state":"uploaded","size":1024,"digest":"$digest",
            "browser_download_url":"$repository/releases/download/$tag/$assetName"}$extraAsset]}"""

    @Test
    fun `signed canonical APK is selected even when an unsigned artifact is present`() {
        val extra = """,{"name":"app-release-unsigned.apk","state":"uploaded","size":1000,
            "browser_download_url":"$repository/releases/download/v3.1.7/app-release-unsigned.apk"}"""
        val result = requireNotNull(ReleaseMetadataParser.parse(metadata(extraAsset = extra), installed))
        assertEquals(assetName, result.apkFileName)
        assertEquals("a".repeat(64), result.apkSha256)
    }

    @Test
    fun `missing or malformed digest is rejected`() {
        for (digest in listOf("", "sha256:bad", "sha1:" + "a".repeat(64))) {
            assertThrows(UpdateException::class.java) { ReleaseMetadataParser.parse(metadata(digest), installed) }
        }
    }

    @Test
    fun `foreign repository URL is rejected`() {
        val raw = metadata().replace("/releases/download/", "/other/download/")
        assertThrows(UpdateException::class.java) { ReleaseMetadataParser.parse(raw, installed) }
    }

    @Test
    fun `unsigned only release cannot be offered`() {
        assertThrows(UpdateException::class.java) {
            ReleaseMetadataParser.parse(metadata().replace(assetName, "app-release-unsigned.apk"), installed)
        }
    }

    @Test
    fun `installed version is not offered again`() {
        assertNull(ReleaseMetadataParser.parse(metadata(tag = "v3.1.6"), installed))
    }
}
