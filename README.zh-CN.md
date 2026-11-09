# DiPlay

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

为兼容的比亚迪安卓车机提供有线及无线 CarPlay，采用 DiAuto 风格界面。

> **本仓库是 DiPlay 的吉利车机适配版本。**
> 基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) v0.2.22，分支 `tianwen_dev`。它不是上游的比亚迪版本，上游作者不提供支持。
>
> 已在**吉利星越L（KX11，2025 款揽星）**实车验证安装并运行；其他吉利车型、车机及固件版本尚未验证。
>
> 吉利相关新增：ECARX 原厂热点处理、吉利方向盘按键映射与按键识别、HUD／仪表导航投屏及可编辑布局、全屏地图投至仪表、吉利音频路由，以及用于读取原厂车辆属性的 `vehicle-probe` 桥接。
>
> 构建说明：产物为 `Diplay-Geely-25-XingYue-L-v<版本>.apk`（debug 为 `…-hud-test.apk`）和 `Geely-25-XingYue-L-Vehicle-Bridge-v<版本>.apk`，两者版本号统一来自 `gradle.properties` 的 `GEELY_VERSION_NAME`。CarPlay 配件身份文件（`offline-mfi/identity.pk8`、`certificate.p7b`）与 release 签名密钥不在仓库内，需要本地自备。本项目仍非 Apple 认证产品，内置身份为实验性身份。
>
> 同步上游：`git fetch upstream && git merge upstream/main`。
>
> 吉利适配安装指南（简体中文）：[docs/GEELY-INSTALL.zh-CN.md](docs/GEELY-INSTALL.zh-CN.md) · 参与贡献：[CONTRIBUTING.md](CONTRIBUTING.md)
>
> **免责声明。** 本项目是独立的实验性适配版本，与吉利汽车、亿咖通（ECARX）、Apple、比亚迪或任何车企均无隶属、认可或合作关系。所有品牌与产品名称均为其各自所有者的商标，仅作识别用途。软件按“原样”提供，不作任何形式的担保——安装与使用风险自负。逆向得到的车机接口可能随任何固件更新而改变或表现异常；驾驶时切勿依赖本应用显示安全关键信息，请务必停车测试。

> 这些项目专注于比亚迪汽车。它们可能在其他品牌上运行，但其他品牌不在支持范围内，也没有增加支持或修复其品牌特定兼容性问题的计划。本分支的吉利适配见上方说明。

[下载与中文网站](https://shihabal3amri.github.io/DiPlay/zh-Hans/) · [0.2.22 版本](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.22) · [完整说明](README.md) · [报告问题](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

## 0.2.22 — 公开预览版

请安装在允许 APK 安装的 Android 7.0+（API 24+）车机上；Android 7.0–8.1 上的有线 CarPlay 已在一台实车上确认，无线连接尚未经实车验证。无需越狱、转接盒、账户或认证服务器。有线及无线核心连接不要求 ADB；可选车辆数据及车辆控制需要支持的固件和已授权网络 ADB。

Wi-Fi Direct 现支持 Android 7.0–9 的旧版建组路径，使用系统返回的真实凭据。首选信道依赖固件 API；Android 7.0–9 无法读回协商频率，所以请求信道在诊断中标为未经验证，系统默认为信道 0。Android 10+ 保留频率验证。也可使用车机内置热点、USB 或[现有 Wi-Fi／同一局域网](docs/EXISTING_WIFI.md)；同一局域网模式由车机和 iPhone 自行连接外部路由器，DiPlay 不替你修改默认路由。[Android 9 Wi-Fi Direct 限制](docs/ANDROID9_WIFI_DIRECT.md)说明清理及持久配置边界。

### 新增与修正

- **车机热点**：修正 0.2.21 在比亚迪 DiLink 3.0／4.0 等车机上通过车机热点无线连接时一直停在“正在打开 CarPlay”的问题；之前填写的“热点网络接口（可选）”可以清空。
- **音乐**：通过 Wi-Fi 播放音乐时恢复 iPhone 的原始速度，每首歌从第一秒起就不再先快 1.5% 再卡顿。
- **麦克风**：USB 连接时，微信语音、语音备忘录和听写能录到声音，不再是静音。
- **连接**：插着线重新连接时，有线 CarPlay 第一次就能连上；连接失败时，CarPlay 画面和主页会说明原因以及应该怎么改。
- **语言**：新增韩语和土耳其语。
- **新选项（默认关闭）**：在桌面卡片中保留 CarPlay、导航提示音请求音频焦点、按通话本身的采样率通话、DiLink 3.0 全屏仪表地图，以及腾势 N9 的仪表地图。

[0.2.22 完整说明](docs/RELEASE-NOTES-0.2.22.md)包含贡献链接及功能限制；构建和验证信息见[验证记录](docs/VALIDATION.md)。Android 7.0–8.1 尚需实车验证，不宣称所有车型的连接、音频或 Siri 问题均已解决。可选功能请停车后测试。

### 请提供 0.2.22 的新诊断报告

1. 更新到 **0.2.22**，复现问题并记录发生时间。开机／自动启动问题发生后，可手动打开 DiPlay 导出。
2. 打开“**设置 → 诊断 → 保存诊断报告**”。Android 10+ 通常保存到 **Downloads/DiPlay**；Android 7.0–9 会请求存储权限并同样保存到该位置，也可点“选择保存位置”。如选择器或公共存储不可用，应用会使用专用外部或私有目录，并在确认中说明目的地。
3. 使用确认中的**查看报告／分享**；没有分享应用时，可在报告视图中选择并复制文本。检查 `.txt` 并删除隐私信息，再附到匹配的[现有问题](https://github.com/shihabal3amri/DiPlay/issues)，或[新建问题](https://github.com/shihabal3amri/DiPlay/issues/new/choose)。报告不会自动上传，请勿公开热点密码或私有认证文件。
4. 注明车型／车机、DiLink/Android/完整固件版本、iPhone/iOS、USB／车机热点／Wi-Fi Direct／同一局域网、相关设置、复现步骤、预期与实际结果及故障时间。

[从源码构建](docs/BUILD.md)：主应用请选择 `mobile` 模块。`maphost` 是地图演示应用，构建步骤和 APK 路径见说明。

历史记录：[0.2.17](docs/RELEASE-NOTES-0.2.17.md)、[0.2.16](docs/RELEASE-NOTES-0.2.16.md)、[0.2.15](docs/RELEASE-NOTES-0.2.15.md)、[0.2.14](docs/RELEASE-NOTES-0.2.14.md)、[0.2.13](docs/RELEASE-NOTES-0.2.13.md)、[0.2.12](docs/RELEASE-NOTES-0.2.12.md)、[0.2.11](docs/RELEASE-NOTES-0.2.11.md)、[安装与连接](docs/INSTALL.md)。

这是公开预览版，**未经 Apple 认证**。APK 使用从公开 Carlinkit 固件中提取的既有实验性配件身份，并非为 DiPlay 新签发的 MFi 身份；其中的私钥可被提取，未来 iOS 是否继续接受及其公开分发适用性尚未确定。Android 签名密钥和配件身份不进入 Git 或源代码压缩包；普通源代码/CI 构建默认不配置身份。部分车机仍可能卡顿或无法应用图标大小设置。

标准导航小组件需要支持 Android 小组件的启动器；比亚迪内置主页不接受任意小组件。悬浮地图和嵌入地图需要启用“CarPlay 仪表地图”。应用和网站都包含十一种语言：英语、简体中文、繁体中文（台湾）、阿拉伯语、俄语、乌克兰语、西班牙语、日语、意大利语、韩语和土耳其语。应用的香港／澳门及 Hant 选择使用台湾译文，不宣称提供独立地区翻译。[繁体中文说明](README.zh-TW.md)提供安装、设置、隐私和构建指南。源代码、构建说明及许可证随版本提供。
