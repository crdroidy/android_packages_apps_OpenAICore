# AICore interoperability notes

What the proprietary `com.google.android.aicore` binder surface looks like, so a compatible
service can be written. This is interface information only — no Google code is reproduced here,
and the APK it was read from is not redistributed with this repo.

## Why this exists

Google's first-party AI features (Magic Cue, Pixel Screenshots, Pixel Weather summaries) do not
use the AOSP on-device-intelligence seam. Verified on a Pixel 10 Pro running crDroid 17.0:

```
$ adb shell dumpsys on_device_intelligence
  Configurations:
    OnDeviceIntelligenceService:
    OnDeviceSandboxedInferenceService:
```

Both empty, with AICore installed and running. So those apps talk to AICore's private AIDL, and
nothing OpenAICore does on the platform seam will reach them. Serving them means implementing
AICore's own interface — which is what these notes are for.

Two useful consequences of the same finding: the AOSP seam is entirely unclaimed, so OpenAICore
drops in with nothing to displace; and the two stacks cannot collide, because they never touch the
same binder.

## Provenance

Read from the Play-delivered beta build:

```
com.google.android.aicore
versionCode  501315
versionName  0.thirdpartyexperimental.ffdf_aicore_20260807.00_RC02.964658782
minSdk 34  targetSdk 35
```

Not the `AICorePrebuilt-aicore_20260302.01_RC00` in the vendor tree — that one is a stub
(`versionName` literally begins `0.stub.`) containing no inference services at all. The real
implementation only exists on a device that has taken the Play update.

Recovered with `tools/dexprobe.py`, which reads DEX structures directly. Class and method names are
R8-obfuscated, but binder interface descriptors survive because `asInterface` compares them at
runtime, and transaction codes survive because they are the wire protocol.

## Shape

The bind target is `com.google.android.apps.aicore.service.AiCoreService`, guarded by
`com.google.android.apps.aicore.service.BIND_SERVICE` — declared with no `protectionLevel`, so it
defaults to `normal` and any caller can hold it.

`IAICoreService` is the root: 41 transactions, almost all of the form
`getSomeService(Feature) -> ISomeService`. Each feature service is then small and follows one
template. Two observed instances:

```
ISummarizationService  (8 methods)     ILLMService  (8 methods)
  txn 3  (Request, cb) -> handle         txn 6  (Request, cb) -> handle
  txn 4  () -> int                       txn 4  () -> int
  txn 5  (cb) -> handle                  txn 5  (cb) -> handle
  + streaming and result-with-info variants
```

Transaction codes and prototypes are read from the binary and are reliable. The *names* are not
recovered — R8 renamed them — so treat any mapping to `runInference` or `prepareInferenceEngine` as
inference from shape until confirmed. One name is known independently, from an ASI log string:
`prepareInferenceEngine` exists on the Astrea `IGenAiInferenceService`.

The regularity is the important part: every feature service is the same small template, so a shim
implements it once and instantiates per feature rather than writing 80 interfaces.

## What is still unknown

The parcelable layouts. `Feature`, the per-service request types, and the cancellation handle are
all obfuscated classes whose field order has to be recovered before a shim can marshal correctly.
That is field-level work best done with a full decompiler against the same APK build.

## Interface inventory

```
IAICoreService IAiCoreServiceProvider IAiCoreServiceProviderCallback 
IAstroboyResultCallback IAstroboyService IBaymaxResultCallback IBaymaxService 
IBenderResultCallback IBenderService IBishopResultCallback IBishopService 
ICancellationCallback ICortanaResultCallback ICortanaService ICortanaStateCallback 
ICortanaStreamingCallback IDownloadListener IDownloadListener2 IEmbeddingCachingCallback 
IFeatureMetadataCallback IHintCallback IImageDescriptionResultCallback 
IImageDescriptionResultWithInfoCallback IImageDescriptionService 
IImageDescriptionStreamingCallback IImageEmbeddingCallback IInferenceStateCallback 
IInfoExtractionResultCallback IInfoExtractionResultWithInfoCallback IInfoExtractionService 
IInfoExtractionStreamingCallback IIntentQueryGenerationResponseCallback 
IIntentQueryGenerationService ILLMResultCallback ILLMResultWithInfoCallback ILLMService 
ILLMStreamingCallback IMagicRewriteResultCallback IMagicRewriteResultWithInfoCallback 
IMagicRewriteService IMagicRewriteStreamingCallback IMockService IOcrResultCallback 
IOcrService IOptimusResultCallback IOptimusService IPrepareInferenceEngineCallback 
IProofreadingResultCallback IProofreadingService IQuestionToAnswerResultCallback 
IQuestionToAnswerResultWithInfoCallback IQuestionToAnswerService 
IQuestionToAnswerStreamingCallback IRosieRobotResultCallback 
IRosieRobotResultWithInfoCallback IRosieRobotService IRosieRobotStreamingCallback 
ISmartReplyResultCallback ISmartReplyResultWithInfoCallback ISmartReplyService 
ISonnyResultCallback ISonnyService ISuggestedTextResultCallback 
ISuggestedTextResultWithInfoCallback ISuggestedTextService ISummarizationResultCallback 
ISummarizationResultWithInfoCallback ISummarizationService ISummarizationStreamingCallback 
ITarsResultCallback ITarsService ITaskStateChangeCallback ITextClassificationResultCallback 
ITextClassificationService ITextEmbeddingMetadataCallback ITextEmbeddingResultCallback 
ITextEmbeddingService ITextToImageResultCallback ITextToImageService ITokenizationCallback 
IWalleResultCallback IWalleResultWithInfoCallback IWalleService 
```

The named services map cleanly onto features OpenAICore already implements:

| AICore interface | OpenAICore feature |
|---|---|
| `ISummarizationService` | `SUMMARIZE` |
| `IProofreadingService` | `PROOFREAD` |
| `IMagicRewriteService` | `REWRITE` |
| `IImageDescriptionService` | `DESCRIBE_IMAGE` |
| `ILLMService` | `PROMPT` |
| `ITextEmbeddingService` | platform embedding API |

The robot codenames — Astroboy, Baymax, Bender, Bishop, Cortana, Optimus, Rosie, Sonny, Tars,
Walle — appear to be internal model wrappers rather than product features, and are not worth
implementing.

## Where a shim belongs

Not in this repo's core. A separate `OpenAICoreCompat` package that implements Google's AIDL and
translates onto `IntelligenceClient` keeps the reverse-engineered surface away from the
Apache-2.0 core, lets OpenAICore stay useful on de-Googled builds regardless, and lets the shim
rot against Play updates without taking anything else down.

Two gates to expect beyond the AIDL itself:

- **Phenotype flags.** ASI gates its AI features on server-delivered flags
  (`Autofill__show_aicore_candidate_icon`, `Memory__enable_aicore_contextual_suggestions_api`).
  A working backend behind a disabled flag does nothing. Probe this before doing the AIDL work —
  it is the cheaper question and it can invalidate the expensive one.
- **Output contracts.** Making the call succeed is not the same as returning what the caller
  parses. That is per-feature work and it is where the effort actually lives.

Signature checking is not expected to be a gate: crDroid already carries the microG-style
`FAKE_PACKAGE_SIGNATURE` path in `ComputerEngine`.
