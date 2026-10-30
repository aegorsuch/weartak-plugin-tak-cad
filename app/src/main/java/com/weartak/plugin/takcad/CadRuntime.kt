// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.AlertRequestParcel
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CadRuntime(
    context: Context,
    val store: IncidentStore,
    private val secrets: RoutingCredentials,
    private val directions: (Position, Position, String) -> Route = RouteClient()::directions,
) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val gate = Any()
    private val pendingReceives = AtomicInteger()
    @Volatile private var control: IWearTakPluginControl? = null
    @Volatile private var callback: IWearTakHostCallback? = null
    @Volatile private var running = false
    @Volatile private var generation = 0L
    @Volatile private var storageReady = false
    @Volatile private var callsign = ""
    @Volatile private var hostServices: CadHostServices? = null
    @Volatile private var hostCapabilities = CadHostCapabilities(emptyList(), emptySet(), false)
    @Volatile private var selectedServerId: String? = null
    @Volatile var cadStatus = "Update WearTAK for CAD server access"
        private set
    @Volatile var bridgeSeen = false
        private set
    @Volatile var message = "Awaiting host"
        private set
    @Volatile var lastError: String? = null
        private set

    init {
        worker.scheduleWithFixedDelay({
            if (running && storageReady) {
                store.snapshot().filter { it.participation == Participation.RESPONSE_QUEUED }.forEach { incident ->
                    attempt { routeAndDispatch(incident.ask.id) }
                }
            }
        }, 10, 10, TimeUnit.SECONDS)
        worker.scheduleWithFixedDelay({
            if (running && storageReady && hostServices != null) attempt { refreshCad() }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun restore() = enqueue {
        store.load()
        storageReady = true
        message = "Awaiting host"
    }

    fun observe(listener: () -> Unit) { listeners += listener }
    fun unobserve(listener: () -> Unit) { listeners -= listener }
    fun responderUid(): String = settings.getString("uid", "") ?: ""
    fun responderVehicleUid(): String = settings.getString("vehicleUid", "") ?: ""
    fun involvement(incident: Incident): String? = incident.ask.canonical?.involvement(callsign, responderVehicleUid())
        ?: if (incident.ask.canonical == null) when (incident.participation) {
            Participation.ASKED -> "Asked"
            Participation.RESPONSE_QUEUED -> "Responding"
            else -> null
        } else null
    fun visibleIncidents(): List<Incident> = store.snapshot()
        .filter {
            if (it.ask.canonical == null) true
            else if (hostServices == null) it.serverId == null
            else it.serverId != null && it.serverId == selectedServerId
        }
        .filter { it.ask.canonical?.status?.let { status -> status == "ACTIVE" } ?: true }
        .sortedWith(compareBy<Incident> { involvement(it) == null }.thenBy { it.ask.title }.thenBy { it.ask.id })
    fun keyConfigured(): Boolean = secrets.configured()
    fun isRunning(): Boolean = running
    fun supportsCad(): Boolean = hostServices != null
    fun canMapAction(action: String): Boolean = running && action in hostCapabilities.mapActions
    fun canSelectServer(): Boolean = running && hostCapabilities.servers.size > 1
    fun selectedServerName(): String? = hostCapabilities.servers.firstOrNull { it.id == selectedServerId }?.name
    fun nextServerName(): String? {
        val servers = hostCapabilities.servers
        if (servers.size <= 1) return null
        return servers[(servers.indexOfFirst { it.id == selectedServerId } + 1) % servers.size].name
    }
    fun notifyHostedNavigation() = changed()
    internal fun close() { worker.shutdownNow() }
    internal fun awaitIdle() { worker.submit {}.get(30, TimeUnit.SECONDS) }

    fun attach(host: IWearTakPluginControl, services: CadHostServices? = null) {
        synchronized(gate) {
            generation++
            control = host
            hostServices = services
            hostCapabilities = CadHostCapabilities(emptyList(), emptySet(), false)
            selectedServerId = null
            cadStatus = if (services == null) "Update WearTAK for CAD server access" else "Awaiting CAD server"
            running = false
            bridgeSeen = false
        }
        message = "Awaiting incidents"
        changed()
    }

    fun setCallback(value: IWearTakHostCallback?) { callback = value }

    fun start() {
        synchronized(gate) {
            check(control != null) { "Initialize host before starting plugin" }
            running = true
            generation++
        }
        message = if (bridgeSeen) "Running" else "Awaiting incidents"
        changed()
        val epoch = generation
        enqueue {
            checkCurrent(epoch)
            refreshIdentity()
            if (hostServices != null) refreshCad()
        }
    }

    fun stop(detach: Boolean = false) {
        synchronized(gate) {
            running = false
            generation++
            if (detach) {
                control = null
                callback = null
                hostServices = null
                hostCapabilities = CadHostCapabilities(emptyList(), emptySet(), false)
                selectedServerId = null
            }
        }
        message = if (detach) "Host detached" else "Stopped; routing paused"
        changed()
    }

    fun configure(uid: String, key: String, vehicleUid: String = "") = enqueue {
        require(uid.isNotBlank() && uid.length <= 256 && uid.none { it.isISOControl() }) { "Enter WearTAK self UID" }
        val previous = responderUid()
        require(previous.isBlank() || previous == uid ||
            store.snapshot().none { it.participation == Participation.RESPONSE_QUEUED }) {
            "Cannot change responder UID with active responses"
        }
        require(vehicleUid.length <= 256 && vehicleUid.none { it.isISOControl() }) { "Invalid vehicle UID" }
        if (key.isNotBlank()) secrets.write(key)
        check(settings.edit().putString("uid", uid).putString("vehicleUid", vehicleUid).commit()) { "Could not save responder identity" }
        message = "Routing setup saved"
    }

    fun clearKey() = enqueue {
        secrets.clear()
        message = "Routing key removed; route refresh paused"
    }

    fun refreshIncidents() {
        val epoch = generation
        enqueue {
            checkCurrent(epoch)
            refreshCad()
        }
    }

    fun nextServer() {
        val epoch = generation
        enqueue {
            checkCurrent(epoch)
            val services = requireNotNull(hostServices) { "Update WearTAK for CAD server access" }
            val capabilities = services.capabilities()
            synchronized(gate) {
                checkCurrent(epoch)
                check(services === hostServices) { "Host connection changed" }
                hostCapabilities = capabilities
                val servers = capabilities.servers
                check(servers.size > 1) { "Multiple connected CAD servers unavailable" }
                selectedServerId = servers[(servers.indexOfFirst { it.id == selectedServerId } + 1) % servers.size].id
                cadStatus = "Refreshing CAD incidents"
            }
            changed()
            refreshCad()
        }
    }

    fun mapAction(id: String, action: String) {
        val epoch = generation
        enqueue {
            checkCurrent(epoch)
            val services = requireNotNull(hostServices) { "Update WearTAK for map actions" }
            val incident = store.snapshot().firstOrNull { it.ask.id == id } ?: error("Incident not found")
            check(incident.serverId == null || incident.serverId == selectedServerId) { "Select incident on current CAD server" }
            val capabilities = services.capabilities()
            checkCurrent(epoch)
            check(action in capabilities.mapActions) { "Map action unavailable in WearTAK" }
            services.mapAction(action, incident)
            synchronized(gate) {
                checkCurrent(epoch)
                check(services === hostServices) { "Host connection changed" }
                message = if (action == C.ACTION_BLOODHOUND) "Bloodhound activation requested" else "Map focus requested"
            }
        }
    }

    private fun refreshCad() {
        val epoch = generation
        var completed = false
        try {
            refreshCadSnapshot(epoch)
            completed = true
        } finally {
            synchronized(gate) {
                if (!completed && epoch == generation && running) cadStatus = "CAD refresh failed; cached incidents"
            }
        }
    }

    private fun refreshCadSnapshot(epoch: Long) {
        checkCurrent(epoch)
        check(storageReady) { "Incident storage unavailable" }
        val services = requireNotNull(hostServices) { "Update WearTAK for CAD server access" }
        val capabilities = services.capabilities()
        val serverId = synchronized(gate) {
            checkCurrent(epoch)
            check(services === hostServices) { "Host connection changed" }
            hostCapabilities = capabilities
            if (capabilities.servers.none { it.id == selectedServerId }) selectedServerId = null
            if (selectedServerId == null && capabilities.servers.size == 1) selectedServerId = capabilities.servers.single().id
            selectedServerId
        }
        refreshIdentity()
        check(capabilities.takHttps) { "CAD HTTPS unavailable in WearTAK" }
        if (serverId == null) {
            synchronized(gate) {
                checkCurrent(epoch)
                check(services === hostServices) { "Host connection changed" }
                cadStatus = if (capabilities.servers.isEmpty()) "Connect a TAK server" else "Select CAD server"
                message = cadStatus
            }
            return
        }
        val records = services.incidents(serverId)
        synchronized(gate) {
            checkCurrent(epoch)
            check(services === hostServices && serverId == selectedServerId) { "CAD server changed; refresh incidents" }
            store.replaceCanonical(records, serverId)
            cadStatus = "CAD incidents current"
            message = cadStatus
        }
    }

    private fun checkCurrent(epoch: Long) {
        check(running && epoch == generation) { "Host stopped or changed; retry after reconnect" }
    }

    fun receive(xml: String) {
        require(xml.length <= CadProtocol.MAX_XML) { "CoT XML too large" }
        if (pendingReceives.incrementAndGet() > 64) {
            pendingReceives.decrementAndGet()
            throw IllegalStateException("Incoming CoT queue full; host must apply backpressure")
        }
        val epoch = generation
        enqueue {
            try {
                check(running && epoch == generation) { "Start plugin before forwarding CoT" }
                check(storageReady) { "Incident storage unavailable; recover storage before receiving events" }
                refreshIdentity()
                val event = CadProtocol.parse(xml)
                bridgeSeen = true
                if (event != null) {
                    val freshAsk = if (event is CadEvent.Ask) {
                        val previous = store.snapshot().firstOrNull { it.ask.id == event.id }
                        if (event.canonical != null) {
                            event.canonical.status == "ACTIVE" &&
                                event.canonical.involvement(callsign, responderVehicleUid()) == "Requested" &&
                                previous?.ask?.canonical?.involvement(callsign, responderVehicleUid()) != "Requested"
                        } else previous == null || previous.participation == Participation.OBSERVED
                    } else false
                    store.receive(event, System.currentTimeMillis())
                    message = "TAK-CAD update received"
                    if (freshAsk && event is CadEvent.Ask) {
                        control?.requestAlert(AlertRequestParcel(title = "Incident ask", message = event.title, severity = 1))
                    }
                }
            } finally {
                pendingReceives.decrementAndGet()
            }
        }
    }

    fun accept(id: String) = enqueue {
        val incident = store.snapshot().firstOrNull { it.ask.id == id } ?: error("Incident not found")
        require(incident.participation == Participation.ASKED) { "Ask is no longer pending" }
        routeAndDispatch(id)
    }

    fun decline(id: String) = enqueue {
        check(storageReady) { "Incident storage unavailable" }
        store.decline(id)
        message = "Declined locally; upstream has no decline message"
    }

    fun refresh(id: String) = enqueue {
        val incident = store.snapshot().firstOrNull { it.ask.id == id } ?: error("Incident not found")
        require(incident.participation == Participation.RESPONSE_QUEUED) { "Accept ask before refreshing route" }
        routeAndDispatch(id)
    }

    private fun routeAndDispatch(id: String) {
        check(storageReady) { "Incident storage unavailable" }
        val incident = store.snapshot().firstOrNull { it.ask.id == id } ?: error("Incident not found")
        check(incident.ask.canonical == null) { "CAD server connection required to respond" }
        val epoch = generation
        val host = control ?: error("Open WearTAK and enable TAK-CAD plugin")
        check(running && host.isCotConnected) { "WearTAK CoT offline; response not sent" }
        val uid = responderUid()
        require(uid.isNotBlank()) { "Configure WearTAK self UID in Routing setup" }
        val telemetry = requireNotNull(host.deviceStatus) { "WearTAK device telemetry unavailable" }.telemetry
        check(telemetry.getBoolean(DeviceStatusContract.KEY_LOCATION_AVAILABLE)) { "WearTAK GPS unavailable" }
        check(telemetry.containsKey(DeviceStatusContract.KEY_LAT) && telemetry.containsKey(DeviceStatusContract.KEY_LON)) {
            "WearTAK GPS coordinates missing"
        }
        val age = System.currentTimeMillis() - telemetry.getLong(DeviceStatusContract.KEY_SNAPSHOT_TIME_MS)
        check(age in -5_000..60_000) { "WearTAK GPS snapshot stale; refresh location" }
        val callsign = telemetry.getString(DeviceStatusContract.KEY_CALLSIGN).orEmpty()
        require(callsign.isNotBlank() && callsign.length <= 256) { "WearTAK callsign unavailable" }
        val start = Position(telemetry.getDouble(DeviceStatusContract.KEY_LAT), telemetry.getDouble(DeviceStatusContract.KEY_LON))
        require(incident.participation in setOf(Participation.ASKED, Participation.RESPONSE_QUEUED)) { "No pending or accepted ask" }
        val route = directions(start, requireNotNull(incident.ask.position) { "Incident location unavailable" }, secrets.read())
        val version = Math.addExact(incident.ownVersion, 1)
        val now = System.currentTimeMillis()
        val xml = CadProtocol.response(incident.ask, uid, callsign, version, route, now, start)
        synchronized(gate) {
            check(running && epoch == generation && host === control && host.isCotConnected) {
                "Host stopped or disconnected during routing; response not sent"
            }

            host.requestCotDispatch(CotRequestParcel("cot_xml", Bundle().apply { putString("xml", xml) }))
            // Host API is void: queue admission is not dispatcher delivery or assignment acknowledgement.
            try {
                store.recordDispatch(id, version, route, now)
            } catch (e: java.io.IOException) {
                storageReady = false
                throw java.io.IOException("Response handed to host but storage failed; automatic retries disabled", e)
            } catch (e: IllegalArgumentException) {
                storageReady = false
                throw IllegalStateException("Response handed to host but storage capacity/state failed; automatic retries disabled", e)
            }
        }
        message = "Response queued to WearTAK; dispatcher delivery unconfirmed"
    }

    private fun refreshIdentity() {
        callsign = control?.deviceStatus?.telemetry?.getString(DeviceStatusContract.KEY_CALLSIGN).orEmpty()
    }

    private fun enqueue(action: () -> Unit) {
        worker.execute { attempt(action) }
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
            lastError = null
        } catch (e: IllegalArgumentException) {
            failed(e.message ?: "Invalid TAK-CAD argument")
        } catch (e: IllegalStateException) {
            failed(e.message ?: "TAK-CAD state unavailable")
        } catch (e: java.io.IOException) {
            failed(e.message ?: "TAK-CAD network or storage unavailable")
        } catch (e: org.json.JSONException) {
            failed("Malformed TAK-CAD or routing JSON")
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            failed("Malformed CoT XML")
        } catch (e: java.security.GeneralSecurityException) {
            failed("Routing credential unavailable; replace key in setup")
        } catch (e: RemoteException) {
            failed("WearTAK host connection failed; reconnect plugin")
        } catch (e: SecurityException) {
            failed("Host denied operation; verify plugin authorization and signing")
        }
        changed()
    }

    private fun failed(text: String) {
        lastError = text
        Log.w("TakCad", text)
        notifyError(text)
    }

    private fun notifyError(text: String) {
        try {
            callback?.onPluginError(PluginErrorParcel(PluginErrorCodes.ILLEGAL_STATE, text, false))
        } catch (e: RemoteException) {
            Log.w("TakCad", "Host unavailable while reporting error")
        }
    }

    private fun changed() {
        listeners.forEach { it() }
        try {
            callback?.onPluginMainUiSchemaChanged()
            callback?.onPluginMainUiStateChanged()
        } catch (e: RemoteException) {
            Log.w("TakCad", "Host unavailable while notifying state")
        }
    }
}
