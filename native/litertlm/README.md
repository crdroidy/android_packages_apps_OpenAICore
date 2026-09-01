# LiteRT-LM engine adapter

This directory holds the adapter that implements `native/include/openaicore_engine.h` on top of
LiteRT-LM. **It is not built by Soong**, and that is not an oversight.

LiteRT-LM builds with Bazel 7.6.1. Bazel does not integrate with the AOSP build, and getting
LiteRT-LM's dependency graph — XNNPACK, ML Drift, the LiteRT runtime — to resolve inside a Soong
build is a project of its own with no upstream support. The adapter is therefore built
out-of-tree and imported as a prebuilt `.so`.

## Why an adapter rather than binding LiteRT-LM directly from JNI

Three reasons, in order of how much they cost to get wrong:

1. **The engine has to be swappable.** Pinning the JNI layer to LiteRT-LM's own headers means a
   second engine, or an upstream API change, reaches into the service code. The ABI in
   `openaicore_engine.h` is ours; nothing above it knows what is underneath.
2. **The Android 16 backport.** The service code must not acquire a dependency that only resolves
   on one branch.
3. **Graceful absence.** A build without the prebuilt still boots and reports the feature
   unavailable, because the JNI layer dlopens the adapter rather than linking it.

## Building

```
git clone https://github.com/google-ai-edge/LiteRT-LM
cd LiteRT-LM
git checkout <pinned tag>          # pin an exact tag; do not track main

# LiteRT-LM v0.16 and later publish a versioned C API with prebuilt binaries for Android.
# Prefer that over the Kotlin/JNI AAR: it is a stable surface and it lets us own cancellation
# and backend selection rather than inheriting whatever the AAR decided.
bazel build -c opt \
    --config=android_arm64 \
    //runtime/engine:litert_lm_c_api_shared
```

Then build this adapter against the resulting headers and library, and copy the output to:

```
prebuilts/litertlm/arm64/libopenaicore_litertlm.so
```

Finally set the Soong config variable so the prebuilt is picked up:

```
$(call soong_config_set,crdroid,openaicore_litertlm_prebuilt,true)
```

## Backend ladder

The adapter must honour `OpenAiCoreBackend` exactly and report what it actually got through
`OpenAiCoreEngineActiveBackend`. Silently falling back is worse than failing: the probe caches a
verdict per device, and a silent fallback caches the wrong one.

The ladder is **NPU → OpenCL → GLES → CPU (XNNPACK)**. There is no Vulkan rung: LiteRT's GPU
engine is ML Drift, which targets OpenCL first and falls back to OpenGL ES for coverage, and has
no Vulkan compute path.

## The ANGLE-CL landmine

Where the vendor ships ANGLE's CL-on-Vulkan translator as `libOpenCL.so` — the direction both
Pixel and Samsung have moved — ML Drift's generated kernels are rejected by Clspv over an implicit
`__global` → `__constant` address-space conversion, and **engine construction fails outright
rather than degrading** (LiteRT-LM issue #2114). `NativeProbe.isAngleOpenCl()` detects this before
the probe ever constructs an engine. The adapter should also fail fast rather than retry if it
sees the same rejection at kernel compile time.

## Memory

`OpenAiCoreEngineConfig.model_fd` is a read-only descriptor. **mmap it.** Roughly 1.1 GB of the
E2B footprint is embedding parameters that are only ever read; reading the model into anonymous
memory instead turns a ~0.8 GB resident set into something that gets the process killed.

The adapter must also tolerate being killed mid-generation. The sandbox is deliberately more
killable than system_server, and a caller seeing `CANCELLED` is a far better outcome than a
caller seeing its own process die.
