# tgwear-android-patch · 手机端桥接

[![License: GPL v2](https://img.shields.io/badge/License-GPL_v2-blue.svg)](./LICENSE)
[![Source: DrKLO/Telegram](https://img.shields.io/badge/Source-DrKLO%2FTelegram-3DDC84.svg)](https://github.com/DrKLO/Telegram)
[![Companion: Quick-app](https://img.shields.io/badge/Companion-Vela%20Quick--app-ff6900.svg)](https://github.com/hrk666666/tgwear-quickapp)

把开源 Telegram for Android（[DrKLO/Telegram](https://github.com/DrKLO/Telegram)）改造成 Vela 穿戴快应用 TG Wear 的手机端配套 App。

> 配套快应用端：[hrk666666/tgwear-quickapp](https://github.com/hrk666666/tgwear-quickapp)（运行在小米手环/手表上的 Telegram 客户端）。

## 设计原则

1. **完全基于开源 Telegram 源码**，不引入第三方 Telegram 客户端库（如 TDLib），保留官方 MTProto 实现
2. **零侵入式改造**：所有新增代码放在 `org.telegram.tgwear` 子包下，不修改任何 `org.telegram.messenger`/`org.telegram.ui` 既有类的逻辑
3. **通过 5 个补丁 + 一组新增 Java 文件**完成接入，所有改动可一键 `git revert`
4. **包名统一为 `com.hrk.tgwear`**（与手表端快应用 `manifest.json` 中的 `package` 完全一致，interconnect 路由要求）
5. **统一签名**：内置 keystore，与快应用端 pem 同源

## 目录结构

```
tgwear-android-patch/
├── README.md                  本文件
├── apply_patches.sh           一键应用补丁脚本
├── .gitignore
├── patches/                   对 DrKLO/Telegram 源码的修改补丁
│   ├── 0001-application-id.patch        applicationId 改为 com.hrk.tgwear
│   ├── 0002-build-gradle-deps.patch     加入 interconnect SDK 依赖
│   ├── 0003-androidmanifest-add-service.patch  注册 WearBridgeService 等
│   ├── 0004-applicationloader-init-bridge.patch  App 启动时拉起 Service
│   └── 0005-signing-config.patch        配置 signingConfigs（与手表端签名一致）
├── signing/                  统一签名材料（两端共用，已开源以便复现）
│   ├── tgwear.jks            Android keystore
│   ├── tgwear.p12            pkcs12 中间格式
│   ├── tgwear.pem            私钥+证书 pem（手表端 .pem 即从此拆出）
│   └── README.md            签名说明
└── src/org/telegram/tgwear/  新增源码（不修改任何现有 Telegram 文件）
    ├── WearConstants.java     RPC 协议常量
    ├── WearConnection.java    连接抽象接口
    ├── InterconnectWearConnection.java  小米 interconnect SDK 实现
    ├── StubWearConnection.java          本地 stub 实现（开发/测试用）
    ├── WearBridgeService.java 前台 Service（生命周期管理）
    ├── BridgeRouter.java      JSON-RPC 路由器
    ├── TdJsonConverter.java   TLRPC 对象 ↔ JSON 互转
    ├── NotificationCenterBridge.java  订阅 Telegram 推送 → 转事件
    ├── BootReceiver.java      开机自启
    └── handlers/              各 RPC 方法实现
        ├── AuthHandler.java        auth.getState / auth.logout
        ├── DialogsHandler.java     dialogs.get
        └── MessagesHandler.java   messages.getHistory / sendText / markRead
```

## 快速开始

### 前置条件

- JDK 17
- Android SDK 34（compileSdk）
- NDK（Telegram 原生库编译需要，若用预编译版本可略过）
- Git
- 小米穿戴 interconnect SDK 的 aar 文件（命名建议：`com.mi.wear.interconnect-1.4.aar`，需自行从小米穿戴第三方 APP 接口文档 1.4 中获取）

### 步骤

```bash
# 1. 克隆 Telegram 源码并切到稳定 tag
git clone https://github.com/DrKLO/Telegram.git
cd Telegram
git checkout release-11.4.2-5469

# 2. 应用 tgwear 补丁
bash /path/to/tgwear-android-patch/apply_patches.sh
#    会自动：应用 5 个 patch + 复制源码 + 复制 signing/ 目录

# 3. 放入小米 interconnect SDK
cp ~/Downloads/com.mi.wear.interconnect-1.4.aar TMessagesProj/libs/

# 4. 签名已通过 0005-signing-config.patch 配置好（与手表端共用同一 keystore）
#    仓库内附 tgwear-android-patch/signing/tgwear.jks，密码 tgwear2026

# 5. 编译
cd TMessagesProj
./gradlew :TMessagesProj:assembleRelease
# 产物：TMessagesProj/build/outputs/apk/release/TMessagesProj-release.apk
```

### 步骤（详细版）

#### 1. 应用补丁

补丁以 `git apply` 方式应用，工作区干净时一次成功：

```bash
cd Telegram
bash /path/to/tgwear-android-patch/apply_patches.sh
```

应用后会看到：
- `TMessagesProj/build.gradle`：`applicationId` 已改为 `com.hrk.tgwear`，加入 `libs/` fileTree
- `TMessagesProj/src/main/AndroidManifest.xml`：新增 `WearBridgeService` / `BootReceiver` 及权限
- `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`：`onCreate()` 末尾启动 Service
- `TMessagesProj/src/main/java/org/telegram/tgwear/`：所有新增源码已复制到位

#### 2. 接入小米 interconnect SDK

把 SDK aar 放入 `TMessagesProj/libs/`，名称不限（`.aar`/`.jar` 都会被 fileTree 包含）。

`InterconnectWearConnection.java` 中预留了反射接入点，类名常量在文件顶部：

```java
private static final String SDK_CLIENT_CLASS = "com.xiaomi.wearable.interconnect.InterconnectClient";
private static final String SDK_CONNECT_CLASS = "com.xiaomi.wearable.interconnect.Connect";
```

**请按实际 SDK 文档调整**：把 `registerMessageListener()` / `registerStateListener()` / `send()` 三个方法中的反射调用替换为直接调用。如果你不调整，编译能通过（因为是反射），但运行时只会打印 log，无法实际通信。

完成接入后建议：
- 把反射代码替换为直接调用，性能更好
- 删除 `registerMessageListener()` 中的 Proxy 占位代码

#### 3. 签名对齐（已内置）

本仓库已经内置统一签名，两端共用：

- Android 端：`tgwear-android-patch/signing/tgwear.jks`，通过 `0005-signing-config.patch` 自动注入 `signingConfigs`
- 手表端：`tgwear-quickapp/sign/{debug,release}/{private.pem,certificate.pem}`，从同一 keystore 拆出

如果你想生成自己的签名（推荐用于生产环境），重新执行：

```bash
# 1. 生成 keystore
keytool -genkeypair -v -keystore tgwear.jks -alias tgwear -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass <你的密码> -keypass <你的密码> \
    -dname "CN=TG Wear, OU=Open Source, O=hrk, L=Local, ST=Local, C=CN"

# 2. 转 p12
keytool -importkeystore -srckeystore tgwear.jks -destkeystore tgwear.p12 \
    -srcstoretype jks -deststoretype pkcs12 \
    -srcstorepass <密码> -deststorepass <密码> -srcalias tgwear -destalias tgwear

# 3. 导出 pem
openssl pkcs12 -nodes -in tgwear.p12 -passin pass:<密码> -out tgwear.pem

# 4. 从 tgwear.pem 拆出私钥和证书：
#    - BEGIN PRIVATE KEY ... END PRIVATE KEY → private.pem
#    - BEGIN CERTIFICATE ... END CERTIFICATE → certificate.pem
# 5. 复制到两端：
#    - tgwear-android-patch/signing/tgwear.{jks,p12,pem}
#    - tgwear-quickapp/sign/{debug,release}/{private,certificate}.pem
```

#### 4. 编译并安装

```bash
cd TMessagesProj
./gradlew :TMessagesProj:assembleRelease
adb install -r build/outputs/apk/release/TMessagesProj-release.apk
```

首次启动会进入 Telegram 官方登录流程（`IntroActivity` → `LaunchActivity`），登录后 `ApplicationLoader.onCreate()` 自动拉起 `WearBridgeService`，interconnect 通道上线。

## RPC 协议

与手表端 [tgwear-quickapp/src/utils/api.js](../tgwear-quickapp/src/utils/api.js) 严格对齐，定义见 [plan-quickapp-tg.md 第四节](../.trae/documents/plan-quickapp-tg.md)。

| 方法 | Android 实现类 | 调用的 Telegram API |
|---|---|---|
| `auth.getState` | `AuthHandler` | `UserConfig.getClientUserId()` / `isClientActivated()` |
| `auth.logout` | `AuthHandler.LogoutHandler` | `MessagesController.performLogout(0)` |
| `dialogs.get` | `DialogsHandler.GetDialogsHandler` | `MessagesController.getAllDialogs()` |
| `messages.getHistory` | `MessagesHandler.GetHistoryHandler` | `MessagesController.getDialogMessages()` + `ConnectionsManager.sendRequest(TL_messages_getHistory)` |
| `messages.sendText` | `MessagesHandler.SendTextHandler` | `SendMessagesHelper.sendMessage(...)` |
| `messages.sendSticker` | `MessagesHandler.SendStickerHandler` | 暂未实现，返回错误 |
| `messages.markRead` | `MessagesHandler.MarkReadHandler` | `MessagesController.markDialogAsRead(...)` |

事件推送：
- `update.newMessage`：订阅 `NotificationCenter.didReceivedNewMessages` 转发
- `update.connectionState`：interconnect 连接状态变化时推送

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│ Xiaomi Vela 穿戴设备                                          │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ tgwear-quickapp（快应用端）                            │  │
│  │  - splash / chats / chat / setting 4 个页面             │  │
│  │  - InputMethod 自定义键盘                              │  │
│  │  - api.js JSON-RPC 客户端                              │  │
│  └─────────────┬──────────────────────────────────────────┘  │
│                │ @system.interconnect                          │
└────────────────┼─────────────────────────────────────────────┘
                 │ JSON-RPC over Bluetooth
┌────────────────┼─────────────────────────────────────────────┐
│ Android Phone  ▼                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ tgwear-android-patch（本仓库，基于 DrKLO/Telegram）    │  │
│  │  - WearBridgeService  前台 Service                     │  │
│  │  - BridgeRouter       JSON-RPC 路由                    │  │
│  │  - AuthHandler        auth.*                           │  │
│  │  - DialogsHandler     dialogs.get                      │  │
│  │  - MessagesHandler    messages.getHistory / sendText   │  │
│  │  - NotificationCenterBridge 推送事件                  │  │
│  │  - MessagesController / SendMessagesHelper（原生）    │  │
│  └────────────────────────────────────────────────────────┘  │
│                 │                                              │
│                 ▼ MTProto over TCP                             │
│        ┌─────────────────────┐                                 │
│        │ Telegram Datacenter │                                 │
│        └─────────────────────┘                                 │
└──────────────────────────────────────────────────────────────┘
```

## 贡献

欢迎 Issue / PR。

### 贡献流程

1. Fork 本仓库
2. 创建分支：`git checkout -b feature/your-feature`
3. 提交：`git commit -m "feat: add your feature"`（推荐 [Conventional Commits](https://www.conventionalcommits.org/) 规范）
4. 推送：`git push origin feature/your-feature`
5. 发起 Pull Request 到 `main` 分支

### 协议同步约定

修改 RPC 协议时必须同步修改两端：

| 修改位置 | 文件 |
|---|---|
| 手机端方法名 | `src/org/telegram/tgwear/WearConstants.java` |
| 手表端方法名 | `tgwear-quickapp/src/utils/api.js` |
| 协议文档 | `.trae/documents/plan-quickapp-tg.md` 第四节 |

### 代码规范

- Java：遵循 [Google Java Style](https://google.github.io/styleguide/javaguide.html)
- 补丁：每个补丁聚焦一个改动点，commit message 用英文
- 新增源码：放在 `org.telegram.tgwear` 子包下，不修改 `org.telegram.messenger` 既有类

## Roadmap

- [ ] **v0.2.0**：贴纸发送支持（`messages.sendSticker`）
- [ ] **v0.3.0**：媒体消息预览（缩略图）
- [ ] **v0.4.0**：大消息分片（>64KB）
- [ ] **v0.5.0**：群组信息查看
- [ ] **v1.0.0**：完整功能对齐 Telegram Lite

## 调试

### Stub 模式

没有手表硬件时，把 `WearBridgeService.createConnection()` 改为：

```java
private WearConnection createConnection() {
    return new StubWearConnection();  // 强制走 stub
}
```

然后用 adb 注入测试数据：

```bash
# 启动 App，看到 logcat: "tgwear/Stub: stub connection ready"
# 然后通过广播或测试代码注入 JSON：
adb shell am broadcast -a com.hrk.tgwear.TEST_INJECT \
    --es msg '{"__rpc":true,"id":1,"method":"auth.getState","params":{}}'

# 查看 logcat: "tgwear/Stub → watch: {"id":1,"result":{...}}"
adb logcat -s tgwear/Stub tgwear/Router tgwear/Service
```

### 真机调试技巧

1. **手表端排查连接问题**：在 splash 页看错误码
   - `1001`：手机端 App 未安装，或包名/签名不一致
   - `204`：连接超时，确认手机端 App 已打开并解锁屏幕
   - `1006`：连接断开，可能是蓝牙距离过远

2. **手机端排查桥接问题**：logcat 过滤 `tgwear/`
   ```
   adb logcat -s tgwear/Service tgwear/Router tgwear/Interconnect tgwear/NotifyBridge
   ```

3. **RPC 调用日志**：每次请求/响应都会打印到 logcat

## 已知限制

1. **SDK 接入需要手工调整**：因小米 interconnect SDK 未公开 maven 坐标，`InterconnectWearConnection.java` 用反射做软依赖，需手动改为直接调用
2. **Telegram API 签名可能随版本变化**：补丁针对 `release-11.4.2-5469`，其他版本需要检查 `SendMessagesHelper.sendMessage()` / `MessagesController.markDialogAsRead()` 的参数列表
3. **不处理大消息分片**：interconnect 单帧限制约 64KB，超长消息需要分片（MVP 阶段未实现，限制消息历史 ≤30 条/页）
4. **不支持媒体消息**：sendSticker 当前返回错误，未来扩展时实现贴纸发送

## License

[GPL v2](./LICENSE) © 2026 hrk666666

继承自 DrKLO/Telegram 的 GPL v2 协议，修改后的代码必须开源。

## 致谢

- [DrKLO/Telegram](https://github.com/DrKLO/Telegram)：Telegram for Android 开源实现
- [小米穿戴第三方 APP 能力开放接口文档 1.4](https://dev.mi.com/)：interconnect 通信能力
- [vela-watch-design](https://www.bandbbs.cn/resources/7086/)：设计规范与 InputMethod 输入法组件（仅用于手表端）
- [vela-quickapp-dev](https://www.bandbbs.cn/resources/6173/)：快应用 API 文档与开发指南（仅用于手表端）
