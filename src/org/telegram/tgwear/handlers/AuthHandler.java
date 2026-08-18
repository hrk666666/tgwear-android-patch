/*
 * tgwear: auth.getState / auth.logout
 */
package org.telegram.tgwear.handlers;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgwear.BridgeRouter;
import org.telegram.tgwear.TdJsonConverter;
import org.telegram.tgwear.WearConstants;

import java.util.HashMap;

public class AuthHandler implements BridgeRouter.Handler {

    private static final String TAG = "tgwear/Auth";

    @NonNull
    @Override
    public Object handle(@Nullable JSONObject params) throws Exception {
        // auth.getState
        int account = UserConfig.selectedAccount;
        UserConfig config = UserConfig.getInstance(account);
        boolean authorized = config != null && config.getClientUserId() != 0 && config.isClientActivated();

        HashMap<String, Object> result = new HashMap<>();
        result.put("authorized", authorized);
        if (authorized) {
            HashMap<String, Object> user = new HashMap<>();
            user.put("id", config.getClientUserId());
            user.put("name", config.getUserName());
            user.put("phone", config.getCurrentUser() != null ? config.getCurrentUser().phone : "");
            result.put("user", user);
        }
        return result;
    }

    /** auth.logout —— 单独定义为内部类，便于在 Router 中注册 */
    public static class LogoutHandler implements BridgeRouter.Handler {
        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            int account = UserConfig.selectedAccount;
            try {
                org.telegram.messenger.MessagesController.getInstance(account).performLogout(0);
            } catch (Throwable t) {
                Log.w(TAG, "performLogout failed", t);
            }
            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return r;
        }
    }
}
