# Roadmap

## Milestone 1 — Phone companion foundation — v0.1

Status: implemented in source.

- Notification access onboarding
- WhatsApp / Telegram notification ingestion
- Local SQLite cache
- 5-recent-conversation view
- 20-message inspection view
- Android RemoteInput text reply
- Offline text outbox
- Connectivity-aware retry
- Bluetooth permission / paired-device diagnostics
- Versioned phone↔glasses JSON protocol skeleton

## Milestone 2 — Companion hardening — v0.2

- Improve duplicate-message detection for grouped notifications
- Track read/dismiss state
- Add explicit outbox screen with retry/cancel controls
- Add encrypted-at-rest option for cached message body data
- Add structured service tests
- Add exportable diagnostic log without message bodies

## Milestone 3 — Telegram client module — v0.3

- Integrate official TDLib
- Telegram login / session storage on phone
- True recent-chat and message-history sync
- Send text through TDLib
- Download / play voice notes
- Upload voice notes
- Offline TDLib outbox reconciliation

## Milestone 4 — Phone↔glasses transport — v0.4

- Replace the transport stub with the transport supported by the Rokid glasses runtime
- Bluetooth-first small-message synchronization
- Chunked binary transfer for compressed voice notes
- Automatic reconnect and capability negotiation
- No Wi-Fi requirement for normal messaging
- Optional P2P escalation only for genuinely large media if needed

## Milestone 5 — App 2: Rokid glasses Social Hub

- Recent 5 chats
- Last 20 synchronized messages
- Notification overlay
- Swipe / tap navigation
- Local offline cache
- Voice-note recorder and player
- Two-stage queue: glasses waiting for phone → phone waiting for internet

## Milestone 6 — App 3: AIUI integration

Expose constrained Social Hub actions to the Rokid AI layer:

- `get_recent_chats`
- `get_messages`
- `get_unread_messages`
- `send_text`
- `start_voice_recording`
- `stop_voice_recording`
- `send_voice`
- `play_voice`
- `mark_read`
- `dismiss_notification`
- `get_connection_status`

Sending actions will use a confirmation policy to avoid accidental external messages.
