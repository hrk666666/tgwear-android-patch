/*
 * tgwear: 本地 stub 实现，不依赖任何 SDK
 *
 * 用途：
 *   - 在没有真实手表硬件时，可以通过 adb shell 或单元测试脚本注入 JSON 到
 *     StubWearConnection.injectMessage() 模拟手表请求
 *   - 通过 {@link #setOutgoingPrinter} 把发出的数据打印到 logcat 便于调试
 *
 * 生产环境必须替换为 InterconnectWearConnection。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class StubWearConnection implements WearConnection {

    private static final String TAG = "tgwear/Stub";

    @Nullable
    private Listener listener;

    /** 用于把发出的数据打印到某个地方（默认 logcat） */
    public interface OutgoingPrinter {
        void print(@NonNull String data);
    }

    @Nullable
    private OutgoingPrinter printer = data -> Log.i(TAG, "stub → watch: " + data);

    private volatile boolean ready = true; // stub 默认就绪

    @Override
    public void init() {
        Log.i(TAG, "init: stub connection ready");
        if (listener != null) listener.onReadyStateChanged(true, null);
    }

    @Override
    public boolean send(@NonNull String data) {
        if (printer != null) printer.print(data);
        return true;
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
        ready = false;
        listener = null;
        printer = null;
    }

    /* === 测试辅助方法 === */

    /** 模拟手表端发来一帧 JSON */
    public void injectMessage(@NonNull String json) {
        if (listener != null) listener.onMessage(json);
    }

    /** 模拟连接状态变化 */
    public void setReady(boolean r) {
        ready = r;
        if (listener != null) listener.onReadyStateChanged(r, null);
    }

    public void setOutgoingPrinter(@Nullable OutgoingPrinter p) {
        this.printer = p;
    }
}
