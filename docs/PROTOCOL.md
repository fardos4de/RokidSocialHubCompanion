# Phone ↔ Glasses Protocol v2

Social Hub v2 uses Rokid's official classic-Bluetooth message channel as its normal transport. The phone is the online gateway and authoritative cache. The glasses remain useful without phone/internet by retaining the last synchronized data and queuing future outgoing actions.

## Design rules

- Classic Bluetooth is the default transport; Wi-Fi/P2P is not required for chat text and control messages.
- No independent internet connection is required on the glasses.
- The phone owns WhatsApp/Telegram integration, network access, and the master local cache.
- The glasses retain at most the current lightweight synchronized subset needed for offline browsing.
- Text/control packets are UTF-8 JSON.
- Future voice-note payloads will use the SDK file-transfer channel; JSON carries only metadata/status.
- Protocol packets contain `v: 2`. A receiver ignores packets newer than the protocol it supports.

## Phone → glasses

### Connection state

```json
{"v":2,"type":"hello","online":true,"queued":0}
```

### Recent conversations

```json
{
  "v":2,
  "type":"conversations",
  "items":[
    {
      "source":"WhatsApp",
      "id":"whatsapp:Ahmed",
      "title":"Ahmed",
      "preview":"See you later",
      "timestamp":1790340000000
    }
  ]
}
```

The phone currently sends the five most recent conversations.

### Messages for one chat

```json
{
  "v":2,
  "type":"messages",
  "id":"whatsapp:Ahmed",
  "source":"WhatsApp",
  "title":"Ahmed",
  "items":[
    {
      "messageId":42,
      "source":"WhatsApp",
      "sender":"Ahmed",
      "body":"See you later",
      "timestamp":1790340000000,
      "outgoing":false
    }
  ]
}
```

The phone returns at most the most recent 20 cached messages for the requested conversation, ordered oldest → newest in the packet.

### Reply result

```json
{"v":2,"type":"reply_result","id":"whatsapp:Ahmed","result":"SENT"}
```

Possible results currently include `SENT`, `QUEUED_OFFLINE`, `NO_ACTIVE_REPLY_ACTION`, `REPLY_ACTION_EXPIRED`, `REPLY_FAILED`, and `NO_CONVERSATION`.

## Glasses → phone

### Request full synchronization

```json
{"v":2,"type":"sync_request"}
```

### Request messages for a conversation

```json
{"v":2,"type":"messages_request","id":"whatsapp:Ahmed"}
```

### Send a text reply

```json
{"v":2,"type":"reply_text","id":"whatsapp:Ahmed","text":"I will be there at 8:30"}
```

If the phone lacks internet, its existing outbox queues the reply. If the phone itself is unavailable, App 2 will retain this command locally and retry after the Bluetooth link returns.

## Future voice-note state model

`LOCAL_ON_GLASSES → WAITING_FOR_PHONE → ON_PHONE → WAITING_FOR_INTERNET → SENDING → SENT`

Voice note metadata/status will use protocol JSON while the compressed audio file itself is transferred separately over the local Rokid transport.
