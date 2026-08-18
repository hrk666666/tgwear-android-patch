# 统一签名

本目录包含两端共用的签名材料，是开源仓库的必要组成部分（让他人能复现编译并保持两端签名一致）。

| 文件 | 用途 |
|---|---|
| `tgwear.jks` | Android 端 APK 签名（keytool 标准 keystore） |
| `tgwear.p12` | 中间格式，jks 转 pkcs12 |
| `tgwear.pem` | 包含私钥+证书的明文 pem |

## 签名信息

```
Alias:         tgwear
Store password: tgwear2026
Key password:  tgwear2026
DN:           CN=TG Wear, OU=Open Source, O=hrk, L=Local, ST=Local, C=CN
Algorithm:    RSA 2048
Validity:     100 年
```

## 与手表端快应用对齐

手表端快应用 `tgwear-quickapp/sign/{debug,release}/` 下的 `private.pem` 和 `certificate.pem`，即从 `tgwear.pem` 拆出的两段（BEGIN PRIVATE KEY / BEGIN CERTIFICATE）。

两端签名一致是小米穿戴 `interconnect` 通信的前提：路由消息按包名+签名匹配。

## 如何重新生成

如需用你自己的密钥替换：

```bash
keytool -genkeypair -v -keystore tgwear.jks -alias tgwear \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass <密码> -keypass <密码> \
    -dname "CN=TG Wear, OU=Open Source, O=hrk, L=Local, ST=Local, C=CN"

keytool -importkeystore -srckeystore tgwear.jks -destkeystore tgwear.p12 \
    -srcstoretype jks -deststoretype pkcs12 \
    -srcstorepass <密码> -deststorepass <密码> -srcalias tgwear -destalias tgwear

openssl pkcs12 -nodes -in tgwear.p12 -passin pass:<密码> -out tgwear.pem
```

然后从 `tgwear.pem` 拆出：
- `-----BEGIN PRIVATE KEY-----` 到 `-----END PRIVATE KEY-----` → `private.pem`
- `-----BEGIN CERTIFICATE-----` 到 `-----END CERTIFICATE-----` → `certificate.pem`

最后把这两个文件复制到 `tgwear-quickapp/sign/{debug,release}/`。
