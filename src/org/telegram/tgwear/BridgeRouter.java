/*
 * tgwear: JSON-RPC 路由器
 *
 * 职责：
 *   1. 解析手表端发来的 JSON 请求 {id, method, params}
 *   2. 按 method 路由到对应 Handler
 *   3. 把 Handler 返回的 result 包成 {id, result} 发回去
 *   4. Handler 抛异常时返回 {id, error: {code, msg}}
 *
 * 协议定义见 plan-quickapp-tg.md 第四节，与手表端 src/utils/api.js 严格对齐。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgwear.handlers.AuthHandler;
import org.telegram.tgwear.handlers.DialogsHandler;
import org.telegram.tgwear.handlers.MessagesHandler;

import java.util.HashMap;
import java.util.Map;

public class BridgeRouter {

    private static final String TAG = "tgwear/Router";

    /** Handler 接口：每个 method 对应一个实现 */
    public interface Handler {
        /**
         * @param params 手表端传来的 params，可能为 null
         * @return result 对象，会被序列化成 JSON 发回
         * @throws Exception 任何异常都会被捕获并转成 error 响应
         */
        @NonNull
        Object handle(@Nullable JSONObject params) throws Exception;
    }

    @NonNull
    private final Map<String, Handler> handlers = new HashMap<>();

    @NonNull
    private final WearConnection connection;

    /** 处理线程独立，避免阻塞主线程 */
    @NonNull
    private final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    public BridgeRouter(@NonNull WearConnection connection) {
        this.connection = connection;

        // 注册 handlers
        register(WearConstants.Method.AUTH_GET_STATE,       new AuthHandler());
        register(WearConstants.Method.AUTH_LOGOUT,          new AuthHandler.LogoutHandler());
        register(WearConstants.Method.DIALOGS_GET,         new DialogsHandler.GetDialogsHandler());
        register(WearConstants.Method.MESSAGES_GET_HISTORY, new MessagesHandler.GetHistoryHandler());
        register(WearConstants.Method.MESSAGES_SEND_TEXT,   new MessagesHandler.SendTextHandler());
        register(WearConstants.Method.MESSAGES_SEND_STICKER,new MessagesHandler.SendStickerHandler());
        register(WearConstants.Method.MESSAGES_MARK_READ,   new MessagesHandler.MarkReadHandler());
    }

    private void register(@NonNull String method, @NonNull Handler h) {
        handlers.put(method, h);
    }

    /** 处理一帧来自手表的 JSON 字符串 */
    public void handle(@NonNull final String raw) {
        executor.execute(() -> {
            try {
                JSONObject req = new JSONObject(raw);
                // 必须有 RPC 标记且带 id（否则忽略）
                if (!req.has(WearConstants.RPC_MARKER)) {
                    Log.d(TAG, "non-rpc message ignored: " + truncate(raw, 200));
                    return;
                }
                if (!req.has(WearConstants.FIELD_ID)) {
                    Log.w(TAG, "rpc request without id: " + truncate(raw, 200));
                    return;
                }
                long id = req.getLong(WearConstants.FIELD_ID);
                String method = req.optString(WearConstants.FIELD_METHOD, "");
                JSONObject params = req.optJSONObject(WearConstants.FIELD_PARAMS);

                Handler h = handlers.get(method);
                if (h == null) {
                    sendError(id, WearConstants.Code.METHOD_NOT_FOUND,
                              "method not found: " + method);
                    return;
                }

                Object result;
                try {
                    result = h.handle(params);
                } catch (RpcException e) {
                    sendError(id, e.code, e.getMessage());
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "handler " + method + " failed", t);
                    sendError(id, WearConstants.Code.INTERNAL_ERROR,
                              "internal: " + t.getMessage());
                    return;
                }

                sendResult(id, result);
            } catch (JSONException e) {
                Log.w(TAG, "parse failed: " + truncate(raw, 200), e);
            }
        });
    }

    /** 发送 RPC 成功响应 */
    public void sendResult(long id, @Nullable Object result) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(WearConstants.FIELD_ID, id);
            // 用 toJSON 把对象转成 JSON（用 TdJsonConverter 统一处理）
            Object json = TdJsonConverter.toJSON(result);
            resp.put(WearConstants.FIELD_RESULT, json);
            connection.send(resp.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendResult failed for id=" + id, t);
        }
    }

    /** 发送 RPC 错误响应 */
    public void sendError(long id, int code, @Nullable String msg) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(WearConstants.FIELD_ID, id);
            JSONObject err = new JSONObject();
            err.put(WearConstants.FIELD_CODE, code);
            err.put(WearConstants.FIELD_MSG, msg == null ? "" : msg);
            resp.put(WearConstants.FIELD_ERROR, err);
            connection.send(resp.toString());
        } catch (JSONException ignore) {
        }
    }

    /** 推送事件到手表端（单向） */
    public void pushEvent(@NonNull String event, @Nullable Object data) {
        try {
            JSONObject msg = new JSONObject();
            msg.put(WearConstants.EVENT_MARKER, true);
            msg.put(WearConstants.FIELD_EVENT, event);
            msg.put(WearConstants.FIELD_DATA, TdJsonConverter.toJSON(data));
            connection.send(msg.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushEvent " + event + " failed", t);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    /** RPC 异常 */
    public static class RpcException extends Exception {
        public final int code;
        public RpcException(int code, @NonNull String msg) {
            super(msg);
            this.code = code;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
