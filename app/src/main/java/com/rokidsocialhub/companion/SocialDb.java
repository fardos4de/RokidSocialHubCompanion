package com.rokidsocialhub.companion;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class SocialDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "social_hub.db";
    private static final int DB_VERSION = 1;
    private static volatile SocialDb instance;

    public static SocialDb get(Context context) {
        if (instance == null) {
            synchronized (SocialDb.class) {
                if (instance == null) instance = new SocialDb(context.getApplicationContext());
            }
        }
        return instance;
    }

    private SocialDb(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,conversation_id TEXT NOT NULL,conversation_title TEXT NOT NULL,sender TEXT,body TEXT NOT NULL,timestamp INTEGER NOT NULL,notification_key TEXT,outgoing INTEGER NOT NULL DEFAULT 0,fingerprint TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, timestamp DESC)");
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,conversation_id TEXT NOT NULL,conversation_title TEXT NOT NULL,notification_key TEXT,type TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,last_error TEXT)");
        db.execSQL("CREATE INDEX idx_outbox_state ON outbox(state, created_at ASC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("DROP TABLE IF EXISTS outbox");
        onCreate(db);
    }

    public synchronized void insertMessage(String source,String conversationId,String conversationTitle,String sender,String body,long timestamp,String notificationKey,boolean outgoing) {
        if (body == null || body.trim().isEmpty()) return;
        String normalizedBody = body.trim();
        String fingerprint = sha256(source + "|" + conversationId + "|" + sender + "|" + normalizedBody + "|" + timestamp + "|" + outgoing);
        ContentValues values = new ContentValues();
        values.put("source", source); values.put("conversation_id", conversationId); values.put("conversation_title", conversationTitle);
        values.put("sender", sender); values.put("body", normalizedBody); values.put("timestamp", timestamp);
        values.put("notification_key", notificationKey); values.put("outgoing", outgoing ? 1 : 0); values.put("fingerprint", fingerprint);
        getWritableDatabase().insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        getWritableDatabase().execSQL("DELETE FROM messages WHERE conversation_id=? AND id NOT IN (SELECT id FROM messages WHERE conversation_id=? ORDER BY timestamp DESC LIMIT 100)", new Object[]{conversationId, conversationId});
    }

    public synchronized List<Models.Conversation> recentConversations(int limit) {
        List<Models.Conversation> result = new ArrayList<>();
        String sql = "SELECT m.source,m.conversation_id,m.conversation_title,m.body,m.timestamp,m.notification_key FROM messages m JOIN (SELECT conversation_id,MAX(timestamp) max_time FROM messages GROUP BY conversation_id) x ON m.conversation_id=x.conversation_id AND m.timestamp=x.max_time GROUP BY m.conversation_id ORDER BY m.timestamp DESC LIMIT ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) result.add(conversationFromCursor(cursor));
        }
        return result;
    }

    public synchronized Models.Conversation conversationById(String conversationId) {
        String sql = "SELECT source,conversation_id,conversation_title,body,timestamp,notification_key FROM messages WHERE conversation_id=? ORDER BY timestamp DESC LIMIT 1";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{conversationId})) {
            return cursor.moveToFirst() ? conversationFromCursor(cursor) : null;
        }
    }

    private Models.Conversation conversationFromCursor(Cursor cursor) {
        Models.Conversation c = new Models.Conversation();
        c.source = cursor.getString(0); c.conversationId = cursor.getString(1); c.title = cursor.getString(2);
        c.lastBody = cursor.getString(3); c.lastTimestamp = cursor.getLong(4); c.latestNotificationKey = cursor.getString(5);
        return c;
    }

    public synchronized List<Models.Message> recentMessages(String conversationId, int limit) {
        List<Models.Message> result = new ArrayList<>();
        String sql = "SELECT id,source,conversation_id,conversation_title,sender,body,timestamp,notification_key,outgoing FROM messages WHERE conversation_id=? ORDER BY timestamp DESC LIMIT ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{conversationId, String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                Models.Message m = new Models.Message();
                m.id = cursor.getLong(0); m.source = cursor.getString(1); m.conversationId = cursor.getString(2); m.conversationTitle = cursor.getString(3);
                m.sender = cursor.getString(4); m.body = cursor.getString(5); m.timestamp = cursor.getLong(6); m.notificationKey = cursor.getString(7); m.outgoing = cursor.getInt(8) != 0;
                result.add(m);
            }
        }
        return result;
    }

    public synchronized long enqueueText(String source,String conversationId,String conversationTitle,String notificationKey,String text,String state,String lastError) {
        ContentValues values = new ContentValues();
        values.put("source", source); values.put("conversation_id", conversationId); values.put("conversation_title", conversationTitle); values.put("notification_key", notificationKey);
        values.put("type", "text"); values.put("payload", text); values.put("state", state); values.put("created_at", System.currentTimeMillis()); values.put("last_error", lastError);
        return getWritableDatabase().insert("outbox", null, values);
    }

    public synchronized List<Models.OutboxItem> pendingOutbox() {
        List<Models.OutboxItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,source,conversation_id,conversation_title,notification_key,type,payload,state,created_at,last_error FROM outbox WHERE state IN ('QUEUED','RETRY') ORDER BY created_at ASC", null)) {
            while (cursor.moveToNext()) {
                Models.OutboxItem item = new Models.OutboxItem();
                item.id = cursor.getLong(0); item.source = cursor.getString(1); item.conversationId = cursor.getString(2); item.conversationTitle = cursor.getString(3); item.notificationKey = cursor.getString(4);
                item.type = cursor.getString(5); item.payload = cursor.getString(6); item.state = cursor.getString(7); item.createdAt = cursor.getLong(8); item.lastError = cursor.getString(9); result.add(item);
            }
        }
        return result;
    }

    public synchronized int outboxCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state IN ('QUEUED','RETRY')", null)) { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
    }

    public synchronized void updateOutbox(long id, String state, String lastError) {
        ContentValues values = new ContentValues(); values.put("state", state); values.put("last_error", lastError);
        getWritableDatabase().update("outbox", values, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void clearAll() { getWritableDatabase().delete("messages", null, null); getWritableDatabase().delete("outbox", null, null); }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8)); StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b)); return builder.toString();
        } catch (Exception e) { return Integer.toHexString(input.hashCode()); }
    }
}
