package com.rokidsocialhub.companion;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class ReplyDispatcher {
    private ReplyDispatcher() {}

    public static String sendOrQueue(Context context,
                                     Models.Conversation conversation,
                                     String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return "EMPTY";

        SocialDb db = SocialDb.get(context);
        if (!NetworkState.get(context).isOnline()) {
            db.enqueueText(conversation.source, conversation.conversationId, conversation.title,
                    conversation.latestNotificationKey, clean, "QUEUED", "Waiting for internet");
            return "QUEUED_OFFLINE";
        }

        String result = sendNow(context, conversation.latestNotificationKey, clean);
        if ("SENT".equals(result)) {
            db.insertMessage(conversation.source, conversation.conversationId, conversation.title,
                    "You", clean, System.currentTimeMillis(), conversation.latestNotificationKey, true);
            return result;
        }

        db.enqueueText(conversation.source, conversation.conversationId, conversation.title,
                conversation.latestNotificationKey, clean, "RETRY", result);
        return result;
    }

    public static String sendNow(Context context, String notificationKey, String text) {
        NotificationReplyRegistry.ReplyTarget target = NotificationReplyRegistry.get(notificationKey);
        if (target == null) return "NO_ACTIVE_REPLY_ACTION";

        try {
            Intent fillInIntent = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput input : target.remoteInputs) {
                results.putCharSequence(input.getResultKey(), text);
            }
            RemoteInput.addResultsToIntent(target.remoteInputs, fillInIntent, results);
            target.pendingIntent.send(context, 0, fillInIntent);
            return "SENT";
        } catch (PendingIntent.CanceledException e) {
            AppLog.e("Reply PendingIntent was canceled", e);
            return "REPLY_ACTION_EXPIRED";
        } catch (Exception e) {
            AppLog.e("Reply failed", e);
            return "REPLY_FAILED: " + e.getClass().getSimpleName();
        }
    }
}
