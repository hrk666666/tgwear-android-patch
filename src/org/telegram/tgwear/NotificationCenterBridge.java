/*
 * tgwear: Telegram 事件桥接器
 *
 * 把 Telegram 内部 NotificationCenter 的关键事件转成 JSON-RPC 事件推送，
 * 通过 BridgeRouter.pushEvent 发送到手表端。
 *
 * 监听的事件：
 *   - didReceivedNewMessages:   新消息到达
 *   - dialogsNeedReload:        会话列表变更
 *   - updateInterfaces:         通用更新（含已读状态、对话已读数等）
 *
 * 协议事件定义见 WearConstants.Event。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.List;

public class NotificationCenterBridge implements NotificationCenter.NotificationCenterDelegate {

    private static final String TAG = "tgwear/NotifyBridge";

    @NonNull
    private final BridgeRouter router;

    private final int currentAccount;
    private final NotificationCenter notificationCenter;

    public NotificationCenterBridge(@NonNull BridgeRouter router) {
        this.router = router;
        this.currentAccount = UserConfig.selectedAccount;
        this.notificationCenter = NotificationCenter.getInstance(currentAccount);
    }

    public void start() {
        // 新消息
        notificationCenter.addObserver(this, NotificationCenter.didReceivedNewMessages);
        // 会话列表变更
        notificationCenter.addObserver(this, NotificationCenter.dialogsNeedReload);
        // 通用接口更新（已读数等）
        notificationCenter.addObserver(this, NotificationCenter.updateInterfaces);
        Log.i(TAG, "NotificationCenterBridge started for account=" + currentAccount);
    }

    public void stop() {
        try {
            notificationCenter.removeObserver(this, NotificationCenter.didReceivedNewMessages);
            notificationCenter.removeObserver(this, NotificationCenter.dialogsNeedReload);
            notificationCenter.removeObserver(this, NotificationCenter.updateInterfaces);
        } catch (Throwable t) {
            Log.w(TAG, "stop failed", t);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (id == NotificationCenter.didReceivedNewMessages) {
                handleNewMessage(args);
            } else if (id == NotificationCenter.dialogsNeedReload) {
                // 会话列表变化太多，节流到 pushDialogsIfNeeded 内
                pushDialogsRefresh();
            } else if (id == NotificationCenter.updateInterfaces) {
                // args[0] 是 updateType bitmask，包含读取状态等，无需逐项推送
                // 这里仅做兜底日志，避免事件洪流
            }
        } catch (Throwable t) {
            Log.w(TAG, "didReceivedNotification failed id=" + id, t);
        }
    }

    private void handleNewMessage(Object[] args) {
        if (args == null || args.length < 3) return;
        // didReceivedNewMessages(int dialogId, MessageObject message, boolean old)
        long dialogId = ((Number) args[0]).longValue();
        Object msgObj = args[1];
        boolean isOld = args.length > 2 && Boolean.TRUE.equals(args[2]);

        if (!(msgObj instanceof MessageObject)) return;
        MessageObject mo = (MessageObject) msgObj;

        // 推送 update.newMessage
        HashMap<String, Object> data = new HashMap<>();
        data.put("peer", TdJsonConverter.peerToString(dialogId, null));
        data.put("message", TdJsonConverter.convertMessageObject(mo));

        // 附带更新后的 dialog 信息（标题、未读数、最新消息时间）
        try {
            MessagesController messages = MessagesController.getInstance(currentAccount);
            TLRPC.Dialog dialog = messages.getDialog(dialogId);
            if (dialog != null) {
                data.put("dialog", TdJsonConverter.convertDialog(dialog));
            }
        } catch (Throwable ignore) {}

        router.pushEvent(WearConstants.Event.NEW_MESSAGE, data);
    }

    /** dialogsNeedReload 节流：合并多次刷新 */
    private long lastDialogsPush = 0;
    private static final long DIALOGS_THROTTLE_MS = 1000;

    private void pushDialogsRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastDialogsPush < DIALOGS_THROTTLE_MS) return;
        lastDialogsPush = now;

        // 我们不直接推送整个会话列表（数据量大），而是让手表端通过 dialogs.get 拉取
        // 仅推送一个"会话已变更"事件，手表端可主动刷新
        // 这里不定义新事件，复用 update.connectionState 但加 ready=true 让 UI 提示"数据已更新"
        // 实际上手表端 update.newMessage 已能覆盖大多数场景，这里只做日志
        Log.d(TAG, "dialogsNeedReload (throttled)");
    }
}
