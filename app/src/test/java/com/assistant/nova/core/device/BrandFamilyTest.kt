package com.assistant.nova.core.device

import com.assistant.nova.core.device.DeviceProfile.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandFamilyTest {

    @Test fun `phones are grouped by the settings they share`() {
        // Build.MANUFACTURER / Build.BRAND as real phones report them
        assertEquals(Family.ONEPLUS, DeviceProfile.familyOf("OnePlus", "OnePlus"))
        assertEquals(Family.ONEPLUS, DeviceProfile.familyOf("OPPO", "OPPO"))
        assertEquals(Family.ONEPLUS, DeviceProfile.familyOf("realme", "realme"))
        assertEquals(Family.SAMSUNG, DeviceProfile.familyOf("samsung", "samsung"))
        assertEquals(Family.XIAOMI, DeviceProfile.familyOf("Xiaomi", "Redmi"))
        assertEquals(Family.XIAOMI, DeviceProfile.familyOf("Xiaomi", "POCO"))
        assertEquals(Family.VIVO, DeviceProfile.familyOf("vivo", "iQOO"))
        assertEquals(Family.MOTOROLA, DeviceProfile.familyOf("motorola", "motorola"))
        assertEquals(Family.GOOGLE, DeviceProfile.familyOf("Google", "google"))
        assertEquals(Family.OTHER, DeviceProfile.familyOf("Nothing", "Nothing"))
    }

    @Test fun `every brand gets its own setup steps`() {
        for (f in Family.entries) {
            val (title, steps) = DeviceProfile.backgroundSteps(f)
            assertTrue(title.isNotBlank() && steps.length > 40)
            assertTrue(DeviceProfile.powerButtonSteps(f).isNotBlank())
        }
        assertTrue(DeviceProfile.backgroundSteps(Family.SAMSUNG).second.contains("Never sleeping apps"))
        assertTrue(DeviceProfile.backgroundSteps(Family.XIAOMI).second.contains("Autostart"))
    }
}
