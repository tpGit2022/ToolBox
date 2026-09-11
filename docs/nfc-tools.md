# NFC 工具

ToolBox 的“NFC”页提供标签读写和 HCE 卡模拟，全部在设备本地完成。

## 读卡与信息展示

进入“读写标签”模式并将标签贴近手机 NFC 天线位置，可查看：

- UID、Android 报告的技术栈及读取时间。
- NfcA 的 ATQA、SAK，NfcB 的 Application Data、Protocol Info。
- NfcF 的 Manufacturer、System Code，NfcV 的 DSF ID、Response Flags。
- IsoDep 的 Historical Bytes、HiLayer Response、最大 APDU 长度和扩展 APDU 能力。
- MIFARE Classic/Ultralight 的类型、容量、扇区、块、收发上限和超时。
- NFC Barcode 类型以及标签是否可格式化为 NDEF。
- NDEF 类型、容量、可写/只读能力、全部记录及原始消息字节。
- Text、URI、Smart Poster、MIME、External Type、Absolute URI 等记录的解析结果，以及每条记录的 TNF、Type、ID 和 Payload 原始值。

页面还展示系统 NFC 控制器状态、HCE/HCE-F、安全 NFC、观察模式、Reader Option、省电模式、Exit Frame、Reader Mode Annotation、标签 Intent 偏好、Gesture Exchange AID 和 NFC 天线位置。较新系统或硬件未提供的字段不会虚构。

## 写入与格式化

可写入以下单条 NDEF 记录：

- 文本。
- URI，包括网页、电话、邮件等 URI。
- 自定义 MIME 类型及 UTF-8 数据。
- NFC Forum External Type 及 UTF-8 数据。

点击操作按钮后，应用进入等待状态；再贴近目标标签才会执行。对于未格式化但实现 `NdefFormatable` 的标签，写入时会自动格式化。也可清空/格式化为空 NDEF。

“永久设为只读”会先显示二次确认。该操作通常不可撤销，具体是否支持由标签芯片和 Android NFC 驱动决定。

## HCE 卡模拟

切换到“卡模拟 HCE”后，应用关闭主动 Reader Mode，让 NFC 控制器进入被动卡模拟状态。本机实现 NFC Forum Type 4 NDEF 应用，AID 为 `D2760000850101`，可向另一台 NFC 设备提供只读文本或 URI。

HCE 只能模拟 ISO-DEP/APDU 层应用，不能指定实体卡 UID，也不能复制门禁卡、银行卡、公交卡或安全元件中的密钥。若设备上有其他应用注册相同 AID，Android 可能显示应用选择界面。

## 系统限制

- Android Beam 和旧的 NFC 点对点推送 API 已废弃并从新系统移除。
- 支付、公交、门禁等凭据通常受密钥、安全元件、厂商服务或系统角色保护，普通应用无法读取或复制。
- MIFARE 等标签的私有存储区需要芯片协议和合法密钥。本工具展示 Android 可安全读取的公开参数，不会自动发送可能改写、锁定或损坏标签的私有命令。
- 不同手机的 NFC 天线位置不同；Android 14 及以上只有在厂商提供数据时才能显示天线坐标。

## 验证

本项目在 WSL Codex 中禁止运行 Android/Gradle 构建。请在 Windows Android Studio 或 Windows PowerShell Codex 中执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

建议用 NfcA/NfcV、可写 NDEF 和未格式化标签分别验证读取、写入、清空和格式化。HCE 需要另一台支持 NFC 读卡的手机验证；“永久设为只读”只应使用可丢弃的测试标签。
