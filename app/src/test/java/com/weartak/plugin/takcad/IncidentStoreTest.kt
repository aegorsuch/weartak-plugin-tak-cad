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
class IncidentStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun response(version: Int, uid: String = "watch") =
        CadEvent.Response(ASK.id, uid, "Unit", version, Route.parse(ROUTE), ASK)

    @Test fun deduplicatesVersionsByUidNotCallsign() {
        val store = IncidentStore(folder.newFile())
        store.receive(ASK, 1)
        assertTrue(store.receive(response(2), 100))
        assertFalse(store.receive(response(2), 200))
        assertFalse(store.receive(response(1), 300))
        assertTrue(store.receive(response(0, "another-watch"), 400))
        val responders = store.snapshot().single().responders
        assertEquals(2, responders.size)
        assertEquals(100L, responders.getValue("watch").receivedAt)
    }

    @Test fun persistenceKeepsParticipationAndRoutes() {
        val file = folder.newFile()
        val store = IncidentStore(file)
        store.receive(ASK, 1)
        store.recordDispatch(ASK.id, 0, Route.parse(ROUTE), 2)
        store.receive(response(0), 3)
        val restored = IncidentStore(file)
        restored.load()
        assertEquals(store.snapshot(), restored.snapshot())
        assertEquals(Participation.RESPONSE_QUEUED, restored.snapshot().single().participation)
        assertThrows(IllegalArgumentException::class.java) { restored.decline(ASK.id) }
    }

    @Test fun duplicateAsksDoNotUndoDeclineOrAcceptance() {
        val store = IncidentStore(folder.newFile())
        store.receive(ASK, 1)
        store.decline(ASK.id)
        store.receive(ASK.copy(summary = "Updated"), 2)
        assertEquals(Participation.DECLINED, store.snapshot().single().participation)
        assertThrows(IllegalArgumentException::class.java) { store.recordDispatch(ASK.id, 0, Route.parse(ROUTE), 3) }
    }

    @Test fun responseDoesNotInventAskAndUpdateCannotInventIncident() {
        val store = IncidentStore(folder.newFile())
        assertFalse(store.receive(response(1).copy(incident = null), 1))
        store.receive(response(0), 2)
        assertEquals(Participation.OBSERVED, store.snapshot().single().participation)
        store.receive(ASK, 3)
        assertEquals(Participation.ASKED, store.snapshot().single().participation)
    }

    @Test fun corruptStorageIsExplicitErrorNotEmptySuccess() {
        val file = folder.newFile().apply { writeText("broken") }
        assertThrows(org.json.JSONException::class.java) { IncidentStore(file).load() }
        assertEquals("broken", file.readText())
    }

    @Test fun changedDestinationInvalidatesOldInstructionsWithoutReaccepting() {
        val store = IncidentStore(folder.newFile())
        store.receive(ASK, 1)
        store.recordDispatch(ASK.id, 0, Route.parse(ROUTE), 2)
        store.receive(ASK.copy(position = Position(42.0, -88.0)), 3)
        val incident = store.snapshot().single()
        assertNull(incident.ownRoute)
        assertEquals(0, incident.ownVersion)
        assertEquals(Participation.RESPONSE_QUEUED, incident.participation)
    }
}
