// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Application
import android.os.Bundle
import android.os.Looper
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices
import com.weartak.plugin.sdk.model.DeviceStatusParcel
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
class CadHostServicesTest {
    @get:Rule val folder = TemporaryFolder()
    private val credentials = object : RoutingCredentials {
        override fun read() = ""
        override fun configured() = false
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }

    private fun record(id: String, name: String = id) =
        JSONObject().put("uid", id).put("incidentName", name).put("status", "ACTIVE")
            .put("requestedCallsigns", JSONArray().put("SELF"))
            .put("location", JSONObject().put("coords", JSONObject().put("latitudeDeg", 38).put("longitudeDeg", -77)))

    private class Services : IWearTakHostServices.Stub() {
        var servers = listOf("server-a")
        var code = 200
        var responseStatus = C.OK
        var body = "[]"
        var returnedServer: String? = null
        var mapActions = listOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET)
        var activeBloodhoundUid: String? = null
        var capabilityStatus = C.OK
        val requests = mutableListOf<Bundle>()
        val maps = mutableListOf<Bundle>()
        val threads = mutableListOf<Thread>()
        var onRequest: () -> Unit = {}
        var route: ((Bundle) -> String)? = null
        override fun getCapabilities(): Bundle {
            threads += Thread.currentThread()
            return C.result(capabilityStatus).apply {
                putInt(C.KEY_VERSION, C.VERSION)
                putBoolean(C.KEY_TAK_HTTPS, true)
                putStringArrayList(C.KEY_MAP_ACTIONS, ArrayList(mapActions))
                activeBloodhoundUid?.let { putString(CadHostServices.ACTIVE_BLOODHOUND_UID, it) }
                putParcelableArrayList(C.KEY_SERVERS, ArrayList(servers.map { id ->
                    Bundle().apply { putString(C.KEY_SERVER_ID, id); putString(C.KEY_SERVER_NAME, id) }
                }))
            }
        }
        override fun requestTakServer(request: Bundle): Bundle {
            threads += Thread.currentThread()
            requests += Bundle(request)
            onRequest()
            return C.result(responseStatus).apply {
                putInt(C.KEY_HTTP_STATUS, code)
                putString(C.KEY_SERVER_ID, returnedServer ?: request.getString(C.KEY_SERVER_ID))
                putString(C.KEY_BODY, route?.invoke(request) ?: body)
            }
        }
        override fun requestMapAction(request: Bundle): Bundle {
            threads += Thread.currentThread()
            maps += Bundle(request)
            if (responseStatus == C.OK && CadHostServices.ACTION_STOP_BLOODHOUND in mapActions) {
                when (request.getString(C.KEY_ACTION)) {
                    C.ACTION_BLOODHOUND -> activeBloodhoundUid = request.getString(C.KEY_UID)
                    CadHostServices.ACTION_STOP_BLOODHOUND -> {
                        if (activeBloodhoundUid != request.getString(C.KEY_UID)) return C.result(C.NOT_FOUND)
                        activeBloodhoundUid = null
                    }
                }
            }
            return C.result(responseStatus)
        }
    }

    private fun runtime(services: Services?, clock: () -> Long = System::currentTimeMillis, clearSettings: Boolean = true): CadRuntime {
        val context = RuntimeEnvironment.getApplication()
        if (clearSettings) context.getSharedPreferences("settings", 0).edit().clear().commit()
        return CadRuntime(context, IncidentStore(folder.newFile().apply { delete() }), credentials, clock = clock).also {
            it.restore()
            it.attach(object : IWearTakPluginControl.Default() {
                override fun getDeviceStatus() = DeviceStatusParcel(true, "Ready", Bundle().apply {
                    putString(DeviceStatusContract.KEY_CALLSIGN, "SELF")
                })
            }, services?.let(::CadHostServices))
            it.start()
            it.awaitIdle()
        }
    }

    @Test fun discoversOnlyVersionOneAndNullOnOldHost() {
        assertNull(C.fromHostExtras(Bundle()))
        val services = Services()
        val extras = Bundle().apply {
            putInt(C.EXTRA_VERSION, C.VERSION)
            putBinder(C.EXTRA_BINDER, services)
        }
        assertNotNull(C.fromHostExtras(extras))
        extras.putInt(C.EXTRA_VERSION, 2)
        assertNull(C.fromHostExtras(extras))
    }

    @Test fun fetchUsesExactReadOnlyPathAndWorkerThenRendersActionableDetails() {
        val services = Services().apply { body = JSONArray().put(record("one")).toString() }
        val runtime = runtime(services)
        try {
            assertNull(runtime.lastError)
            assertEquals("one", runtime.visibleIncidents().single().ask.id)
            assertEquals("Requested", runtime.involvement(runtime.visibleIncidents().single()))
            assertEquals("", runtime.responderVehicleUid())
            val request = services.requests.single()
            assertEquals("server-a", request.getString(C.KEY_SERVER_ID))
            assertEquals("GET", request.getString(C.KEY_METHOD))
            assertEquals("/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit", request.getString(C.KEY_PATH))
            assertEquals("fn=getIncidents", request.getString(C.KEY_QUERY))
            assertEquals(setOf(C.KEY_SERVER_ID, C.KEY_METHOD, C.KEY_PATH, C.KEY_QUERY), request.keySet())
            assertTrue(services.threads.all { it !== Looper.getMainLooper().thread })
            val ui = HostedResponderUi(runtime)
            ui.invoke("takcad.incident.open", Bundle().apply { putString("incidentId", "one") })
            for (action in listOf("takcad.bloodhound", "takcad.target")) {
                assertTrue(ui.schema().cards.single { it.cardId == action }.content.getBoolean("enabled"))
                ui.invoke(action, Bundle().apply { putString("incidentId", "one") })
                runtime.awaitIdle()
                assertNull(runtime.lastError)
            }
            assertEquals(listOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET), services.maps.map { it.getString(C.KEY_ACTION) })
            assertTrue(services.maps.all { it.getDouble(C.KEY_LATITUDE) == 38.0 && it.getDouble(C.KEY_LONGITUDE) == -77.0 })
            assertEquals("one", services.maps.first().getString(C.KEY_UID))
        } finally { runtime.close() }
    }

    @Test fun bloodhoundTargetLeadsActiveIncidentSectionAndIsNotDuplicatedUnderOtherIncidents() {
        val services = Services().apply {
            body = JSONArray().put(record("one")).put(record("two")).toString()
            mapActions = listOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET, CadHostServices.ACTION_STOP_BLOODHOUND)
            activeBloodhoundUid = "two"
        }
        val runtime = runtime(services)
        try {
            val ui = HostedResponderUi(runtime).apply { setHostPresentationVersion(1) }
            fun listCards() = ui.schema().cards
            var cards = listCards()
            val activeHeader = cards.indexOfFirst { it.cardId == "incidents.header" && it.title == "Active Incident" }
            val activeRow = cards.indexOfFirst { it.cardId == "takcad.incident.open.active" }
            val otherHeader = cards.indexOfFirst { it.cardId == "incidents.other.header" }
            assertTrue(activeHeader >= 0 && activeHeader < activeRow && activeRow < otherHeader)
            assertEquals("two", JSONObject(cards[activeRow].content.getString("actionArgsJson")!!)
                .getString("incidentId"))
            val otherRows = cards.filter { it.cardId.startsWith("takcad.incident.open") &&
                it.cardId != "takcad.incident.open.active" }
            assertEquals(listOf("one"), otherRows.map {
                JSONObject(it.content.getString("actionArgsJson")!!).getString("incidentId")
            })

            services.activeBloodhoundUid = null
            runtime.refreshMapState()
            runtime.awaitIdle()
            cards = listCards()
            assertFalse(cards.any { it.cardId == "takcad.incident.open.active" })
            assertEquals(listOf("one", "two"), cards.filter {
                it.cardId.startsWith("takcad.incident.open")
            }.map { JSONObject(it.content.getString("actionArgsJson")!!).getString("incidentId") })
        } finally { runtime.close() }
    }

    @Test fun oldHostDisablesExtensionActionsAndMakesNoNetworkRequest() {
        val runtime = runtime(null)
        try {
            assertNull(runtime.lastError)
            assertFalse(runtime.supportsCad())
            val ui = HostedResponderUi(runtime)
            assertFalse(ui.schema().cards.any { it.cardId == "takcad.incidents.refresh" })
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertTrue(runtime.lastError!!.contains("Update WearTAK"))
        } finally { runtime.close() }
    }

    @Test fun hostedIconsDisableMissingCoordinatesAndUnsupportedActionsIndependently() {
        val services = Services().apply {
            body = JSONArray().put(record("one").put("location", JSONObject.NULL)).toString()
            mapActions = listOf(C.ACTION_TARGET)
        }
        val runtime = runtime(services)
        val launched = mutableListOf<String>()
        try {
            val ui = HostedResponderUi(runtime) { launched += it }
            ui.setHostPresentationVersion(1)
            val args = Bundle().apply { putString("incidentId", "one") }
            ui.invoke("takcad.incident.open", args)
            fun icons() = ui.schema().cards.filter {
                it.content.getString("actionGroup") == "takcad.incident.actions"
            }
            assertEquals(listOf(true, false, false), icons().map { it.content.getBoolean("enabled") })
            ui.invoke("takcad.note.add", args)
            assertEquals(listOf("one"), launched)
            services.body = JSONArray().put(record("one")).toString()
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertEquals(listOf(true, false, true), icons().map { it.content.getBoolean("enabled") })
            ui.invoke("takcad.target", args)
            runtime.awaitIdle()
            assertEquals(C.ACTION_TARGET, services.maps.single().getString(C.KEY_ACTION))
            runtime.stop()
            assertEquals(listOf(false, false, false), icons().map { it.content.getBoolean("enabled") })
        } finally { runtime.close() }
    }

    @Test fun bloodhoundStopUsesLiveMatchingIncidentStateAndNeverWritesRespondingOnStop() {
        val services = Services().apply {
            body = JSONArray().put(record("one")).put(record("two")).toString()
            mapActions = listOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET, CadHostServices.ACTION_STOP_BLOODHOUND)
        }
        val runtime = runtime(services)
        try {
            val ui = HostedResponderUi(runtime)
            ui.setHostPresentationVersion(1)
            val one = Bundle().apply { putString("incidentId", "one") }
            val two = Bundle().apply { putString("incidentId", "two") }
            fun bloodhound() = ui.schema().cards.single { it.cardId == "takcad.bloodhound" }
            ui.invoke("takcad.incident.open", one)
            assertEquals("BLOODHOUND", bloodhound().content.getString("actionIcon"))
            ui.invoke("takcad.bloodhound", one)
            runtime.awaitIdle()
            runtime.awaitIdle()
            assertEquals("one", services.activeBloodhoundUid)
            assertTrue(services.maps.single().getBoolean(CadHostServices.RETURN_TO_MAIN))
            assertEquals("STOP", bloodhound().content.getString("actionIcon"))
            assertEquals("Stop Bloodhound", bloodhound().content.getString("actionLabel"))
            ui.invoke("takcad.back", Bundle())
            ui.invoke("takcad.incident.open", two)
            assertEquals("BLOODHOUND", bloodhound().content.getString("actionIcon"))
            ui.invoke("takcad.back", Bundle())
            ui.invoke("takcad.incident.open", one)
            services.body = JSONArray().put(record("one").put("location", JSONObject.NULL)).put(record("two")).toString()
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertTrue(bloodhound().content.getBoolean("enabled"))
            val before = services.requests.size
            ui.invoke("takcad.bloodhound", one)
            runtime.awaitIdle()
            runtime.awaitIdle()
            assertNull(services.activeBloodhoundUid)
            assertEquals(before, services.requests.size)
            val stop = services.maps.last()
            assertEquals(setOf(C.KEY_ACTION, C.KEY_UID), stop.keySet())
            assertEquals(CadHostServices.ACTION_STOP_BLOODHOUND, stop.getString(C.KEY_ACTION))
            assertEquals("one", stop.getString(C.KEY_UID))
            assertEquals("BLOODHOUND", bloodhound().content.getString("actionIcon"))
            assertFalse(bloodhound().content.getBoolean("enabled"))
            services.activeBloodhoundUid = "two"
            runtime.refreshMapState()
            runtime.awaitIdle()
            runtime.mapAction("one", CadHostServices.ACTION_STOP_BLOODHOUND)
            runtime.awaitIdle()
            assertEquals("two", services.activeBloodhoundUid)
            assertTrue(runtime.lastError!!.contains("not_found"))
        } finally { runtime.close() }
    }

    @Test fun unchangedHostStateRefreshDoesNotLoopSchemaCallbacksOrClearOperationErrors() {
        val services = Services().apply { body = JSONArray().put(record("one")).toString() }
        val runtime = runtime(services)
        var changes = 0
        runtime.setCallback(object : com.weartak.plugin.sdk.aidl.IWearTakHostCallback.Default() {
            override fun onPluginMainUiSchemaChanged() { changes++ }
        })
        try {
            val ui = HostedResponderUi(runtime)
            repeat(3) {
                ui.schema()
                runtime.awaitIdle()
            }
            assertEquals(0, changes)
            services.code = 403
            runtime.refreshIncidents()
            runtime.awaitIdle()
            val failure = runtime.lastError
            assertTrue(failure!!.contains("403"))
            val before = changes
            ui.schema()
            runtime.awaitIdle()
            assertEquals(before, changes)
            assertEquals(failure, runtime.lastError)
            services.capabilityStatus = C.UNAVAILABLE
            ui.schema()
            runtime.awaitIdle()
            assertEquals(before, changes)
            assertTrue(runtime.lastError!!.contains("unavailable"))
        } finally { runtime.close() }
    }

    @Test fun multipleServersRequireExplicitSelectionAndDoNotMixSnapshots() {
        val services = Services().apply {
            servers = listOf("server-a", "server-b")
            body = JSONArray().put(record("a")).toString()
        }
        val runtime = runtime(services)
        try {
            assertTrue(services.requests.isEmpty())
            assertEquals("Select CAD server", runtime.cadStatus)
            runtime.store.receive(CanonicalIncident.parse(record("unscoped").toString()).ask(), 1)
            assertTrue(runtime.visibleIncidents().isEmpty())
            runtime.selectServer("server-a")
            runtime.awaitIdle()
            assertEquals("server-a", services.requests.single().getString(C.KEY_SERVER_ID))
            assertEquals("a", runtime.visibleIncidents().single().ask.id)
            val ui = HostedResponderUi(runtime)
            ui.invoke("takcad.incident.open", Bundle().apply { putString("incidentId", "a") })
            services.code = 403
            runtime.selectServer("server-b")
            runtime.awaitIdle()
            assertTrue(runtime.visibleIncidents().isEmpty())
            assertFalse(ui.schema().cards.any { it.cardId == "details" })
            assertTrue(runtime.lastError!!.contains("403"))
            services.code = 200
            services.body = JSONArray().put(record("b")).toString()
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertEquals("b", runtime.visibleIncidents().single().ask.id)
            assertEquals("server-b", runtime.store.snapshot().single().serverId)
        } finally { runtime.close() }
    }

    @Test fun serverPickerShowsCurrentSelectionAndSelectingRefreshesOnceAndPersists() {
        val services = Services().apply {
            servers = listOf("AFTAK", "NCTAK", "ODIN Local")
            body = JSONArray().put(record("odin")).toString()
        }
        val runtime = runtime(services)
        try {
            val ui = HostedResponderUi(runtime)
            ui.setHostPresentationVersion(1)
            val chip = ui.schema().cards.single { it.cardId == "takcad.server.choose" }
            assertEquals("Active Server", chip.title)
            assertEquals("Select \u25BE", chip.content.getString("actionLabel"))
            assertTrue(chip.content.getBoolean("enabled"))
            assertFalse(ui.schema().cards.any { it.cardId == "takcad.server.next" })
            ui.invoke("takcad.server.choose", null)
            runtime.awaitIdle()
            assertTrue(services.requests.isEmpty())
            val options = ui.schema().cards.filter { it.cardId.startsWith("takcad.server.select") }
            assertEquals(listOf("AFTAK", "NCTAK", "ODIN Local"),
                options.map { it.content.getString("actionLabel") })
            assertTrue(options.all { !it.content.getBoolean("showTitle", true) && it.title != "\u2022" })
            assertEquals("HEADER_END", ui.schema().cards.single { it.cardId == "takcad.server.cancel" }
                .content.getString("actionPlacement"))
            assertThrows(IllegalArgumentException::class.java) {
                ui.invoke("takcad.server.select", Bundle().apply { putString("serverId", "unknown") })
            }
            assertThrows(IllegalArgumentException::class.java) {
                ui.invoke("takcad.server.select", Bundle().apply { putString("incidentId", "ODIN Local") })
            }
            ui.invoke("takcad.server.select", Bundle().apply { putString("serverId", "ODIN Local") })
            runtime.awaitIdle()
            assertEquals(listOf("ODIN Local"), services.requests.map { it.getString(C.KEY_SERVER_ID) })
            assertEquals("odin", runtime.visibleIncidents().single().ask.id)
            assertEquals("ODIN Local \u25BE", ui.schema().cards.first().content.getString("actionLabel"))
            ui.invoke("takcad.server.choose", null)
            runtime.awaitIdle()
            assertEquals("Active Server", ui.schema().cards.single { it.cardId == "server.header" }.title)
            val marked = ui.schema().cards.filter { it.cardId.startsWith("takcad.server.select") }
                .map { it.content.getString("actionLabel") }
            assertEquals(listOf("AFTAK", "NCTAK", "\u2713 ODIN Local"), marked)
            ui.invoke("takcad.server.cancel", null)
            assertTrue(ui.schema().cards.any { it.cardId == "takcad.server.choose" })
            assertEquals(1, services.requests.size)
        } finally { runtime.close() }

        val restored = runtime(services, clearSettings = false)
        try {
            assertEquals("ODIN Local", restored.selectedServer())
            assertEquals("ODIN Local", services.requests.last().getString(C.KEY_SERVER_ID))
        } finally { restored.close() }
    }

    @Test fun serverPickerShowsFourCompactChoicesPerPage() {
        val services = Services().apply { servers = (1..5).map { "Server $it" } }
        val runtime = runtime(services)
        try {
            val ui = HostedResponderUi(runtime)
            ui.invoke("takcad.server.choose", null)
            val firstPage = ui.schema()
            val firstOptions = firstPage.cards.filter { it.cardId.startsWith("takcad.server.select") }
            assertEquals(4, firstOptions.size)
            assertEquals(listOf("Server 1", "Server 2", "Server 3", "Server 4"),
                firstOptions.map { it.content.getString("actionLabel") })
            assertTrue(firstPage.cards.single { it.cardId == "takcad.servers.next" }.content.getBoolean("enabled"))
            assertTrue(firstPage.cards.any { it.cardId == "takcad.server.cancel" })

            ui.invoke("takcad.servers.next", Bundle())
            val secondPage = ui.schema()
            assertEquals(listOf("Server 5"), secondPage.cards.filter {
                it.cardId.startsWith("takcad.server.select")
            }.map { it.content.getString("actionLabel") })
            assertFalse(secondPage.cards.single { it.cardId == "takcad.servers.next" }.content.getBoolean("enabled"))
            assertTrue(secondPage.cards.single { it.cardId == "takcad.servers.previous" }.content.getBoolean("enabled"))
        } finally { runtime.close() }
    }

    @Test fun repeatedCadFailuresBackOffAutomaticPollingUntilSuccessOrManualRefresh() {
        var now = 1_000_000L
        val services = Services().apply { code = 500 }
        val runtime = runtime(services, clock = { now })
        try {
            assertEquals(1, services.requests.size)
            assertEquals(CadRuntime.CAD_POLL_MS, runtime.cadRetryDelayMs())
            now += CadRuntime.CAD_POLL_MS - 1
            runtime.pollNow()
            assertEquals(1, services.requests.size)
            now += 1
            runtime.pollNow()
            assertEquals(2, services.requests.size)
            assertEquals(2 * CadRuntime.CAD_POLL_MS, runtime.cadRetryDelayMs())
            repeat(10) {
                now += runtime.cadRetryDelayMs()
                runtime.pollNow()
            }
            assertEquals(12, services.requests.size)
            assertEquals(CadRuntime.CAD_MAX_BACKOFF_MS, runtime.cadRetryDelayMs())
            now += CadRuntime.CAD_POLL_MS
            runtime.pollNow()
            assertEquals(12, services.requests.size)
            services.code = 200
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertEquals(13, services.requests.size)
            assertEquals(0, runtime.cadRetryDelayMs())
            runtime.pollNow()
            assertEquals(14, services.requests.size)
        } finally { runtime.close() }
    }

    @Test fun httpErrorsAndMalformedOversizeBodiesNeverReplaceLastGoodSnapshot() {
        val services = Services().apply { body = JSONArray().put(record("good")).toString() }
        val runtime = runtime(services)
        try {
            for (code in listOf(302, 401, 403, 404, 500)) {
                services.code = code
                runtime.refreshIncidents()
                runtime.awaitIdle()
                assertTrue(runtime.lastError!!.contains("HTTP $code"))
                assertEquals("good", runtime.visibleIncidents().single().ask.id)
            }
            runtime.mapAction("good", C.ACTION_TARGET)
            runtime.awaitIdle()
            assertNull(runtime.lastError)
            assertEquals("CAD refresh failed; cached incidents", runtime.cadStatus)
            services.code = 200
            for (body in listOf("{}", "[{}", " ".repeat(C.MAX_RESPONSE_BYTES + 1))) {
                services.body = body
                runtime.refreshIncidents()
                runtime.awaitIdle()
                assertNotNull(runtime.lastError)
                assertEquals("good", runtime.visibleIncidents().single().ask.id)
            }
            services.body = "[]"
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertNull(runtime.lastError)
            assertTrue(runtime.visibleIncidents().isEmpty())
        } finally { runtime.close() }
    }

    @Test fun bridgeDenialAndMapFailureSurfaceWithoutReplayOrFalseSuccess() {
        val services = Services().apply { body = JSONArray().put(record("one")).toString() }
        val runtime = runtime(services)
        try {
            services.responseStatus = C.UNAVAILABLE
            runtime.mapAction("one", C.ACTION_BLOODHOUND)
            runtime.awaitIdle()
            assertEquals(1, services.maps.size)
            assertTrue(runtime.lastError!!.contains(C.UNAVAILABLE))
            assertFalse(runtime.message.contains("activation requested"))
            val requests = services.requests.size
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertEquals(requests + 1, services.requests.size)
            assertEquals("one", runtime.visibleIncidents().single().ask.id)
        } finally { runtime.close() }
    }

    @Test fun stopDuringFetchDiscardsResultAndQueuedMapRequests() {
        val services = Services()
        val runtime = runtime(services)
        try {
            services.body = JSONArray().put(record("late")).toString()
            services.onRequest = { runtime.stop() }
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertTrue(runtime.store.snapshot().isEmpty())
            assertNotNull(runtime.lastError)
            runtime.mapAction("late", C.ACTION_TARGET)
            runtime.awaitIdle()
            assertTrue(services.maps.isEmpty())
        } finally { runtime.close() }
    }

    @Test fun responseServerMismatchRejectedAndServerScopePersists() {
        val services = Services().apply { body = JSONArray().put(record("saved")).toString() }
        val runtime = runtime(services)
        try {
            services.returnedServer = "wrong-server"
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertTrue(runtime.lastError!!.contains("server changed"))
            runtime.store.load()
            assertEquals("server-a", runtime.store.snapshot().single().serverId)
            assertThrows(IllegalArgumentException::class.java) {
                runtime.store.receive(CanonicalIncident.parse(record("saved").toString()).ask(), 1)
            }
        } finally { runtime.close() }
    }

    @Test fun disconnectHidesCachedServerRecordsAndReconnectRequiresFreshRead() {
        val services = Services().apply { body = JSONArray().put(record("one")).toString() }
        val runtime = runtime(services)
        try {
            services.servers = emptyList()
            val before = services.requests.size
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertNull(runtime.lastError)
            assertEquals("Connect a TAK server", runtime.cadStatus)
            assertEquals("Connect a TAK server", runtime.message)
            assertTrue(runtime.visibleIncidents().isEmpty())
            assertEquals(before, services.requests.size)
            assertEquals(1, runtime.store.snapshot().size)
            services.servers = listOf("server-a")
            services.body = "[]"
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertTrue(runtime.store.snapshot().isEmpty())
        } finally { runtime.close() }
    }

    @Test fun rebindDuringFetchDoesNotPersistOldHostResult() {
        val services = Services()
        val runtime = runtime(services)
        try {
            services.body = JSONArray().put(record("late")).toString()
            services.onRequest = {
                runtime.attach(object : IWearTakPluginControl.Default() {})
                runtime.start()
            }
            runtime.refreshIncidents()
            runtime.awaitIdle()
            runtime.awaitIdle()
            assertTrue(runtime.store.snapshot().isEmpty())
            assertFalse(runtime.supportsCad())
        } finally { runtime.close() }
    }

    /** Minimal CAD server: getIncidents/getIncident read [records]; insertNote and updateIncident mutate them. */
    private class FakeCad(vararg initial: JSONObject) {
        val records = initial.associateBy { it.getString("uid") }.toMutableMap()
        var applyUpdates = true
        var noteResponse: String? = null
        var applyVehicleResponse = true
        var vehicleInserts = 0
        fun handle(request: Bundle): String {
            val query = request.getString(C.KEY_QUERY).orEmpty()
            val uid = query.substringAfter("uid=", "")
            return when {
                query == "fn=insertVehicleResponse" -> {
                    vehicleInserts++
                    val body = JSONObject(request.getString(C.KEY_BODY)!!)
                    if (applyVehicleResponse) {
                        val vehicle = JSONObject().put("uid", body.getString("vehicleUid"))
                            .put("callsign", body.getString("callsign")).put("name", "Unit").put("status", "IN_ROUTE")
                            .put("currentVehicleResponses", JSONArray().put(JSONObject()
                                .put("incidentUid", body.getString("incidentUid"))
                                .put("responseStatus", body.getString("responseStatus"))))
                        val record = records.getValue(body.getString("incidentUid"))
                        val list = record.optJSONArray("vehiclesResponding") ?: JSONArray().also { record.put("vehiclesResponding", it) }
                        list.put(JSONObject().put("vehicle", vehicle))
                    }
                    """{"success":true,"result":"00000000-0000-0000-0000-000000000002"}"""
                }
                query == "fn=getIncidents" -> JSONArray(records.values.map { JSONObject(it.toString()) }).toString()
                query.startsWith("fn=getIncident&") -> records.getValue(uid).toString()
                query == "fn=insertNote" -> noteResponse ?: run {
                    val note = JSONObject(request.getString(C.KEY_BODY)!!)
                    records.getValue(note.getString("incidentUid")).getJSONArray("notes")
                        .put(JSONObject(note.toString()).put("uid", "00000000-0000-0000-0000-000000000001"))
                    """{"success":true,"result":"00000000-0000-0000-0000-000000000001"}"""
                }
                query.startsWith("fn=updateIncident&") -> {
                    if (applyUpdates) records[uid] = JSONObject(request.getString(C.KEY_BODY)!!)
                    """{"name":"TakCadServerPlugin"}"""
                }
                else -> error("Unexpected CAD query $query")
            }
        }
    }

    private fun writable(id: String) = record(id).put("notes", JSONArray())
        .put("vehicleUidsRequested", JSONArray().put("veh-1")).put("details", "keep me")

    @Test fun addNoteUsesPutBodyWithCallsignAndRefreshesThenClearsDraft() {
        val cad = FakeCad(writable("one"))
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        try {
            services.requests.clear()
            runtime.addNote("one", "  Smoke visible from north  ")
            runtime.awaitIdle()
            assertNull(runtime.writeError)
            assertEquals("Note added", runtime.writeNotice)
            assertEquals("", runtime.noteDraft("one"))
            val put = services.requests.first()
            assertEquals("PUT", put.getString(C.KEY_METHOD))
            assertEquals("/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit/result", put.getString(C.KEY_PATH))
            assertEquals("fn=insertNote", put.getString(C.KEY_QUERY))
            assertEquals("application/json", put.getString(C.KEY_CONTENT_TYPE))
            val note = JSONObject(put.getString(C.KEY_BODY)!!)
            assertEquals(setOf("incidentUid", "timestamp", "creator", "info"), note.keys().asSequence().toSet())
            assertEquals("one", note.getString("incidentUid"))
            assertEquals("SELF", note.getString("creator"))
            assertEquals("Smoke visible from north", note.getString("info"))
            java.time.Instant.parse(note.getString("timestamp"))
            assertEquals("fn=getIncidents", services.requests.last().getString(C.KEY_QUERY))
            assertEquals("Smoke visible from north", runtime.visibleIncidents().single().ask.canonical!!.notes.single().info)
        } finally { runtime.close() }
    }

    @Test fun rejectedOrFailedNoteKeepsDraftAndErrorSurvivesPolling() {
        val cad = FakeCad(writable("one"))
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        try {
            cad.noteResponse = """{"success":false,"errors":["Incident closed"]}"""
            runtime.addNote("one", "Keep this")
            runtime.awaitIdle()
            assertEquals("Keep this", runtime.noteDraft("one"))
            assertTrue(runtime.writeError!!.contains("draft kept"))
            assertTrue(runtime.writeError!!.contains("Incident closed"))
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertNull(runtime.lastError)
            assertNotNull(runtime.writeError)
            services.code = 400
            cad.noteResponse = "Result data from plugin was null"
            runtime.addNote("one", "Keep this too")
            runtime.awaitIdle()
            assertEquals("Keep this too", runtime.noteDraft("one"))
            assertTrue(runtime.writeError!!.contains("HTTP 400"))
            services.code = 200
            cad.noteResponse = null
            runtime.addNote("one", "   ")
            runtime.awaitIdle()
            assertTrue(runtime.writeError!!.contains("empty"))
            assertTrue(cad.records.getValue("one").getJSONArray("notes").length() == 0)
        } finally { runtime.close() }
    }

    @Test fun setStatusSendsFreshRecordWithOnlyStatusChangedAndVerifies() {
        val cad = FakeCad(writable("one"), writable("two"))
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        try {
            cad.records.getValue("one").put("details", "changed on server").put("extraField", 7)
            services.requests.clear()
            runtime.setStatus("one", CadStatus.COMPLETED)
            runtime.awaitIdle()
            assertNull(runtime.writeError)
            assertEquals("Status set to Completed", runtime.writeNotice)
            assertEquals(listOf("fn=getIncident&uid=one", "fn=updateIncident&uid=one", "fn=getIncident&uid=one", "fn=getIncidents"),
                services.requests.map { it.getString(C.KEY_QUERY) })
            val post = services.requests[1]
            assertEquals("POST", post.getString(C.KEY_METHOD))
            assertEquals("/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit", post.getString(C.KEY_PATH))
            val sent = JSONObject(post.getString(C.KEY_BODY)!!)
            val expected = writable("one").put("details", "changed on server").put("extraField", 7).put("status", "COMPLETED")
            assertEquals(expected.toString(), sent.toString())
            assertEquals(listOf("two"), runtime.visibleIncidents().map { it.ask.id })
        } finally { runtime.close() }
    }

    @Test fun ignoredStatusUpdateIsReportedAsFailure() {
        val cad = FakeCad(writable("one")).apply { applyUpdates = false }
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        try {
            runtime.setStatus("one", CadStatus.CANCELLED)
            runtime.awaitIdle()
            assertTrue(runtime.writeError!!.contains("CAD did not apply status change"))
            assertNull(runtime.writeNotice)
            assertEquals("one", runtime.visibleIncidents().single().ask.id)
            assertThrows(IllegalArgumentException::class.java) { runtime.setStatus("one", "OPEN") }
        } finally { runtime.close() }
    }

    @Test fun bloodhoundMarksVehicleInRouteOnceAndVerifies() {
        val cad = FakeCad(writable("one"))
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        val vehicle = "11111111-1111-1111-1111-111111111111"
        try {
            runtime.configure("uid", "", vehicle)
            runtime.awaitIdle()
            repeat(2) {
                runtime.mapAction("one", C.ACTION_BLOODHOUND)
                runtime.awaitIdle()
                runtime.awaitIdle()
            }
            assertEquals(2, services.maps.size)
            assertEquals(1, cad.vehicleInserts)
            assertNull(runtime.writeError)
            assertEquals("Marked responding", runtime.writeNotice)
            val put = services.requests.first { it.getString(C.KEY_QUERY) == "fn=insertVehicleResponse" }
            assertEquals("PUT", put.getString(C.KEY_METHOD))
            assertEquals("/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit/result", put.getString(C.KEY_PATH))
            assertEquals("application/json", put.getString(C.KEY_CONTENT_TYPE))
            val sent = JSONObject(put.getString(C.KEY_BODY)!!)
            assertEquals(setOf("uid", "callsign", "vehicleUid", "incidentUid", "responseStatus"), sent.keys().asSequence().toSet())
            assertEquals("IN_ROUTE", sent.getString("responseStatus"))
            assertEquals("SELF", sent.getString("callsign"))
            assertEquals(vehicle, sent.getString("vehicleUid"))
            assertEquals("Responding", runtime.involvement(runtime.visibleIncidents().single()))
        } finally { runtime.close() }
    }

    @Test fun bloodhoundStartsEvenWhenRespondingCannotBeRecorded() {
        val cad = FakeCad(writable("one"))
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        try {
            runtime.mapAction("one", C.ACTION_BLOODHOUND)
            runtime.awaitIdle()
            runtime.awaitIdle()
            assertEquals(1, services.maps.size)
            assertEquals(0, cad.vehicleInserts)
            assertTrue(runtime.writeError!!.contains("vehicle UID"))
            assertNull(runtime.lastError)
            runtime.configure("uid", "", "11111111-1111-1111-1111-111111111111")
            runtime.awaitIdle()
            cad.applyVehicleResponse = false
            runtime.mapAction("one", C.ACTION_BLOODHOUND)
            runtime.awaitIdle()
            runtime.awaitIdle()
            assertEquals(2, services.maps.size)
            assertEquals(1, cad.vehicleInserts)
            assertTrue(runtime.writeError!!.contains("not marked responding"))
            assertTrue(runtime.writeError!!.contains("did not record"))
            assertNull(runtime.lastError)
        } finally { runtime.close() }
    }

    @Test fun hostedDetailsRemoveManualStatusControlsButNoteActionStillLaunchesEntry() {
        val cad = FakeCad(writable("one"))
        val services = Services().apply { route = cad::handle }
        val runtime = runtime(services)
        val launched = mutableListOf<String>()
        try {
            val ui = HostedResponderUi(runtime) { launched += it }
            val args = Bundle().apply { putString("incidentId", "one") }
            ui.invoke("takcad.incident.open", args)
            ui.invoke("takcad.note.add", args)
            assertEquals(listOf("one"), launched)
            val cards = ui.schema().cards
            assertFalse(cards.any { it.cardId.startsWith("takcad.status.") || it.cardId == "takcad.write" })
            assertFalse(cards.any { it.title in setOf("CAD update", "Status", "Navigation") })
            listOf("choose", "active", "cancelled", "completed", "confirm", "cancel").forEach {
                assertThrows(IllegalArgumentException::class.java) { ui.invoke("takcad.status.$it", args) }
            }
            assertEquals("ACTIVE", cad.records.getValue("one").getString("status"))
        } finally { runtime.close() }
    }

    @Test fun snapshotValidationAndStorageFailureAreAtomicAndPreserveLegacyRecords() {
        val file = folder.newFile().apply { delete() }
        val store = IncidentStore(file)
        store.receive(ASK, 1)
        val incident = CanonicalIncident.parse(record("canonical").toString())
        store.replaceCanonical(listOf(incident), "server-a")
        val before = file.readText()
        assertThrows(IllegalArgumentException::class.java) {
            store.replaceCanonical(listOf(incident, incident), "server-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.replaceCanonical(listOf(CanonicalIncident.parse(record(ASK.id).toString())), "server-a")
        }
        assertEquals(before, file.readText())
        val pending = java.io.File("${file.path}.pending")
        assertTrue(pending.mkdir())
        assertThrows(java.io.IOException::class.java) {
            store.replaceCanonical(emptyList(), "server-a")
        }
        assertEquals(before, file.readText())
        assertEquals(2, store.snapshot().size)
        assertTrue(pending.delete())
        store.replaceCanonical(emptyList(), "server-a")
        assertEquals(ASK, store.snapshot().single().ask)
    }
}
