# 小哈电巡（Android）

把 [Eggbeater5925/evwatch-monitor](https://github.com/Eggbeater5925/evwatch-monitor) 里
「哈啰小哈智电」这一路监控移植成 Android App。
原项目是 macOS 上的 Python 常驻服务，本仓库是它的 Kotlin/Compose 版本。

## 功能

### 云端监控（哈啰小哈智电）
- 填 Token + Ticket（小哈智电小程序抓包获取），电池编号留空自动取账号下第一块
- 展示：电量 SOC、充电状态、真实总电压 / 电流 / 功率、串数、压差、MOS 温度、
  健康度 SOH、循环次数、剩余容量、预估续航、总里程、上报时间
- 定时轮询（默认 60 秒，可配）
- 提醒：
  - 电量达到里程碑（默认 80 / 90 / 100%）——同一次充电内只提醒一次
  - 低于阈值（默认 20%）——同一次放电内只提醒一次
  - 充电开始 / 充电结束（含本次充电时长与起止电量）
- 推送通道：钉钉机器人（支持加签）、企业微信机器人；都没配就只发本地通知

### 本地蓝牙（无需账号）
- 扫描附近电池的 BLE 广播读取本机电量（不配对、不连云），解析设备名 `HBB<编号>SOC<电量>`
- 主电量卡**融合两个来源**：本地有实时读数优先显示本地，否则显示云端，并并列标出两来源

### 单体电压网格
- 4 列网格展示每串单体电压（C01 + mV），**最高串蓝点、最低串红点**
- 标题行显示「最高 / 最低 / 压差」具体数值，压差过大（>100mV）标红

### 实时通信控制台
- 分类型日志流：遥测状态、请求云端、响应成功、蓝牙状态、提醒、错误
- 每条带图标 + 时间戳，右上角「清空」

### GPS 轨迹
- `battery.track` 接口取最近定位点，显示地址或经纬度，可点「查看位置」用系统地图打开

## 操作入口

大部分操作收在**右上角 ⋮ 菜单**：开始/停止监控、本地蓝牙（扫描/停止/选电池/开启蓝牙或定位）、修改配置。
配置页顶部右上有「退出 ✕」。

## 协议

- `POST https://switchpowerapi.hellobike.com/api?{action}`
- 请求体：`base64(AES-128-CBC(明文JSON))`，key 与 iv 相同
- 签名：`HmacSHA1(明文 + timestamp + nonce, secret)` 的 hex
- action：
  - `swp.li.user.battery.list` 电池列表
  - `swp.li.user.battery.status` 实时电气参数 / 单体电压
  - `swp.li.user.battery.detail` 电量 / 健康度 / 续航 / 里程
  - `swp.li.user.battery.track` GPS 轨迹

字段说明见 `docs`（继承自原项目）。

> 注意：`status.voltage` 是**真实总电压**，`detail.volt` 是**额定电压**，功率按真实电压 × 电流计算。

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
