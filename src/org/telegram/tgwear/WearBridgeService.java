/*
 * tgwear: 与手表端通信的前台 Service
 *
 * 生命周期：
 *   onCreate  → 创建 WearConnection + BridgeRouter + 启动 Telegram 推送监听
 *   onStartCommand → 升级为前台 Service，保活
 *   onDestroy → 释放连接、注销监听
 *
 * 该 Service 不直接处理 RPC，所有请求交给 BridgeRouter 路由到具体 Handler。
 */
package org.telegram.tgwear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgwear.NotificationCenterBridge;

/**
 * 桥接 Service。
 *
 * 注意：不要把这个类移到 org.telegram.messenger 包下，避免与现有 Telegram Service 命名冲突。
 */
public class WearBridgeService extends Service {

    private static final String TAG = "tgwear/Service";
    private static final String CHANNEL_ID = "tgwear_bridge";
    private static final int NOTIFICATION_ID = 0x7701;

    @Nullable
    private WearConnection connection;

    @Nullable
    private BridgeRouter router;

    @Nullable
    private NotificationCenterBridge notificationBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // 1. 创建连接（生产用 InterconnectWearConnection；开发期可改 StubWearConnection）
            // 通过 BuildConfig 或 reflection 检测 SDK 是否存在来决定使用哪个实现
            connection = createConnection();

            // 2. 创建 Router
            router = new BridgeRouter(connection);

            // 3. 启动 Telegram 推送桥接
            notificationBridge = new NotificationCenterBridge(router);
            notificationBridge.start();

            // 4. 设置连接监听 → 收到请求就交给 Router
            connection.setMessageListener(new WearConnection.Listener() {
                @Override
                public void onReadyStateChanged(boolean ready, @Nullable Object info) {
                    Log.i(TAG, "connection ready=" + ready);
                    if (router != null) {
                        router.pushEvent(
                            WearConstants.Event.CONNECTION_STATE,
                            new java.util.HashMap<String, Object>() {{
                                put("ready", ready);
                                put("reconnected", info != null);
                            }}
                        );
                    }
                }

                @Override
                public void onMessage(@Nullable String message) {
                    if (message == null || message.isEmpty()) return;
                    if (router != null) router.handle(message);
                }
            });

            connection.init();
            Log.i(TAG, "WearBridgeService started");
        } catch (Throwable t) {
            FileLog.e(TAG + " onCreate failed", t);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ensureChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Wear Bridge")
            .setContentText("正在与手表端 Telegram 通信")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (notificationBridge != null) notificationBridge.stop();
            if (router != null) router.shutdown();
            if (connection != null) connection.destroy();
        } catch (Throwable t) {
            FileLog.e(TAG + " onDestroy failed", t);
        }
        connection = null;
        router = null;
        notificationBridge = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 根据环境选择连接实现 */
    @NonNull
    private WearConnection createConnection() {
        // 优先用 InterconnectWearConnection；若 SDK 类未加载则降级为 Stub
        try {
            Class.forName("com.xiaomi.wearable.interconnect.InterconnectClient");
            return new InterconnectWearConnection(this);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "interconnect SDK not found, fallback to StubWearConnection");
            return new StubWearConnection();
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "TG Wear Bridge", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Bridge between phone Telegram and watch quick-app");
            nm.createNotificationChannel(ch);
        }
    }
}
