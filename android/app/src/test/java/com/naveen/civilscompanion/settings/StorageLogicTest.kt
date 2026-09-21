package com.naveen.civilscompanion.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageLogicTest {
    @Test fun byteText() {
        assertEquals("0 B", StorageLogic.bytes(0))
        assertEquals("0 B", StorageLogic.bytes(-5))
        assertEquals("1023 B", StorageLogic.bytes(1023))
        assertEquals("2 KB", StorageLogic.bytes(2048))
        assertEquals("5.0 MB", StorageLogic.bytes(5L * 1024 * 1024))
        assertEquals("1.50 GB", StorageLogic.bytes(1_610_612_736L))
    }

    @Test fun limits() {
        assertEquals(20L * 1024 * 1024 * 1024, StorageLogic.limitBytes(20))
        assertEquals(5L * 1024 * 1024 * 1024, StorageLogic.limitBytes(1))
        assertEquals(5, StorageLogic.stepLimit(5, -5))
        assertEquals(200, StorageLogic.stepLimit(200, 5))
        assertEquals(25, StorageLogic.stepLimit(20, 5))
        assertEquals(0.5f, StorageLogic.fraction(10L * 1024 * 1024 * 1024, 20), 0.001f)
        assertEquals(1f, StorageLogic.fraction(500L * 1024 * 1024 * 1024, 20), 0f)
    }

    @Test fun partsDropEmptyOnes() {
        val parts = StorageLogic.parts(100, 0, 50, 0, 7)
        assertEquals(listOf("Audio", "Study data", "Backups"), parts.map { it.label })
    }

    @Test fun backupNamesAndPercent() {
        assertEquals("21 Sep 2026, 03:00", StorageLogic.backupLabel("civils-backup-20260921-030000.tar.gz"))
        assertEquals("other.txt", StorageLogic.backupLabel("other.txt"))
        assertEquals("34%", StorageLogic.percent(0.345))
        assertEquals("100%", StorageLogic.percent(3.0))
    }
}
