# Contributing

Thanks for your interest in improving DiPlay (Geely adaptation). This repository is a Geely-specific fork of [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay); see the disclaimer in the [README](README.md).

简体中文摘要：本仓库是 DiPlay 的吉利车机适配 fork。比亚迪核心功能请向上游提 issue/PR；这里欢迎吉利车型预置、车桥适配、翻译与文档贡献。提交前跑 AGENTS.md 里的 CI 检查命令；签名文件与配件身份文件永远不要提交。

## Scope

| Change type | Where it belongs |
| --- | --- |
| BYD core CarPlay, connection, audio, display | [Upstream repo](https://github.com/shihabal3amri/DiPlay/issues) — this fork keeps upstream changes minimal |
| Geely/ECARX vehicle data, wheel keys, HUD projection, audio routing | This fork |
| Translations, documentation, site | This fork (translations also appreciated upstream) |

## Vehicle adaptation workflow (Geely models)

1. Install the latest release of both APKs on the car and save your model preset in **Vehicle data**.
2. Run the property scan and attach the report to your issue/PR. Sentinel values (`255`, `65535`, `1.401298464324817E-45`) mean "unsupported"; ignore those rows.
3. To add a model preset: add `vehicle-bridge/src/main/res/raw/<model>_properties.json` (schema 1, same format as `xingyue_l_kx11_properties.json`) and register it in `Profiles.kt` (`Presets`).
4. Verify on the real car: values appear in **Vehicle data** and on HUD cards; include a photo or diagnostic report.

## Development

- Build instructions: [docs/BUILD.md](docs/BUILD.md). Release signing is supplied via environment variables (`ANDROID_KEYSTORE_*`); the CarPlay accessory identity (`offline-mfi/`) stays local.
- Before opening a PR, run the CI command from [AGENTS.md](AGENTS.md):

  ```sh
  ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
    :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
    :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
  ```

- Coding-agent conventions live in [AGENTS.md](AGENTS.md); testing notes in [docs/TESTING.md](docs/TESTING.md).

## Never commit

- Keystores and keys: `*.jks`, `*.keystore`, `*.pk8`, `*.p7b`, `release-keys/`
- `local.properties`, `*.apk` build outputs
- Personal logs with hotspot passwords or account data

CI runs `scripts/check_public_tree.py` and will fail the build if credential containers enter the tree.

## Reporting issues

Use the issue templates. Connection problems require a diagnostic report; Geely vehicle-data and HUD problems should include the scan report and the saved preset name. Test while parked and remove personal information from attached logs.
