/*
 * tgwear: dialogs.get
 */
package org.telegram.tgwear.handlers;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgwear.BridgeRouter;
import org.telegram.tgwear.TdJsonConverter;
import org.telegram.tgwear.WearConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DialogsHandler {

    private static final String TAG = "tgwear/Dialogs";

    public static class GetDialogsHandler implements BridgeRouter.Handler {

        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            int limit = params != null && params.has("limit") ? params.getInt("limit") : 30;
            long offsetId = params != null && params.has("offset_id") ? params.getLong("offset_id") : 0;

            int account = org.telegram.messenger.UserConfig.selectedAccount;
            MessagesController messages = MessagesController.getInstance(account);

            ArrayList<TLRPC.Dialog> all = messages.getAllDialogs();
            if (all == null || all.isEmpty()) {
                HashMap<String, Object> r = new HashMap<>();
                r.put("dialogs", new ArrayList<>());
                return r;
            }

            // 排序：按 last_message_date 降序
            List<TLRPC.Dialog> sorted = new ArrayList<>(all);
            // Telegram 内部已基本按时间排，但保险起见再排一次
            java.util.Collections.sort(sorted, (a, b) -> {
                long da = a.last_message_date;
                long db = b.last_message_date;
                return Long.compare(db, da);
            });

            // 偏移分页
            int start = 0;
            if (offsetId > 0) {
                for (int i = 0; i < sorted.size(); i++) {
                    TLRPC.Dialog d = sorted.get(i);
                    if (d.last_message != null && d.last_message.id == offsetId) {
                        start = i + 1;
                        break;
                    }
                }
            }
            int end = Math.min(start + limit, sorted.size());

            List<TLRPC.Dialog> page = sorted.subList(start, end);

            // 转换为协议格式（仅保留必要的字段）
            List<Object> dialogs = new ArrayList<>(page.size());
            for (TLRPC.Dialog d : page) {
                try {
                    dialogs.add(TdJsonConverter.convertDialog(d));
                } catch (Throwable t) {
                    Log.w(TAG, "convert dialog failed", t);
                }
            }

            HashMap<String, Object> result = new HashMap<>();
            result.put("dialogs", dialogs);
            return result;
        }
    }
}
