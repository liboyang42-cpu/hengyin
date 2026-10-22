# LANE H2 — 旧 10-15 修复 PR #33 / #34 / #36 / #44 逐项核对

- 对照基线：live `origin/master` = `063a6c3c`（本次核对时与参考 SHA 一致）
- 旧 PR 头：#33 `f01b5c06`、#34 `a9d1dcda`、#36 `8822ab38`、#44 `5752ee6f`（均 open、未合并，base 为 2026-10-15 集成分支）
- 分支：`feature/lane-h2-oldpr-33-34-36-44-20261022`
- 方法：读每个 PR 的说明与 diff（#44 diff 超 300 文件，按其标题所列两项 + 对应 CORE 证据文档核对）；把旧 PR 头的小程序单测原样复制到临时目录，只改模块路径后跑在 master 代码上（临时文件已删除，未提交）；Java 侧比对旧头与 master 的测试/实现文件。
- 范围说明：#36（254 文件）与 #44（410 文件）的分支还带了大量其他 lane 的长跑切片（优惠券、排期、结局、隐私等）。本 lane 只核对 PR 标题与说明里写明的需求，其余归其他并行 lane / 旧 PR lane。

## 结论计数

| 状态 | 数量 |
| --- | --- |
| COVERED（master 已有实现 + 回归测试） | 43 |
| MISSING → 本 PR 补回归测试（行为已在 master，仅缺测试） | 2 |
| MISSING → NEEDS_OWNER_APPROVAL（不改行为） | 1 |
| 观察项 → NEEDS_OWNER_APPROVAL（不是旧 PR 引入，与本 lane 定位约束冲突） | 1 |
| 合计 | 47 |

本 PR 没有改任何生产代码，只新增两份回归测试和本文档。没有新 DDL、新依赖，也没有改 C00 保留文件。

## #33 退款确认与账号代次（E05）

退款金额、端点、退款政策均未改动；本 lane 只补测试。

| # | 需求 | 状态 | master 证据 |
| --- | --- | --- | --- |
| E05-01 | 订阅模板去重；授权结果按业务 key 对应 | COVERED | `chengyinhub-xcx/utils/subscribe.js:43` selectTemplates；`tests/unit/longrun-e05-01.test.js`（与旧头一致，4/4 过） |
| E05-02 | 部分授权 / 同步异常（静态反证，无需改码） | COVERED | 同 E05-01 测试 |
| E05-03 | 订单历史退款：确认弹窗与请求按账号/组件代次隔离 | COVERED | `subpackageMember/components/cy/scene-member-order-history/index.js:285-330`；`tests/unit/longrun-e05-03.test.js`（master 版是旧测试的超集，另加 UNKNOWN 状态刷新 `:91`、免费单 cancel-free） |
| E05-04 | 参与详情退款上下文隔离 | COVERED | `subpackageMember/components/scene-member-participation-detail/index.js:51`、`:223`；`tests/unit/longrun-e05-04.test.js:90`（UNKNOWN 刷新） |
| E05-05 | 收藏换号晚回包（静态反证） | COVERED | `tests/unit/b01-lifecycle-account.test.js`；本 PR 新增收藏用例（见下） |
| E05-06 | 收藏分页失败不推进游标 | COVERED | `tests/unit/longrun-e05-06.test.js`（master 版改走社区分页 hasMore） |
| E05-07 | 取消收藏与读取竞争 | COVERED | `tests/unit/longrun-e05-07.test.js` |
| E05-08 | 收藏卸载与回显（静态反证） | COVERED | `tests/unit/b01-lifecycle-account.test.js` |
| E05-09 | 订单历史列表卸载代次 | COVERED | `tests/unit/longrun-e05-09.test.js`（与旧头一致） |
| E05-10 | 参与详情读取上下文 | COVERED | `tests/unit/longrun-e05-10.test.js`（与旧头一致） |
| E05-11 | 订单历史同实例列表请求（静态反证） | COVERED | `scene-member-order-history/index.js:132`、`:158` 在途去重；e05-09 测试 |
| E05-12 | 收藏返回刷新不被旧读取吞掉 | COVERED | `pages/mylike/mylike.js:65`；`tests/unit/longrun-e05-12.test.js` |
| E05-13 | 未配置订阅 key 不读原型成员 | COVERED | `tests/unit/longrun-e05-13.test.js`（与旧头一致） |
| E05-14 | Encounter 冻结键损坏不得实时回退 | COVERED | `E05_14Test`（与旧头一致）；`PlayEncounterServiceImpl` 是旧头的超集 |
| E05-15 | 漫游分享文本截断 UTF-16 边界 | COVERED | `E05_15Test`（一致）；`RoamSharePrivacyService` 一致 |
| E05-16 | SharePrivacy 坐标/点列白名单（只读支援） | COVERED | `RoamSharePrivacyService`（一致） |
| E05-17 | Encounter 当前局走 findCurrent | COVERED | `E05_17Test`（一致）；`TopicRouteSessionScope.findCurrent` 一致；`ApiPlayEncounterControllerTest` 为超集 |
| B01-R1 | 详情/历史/参与三处：确认后只发一次 cancel-refund，之后换号不再写、不出副作用 | **MISSING 测试 → 已补** | 新 `tests/unit/b01-refund-account-regression.test.js` |
| B01-R2 | 三处：换号 / 组件 detach 后旧确认不能发退款 | **MISSING 测试 → 已补** | 同上（变异验证：删掉订单历史 `isCurrent()` 守卫后该用例失败） |
| B01-R3 | 三处：换号 / detach 后迟到的退款成功回包不 toast、不刷新、不改 data | **MISSING 测试 → 已补** | 同上 |
| B01-F1 | 收藏：行重排后取消收藏按捕获的稳定 ID 移除 | **MISSING 测试 → 已补** | 同上（走 `tests/helpers/community-page-seam`） |
| B01-F2 | 收藏：A 账号完成回调不能解锁 B 账号对同一 ID 的在途锁 | **MISSING 测试 → 已补** | 同上 |
| B01-F3 | 收藏：A 打开的主题确认框切到 B 后不能发送 | **MISSING 测试 → 已补** | 同上 |
| S-1 | 退款取消确认手势内同步请求 `refund`/`refundRejected` 订阅授权 | **MISSING → NEEDS_OWNER_APPROVAL** | 旧 PR 在 `scene-member-order-history/index.js` 等处有 `subscribe.request(['refund','refundRejected'])`。master 从公开快照导入起就没有这段，`longrun-e05-03/04` 明确断言 `subscriptions == []`（"subscriptions stay disabled"）。退款本身照常发出。是否恢复退款通知订阅是产品/合规决定，本 lane 不动 |

> 计数说明：B01-R1..R3、B01-F1..F3 是同一个缺失测试文件里的用例，表头计数里算作 1 项 MISSING（已补）。

## #34 定位取消身份与常亮失败恢复（E06）

| # | 需求 | 状态 | master 证据 |
| --- | --- | --- | --- |
| E06-01 | 同 context 重复注册保留身份 | COVERED | `tests/unit/longrun-e06-01.test.js`（旧头版本跑在 master 上 4/4 过） |
| E06-02 | 页面暂停优先于系统中断恢复（无需改码） | COVERED | `tests/unit/b01-lifecycle-account.test.js`、e06-01 |
| E06-03 | 常亮失败可由后续操作重试；忽略过期回调；不自动重试 | COVERED | `pages/play/utils/audio-session.js:60-75`（模块已从 `utils/` 移到 `pages/play/utils/`）；`tests/unit/longrun-e06-03.test.js`、`audio-session.test.js` |
| E06-04 | poller 停止/重启在途世代（无需改码） | COVERED | `pages/play/utils/poller.js:15`；`tests/unit/b01-platform-lifecycle-regression.test.js`、`poller.test.js` |
| E06-05 | poller 延迟配置边界 | COVERED | `pages/play/utils/poller.js:9`；`tests/unit/longrun-e06-05.test.js` |
| E06-06 | 最近定位拒绝无效类型；无效 save 不覆盖已存坐标 | 代码 COVERED，**测试 MISSING → 已补** | `utils/location/last-location.js:18`；新 `tests/unit/longrun-e06-06.test.js`（4/4） |
| E06-07 | 选址取消/拒权链（无需改码） | COVERED | `utils/location/location-manager.js`（与旧头一致） |
| E06-08 | 定位 owner 改独立领取身份；释放后同 key 重获不复活旧回调 | COVERED | `utils/location/bg-tracker.js:26`、`:140`、`:151`；`tests/unit/longrun-e06-08.test.js`（master 另加 revoke 用例） |
| E06-09 | GPS 启动 API 同步异常收口 | COVERED | `tests/unit/longrun-e06-09.test.js`（一致） |
| E06-10 | 共享定位消费者异常隔离 | COVERED | `tests/unit/longrun-e06-10.test.js`（一致） |
| E06-11 | 失败分发期间同 key 直接重获 | COVERED | `tests/unit/longrun-e06-11.test.js`（一致） |
| E06-12 | stop 同步完成链异常传播 | COVERED | `tests/unit/longrun-e06-12.test.js`（一致） |
| E06-13 | 选址与当前定位坐标类型 | COVERED | `tests/unit/longrun-e06-13.test.js`（一致） |
| E06-14 | Share 存储重放 / 50 上限（无需改码） | COVERED | 静态反证；`ApiRoamShareController` 是旧头的超集 |
| E06-15 | 分享撤销与发布交错（只读审计） | COVERED | 同上 |
| E06-16 | 匿名分享读取与 GONE 合同 | COVERED | `E06_17Test`（GONE 文案断言） |
| E06-17 | 分享读库故障保留可重试的传输状态 | COVERED | `E06_17Test`（与旧头只差 token 字面量写法） |
| L-1 | 观察项：定位需"前台按需、onHide/onUnload 停" | **NEEDS_OWNER_APPROVAL** | play/roam 行程用 `bg-tracker` 后台定位，按设计在 onHide **不**停（`utils/location/bg-tracker.js:10-12`、`pages/play/index.js:888-892`），只在 onUnload/行程结束时 release（`pages/play/index.js:4518`、`pages/roam/index.js:930` → `:3838`）。其他页面只用一次性的 `wx.chooseLocation`（前台按需）。这是 #34 之前就有的设计，#34 没引入也没改。本 lane 不改；如果要严格执行"onHide 必停"，需要负责人决定是否放弃锁屏行程定位 |

## #36 取消/下架活动的引用卡

| # | 需求 | 状态 | master 证据 |
| --- | --- | --- | --- |
| R36-1 | 公开帖子不再返回 publish_status=0 或 cancel_time 非空的 ACTIVITY 引用卡 | COVERED | `chengyinhub-system/src/main/resources/mapper/business/CommunityPostMapper.xml:429-432`（selectReferences 的 NOT EXISTS） |
| R36-2 | 只隐藏卡片，帖子正文和存储的快照不动 | COVERED | `E02_03Test` 的 cancelled/offline 用例 + `assertStorageUnchanged` |
| R36-3 | 报名变化、目标字段编辑不改历史投影 | COVERED | `E02_03Test.activeCardKeepsHistoricalProjectionDespiteTargetEditsOrRegistrationChanges` |
| R36-4 | 其他引用类型、缺目标、NULL 状态照旧 | COVERED | `E02_03Test.unrelatedTypesMissingTargetsNullStatesAndOriginalRowGatesRemainUnchanged` |
| R36-5 | 后续项：E02_01/E02_11/E02_06/E02_25MySqlIT/B05CommunityVisibilityMySqlIT 的 scratch schema 补 cms_activity | COVERED | 五个类在 master 里都会建 `cms_activity` |

## #44 协作者输入与取消幂等

| # | 需求 | 状态 | master 证据 |
| --- | --- | --- | --- |
| R44-1 | create / FULL update 在任何聚合写入前拒绝 collaboratorIds 里的 null/≤0（IllegalArgumentException，默认 AjaxResult 500） | COVERED | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java:5980-5989`；`OWN_C03_14Test`（10 项，与旧头一致） |
| R44-2 | 保留重复正 ID 及顺序、缺席/[] 语义、WHITELIST 忽略、owner 与完整性闸先于名单检查 | COVERED | `OWN_C03_14Test`、`OWN_C03_12Test`（一致） |
| R44-3 | TopicUpdateByOwnerTest 成功插入夹具修复 | COVERED | master 的 `TopicUpdateByOwnerTest` 是旧头的超集；`TopicCreateBusinessErrorPassthroughTest` 存在 |
| R44-4 | 显式 type=0 取消点赞，DELETE 0 行也算成功（幂等） | COVERED | `chengyinhub-admin/.../ApiTopicController.java:1429-1441` 改成 desired-state `setLiked`，返回值不再决定成败；`TopicLikeRetryDirectionV2Test.java:36-46` 断言连续两次 type=0 都返回 200（mock DELETE 返回 0） |
| R44-5 | 空 type 的旧切换语义、owner 过滤、DELETE 异常向外传播 | COVERED（合同已被取代） | master 把空 type 当成显式点赞（不再切换），见 `TopicLikeRetryDirectionV2Test`；owner 过滤在 `CmsTopicLikeMapper.xml:99-101`；`CmsTopicLikeServiceImpl.java:24-30` 不吞异常 |

## 本 PR 的验证证据

- `node --test chengyinhub-xcx/tests/unit/b01-refund-account-regression.test.js` → 18 pass / 0 fail
- `node --test chengyinhub-xcx/tests/unit/longrun-e06-06.test.js` → 4 pass / 0 fail
- 变异检查：去掉 `scene-member-order-history/index.js` 确认回调里的 `!isCurrent()` 守卫后，"history: stale confirmation after account cannot dispatch refund" 失败；已恢复原文件
- `npm run test:unit`（chengyinhub-xcx 全量）→ 13496 pass / 0 fail / 0 skip
- 旧头测试跑在 master 上：#34 的 11 个文件全过；#33 里失败的 8 处都能解释——6 处是"订阅已停用"的合同变化（S-1），2 处是收藏帖文改走社区客户端（换上 seam 后通过）。e05-03/04/06/07/12 的旧版失败是合同演进，master 已带对应的新版测试
- Java：本 PR 没改 Java；Java 侧证据是上面列出的 master 现有测试。CI 结果见 PR
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有重新生成 ledger/baseline

## 需要人工在微信开发者工具 / 真机上确认

1. **退款取消（真机，用测试订单）**：已支付订单在"订单历史""订单详情""参与详情"三处各点一次取消 → 确认 → 只发一次 cancel-refund；确认框打开时切换账号再点确认，不能发出请求；弱网下 UNKNOWN 结果会刷新订单状态（出现"取消结果暂未确认，正在刷新订单"）
2. **退款通知订阅（S-1，等负责人决定）**：当前取消退款不会弹订阅授权。如果决定恢复，需要真机验证订阅弹窗在确认手势内同步弹出，并且不阻塞退款
3. **我的收藏（DevTools）**：帖文/主题列表取消收藏后列表正确移除；切换账号后旧请求回包不影响新账号
4. **定位（真机）**：play 行程开始后锁屏仍持续定位，通关/退出/返回（onUnload）后系统状态栏的定位图标消失；roam 同理；选址页取消/拒权能回到原页面不报错；拒绝定位权限后再授权能重新获取
5. **屏幕常亮（真机）**：全屏故事/长音频期间不熄屏；`setKeepScreenOn` 失败后再操作一次能重新申请；离开页面后恢复正常熄屏
6. **活动引用卡（DevTools + 测试库）**：帖子引用的活动被取消或下架后，帖子详情不再显示活动卡，正文仍在

## NEEDS_OWNER_APPROVAL

1. **S-1 退款取消的订阅授权**（#33）：master 没有在退款取消确认时请求 `refund`/`refundRejected` 订阅，测试也明确把这当成现行合同。和退款通知相关，按本 lane 规则不改退款相关行为，请负责人决定是否恢复
2. **L-1 后台行程定位不在 onHide 停**（与 #34 无关的既有设计）：play/roam 锁屏行程定位在 onHide 不停，只在 onUnload/行程结束停。和本 lane 的"onHide/onUnload 停"要求冲突，请负责人决定以哪个为准；本 PR 不动
