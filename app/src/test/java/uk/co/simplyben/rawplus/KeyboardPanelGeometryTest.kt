package uk.co.simplyben.rawplus

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPanelGeometryTest {
    @Test fun `short landscape constrains collapsed and expanded panels`() {
        // A requested 420px panel must not escape a 320px parent into navigation controls.
        assertEquals(320, KeyboardPanelGeometry.height(false, 420, 320, 48, exact = false))
        assertEquals(320, KeyboardPanelGeometry.height(true, 420, 320, 48, exact = false))
        assertEquals(320, KeyboardPanelGeometry.height(true, 420, 320, 48, exact = true))
        assertEquals(852, KeyboardPanelGeometry.height(true, 420, 900, 48, exact = false))
    }

    @Test fun `only navigation overlap not already reserved by ancestors becomes padding`() {
        val window = PanelBounds(0, 0, 500, 900)
        val navigation = PanelSafeInsets(0, 0, 40)
        assertEquals(PanelSafeInsets(0, 0, 40), KeyboardPanelGeometry.residual(
            PanelBounds(0, 480, 500, 900), window, navigation))
        assertEquals(PanelSafeInsets(0, 0, 0), KeyboardPanelGeometry.residual(
            PanelBounds(0, 440, 500, 860), window, navigation))
        assertEquals(PanelSafeInsets(0, 0, 10), KeyboardPanelGeometry.residual(
            PanelBounds(0, 450, 500, 870), window, navigation))
    }

    @Test fun `landscape side insets use shared window coordinates without doubling`() {
        val window = PanelBounds(20, 30, 1020, 430)
        val cutoutAndNavigation = PanelSafeInsets(60, 24, 0)
        assertEquals(PanelSafeInsets(60, 24, 0), KeyboardPanelGeometry.residual(
            PanelBounds(20, 110, 1020, 430), window, cutoutAndNavigation))
        assertEquals(PanelSafeInsets(0, 0, 0), KeyboardPanelGeometry.residual(
            PanelBounds(80, 110, 996, 430), window, cutoutAndNavigation))
    }
}
