package com.rokidsocialhub.companion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Versioned JSON contract between the phone companion and the Rokid glasses client. */
public final class SocialProtocol {
    public static final int VERSION = 2;
    private SocialProtocol() {}

    public static String hello(boolean online, int queued) {
        try {
            return new JSONObject()
                    .put("v", VERSION)
                    .put("type", "hello")
                    .put("online", online)
                    .put("queued", queued)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static String conversations(List<Models.Conversation> conversations) {
        try {
            JSONArray items = new JSONArray();
            for (Models.Conversation c : conversations) {
                items.put(new JSONObject()
                        .put("source", c.source)
                        .put("id", c.conversationId)
                        .put("title", c.title)
                        .put("preview", c.lastBody)
                        .put("timestamp", c.lastTimestamp));
            }
            return new JSONObject()
                    .put("v", VERSION)
                    .put("type", "conversations")
                    .put("items", items)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static String messages(Models.Conversation conversation, List<Models.Message> messages) {
        try {
            JSONArray items = new JSONArray();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Models.Message m = messages.get(i);
                items.put(new JSONObject()
                        .put("messageId", m.id)
                        .put("source", m.source)
                        .put("sender", m.sender)
                        .put("body", m.body)
                        .put("timestamp", m.timestamp)
                        .put("outgoing", m.outgoing));
            }
            return new JSONObject()
                    .put("v", VERSION)
                    .put("type", "messages")
                    .put("id", conversation.conversationId)
                    .put("source", conversation.source)
                    .put("title", conversation.title)
                    .put("items", items)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static String replyResult(String conversationId, String result) {
        try {
            return new JSONObject()
                    .put("v", VERSION)
                    .put("type", "reply_result")
                    .put("id", conversationId)
                    .put("result", result)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }
}
