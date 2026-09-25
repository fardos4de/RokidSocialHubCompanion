# Architecture Decisions

## ADR-001 — Phone is the online gateway

The glasses do not maintain their own WhatsApp/Telegram internet sessions. Online actions are delegated to the nearby phone. This reduces glasses battery use and keeps authentication on Android.

## ADR-002 — Bluetooth/local transport is the default

Small messages, commands, synchronization state and delivery receipts should use a low-overhead local transport. Wi-Fi/P2P is not part of normal operation and is reserved for a later milestone only if large media makes it necessary.

## ADR-003 — No unofficial WhatsApp linked-device implementation

WhatsApp v0.x integration stays within Android-supported notification/reply capabilities. This avoids embedding an unofficial WhatsApp Web/device protocol into Social Hub.

## ADR-004 — Telegram will use TDLib

The future full Telegram module belongs on the phone and will use Telegram's official client library rather than reimplementing MTProto on the glasses.

## ADR-005 — AIUI is the top layer, not the account layer

AIUI will call a narrow Social Hub action surface. It will not hold WhatsApp/Telegram credentials or implement their protocols directly.
