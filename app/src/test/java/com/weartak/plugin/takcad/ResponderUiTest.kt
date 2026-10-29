// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
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
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val scroll = root.getChildAt(0) as ScrollView
            val content = scroll.getChildAt(0) as LinearLayout
            val children = (0 until content.childCount).map { content.getChildAt(it) }
            assertTrue(children.filterIsInstance<TextView>().any { it.text.contains("No incident asks") })
            children.filterIsInstance<Button>().single { it.text == activity.getString(R.string.configuration) }.performClick()
            val setup = (root.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
            assertTrue((0 until setup.childCount).map { setup.getChildAt(it) }.filterIsInstance<TextView>()
                .any { it.text.contains("OpenRouteService") })
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
}
