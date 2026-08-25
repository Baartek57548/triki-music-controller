package pl.trikimusic.controller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun `parses release tags and build suffixes`() {
        assertEquals(SemanticVersion(2, 3, 4), SemanticVersion.parse("v2.3.4"))
        assertEquals(SemanticVersion(2, 3, 4), SemanticVersion.parse("2.3.4-debug"))
        assertEquals(SemanticVersion(12, 34, 56), SemanticVersion.parse("V12.34.56+build.7"))
    }

    @Test
    fun `rejects incomplete malformed and overflowing versions`() {
        assertNull(SemanticVersion.parse("2.3"))
        assertNull(SemanticVersion.parse("release-2.3.4"))
        assertNull(SemanticVersion.parse("2.3.-1"))
        assertNull(SemanticVersion.parse("999999999999.1.1"))
    }

    @Test
    fun `compares every numeric component instead of lexicographic text`() {
        assertTrue(SemanticVersion(2, 10, 0) > SemanticVersion(2, 9, 99))
        assertTrue(SemanticVersion(3, 0, 0) > SemanticVersion(2, 99, 99))
        assertEquals(0, SemanticVersion(2, 3, 4).compareTo(SemanticVersion(2, 3, 4)))
    }
}
