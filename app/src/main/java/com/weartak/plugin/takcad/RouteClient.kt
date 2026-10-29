// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class RouteClient {
    fun directions(start: Position, destination: Position, key: String): Route {
        require(key.isNotBlank() && key.length <= 4096 && key.none { it.isISOControl() }) { "Configure a valid routing API key" }
        val url = URL("https://api.openrouteservice.org/v2/directions/driving-car" +
            "?start=${start.longitude},${start.latitude}&end=${destination.longitude},${destination.latitude}")
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", key)
            connection.setRequestProperty("Accept", "application/geo+json")
            val status = connection.responseCode
            if (status != 200) throw IOException("OpenRouteService HTTP $status; check key, quota, and coverage")
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= CadProtocol.MAX_XML / 2) { "Routing response exceeds transport limit" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return Route.parse(body)
        } finally {
            connection.disconnect()
        }
    }
}
