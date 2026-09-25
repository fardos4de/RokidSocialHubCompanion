package com.rokidsocialhub.companion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Versioned protocol between the phone companion and the future Rokid glasses client.
 * Transport is intentionally abstract in App 1: the same payloads can later ride over
 * Rokid's phone/glasses channel or a Bluetooth implementation without changing app logic.
 */
public final class SocialProtocol {
    public static final int VERSION = 1;
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
}
