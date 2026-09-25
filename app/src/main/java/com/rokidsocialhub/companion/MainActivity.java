package com.rokidsocialhub.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity implements NetworkState.Listener {
    private static final int REQUEST_BLUETOOTH = 1001;

    private TextView internetStatus;
    private TextView notificationStatus;
    private TextView bluetoothStatus;
    private TextView outboxStatus;
    private LinearLayout conversationsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NetworkState.get(this).addListener(this);
        buildUi();
        refresh();
    }

    @Override
    protected void onDestroy() {
        NetworkState.get(this).removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        OutboxProcessor.flush(this);
        refresh();
    }

    @Override
    public void onNetworkStateChanged(boolean online) {
        runOnUiThread(() -> {
            if (online) OutboxProcessor.flush(this);
            refresh();
        });
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root);

        TextView title = text("Rokid Social Hub", 28, true);
        root.addView(title);
        TextView subtitle = text("Phone companion · App 1 · v0.1", 15, false);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        internetStatus = statusText();
        notificationStatus = statusText();
        bluetoothStatus = statusText();
        outboxStatus = statusText();
        root.addView(internetStatus);
        root.addView(notificationStatus);
        root.addView(bluetoothStatus);
        root.addView(outboxStatus);

        Button notificationAccess = button("Grant notification access");
        notificationAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(notificationAccess);

        Button bluetooth = button("Allow Nearby devices / Bluetooth");
        bluetooth.setOnClickListener(v -> requestBluetoothPermission());
        root.addView(bluetooth);

        Button refresh = button("Refresh / retry queued replies");
        refresh.setOnClickListener(v -> {
            int sent = OutboxProcessor.flush(this);
            Toast.makeText(this, sent > 0 ? "Sent " + sent + " queued message(s)" : "Nothing sent", Toast.LENGTH_SHORT).show();
            refresh();
        });
        root.addView(refresh);

        Button demo = button("Add demo conversation");
        demo.setOnClickListener(v -> {
            SocialDb.get(this).insertMessage("Demo", "demo:Ahmed", "Ahmed", "Ahmed",
                    "This is a local demo message. Real WhatsApp/Telegram messages appear after notification access is granted.",
                    System.currentTimeMillis(), "demo-key", false);
            refresh();
        });
        root.addView(demo);

        Button protocol = button("Show glasses protocol preview");
        protocol.setOnClickListener(v -> showProtocolPreview());
        root.addView(protocol);

        Button clear = button("Clear local Social Hub cache");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear local cache?")
                .setMessage("This removes cached messages and queued replies stored by Social Hub. It does not delete anything from WhatsApp or Telegram.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    SocialDb.get(this).clearAll();
                    refresh();
                }).show());
        root.addView(clear);

        TextView section = text("Recent conversations", 20, true);
        section.setPadding(0, dp(24), 0, dp(8));
        root.addView(section);

        TextView hint = text("The companion caches notification-visible WhatsApp/Telegram messages. Tap a conversation to inspect its last 20 cached messages or try a notification reply.", 14, false);
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint);

        conversationsContainer = new LinearLayout(this);
        conversationsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(conversationsContainer);

        setContentView(scroll);
    }

    private void refresh() {
        if (internetStatus == null) return;
        boolean online = NetworkState.get(this).isOnline();
        internetStatus.setText("Internet: " + (online ? "ONLINE" : "OFFLINE — new outgoing actions will queue"));
        notificationStatus.setText("Notification access: " + (hasNotificationAccess() ? "GRANTED" : "NOT GRANTED"));
        bluetoothStatus.setText("Bluetooth: " + bluetoothSummary());
        outboxStatus.setText("Queued replies: " + SocialDb.get(this).outboxCount());
        renderConversations();
    }

    private void renderConversations() {
        conversationsContainer.removeAllViews();
        List<Models.Conversation> conversations = SocialDb.get(this).recentConversations(5);
        if (conversations.isEmpty()) {
            TextView empty = text("No cached conversations yet.", 15, false);
            empty.setPadding(0, dp(12), 0, dp(12));
            conversationsContainer.addView(empty);
            return;
        }
        for (Models.Conversation conversation : conversations) {
            Button row = button(conversation.source + " · " + conversation.title + "\n" + trimPreview(conversation.lastBody));
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> showConversation(conversation));
            conversationsContainer.addView(row);
        }
    }

    private void showConversation(Models.Conversation conversation) {
        List<Models.Message> messages = SocialDb.get(this).recentMessages(conversation.conversationId, 20);
        StringBuilder builder = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Models.Message m = messages.get(i);
            builder.append(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(m.timestamp)))
                    .append("  ")
                    .append(m.outgoing ? "You" : (m.sender == null ? conversation.title : m.sender))
                    .append("\n")
                    .append(m.body)
                    .append("\n\n");
        }
        boolean canReply = NotificationReplyRegistry.has(conversation.latestNotificationKey);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(conversation.source + " · " + conversation.title)
                .setMessage(builder.length() == 0 ? "No cached messages." : builder.toString())
                .setNegativeButton("Close", null)
                .setNeutralButton("Reply", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button reply = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            reply.setEnabled(canReply || !NetworkState.get(this).isOnline());
            reply.setOnClickListener(v -> showReplyDialog(conversation));
        });
        dialog.show();
    }

    private void showReplyDialog(Models.Conversation conversation) {
        EditText input = new EditText(this);
        input.setHint("Message");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = dp(18);
        input.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Reply to " + conversation.title)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            String result = ReplyDispatcher.sendOrQueue(this, conversation, text);
            Toast.makeText(this, humanResult(result), Toast.LENGTH_LONG).show();
            dialog.dismiss();
            refresh();
        }));
        dialog.show();
    }

    private void showProtocolPreview() {
        String payload = SocialProtocol.conversations(SocialDb.get(this).recentConversations(5));
        new AlertDialog.Builder(this)
                .setTitle("Phone → glasses payload")
                .setMessage(payload)
                .setPositiveButton("Close", null)
                .show();
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, SocialNotificationListener.class);
        return enabled.contains(component.flattenToString()) || enabled.contains(component.flattenToShortString());
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
        } else {
            Toast.makeText(this, bluetoothSummary(), Toast.LENGTH_LONG).show();
        }
    }

    private String bluetoothSummary() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return "permission needed";
            }
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return "not supported";
            if (!adapter.isEnabled()) return "OFF";
            Set<android.bluetooth.BluetoothDevice> bonded = adapter.getBondedDevices();
            return "ON · " + bonded.size() + " paired device(s)";
        } catch (SecurityException e) {
            return "permission needed";
        }
    }

    private String humanResult(String result) {
        if ("SENT".equals(result)) return "Reply sent through the active notification action.";
        if ("QUEUED_OFFLINE".equals(result)) return "No internet. Reply queued on the phone.";
        if ("NO_ACTIVE_REPLY_ACTION".equals(result)) return "Queued. The notification reply action is not currently active.";
        if ("REPLY_ACTION_EXPIRED".equals(result)) return "Queued. The old notification reply action expired.";
        return result;
    }

    private TextView statusText() {
        TextView view = text("", 15, true);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        return button;
    }

    private String trimPreview(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').trim();
        return clean.length() > 90 ? clean.substring(0, 87) + "..." : clean;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
