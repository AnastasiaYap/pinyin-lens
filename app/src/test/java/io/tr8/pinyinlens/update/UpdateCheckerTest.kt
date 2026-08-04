package io.tr8.pinyinlens.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `later patch is newer`() {
        assertTrue(UpdateChecker.isNewer("0.3.1", "0.3.0"))
        assertFalse(UpdateChecker.isNewer("0.3.0", "0.3.1"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("0.3.1", "0.3.1"))
    }

    @Test
    fun `compares numerically, not as strings`() {
        // The trap: "0.10.0" sorts before "0.9.0" as text, so a string
        // comparison would never offer the upgrade.
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.10.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.99"))
    }

    @Test
    fun `tolerates a v prefix from the tag`() {
        assertTrue(UpdateChecker.isNewer("v0.4.0", "0.3.1"))
        assertFalse(UpdateChecker.isNewer("v0.3.1", "0.3.1"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
        assertTrue(UpdateChecker.isNewer("2", "1.9.9"))
    }

    @Test
    fun `ignores suffixes after the number`() {
        assertTrue(UpdateChecker.isNewer("0.4.0-beta", "0.3.1"))
        assertFalse(UpdateChecker.isNewer("0.3.1-rc1", "0.3.1"))
    }

    @Test
    fun `garbage does not offer an update`() {
        assertFalse(UpdateChecker.isNewer("", "0.3.1"))
        assertFalse(UpdateChecker.isNewer("not-a-version", "0.3.1"))
    }
}
