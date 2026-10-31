// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Application
import android.os.Bundle
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class HostedResponderUiTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var runtime: CadRuntime
    private lateinit var ui: HostedResponderUi
    private val idArgs get() = Bundle().apply { putString("incidentId", ASK.id) }

    @Before fun setup() {
        runtime = CadRuntime(RuntimeEnvironment.getApplication(), IncidentStore(folder.newFile().apply { delete() }),
            object : RoutingCredentials {
                override fun read() = "secret-must-not-be-in-host-schema"
                override fun configured() = true
                override fun write(value: String) = Unit
                override fun clear() = Unit
            })
        runtime.restore()
        runtime.awaitIdle()
        ui = HostedResponderUi(runtime)
    }

    @After fun cleanup() { runtime.close() }

    @Test fun canonicalDetailHasThreeAccessibleIconActionsAndHeaderBackWithoutVisibleTitles() {
        val record = JSONObject().put("uid", ASK.id).put("incidentName", "Exercise").put("status", "ACTIVE")
            .put("details", "Training summary")
            .put("location", JSONObject().put("address", JSONObject().put("streetName", "Training road")))
            .put("personnelResponding", org.json.JSONArray().put(JSONObject().put("uid", "person")))
            .put("notes", org.json.JSONArray().put(JSONObject().put("uid", "note").put("info", "Existing note")))
        runtime.store.receive(CanonicalIncident.parse(record.toString()).ask(), 1)
        ui.setHostPresentationVersion(1)
        ui.invoke("takcad.incident.open", idArgs)
        val schema = ui.schema()
        val icons = schema.cards.filter { it.content.getString("actionGroup") == "takcad.incident.actions" }
        assertEquals(listOf("takcad.note.add", "takcad.bloodhound", "takcad.target"), icons.map { it.cardId })
        assertEquals(listOf("NOTE", "BLOODHOUND", "TARGET"), icons.map { it.content.getString("actionIcon") })
        assertEquals(listOf("Add note", "Start Bloodhound", "Focus incident on map"),
            icons.map { it.content.getString("actionLabel") })
        assertTrue(icons.all { it.content.getString("actionPresentation") == "ICON" &&
            !it.content.getBoolean("showTitle", true) && !it.content.getBoolean("enabled") })
        assertTrue(icons.all { JSONObject(it.content.getString("actionArgsJson")!!)
            .getString("incidentId") == ASK.id })
        val back = schema.cards.single { it.content.getString("actionPlacement") == "HEADER_END" }
        assertEquals("takcad.back", back.cardId)
        assertEquals("BACK", back.content.getString("actionIcon"))
        assertEquals("Back", back.content.getString("actionLabel"))
        assertFalse(back.content.getBoolean("showTitle", true))
        assertNull(back.content.getString("actionGroup"))
        assertFalse(schema.cards.any { it.title == "Navigation" || it.title == "\uD83D\uDC3E" })
        assertTrue(schema.cards.any { it.subtitle?.contains("Existing note") == true })
        val fields = schema.cards.filter { it.cardId.startsWith("incident.detail.") }
        assertEquals(listOf("Address", "Notes", "Responders", "Summary"), fields.map { it.title })
        assertTrue(fields.none { it.subtitle.orEmpty().contains("Exercise") })
        assertTrue(fields.none { it.subtitle.orEmpty().lines().contains("ACTIVE") })
        assertEquals("Training road", fields.single { it.title == "Address" }.subtitle)
        assertTrue(MainUiSchemaContractValidator.validate(schema, strictV1CardTypesOnly = true) is ContractValidationResult.Ok)
        ui.invoke("takcad.back", Bundle())
        val open = ui.schema().cards.single { it.cardId == "takcad.incident.open" }
        assertEquals("Exercise", open.content.getString("actionLabel"))
        assertFalse(open.content.getBoolean("showTitle", true))
        assertFalse(open.title.contains("ACTIVE"))
    }

    @Test fun oldHostAndRevokedPresentationSupportReceiveOnlyOriginalActionKeys() {
        runtime.store.receive(CanonicalIncident.parse(JSONObject().put("uid", ASK.id)
            .put("incidentName", "Exercise").put("status", "ACTIVE").toString()).ask(), 1)
        ui.invoke("takcad.incident.open", idArgs)
        fun assertLegacy() {
            val schema = ui.schema()
            assertTrue(schema.cards.filter { it.cardType == "ACTION" }.all {
                it.content.keySet() == setOf("actionId", "actionLabel", "enabled", "actionArgsJson")
            })
            assertTrue(MainUiSchemaContractValidator.validate(schema, strictV1CardTypesOnly = true) is ContractValidationResult.Ok)
        }
        assertLegacy()
        ui.setHostPresentationVersion(1)
        assertTrue(ui.schema().cards.any { it.content.getString("actionPresentation") == "ICON" })
        ui.setHostPresentationVersion(0)
        assertLegacy()
    }

    @Test fun listAndDetailsUseExactActionContractWithoutSecrets() {
        runtime.store.receive(ASK, 1)
        val schema = ui.schema()
        assertEquals("takcad.server.choose", schema.cards.first().cardId)
        assertEquals("Active Server", schema.cards.first().title)
        assertEquals("incidents.header", schema.cards[1].cardId)
        assertEquals("Active Incident", schema.cards[1].title)
        assertTrue(MainUiSchemaContractValidator.validate(schema, strictV1CardTypesOnly = true) is ContractValidationResult.Ok)
        val open = schema.cards.single { it.cardId == "takcad.incident.open" }
        assertEquals("ACTION", open.cardType)
        assertEquals("takcad.incident.open", open.content.getString("actionId"))
        assertEquals("Asked - acceptance required", open.title)
        assertEquals(ASK.title, open.content.getString("actionLabel"))
        assertEquals(ASK.id, JSONObject(open.content.getString("actionArgsJson")!!).getString("incidentId"))
        assertTrue(open.content.getBoolean("enabled"))
        ui.invoke("takcad.incident.open", idArgs)
        val details = ui.schema()
        assertTrue(details.cards.any { it.cardId == "takcad.incident.accept" })
        assertFalse(details.cards.single { it.cardId == "takcad.incident.accept" }.content.getBoolean("enabled"))
        val detailCards = details.cards.filter { it.cardType == "STATUS" }
        assertEquals(listOf("Address", "Summary"), detailCards.map { it.title })
        assertFalse(detailCards.any { it.subtitle.orEmpty().contains(ASK.title) })
        assertFalse(detailCards.any { it.title == "Active Incident" })
        assertEquals(detailCards.size, detailCards.map { it.title }.distinct().size)
        assertFalse(details.cards.any { it.cardId.startsWith("takcad.details.") })
        assertFalse(details.toString().contains("secret-must-not"))
        assertFalse(details.cards.any { it.content.keySet().any { key -> key.contains("key", true) || key.contains("token", true) } })
        assertFalse(details.cards.any { it.cardId == "limits" })
    }

    @Test fun emptyListHasServerDropdownFirstAndNoHelperText() {
        val schema = ui.schema()
        assertEquals(listOf("takcad.server.choose", "incidents.header", "incidents.other.header"),
            schema.cards.map { it.cardId })
        assertEquals("Active Server", schema.cards.first().title)
        assertEquals("Select \u25BE", schema.cards.first().content.getString("actionLabel"))
        assertEquals("Active Incident", schema.cards[1].title)
        assertEquals("Other Incidents", schema.cards.last().title)
        assertFalse(schema.cards.any { it.cardId == "takcad.incidents.refresh" })
        assertFalse(schema.toString().contains("No incidents"))
        assertFalse(schema.toString().contains("requires host integration"))
        assertTrue(MainUiSchemaContractValidator.validate(schema, strictV1CardTypesOnly = true) is ContractValidationResult.Ok)
    }

    @Test fun routeAndResponderNavigationRemainInsideSchema() {
        runtime.store.receive(ASK, 1)
        runtime.store.recordDispatch(ASK.id, 0, Route.parse(ROUTE), 2)
        runtime.store.receive(CadEvent.Response(ASK.id, "other", "Other unit", 0, Route.parse(ROUTE), ASK), 3)
        ui.invoke("takcad.incident.open", idArgs)
        assertTrue(ui.schema(100_000).cards.single { it.cardId == "route" }.subtitle!!.contains("Turn right"))
        assertTrue(ui.schema(100_000).cards.single { it.cardId == "responders" }.subtitle!!.contains("STALE"))
        ui.invoke("takcad.route.next", idArgs)
        assertTrue(ui.schema().cards.single { it.cardId == "route" }.subtitle!!.contains("Arrive"))
        assertThrows(IllegalArgumentException::class.java) {
            ui.invoke("takcad.incident.decline", Bundle().apply { putString("incidentId", "wrong") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ui.invoke("takcad.back", Bundle().apply { putString("token", "do-not-accept") })
        }
        ui.invoke("takcad.back", Bundle())
        assertTrue(ui.schema().cards.any { it.cardId == "takcad.incident.open" })
    }

    @Test fun longUnicodeDetailsAndInstructionsArePagedWithinSdkBounds() {
        val longAsk = ASK.copy(summary = "\u4e2d".repeat(2000))
        runtime.store.receive(longAsk, 1)
        val route = Route.parse(ROUTE.replace("Turn right", "\u4e2d".repeat(1000)))
        runtime.store.recordDispatch(ASK.id, 0, route, 2)
        runtime.store.receive(CadEvent.Response(ASK.id, "other", "\u4e2d".repeat(80), 0, route, longAsk), 3)
        ui.invoke("takcad.incident.open", idArgs)
        val schema = ui.schema()
        assertTrue(schema.cards.size <= 16)
        assertTrue(MainUiSchemaContractValidator.validate(schema, strictV1CardTypesOnly = true) is ContractValidationResult.Ok)
        assertTrue(schema.cards.all { it.subtitle.orEmpty().toByteArray().size <= 256 })
        val before = schema.cards.single { it.cardId == "route" }.subtitle
        ui.invoke("takcad.route.text.next", idArgs)
        assertEquals("Guidance", ui.schema().cards.single { it.cardId == "route" }.title)
        assertEquals(before, ui.schema().cards.single { it.cardId == "route" }.subtitle)
        assertFalse(schema.cards.any { it.cardId.startsWith("takcad.details.") })
        assertTrue(schema.cards.any { it.title == "Summary" &&
            it.subtitle!!.endsWith("…") })
    }

    @Test fun declineUsesRuntimeAndNavigationEmitsHostRefreshCallbacks() {
        var schemas = 0
        var states = 0
        runtime.setCallback(object : IWearTakHostCallback.Default() {
            override fun onPluginMainUiSchemaChanged() { schemas++ }
            override fun onPluginMainUiStateChanged() { states++ }
        })
        runtime.store.receive(ASK, 1)
        ui.invoke("takcad.incident.open", idArgs)
        ui.invoke("takcad.incident.decline", idArgs)
        runtime.awaitIdle()
        assertEquals(Participation.DECLINED, runtime.store.snapshot().single().participation)
        assertTrue(schemas > 0)
        assertTrue(states > 0)
        assertFalse(ui.schema().cards.any { it.cardId == "takcad.incident.accept" })
    }
}
