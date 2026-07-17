package com.zovexcore.browser;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.torproject.jni.TorService;

/**
 * Wraps the embedded org.torproject.jni.TorService (info.guardianproject:tor-android):
 * binding to it starts the Tor process automatically (its onCreate() calls
 * startTorServiceThread()); it broadcasts ACTION_STATUS with EXTRA_STATUS in
 * {STARTING, ON, STOPPING, OFF} as it progresses, and exposes the local SOCKS
 * port via getSocksPort() once status is ON.
 */
public class TorManager {

    public interface Listener {
        void onTorStatusChanged(String status);
        void onTorReady(int socksPort);
        void onTorError(String message);
    }

    private final Context appContext;
    private final Listener listener;
    private TorService torService;
    private boolean bound = false;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            if (status == null) {
                return;
            }
            if (listener != null) {
                listener.onTorStatusChanged(status);
            }
            if (TorService.STATUS_ON.equals(status) && torService != null && listener != null) {
                listener.onTorReady(torService.getSocksPort());
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                torService = ((TorService.LocalBinder) service).getService();
                bound = true;
            } catch (Exception e) {
                if (listener != null) {
                    listener.onTorError("Tor service connection failed: " + e.getMessage());
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            torService = null;
            bound = false;
        }
    };

    public TorManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        try {
            if (!receiverRegistered) {
                appContext.registerReceiver(statusReceiver, new IntentFilter(TorService.ACTION_STATUS));
                receiverRegistered = true;
            }
            appContext.bindService(new Intent(appContext, TorService.class), connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            if (listener != null) {
                listener.onTorError("Could not start Tor: " + e.getMessage());
            }
        }
    }

    public void stop() {
        if (bound) {
            try {
                appContext.unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(statusReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        torService = null;
    }

    public int getSocksPort() {
        return torService != null ? torService.getSocksPort() : -1;
    }
}
