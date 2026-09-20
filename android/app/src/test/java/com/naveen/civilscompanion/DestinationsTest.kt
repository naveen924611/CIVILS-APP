package com.naveen.civilscompanion

import com.naveen.civilscompanion.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsTest {
    @Test
    fun railHasTheTenItemsInSpecOrder() {
        val labels = Destination.entries.map { it.label }
        assertEquals(
            listOf("Today", "Briefs", "Library", "Read", "Notes", "Revise", "Ask", "Focus", "Alerts", "Settings"),
            labels,
        )
    }

    @Test
    fun routesAreUnique() {
        val routes = Destination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it == it.lowercase() })
    }
}
