# Models and privacy

Everything runs on the device. Audio, transcripts, summaries, and categories are processed locally. The only network traffic is the download of model files, which are fetched from pinned URLs.

## Model roles

Each tier uses up to three models.

| Role | Used for |
|------|----------|
| `LIVE_VOICE` | Live transcription while recording (16 kHz path). |
| `VOICE` | Batch transcription of the finished recording. |
| `LLM` | Summaries and categories. |

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
