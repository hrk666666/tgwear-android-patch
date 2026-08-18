/*
 * tgwear: 与手表端通信的连接抽象层
 *
 * 把"如何与手表端收发数据"这一行为抽象成接口，让具体实现可替换：
 *   - InterconnectWearConnection: 用小米穿戴 interconnect SDK 实现（生产用）
 *   - StubWearConnection:         用本地回调模拟（开发/单测用）
 *
 * 这样设计的原因：
 *   1. 小米 interconnect SDK 的具体类名/包名随版本变化，且未公开 maven 坐标，
 *      通过接口隔离可以让桥接主流程先编译通过，SDK 接入只动 InterconnectWearConnection 一个文件。
 *   2. 测试时不依赖真实手表硬件。
 */
package org.telegram.tgwear;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 表示"与手表端的一条双向通道"。所有 RPC 响应与事件推送都通过这条通道发出。
 */
public interface WearConnection {

    /**
     * 初始化连接。SDK 通常会自动建立蓝牙通道，这里仅注册回调。
     * 必须在主线程调用，连接就绪后会回调 {@link Listener#onReadyStateChanged}。
     */
    void init();

    /**
     * 向手表端发送一帧 JSON 数据。data 是已序列化好的 JSON 字符串。
     *
     * @return true 表示已成功投递到 SDK；false 表示当前未连接，调用方应缓存重试。
     */
    boolean send(@NonNull String data);

    /**
     * 设置消息监听器。可被多次调用，最后一次生效。
     * 监听器收到的 message 是手表端发送的 JSON 字符串。
     */
    void setMessageListener(@Nullable Listener listener);

    /** 是否处于已连接状态 */
    boolean isReady();

    /** 释放资源（Service 销毁时调用） */
    void destroy();

    /** 监听器接口 */
    interface Listener {
        /**
         * @param ready   true 表示连接已建立，可以发送数据
         * @param info    附带信息（如是否为重连），可为 null
         */
        void onReadyStateChanged(boolean ready, @Nullable Object info);

        /** 收到手表端发来的 JSON 字符串 */
        void onMessage(@NonNull String message);
    }
}
