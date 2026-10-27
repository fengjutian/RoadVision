package com.roadvision.roadsense.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyLogTest {
    @Test
    fun masksPlateForLogs() {
        assertEquals("粤******8", PrivacyLog.maskPlate("粤B12345" + "8"))
        assertEquals("**", PrivacyLog.maskPlate("A"))
    }
}
