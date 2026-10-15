# B01 系列编辑时刻最小补缺（冻结）

2026-10-15，worker b01_sol；共享候选 codex/mini-bug-arch-integration-20261015。协调者已追加批准独占 `chengyinhub-xcx/pages/club/event-ops/index.js` 和新测试 `chengyinhub-xcx/tests/unit/b01-series-start-time.test.js`。rg 已确认路径一致。未修改此前冻结的六个 JS、公共基线、动作清单、请求封装、WXML/WXSS、布局或后端；未切分支、git add/commit。

## 合同与修复

与 B04_sol 确认：POST `/api/club/event-ops/series/detail` 请求 `{clubId:21,seriesId:61}`，`data.startTime` 为按 Asia/Shanghai 从未来 occurrence 还原的共同 HH:mm；list 同字段。无未来场次/混合时刻为 null，序列化省略也视为未知。fixture 00:30（跨 UTC 日界）/18:45 是测试值；完整 DTO 另含 topicId、defaultLeadMemberId、version、offerMinutes、recurrenceType、waitlistEnabled、futureDates。

原 loadSeriesDetail 无条件设置 09:00，使非九点系列编辑后新增日期被改为九点。normalizeSeries 现仅接受有效、补零的 00:00–23:59；loadSeriesDetail 使用该值，null/缺失/非法值设空并覆盖前次编辑残留。submitSeries 原有“请选择集合时刻”校验阻止未知值写请求。用户显式选择时刻后可提交。创建默认值仍沿既有逻辑。

POST `/api/club/event-ops/series/update-future` 保留 startTime、expectedVersion 和原 JSON 字段；后端合同为仅新增日期使用该时刻，已有日期不改时刻。

## 真实处理器验证

新增 Node 测试通过 Page 注册加载真实页面，调用 beginEditSeries/loadSeriesDetail、日期/时刻处理器及 submitSeries，并捕获真实 sendRequest 配置；不是源码字符串断言。

- `series-start-time-before.txt`：先在旧源码运行 7 项，0 通过/7 失败。00:30、18:45 读回变09:00；null、无未来、缺失和非法值仍发写请求，均为业务断言失败。
- `series-start-time-after.txt`：最终 8/8 通过，fail/cancelled/skipped 均0。验证非09:00日期重排请求保真、null/无未来/缺失/非法值清残留并拒写，以及未知值显式选择后可写。
- `series-start-time-related.txt`：上述8项与既有 event-ops pages、series-title、cu-c-146相关套件合跑54/54，fail/cancelled/skipped 均0。与8项重复，不相加。
- `git diff --check -- chengyinhub-xcx/pages/club/event-ops/index.js chengyinhub-xcx/tests/unit/b01-series-start-time.test.js`：无输出，退出0。

复跑：`node --test chengyinhub-xcx/tests/unit/b01-series-start-time.test.js`。相关合跑文件列表见上述相关输出及本报告。

## 未验证与回退

仅 Cloud 轻量 Node；未跑全量、Java、DB、VPS联调、真实服务端独立读回或微信模拟器/设备截图。未声称 Node fixture 是真实业务读回。设备表单空值呈现及真实后端 fixture 由 C99 续验；没有 UI 变更。源码/测试/证据已冻结交协调者，行号及 ledger 由根维护。需回退时仅回退本补缺，不覆盖其他 lane。
