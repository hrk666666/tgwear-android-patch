/*
 * tgwear: 开机自启接收器
 *
 * 设备重启后自动启动 WearBridgeService，确保 interconnect 通道恢复。
 * 需要 RECEIVE_BOOT_COMPLETED 权限（已在 patch 中加入）。
 */
package org.telegram.tgwear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "tgwear/Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "boot completed, starting WearBridgeService");
        try {
            Intent svc = new Intent(context, WearBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start service on boot failed", t);
        }
    }
}
