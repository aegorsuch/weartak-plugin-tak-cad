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
        val requests = mutableListOf<Bundle>()
        val maps = mutableListOf<Bundle>()
        val threads = mutableListOf<Thread>()
        var onRequest: () -> Unit = {}
        override fun getCapabilities(): Bundle {
            threads += Thread.currentThread()
            return C.result(C.OK).apply {
                putInt(C.KEY_VERSION, C.VERSION)
                putBoolean(C.KEY_TAK_HTTPS, true)
                putStringArrayList(C.KEY_MAP_ACTIONS, arrayListOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET))
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
                putString(C.KEY_BODY, body)
            }
        }
        override fun requestMapAction(request: Bundle): Bundle {
            threads += Thread.currentThread()
            maps += Bundle(request)
            return C.result(responseStatus)
        }
    }

    private fun runtime(services: Services?): CadRuntime {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        return CadRuntime(context, IncidentStore(folder.newFile().apply { delete() }), credentials).also {
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

    @Test fun oldHostDisablesExtensionActionsAndMakesNoNetworkRequest() {
        val runtime = runtime(null)
        try {
            assertNull(runtime.lastError)
            assertFalse(runtime.supportsCad())
            val ui = HostedResponderUi(runtime)
            assertFalse(ui.schema().cards.single { it.cardId == "takcad.incidents.refresh" }.content.getBoolean("enabled"))
            runtime.refreshIncidents()
            runtime.awaitIdle()
            assertTrue(runtime.lastError!!.contains("Update WearTAK"))
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
            runtime.nextServer()
            runtime.awaitIdle()
            assertEquals("server-a", services.requests.single().getString(C.KEY_SERVER_ID))
            assertEquals("a", runtime.visibleIncidents().single().ask.id)
            val ui = HostedResponderUi(runtime)
            ui.invoke("takcad.incident.open", Bundle().apply { putString("incidentId", "a") })
            services.code = 403
            runtime.nextServer()
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
