package com.rokidsocialhub.companion;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.concurrent.CopyOnWriteArrayList;

public final class NetworkState {
    public interface Listener { void onNetworkStateChanged(boolean online); }

    private static volatile NetworkState instance;
    private final ConnectivityManager connectivityManager;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean online;

    public static NetworkState get(Context context) {
        if (instance == null) {
            synchronized (NetworkState.class) {
                if (instance == null) instance = new NetworkState(context.getApplicationContext());
            }
        }
        return instance;
    }

    private NetworkState(Context context) {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        online = calculateOnline();
        connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { publish(); }
            @Override public void onLost(Network network) { publish(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { publish(); }
        });
    }

    public boolean isOnline() {
        online = calculateOnline();
        return online;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private boolean calculateOnline() {
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void publish() {
        boolean newValue = calculateOnline();
        if (newValue == online) return;
        online = newValue;
        for (Listener listener : listeners) listener.onNetworkStateChanged(newValue);
    }
}
