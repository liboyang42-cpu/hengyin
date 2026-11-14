# 自由探索修复波 2026-11-14（D 组）

分支 fix/free-explore-1114。本地未编译/未跑 Java 测试（规则：仅 CI/VPS）；以 PR 检查为准。

## 逐项
- D-01 已修：货架 15s 新鲜窗口，attached+show 只发一次请求。
- D-02 已修：首页返回 total 后并行取余页。
- D-03 已修：新增 `templatedetail/utils/topic-use-flow.js`（预览/详情共用），requestId 每次意图只生成一次、重试复用；页面卸载后仍记录已复制草稿 id（5 分钟内再次采用直接打开该草稿）。
- D-04 未改行为：既有测试 `template-tab-failure-state-contract` 明确要求只上架保留的 25 个、不填充，属业主规则；表外标题加固定标签 console.warn。需业主决定是否通用封面。
- D-05 已修：封面失败防抖合并重建。
- D-06 已修：7 个精选全部展示。
- D-07 已修：openTopic/openGame 600ms 防连点。
- D-08 已修：提示改为「暂未开放」。
- D-09 未修：既有测试禁止空态加刷新按钮；仅改为切回空 tab 时重新拉取。
- D-10/D-11 已修：权限加载失败显示可点重试；发布弹窗打开时补拉 publish/home。
- D-12/D-13 已修：预览页主按钮「用这个模板」，详情 CTA 改主按钮同文案。
- D-14 已修：无 stops 时用空态替代「—」。
- D-15 已修：goPrimary 校验登录并遵守 readonly，货架透传 readonly。
- D-16 已修：预览接口返回 productType；自由探索定价页只显示门票价，确认不带 leadCost/teamSize。
- D-17 已修：展示平台服务费（platformRate，3%）与预计到手。
- D-18 已修：改成本/人数即禁用确认并 0.6s 自动重算。
- D-19 仅可见化，未定分账规则（待业主拍板）：主办方定价页展示「结算待处理」+原因+金额（GET /api/topic/pricing/settlement-pending，按归属校验）；运营端列表 GET /business/activity/stranded-free-explore（复用 business:activity:list 权限）。不写任何账。
- D-20 已修：onUnload 清理返回定时器。
- D-21 已修：slider min 取 ceil(地板价)。
- D-22 已修：定价 preview/confirm 接受 scope，商家岗位按 /use 同口径解析店主，再校验主题归属；前端按 contentPublisherType 透传。
- D-23 已修：文案「已锁价，等待运营开售」；新增 GET /api/topic/pricing/sale-status 状态入口，定价页展示。
- D-27 已修：reload 复位 loadingMore。
- D-28 核实：预约 POST /api/free-explore/bundle/reservation 与商家预约列表/no-show 小程序无其他入口，已接入：权益页显示章节名/内容/店名（bundle.chapters 为新增附加字段），「预约」按钮（幂等键，未确认走「重新确认」）；新页 pages/merchant/bundle-reservations（商家「更多」→套票到店，未来 7 天列表，开始后可记未到店）。预约总开关后端默认关闭。
- D-29/D-30 已修：新增 FreeExploreFeeAllocator，统一取报名冻结费率，整场费用四舍五入一次，余额法（最大余数）分摊到逐单，现金与分账行分到分一致；缺冻结费率的报名被隔离并 ERROR 告警，不再回滚整场。未覆盖：部分退款留存部分仍用现行规则费率（无逐单分账行）。无新 migration，未改既有 migration。
- 单测：FreeExploreFeeAllocatorTest（3×9.50=0.86、1–40 张合计恒等、混合费率、退款冲回合计、缺费率），MerchantPublishedFreeExploreSettlementTest 更新并加缺费率隔离用例。

## 其他
- 已重新生成 route-matrix、action-ledger；更新相关计数/契约测试。未跑 xcx-check selftest。
- 描述中 ASCII 双引号问题归内容线。

STATUS: PARTIAL - D-04、D-09 受既有业主测试约束未改行为；D-19 分账规则待业主决定；CI 结果见 PR

## PR #833 CI 修复（2026-11-14）

- 正常合入 public `origin/master`（561cafd10，含 #831）；未变基、未强推。原 run 43584717767 的后端三个失败来自 #831 合并树：首尾标点已允许，旧断言仍拒绝显式别名「正确。」；性质测试的旧实现用 `trim().isEmpty()` 代替实际的 `StringUtils.isBlank`，错误地接受纯 U+3000 候选；跨夜测试按 JVM 默认时区构造日期，与已改为 Asia/Shanghai 的生产判定不一致。
- 主分支故障由 `fix/master-ci-1114` owner 统一修复。按 lead 指令，本 PR 撤回前述三个后端测试修复；只保留自由探索引入的修复，待主分支修复合并后再次正常合入 master。
- 定价页 D-19/D-23 读取以前在业务失败和网络失败时直接消失；现在按现有 toast 封装显示失败，卸载后的回调保持无提示。新增两项行为测试覆盖业务拒绝、空回包、网络失败、成功只读与卸载。D-19 仍只有 GET 可见化，不增加分账或写账行为。
- `ui-integrity`：仅重锚两项 templatedetail U4 与既有 pricing 补名 U5；删除已经被实际读取的 pubLoaded 旧债务，u4Field 99→98。keyDigest 使用门禁 `baselineKeyDigest()` 相同算法（排序后的键以换行连接再 SHA-256），有日期注释；没有新增基线项或提高棘轮。
- 新预约页保留协作者补入的 C63 `setData` 截图矩阵行（删除合并产生的重复 F317），fixtureVerified 仍为 16、blocked 仍为 24；矩阵 369 行。仅新增待采集计划，不宣称已截图或完成业务验收。C63 的 fixture 与 selector 使用现有生成器生成，仅提取新预约页产物，保留其他页面原有审校状态；fixture 合同 10/10 通过。
- 使用 `build-route-matrix.js --json` 与 `npm run audit:actions:update` 重建。当前合并树动作总数 6060：tap 3051、event-contract 3009、external tap 381、本地 tap 2670、本地 event-contract 2910；按实际新增控件同步精确断言，保留台账新鲜度、handler 真源、动作分支及证据 pending 规则。
- 本机聚焦验证：新增失败反馈 2/2；定价/路由聚焦测试 15/15；截图矩阵双向覆盖与统计通过；UI-GATE-0（warn-as-error）通过。完整 Java 编译/测试与所有分片以本次推送后的 GitHub-hosted CI 为准，后续最终报告记录 SHA 和各检查结果。
- 未运行本地 Java/Docker/Redis；未运行 `ci/xcx-check.sh` selftests；未改 `ci/architecture/*` 或 workflow；未部署、未合并、未操作生产或密钥。
