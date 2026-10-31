// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Application
import android.os.Bundle
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.DeviceStatusParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CanonicalIncidentTest {
    @get:Rule val folder = TemporaryFolder()
    private fun record(uid: String = "incident", name: String = "Exercise", status: String = "ACTIVE") =
        JSONObject().put("uid", uid).put("incidentName", name).put("status", status)
            .put("details", "Synthetic details <north>")
            .put("location", JSONObject().put("coords", JSONObject().put("latitudeDeg", 38).put("longitudeDeg", -77)))

    private fun xml(record: JSONObject) =
        "<event><detail><__takCadIncidentDetails>${android.text.TextUtils.htmlEncode(record.toString())}" +
            "</__takCadIncidentDetails></detail></event>"

    @Test fun canonicalDetailNeedsNoInventedEventTypeOrHowAndPersists() {
        val data = record().put("incidentType", JSONObject().put("name", "Medical"))
            .put("requestedCallsigns", JSONArray().put("SELF"))
        data.getJSONObject("location").put("address", JSONObject().put("streetName", "Exercise Road")
            .put("city", "Training City"))
        val event = CadProtocol.parse(xml(data)) as CadEvent.Ask
        assertEquals(Position(38.0, -77.0), event.position)
        assertEquals("Medical", event.canonical!!.type)
        assertEquals("Exercise Road, Training City",
            Incident(event).detailSections().single { it.first == "Location" }.second)
        val file = folder.newFile()
        val store = IncidentStore(file)
        store.receive(event, 1)
        val restored = IncidentStore(file)
        restored.load()
        assertEquals(event, restored.snapshot().single().ask)
        assertThrows(IllegalArgumentException::class.java) {
            CadProtocol.response(event, "uid", "SELF", 0, Route.parse(ROUTE), 1, Position(38.0, -77.0))
        }
    }

    @Test fun getIncidentsArrayAndMissingLocationAreSupportedWithoutInventedCoordinates() {
        val data = record().put("location", JSONObject.NULL)
        val incident = CanonicalIncident.parseList(JSONArray().put(data).toString()).single()
        assertNull(incident.position)
        assertFalse(Incident(incident.ask()).detailSections().any { it.second.contains("0.0, 0.0") })
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIncident.parseList(JSONArray().put(data).put(data).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIncident.parse(record().put("status", "UNKNOWN").toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIncident.parse(record().put("location", JSONObject().put("coords",
                JSONObject().put("latitudeDeg", 100).put("longitudeDeg", 0))).toString())
        }
    }

    @Test fun identicalIncidentNameAndNarrativeAppearOnlyOnce() {
        val incident = CanonicalIncident.parse(record(name = "Exercise")
            .put("details", "Exercise").toString())
        val sections = Incident(incident.ask()).detailSections()
        assertEquals(listOf("Active Incident", "Location"), sections.map { it.first })
        assertEquals("Exercise\nACTIVE", sections.first().second)
    }

    @Test fun requestsMatchExactSelfCallsignOrVehicleUidButNeverBlankIdentity() {
        val incident = CanonicalIncident.parse(record().put("requestedCallsigns", JSONArray().put("SELF"))
            .put("vehicleUidsRequested", JSONArray().put("vehicle-1")).toString())
        assertEquals("Requested", incident.involvement("SELF", ""))
        assertEquals("Requested", incident.involvement("", "vehicle-1"))
        assertNull(incident.involvement("self", ""))
        assertNull(incident.involvement("", ""))
    }

    @Test fun activeResponseMatchesCurrentIncidentAndOnSceneOrInRouteStatus() {
        for (status in listOf("IN_ROUTE", "ON_SCENE", "AVAILABLE")) {
            val vehicle = JSONObject().put("uid", "v").put("callsign", "SELF").put("name", "Engine")
                .put("status", status).put("currentVehicleResponses", JSONArray().put(
                    JSONObject().put("incidentUid", "incident").put("responseStatus", status)))
            val incident = CanonicalIncident.parse(record().put("vehiclesResponding", JSONArray().put(
                JSONObject().put("vehicle", vehicle).put("eta", 120))).toString())
            assertEquals(if (status == "AVAILABLE") null else "Responding", incident.involvement("SELF", ""))
            assertEquals(if (status == "AVAILABLE") null else "Responding", incident.involvement("", "v"))
            assertNull(incident.involvement("OTHER", "other"))
            assertTrue(Incident(incident.ask()).detailSections().single { it.first == "Responders" }.second.contains("2 min"))
        }
        val vehicle = JSONObject().put("uid", "v").put("callsign", "SELF").put("status", "IN_ROUTE")
            .put("currentVehicleResponses", JSONArray().put(
                JSONObject().put("incidentUid", "different").put("responseStatus", "IN_ROUTE")))
        val incident = CanonicalIncident.parse(record().put("vehiclesResponding", JSONArray().put(
            JSONObject().put("vehicle", vehicle))).toString())
        assertNull(incident.involvement("SELF", "v"))
    }

    @Test fun personnelMetadataShowsOnlyVerifiedUidAndArrivalTime() {
        val incident = CanonicalIncident.parse(record().put("personnelResponding", JSONArray().put(
            JSONObject().put("uid", "person-1").put("arrivalTime", "2026-10-30T12:00:00Z"))).toString())
        val details = Incident(incident.ask()).detailSections().single { it.first == "Responders" }.second
        assertTrue(details.contains("Personnel | person-1 | 2026-10-30T12:00:00Z"))
        assertNull(incident.involvement("person-1", ""))
    }

    @Test fun notesAppearNewestFirstByInstantNotTimezoneString() {
        val notes = JSONArray().put(JSONObject().put("uid", "old").put("timestamp", "2026-10-30T12:00:00Z")
            .put("creator", "DISPATCH").put("info", "Old note"))
            .put(JSONObject().put("uid", "new").put("timestamp", "2026-10-30T08:30:00-05:00")
                .put("creator", "SELF").put("info", "Recent note"))
        val incident = CanonicalIncident.parse(record().put("notes", notes).toString())
        assertEquals(listOf("new", "old"), incident.notes.map { it.uid })
        val sections = Incident(incident.ask()).detailSections()
        val notesText = sections.single { it.first == "Notes" }.second
        assertTrue(notesText.indexOf("Recent note") < notesText.indexOf("Old note"))
        assertEquals("Synthetic details <north>", sections.single { it.first == "Summary" }.second)
    }

    @Test fun hostedIncidentListPrioritizesSelfAndOpensCanonicalExpandedDetails() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val store = IncidentStore(folder.newFile().apply { delete() })
        val runtime = CadRuntime(context, store, object : RoutingCredentials {
            override fun read() = ""
            override fun configured() = false
            override fun write(value: String) = Unit
            override fun clear() = Unit
        })
        try {
            runtime.restore()
            runtime.attach(object : IWearTakPluginControl.Default() {
                override fun getDeviceStatus() = DeviceStatusParcel(true, "Ready", Bundle().apply {
                    putString(DeviceStatusContract.KEY_CALLSIGN, "SELF")
                })
            })
            runtime.start()
            runtime.receive(xml(record("other", "A other")))
            runtime.receive(xml(record("mine", "Z mine").put("requestedCallsigns", JSONArray().put("SELF"))))
            runtime.receive(xml(record("closed", "Closed", "COMPLETED")))
            runtime.awaitIdle()
            assertEquals(listOf("mine", "other"), runtime.visibleIncidents().map { it.ask.id })
            val ui = HostedResponderUi(runtime)
            val list = ui.schema()
            val rows = list.cards.filter { it.cardId.startsWith("takcad.incident.open") }
            assertEquals(2, rows.size)
            assertTrue(rows.first().title.contains("Requested"))
            assertEquals("Z mine", rows.first().content.getString("actionLabel"))
            assertTrue(MainUiSchemaContractValidator.validate(list, strictV1CardTypesOnly = true) is ContractValidationResult.Ok)
            ui.invoke("takcad.incident.open", Bundle().apply { putString("incidentId", "mine") })
            val details = ui.schema()
            assertEquals(listOf("takcad.bloodhound", "takcad.target"),
                details.cards.take(2).map { it.cardId })
            assertEquals(listOf("\uD83D\uDC3E", "\u2316"), details.cards.take(2).map { it.title })
            assertEquals("Z mine\nACTIVE", details.cards.single { it.cardId == "incident.detail.0" }.subtitle)
            assertFalse(details.cards.any { it.cardId in setOf("limits", "takcad.incident.accept", "takcad.incident.decline") })
            runtime.accept("mine")
            runtime.awaitIdle()
            assertNotNull(runtime.lastError)
            assertEquals(-1, store.snapshot().first { it.ask.id == "mine" }.ownVersion)
            assertThrows(IllegalArgumentException::class.java) {
                store.receive(ASK.copy(id = "mine"), 1)
            }
        } finally {
            runtime.close()
        }
    }

    @Test fun canonicalIncidentListPaginatesSixRowsWithoutDroppingAny() {
        val context = RuntimeEnvironment.getApplication()
        val store = IncidentStore(folder.newFile().apply { delete() })
        val runtime = CadRuntime(context, store, object : RoutingCredentials {
            override fun read() = ""
            override fun configured() = false
            override fun write(value: String) = Unit
            override fun clear() = Unit
        })
        try {
            for (index in 0..7) store.receive(CanonicalIncident.parse(record("$index", "Incident $index").toString()).ask(), 1)
            val ui = HostedResponderUi(runtime)
            assertEquals(6, ui.schema().cards.count { it.cardId.startsWith("takcad.incident.open") })
            ui.invoke("takcad.incidents.next", Bundle())
            assertEquals(2, ui.schema().cards.count { it.cardId.startsWith("takcad.incident.open") })
            ui.invoke("takcad.incidents.previous", Bundle())
            assertEquals(6, ui.schema().cards.count { it.cardId.startsWith("takcad.incident.open") })
        } finally {
            runtime.close()
        }
    }
}
