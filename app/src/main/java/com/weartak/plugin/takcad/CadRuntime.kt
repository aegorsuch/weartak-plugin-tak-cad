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
    private val clock: () -> Long = System::currentTimeMillis,
    private val directions: (Position, Position, String) -> Route = RouteClient()::directions,
) {
    companion object {
        internal const val CAD_POLL_MS = 5_000L
        internal const val CAD_MAX_BACKOFF_MS = 300_000L
        private const val SERVER_PREFERENCE = "cadServerId"
        private const val NOTE_DRAFT_PREFIX = "noteDraft."
    }

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
    @Volatile private var cadFailures = 0
    @Volatile private var nextAutoRefreshAt = 0L
    @Volatile var cadStatus = "Update WearTAK for CAD server access"
        private set
    @Volatile var bridgeSeen = false
        private set
    @Volatile var message = "Awaiting host"
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var writing = false
    /** Last CAD write failure; persists across polls so the user sees why input was kept. */
    @Volatile var writeError: String? = null
        private set
    @Volatile var writeNotice: String? = null
        private set

    init {
        worker.scheduleWithFixedDelay({
            if (running && storageReady) {
                store.snapshot().filter { it.participation == Participation.RESPONSE_QUEUED }.forEach { incident ->
                    attempt { routeAndDispatch(incident.ask.id) }
                }
            }
        }, 10, 10, TimeUnit.SECONDS)
        worker.scheduleWithFixedDelay({ pollCad() }, CAD_POLL_MS, CAD_POLL_MS, TimeUnit.MILLISECONDS)
    }

    /** Automatic refresh; repeated failures back off exponentially instead of polling every interval. */
    private fun pollCad() {
        if (running && storageReady && hostServices != null && clock() >= nextAutoRefreshAt) attempt { refreshCad() }
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
    fun canSelectServer(): Boolean = running && hostCapabilities.servers.isNotEmpty()
    fun servers(): List<CadServer> = hostCapabilities.servers
    fun selectedServer(): String? = selectedServerId
    fun selectedServerName(): String? = hostCapabilities.servers.firstOrNull { it.id == selectedServerId }?.name
    fun notifyHostedNavigation() = changed()
    internal fun close() { worker.shutdownNow() }
    internal fun awaitIdle() { worker.submit {}.get(30, TimeUnit.SECONDS) }
    internal fun pollNow() { worker.submit { pollCad() }.get(30, TimeUnit.SECONDS) }
    internal fun cadRetryDelayMs(): Long = (nextAutoRefreshAt - clock()).coerceAtLeast(0)

    fun attach(host: IWearTakPluginControl, services: CadHostServices? = null) {
        synchronized(gate) {
            generation++
            control = host
            hostServices = services
            hostCapabilities = CadHostCapabilities(emptyList(), emptySet(), false)
            selectedServerId = null
            resetCadBackoff()
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
            resetCadBackoff()
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
            resetCadBackoff()
            refreshCad()
        }
    }

    /** Re-reads host server list for the selector without contacting any CAD server. */
    fun refreshServers() {
        val epoch = generation
        enqueue {
            checkCurrent(epoch)
            val services = requireNotNull(hostServices) { "Update WearTAK for CAD server access" }
            val capabilities = services.capabilities()
            synchronized(gate) {
                checkCurrent(epoch)
                check(services === hostServices) { "Host connection changed" }
                hostCapabilities = capabilities
            }
            changed()
        }
    }

    /** Selects and persists [serverId], then refreshes incidents from it once. */
    fun selectServer(serverId: String) {
        val epoch = generation
        enqueue {
            checkCurrent(epoch)
            val services = requireNotNull(hostServices) { "Update WearTAK for CAD server access" }
            val capabilities = services.capabilities()
            synchronized(gate) {
                checkCurrent(epoch)
                check(services === hostServices) { "Host connection changed" }
                hostCapabilities = capabilities
                require(capabilities.servers.any { it.id == serverId }) { "CAD server no longer connected" }
                check(settings.edit().putString(SERVER_PREFERENCE, serverId).commit()) { "Could not save CAD server" }
                selectedServerId = serverId
                resetCadBackoff()
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

    fun canWrite(): Boolean = running && hostServices != null && selectedServerId != null && !writing
    fun isWriting(): Boolean = writing
    fun noteDraft(id: String): String = settings.getString(NOTE_DRAFT_PREFIX + id, "") ?: ""
    fun saveNoteDraft(id: String, text: String) {
        settings.edit().apply {
            if (text.isBlank()) remove(NOTE_DRAFT_PREFIX + id) else putString(NOTE_DRAFT_PREFIX + id, text)
        }.apply()
    }
    /** Caller is responsible for notifying listeners. */
    fun clearWriteResult() {
        if (writing) return
        writeError = null
        writeNotice = null
    }

    /** Draft is persisted before sending and only cleared once CAD confirms the note. */
    fun addNote(id: String, text: String) {
        saveNoteDraft(id, text)
        write("Note not saved; draft kept") { epoch, services, serverId ->
            val info = text.trim()
            require(info.isNotEmpty()) { "Note is empty" }
            require(info.length <= CadStatus.MAX_NOTE_CHARS) { "Note over ${CadStatus.MAX_NOTE_CHARS} characters" }
            val incident = writableIncident(id, serverId)
            refreshIdentity()
            val creator = callsign.trim()
            check(creator.isNotEmpty()) { "Watch callsign unavailable" }
            val timestamp = java.time.Instant.ofEpochMilli(clock()).toString()
            services.insertNote(serverId, CadNoteDraft(incident.ask.id, timestamp, creator, info))
            saveNoteDraft(id, "")
            writeNotice = "Note added"
            refreshAfterWrite(epoch, "Note added")
        }
    }

    /** Read-modify-write so only status changes; the server always answers 200, so verify by re-reading. */
    fun setStatus(id: String, status: String) {
        require(status in CadStatus.VALUES) { "Invalid incident status" }
        write("Status not changed") { epoch, services, serverId ->
            writableIncident(id, serverId)
            val current = services.incidentJson(serverId, id)
            checkCurrent(epoch)
            val label = CadStatus.label(status)
            if (CanonicalIncident.parse(current).status != status) {
                services.updateStatus(serverId, current, status)
                checkCurrent(epoch)
                check(CanonicalIncident.parse(services.incidentJson(serverId, id)).status == status) {
                    "CAD did not apply status change"
                }
            }
            writeNotice = "Status set to $label"
            refreshAfterWrite(epoch, "Status set to $label")
        }
    }

    private fun write(failurePrefix: String, action: (Long, CadHostServices, String) -> Unit) {
        val epoch = generation
        synchronized(gate) {
            if (writing) {
                writeError = "Wait for current CAD update"
                changed()
                return
            }
            writing = true
            writeError = null
            writeNotice = null
        }
        changed()
        enqueue {
            try {
                checkCurrent(epoch)
                val services = requireNotNull(hostServices) { "Update WearTAK for CAD server access" }
                val serverId = requireNotNull(selectedServerId) { "Select CAD server" }
                action(epoch, services, serverId)
            } catch (e: Exception) {
                writeNotice = null
                writeError = "$failurePrefix: ${e.message ?: e.javaClass.simpleName}"
                throw e
            } finally {
                writing = false
            }
        }
    }

    private fun writableIncident(id: String, serverId: String): Incident {
        val incident = store.snapshot().firstOrNull { it.ask.id == id && it.ask.canonical != null }
            ?: error("Incident not found; refresh incidents")
        check(incident.serverId == serverId) { "Select incident on current CAD server" }
        return incident
    }

    private fun refreshAfterWrite(epoch: Long, done: String) {
        try {
            refreshCad()
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            writeNotice = "$done; refresh failed"
            Log.w("TakCad", "Refresh after CAD write failed", e)
        }
        checkCurrent(epoch)
    }

    private fun refreshCad() {
        val epoch = generation
        var completed = false
        try {
            refreshCadSnapshot(epoch)
            completed = true
        } finally {
            synchronized(gate) {
                if (epoch == generation && running) {
                    if (completed) {
                        resetCadBackoff()
                    } else {
                        cadStatus = "CAD refresh failed; cached incidents"
                        cadFailures = (cadFailures + 1).coerceAtMost(16)
                        val delay = (CAD_POLL_MS shl (cadFailures - 1).coerceAtMost(10)).coerceAtMost(CAD_MAX_BACKOFF_MS)
                        nextAutoRefreshAt = clock() + delay
                        Log.i("TakCad", "CAD refresh failure $cadFailures; next automatic retry in ${delay / 1000}s")
                    }
                }
            }
        }
    }

    private fun resetCadBackoff() {
        cadFailures = 0
        nextAutoRefreshAt = 0L
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
            val preferred = settings.getString(SERVER_PREFERENCE, null)
            if (capabilities.servers.none { it.id == selectedServerId }) selectedServerId = null
            if (selectedServerId == null && capabilities.servers.any { it.id == preferred }) selectedServerId = preferred
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
