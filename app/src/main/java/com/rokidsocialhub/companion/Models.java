package com.rokidsocialhub.companion;

public final class Models {
    private Models() {}

    public static final class Message {
        public long id;
        public String source;
        public String conversationId;
        public String conversationTitle;
        public String sender;
        public String body;
        public long timestamp;
        public String notificationKey;
        public boolean outgoing;
    }

    public static final class Conversation {
        public String source;
        public String conversationId;
        public String title;
        public String lastBody;
        public long lastTimestamp;
        public String latestNotificationKey;
    }

    public static final class OutboxItem {
        public long id;
        public String source;
        public String conversationId;
        public String conversationTitle;
        public String notificationKey;
        public String type;
        public String payload;
        public String state;
        public long createdAt;
        public String lastError;
    }
}
