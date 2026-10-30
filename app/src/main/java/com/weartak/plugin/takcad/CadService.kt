// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.validation.SettingsApplyValidator

class CadService : Service() {
    private val lifecycle = PluginLifecycleMachine()
    private val runtime get() = (application as CadApplication).runtime
    private val hostedUi by lazy { HostedResponderUi(runtime) }
    @Volatile private var hostUid: Int? = null
    private var hostCallback: IWearTakHostCallback? = null

    private fun transition(event: PluginLifecycleEvent) {
        val result = lifecycle.dispatch(event)
        check(result !is PluginLifecycleTransition.Rejected) { "Invalid plugin lifecycle transition: $event" }
    }

    private fun requireHost() {
        check(hostUid != null && Binder.getCallingUid() == hostUid) { "Caller is not initialized host" }
        check(lifecycle.snapshot() != PluginLifecycleState.SHUTDOWN) { "Plugin shut down; rebind service" }
    }

    private val binder = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(
            BuildConfig.APPLICATION_ID, getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID,
            "Responder incident asks, routing and ETAs. Incoming CoT requires host bridge.",
        )
        override fun getApiVersion() = WearTakPluginContract.CONTRACT_API_VERSION
        override fun getCapabilities() = PluginCapabilitiesParcel(
            hostedSettingsUi = false, hostedMainUi = true, externalMainActivity = false,
        )
        override fun getStatus() = PluginStatusParcel(lifecycle.snapshot(), runtime.lastError ?: runtime.message)

        override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) {
            initializePluginV2(init, control, null)
        }

        override fun initializePluginV3(
            init: PluginInitParcel?,
            control: IWearTakPluginControl?,
            controlV2: IWearTakPluginHostControlV2?,
            controlV3: IWearTakPluginHostControlV3?,
        ) {
            initializePluginV2(init, control, controlV2)
        }

        override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?) {
            requireNotNull(init) { "Missing host initialization" }
            requireNotNull(control) { "Missing host CoT control" }
            require(init.hostUid == Binder.getCallingUid()) { "Host UID mismatch" }
            require(packageManager.getPackagesForUid(init.hostUid)?.contains(init.hostPackageName) == true) {
                "Host package does not match caller UID"
            }
            val validation = PluginMetadataValidator.validateInitAgainstPlugin(init, BuildConfig.APPLICATION_ID)
            require(validation is ContractValidationResult.Ok) { "Host/plugin metadata mismatch" }
            transition(PluginLifecycleEvent.INITIALIZED)
            hostUid = init.hostUid
            runtime.attach(control)
        }

        override fun startPlugin() {
            requireHost()
            transition(PluginLifecycleEvent.HOST_STARTED)
            runtime.start()
        }

        override fun stopPlugin() {
            requireHost()
            runtime.stop()
            transition(PluginLifecycleEvent.HOST_STOPPED)
        }

        override fun shutdownPlugin() {
            requireHost()
            runtime.stop(detach = true)
            transition(PluginLifecycleEvent.HOST_SHUTDOWN)
            hostCallback = null
            hostUid = null
        }

        override fun registerHostCallback(callback: IWearTakHostCallback?) {
            // Hosts may register callback before initializePluginV2.
            if (hostUid != null) requireHost()
            hostCallback = callback
            runtime.setCallback(callback)
        }

        override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
            requireHost()
            if (hostCallback?.asBinder() == callback?.asBinder()) {
                hostCallback = null
                runtime.setCallback(null)
            }
        }

        override fun onHostSettingsChanged(settings: HostSettingsParcel?) { requireHost() }
        override fun getSettingsSchema() = SettingsSchemaParcel(title = "Hosted routing setup requires secure input support")
        override fun getSettingsValues() = Bundle()
        override fun applySettings(values: Bundle?) { applySettingsWithResult(values, SettingsApplyMode.PARTIAL_PATCH) }
        override fun applySettingsWithResult(values: Bundle?, mode: Int): SettingsApplyResultParcel {
            requireHost()
            return SettingsApplyValidator.applySettingsWithResult(getSettingsSchema(), Bundle(), values, null, mode)
        }

        override fun getMainUiSchema() = hostedUi.schema()

        override fun getMainUiState() = Bundle().apply {
            putString("last_message", runtime.lastError ?: runtime.message)
            putString("kv_row1_label", "Incident asks")
            putString("kv_row1_value", runtime.store.snapshot().count { it.participation == Participation.ASKED }.toString())
            putString("kv_row2_label", "Incoming CoT bridge")
            putString("kv_row2_value", if (runtime.bridgeSeen) "Observed" else "Not observed; host forwarding required")
            putString("kv_row3_label", "Response delivery")
            putString("kv_row3_value", "Host queue only; no dispatcher acknowledgement")
        }

        override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
            requireHost()
            when (actionId) {
                "takcad.receive" -> {
                    val xml = requireNotNull(args?.getString("xml")) { "Missing CoT XML" }
                    require(xml.length <= CadProtocol.MAX_XML) { "CoT XML too large" }
                    runtime.receive(xml)
                }
                else -> hostedUi.invoke(actionId, args)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != "com.weartak.plugin.action.PLUGIN_SERVICE") return null
        transition(PluginLifecycleEvent.BIND_ATTACHED)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runtime.stop(detach = true)
        hostUid = null
        hostCallback = null
        lifecycle.dispatch(PluginLifecycleEvent.RESET)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runtime.stop(detach = true)
        super.onDestroy()
    }
}
