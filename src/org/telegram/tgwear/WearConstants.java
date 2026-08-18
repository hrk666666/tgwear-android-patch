/*
 * tgwear: 与手表端快应用通信的协议常量
 *
 * 与 c:\Users\hrk\Downloads\tgwear-quickapp\src\utils\api.js 中的方法名一一对应。
 * 修改这里必须同步修改手表端。
 */
package org.telegram.tgwear;

public final class WearConstants {

    private WearConstants() {}

    /** RPC 请求中"是否为请求"的标记字段（手表端 api.js 在 send 时自动加上） */
    public static final String RPC_MARKER = "__rpc";

    /** 事件推送标记字段 */
    public static final String EVENT_MARKER = "__event";

    /** RPC 字段名 */
    public static final String FIELD_ID = "id";
    public static final String FIELD_METHOD = "method";
    public static final String FIELD_PARAMS = "params";
    public static final String FIELD_RESULT = "result";
    public static final String FIELD_ERROR = "error";
    public static final String FIELD_CODE = "code";
    public static final String FIELD_MSG = "msg";
    public static final String FIELD_EVENT = "event";
    public static final String FIELD_DATA = "data";

    /** RPC 方法清单 —— 与手表端 src/utils/api.js 对齐 */
    public static final class Method {
        private Method() {}
        public static final String AUTH_GET_STATE      = "auth.getState";
        public static final String AUTH_LOGOUT         = "auth.logout";
        public static final String DIALOGS_GET         = "dialogs.get";
        public static final String MESSAGES_GET_HISTORY= "messages.getHistory";
        public static final String MESSAGES_SEND_TEXT  = "messages.sendText";
        public static final String MESSAGES_SEND_STICKER = "messages.sendSticker";
        public static final String MESSAGES_MARK_READ  = "messages.markRead";
    }

    /** 事件名 */
    public static final class Event {
        private Event() {}
        public static final String NEW_MESSAGE         = "update.newMessage";
        public static final String CONNECTION_STATE    = "update.connectionState";
    }

    /** 错误码 */
    public static final class Code {
        private Code() {}
        public static final int OK              = 0;
        public static final int PARSE_ERROR     = -32700;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS  = -32602;
        public static final int INTERNAL_ERROR  = -32603;
        public static final int NOT_AUTHORIZED  = 401;
        public static final int PEER_INVALID    = 400;
    }
}
