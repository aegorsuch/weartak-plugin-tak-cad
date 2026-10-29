// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Application
import android.os.Bundle
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.model.DeviceStatusParcel
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CadRuntimeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var runtime: CadRuntime
    private lateinit var host: Host
    private var routeCalls = 0
    private var routeAction: () -> Unit = {}

    private class Credentials : RoutingCredentials {
        var value = ""
        override fun read() = value
        override fun configured() = value.isNotBlank()
        override fun write(value: String) { this.value = value }
        override fun clear() { value = "" }
    }

    private class Host : IWearTakPluginControl.Default() {
        val sent = mutableListOf<CotRequestParcel>()
        var connected = true
        var age = 0L
        var failSend = false
        override fun isCotConnected() = connected
        override fun getDeviceStatus() = DeviceStatusParcel(true, "Ready", Bundle().apply {
            putBoolean(DeviceStatusContract.KEY_LOCATION_AVAILABLE, true)
            putDouble(DeviceStatusContract.KEY_LAT, 41.0)
            putDouble(DeviceStatusContract.KEY_LON, -87.0)
            putString(DeviceStatusContract.KEY_CALLSIGN, "Unit 1")
            putLong(DeviceStatusContract.KEY_SNAPSHOT_TIME_MS, System.currentTimeMillis() - age)
        })
        override fun requestCotDispatch(request: CotRequestParcel) {
            if (failSend) throw android.os.RemoteException("Disconnected")
            sent += request
        }
    }

    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit()
        host = Host()
        runtime = CadRuntime(RuntimeEnvironment.getApplication(), IncidentStore(folder.newFile().apply { delete() }),
            Credentials()) { start, destination, key ->
            routeCalls++
            assertEquals(Position(41.0, -87.0), start)
            assertEquals(ASK.position, destination)
            check(key.isNotBlank()) { "Routing key missing" }
            routeAction()
            Route.parse(ROUTE)
        }
        runtime.restore()
        runtime.configure("watch-uid", "runtime-only-test-key")
        runtime.attach(host)
        runtime.start()
        runtime.receive(askXml())
        runtime.awaitIdle()
    }

    @After fun cleanup() { runtime.close() }

    @Test fun acceptanceDispatchesOnlyOnceAndRefreshIncrementsVersion() {
        assertTrue(host.sent.isEmpty())
        runtime.accept(ASK.id)
        runtime.awaitIdle()
        assertEquals(Participation.RESPONSE_QUEUED, runtime.store.snapshot().single().participation)
        assertEquals(0, runtime.store.snapshot().single().ownVersion)
        runtime.accept(ASK.id)
        runtime.awaitIdle()
        assertNotNull(runtime.lastError)
        assertEquals(1, host.sent.size)
        runtime.refresh(ASK.id)
        runtime.awaitIdle()
        assertEquals(2, host.sent.size)
        assertEquals(1, runtime.store.snapshot().single().ownVersion)
    }

    @Test fun offlineAndStaleLocationNeverMarkAccepted() {
        host.connected = false
        runtime.accept(ASK.id)
        runtime.awaitIdle()
        assertTrue(host.sent.isEmpty())
        assertEquals(Participation.ASKED, runtime.store.snapshot().single().participation)
        host.connected = true
        host.age = 60_001
        runtime.accept(ASK.id)
        runtime.awaitIdle()
        assertTrue(host.sent.isEmpty())
        assertEquals(0, routeCalls)
        assertTrue(runtime.lastError!!.contains("stale"))
    }

    @Test fun hostFailureLeavesAskPending() {
        host.failSend = true
        runtime.accept(ASK.id)
        runtime.awaitIdle()
        assertEquals(Participation.ASKED, runtime.store.snapshot().single().participation)
        assertNotNull(runtime.lastError)
    }

    @Test fun stopDuringNetworkPreventsDispatch() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        routeAction = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        runtime.accept(ASK.id)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        runtime.stop()
        release.countDown()
        runtime.awaitIdle()
        assertTrue(host.sent.isEmpty())
        assertEquals(Participation.ASKED, runtime.store.snapshot().single().participation)
    }

    @Test fun malformedInboundAndDeclineReportedWithoutDispatch() {
        runtime.receive("""<event how="tak-cad-incident"><detail>{}</detail></event>""")
        runtime.awaitIdle()
        assertNotNull(runtime.lastError)
        runtime.decline(ASK.id)
        runtime.awaitIdle()
        assertEquals(Participation.DECLINED, runtime.store.snapshot().single().participation)
        assertTrue(host.sent.isEmpty())
    }
}
