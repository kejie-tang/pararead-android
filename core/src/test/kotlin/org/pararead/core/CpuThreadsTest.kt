package org.pararead.core

import kotlin.test.*
import org.junit.Test

class CpuThreadsTest {
    @Test fun automaticSizingReservesCapacityOnLargerCpus() {
        assertEquals(1, CpuThreads.resolve(0, 1))
        assertEquals(4, CpuThreads.resolve(0, 4))
        assertEquals(6, CpuThreads.resolve(0, 8))
        assertEquals(9, CpuThreads.resolve(0, 12))
    }
    @Test fun manualSettingSurvivesAChangeInAvailableCpuCount() {
        assertEquals(6, CpuThreads.resolve(6, 8))
        assertEquals(8, CpuThreads.resolve(8, 8))
        assertEquals(4, CpuThreads.resolve(6, 4))
        assertEquals(6, CpuThreads.resolve(6, 8))
    }
    @Test fun invalidCountsCannotReachTheNativeEngine() {
        assertFailsWith<IllegalArgumentException> { CpuThreads.resolve(-1, 8) }
        assertEquals(1, CpuThreads.resolve(0, 0))
        assertEquals(8, CpuThreads.resolve(Int.MAX_VALUE, 8))
    }
}
