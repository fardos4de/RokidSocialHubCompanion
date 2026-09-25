package com.rokidsocialhub.companion;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;

import java.util.concurrent.ConcurrentHashMap;

public final class NotificationReplyRegistry {
    public static final class ReplyTarget {
        public final PendingIntent pendingIntent;
        public final RemoteInput[] remoteInputs;

        ReplyTarget(PendingIntent pendingIntent, RemoteInput[] remoteInputs) {
            this.pendingIntent = pendingIntent;
            this.remoteInputs = remoteInputs;
        }
    }

    private static final ConcurrentHashMap<String, ReplyTarget> TARGETS = new ConcurrentHashMap<>();
    private NotificationReplyRegistry() {}

    public static void inspect(String notificationKey, Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) return;
        for (Notification.Action action : actions) {
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs != null && inputs.length > 0 && action.actionIntent != null) {
                TARGETS.put(notificationKey, new ReplyTarget(action.actionIntent, inputs));
                return;
            }
        }
    }

    public static ReplyTarget get(String notificationKey) {
        if (notificationKey == null) return null;
        return TARGETS.get(notificationKey);
    }

    public static void remove(String notificationKey) {
        if (notificationKey != null) TARGETS.remove(notificationKey);
    }

    public static boolean has(String notificationKey) {
        return notificationKey != null && TARGETS.containsKey(notificationKey);
    }
}
