package com.smartcampus.door.core

import com.smartcampus.door.core.state.emergencyPresentation
import kotlin.test.Test
import kotlin.test.assertEquals

class EmergencyPresentationTest {
    @Test
    fun `each kind has an order and three steps`() {
        for (kind in listOf("fire", "biohazard", "nuclear", "bomb", "seismic", "power")) {
            val presentation = emergencyPresentation(kind)
            assertEquals(3, presentation.steps.size, kind)
            assert(presentation.headline.isNotBlank()) { kind }
        }
    }

    @Test
    fun `uses the same words as the Faculty screens`() {
        assertEquals("EVACUATE NOW", emergencyPresentation("fire").headline)
        assertEquals("DROP, COVER, HOLD", emergencyPresentation("seismic").headline)
        assertEquals("Use the stairs", emergencyPresentation("fire").steps.first().title)
    }

    @Test
    fun `an unknown kind from a newer server is told to evacuate`() {
        assertEquals("EVACUATE NOW", emergencyPresentation("meteor").headline)
    }
}
