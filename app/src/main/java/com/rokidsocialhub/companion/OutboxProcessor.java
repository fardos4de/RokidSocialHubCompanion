package com.rokidsocialhub.companion;

import android.content.Context;

import java.util.List;

public final class OutboxProcessor {
    private OutboxProcessor() {}

    public static int flush(Context context) {
        if (!NetworkState.get(context).isOnline()) return 0;

        SocialDb db = SocialDb.get(context);
        List<Models.OutboxItem> items = db.pendingOutbox();
        int sent = 0;
        for (Models.OutboxItem item : items) {
            if (!"text".equals(item.type)) continue;
            String result = ReplyDispatcher.sendNow(context, item.notificationKey, item.payload);
            if ("SENT".equals(result)) {
                db.updateOutbox(item.id, "SENT", null);
                db.insertMessage(item.source, item.conversationId, item.conversationTitle,
                        "You", item.payload, System.currentTimeMillis(), item.notificationKey, true);
                sent++;
            } else {
                db.updateOutbox(item.id, "RETRY", result);
            }
        }
        return sent;
    }
}
