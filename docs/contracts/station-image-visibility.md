# 站点照片可见性合同（F07，2026-11-18）

后续站点照片到站前不显示，由服务端执行（抓包也拿不到图片地址）。唯一判定入口：
`chengyinhub-system/.../service/support/StationImageVisibility.java`。

| 产品 | 规则 |
|---|---|
| 城市定向（mode / product_type 1，缺省按 1） | 只下发第一站（sort_id 最小）和本局已到达（扫码或点「我已到达」，checkin_time 不为空）或已完成的站的 `imgUrl` |
| 自由探索（mode 2） | 店铺照片用来找店，不遮 |

- 被遮的站：`imgUrl: null`，同时下发 `imgHidden: true`。小程序用 `cy-station-silhouette` 剪影占位，iOS 解析 `imgHidden`。
- 已接入：`/api/play/nodes`（`PlayNodeQueryApplication`）、`/api/topic/info-to-user`（`ApiTopicController.hideLaterStationImages`；主题作者本人看全部，承接商家看自己那一站）。
- 主题封面、章节封面照常下发；封面本身不能露答案，由内容侧 PHOTO BRIEF 保证。
- 任何新接口返回站点图都必须经过 `StationImageVisibility`。
