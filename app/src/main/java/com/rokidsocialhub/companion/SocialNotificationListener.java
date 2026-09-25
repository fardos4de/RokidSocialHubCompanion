package com.rokidsocialhub.companion;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

public final class SocialNotificationListener extends NotificationListenerService {
    private static final String WHATSAPP = "com.whatsapp";
    private static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    private static final String TELEGRAM = "org.telegram.messenger";
    private static final String TELEGRAM_WEB = "org.telegram.messenger.web";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        AppLog.d("Notification listener connected");
        OutboxProcessor.flush(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isSupported(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        NotificationReplyRegistry.inspect(sbn.getKey(), notification);
        ingest(sbn);
        OutboxProcessor.flush(this);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NotificationReplyRegistry.remove(sbn.getKey());
    }

    private void ingest(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return;

        String source = sourceName(sbn.getPackageName());
        String title = asString(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (isBlank(title)) title = asString(extras.getCharSequence(Notification.EXTRA_TITLE));
        if (isBlank(title)) title = source;

        String conversationId = source.toLowerCase() + ":" + title.trim();
        boolean insertedStructured = false;

        android.os.Parcelable[] rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (rawMessages != null) {
            try {
                List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(rawMessages);
                for (Notification.MessagingStyle.Message message : messages) {
                    if (message == null || message.getText() == null) continue;
                    String sender = message.getSenderPerson() != null
                            ? String.valueOf(message.getSenderPerson().getName())
                            : title;
                    long timestamp = message.getTimestamp() > 0 ? message.getTimestamp() : sbn.getPostTime();
                    SocialDb.get(this).insertMessage(source, conversationId, title, sender,
                            String.valueOf(message.getText()), timestamp, sbn.getKey(), false);
                    insertedStructured = true;
                }
            } catch (Exception e) {
                AppLog.e("Unable to parse MessagingStyle notification", e);
            }
        }

        if (!insertedStructured) {
            String body = asString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (isBlank(body)) body = asString(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (!isBlank(body)) {
                SocialDb.get(this).insertMessage(source, conversationId, title, title,
                        body, sbn.getPostTime(), sbn.getKey(), false);
            }
        }
    }

    private static boolean isSupported(String packageName) {
        return WHATSAPP.equals(packageName)
                || WHATSAPP_BUSINESS.equals(packageName)
                || TELEGRAM.equals(packageName)
                || TELEGRAM_WEB.equals(packageName);
    }

    private static String sourceName(String packageName) {
        if (packageName != null && packageName.startsWith("com.whatsapp")) return "WhatsApp";
        if (packageName != null && packageName.startsWith("org.telegram")) return "Telegram";
        return packageName == null ? "Unknown" : packageName;
    }

    private static String asString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
