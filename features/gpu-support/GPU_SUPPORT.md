# GPU support (design)

Status: proposal. No code changes yet.

## Goal

Run on-device inference on the GPU or NPU where it is faster, and fall back to the CPU when it is not available or not stable. Transcription and summarization are the two heavy workloads.

## Current state

| Workload | Runtime | Where | Accelerator today |
|----------|---------|-------|-------------------|
| Live transcription | sherpa-onnx 1.13.8 | `AndroidLiveTranscriber` | `provider = "cpu"` |
| Batch transcription | sherpa-onnx 1.13.8 | `AndroidBatchTranscriber` | `provider = "cpu"` |
| Summary / categories (LLM) | LiteRT-LM 0.16.1 | `LocalLlmRuntime` | `Backend.CPU()` |
| iOS | No transcriber or LLM implementation found under `iosMain` | n/a | n/a |

Every call site hard-codes the CPU, so the change is small and local on Android.

## Options per workload

### LLM (LiteRT-LM)

`EngineConfig` takes a `Backend`. Options are `CPU()`, `GPU()` and, where supported, an NPU backend. Switching is a one-line change plus fallback handling. Models must be compatible with the chosen backend, so check the pinned model files in `ModelCatalog` before enabling GPU for a tier.

### Speech (sherpa-onnx)

The provider string selects the execution provider. The prebuilt Android AAR supports `cpu` and `nnapi`. NNAPI is deprecated since Android 15 and its operator coverage is uneven, so it can be slower than the CPU or fail on some devices. Treat it as experimental. A GPU or QNN build would need a custom sherpa-onnx build and is out of scope.

### iOS

Not applicable until iOS transcription and summarization exist. When they do, prefer Core ML or Metal through the platform runtime.

## Design

1. **Setting**: add an `Acceleration` setting with values `Auto` (default), `CPU only`, and `GPU`. Store it in the local settings collection, next to `showBackgroundActivityIndicator`.
2. **Selector**: add a common `AcceleratorSelector` that returns the preferred backend per role (`LIVE_VOICE`, `VOICE`, `LLM`) from the setting, the model tier and device capabilities. Keep it a pure function so it is unit-testable in `commonTest`.
3. **Platform mapping**: in `androidMain`, map the selection to `Backend.GPU()` for the LLM. Speech stays on `provider = "cpu"` regardless of the setting, because the prebuilt sherpa-onnx AAR has no GPU provider. NNAPI is not treated as GPU. If it is ever offered, it will be a separate, explicitly experimental setting with its own CPU fallback.
4. **Fallback**: if engine creation or the first inference fails on an accelerator, retry once on the CPU for that session and remember the failure for the model and device. Do not retry the accelerator again until the app version or the setting changes.
5. **Defaults**: `Auto` uses the GPU for the LLM on the MID and LATEST tiers only. Speech stays on the CPU until benchmarks show a gain, because live transcription is latency-sensitive and small models run well on the CPU.
6. **Memory**: GPU backends use more memory and can be killed under pressure. Do not run GPU inference while recording on the LEGACY tier.

## Validation

- Benchmark each workload on CPU and on each accelerator on at least one low, one mid and one high tier device. Record real-time factor for speech and tokens per second for the LLM.
- Compare output quality. The transcript word error rate and summary quality must not regress.
- Test the fallback by forcing an accelerator failure.
- Unit tests for `AcceleratorSelector`. Instrumented test for fallback.

## Risks

- Driver bugs and crashes on some GPUs, mitigated by the fallback and the remembered failure.
- Thermal throttling and battery use on long sessions.
- Larger APK or model size if extra backend libraries are needed.
- NNAPI deprecation for speech.

## Open questions

- Which devices and models does the benchmarking session cover?
- Should `GPU` be exposed to users, or kept as an internal `Auto` decision only?
- Is an NPU path (Qualcomm or Tensor) worth the build and maintenance cost?

## Rollout

1. Add the setting and selector with CPU behavior unchanged.
2. Enable the LLM GPU path behind `Auto` on MID and LATEST.
3. Revisit speech acceleration after the benchmark results.

Each step ships as its own pull request with a changelog entry and version bump.
