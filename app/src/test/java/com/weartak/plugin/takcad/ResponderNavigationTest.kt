// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class ResponderNavigationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun listSelectionAndPagingAreBounded() {
        val store = IncidentStore(folder.newFile())
        val navigation = ResponderNavigation(store)
        assertNull(navigation.snapshot().incident)
        assertThrows(IllegalArgumentException::class.java) { navigation.select("missing") }
        store.receive(ASK, 1)
        store.receive(ASK.copy(id = "second"), 2)
        navigation.pageIncidents(1)
        assertEquals(1, navigation.snapshot().incidentOffset)
        assertThrows(IllegalArgumentException::class.java) { navigation.pageIncidents(Int.MAX_VALUE) }
        navigation.select(ASK.id)
        assertEquals(ASK, navigation.snapshot().incident?.ask)
        navigation.back()
        assertNull(navigation.snapshot().incident)
    }

    @Test fun instructionsResetOnNewRouteAndCannotOverflow() {
        val store = IncidentStore(folder.newFile())
        store.receive(ASK, 1)
        store.recordDispatch(ASK.id, 0, Route.parse(ROUTE), 2)
        val navigation = ResponderNavigation(store)
        navigation.select(ASK.id)
        navigation.step(1)
        assertEquals(1, navigation.snapshot().instructionIndex)
        assertThrows(IllegalArgumentException::class.java) { navigation.step(1) }
        store.recordDispatch(ASK.id, 1, Route.parse(ROUTE.replace("Turn right", "Turn left")), 3)
        assertEquals(0, navigation.snapshot().instructionIndex)
    }

    @Test fun responderPagingRequiresSelectedIncidentAndKeepsUidIdentity() {
        val store = IncidentStore(folder.newFile())
        store.receive(ASK, 1)
        val navigation = ResponderNavigation(store)
        assertThrows(IllegalArgumentException::class.java) { navigation.pageResponders(1) }
        navigation.select(ASK.id)
        assertThrows(IllegalArgumentException::class.java) { navigation.pageResponders(1) }
        for (uid in listOf("one", "two")) {
            store.receive(CadEvent.Response(ASK.id, uid, "Same callsign", 0, Route.parse(ROUTE), ASK), 2)
        }
        navigation.pageResponders(1)
        assertEquals(1, navigation.snapshot().responderOffset)
    }
}
