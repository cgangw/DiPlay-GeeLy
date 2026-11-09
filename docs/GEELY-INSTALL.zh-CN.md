# 吉利适配版安装指南（简体中文）

本指南针对本仓库的吉利（Geely）适配：**DiPlay 主应用**（`com.shihab.diplay`）+ **GD 车桥**（`com.geely.desktop.vehicle.bridge`）。通用安装与连接说明见 [INSTALL.md](INSTALL.md)；本文只补充吉利车型相关步骤。

> **已实车验证的车型**：吉利星越 L（KX11，2025 款揽星，车机 `QTI KX11_A2`，Android 11）。
> 其他吉利车型可以尝试，但预置表、按键映射均未验证，欢迎按[贡献指南](../CONTRIBUTING.md)回报扫描报告。

## 1. 下载

从 [Releases](https://github.com/tianwenbro/DiPlay-GeeLy/releases) 下载最新 tag 的两个 APK：

| 文件 | 说明 |
| --- | --- |
| `Diplay-Geely-25-XingYue-L-v<版本>.apk` | CarPlay 主应用，装在车机上（不是 iPhone） |
| `Geely-25-XingYue-L-Vehicle-Bridge-v<版本>.apk` | 车桥，读取车况数据、转发方向盘按键 |

两个 APK 使用同一签名证书，可原地覆盖升级，数据不会丢失。两者版本号统一由 `gradle.properties` 的 `GEELY_VERSION_NAME` 控制。

## 2. 安装

1. 将两个 APK 拷入车机（U 盘或 `adb install` 均可），建议先装车桥、再装 DiPlay。
2. 车机需允许安装未知来源应用。
3. DiPlay 支持 Android 7.0+；吉利 KX11 车机为 Android 11，全部功能可用。

## 3. 配置车况数据（车辆数据页）

1. 打开 DiPlay → **设置 → 关于**，连点版本号 7 次解锁开发者选项。
2. 回到 **车辆数据** 页：
   - 选择预置 **「星越 L · KX11（25 揽星）」** 并**保存**。
   - 点 **「车桥连接状态」**：`car_function`、`sensor_manager` 应为 `READY`。
3. 保存后，车速、续航、车外/车内温度即可在车辆数据页读到。

**KX11 当前可读字段**（sensor 通道）：车速（km/h，原始值 m/s 已自动换算）、续航、车外温度、车内温度。
**不可读字段**：转速、远近光/转向灯、挡位、门窗、胎压——本车机固件不向普通应用暴露这些数据（扫描时返回哨兵值）。

## 4. HUD 布局

设置 → **HUD 布局编辑** 可添加/拖动/缩放卡片：

- **导航卡**：转向箭头、距离、道路名、剩余里程、到达时间（iPhone 导航时显示）。
- **数值卡**：车速、续航、车外温度、车内温度。数字大字号 + 单位小字号（50%），无标签时可凭数值单位区分。
- **时钟卡**。

保存后立即生效；布局独立于主屏显示。

## 5. 方向盘按键

车桥接管 OneOS 方控：播放、暂停、上一曲、下一曲已在 KX11 实车验证。按键学习与拦截状态在 **车辆数据 → 方控** 页查看。

## 6. 排障

| 现象 | 处理 |
| --- | --- |
| 数值卡显示 `—` | 确认已保存预置；点「车桥连接状态」看通道是否 `READY`；覆盖安装的 DiPlay 与车桥版本是否配套 |
| 扫描报告大量行是 `255` 或 `1.401298464324817E-45` | 这是厂商 API 的"不支持"哨兵值，属正常；只有非哨兵行才是真实数据 |
| 无线连接卡在"正在打开 CarPlay" | 见 [CONNECTION_SETUP.md](CONNECTION_SETUP.md) 与 [WIRELESS_DIAGNOSTICS.md](WIRELESS_DIAGNOSTICS.md) |
| 其它问题 | 保存诊断报告（Android 10+ 存到 `Downloads/DiPlay`），按 issue 模板提报 |

## 7. 隐私与安全

- 车况数据仅在本机读取与显示，不上传任何服务器，见 [PRIVACY.md](PRIVACY.md)。
- 签名密钥、CarPlay 配件身份文件不在仓库内；请勿把自备的 `*.jks`、`identity.pk8`、`certificate.p7b` 提交到公共仓库。
