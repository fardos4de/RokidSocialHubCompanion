# Rokid Social Hub Companion

Android phone-side companion for a planned Rokid social-messaging hub.

**Current release:** `0.1.0` (App 1, foundation build)

## What v0.1 does

- Captures notification-visible messages from WhatsApp, WhatsApp Business, and Telegram after the user explicitly grants Android Notification Access.
- Keeps a bounded local cache and exposes the 5 most recent conversations / 20 most recent messages in the test UI.
- Detects Android notification `RemoteInput` reply actions and can reply through the same supported notification action.
- Queues text replies when the phone has no validated internet connection and retries when connectivity returns.
- Shows Bluetooth availability and paired-device state; no proprietary Rokid SDK is required in this app.
- Defines protocol-v1 JSON payloads that App 2 (the glasses client) can consume later.
- Stores data only on-device in an app-private SQLite database.

## Important scope limits in v0.1

This release intentionally does **not** pretend to be a full WhatsApp client. It does not bypass WhatsApp encryption, scrape WhatsApp's private database, or emulate an unofficial linked device. WhatsApp history is limited to content Android exposes through notifications.

Telegram TDLib login/history, voice-note transfer, real glasses Bluetooth transport, and AIUI commands are later milestones. The architecture keeps those pieces separate so they can be added without replacing the phone-side cache/outbox.

## Install / run locally

1. Open the project in a current Android Studio release.
2. Let Android Studio install Android SDK 35 if necessary.
3. Build and install the `debug` variant on the Android phone.
4. Launch **Rokid Social Hub**.
5. Tap **Grant notification access** and enable the app.
6. Tap **Allow Nearby devices / Bluetooth**.
7. Receive a WhatsApp or Telegram notification, then return to Social Hub. The conversation should appear under **Recent conversations**.
8. Tap the conversation to inspect the cached messages. If the current notification exposes a reply action, **Reply** can send through that action.

## Build with GitHub Actions

The repository includes `.github/workflows/android-debug.yml`. Every push to `main` or manual workflow run builds a debug APK and uploads it as the `RokidSocialHubCompanion-debug` artifact.

## Project order

1. **App 1 — Android companion** (this repository)
2. **App 2 — Rokid glasses Social Hub UI / Bluetooth client**
3. **App 3 — AIUI agent bridge** for natural-language actions such as reading messages, replying, playing voice notes and recording/sending voice notes

See `docs/ROADMAP.md` for the milestone plan and `docs/PROTOCOL.md` for the phone↔glasses contract.
