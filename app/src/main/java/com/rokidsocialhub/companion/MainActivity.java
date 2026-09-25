package com.rokidsocialhub.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity implements NetworkState.Listener, RokidTransport.Listener {
    private static final int REQUEST_BLUETOOTH = 1001;
    private static final int REQUEST_ENABLE_BLUETOOTH = 1002;
    private TextView internetStatus, notificationStatus, bluetoothStatus, outboxStatus;
    private LinearLayout conversationsContainer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NetworkState.get(this).addListener(this);
        RokidTransport.initialize(this);
        RokidTransport.addListener(this);
        buildUi();
        refresh();
    }

    @Override protected void onDestroy() {
        NetworkState.get(this).removeListener(this);
        RokidTransport.removeListener(this);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        OutboxProcessor.flush(this);
        RokidTransport.initialize(this);
        refresh();
    }

    @Override public void onNetworkStateChanged(boolean online) {
        runOnUiThread(() -> { if (online) OutboxProcessor.flush(this); refresh(); });
    }

    @Override public void onRokidStatusChanged(String status) {
        runOnUiThread(this::refresh);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root);

        root.addView(text("Rokid Social Hub", 28, true));
        TextView subtitle = text("Phone companion · App 1 · v0.2.1 diagnostic transport", 15, false);
        subtitle.setPadding(0,0,0,dp(20));
        root.addView(subtitle);

        internetStatus=statusText();
        notificationStatus=statusText();
        bluetoothStatus=statusText();
        outboxStatus=statusText();
        root.addView(internetStatus);
        root.addView(notificationStatus);
        root.addView(bluetoothStatus);
        root.addView(outboxStatus);

        Button notificationAccess=button("Grant notification access");
        notificationAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(notificationAccess);

        Button bluetooth=button("Connect / diagnose Rokid Glass3");
        bluetooth.setOnClickListener(v -> requestBluetoothPermissionAndConnect());
        root.addView(bluetooth);

        Button refresh=button("Refresh / retry queued replies");
        refresh.setOnClickListener(v -> {
            int sent=OutboxProcessor.flush(this);
            Toast.makeText(this,sent>0?"Sent "+sent+" queued message(s)":"Nothing sent",Toast.LENGTH_SHORT).show();
            refresh();
        });
        root.addView(refresh);

        Button demo=button("Add demo conversation");
        demo.setOnClickListener(v -> {
            SocialDb.get(this).insertMessage("Demo","demo:Ahmed","Ahmed","Ahmed","This is a local demo message. Real WhatsApp/Telegram messages appear after notification access is granted.",System.currentTimeMillis(),"demo-key",false);
            RokidTransport.pushRecentConversations();
            refresh();
        });
        root.addView(demo);

        Button protocol=button("Show glasses protocol preview");
        protocol.setOnClickListener(v -> showProtocolPreview());
        root.addView(protocol);

        Button clear=button("Clear local Social Hub cache");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear local cache?")
                .setMessage("This removes cached messages and queued replies stored by Social Hub. It does not delete anything from WhatsApp or Telegram.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Clear",(d,w)->{SocialDb.get(this).clearAll(); RokidTransport.pushRecentConversations(); refresh();})
                .show());
        root.addView(clear);

        TextView section=text("Recent conversations",20,true);
        section.setPadding(0,dp(24),0,dp(8));
        root.addView(section);
        TextView hint=text("The phone remains the master. When Glass3 is connected, these 5 conversations and up to 20 messages per opened chat are synchronized over classic Bluetooth.",14,false);
        hint.setPadding(0,0,0,dp(12));
        root.addView(hint);
        conversationsContainer=new LinearLayout(this);
        conversationsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(conversationsContainer);
        setContentView(scroll);
    }

    private void refresh() {
        if (internetStatus==null) return;
        boolean online=NetworkState.get(this).isOnline();
        internetStatus.setText("Internet: "+(online?"ONLINE":"OFFLINE — outgoing actions will queue"));
        notificationStatus.setText("Notification access: "+(hasNotificationAccess()?"GRANTED":"NOT GRANTED"));
        bluetoothStatus.setText("Bluetooth: "+bluetoothSummary()+"\nRokid link: "+RokidTransport.getStatus());
        outboxStatus.setText("Queued replies: "+SocialDb.get(this).outboxCount());
        renderConversations();
    }

    private void renderConversations() {
        conversationsContainer.removeAllViews();
        List<Models.Conversation> conversations=SocialDb.get(this).recentConversations(5);
        if (conversations.isEmpty()) {
            TextView empty=text("No cached conversations yet.",15,false);
            empty.setPadding(0,dp(12),0,dp(12));
            conversationsContainer.addView(empty);
            return;
        }
        for (Models.Conversation conversation:conversations) {
            Button row=button(conversation.source+" · "+conversation.title+"\n"+trimPreview(conversation.lastBody));
            row.setAllCaps(false);
            row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v->showConversation(conversation));
            conversationsContainer.addView(row);
        }
    }

    private void showConversation(Models.Conversation conversation) {
        List<Models.Message> messages=SocialDb.get(this).recentMessages(conversation.conversationId,20);
        StringBuilder builder=new StringBuilder();
        for(int i=messages.size()-1;i>=0;i--){
            Models.Message m=messages.get(i);
            builder.append(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(m.timestamp)))
                    .append("  ").append(m.outgoing?"You":(m.sender==null?conversation.title:m.sender))
                    .append("\n").append(m.body).append("\n\n");
        }
        boolean canReply=NotificationReplyRegistry.has(conversation.latestNotificationKey);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle(conversation.source+" · "+conversation.title)
                .setMessage(builder.length()==0?"No cached messages.":builder.toString())
                .setNegativeButton("Close",null)
                .setNeutralButton("Reply",null)
                .create();
        dialog.setOnShowListener(ignored->{
            Button reply=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            reply.setEnabled(canReply||!NetworkState.get(this).isOnline());
            reply.setOnClickListener(v->showReplyDialog(conversation));
        });
        dialog.show();
    }

    private void showReplyDialog(Models.Conversation conversation) {
        EditText input=new EditText(this);
        input.setHint("Message");
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad=dp(18);
        input.setPadding(pad,pad,pad,pad);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Reply to "+conversation.title)
                .setView(input)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Send",null)
                .create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=input.getText().toString().trim();
            if(value.isEmpty())return;
            String result=ReplyDispatcher.sendOrQueue(this,conversation,value);
            Toast.makeText(this,humanResult(result),Toast.LENGTH_LONG).show();
            dialog.dismiss();
            RokidTransport.pushRecentConversations();
            refresh();
        }));
        dialog.show();
    }

    private void showProtocolPreview(){
        String payload=SocialProtocol.conversations(SocialDb.get(this).recentConversations(5));
        new AlertDialog.Builder(this).setTitle("Phone → glasses payload").setMessage(payload).setPositiveButton("Close",null).show();
    }

    private boolean hasNotificationAccess(){
        String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");
        if(enabled==null)return false;
        ComponentName component=new ComponentName(this,SocialNotificationListener.class);
        return enabled.contains(component.flattenToString())||enabled.contains(component.flattenToShortString());
    }

    private void requestBluetoothPermissionAndConnect(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQUEST_BLUETOOTH);
                return;
            }
        } else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_BLUETOOTH);
            return;
        }

        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){
            Toast.makeText(this,"Bluetooth is not available on this phone.",Toast.LENGTH_LONG).show();
            return;
        }
        if(!adapter.isEnabled()){
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
            return;
        }

        RokidTransport.initialize(this);
        RokidTransport.ensureConnected();
        refresh();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQUEST_BLUETOOTH){
            boolean ok=grantResults.length>0;
            for(int r:grantResults)ok&=r==PackageManager.PERMISSION_GRANTED;
            if(ok) requestBluetoothPermissionAndConnect();
            else Toast.makeText(this,"Bluetooth scan and connection permissions are needed.",Toast.LENGTH_LONG).show();
            refresh();
        }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_ENABLE_BLUETOOTH){
            if(resultCode==RESULT_OK) requestBluetoothPermissionAndConnect();
            else Toast.makeText(this,"Bluetooth must be enabled to connect the glasses.",Toast.LENGTH_LONG).show();
            refresh();
        }
    }

    private String bluetoothSummary(){
        try{
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
                return "permission needed";
            BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null)return "not supported";
            if(!adapter.isEnabled())return "OFF";
            Set<BluetoothDevice> bonded=adapter.getBondedDevices();
            List<String> likely=new ArrayList<>();
            for(BluetoothDevice d:bonded){
                String name=d.getName()==null?"":d.getName();
                String lower=name.toLowerCase();
                if(lower.contains("rokid")||lower.contains("glass")) likely.add(name);
            }
            String suffix=likely.isEmpty()?"":" · likely: "+String.join(", ",likely);
            return "ON · "+bonded.size()+" paired device(s)"+suffix;
        }catch(SecurityException e){
            return "permission needed";
        }
    }

    private String humanResult(String result){
        if("SENT".equals(result))return "Reply sent through the active notification action.";
        if("QUEUED_OFFLINE".equals(result))return "No internet. Reply queued on the phone.";
        if("NO_ACTIVE_REPLY_ACTION".equals(result))return "Queued. The notification reply action is not currently active.";
        if("REPLY_ACTION_EXPIRED".equals(result))return "Queued. The old notification reply action expired.";
        return result;
    }

    private TextView statusText(){TextView view=text("",15,true);view.setPadding(0,dp(4),0,dp(4));return view;}
    private TextView text(String value,int sp,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(sp);if(bold)view.setTypeface(view.getTypeface(),android.graphics.Typeface.BOLD);return view;}
    private Button button(String label){Button button=new Button(this);button.setText(label);button.setAllCaps(false);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);params.topMargin=dp(8);button.setLayoutParams(params);button.setPadding(dp(12),dp(10),dp(12),dp(10));return button;}
    private String trimPreview(String value){if(value==null)return "";String clean=value.replace('\n',' ').trim();return clean.length()>90?clean.substring(0,87)+"...":clean;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
