// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CadApplication::class)
class ResponderUiTest {
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
            assertTrue(children.filterIsInstance<TextView>().any { it.text == "No incidents" })
            assertFalse(children.filterIsInstance<TextView>().any { it.text.contains("protocol extension") })
            assertEquals(R.drawable.ic_launcher, activity.applicationInfo.icon)
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
            assertTrue(binder.mainUiSchema.cards.single { it.cardId == "takcad.incidents.refresh" }
                .content.getBoolean("enabled"))
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
