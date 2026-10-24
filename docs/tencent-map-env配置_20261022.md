# 腾讯位置服务环境变量（首页城市定位）

首页城市定位走服务端 `POST /api/map/reverse-geocode`。腾讯位置服务默认关闭（`application.yml`：`tencent.map.enabled=${CHENGYIN_TENCENT_MAP_ENABLED:false}`，Key 默认为空），未配置时接口返回 `city=""`、`manualInputRequired=true`、`reason=MAP_UNAVAILABLE`，小程序改为让用户手动选择城市。

## 应用机必须设置（仅负责人可操作）

| 环境变量 | 值 | 说明 |
|---|---|---|
| `CHENGYIN_TENCENT_MAP_ENABLED` | `true` | 启用服务端腾讯位置服务 |
| `CHENGYIN_TENCENT_MAP_KEY` | 腾讯位置服务 Key | 仅服务端使用，不下发小程序，不要提交到仓库 |

## 腾讯位置服务控制台

- Key 需开通 **WebServiceAPI**（含逆地址解析 geocoder）。
- 如 Key 设置了 IP 白名单（签名/来源限制），需把**应用服务器的出口公网 IP** 加入白名单，否则会返回鉴权失败。
- 设置后重启 jar；启动日志出现「腾讯位置服务未启用 / 未配置 Key」的 WARN 即表示仍未生效（日志不打印 Key）。

限流：`tencent.map.reverse-geocode-per-minute`（默认 60），超限返回 `reason=MAP_RATE_LIMITED`，同样触发手动选城市。
