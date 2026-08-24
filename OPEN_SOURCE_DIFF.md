# LoverConnect 2.3.0-rc4 开源纯净版差异说明

生成日期：2026-08-23（Asia/Shanghai）

## 基线与边界

- Android 基线：`C:\Users\xin\LoverConnect-2.3-dev` 当前 rc4 工作树的源码快照。
- 服务端基线：VPS 正在运行并通过 rc4 验收的 `/root/sentinel/loverconnect_ingress.py`，而不是座机里已经落后的同名副本。
- 个人版源码、已安装 APK、手机运行数据和 VPS 在线服务均未修改。
- 本次不处理“出门后一直报在家”这一独立缺陷，也不构建 APK。

## 可审差异

| 文件 | 改动 |
|---|---|
| `LICENSE` | 经上游维护者明确同意，新增标准 MIT License；同时保留 `LoverConnect` 与 `AZHi-xinxin` 的版权声明。 |
| `README.md` | 面向用户的“学校”配置改为“工作”。 |
| `LOCATION-SAFETY.md` | “家 / 学校”“到校”等公开文案改为“家 / 工作”“到工作地点”。 |
| `app/src/main/java/com/lover/connect/LocationSafetySection.kt` | 可见文案、内部围栏 ID 与局部变量由 `school` 统一迁移为 `work`；未改围栏算法。 |
| `app/src/test/java/com/lover/connect/GeofenceStateMachineTest.kt` | 测试夹具、测试名和断言同步迁移到 `work`。 |
| `app/src/test/java/com/lover/connect/LocationEventCompactorTest.kt` | 事件测试中的区域 ID 由 `school` 改为 `work`。 |
| `server/loverconnect_ingress.py` | 先同步已验收的线上 rc4 实现，再把自然语言识别中的“学校”语义改为“工作地点 / 公司”。 |
| `server/test_loverconnect_ingress.py` | 同步线上 rc4 测试，并将学校场景改为工作场景。 |

Android 侧与本机 rc4 工作树相比，以上五个文件只有术语与对应测试夹具的等价迁移。服务端差异较大，是因为本机副本落后于线上 rc4；本包以已经过实际验收的线上实现为准，避免误把旧服务端开源。

## 隐私净化结果

- 源码内没有复制运行时定位记录、events、账号资料、SharedPreferences、数据库或日志。
- 没有 `.env`、私钥、证书、Token、密码或 `local.properties`。
- 唯一保留的凭证文件是 `server/credentials.env.example`，内容均为占位符。
- 已扫描并确认不存在已知姓名、账号、电话、邮箱、家庭/局域网/Tailscale/VPS 地址等个人标记。
- 已扫描并确认 `学校`、`school`（忽略大小写）零残留。
- `.git`、IDE 配置、构建缓存、崩溃日志及个人内部交接文档未进入源码包。

## 验证结果

- 服务端：`python -m py_compile loverconnect_ingress.py` 通过。
- 服务端：19 项 `unittest`，0 failure / 0 error。
- Android：`testDebugUnitTest` 构建成功，29 项测试，0 failure / 0 error / 0 skipped。
- Android 构建只出现既有 API 弃用警告与 SDK XML 版本提示，不影响本次测试结论。

## 版本策略

本源码保留 `versionCode = 7`、`versionName = "2.3.0-rc4"`，用于证明它与已验收个人版处于同一功能基线。公开最终版的版本号与签名由后续构建者在独立发布目录决定，避免在净化阶段擅自改变版本身份。

## 许可证

上游维护者已明确同意采用 MIT License。本包新增根目录 `LICENSE`，并保留上游与增强版维护者的版权声明。第三方依赖和素材仍遵循各自许可证。

## 后续构建者检查项

1. 仅从本纯净源码目录构建，不回读个人版工作树。
2. 在独立发布目录设置正式版本号和公开签名。
3. 构建后复跑 Android 单元测试，并核对 APK 哈希。
4. 将最终 APK 哈希交给文档负责人补入公开 README / Release Notes。
