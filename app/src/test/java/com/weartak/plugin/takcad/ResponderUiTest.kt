// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CadApplication::class)
class ResponderUiTest {
    @Test fun incidentDetailStartsWithAccessibleMapIconActions() {
        val runtime = (RuntimeEnvironment.getApplication() as CadApplication).runtime
        runtime.awaitIdle()
        val canonical = CanonicalIncident.parse(JSONObject()
            .put("uid", "incident")
            .put("incidentName", "Exercise")
            .put("status", "ACTIVE")
            .put("location", JSONObject().put("coords", JSONObject()
                .put("latitudeDeg", 38).put("longitudeDeg", -77)))
            .toString())
        runtime.store.receive(canonical.ask(), 1)
        val controller = Robolectric.buildActivity(ResponderActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            var content = ((root.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout)
            val open = content.children().filterIsInstance<Button>().single { it.text.toString().startsWith("Exercise") }
            assertEquals("Exercise", open.text)
            assertFalse(open.text.contains("ACTIVE"))
            open.performClick()
            content = ((root.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout)
            val children = content.children()
            val backRow = children.first() as LinearLayout
            assertEquals(Gravity.END, backRow.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
            val back = backRow.children().single() as ImageButton
            assertEquals("Back", back.contentDescription)
            val mapActions = children[1] as LinearLayout
            assertEquals(Gravity.CENTER_HORIZONTAL, mapActions.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            val icons = mapActions.children().filterIsInstance<ImageButton>()
            assertEquals(listOf("Add note", "Start Bloodhound", "Focus incident on map"),
                icons.map { it.contentDescription.toString() })
            assertEquals(3, mapActions.childCount)
            val dp = activity.resources.displayMetrics.density
            assertTrue(icons.all { it.layoutParams.width == (48 * dp).toInt() &&
                it.layoutParams.height == (48 * dp).toInt() })
            assertEquals(listOf(0, (4 * dp).toInt(), (4 * dp).toInt()), icons.map {
                (it.layoutParams as LinearLayout.LayoutParams).marginStart
            })
            assertTrue(icons.none { it.isEnabled })
            assertEquals("Active Incident", (children[2] as TextView).text)
            assertTrue((children[3] as TextView).text.contains("ACTIVE"))
            assertFalse(children.filterIsInstance<TextView>().any {
                it.text in listOf("Add note", "Bloodhound", "Target", "Navigation", "Back")
            })
            assertFalse(children.filterIsInstance<Button>().any { it.text.toString().startsWith("Status") })
            assertFalse(children.filterIsInstance<TextView>().any {
                it.text in listOf("CAD update", "Status", "Navigation", "DEV", "SIM")
            })
            assertEquals(R.drawable.bloodhound, shadowOf(icons[1].drawable).createdFromResId)
            back.performClick()
            content = ((root.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout)
            assertEquals("Active Server", (content.getChildAt(0) as TextView).text)
        } finally {
            controller.pause().stop().destroy()
            runtime.close()
        }
    }

    private fun LinearLayout.children(): List<android.view.View> =
        (0 until childCount).map(::getChildAt)

    @Test fun incidentIconsRespectCoordinatesAndCapabilitiesAndLaunchExistingActions() {
        val runtime = (RuntimeEnvironment.getApplication() as CadApplication).runtime
        runtime.awaitIdle()
        val record = JSONObject().put("uid", "incident").put("incidentName", "Exercise").put("status", "ACTIVE")
            .put("notes", org.json.JSONArray().put(JSONObject().put("uid", "note").put("info", "Existing note")))
        val maps = mutableListOf<Bundle>()
        var activeBloodhound: String? = null
        val services = object : IWearTakHostServices.Stub() {
            override fun getCapabilities() = C.result(C.OK).apply {
                putInt(C.KEY_VERSION, C.VERSION)
                putBoolean(C.KEY_TAK_HTTPS, true)
                putStringArrayList(C.KEY_MAP_ACTIONS,
                    arrayListOf(C.ACTION_TARGET, C.ACTION_BLOODHOUND, CadHostServices.ACTION_STOP_BLOODHOUND))
                activeBloodhound?.let { putString(CadHostServices.ACTIVE_BLOODHOUND_UID, it) }
                putParcelableArrayList(C.KEY_SERVERS, arrayListOf(Bundle().apply {
                    putString(C.KEY_SERVER_ID, "server")
                    putString(C.KEY_SERVER_NAME, "Server")
                }))
            }
            override fun requestTakServer(request: Bundle) = C.result(C.OK).apply {
                putInt(C.KEY_HTTP_STATUS, 200)
                putString(C.KEY_SERVER_ID, "server")
                putString(C.KEY_BODY, org.json.JSONArray().put(record).toString())
            }
            override fun requestMapAction(request: Bundle): Bundle {
                maps += Bundle(request)
                when (request.getString(C.KEY_ACTION)) {
                    C.ACTION_BLOODHOUND -> activeBloodhound = request.getString(C.KEY_UID)
                    CadHostServices.ACTION_STOP_BLOODHOUND -> activeBloodhound = null
                }
                return C.result(C.OK)
            }
        }
        runtime.attach(IWearTakPluginControl.Default(), CadHostServices(services))
        runtime.start()
        runtime.awaitIdle()
        val controller = Robolectric.buildActivity(ResponderActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun content() = (activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                .getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
            content().children().filterIsInstance<Button>()
                .single { it.text.toString().startsWith("Exercise") }.performClick()
            fun icons() = (content().getChildAt(1) as LinearLayout).children().filterIsInstance<ImageButton>()
            assertEquals(listOf(true, false, false), icons().map { it.isEnabled })
            assertTrue(content().children().filterIsInstance<TextView>().any { it.text.contains("Existing note") })
            icons()[0].performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(NoteActivity::class.java.name, intent.component!!.className)
            assertEquals("incident", intent.getStringExtra(NoteActivity.EXTRA_INCIDENT_ID))
            record.put("location", JSONObject().put("coords", JSONObject()
                .put("latitudeDeg", 38).put("longitudeDeg", -77)))
            runtime.refreshIncidents()
            runtime.awaitIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(listOf(true, true, true), icons().map { it.isEnabled })
            icons()[1].performClick()
            runtime.awaitIdle()
            runtime.awaitIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("Stop Bloodhound", icons()[1].contentDescription)
            assertEquals(R.drawable.ic_stop, shadowOf(icons()[1].drawable).createdFromResId)
            icons()[2].performClick()
            runtime.awaitIdle()
            assertEquals(listOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET), maps.map { it.getString(C.KEY_ACTION) })
            assertTrue(maps.first().getBoolean(CadHostServices.RETURN_TO_MAIN))
            assertFalse(maps.last().containsKey(CadHostServices.RETURN_TO_MAIN))
            assertTrue(maps.all { it.getString(C.KEY_UID) == "incident" &&
                it.getDouble(C.KEY_LATITUDE) == 38.0 && it.getDouble(C.KEY_LONGITUDE) == -77.0 })
            record.remove("location")
            runtime.refreshIncidents()
            runtime.awaitIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(listOf(true, true, false), icons().map { it.isEnabled })
            icons()[1].performClick()
            runtime.awaitIdle()
            runtime.awaitIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("Start Bloodhound", icons()[1].contentDescription)
            assertEquals(setOf(C.KEY_ACTION, C.KEY_UID), maps.last().keySet())
            assertEquals(CadHostServices.ACTION_STOP_BLOODHOUND, maps.last().getString(C.KEY_ACTION))
            runtime.stop()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(listOf(false, false, false), icons().map { it.isEnabled })
        } finally {
            controller.pause().stop().destroy()
            runtime.close()
        }
    }

    @Test fun launcherShowsEmptyStateAndSetupWithoutHost() {
        val runtime = (RuntimeEnvironment.getApplication() as CadApplication).runtime
        runtime.awaitIdle()
        val controller = Robolectric.buildActivity(ResponderActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("TAK CAD", activity.getString(R.string.app_name))
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val scroll = root.getChildAt(0) as ScrollView
            val content = scroll.getChildAt(0) as LinearLayout
            val children = (0 until content.childCount).map { content.getChildAt(it) }
            assertEquals("Active Server", (children.first() as TextView).text)
            assertEquals("Select \u25BE", (children[1] as Button).text)
            assertEquals("Active Incident", (children[2] as TextView).text)
            assertFalse(children.filterIsInstance<TextView>().any { it.text.contains("protocol extension") })
            assertEquals(R.mipmap.ic_launcher, activity.applicationInfo.icon)
            assertNotNull(activity.getDrawable(activity.applicationInfo.icon))
            children.filterIsInstance<Button>().single { it.text == activity.getString(R.string.configuration) }.performClick()
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            val setup = (root.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
            assertTrue((0 until setup.childCount).map { setup.getChildAt(it) }.filterIsInstance<android.widget.EditText>()
                .any { it.hint == activity.getString(R.string.ors_key) })
            assertFalse((0 until setup.childCount).map { setup.getChildAt(it) }.filterIsInstance<TextView>()
                .any { it.text.contains("Leave blank") || it.text.contains("Routing sends") })
        } finally {
            controller.pause().stop().destroy()
            runtime.close()
        }
    }

    @Test fun binderRequiresInitializationAndRejectsMismatchedHost() {
        val runtime = (RuntimeEnvironment.getApplication() as CadApplication).runtime
        val controller = Robolectric.buildService(CadService::class.java).create()
        try {
            val service = controller.get()
            assertNull(service.onBind(Intent("unrelated.action")))
            val binder = IWearTakPlugin.Stub.asInterface(service.onBind(Intent("com.weartak.plugin.action.PLUGIN_SERVICE")))
            assertEquals(BuildConfig.APPLICATION_ID, binder.pluginInfo.pluginId)
            assertEquals(BuildConfig.APPLICATION_ID, binder.pluginInfo.packageName)
            assertEquals(
                R.mipmap.ic_launcher,
                service.packageManager.getApplicationInfo(binder.pluginInfo.packageName, 0).icon,
            )
            assertEquals("TAK CAD", binder.pluginInfo.displayName)
            assertFalse(binder.capabilities.cotReadyEvents)
            assertTrue(binder.capabilities.hostedMainUi)
            assertFalse(binder.capabilities.externalMainActivity)
            assertThrows(IllegalStateException::class.java) {
                binder.invokeMainUiAction("takcad.receive", Bundle())
            }
            assertThrows(IllegalArgumentException::class.java) {
                binder.initializePluginV2(PluginInitParcel("not.host", -1, BuildConfig.APPLICATION_ID), IWearTakPluginControl.Default(), null)
            }
            val context = RuntimeEnvironment.getApplication()
            binder.initializePluginV3(
                PluginInitParcel(context.packageName, android.os.Binder.getCallingUid(), BuildConfig.APPLICATION_ID),
                IWearTakPluginControl.Default(), null, null,
            )
            binder.startPlugin()
            binder.invokeMainUiAction("takcad.receive", Bundle().apply { putString("xml", askXml()) })
            runtime.awaitIdle()
            assertEquals(ASK, runtime.store.snapshot().single().ask)
            binder.invokeMainUiAction("takcad.incident.open", Bundle().apply { putString("incidentId", ASK.id) })
            assertTrue(binder.mainUiSchema.cards.any { it.cardId == "takcad.incident.accept" })
            binder.stopPlugin()
            binder.shutdownPlugin()
        } finally {
            controller.destroy()
            runtime.close()
        }
    }

    @Test fun validatedInitializationDiscoversOptionalHostServicesAndShutdownRevokesConsumer() {
        val context = RuntimeEnvironment.getApplication() as CadApplication
        val runtime = context.runtime
        val controller = Robolectric.buildService(CadService::class.java).create()
        val calls = mutableListOf<Thread>()
        val services = object : IWearTakHostServices.Stub() {
            override fun getCapabilities(): Bundle {
                calls += Thread.currentThread()
                return C.result(C.OK).apply {
                    putInt(C.KEY_VERSION, C.VERSION)
                    putBoolean(C.KEY_TAK_HTTPS, true)
                    putStringArrayList(C.KEY_MAP_ACTIONS, arrayListOf(C.ACTION_TARGET))
                    putParcelableArrayList(C.KEY_SERVERS, arrayListOf<Bundle>())
                }

                @Test fun hostedPresentationRequiresIntegerAdvertisementAndShutdownRevokesIt() {
                    val context = RuntimeEnvironment.getApplication() as CadApplication
                    val runtime = context.runtime
                    runtime.awaitIdle()
                    runtime.store.receive(CanonicalIncident.parse(JSONObject().put("uid", "incident")
                        .put("incidentName", "Exercise").put("status", "ACTIVE").toString()).ask(), 1)
                    val key = "com.weartak.hostedUi.actionPresentationVersion"
                    val advertisements = listOf(
                        Bundle(),
                        Bundle().apply { putString(key, "1") },
                        Bundle().apply { putBoolean(key, true) },
                        Bundle().apply { putInt(key, 0) },
                        Bundle().apply { putInt(key, 1) },
                    )
                    try {
                        advertisements.forEachIndexed { index, extras ->
                            val controller = Robolectric.buildService(CadService::class.java).create()
                            try {
                                val binder = IWearTakPlugin.Stub.asInterface(
                                    controller.get().onBind(Intent("com.weartak.plugin.action.PLUGIN_SERVICE")))
                                val init = PluginInitParcel(context.packageName, android.os.Binder.getCallingUid(), BuildConfig.APPLICATION_ID)
                                init.hostExtras.putAll(extras)
                                binder.initializePluginV2(init, IWearTakPluginControl.Default(), null)
                                binder.invokeMainUiAction("takcad.incident.open", Bundle().apply { putString("incidentId", "incident") })
                                val actions = binder.mainUiSchema.cards.filter { it.cardType == "ACTION" }
                                assertEquals(index == 4, actions.any { it.content.getString("actionPresentation") == "ICON" })
                                if (index != 4) assertTrue(actions.all {
                                    it.content.keySet() == setOf("actionId", "actionLabel", "enabled", "actionArgsJson")
                                })
                                binder.shutdownPlugin()
                                assertTrue(binder.mainUiSchema.cards.filter { it.cardType == "ACTION" }.all {
                                    it.content.keySet() == setOf("actionId", "actionLabel", "enabled", "actionArgsJson")
                                })
                            } finally { controller.destroy() }
                        }
                    } finally { runtime.close() }
                }
            }
            override fun requestMapAction(request: Bundle) = C.result(C.UNAVAILABLE)
            override fun requestTakServer(request: Bundle) = C.result(C.UNAVAILABLE)
        }
        try {
            val binder = IWearTakPlugin.Stub.asInterface(
                controller.get().onBind(Intent("com.weartak.plugin.action.PLUGIN_SERVICE")))
            val init = PluginInitParcel(context.packageName, android.os.Binder.getCallingUid(), BuildConfig.APPLICATION_ID)
            init.hostExtras.putInt(C.EXTRA_VERSION, C.VERSION)
            init.hostExtras.putBinder(C.EXTRA_BINDER, services)
            binder.initializePluginV2(init, IWearTakPluginControl.Default(), null)
            binder.startPlugin()
            runtime.awaitIdle()
            assertTrue(runtime.supportsCad())
            assertEquals("Connect a TAK server", runtime.cadStatus)
            assertEquals("CAD server", binder.mainUiState.getString("kv_row2_label"))
            assertEquals("Connect a TAK server", binder.mainUiState.getString("kv_row2_value"))
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.all { it !== android.os.Looper.getMainLooper().thread })
            assertFalse(binder.mainUiSchema.cards.any { it.cardId == "takcad.incidents.refresh" })
            binder.stopPlugin()
            binder.shutdownPlugin()
            assertFalse(runtime.supportsCad())
            assertFalse(runtime.canMapAction(C.ACTION_TARGET))
        } finally {
            controller.destroy()
            runtime.close()
        }
    }
}
