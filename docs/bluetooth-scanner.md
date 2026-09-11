# 蓝牙信号扫描

ToolBox 的“蓝牙”页会同时尝试扫描 BLE 广播和经典蓝牙设备，扫描结果只在本机展示，不会配对、连接或上传。

## 展示信息

- 设备名称、地址、用户别名、设备类型和配对状态。
- 最近一次原始 RSSI（dBm）及粗略强弱描述，并按 RSSI 从强到弱排序。
- 蓝牙设备类别、类别服务能力以及系统已知的服务 UUID。
- BLE 广播的 Tx Power、主/辅助 PHY、可连接状态、传统/扩展广播格式、广播 SID、周期广播间隔和数据完整状态。
- 广播 Flags、Solicitation UUID、厂商数据、Service Data 和原始广播字节。
- 首次发现与最近发现时间。

同一个地址被 BLE 和经典蓝牙同时发现时，页面会合并为一项。部分设备会使用定期轮换的随机地址，因此仍可能出现多项。

## 信道限制

蓝牙工作在 2.4 GHz ISM 频段。页面会展示对应的信道体系：BLE 共 40 个 2 MHz 信道（37/38/39 为主广播信道），经典蓝牙使用 79 个 1 MHz 跳频信道。Android 公共扫描 API 不提供接收某个广播或发现响应时的实际射频信道/中心频率，因此页面明确显示“系统未提供”，不会根据 RSSI 或其他字段猜测信道号。

## 权限与系统要求

- Android 12 及以上需要“附近的设备”（`BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`）权限。
- Android 11 及以下需要精确位置权限并开启系统定位。
- 必须开启系统蓝牙；不支持 BLE 的设备仍可尝试经典蓝牙发现。
- 系统可能限制高频扫描，设备也可能因未处于可发现状态而不出现在结果中。

## 验证

本项目在 WSL Codex 中禁止运行 Android/Gradle 构建。请在 Windows Android Studio 或 Windows PowerShell Codex 中执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

建议分别在 Android 11 及以下、Android 12 及以上真机验证权限拒绝、蓝牙关闭、定位关闭、BLE 广播、经典蓝牙发现、停止扫描和离开页面自动停止扫描。
