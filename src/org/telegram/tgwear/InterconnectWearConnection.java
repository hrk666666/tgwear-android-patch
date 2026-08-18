/*
 * tgwear: 基于小米穿戴 interconnect SDK 的连接实现
 *
 * 接入步骤（开发者自行获取 SDK 后替换占位代码）：
 *   1. 把 com.mi.wear.interconnect-x.y.z.aar 放到 TMessagesProj/libs/
 *   2. build.gradle 中已通过 fileTree(dir: "libs") 包含它
 *   3. 把下方 TODO 标记处替换为 SDK 实际的类与方法
 *
 * 接口猜测依据：
 *   - 小米穿戴第三方 APP 能力开放接口文档 1.4 版
 *   - 与手表端 @system.interconnect 的 API 对称（onmessage / send / getReadyState）
 *
 * SDK 通常会提供：
 *   InterconnectClient.getInstance(context)
 *       .connect()                    -> Connect
 *   Connect.send(jsonString)          -> boolean
 *   Connect.setMessageListener(cb)
 *   Connect.addOnConnectionStateChangeListener(cb)
 *
 * 因 SDK 接口未公开稳定，这里用反射做软依赖：编译期不需要 SDK，运行期类不存在时
 * 自动降级为不连接（便于先打通其他流程）。
 */
package org.telegram.tgwear;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;

public class InterconnectWearConnection implements WearConnection {

    private static final String TAG = "tgwear/Interconnect";

    /**
     * 假设的 SDK 类名。请按实际 SDK 文档修改。
     * 已知部分版本使用 "com.xiaomi.wearable.interconnect.InterconnectClient"。
     */
    private static final String SDK_CLIENT_CLASS = "com.xiaomi.wearable.interconnect.InterconnectClient";
    private static final String SDK_CONNECT_CLASS = "com.xiaomi.wearable.interconnect.Connect";

    @NonNull
    private final Context context;

    @Nullable
    private Listener listener;

    /** 反射持有的 SDK 对象 */
    @Nullable
    private Object connectInstance;

    private volatile boolean ready = false;

    public InterconnectWearConnection(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void init() {
        try {
            Class<?> clientCls = Class.forName(SDK_CLIENT_CLASS);
            Class<?> connectCls = Class.forName(SDK_CONNECT_CLASS);

            // InterconnectClient.getInstance(context)
            Method getInstance = clientCls.getMethod("getInstance", Context.class);
            Object client = getInstance.invoke(null, context);

            // client.connect() -> Connect
            Method connect = clientCls.getMethod("connect");
            connectInstance = connect.invoke(client);

            if (connectInstance == null) {
                throw new IllegalStateException("connect() returned null");
            }

            // 设置消息监听（SDK 可能用 setOnMessageReceivedListener / registerMessageListener 等）
            // 这里尝试常见的接口名，调用失败时记录但不抛出
            registerMessageListener(connectCls);

            // 设置连接状态监听
            registerStateListener(connectCls);

            // 主动查询一次当前状态
            refreshReadyState(connectCls);

            Log.i(TAG, "interconnect SDK 初始化成功");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "SDK 类未找到，请确认 aar 已放入 libs/ 并已 sync。当前将无法与手表通信", e);
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
        }
    }

    /** 反射尝试调用多种可能的"设置消息监听器"方法名 */
    private void registerMessageListener(Class<?> connectCls) {
        String[] candidates = {
            "setOnMessageReceivedListener",
            "setMessageListener",
            "registerMessageListener",
            "setOnMessageListener"
        };
        for (String name : candidates) {
            try {
                Method m = connectCls.getMethod(name, Object.class); // 用 Object 占位
                // 真实接入时应该传 SDK 自己定义的 Listener 接口实例
                // 这里创建一个动态代理，转调 onMessage
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    connectCls.getClassLoader(),
                    new Class[]{ /* SDK Listener 接口 */ },
                    (proxyObj, method, args) -> {
                        if (method.getName().startsWith("on") && args != null && args.length > 0) {
                            // 假设第一个参数是 String / JSONObject
                            Object msg = args[0];
                            if (msg != null && listener != null) {
                                listener.onMessage(msg.toString());
                            }
                        }
                        return null;
                    }
                );
                // 因为我们不知道 SDK 接口类型，这里只是占位；实际接入时替换为具体实现
                // 调用 m.invoke(connectInstance, proxy) 会因类型不匹配失败，所以这里只做日志
                Log.d(TAG, "尝试注册消息监听器: " + name + "（需替换为具体 SDK 接口实现）");
                return;
            } catch (NoSuchMethodException ignored) {
                // 尝试下一个候选名
            }
        }
        Log.w(TAG, "找不到消息监听器设置方法，手表端消息将无法接收");
    }

    /** 反射尝试注册状态变化监听 */
    private void registerStateListener(Class<?> connectCls) {
        try {
            // 常见方法名 addOnConnectionStateChangeListener / setOnStateChangeListener
            Log.d(TAG, "注册状态监听器（需替换为具体 SDK 接口实现）");
        } catch (Throwable t) {
            Log.w(TAG, "registerStateListener failed", t);
        }
    }

    /** 反射调用 getReadyState() 之类的方法 */
    private void refreshReadyState(Class<?> connectCls) {
        String[] candidates = { "getReadyState", "isConnected", "isReady" };
        for (String name : candidates) {
            try {
                Method m = connectCls.getMethod(name);
                Object r = m.invoke(connectInstance);
                if (r instanceof Boolean) {
                    ready = (Boolean) r;
                    return;
                } else if (r instanceof Number) {
                    // 假设 1 = ready, 2 = disconnected
                    ready = ((Number) r).intValue() == 1;
                    return;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, name + " invoke failed", t);
                return;
            }
        }
    }

    @Override
    public boolean send(@NonNull String data) {
        if (connectInstance == null) return false;
        try {
            Class<?> cls = connectInstance.getClass();
            // 常见方法名 send / sendData
            for (String name : new String[]{"send", "sendData"}) {
                try {
                    Method m = cls.getMethod(name, String.class);
                    Object r = m.invoke(connectInstance, data);
                    return !(r instanceof Boolean) || (Boolean) r;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "send failed", t);
        }
        return false;
    }

    @Override
    public void setMessageListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void destroy() {
        // SDK 可能提供 disconnect() / release()
        if (connectInstance != null) {
            for (String name : new String[]{"disconnect", "release", "close"}) {
                try {
                    Method m = connectInstance.getClass().getMethod(name);
                    m.invoke(connectInstance);
                    break;
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    Log.w(TAG, name + " failed", t);
                }
            }
        }
        connectInstance = null;
        listener = null;
        ready = false;
    }
}
