// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.os.Bundle
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices

data class CadServer(val id: String, val name: String)
data class CadHostCapabilities(val servers: List<CadServer>, val mapActions: Set<String>, val takHttps: Boolean)

/** Synchronous adapter; callers run exclusively on the responder worker. */
class CadHostServices(private val services: IWearTakHostServices) {
    fun capabilities(): CadHostCapabilities {
        val result = checked(services.capabilities)
        check(result.getInt(C.KEY_VERSION) == C.VERSION) { "Unsupported host services version" }
        @Suppress("DEPRECATION")
        val servers = result.getParcelableArrayList<Bundle>(C.KEY_SERVERS).orEmpty().map {
            CadServer(requireNotNull(it.getString(C.KEY_SERVER_ID)) { "Host server ID missing" },
                requireNotNull(it.getString(C.KEY_SERVER_NAME)) { "Host server name missing" })
        }
        require(servers.size <= 100 && servers.all { it.id.isNotBlank() && it.id.length <= 256 && it.name.length <= 512 })
        require(servers.map { it.id }.distinct().size == servers.size) { "Duplicate host server ID" }
        return CadHostCapabilities(servers.sortedWith(compareBy<CadServer> { it.name }.thenBy { it.id }),
            result.getStringArrayList(C.KEY_MAP_ACTIONS).orEmpty().toSet(), result.getBoolean(C.KEY_TAK_HTTPS))
    }

    fun incidents(serverId: String): List<CanonicalIncident> {
        val result = checked(services.requestTakServer(Bundle().apply {
            putString(C.KEY_SERVER_ID, serverId)
            putString(C.KEY_METHOD, "GET")
            putString(C.KEY_PATH, "/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit")
            putString(C.KEY_QUERY, "fn=getIncidents")
        }))
        check(result.getString(C.KEY_SERVER_ID) == serverId) { "CAD server changed; refresh incidents" }
        check(result.containsKey(C.KEY_HTTP_STATUS)) { "CAD HTTP status missing" }
        val status = result.getInt(C.KEY_HTTP_STATUS)
        check(status == 200) { "CAD HTTP $status; verify selected server and CAD access" }
        val body = requireNotNull(result.getString(C.KEY_BODY)) { "CAD response body missing" }
        require(body.toByteArray(Charsets.UTF_8).size <= C.MAX_RESPONSE_BYTES) { "CAD response exceeds host limit" }
        return CanonicalIncident.parseList(body)
    }

    fun mapAction(action: String, incident: Incident) {
        require(action in setOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET)) { "Unsupported map action" }
        val position = requireNotNull(incident.ask.position) { "Incident location unavailable" }
        checked(services.requestMapAction(Bundle().apply {
            putString(C.KEY_ACTION, action)
            putString(C.KEY_UID, incident.ask.id)
            putString(C.KEY_LABEL, incident.ask.title.take(256))
            putDouble(C.KEY_LATITUDE, position.latitude)
            putDouble(C.KEY_LONGITUDE, position.longitude)
        }))
    }

    private fun checked(result: Bundle?): Bundle {
        val response = requireNotNull(result) { "Host services response missing" }
        val status = response.getString(C.KEY_STATUS)
        check(status == C.OK) { "Host services ${status ?: "invalid response"}; reconnect or retry action" }
        return response
    }
}
