# Rokid Social Hub

Bluetooth-first social messaging hub for Rokid Glass3.

## Current builds

- **App 1 — Android phone companion `v0.2.0`**
- **App 2 — Rokid Glass3 client `v0.1.0`**
- **App 3 — AIUI agent bridge** — planned after App 2 is validated on hardware

Both App 1 v0.2 and App 2 v0.1 compile successfully in GitHub Actions against Rokid SDK `2.2.0-E`.

## Validated phone foundation

Hardware testing of App 1 v0.1 confirmed:

- WhatsApp notifications are captured and cached correctly.
- Telegram notifications are captured and cached correctly.
- Android notification `RemoteInput` replies send real WhatsApp/Telegram messages successfully.

## App 1 v0.2 — phone companion

Adds Rokid's official Phone SDK and a local classic-Bluetooth transport to the validated notification/reply foundation.

- Scans for Glass3 over classic Bluetooth.
- Uses client ID `RokidSocialHubGlass`, matching the glasses client.
- Synchronizes the 5 most recent cached chats.
- Returns up to the latest 20 cached messages when the glasses open a chat.
- Accepts `reply_text` commands from the glasses and routes them through the already validated Android notification reply mechanism.
- Queues phone-side replies while internet is unavailable.
- Pushes updated chat summaries to the glasses when a supported notification arrives.
- Disables Rokid cloud/network services in this transport layer; no Rokid AK/SK is required for this local Bluetooth milestone.

## App 2 v0.1 — Glass3 client

The `glassapp` module is a native Glass3 Android application using `com.rokid.security:glass3.open.sdk:2.2.0-E`.

- Classic Bluetooth only for normal synchronization; no Wi-Fi/P2P is used by Social Hub chat transport.
- Persistent local cache so synchronized chats/messages remain readable when the phone is absent.
- Displays the 5 synchronized recent conversations.
- Requests and displays up to 20 messages when a chat is opened.
- Local glasses outbox queues replies created while the phone is disconnected and retries them after Bluetooth returns.
- First milestone uses three quick replies to validate the full round trip before microphone/audio complexity is added.

### Glass controls

- **LEFT / RIGHT** — select chats/replies; inside a chat, scroll messages.
- **ENTER / click** — open chat or confirm reply.
- **BACK** — return to the previous screen.

## First App 2 hardware test

1. Install **phone companion v0.2.0**. Because GitHub debug signing keys can differ between CI builds, uninstall v0.1 first if Android refuses the update. If you uninstall, re-grant Notification Access afterward.
2. On the phone, grant Nearby Devices/Bluetooth permission and keep WhatsApp/Telegram notifications enabled.
3. Install **Glass3 client v0.1.0** on the glasses with ADB, for example:
   `adb -s <GLASS_SERIAL> install -r RokidSocialHubGlass-v0.1.0-debug.apk`
4. Open **Social Hub** on the glasses.
5. Open the phone companion and tap **Allow Bluetooth / connect Rokid Glass3**.
6. Expected phone status: `Glass3 connected`.
7. Expected glasses status: `PHONE ✓` and the cached recent-chat list appears.
8. Open one conversation on the glasses. The phone should return up to 20 cached messages.
9. Press ENTER in the chat, choose a quick reply, and send it. The real message should be sent through WhatsApp/Telegram on the phone.
10. For offline testing, disconnect/disable the phone link, select a reply on the glasses, then reconnect. The glasses-side outbox should resend the command when Bluetooth returns.

## Current service scope

This project does **not** emulate an unofficial WhatsApp linked device, bypass WhatsApp encryption, or scrape WhatsApp's private database. WhatsApp history remains limited to data Android exposes through notifications. Telegram TDLib is a later enhancement for richer Telegram history/media support.

## Protocol

Phone↔glasses packets use protocol v2. See `docs/PROTOCOL.md`.

## Next milestones after the App 2 hardware test

1. Voice-note recording on Glass3 and Bluetooth file transfer to the phone.
2. Voice-note playback on Glass3.
3. Incoming-message notification UX on the glasses.
4. Telegram TDLib integration for richer Telegram behavior.
5. AIUI agent bridge for commands such as “read my new messages,” “reply to Ahmed,” and “send a voice note.”

## Builds

`.github/workflows/android-debug.yml` builds and uploads two artifacts on each push to `main`:

- `RokidSocialHubCompanion-debug`
- `RokidSocialHubGlass-debug`
