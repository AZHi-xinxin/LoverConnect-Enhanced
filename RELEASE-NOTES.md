# LoverConnect Release Notes

## 2.3.0-rc5（2026-08-26）· Vivo 稳定兼容版

基于已实机验证的 Vivo 无障碍诊断源码制作的正式兼容版。**诊断测试结果**（vivo 实机）：无障碍开关不再自动关闭、小L截屏与分析正常、手机可达、状态栏主动推送正常、无误报。

### 本版变化

- 恢复正式应用名 `LoverConnect`（诊断版临时名 `LoverConnect A/B` 已移除）。
- 版本号递增：versionCode 7 → **8**，versionName `2.3.0-rc4-vivo-ab` → **`2.3.0-rc5`**；包名、签名与覆盖安装兼容（同证书可覆盖安装旧版本与诊断版，不清数据）。
- 移除面向用户的 A/B 测试文案；`get_l_service_status` 诊断状态读取能力保留（`build_variant` 改为 `release`）。
- 文案「学校」全部改为「工作」（与 GitHub rc4 纯净版同一改法：界面、README、LOCATION-SAFETY、测试与服务端测试同步，围栏 id 由 `school` 改名 `work`）。
- 保留已验证稳定的能力：无障碍被动前台观察、小L截屏与视觉分析、前台状态记录、主动通知、哨兵链路。
- **应用锁/锁定浮层/返回桌面/强制停留与定时跳转 RikkaHub：本版暂不支持**（Vivo 兼容模式）。原因：这组主动干预曾导致 Vivo 上无障碍开关被系统回收。MCP 工具 `lock_app` / `focus_rikka` / `redirect_to_rikka` 调用时会如实返回「暂不支持」且**不写入任何配置**（不存在「显示成功但实际无动作」）；`unlock_app` / `list_locked_apps` 可用于查看与清理历史锁定配置。

### 签名

- 与 GitHub 官方 Release（v2.3.0-rc4）同一签名密钥与证书。
- 证书 SHA-256：`b2fd4dc1083d5e267c83c908f18bb5d76e9ba746edc8b21fbc07086b865d8cd1`

### 已知限制

- 应用锁、锁定浮层、返回桌面、跳转 RikkaHub 暂不支持（见上）。
- 本版基于个人版验证源码构建；「学校→工作」文案清理已并入本版。
- 旧版本已设置「学校」围栏的用户覆盖安装后，「工作」围栏会显示为未设置（围栏 id 由 `school` 改名 `work`），需重新设置一次工作地点；家围栏不受影响。

---

## 2.3.0-rc4（历史）

GitHub 官方 Release 版本（versionCode 7）。本 rc5 可覆盖安装。
