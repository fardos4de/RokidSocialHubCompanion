# v0.1 Phone Test Plan

## First launch

1. Install and launch the debug APK.
2. Verify Internet status is shown.
3. Grant Notification Access from the system settings page opened by the app.
4. Grant Nearby devices / Bluetooth permission on Android 12+.
5. Confirm Bluetooth status shows ON/OFF and paired-device count rather than a permission error.

## Message capture

1. Leave the phone on the home screen.
2. Have another account send a WhatsApp message.
3. Confirm Android displays the WhatsApp notification.
4. Open Social Hub.
5. Confirm the chat appears in Recent conversations.
6. Tap it and confirm the notification-visible text appears.
7. Repeat with Telegram.

## Reply

1. Keep a current WhatsApp/Telegram notification that contains a Reply action.
2. Open the cached conversation.
3. Tap Reply.
4. Send a short test message.
5. Confirm the target app sends the reply.

## Offline queue

1. Disable Wi-Fi and mobile data.
2. Open a cached conversation and compose a reply.
3. Confirm Social Hub reports that it queued the reply.
4. Restore internet.
5. Open Social Hub or receive another notification.
6. Confirm the outbox count drops if the original notification reply action is still valid.

## Expected v0.1 limitation

Android notification reply PendingIntents are ephemeral. If the source app removes/replaces a notification or Android kills the associated action, an old queued WhatsApp reply cannot be reconstructed from disk. Telegram TDLib in the later module will not have this limitation.
