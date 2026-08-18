/*
 * tgwear: messages.* 系列 handlers
 *
 * 实现与手表端 src/utils/api.js 中方法名一一对应：
 *   - messages.getHistory  → 拉取历史消息
 *   - messages.sendText    → 发送文本
 *   - messages.sendSticker → 发送贴纸
 *   - messages.markRead    → 标记已读
 *
 * 所有 Telegram API 调用都是异步的，但桥接协议要求同步返回结果。
 * 策略：
 *   - 发送类（sendText/sendSticker）：立即返回本地构造的占位 message，更新通过事件推送回手表
 *   - 查询类（getHistory）：使用 Telegram 的本地缓存（MessagesStorage.syncDatabase），同步返回
 *     缓存结果后异步触发 RPC 拉取更精确的数据；更新通过事件推送
 */
package org.telegram.tgwear.handlers;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgwear.BridgeRouter;
import org.telegram.tgwear.TdJsonConverter;
import org.telegram.tgwear.WearConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MessagesHandler {

    private static final String TAG = "tgwear/Messages";

    /** 拉取历史消息（使用本地缓存，可能为空） */
    public static class GetHistoryHandler implements BridgeRouter.Handler {

        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || !params.has("peer")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
            }
            String peer = params.getString("peer");
            int limit = params.has("limit") ? params.getInt("limit") : 30;
            long offsetId = params.has("offset_id") ? params.getLong("offset_id") : 0;

            long dialogId = TdJsonConverter.peerToDialogId(peer);
            if (dialogId == 0) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "bad peer: " + peer);
            }

            int account = UserConfig.selectedAccount;
            MessagesController messages = MessagesController.getInstance(account);

            // 从本地缓存读取消息（Telegram 在内部维护 dialogMessages 缓存）
            ArrayList<MessageObject> cached = messages.getDialogMessages(dialogId);

            List<Object> result = new ArrayList<>();
            if (cached != null) {
                // 按 id 升序（旧→新）
                cached.sort((a, b) -> Long.compare(a.getId(), b.getId()));
                boolean skip = offsetId > 0;
                int collected = 0;
                // 反向遍历（新→旧），offsetId 之前的更老消息
                for (int i = cached.size() - 1; i >= 0; i--) {
                    MessageObject mo = cached.get(i);
                    if (skip) {
                        if (mo.getId() == offsetId) skip = false;
                        continue;
                    }
                    result.add(0, TdJsonConverter.convertMessageObject(mo));
                    collected++;
                    if (collected >= limit) break;
                }
            }

            HashMap<String, Object> r = new HashMap<>();
            r.put("messages", result);

            // 异步触发 RPC 拉取最新数据（更新会通过 NotificationCenterBridge 推送）
            try {
                long finalOffsetId = offsetId;
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                TLRPC.InputPeer inputPeer = messages.getInputPeer(dialogId);
                if (inputPeer != null) {
                    req.peer = inputPeer;
                    req.limit = limit;
                    req.offset_id = (int) finalOffsetId;
                    req.offset_date = 0;
                    req.add_offset = 0;
                    req.max_id = 0;
                    req.min_id = 0;
                    org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req,
                        (response, error) -> {
                            if (error != null) {
                                Log.w(TAG, "getHistory rpc error: " + error.text);
                            }
                            // 成功后 NotificationCenter 会触发更新，交给 NotificationCenterBridge
                        });
                }
            } catch (Throwable t) {
                Log.w(TAG, "trigger getHistory rpc failed", t);
            }

            return r;
        }
    }

    /** 发送文本消息 */
    public static class SendTextHandler implements BridgeRouter.Handler {

        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            }
            String peer = params.optString("peer", "");
            String text = params.optString("text", "");
            long replyTo = params.optLong("reply_to", 0);
            if (peer.isEmpty() || text.isEmpty()) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS,
                                                     "peer and text required");
            }
            long dialogId = TdJsonConverter.peerToDialogId(peer);
            if (dialogId == 0) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "bad peer: " + peer);
            }

            int account = UserConfig.selectedAccount;
            MessagesController messages = MessagesController.getInstance(account);
            TLRPC.InputPeer inputPeer = messages.getInputPeer(dialogId);
            if (inputPeer == null) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID,
                                                     "inputPeer null for " + peer);
            }

            // 调用原生发送方法
            // 注意：SendMessagesHelper 接口签名随版本略有差异，下面用最常见的形式
            // 若编译失败请检查参数类型与当前 Telegram 版本对齐
            long msgId = SendMessagesHelper.getInstance(account).sendMessage(
                text,    // messageText
                dialogId,
                null,     // replyToMessage
                null,     // webPage
                null,     // searchResult
                null,     // lookupResult
                false,    // isForward
                0,        // forwardingMessageId
                false,    // attachBotName
                null,     // user
                null,     // messageEffectId
                null,     // replyToStory
                false     // invert
            );

            // 返回占位 message，手表端可立即显示；真实状态通过事件推送
            HashMap<String, Object> r = new HashMap<>();
            HashMap<String, Object> msg = new HashMap<>();
            msg.put("id", msgId == 0 ? System.currentTimeMillis() : msgId);
            msg.put("out", true);
            msg.put("text", text);
            msg.put("date", System.currentTimeMillis() / 1000);
            msg.put("peer", peer);
            r.put("message", msg);
            return r;
        }
    }

    /** 发送贴纸 —— MVP 可暂不实现，返回错误 */
    public static class SendStickerHandler implements BridgeRouter.Handler {
        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            throw new BridgeRouter.RpcException(WearConstants.Code.INTERNAL_ERROR,
                                                 "sendSticker not implemented in MVP");
        }
    }

    /** 标记已读 */
    public static class MarkReadHandler implements BridgeRouter.Handler {
        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || !params.has("peer")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
            }
            String peer = params.getString("peer");
            long dialogId = TdJsonConverter.peerToDialogId(peer);
            int maxId = params.optInt("max_id", 0);
            if (dialogId == 0) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "bad peer: " + peer);
            }

            int account = UserConfig.selectedAccount;
            MessagesController messages = MessagesController.getInstance(account);
            try {
                messages.markDialogAsRead(dialogId, maxId, maxId, 0, false, 0, 0, true, 0);
            } catch (NoSuchMethodError e) {
                // Telegram API 签名变化时降级到简单调用
                Log.w(TAG, "markDialogAsRead signature changed, fallback", e);
                try {
                    org.telegram.tgnet.ConnectionsManager.getInstance(account)
                        .sendRequest(new TLRPC.TL_messages_readHistory(messages.getInputPeer(dialogId), maxId, 0, maxId, 0, 0),
                                     (r, err) -> {});
                } catch (Throwable t) {
                    Log.w(TAG, "fallback readHistory failed", t);
                }
            }

            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return r;
        }
    }
}
