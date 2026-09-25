# Phone ↔ Glasses Protocol v1

The application logic is intentionally independent from the physical transport. App 2 can carry the same packets over Rokid's supported phone/glasses channel, classic Bluetooth, BLE, or another documented local channel.

## Design rules

- Bluetooth/local transport first.
- No internet connection is required on the glasses.
- The phone is the authoritative online gateway and master message database.
- Glasses keep only a small synchronized cache.
- Every command gets a local command ID so future delivery receipts can be correlated.
- Text packets are UTF-8 JSON.
- Binary audio will be transferred separately in chunks; JSON only contains metadata.

## Example: hello

```json
{"v":1,"type":"hello","online":true,"queued":0}
```

## Example: conversation list

```json
{
  "v": 1,
  "type": "conversations",
  "items": [
    {
      "source": "Telegram",
      "id": "telegram:12345",
      "title": "Ahmed",
      "preview": "See you later",
      "timestamp": 1790340000000
    }
  ]
}
```

## Planned glasses → phone commands

```json
{"v":1,"type":"get_messages","commandId":"c1","conversationId":"telegram:12345","limit":20}
```

```json
{"v":1,"type":"send_text","commandId":"c2","conversationId":"telegram:12345","text":"I will be there at 8:30"}
```

```json
{"v":1,"type":"queue_voice","commandId":"c3","conversationId":"telegram:12345","audioId":"a9","codec":"opus","bytes":98211}
```

## Delivery state model

`LOCAL_ON_GLASSES → WAITING_FOR_PHONE → ON_PHONE → WAITING_FOR_INTERNET → SENDING → SENT`

Errors remain retryable unless the target conversation/action is no longer valid.
