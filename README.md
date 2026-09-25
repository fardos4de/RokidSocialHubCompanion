# Rokid Social Hub Companion

Android phone-side companion for the Rokid Social Hub project.

**Current release:** `0.2.0` (App 1 + official Rokid Bluetooth transport)

## Validated v0.1 foundation

The first device test confirmed all three phone-side fundamentals on the target Android phone:

- WhatsApp notifications are captured and cached correctly.
- Telegram notifications are captured and cached correctly.
- Android notification `RemoteInput` replies successfully send real messages.

## What v0.2 adds

- Official Rokid Phone SDK `2.2.0-E` from Rokid's public Maven repository.
- Classic-Bluetooth Glass3 discovery and connection; no Wi-Fi/P2P is required for normal Social Hub synchronization.
- Protocol v2 synchronization of the 5 most recent conversations.
- On-demand synchronization of up to 20 cached messages for an opened conversation.
- Glass-to-phone `reply_text` commands routed through the already validated WhatsApp/Telegram notification reply path.
- Phone-side reply results and updated conversation/message synchronization.
- Automatic push of the recent-conversation list when a new supported notification arrives.
- All Rokid cloud/network services are disabled in the SDK transport layer; empty AK/SK values are used because this milestone only needs the local Bluetooth channel.

## Current WhatsApp/Telegram scope

This application does **not** emulate an unofficial WhatsApp linked device, bypass WhatsApp encryption, or scrape WhatsApp's private database. WhatsApp history remains limited to information Android exposes to the notification listener. Telegram TDLib will be a later enhancement for full Telegram account history/media behavior.

## Install / test phone v0.2

1. Install the current debug APK over v0.1.
2. Keep Notification Access enabled.
3. Grant both Nearby Devices / Bluetooth permissions when requested.
4. Tap **Allow Bluetooth / connect Rokid Glass3**.
5. The app scans for a non-LE device whose name contains `Glass3` and connects using Rokid's official phone SDK.
6. Once App 2 is installed on the glasses, the phone will send the current 5-chat snapshot automatically.

## Build with GitHub Actions

The repository includes `.github/workflows/android-debug.yml`. A push to `main` builds a debug APK and uploads it as an artifact.

## Project order

1. **App 1 — Android companion** — notification/reply foundation validated; official Rokid Bluetooth transport now being integrated.
2. **App 2 — Rokid glasses Social Hub** — next: recent chats, 20-message view, Bluetooth reply round-trip and offline cache.
3. **App 3 — AIUI agent bridge** — natural-language read/reply/play/record/send actions after App 2 transport is proven.

See `docs/ROADMAP.md` and `docs/PROTOCOL.md` for design details.
