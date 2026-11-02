# 小哈电巡（Android）

把 [Eggbeater5925/evwatch-monitor](https://github.com/Eggbeater5925/evwatch-monitor) 里
「哈啰小哈智电」这一路监控移植成 Android App。
原项目是 macOS 上的 Python 常驻服务，本仓库是它的 Kotlin/Compose 版本。

## 功能

- 填 Token + Ticket（小哈智电小程序抓包获取），电池编号可留空自动取账号下第一块
- 展示电量（SOC）、充电状态、总电压、电流、MOS 温度、SOH、循环次数、上报时间
- 定时轮询（默认 60 秒，可配），可手动刷新
- 提醒：
  - 电量达到里程碑（默认 80 / 90 / 100%）——同一次充电内只提醒一次
  - 低于阈值（默认 20%）——同一次放电内只提醒一次
  - 充电开始 / 充电结束（含本次充电时长与起止电量）
- 推送通道：钉钉机器人（支持加签）、企业微信机器人；都没配就只发本地通知

## 协议

- `POST https://switchpowerapi.hellobike.com/api?{action}`
- 请求体：`base64(AES-128-CBC(明文JSON))`，key 与 iv 相同
- 签名：`HmacSHA1(明文 + timestamp + nonce, secret)` 的 hex
- action：
  - `swp.li.user.battery.list` 电池列表
  - `swp.li.user.battery.status` 实时电气参数
  - `swp.li.user.battery.detail` 电量 / 健康度等

字段说明见 `docs`（继承自原项目）。

## 构建

```bash
gradle assembleDebug
# 或 ./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 国内网络若下载依赖慢，`settings.gradle.kts` 已预置阿里云 Maven 镜像。

## 免责声明

仅限本人账号、本人电池使用。接口信息来自公开客户端静态分析，
本仓库不含任何抓包原文或真实凭据，示例值均为占位符。
