# MMS Test — Reference Video-MMS Sender

A **minimal, single-Activity Android app** whose only job is to send a real
carrier video MMS and log every stage of the pipeline. It is a throwaway
reference implementation: this folder **is** a complete Android Studio project,
so open it directly, sync, build, install it on the phone, and press **Send**.

> Base44 cannot run native Android code. This page mirrors a real Gradle project
> that lives on disk under `src/data/kit/`. Clone the repo and open that folder
> directly in Android Studio.

## What it does

1. You type a recipient phone number (and optional text).
2. You pick a short video.
3. If the video is larger than the configurable ceiling (default 1000 KB),
   it is transcoded with **Media3 Transformer**, halving resolution until it
   fits under the ceiling.
4. An MMS `M-Send.req` PDU is composed with the AOSP-proven
   `PduComposer` (parts: optional text + H.264 video), written to a cache file,
   exposed through a `FileProvider`, and dispatched with
   `SmsManager.sendMultimediaMessage(...)`.
5. Every stage is written to the on-screen diagnostic log (and can be copied
   to clipboard): picked file path/size/MIME, transcode passes & sizes, PDU
   composition, FileProvider URI, SmsManager dispatch, and the send-result
   broadcast (success or one of `MMS_ERROR_*`).

It is **not** an SMS client. No conversations, contacts, history, themes, RCS,
notifications, or share-intents. It only sends one MMS.

## Why this design (the researched, current mechanism)

- **`SmsManager.sendMultimediaMessage`** (API 21+) is the supported, current
  Android MMS send API. It reads a raw MMS PDU from the given content URI and
  hands it to the platform MMS service, which deals with the carrier MMSC.
- The PDU is composed with the Apache-2.0 AOSP `com.google.android.mms.pdu_alt`
  helper classes (`SendReq`, `PduBody`, `PduPart`, `PduComposer`) pulled in via
  the `com.klinkerapps:android-smsmms` artifact. These classes encode the
  WAP/MMS binary correctly — the part that is most error-prone to hand-roll and
  most likely to silently break.
- `SendReq`'s default constructor already sets the required headers:
  `X-Mms-Message-Type = M-Send.req`, `X-Mms-MMS-Version`, `From = insert-address-token`,
  `X-Mms-Transaction-Id`, and `Content-Type`. We only add `To`, the parts, and
  switch the content type to `application/vnd.wap.multipart.mixed` for the
  broadest carrier acceptance.
- The PDU file is **not** stored in the MMS content provider; it is served from
  a `FileProvider` URI, which `sendMultimediaMessage` reads directly. (Some OEM
  MMS providers reject `openInputStream` on `content://mms/<id>`; a FileProvider
  URI sidesteps that.)

## Setup (Android Studio)

The kit is a ready-to-sync Gradle project. No files to move and no "New Project"
wizard needed.

1. **Open** this folder (`src/data/kit/`) directly in Android Studio:
   `File → Open → select the folder`. It contains `settings.gradle.kts` at the
   root and an `app/` module, so Studio recognises it as a project.
2. **Sync Gradle** (Studio prompts automatically; otherwise the elephant icon).
   The included Gradle wrapper pins Gradle 8.7 to match AGP 8.5.2.
3. **Run** on the test phone (the one with the SIM and the carrier you want to
   validate). Grant the `SEND_SMS` runtime permission when prompted.
4. From another phone, send **a short video you want to test** to *yourself*
   (or pick a tiny clip ≤ a few seconds). Enter the recipient's number, press
   **Send MMS**. Watch the log.
5. Ask the recipient whether the video arrived. **Copy Log** to keep the full
   evidence trail.

## Reading the result

- `MMS >>> SUCCESS` + the recipient got the video → the pipeline works on this
  phone/carrier. Hand the whole project to Rocket and say: *"this exact app
  sends video MMS on this phone/SIM/carrier; compare CyberSMS's video pipeline
  against it."*
- Any `MMS_ERROR_*` (or an exception) → the log shows exactly which stage
  failed. If the failure is also reproducible here, the problem is below
  CyberSMS's architecture (carrier MMSC, APN, size, codec) — not in CyberSMS's
  giant codebase.

## Troubleshooting / known-fiddly bits

- `MMS_ERROR_IO_ERROR` almost always means the final attachment still exceeded
  the carrier's real limit (which can be below `MMS_CONFIG_MAX_MESSAGE_SIZE`).
  Lower the **Max attachment** slider and resend; also confirm the transcode
  actually produced a smaller file (see `[TRANSCODE]` lines).
- `MMS_ERROR_NO_APN` / `MMS_ERROR_UNABLE_TO_CONNECT_MMS` → the carrier/MVNO
  APN has no MMSC. This is a device provisioning issue, not app code.
- `MMS_ERROR_RETRY` → transient carrier issue; resend after a moment.
- Some carriers reject `video/mp4` and only accept `video/3gpp`. If you see a
  send succeed but the recipient gets nothing or a "cannot play" stub, switch
  the transcode output container to `.3gp` (change the Media3 output filename
  extension and set the muxer accordingly) and `videoMime` to `video/3gpp`.
- On Android 14 (targetSdk 34), the result `BroadcastReceiver` must be
  registered with `RECEIVER_NOT_EXPORTED` (already done here).

## Files at a glance

| File | Role |
|------|------|
| `MainActivity.kt` | UI, file picking, orchestration, permission |
| `mms/MmsSender.kt` | PDU composition + `sendMultimediaMessage` dispatch + result |
| `mms/VideoTranscoder.kt` | Media3 transcode, resolution-halve to hit ceiling |
| `mms/DiagnosticLogger.kt` | Shared thread-safe log bus |
| `AndroidManifest.xml` | SEND_SMS, FileProvider for the PDU |
| `file_paths.xml` | FileProvider cache-path for the PDU file |
| `activity_main.xml` | The 4-control UI + scrollable log |