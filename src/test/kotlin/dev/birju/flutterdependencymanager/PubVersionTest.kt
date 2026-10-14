package dev.birju.flutterdependencymanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PubVersionTest {

    @Test
    fun ordering() {
        assertTrue(PubVersion.isOutdated("1.2.3", "1.2.4"))
        assertTrue(PubVersion.isOutdated("^1.2.3", "1.3.0"))
        assertTrue(PubVersion.isOutdated("^1.9.0", "1.10.0")) // numeric, not lexical
        assertFalse(PubVersion.isOutdated("2.0.0", "2.0.0"))
        assertFalse(PubVersion.isOutdated("2.1.0", "2.0.9"))
    }

    @Test
    fun prereleases() {
        assertTrue(PubVersion.isOutdated("1.0.0-beta.1", "1.0.0")) // release beats its prerelease
        assertFalse(PubVersion.isOutdated("2.0.0-dev.5", "1.9.9")) // ahead of stable stays quiet
        assertTrue(PubVersion.isOutdated("1.0.0-beta.1", "1.0.0-beta.2"))
        assertTrue(PubVersion.isOutdated("1.0.0-beta.9", "1.0.0-beta.10"))
    }

    @Test
    fun buildMetadataIgnored() {
        assertFalse(PubVersion.isOutdated("1.2.3+4", "1.2.3+9"))
        assertTrue(PubVersion.isOutdated("1.2.3+4", "1.2.4"))
    }

    @Test
    fun updateTypes() {
        assertEquals(UpdateType.MAJOR, PubVersion.updateType("^1.2.3", "2.0.0"))
        assertEquals(UpdateType.MINOR, PubVersion.updateType("^1.2.3", "1.5.0"))
        assertEquals(UpdateType.PATCH, PubVersion.updateType("^1.2.3", "1.2.9"))
        assertEquals(UpdateType.PATCH, PubVersion.updateType("1.0.0-beta.1", "1.0.0"))
        assertNull(PubVersion.updateType("^1.2.3", "1.2.3"))
    }

    @Test
    fun belowOneShiftsLikeCaret() { // pub: ^0.2.0 allows <0.3.0, ^0.0.3 allows <0.1.0
        assertEquals(UpdateType.MAJOR, PubVersion.updateType("^0.2.0", "0.5.0"))
        assertEquals(UpdateType.MAJOR, PubVersion.updateType("^0.2.0", "1.0.0"))
        assertEquals(UpdateType.MINOR, PubVersion.updateType("^0.2.0", "0.2.5"))
        assertEquals(UpdateType.MINOR, PubVersion.updateType("^0.0.3", "0.0.4"))
        assertEquals(UpdateType.MAJOR, PubVersion.updateType("^0.0.3", "0.1.0"))
    }

    @Test
    fun unparseableNeverWarns() {
        assertNull(PubVersion.parse("any"))
        assertNull(PubVersion.parse(">=1.0.0 <2.0.0"))
        assertFalse(PubVersion.isOutdated("any", "9.9.9"))
        assertFalse(PubVersion.isOutdated("1.2.3", "not-a-version"))
    }
}
