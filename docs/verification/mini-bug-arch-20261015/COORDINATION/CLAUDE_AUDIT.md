# Claude 协调只读审计（2026-10-15）

CY-HANDOFF: CODEX — 供 C00 串行发布；本文件不是 ASSIGNED 或合同冻结授权。

已完整读取控制协议四文档、根及小程序 AGENTS、PR21 d9dc65a 下 mini-claude-ui-20261015 全目录（仅 README.md/TASKS.md）。PR21 是基于 d551c6b 的文档申请；本轮比较至 c236f13。没有切分支、暂存、提交或编辑控制账。

## 申请与冲突

PR21 卡的“独占文件”是路径模式，不是实际领取记录。CL1 广泛申请 roam 全目录和全部 roam-* 工具；CL3 的 pages/club/** 也过宽，不能原样 ASSIGNED。下方列候选中实际命中的精确路径，不能把枚举当全部都需要编辑。CL2 的新 report-* 页面尚无精确命名，CL3 徽章页尚无明确路径；须先提交完整文件清单（含专属测试）再领取。昵称资料页二选一不能同时默认认领两套。建议 CL3 新增 pages/club/badge-manage/index.{js,json,wxml,wxss}，这只是待申请方案。

本轮直接交叉：CL3 的 pages/club/** 包含 B04 追加修改 pages/club/event-ops/index.js（原 tasks.json 未列但本轮确实已改）；必须排除此文件。CL4 NPC UI 可独占，但 NpcChatService.java 本轮 A02 已改，不可连带领取。任何 card 不拥有 app.json、动作账/门禁计数、共享fixture、请求封装、workflow/pom。

依赖：CL1 只读布局/路线展示可独立，若开局依赖会话或路线运行时，受 BK-SESSION/BK-GPS，不能新建会话真源。CL2 消费现有 trust 合同（BK-EXISTING），内容展示还受 BK-PRIVACY。CL3 badge 受 BK-BADGE，昵称属现有接口核对；不能据隐藏按钮推导权限。CL4 官方主办方操作与 club 系列排期不是同一接口，勿把 B04 当官方接口已验；NPC 导出受 BK-AI/既有本人范围，积分规则读取受 BK-ASSET/既有配置，不改政策。CL5 必须继续等待认领材料合同。

控制 BACKEND_CHANGES 当前仍是初始提案/未部署记录；PR21 自称“无依赖”不等于接口已 CONTRACT_FROZEN。新 UI 可以设计/明确fixture，未逐端点核方法/DTO/错误/权限/request/version/分页和兼容fixture前，不批准接线或真实联调；本次未替这些能力宣告 SOURCE_READY/TEST_DEPLOYED。

### CL1 精确路径候选

- `chengyinhub-xcx/pages/roam/index.js` — PR20 重叠
- `chengyinhub-xcx/pages/roam/index.json`
- `chengyinhub-xcx/pages/roam/index.wxml` — PR20 重叠
- `chengyinhub-xcx/pages/roam/index.wxss` — PR20 重叠
- `chengyinhub-xcx/pages/roam/node-level-style.js`
- `chengyinhub-xcx/subpackageRoam/citynode-code/index.js`
- `chengyinhub-xcx/subpackageRoam/citynode-code/index.json`
- `chengyinhub-xcx/subpackageRoam/citynode-code/index.wxml`
- `chengyinhub-xcx/subpackageRoam/citynode-code/index.wxss`
- `chengyinhub-xcx/subpackageRoam/citystamp/index.js`
- `chengyinhub-xcx/subpackageRoam/citystamp/index.json`
- `chengyinhub-xcx/subpackageRoam/citystamp/index.wxml`
- `chengyinhub-xcx/subpackageRoam/citystamp/index.wxss`
- `chengyinhub-xcx/subpackageRoam/citystamp/mailbox.png`
- `chengyinhub-xcx/subpackageRoam/citystamp/printer-body.jpg`
- `chengyinhub-xcx/subpackageRoam/citystamp/printer-slot.jpg`
- `chengyinhub-xcx/subpackageRoam/history/index.js` — PR20 重叠
- `chengyinhub-xcx/subpackageRoam/history/index.json`
- `chengyinhub-xcx/subpackageRoam/history/index.wxml` — PR20 重叠
- `chengyinhub-xcx/subpackageRoam/history/index.wxss`
- `chengyinhub-xcx/subpackageRoam/nearby/index.js` — PR20 重叠
- `chengyinhub-xcx/subpackageRoam/nearby/index.json`
- `chengyinhub-xcx/subpackageRoam/nearby/index.wxml` — PR20 重叠
- `chengyinhub-xcx/subpackageRoam/nearby/index.wxss` — PR20 重叠
- `chengyinhub-xcx/subpackageRoam/official-route/index.js`
- `chengyinhub-xcx/subpackageRoam/official-route/index.json`
- `chengyinhub-xcx/subpackageRoam/official-route/index.wxml`
- `chengyinhub-xcx/subpackageRoam/official-route/index.wxss`
- `chengyinhub-xcx/subpackageRoam/official-routes/index.js`
- `chengyinhub-xcx/subpackageRoam/official-routes/index.json`
- `chengyinhub-xcx/subpackageRoam/official-routes/index.wxml`
- `chengyinhub-xcx/subpackageRoam/official-routes/index.wxss`
- `chengyinhub-xcx/subpackageRoam/poi-detail/index.js`
- `chengyinhub-xcx/subpackageRoam/poi-detail/index.json`
- `chengyinhub-xcx/subpackageRoam/poi-detail/index.wxml`
- `chengyinhub-xcx/subpackageRoam/poi-detail/index.wxss`
- `chengyinhub-xcx/subpackageRoam/session/index.js`
- `chengyinhub-xcx/subpackageRoam/session/index.json`
- `chengyinhub-xcx/subpackageRoam/session/index.wxml`
- `chengyinhub-xcx/subpackageRoam/session/index.wxss`
- `chengyinhub-xcx/subpackageRoam/utils/map-team.js`
- `chengyinhub-xcx/subpackageRoam/utils/official-route.js`
- `chengyinhub-xcx/utils/roam-cache-epoch.js`
- `chengyinhub-xcx/utils/roam-demo-gate.js`
- `chengyinhub-xcx/utils/roam-entry-map.js`
- `chengyinhub-xcx/utils/roam-footprint-hint.js`
- `chengyinhub-xcx/utils/roam-geo.js`
- `chengyinhub-xcx/utils/roam-hangout-marker.js`
- `chengyinhub-xcx/utils/roam-hangout.js`
- `chengyinhub-xcx/utils/roam-history-metrics.js`
- `chengyinhub-xcx/utils/roam-npc-coordinator.js`
- `chengyinhub-xcx/utils/roam-npc-marker.js`
- `chengyinhub-xcx/utils/roam-player-memory.js`
- `chengyinhub-xcx/utils/roam-poi-group.js`
- `chengyinhub-xcx/utils/roam-poi-state.js`
- `chengyinhub-xcx/utils/roam-recovery.js`
- `chengyinhub-xcx/utils/roam-route-privacy.js`
- `chengyinhub-xcx/utils/roam-runners.js`
- `chengyinhub-xcx/utils/roam-screen-fog.js`
- `chengyinhub-xcx/utils/roam-session-photos.js`
- `chengyinhub-xcx/utils/roam-share-snapshot.js`
- `chengyinhub-xcx/utils/roam-tile-memory-sync.js`
- `chengyinhub-xcx/utils/roam-track-filter.js`
- `chengyinhub-xcx/utils/roam-track-simplify.js`

### CL2 精确路径候选

- `chengyinhub-xcx/pages/team/report-sheet/index.js`
- `chengyinhub-xcx/pages/team/report-sheet/index.json`
- `chengyinhub-xcx/pages/team/report-sheet/index.wxml`

### CL3 精确路径候选

- `chengyinhub-xcx/pages/gerenziliao/gerenziliao.js`
- `chengyinhub-xcx/pages/gerenziliao/gerenziliao.json`
- `chengyinhub-xcx/pages/gerenziliao/gerenziliao.wxml`
- `chengyinhub-xcx/pages/gerenziliao/gerenziliao.wxss`
- `chengyinhub-xcx/pages/gerenziliao/images/d_bianji2.png`
- `chengyinhub-xcx/pages/gerenziliao/images/d_sctb.png`
- `chengyinhub-xcx/pages/userinfo/userinfo.js`
- `chengyinhub-xcx/pages/userinfo/userinfo.json`
- `chengyinhub-xcx/pages/userinfo/userinfo.wxml`
- `chengyinhub-xcx/pages/userinfo/userinfo.wxss`

### CL4 精确路径候选

- `chengyinhub-xcx/components/cy/npc-chat/index.js`
- `chengyinhub-xcx/components/cy/npc-chat/index.json`
- `chengyinhub-xcx/components/cy/npc-chat/index.wxml`
- `chengyinhub-xcx/components/cy/npc-chat/index.wxss`
- `chengyinhub-xcx/pages/activity/official-detail/index.js`
- `chengyinhub-xcx/pages/activity/official-detail/index.json`
- `chengyinhub-xcx/pages/activity/official-detail/index.wxml`
- `chengyinhub-xcx/pages/activity/official-detail/index.wxss`
- `chengyinhub-xcx/pages/activity/official-inbox/index.js`
- `chengyinhub-xcx/pages/activity/official-inbox/index.json`
- `chengyinhub-xcx/pages/activity/official-inbox/index.wxml`
- `chengyinhub-xcx/pages/activity/official-inbox/index.wxss`
- `chengyinhub-xcx/pages/activity/official-mine/index.js`
- `chengyinhub-xcx/pages/activity/official-mine/index.json`
- `chengyinhub-xcx/pages/activity/official-mine/index.wxml`
- `chengyinhub-xcx/pages/activity/official-mine/index.wxss`
- `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js`
- `chengyinhub-xcx/subpackageMember/components/cy/profile/index.json`
- `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml`
- `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxss`

### CL5 精确路径候选

- `chengyinhub-xcx/pages/merchant/components/merchant-claim-sheet/index.js`
- `chengyinhub-xcx/pages/merchant/components/merchant-claim-sheet/index.json`
- `chengyinhub-xcx/pages/merchant/components/merchant-claim-sheet/index.wxml`
- `chengyinhub-xcx/pages/merchant/components/merchant-claim-sheet/index.wxss`
- `chengyinhub-xcx/utils/merchant-claim.js`

## 可委派专属后端的明确边界

CL1 若只保留本地路线站点展示，不需要后端。若实证需要增量，可申请新的 `OfficialRouteFollowController.java`、`OfficialRouteFollowService.java` 及专属测试（同现有 api/service 包），限薄适配现有路线读取，不能写 GameSessionRuntimeServiceImpl、TopicRouteRuntimeServiceImpl、PlayJourneyStoryServiceImpl、Mapper或新增表；这些命名是待批准清单，不是已有文件/实现。若能力需要运行时变化则退回 C00，不能以“专属”绕开共享所有权。

CL2–CL4 文档明确消费已有接口，没有提出或证实必须新增后端；本轮不应虚构后端工单。CL5 的 MerchantClaimServiceImpl/DTO/Mapper 是认领专属实现，当前不与原 owned_files/本轮源码相交，可由 C00 在合同决策后精确委派；但 AppUploadService、ApiCommonController、FileOssUploadUtils 是共享上传，不能一并交出。鉴权/RBAC改变仍单独停点。

## 门店材料：能确认实现，不能冻结完整业务合同

- `app.js:508` chooseImage 的默认 mapResult 是 `data.url`；claim-sheet addProof 未覆盖 options，故 proofUrls 是完整（可能签名）URL。`utils/merchant-claim.js:9,42` 固定 PHOTO，并把 URL 写作 assetId。
- `ApiCommonController.java:102–140` POST `/api/common/uploadOSS` 返回顶层 fileName、非售后 url；community 的 uploadReceipt 只属于其分支，不能套用认领。
- `AppUploadService.java:109–122` 经安全检查后交 photoOwnerUploader，绑定 memberId/bizType；`FileOssUploadUtils.java:180–201` 以 fileRelName 调 OSS putObject，并把同值返回 fileName，url 另生成签名链接。因此 fileName 有“真实对象键”源码证据，不能截 URL 猜键。
- `ApiMerchantClaimController.java` POST submit / GET status；`MerchantClaimServiceImpl.validateSubmit` 只要求 proofType/assetId 非空及 trim 长度 <=32/255，1–10 份；没有枚举、对象存在/归属/claim业务类型核验。submit 将二者原样trim落库，fingerprint 将排序后的 type:asset 加入摘要。空/超长400；重复同requestId不同指纹409；controller只返回code/msg，不暴露 exception 内业务code字段。
- `migration_20260828_merchant_claim_ops.sql:34–38` proof_type varchar(32) 无枚举，asset_id 注释“已有对象存储资源标识，不存客户端任意URL”，唯一键(claim_id,asset_id)。这是历史DDL源码注释，不是实库已核证据。

最小冻结前证据：C00 明确 proofType 的既有业务值来源（目前 PHOTO 无依据）；确定使用上传 fileName 原值及对应上传 bizType/审核读取方式，给正常、空/超长、URL误传、重复材料、同requestId同/异内容、越权/不可认领样本；审阅旧客户端 URL 数据兼容，不先写新政策/DDL/鉴权。本次只能确认 fileName 为对象键，不能据此冻结 PHOTO 或宣称服务端拒绝任意URL。若增加对象归属核验，需具体补丁及授权审查，不由本审计擅加。

## PR20 新功能隔离

PR20 49182b7 是独立 master 父链的 127 文件改动，含两个 migration_20261012_*.sql 和迁移索引；不得整包集成进本批。它与 CL1 直接交叉：pages/roam/index.{js,wxml,wxss}、subpackageRoam/history/index.{js,wxml}、subpackageRoam/my-teams/index.{js,json,wxml,wxss}、subpackageRoam/nearby/index.{js,wxml,wxss}。公共路由/动作账/门禁也交叉。与 B01 原 owned_files 交叉为 scene-member-order-detail/index.js；对应 WXML 也需联合事件合同。下列是 PR20 与本轮 d551c6b..c236f13 的全部精确交集（不能用直接合并覆盖）：

- `chengyinhub-xcx/scripts/uiaudit/action-ledger.json`
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js`
- `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`
- `chengyinhub-xcx/tests/unit/action-ledger.test.js`

验证：仅 git show/ls-tree/diff、源码只读取证；未跑全量Node、Java、DB、真实provider、VPS或设备测试。
