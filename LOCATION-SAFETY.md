# LoverConnect 2.4 自愿安全位置播报

本功能用手机本地“家 / 工作 / 一个自定义名称”地理围栏生成有限的结构化事件，让用户选择的 AI
在未核对到报备时温和提醒，并在稳定到达某个围栏时播报一次平安消息。自定义围栏可命名为
“健身房”“朋友家”等；重新设置或删除后，运行中的定位服务会立即重载配置。

## 隐私与控制边界

- 默认关闭，只能由手机界面授权、配置和开启。
- 原始坐标只存在于 Android Keystore 加密的本机配置中，不进入事件、日志、MCP、
  服务器数据库或备份。
- AI/MCP 只能读取开关、用户设置的区域名称、区域状态和队列数量，不能静默设置或修改安全区。
- 自定义名称按用户配置数据处理，剥离控制/双向排版字符、限制为1–24字，并明确没有指令权限。
- GPS 丢失、精度不足或单点漂移不会被解释成“已经离开”。
- 常驻前台通知提供暂停、关闭和清除全部位置数据入口。

## 事件链路

```text
Android 本地围栏
  -> SQLite 离线队列（按序、幂等、有限退避）
  -> POST /loverconnect/v1/location-events
  -> VPS 鉴权 / 去重 / 限流 / 持久任务
  -> 只依据手机“已报备”按钮产生的结构化标记核对
  -> send-only 写入 RikkaHub 提醒 / 到达播报
```

支持的事件：

- `zone_exit_confirmed`
- `zone_enter_confirmed`
- `distance_tier_crossed`
- `location_degraded`
- `tracking_paused`
- `offline_trip_summary`
- `report_acknowledged`

若一次离开和到达都发生在断网期间，手机会把尚未送达的旧事件压缩成一条到达摘要，
不会联网后补发已经失效的离开警报。服务器对 `event_id` 幂等，同一外出最多一次
首次提醒和一次远距离提醒；所有位置消息共享每小时四条上限。

## 报备核对

服务端不读取 RikkaHub 的任何会话正文，也不让模型猜测某句话是否算报备。手机端的
“本次已报备”与确认按钮会产生 `reported_override` / `report_acknowledged` 结构化标记；
只有这些标记会取消待发送提醒。RikkaHub 连接仅用于 send-only 写入有限提醒文本。

## 服务端

服务端实现在 [`server/`](server/)；它同时保留旧的 `/loverconnect/alert` 语义。
先在独立回环端口和独立数据库运行测试，再灰度切换现有端口：

```bash
cd server
python3 -m unittest -v test_loverconnect_ingress.py
```

真实凭据只能放在仓库外、权限受限的环境文件中。公开仓库只保留
`credentials.env.example`，不得提交 Token、会话 ID、私人地址或服务器地址。

## 当前限制

- 首版只判断围栏进出与距离档位，不监控路线、停留或危险程度。
- 不依赖地图 API，也不显示连续轨迹。
- 这是陪伴提醒功能，不保证紧急救援，不能作为唯一安全措施。
