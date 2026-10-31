> 历史源码兼容性参考；不代表 hengyin 当前候选已完成页面实跑或上线验收。

# 小程序页面清理审计（2026-10-09，第二轮 W1）

## 结论

| 注册页面 | DELETE | KEEP | UNSURE | 未注册孤立 Page+WXML |
| --- | --- | --- | --- | --- |
| 148 | **0** | **144** | **4** | 0 |

2026-10-26 按当前 app.json 回读为 148 页；本轮补入角色卡、定向计分、本人发票三页，均保留，未进行设备验收。

2026-10-17 漫游上线新增 6 个注册页(我的队伍 + 5 个漫游工具页),均由漫游入口或漫游记录直接引用,判定 KEEP。

基线：`feature/generic-gameplay@1fb2e72217`；工作分支：`feature/gg-r2-anchor-cleanup`。**本轮不删除任何页面、app.json 条目、测试、台账声明或后端接口。** 当前能证明的是保留依据，不能把“搜不到字面量”解释为“无人访问”。协调者已确认保留四个不确定兼容壳；不能为了制造删除数量而破坏跨页契约或历史链接。

## 扫描与判定边界

可重复的只读脚本：`chengyinhub-xcx/tests/tools/audit-page-cleanup.js`：

```sh
cd chengyinhub-xcx
node tests/tools/audit-page-cleanup.js
node tests/tools/audit-page-cleanup.js --table
node --test tests/unit/page-cleanup-audit.test.js
```

脚本从 app.json 主包、分包取完整页面清单，核对全部 tabBar；扫描 Git 跟踪的 JS/JSON/WXML/Vue/HTML/Java/XML/SQL/YAML/properties/TS。覆盖小程序页面、组件、utils、后端控制器/服务/模板/配置/SQL以及 H5/admin 源码；docs 与生成台账不充当运行时跳转证据。记录完整路由及候选目录前缀、分享钩子、跨文件测试、动态 url 和二维码/订阅消息调用位置。没有访问线上服务、线上数据库、微信后台、已发出的历史消息或二维码。

判定：
- **KEEP**：tabBar 或当前源引用，或确知历史分享兼容。源引用包含导航、场景注册、引用常量及注释，属于保留线索，不等价于每个页面当前都在真实业务链上。
- **UNSURE**：入口已被新页面取代，却仍有跨页契约/历史外发风险；必须由页面 owner 核验历史分享、二维码、订阅消息投放及动态跳转后才能删除。
- **DELETE**：须同时排除 tabBar、全部直接/动态/WXML/组件/utils导航、分享/QR/scene/订阅消息/后台链接及非自身测试。**本轮没有满足全部条件的页面。** 不修改其他测试以人为消除保留证据。

## 外发与动态入口保留证据

| 来源 | 源码位置 | 对清理的影响 |
| --- | --- | --- |
| 玩家二维码 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayerWxacodeServiceImpl.java:41` | 固定落地 `pages/index/index`，另携带签名 scene；首页不能删 |
| 订阅消息 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxSubscribeMsgServiceImpl.java:72` | `wx.subscribe.page` 可由部署配置覆盖，默认首页；本地缺失线上历史配置/已发消息不能证明兼容壳未被投放 |
| 场景路由 | `chengyinhub-xcx/utils/scene-registry.js:21` | 页路由注册可能经 scene 动态选择，并非所有调用处含页面字面量；脚本纳入注册源 |
| 模板字符串、变量 url、WXML url 属性 | 全仓 JS/WXML/Vue/Java/XML/SQL 配置扫描（脚本 JSON 的 references/externalSources） | 全路由及目录前缀搜索只提供线索，不能替代动态数据流和外部输入审查；不据此批准删除 |
| 旧圈玩法分享 | `chengyinhub-xcx/pages/play/circle/index.js:2`、`chengyinhub-xcx/pages/play/circle/index.js:499` | 历史卡片仍可进入旧地址；分享钩子存在，保留 redirect/恢复入口 |
| H5/admin 与后端模板/SQL | `chengyinhub-ui/`、后端模块、`sql/` 全部跟踪文本扫描 | 四个候选未找到完整路由的可执行外发调用；仅是当前仓库的负证据，不涵盖已发链接/远端配置 |

动态导航不接受“没有完整字符串”作为无引用证明。尤其 `subpackageMember/tixianjilu/tixianjilu.js:6` 的旧提现深链说明是一条历史兼容线索，不是实际 navigateTo 调用。

## 五个弱直接引用页面的逐项证据

全部五页都不在 tabBar；当前运行源中未找到完整路由的导航调用。四个 UNSURE 不删除，表内负责人是需作决定的业务 owner，不是自动授权。

| 页面 | 页面性质及外发证据 | 非自身测试证据 | 判定与 owner 待确认 |
| --- | --- | --- | --- |
| `pages/play/circle/index` | 旧圈玩法入口；文件头说明历史分享只能进入此处，onShareAppMessage 仍存在；通过新玩法入口兼容恢复 | `tests/unit/play-state-recovery-contract.test.js:81`；`tests/unit/dialog-contract-r3-contract.test.js:60`；`tests/unit/nav-route-exists.test.js:204` | **KEEP**；明确分享目标，不能删除 |
| `pages/publish/topicadd/topicadd` | 已变为主题编辑器 fabu 的兼容 redirect 壳；无当前完整路由导航/QR/订阅字面量，但历史卡片/后台投放未核验 | `tests/unit/topic-editor-theme-contract.test.js:242`；`tests/unit/publish-creator-uiux-recovery-contract.test.js:173`；`tests/unit/nav-route-exists.test.js:200`（另有自身 shell 测试） | **UNSURE**；创作者/主题编辑 owner 确认是否仍有旧编辑入口、分享、QR、订阅消息或管理后台动态链接 |
| `pages/club/workbench/index` | 俱乐部工作台迁移后的兼容壳，保留 id 与失败恢复；无当前完整导航/外发字面量 | `tests/unit/club-detail-ia-contract.test.js:11`；`tests/unit/club-route-recovery-contract.test.js:115`；`tests/unit/access-gate-contract.test.js:71` | **UNSURE**；俱乐部 owner 确认运营人员书签、分享/QR/订阅消息与后台工作台深链已迁移；不能凭新台替换即删旧入口 |
| `pages/coop/withdraw/index` | 已停用的合作提现入口；历史深链说明仍在 `subpackageMember/tixianjilu/tixianjilu.js:6`，当前未发现完整导航/外发调用 | `tests/unit/withdraw-cs-popup-contract.test.js:87`；`tests/unit/access-gate-contract.test.js:136`；`tests/unit/merchant-top-level-theme-contract.test.js:13` | **UNSURE**；资金/商家 owner 确认旧收益页/客服链接、QR、订阅消息和后台配置；未获确认前保留停用说明页 |
| `pages/coop/withdraw/records/index` | 旧记录页 redirect 壳，带重试/单飞恢复；同上历史深链说明 | `tests/unit/ui-shots-0916-fix-contract.test.js:327`；`tests/unit/access-gate-contract.test.js:137`；`tests/unit/coop-dialog-gap-contract.test.js:36` | **UNSURE**；资金/商家 owner 确认旧记录外发目标、QR/订阅消息及动态路由；保留网络失败恢复契约 |

上述 tests 路径均相对 `chengyinhub-xcx/`。每页完整引用列表由只读脚本输出；不以自身测试阻止删除，而是明确列出的**跨页测试**已违反本次 DELETE 条件。

## 完整页面清单

下表当前引用均为仓库根路径；每页给出一个保留线索（全部线索见脚本 JSON）。除 tabBar、旧圈分享及四个不确定壳外，KEEP 页面均交叉检查有非行首注释的源引用；表内首条线索仍可能是兼容说明。KEEP 不声称线上触达量或业务已验收。



| 页面 | tabBar | 当前源引用（文件:行） | 判定 |
| --- | --- | --- | --- |
| `pages/index/index` | 是 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiChapterMerchantNodeController.java:143` | KEEP |
| `pages/template/index` | 是 | `chengyinhub-xcx/components/cy/scene-asset-earnings/index.js:102` | KEEP |
| `pages/talent/list/index` | 是 | `chengyinhub-xcx/components/tabBar/index.js:20` | KEEP |
| `pages/member/index/index` | 是 | `chengyinhub-xcx/components/cy/profile-loading/index.js:50` | KEEP |
| `pages/roam/index` | 是 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxSubscribeMsgServiceImpl.java:301` | KEEP |
| `pages/privacy/index` | 否 | `chengyinhub-xcx/app.js:349` | KEEP |
| `pages/agreement/index` | 否 | `chengyinhub-xcx/components/cy/danger-confirm/index.wxml:5` | KEEP |
| `pages/deregister/index` | 否 | `chengyinhub-xcx/pages/agreement/index.js:9` | KEEP |
| `pages/team/detail/index` | 否 | `chengyinhub-xcx/pages/team/join/index.js:119` | KEEP |
| `pages/team/join/index` | 否 | `chengyinhub-xcx/pages/team/detail/index.js:172` | KEEP |
| `pages/play/index` | 否 | `chengyinhub-admin/sql/migration_20260825_named_mechanic_advanced_config.sql:8` | KEEP |
| `pages/play/merchant/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:317` | KEEP |
| `pages/play/circle/index` | 否 | 旧分享；`chengyinhub-xcx/pages/play/circle/index.js:2` | KEEP |
| `pages/play/poi-challenge/index` | 否 | 官方地点挑战；`chengyinhub-xcx/subpackageRoam/nearby/index.js:1059` | KEEP |
| `pages/activity/detail/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:5382` | KEEP |
| `pages/activity/baoming/baoming` | 否 | `chengyinhub-xcx/pages/topic/index/index.js:794` | KEEP |
| `pages/activity/list/index` | 否 | `chengyinhub-xcx/pages/activity/baoming/baoming.js:821` | KEEP |
| `pages/activity/official-detail/index` | 否 | `chengyinhub-xcx/components/cy/scene-deep-link/index.js:159` | KEEP |
| `pages/activity/official-inbox/index` | 否 | `chengyinhub-xcx/pages/activity/list/index.js:226` | KEEP |
| `pages/activity/official-mine/index` | 否 | `chengyinhub-xcx/pages/activity/list/index.js:223` | KEEP |
| `pages/activity/official-organizer/index` | 否 | `chengyinhub-xcx/pages/activity/official-detail/index.js:275` | KEEP |
| `subpackageA/pages/myproject/index` | 否 | `chengyinhub-xcx/pages/merchant/index/index.js:1252` | KEEP |
| `subpackageA/pages/infomation/infomation` | 否 | `chengyinhub-xcx/pages/shezhi/shezhi.js:129` | KEEP |
| `subpackageA/pages/infomationdetail/infomationdetail` | 否 | `chengyinhub-xcx/utils/scene-registry.js:71` | KEEP |
| `subpackageA/pages/assetcenter/income-detail/income-detail` | 否 | `chengyinhub-xcx/components/cy/scene-asset-earnings/index.js:90` | KEEP |
| `subpackageA/pages/assetcenter/earnings/index` | 否 | `chengyinhub-xcx/pages/coop/finance/index.js:16` | KEEP |
| `subpackageA/pages/assetcenter/analysis/index` | 否 | `chengyinhub-xcx/components/cy/scene-asset-earnings/index.js:153` | KEEP |
| `subpackageP3/pages/growthcenter/index/index` | 否 | `chengyinhub-xcx/pages/topic/index/index.js:458` | KEEP |
| `subpackageP3/pages/growthcenter/leaderboard/index` | 否 | `chengyinhub-xcx/subpackageP3/pages/growthcenter/index/index.js:296` | KEEP |
| `subpackageP3/pages/stamp-camera/index/index` | 否 | `chengyinhub-xcx/components/cy/scene-deep-link/index.js:153` | KEEP |
| `subpackageP3/pages/stamp-album/index/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1741` | KEEP |
| `subpackageP3/pages/badge-wall/index/index` | 否 | `chengyinhub-xcx/pages/play/index.js:5310` | KEEP |
| `subpackageP3/pages/badge-3d/index` | 否 | `chengyinhub-xcx/pages/play/index.js:2889` | KEEP |
| `subpackageP3/pages/object-cards/index/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1742` | KEEP |
| `subpackageB/pages/im/list/index` | 否 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiImController.java:64` | KEEP |
| `subpackageB/pages/im/chat/index` | 否 | `chengyinhub-xcx/pages/coop/invite-detail/index.js:296` | KEEP |
| `pages/publish/fabu/index` | 否 | `chengyinhub-admin/sql/migration_oms_ticket_sale_fields_20260918.sql:4` | KEEP |
| `pages/publish/simple/index` | 否 | `chengyinhub-xcx/components/cy/publish-sheet/index.js:65` | KEEP |
| `pages/publish/temp/index` | 否 | `chengyinhub-admin/sql/migration_20260825_named_mechanic_advanced_config.sql:26` | KEEP |
| `pages/publish/activity/index` | 否 | `chengyinhub-xcx/pages/merchant/decor/index.js:902` | KEEP |
| `pages/publish/topicadd/topicadd` | 否 | 无直接源引用；兼容契约/外发历史待确认 | UNSURE |
| `pages/publish/template-intro/index` | 否 | `chengyinhub-xcx/components/cy/publish-sheet/index.js:84` | KEEP |
| `pages/club/apply/index` | 否 | `chengyinhub-xcx/pages/club/create/index.js:184` | KEEP |
| `pages/club/badges/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:2377` | KEEP |
| `pages/club/create/index` | 否 | `chengyinhub-xcx/pages/club/apply/index.js:370` | KEEP |
| `pages/club/customer-detail/index` | 否 | `chengyinhub-xcx/pages/club/customers/index.js:109` | KEEP |
| `pages/club/customers/index` | 否 | `chengyinhub-xcx/pages/club/customer-detail/index.js:72` | KEEP |
| `pages/club/detail/index` | 否 | `chengyinhub-xcx/components/cy/club-topic-groupcode/index.js:3` | KEEP |
| `pages/club/edit/index` | 否 | `chengyinhub-xcx/utils/scene-registry.js:83` | KEEP |
| `pages/club/enroll/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:831` | KEEP |
| `pages/club/checkin-detail/index` | 否 | `chengyinhub-xcx/pages/club/enroll/index.js:443` | KEEP |
| `pages/club/event-ops/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:837` | KEEP |
| `pages/club/governance/index` | 否 | `chengyinhub-xcx/components/cy/scene-route-content/index.js:404` | KEEP |
| `pages/club/group-code/index` | 否 | `chengyinhub-xcx/pages/club/topic-detail/index.js:896` | KEEP |
| `pages/club/join-requests/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:2275` | KEEP |
| `pages/club/notify/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:953` | KEEP |
| `pages/club/roles/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:1032` | KEEP |
| `pages/club/settlement/index` | 否 | `chengyinhub-xcx/components/cy/scene-club-edit/index.js:421` | KEEP |
| `pages/club/topic-detail/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:2535` | KEEP |
| `pages/club/topic-story/index` | 否 | `chengyinhub-xcx/pages/club/topic-detail/index.js:654` | KEEP |
| `pages/club/workbench/index` | 否 | 无直接源引用；兼容契约/外发历史待确认 | UNSURE |
| `pages/topic/index/index` | 否 | `chengyinhub-admin/sql/migration_seed_prefab_life_20260918.sql:51` | KEEP |
| `pages/topic/merchantinfo/merchantinfo` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:340` | KEEP |
| `pages/topic/merchantapply/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:328` | KEEP |
| `pages/topic/pricing/index` | 否 | `chengyinhub-xcx/pages/topic/index/index.js:751` | KEEP |
| `pages/topic/pricing/partner/index` | 否 | `chengyinhub-xcx/pages/topic/pricing/index.js:212` | KEEP |
| `pages/merchant/index/index` | 否 | `chengyinhub-xcx/components/cy/access-gate/index.js:85` | KEEP |
| `pages/merchant/apply/index` | 否 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantPrerequisiteServiceImpl.java:16` | KEEP |
| `pages/merchant/ledger/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:26` | KEEP |
| `pages/merchant/ledger/order-detail/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:39` | KEEP |
| `pages/merchant/ledger/batch-detail/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:52` | KEEP |
| `pages/merchant/aftercare/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:65` | KEEP |
| `pages/merchant/predict/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:93` | KEEP |
| `pages/merchant/aftercare/detail/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:79` | KEEP |
| `pages/merchant/profile/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:105` | KEEP |
| `pages/merchant/marketing/index` | 否 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java:415` | KEEP |
| `pages/merchant/reviews/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:142` | KEEP |
| `pages/merchant/marketing/ai-insight/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:129` | KEEP |
| `pages/merchant/relation/index` | 否 | `chengyinhub-xcx/components/tabBar/index.js:11` | KEEP |
| `pages/merchant/citynode/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:164` | KEEP |
| `pages/merchant/citynode/create/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:175` | KEEP |
| `pages/merchant/decor/index` | 否 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantPrerequisiteServiceImpl.java:17` | KEEP |
| `pages/merchant/decor/ai-npc/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:200` | KEEP |
| `pages/merchant/decor/coop-setting/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:212` | KEEP |
| `pages/merchant/decor/gallery/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:223` | KEEP |
| `pages/merchant/decor/perks/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:234` | KEEP |
| `pages/merchant/coop-center/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:246` | KEEP |
| `pages/merchant/customer/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:257` | KEEP |
| `pages/merchant/game-node/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:291` | KEEP |
| `pages/merchant/customer/detail/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:278` | KEEP |
| `pages/merchant/verify-records/index` | 否 | `chengyinhub-xcx/pages/merchant/index/index.js:315` | KEEP |
| `pages/merchant/crm-campaigns/index` | 否 | `chengyinhub-xcx/pages/merchant/customer/index.js:915` | KEEP |
| `pages/merchant/team/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/logs/walk-merchant.json:305` | KEEP |
| `pages/coop/list/index` | 否 | `chengyinhub-xcx/pages/club/detail/index.js:818` | KEEP |
| `pages/coop/invite-detail/index` | 否 | `chengyinhub-xcx/pages/coop/list/index.js:728` | KEEP |
| `pages/coop/invite/index` | 否 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCoopController.java:318` | KEEP |
| `pages/coop/nearby/index` | 否 | `chengyinhub-xcx/pages/coop/invite/index.js:461` | KEEP |
| `pages/coop/finance/index` | 否 | `chengyinhub-xcx/pages/coop/settlement-detail/index.js:135` | KEEP |
| `pages/coop/settlement-detail/index` | 否 | `chengyinhub-xcx/components/cy/scene-merchant-profit/index.js:178` | KEEP |
| `pages/coop/withdraw/index` | 否 | `chengyinhub-xcx/subpackageMember/tixianjilu/tixianjilu.js:6` | UNSURE |
| `pages/coop/withdraw/records/index` | 否 | `chengyinhub-xcx/subpackageMember/tixianjilu/tixianjilu.js:6` | UNSURE |
| `pages/square/detail/index` | 否 | `chengyinhub-xcx/pages/mylike/mylike.js:313` | KEEP |
| `pages/search2/index` | 否 | `chengyinhub-xcx/pages/merchant/index/index.js:1026` | KEEP |
| `pages/search2/result/index` | 否 | `chengyinhub-xcx/pages/search2/index.js:135` | KEEP |
| `pages/searchmap/index` | 否 | `chengyinhub-xcx/components/cy/icon/spec.json:70` | KEEP |
| `pages/templatedetail/templatedetail` | 否 | `chengyinhub-xcx/pages/club/topic-story/index.js:239` | KEEP |
| `pages/templatedetail/topic-preview/index` | 否 | `chengyinhub-xcx/pages/templatedetail/shelf/index.js:324` | KEEP |
| `pages/address/address` | 否 | `chengyinhub-xcx/pages/activity/baoming/baoming.js:562` | KEEP |
| `pages/addressinfo/addressinfo` | 否 | `chengyinhub-xcx/pages/activity/baoming/baoming.js:581` | KEEP |
| `pages/shezhi/shezhi` | 否 | `chengyinhub-xcx/components/cy/scene-deep-link/index.js:34` | KEEP |
| `pages/shezhi/about/index` | 否 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java:899` | KEEP |
| `pages/shezhi/marketing/index` | 否 | `chengyinhub-xcx/pages/shezhi/shezhi.js:192` | KEEP |
| `pages/gerenziliao/gerenziliao` | 否 | `chengyinhub-xcx/pages/shezhi/shezhi.js:139` | KEEP |
| `pages/userinfo/userinfo` | 否 | `chengyinhub-admin/sql/migration_merchant_npc_menu_20260824.sql:116` | KEEP |
| `pages/mylike/mylike` | 否 | `chengyinhub-xcx/pages/shezhi/shezhi.js:154` | KEEP |
| `pages/crop/index` | 否 | `chengyinhub-xcx/app.js:470` | KEEP |
| `subpackageMember/free-explore/index` | 否 | `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js:188` | KEEP |
| `subpackageMember/character/index` | 否 | `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js:148` | KEEP |
| `subpackageMember/orient-score/index` | 否 | `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js:148` | KEEP |
| `subpackageMember/invoice/index` | 否 | `chengyinhub-xcx/pages/shezhi/shezhi.js:218` | KEEP |
| `subpackageMember/invoice-detail/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js:388` | KEEP |
| `subpackageMember/invoice-apply/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js:385` | KEEP |
| `subpackageMember/invoice-titles/index` | 否 | `chengyinhub-xcx/subpackageMember/invoice-title-edit/index.js:79` | KEEP |
| `subpackageMember/invoice-title-edit/index` | 否 | `chengyinhub-xcx/subpackageMember/invoice-titles/index.js:43` | KEEP |
| `subpackageMember/complaint/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1749` | KEEP |
| `subpackageMember/coupon-wallet/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1753` | KEEP |
| `subpackageMember/coupon-qr/index` | 否 | `chengyinhub-xcx/utils/scene-registry.js:48` | KEEP |
| `subpackageMember/coupon/coupon` | 否 | `chengyinhub-xcx/pages/merchant/index/index.js:278` | KEEP |
| `subpackageMember/couponInfo/couponInfo` | 否 | `chengyinhub-xcx/subpackageMember/coupon/coupon.js:203` | KEEP |
| `subpackageMember/mycanyu/mycanyu` | 否 | `chengyinhub-xcx/pages/activity/baoming/baoming.js:466` | KEEP |
| `subpackageMember/mycanyuinfo/mycanyuinfo` | 否 | `chengyinhub-xcx/utils/scene-registry.js:59` | KEEP |
| `subpackageMember/myinvite/myinvite` | 否 | `chengyinhub-xcx/utils/scene-registry.js:65` | KEEP |
| `subpackageMember/mytemplate/mytemplate` | 否 | `chengyinhub-xcx/pages/publish/temp/index.js:3203` | KEEP |
| `subpackageMember/order/order` | 否 | `chengyinhub-xcx/pages/topic/index/index.js:1069` | KEEP |
| `subpackageMember/orderinfo/orderinfo` | 否 | `chengyinhub-xcx/pages/activity/baoming/baoming.js:1036` | KEEP |
| `subpackageMember/points-history/index` | 否 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1386` | KEEP |
| `subpackageMember/report-mine/index` | 否 | `` | KEEP |
| `subpackageMember/report-detail/index` | 否 | `` | KEEP |
| `subpackageMember/appeal-mine/index` | 否 | `chengyinhub-xcx/pages/shezhi/shezhi.js:218` | KEEP |
| `subpackageMember/signup/index` | 否 | `chengyinhub-xcx/pages/play/index.js:2876` | KEEP |
| `subpackageMember/tixian/tixian` | 否 | `chengyinhub-xcx/utils/scene-registry.js:63` | KEEP |
| `subpackageMember/tixianjilu/tixianjilu` | 否 | `chengyinhub-xcx/pages/coop/withdraw/records/index.js:7` | KEEP |
| `subpackageRoam/citynode-code/index` | 否 | `chengyinhub-xcx/utils/scene-registry.js:50` | KEEP |
| `subpackageRoam/history/index` | 否 | `chengyinhub-xcx/utils/scene-registry.js:45` | KEEP |
| `subpackageRoam/poi-detail/index` | 否 | `chengyinhub-xcx/pages/merchant/profile/index.js:15` | KEEP |
| `subpackageRoam/session/index` | 否 | `chengyinhub-xcx/subpackageRoam/history/index.js:11` | KEEP |
| `subpackageRoam/nearby/index` | 否 | `chengyinhub-xcx/docs/audit-e2e-202609/report.html:106` | KEEP |
| `subpackageRoam/citystamp/index` | 否 | `chengyinhub-xcx/components/cy/proto-cam/index.js:1` | KEEP |
| `subpackageRoam/my-teams/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:743` | KEEP |
| `subpackageRoam/official-routes/index` | 否 | 入口待拍板(见 nav-route-exists 孤儿页豁免,2026-10-19 移植基线 366b846f) | KEEP |
| `subpackageRoam/official-route/index` | 否 | `chengyinhub-xcx/subpackageRoam/official-routes/index.js:32` | KEEP |
| `subpackageTools/capsule/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:721` | KEEP |
| `subpackageTools/monthly-recap/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:739` | KEEP |
| `subpackageTools/exploration-goals/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:720` | KEEP |
| `subpackageTools/anniversary/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:738` | KEEP |
| `subpackageTools/exploration-export/index` | 否 | `chengyinhub-xcx/pages/roam/index.js:740` | KEEP |

## 验证与未验证

- 清理脚本测试覆盖：清单与 app.json 相同、tabBar 全部保留、旧圈分享保留、四个 UNSURE 具有跨页测试、订阅配置外发入口存在、无孤立 Page+WXML。
- 完整前端单测、台账重建、lint:ui 的最终执行结果见同轮 `generic-gameplay-offline-anchor-1009.md`；没有生成台账提交。
- 未作线上外发历史核验、真实设备/多帐号验收或生产发布。owner 未确认的四个页面继续保留；后续删除必须先提供动态入口与历史消息迁移证据。
