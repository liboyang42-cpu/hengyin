# 本人公平统计样例

合成账号 7。已批准参数：Δ 100、H 5000、L 1000、`pageSize` 1、`metricVersion` `metric-1`。账号 7 在 6000 完成 `o1`，在 6200 完成 `o2`。账号 8 的 `ob` 不进入这些响应。响应头 `X-Chengyin-Candidate: city-fairness`。

| 文件 | 场景 |
| --- | --- |
| `report-first-page.json` | `GET /report` 首次，`cursor` 为 null |
| `outing-first-page.json` | 同一 `queryId` 的出行第一页。快照已存在，所以 `readBack` 为 true，`cursor` 仍为 null |
| `outing-next-page.json` | 出行下一页，请求 `cursor=1` |
| `report-empty-mature.json` | `asOf` 7000，成熟队列为空，计数为 null，目标完成为真实 0 |
| `report-503.json` | `selectRead` 失败。原查询还在，响应不是成功的空报告 |
| `outing-503.json` | 同一失败在出行接口上的形状 |
