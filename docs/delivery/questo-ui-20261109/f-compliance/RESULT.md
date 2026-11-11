# f-compliance 修复线结果（2026-11-11）

分支 `claude/f-compliance-20261111`，底座 `origin/launch/questo-20261110`（2256f79e）。范围：AUDIT-GAPS 阻断 D（第 1–4 项）、缺口第 4 组「合规入口」、第 10 组「套票玩家端」的文案清理。众筹送礼（D-5）不在本线范围，没有动。

## 结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| P0-14 AIGC 显式标识 | fixed | 用 AI 快速配置起草的主题（服务端 `cms_topic.publish_mode = ai_simple`），玩家和作者看到时都显示「AI 生成」：剧情与玩法页标签下方显示一枚 `cy-tag`；游玩开场眉题改为「城瘾 · 城市定向 · AI 生成」；快速配置草稿卡的元信息改为「AI 生成 · N 站」，展开后状态胶囊也带「AI 生成」。判据只有后端 `AigcLabel` 这一份，小程序 `utils/aigc-label.js` 是它的镜像。游玩接口新增 `topicAiGenerated` 字段 | `AigcLabel.java`、`PlayNodeQueryApplication.java`、`utils/aigc-label.js`、`pages/club/topic-story/*`、`pages/play/index.{js,wxml}`、`pages/publish/simple/*` | `AigcLabelTest`；`f-compliance-launch.test.js`；rig 截图 `P0-14-topic-story-*`、`P0-14-play-intro-*`、`P0-14-simple-*` | 真机字体与标签对比度 |
| P2-020 算法备案号公示位 | fixed | 新增配置键 `aigc.algorithm.filing-no`，用新迁移写入空值。`/api/config/features` 下发 `aigcFilingNo`，配置为空时下发空串，页面不渲染这一行。备案号显示在「关于」页版本号下方，以及 AI 生成入口（快速配置页）正文末尾 | `ApiConfigController.java`、`migration_20261111_aigc_filing_config_seed.sql`、`pages/shezhi/about/*`、`pages/publish/simple/*` | `NpcFeatureFlagsTest`（下发键集合、空串、去空白、读取失败按空处理）；rig 中写入示例值后截图 `P2-020-about-*`、`P2-020-simple-filing-*` | — |
| P0-10 AI 生成日志留存 | fixed | 在既有的每日 Quartz 任务 `dataRetentionCleanupTask.run()` 里加 `ai_generation_log` 步骤：分批删除，`chengyin.retention.ai-generation-days` 可配，默认 180 天，配成 ≤0 时跳过。该步骤失败不影响其他步骤，失败时照常告警。新迁移补了 `create_time` 索引。没有另建任务，sys_job 也不用新增登记 | `DataRetentionCleanupTask.java`、`DataRetentionServiceImpl.java`、`IDataRetentionService.java`、`AiGenerationLogMapper.{java,xml}`、`migration_20261111_ai_generation_log_retention_index.sql` | `DataRetentionCleanupTaskFailureTest`（覆盖默认 180 天、改配置、≤0 跳过、失败后其余步骤仍执行）；`DataRetentionServiceImplTest`；`AiLogMapperSqlTest` | 没在真 MySQL 上跑删除（单测用 Mock） |
| P0-30 NPC 对话 token | fixed | OpenAI 兼容客户端从上游响应读取 `usage.prompt_tokens / completion_tokens`。网关在 AI 生成日志和返回结果里都记同一组数：上游给了 usage 就用实数，没给就沿用原来的估算。NPC 对话成功时把这组数写进 `npc_chat_log`，失败或结果未知时仍写 null（未知不当作 0） | `LlmClient.java`、`OpenAiCompatibleLlmClient.java`、`AiGatewayResult.java`、`AiGatewayServiceImpl.java`、`NpcChatService.java` | `OpenAiCompatibleLlmClientUsageTest`、`AiGatewayServiceImplTest`（新增 2 例）、`NpcChatServiceTest`（新增 1 例），AI 与 NPC 相关测试 271 例全部通过 | 真模型的 usage 字段 |
| OFF-AI-DEL-01 设置页 AI 对话记录 | fixed | 设置页「隐私与定位」下面加一行「AI 对话记录」，点开是动作菜单：导出对话记录会把文本复制到剪贴板；清除对话记录标红，先弹危险确认（沿用 `ai.npc.chatData.delete` 的文案），再调用原删除接口，回执幂等，用的 bucket 与 NPC 面板相同。接口身份只取登录态，所以只会删本人的记录。组件放在 subpackageMember，经 componentPlaceholder 异步加载，不进主包。**rig 实测发现原有缺陷**：NPC 面板和新入口都用表单编码发送删除请求，而接口按 `@RequestBody` 只收 JSON，结果是 `HttpMediaTypeNotSupportedException`，返回「没能完成」。两处都已改为 JSON | `subpackageMember/components/cy/ai-chat-records/*`、`pages/shezhi/shezhi.{wxml,js,json}`、`subpackageMember/components/cy/npc-chat/index.js` | `f-compliance-launch.test.js`（导出、空记录、失败、确认后才删、请求体只含 requestId、JSON 头）；rig 实测：库里 2 条 → 0 条，截图 `OFF-AI-DEL-01-*`（含「已清除 2 条对话」） | 真机剪贴板提示 |
| T-37 紧急求助 | fixed | 游玩工具菜单第一行（联系主办之后）加「紧急求助」。点开是 `cy-option-sheet` 动作菜单，三项：拨打 110、拨打 120、复制我的位置。拨号用 `wx.makePhoneCall`，只允许这两个号码。位置优先用本局已有定位，没有就前台取一次 gcj02，然后复制成「纬度/经度 + 高德标点链接」文字。没有说明文字，也没有红色。预览态不显示这一行 | `pages/play/utils/play-sos.js`、`pages/play/index.{js,wxml}` | `f-compliance-launch.test.js`；rig 截图 `T-37-menu-*`、`T-37-sheet-after` | 真机拨号盘、定位授权弹窗 |
| TEAM-REPORT-01 附近队伍卡举报 | fixed | 附近队伍半屏卡底部加次级文字链「举报队伍」，接上既有的 `team-report-sheet`（POST `/api/team/report`），和队伍详情页是同一个组件。队长看自己的卡时不显示 | `subpackageRoam/nearby/index.{js,json,wxml,wxss}` | `f-compliance-launch.test.js`；rig 截图 `TEAM-REPORT-01-*` | — |
| 第 10 组 套票页文案 | fixed | 玩家页去掉内部编号（原目标、原预约、原退款申请、权益行号、权益编号）和内部用语（回读、原请求、请求号、不代表…、试算、供给、履约、台账、平台复核口径等），状态改成白话（待使用 / 已使用 / 未到店 / 退款处理中…），去掉解释性小字。订单号保留。后端透传的 `res.msg` 原样不改 | `subpackageMember/free-explore/index.{wxml,js}`、`subpackageMember/utils/free-explore-bundle.js` | `free-explore-bundle-page.test.js` 42 例（新增一例扫描 wxml、js 和 wire 的字面量里是否还有内部用语）；rig 截图 `FX-COPY-*` | 开售后真实台账数据 |

## 闸门

- 后端：`mvn -q -DskipTests install` 通过；涉及模块的单测 271 例 + 6 例全部通过（0 失败）。
- 迁移：`ci/migration_gates.py sql`、`dep-order`、`ci/migration-safety-check.sh` 全部通过。两份新迁移都已登记进 `ci/migration-dependencies.json`，没有改动任何已有迁移。
- 小程序：`node chengyinhub-xcx/tests/lint/ui-integrity.js` → **UI-GATE-0:PASS**，没有新增基线条目（附近页方法追加在文件末尾，避免行号位移）。action-ledger 已用 `--write` 重建，冻结计数逐条加了注释（tap 3018 → 3021，事件合同 2979 → 2986，合计 5997 → 6007），游玩页两条按行号定位的条目只是位移（322 → 323，534 → 535）。GET 授权矩阵登记了新导出调用点（127 → 128）。
- 全量单测：见文末补记。
- 主包：`mainpkg-size-report` 合计 3221KB，与底座同一档，按 0.633 折算约 2039 KiB，低于 2048。新增的组件和工具函数都放在分包，主包只多了 `utils/aigc-label.js`（约 0.6KB）和设置页、关于页的几行。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。diff 已扫描过，不含密钥。

## 截图

全部放在 `shots/`，390×844，JPEG q70，用走查 rig 拍摄：`claude/cloud-walk-20261101-rig` 加 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（OSS 用占位值），再加 club 分支的 `harness-patches.diff`。后端 jar 由本分支构建。before 用底座小程序包，after 用本分支小程序包；同一个库、同一个身份、同一个页面状态。每张截图我都看过。

rig 里做过的夹具和注入（HARNESS，只动 rig 的一次性库，不碰产品代码）：

- 主题 990013 / 990014 的 `publish_mode` 设为 `ai_simple`；备案号写入示例值「网信算备310000000000000000001号（示例）」；990014 由 staging 的 `free_orient_theme.py` 生成。
- 附近页的夹具主题没有地理坐标，所以队伍卡是用页面自己的 `openTeam` 加注入行打开的。
- 快速配置草稿态用 `setData` 注入，与 `scripts/shot-matrix.js` 的 E18 同形。
- 游玩页 rig 的微信隐私授权层收不掉，用 `privacyGateShow=false` 绕过；游玩工具菜单用页面自己的 `toggleSessionTools` 打开。
- 套票台账开关在 rig 里是关的，所以用页面自己的 `apiRequest` 回放一份夹具回包，再走页面原本的解析和渲染。

## 待你决定（已按默认实现）

1. **备案号的值**：拿到生成式 AI 备案或登记号后，在后台「参数设置」填入 `aigc.algorithm.filing-no`，要写完整写法，例如「网信算备……号」；页面不会自动加前缀。默认留空，不显示。
2. **「AI 生成」的判定范围**：只标 `publish_mode = ai_simple` 的主题（AI 快速配置起草的）。作者后来全文重写过，标签也照样保留，偏保守。专业编辑器里没有服务端字段能区分「某一段是 AI 写的」，所以这类内容目前不标；编辑器把 AI 草稿采用为简介时，会追加「[AI 辅助生成]」，这条原有逻辑保留不变。NPC 对话面板本身就是 AI 角色，没有另外改。
3. **留存天数**：默认 180 天，需要与线上《隐私政策》写的留存期一致。如果政策写的不是 180 天，生产环境配置 `chengyin.retention.ai-generation-days` 即可。
4. **紧急求助的位置分享**：微信小程序不能直接向 110/120 推送位置，所以做成「复制位置文字」，文字里带高德标点链接，玩家可以粘贴给任何人。如果你更想用 `wx.openLocation` 打开地图再从里面转发，改动很小。
5. **套票页订单号**：内部 ID 都去掉了，但保留了「订单号」（用户找客服时要报这个号）。「权益编号」已删除；如果线下核销要用这个编号，需要告诉我加回来。

## 跨线

- `subpackageMember/components/cy/npc-chat/index.js` 里的删除请求改为 JSON（属于原面板的缺陷，只改了这一处，两行）。
- 游玩页（l-play 的地盘）只加了一行菜单、一个 option-sheet 和开场眉题，Page 合并时多展开了一个 `playSos`，没有动别的行。

## 补记

全量小程序单测：见 PR 描述（本文件提交时正在跑）。

STATUS: COMPLETE
