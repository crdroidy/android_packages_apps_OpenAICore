# OpenAICore

An open on-device intelligence stack for crDroid, built on the AOSP on-device-intelligence
platform API rather than on Google's AICore.

## What this is not

It is **not** a drop-in replacement for `com.google.android.aicore`, and it does not try to be.
ML Kit's GenAI APIs — the thing third-party apps actually call — are documented as unsupported on
devices with an unlocked bootloader, which every crDroid install has. Their client libraries bind
Google's AICore through Play Services over private AIDL. Chasing binary compatibility means
reverse-engineering that surface and still losing to integrity checks.

## What it is

An implementation of the platform seam AOSP already ships for exactly this purpose:

| Layer | Component | Process |
|---|---|---|
| 4 | `IntelligenceClient` (`openaicore-sdk`) | caller's |
| 3 | `android.app.ondeviceintelligence.OnDeviceIntelligenceManager` | caller's (platform, unmodified) |
| — | `OnDeviceIntelligenceManagerService` | system_server (platform, unmodified) |
| 2 | `IntelligenceBrokerService` | `org.crdroid.intelligence` |
| 1 | `InferenceService` | isolated uid, `isolated_compute_app` |
| 0 | `libopenaicore_litertlm.so` over LiteRT-LM | same isolated process |

plus `org.crdroid.intelligence.models`, a **separate package** that holds the only network access.

## Things that are load-bearing and easy to break

**The inference service must be `android:isolatedProcess="true"`.** This is not a design
preference. `OnDeviceIntelligenceManagerService.validateServiceElevated` throws `SecurityException`
at bind time unless `FLAG_ISOLATED_PROCESS` is set and `FLAG_EXTERNAL_SERVICE` is not. Everything
else follows from it: no network, no opening files by path, and hence the file-descriptor provider.

**The intent action is what grants GPU access.** `ActiveServices.generateAdditionalSeInfoFromService`
appends `:isolatedComputeApp` for services bound with the `OnDeviceSandboxedInferenceService`
action, which lands the process in the `isolated_compute_app` SELinux domain. That domain gets
`gpu_sphal_use`; plain `isolated_app` is explicitly denied `gpu_device` access. Change the action
and ML Drift stops working with no obvious cause.

**No SELinux policy changes are needed.** AOSP 17 already ships `isolated_compute_app` with GPU
access and the FD-read rules this design depends on. Adding a custom domain would be strictly
worse than the one the platform already assigns.

**The broker must not request `INTERNET`.** The `SYSTEM_VENDOR_INTELLIGENCE` and
`SYSTEM_UI_INTELLIGENCE` roles — which carry `DISCOVER_APP_FUNCTIONS`, and therefore access to the
AppFunctions registry — require their holder not to request it and to reach the network only via a
separate open-source component. That is the entire reason `org.crdroid.intelligence.models` is a
second APK. Adding `INTERNET` to the broker silently forfeits the registry.

**Thermal state has to be pushed, not polled.** `isolated_compute_app` cannot find
`power_service`; it is not on the `isolated_compute_allowed_service` list. `ResourceGovernor`
observes thermal state in the broker and pushes it through `updateProcessingState`.

**No platform types below Layer 2 or above Layer 4.** `android.app.ondeviceintelligence` appears
in the broker and in `OpenIntelligenceService`, and nowhere else. That constraint is what makes
the Android 16 backport a module swap rather than a rewrite.

## Building

Everything except the engine adapter builds in-tree. The adapter is built out of tree with Bazel;
see `native/litertlm/README.md`. Without it the build still works: `EngineHolder` falls back to a
model-free engine and every feature reports honestly as unavailable.

## Wiring it into a product

In `vendor/lineage`:

```make
PRODUCT_PACKAGES += OpenAICore OpenAICoreModels
```

and in the framework-res overlay:

```xml
<string name="config_defaultOnDeviceIntelligenceService" translatable="false">org.crdroid.intelligence/org.crdroid.intelligence.broker.IntelligenceBrokerService</string>
<string name="config_defaultOnDeviceSandboxedInferenceService" translatable="false">org.crdroid.intelligence/org.crdroid.intelligence.inference.InferenceService</string>
```

Naming the package in `config_defaultOnDeviceSandboxedInferenceService` also gets it onto
`MemoryLimiter`'s exempt list and into `PccSandboxManagerInternal`'s allowed set, both of which
read that same config string.

No aconfig or `RELEASE_*` flag changes are required: `enable_on_device_intelligence` is already
`ENABLED` in the `cp2a` release config crDroid builds, and `RELEASE_ONDEVICE_INTELLIGENCE_MODULE`
defaults to false, which is the platform (non-mainline) variant this targets.
