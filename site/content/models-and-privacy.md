# Models and privacy

Everything runs on the device. Audio, transcripts, summaries, and categories are processed locally. The only network traffic is the download of model files, which are fetched from pinned URLs.

## Model roles

Each tier uses up to three models during normal setup. Speaker-recognition models are a separate optional Android download.

| Role | Used for |
|------|----------|
| `LIVE_VOICE` | Live transcription while recording (16 kHz path). |
| `VOICE` | Batch transcription of the finished recording. |
| `LLM` | Summaries and categories. Runs on the GPU on the MID and LATEST tiers when the GPU acceleration setting is on (default), and falls back to the CPU if the GPU fails. |

## Speaker recognition models

Speaker recognition is Android-only and is off until the user downloads the models and explicitly agrees to the consent dialog. These files are not downloaded during normal setup. The download is offered only on devices with at least 5 GiB RAM; devices below that threshold do not run the analysis because low-end performance has not been validated.

| Model | Artifact | Size | License |
|-------|----------|-----:|---------|
| Pyannote segmentation 3.0 int8 | [Pinned ONNX file](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx) | 1,540,506 bytes | MIT |
| 3D-Speaker ERes2Net-Base | [sherpa-onnx release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx) | 39,593,761 bytes | Apache-2.0, according to the model card |

The combined download is 41,134,267 bytes. Each file is pinned by URL, expected size, and SHA-256; it is verified before use. Downloads wait for an unmetered connection unless the user explicitly allows mobile data for that download. Analysis stays on-device.

## Tiers

The tier comes from the RAM the operating system reports (`ModelTierSelector` in `data/service/model/ModelCatalog.kt`).

| Tier | RAM | Live and voice speech models | LLM |
|------|-----|------------------------------|-----|
| LEGACY | under 5 GiB | Whisper tiny (one download serves both roles) | Qwen3 0.6B |
| MID | 5 to under 10 GiB | Whisper tiny (live), Whisper Base (voice) | Gemma 4 E2B |
| LATEST | 10 GiB or more | Whisper tiny (live), Whisper Large v3 (voice) | Gemma 4 E4B |

Android reports RAM through `AndroidModelDevice`. iOS reports the physical memory through `IosModelDevice`, which reads `NSProcessInfo.physicalMemory`.

## Download integrity

Each file is pinned by:

- the URL, which includes a revision;
- the expected size in bytes;
- the SHA-256 of the file.

`ModelFileStore` compares the SHA-256 of each file on disk with the pinned value. Downloads wait for an unmetered connection, or for the user's consent to use mobile data for that run (`ModelNetworkPolicy`).

## Storage and backup

- Models are stored in the app's `models` directory (`ModelCatalog.MODEL_DIRECTORY_NAME`).
- On iOS, that directory is excluded from iCloud backup, because the files can be downloaded again (`excludeModelsFromBackup` in `iosMain/.../MainViewController.kt`).
- Memories and recordings are stored under the app's recordings root. Recording paths in the database are relative to it.

## Microphone

The app asks for microphone permission before recording. If the permission is denied, it explains why and offers to open system settings.
