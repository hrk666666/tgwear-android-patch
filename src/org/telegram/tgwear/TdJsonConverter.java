/*
 * tgwear: TLRPC 对象 ↔ JSON 协议结构转换器
 *
 * 协议定义见 plan-quickapp-tg.md 4.5 节，与手表端 src/utils/format.js 中 _mapDialog
 * 和 src/pages/chat/chat.ux 中 _mapMsg 严格对齐。
 *
 * 我们不直接序列化整个 TLRPC 对象（避免暴露内部字段、防止 JSON 体积过大），
 * 而是按协议挑出 UI 真正需要的字段。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class TdJsonConverter {

    private static final String TAG = "tgwear/Converter";

    private TdJsonConverter() {}

    /**
     * 把任意 Java 对象转成可放入 JSONObject 的值：
     *   - null / Boolean / Integer / Long / Double / String → 原样
     *   - Map → JSONObject
     *   - Collection / 数组 → JSONArray
     *   - TLRPC.User → user JSON
     *   - TLRPC.Chat  → chat JSON
     *   - TLRPC.Dialog → dialog JSON
     *   - TLRPC.Message / MessageObject → message JSON
     *   - 其他对象 → 用反射提取 getter（最简策略，避免引入 Gson）
     */
    @Nullable
    public static Object toJSON(@Nullable Object o) {
        if (o == null) return null;
        if (o instanceof String || o instanceof Boolean || o instanceof Number) return o;
        if (o instanceof Map) {
            JSONObject jo = new JSONObject();
            for (Object e : ((Map<?,?>) o).entrySet()) {
                Map.Entry<?,?> entry = (Map.Entry<?,?>) e;
                try {
                    jo.put(String.valueOf(entry.getKey()), toJSON(entry.getValue()));
                } catch (Throwable ignore) {}
            }
            return jo;
        }
        if (o instanceof Collection) {
            JSONArray arr = new JSONArray();
            for (Object item : (Collection<?>) o) {
                arr.put(toJSON(item));
            }
            return arr;
        }
        if (o.getClass().isArray()) {
            JSONArray arr = new JSONArray();
            int len = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < len; i++) {
                arr.put(toJSON(java.lang.reflect.Array.get(o, i)));
            }
            return arr;
        }
        if (o instanceof TLRPC.User)   return convertUser((TLRPC.User) o);
        if (o instanceof TLRPC.Chat)   return convertChat((TLRPC.Chat) o);
        if (o instanceof TLRPC.Dialog) return convertDialog((TLRPC.Dialog) o);
        if (o instanceof TLRPC.Message)return convertMessage((TLRPC.Message) o, 0);
        if (o instanceof MessageObject)return convertMessageObject((MessageObject) o);
        // fallback：toString，避免编译失败
        return o.toString();
    }

    /* === User === */
    @NonNull
    public static JSONObject convertUser(@NonNull TLRPC.User user) {
        JSONObject j = new JSONObject();
        try {
            j.put("id", user.id);
            j.put("first_name", nullToEmpty(user.first_name));
            j.put("last_name", nullToEmpty(user.last_name));
            j.put("username", nullToEmpty(user.username));
            j.put("phone", nullToEmpty(user.phone));
            j.put("self", user.self);
        } catch (Throwable t) {
            Log.w(TAG, "convertUser failed", t);
        }
        return j;
    }

    /* === Chat === */
    @NonNull
    public static JSONObject convertChat(@NonNull TLRPC.Chat chat) {
        JSONObject j = new JSONObject();
        try {
            j.put("id", chat.id);
            j.put("title", nullToEmpty(chat.title));
            j.put("username", nullToEmpty(chat.username));
            j.put("participants_count", chat.participants_count);
            j.put("is_channel", chat.megagroup || chat.channel);
        } catch (Throwable t) {
            Log.w(TAG, "convertChat failed", t);
        }
        return j;
    }

    /* === Dialog === */
    @NonNull
    public static JSONObject convertDialog(@NonNull TLRPC.Dialog dialog) {
        JSONObject j = new JSONObject();
        try {
            j.put("peer", peerToString(dialog.id, dialog));
            j.put("title", getDialogTitle(dialog));
            j.put("unread_count", dialog.unread_count);
            j.put("last_message_date", dialog.last_message_date);
            j.put("last_message_id", dialog.last_message != null ? dialog.last_message.id : 0);
            j.put("last_message", convertMessage(dialog.last_message, 0));
            j.put("pinned", dialog.pinned);
        } catch (Throwable t) {
            Log.w(TAG, "convertDialog failed", t);
        }
        return j;
    }

    /* === Message === */
    @NonNull
    public static JSONObject convertMessage(@Nullable TLRPC.Message msg, long dialogId) {
        JSONObject j = new JSONObject();
        if (msg == null) return j;
        try {
            j.put("id", msg.id);
            j.put("date", msg.date);
            j.put("out", msg.out);
            j.put("from_id", msg.from_id != null ? msg.from_id.user_id : 0);
            j.put("peer", peerToString(dialogId != 0 ? dialogId : msg.dialog_id, null));
            j.put("text", nullToEmpty(msg.message));
            // 媒体消息：贴纸
            if (msg.media != null && msg.media.document != null) {
                TLRPC.Document doc = msg.media.document;
                boolean isSticker = false;
                String emoji = "";
                if (doc.attributes != null) {
                    for (TLRPC.DocumentAttribute attr : doc.attributes) {
                        if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                            isSticker = true;
                            emoji = nullToEmpty(((TLRPC.TL_documentAttributeSticker) attr).alt);
                            break;
                        }
                    }
                }
                if (isSticker) {
                    JSONObject sticker = new JSONObject();
                    sticker.put("id", doc.id);
                    sticker.put("emoji", emoji);
                    // 手表端不渲染图片，svg 留空
                    sticker.put("svg", "");
                    j.put("sticker", sticker);
                }
            }
            j.put("reply_to", msg.reply_to != null ? msg.reply_to.reply_to_msg_id : 0);
        } catch (Throwable t) {
            Log.w(TAG, "convertMessage failed", t);
        }
        return j;
    }

    /* === MessageObject === */
    @NonNull
    public static JSONObject convertMessageObject(@NonNull MessageObject mo) {
        TLRPC.Message msg = mo.messageOwner;
        return convertMessage(msg, mo.getDialogId());
    }

    /* === Helpers === */

    /**
     * peer 字符串格式：与手表端 plan-quickapp-tg.md 4.5 节一致：
     *   - 用户对话: "user:<id>"
     *   - 普通群:   "chat:<id>"
     *   - 频道/超级群（id 为负）: "chat:-<abs_id>"
     */
    @NonNull
    public static String peerToString(long dialogId, @Nullable TLRPC.Dialog dialog) {
        // Telegram 内部：dialog_id > 0 是用户，< 0 是群/频道
        if (dialogId > 0) return "user:" + dialogId;
        return "chat:" + dialogId;
    }

    /** 反解 peer 字符串为 Telegram 内部 dialog_id */
    public static long peerToDialogId(@NonNull String peer) {
        int idx = peer.indexOf(':');
        if (idx < 0) {
            try { return Long.parseLong(peer); } catch (NumberFormatException e) { return 0; }
        }
        try {
            return Long.parseLong(peer.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NonNull
    private static String getDialogTitle(@NonNull TLRPC.Dialog dialog) {
        int currentAccount = UserConfig.selectedAccount;
        long id = dialog.id;
        if (id > 0) {
            TLRPC.User u = org.telegram.messenger.MessagesController.getInstance(currentAccount).getUser(id);
            if (u != null) {
                String name = u.first_name == null ? "" : u.first_name;
                if (u.last_name != null && !u.last_name.isEmpty()) name += " " + u.last_name;
                return name.isEmpty() ? (u.username != null ? "@" + u.username : "未知") : name;
            }
        } else {
            TLRPC.Chat c = org.telegram.messenger.MessagesController.getInstance(currentAccount).getChat(-id);
            if (c != null && c.title != null) return c.title;
        }
        return "未知";
    }

    @NonNull
    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
