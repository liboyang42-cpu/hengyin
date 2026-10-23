# Agent C：玩法命令身份与当前小程序候选

结论：第一批代码候选已完成，轻量 Node 回归 18 个文件、191 项实际通过、0 失败、0 跳过。Java 编译、Java 单测、真实 MySQL、微信模拟器/真机尚未运行，本候选未达到正式集成/部署验收。高级玩法 Advanced act/replay 的请求指纹漏洞尚未实现，已提交精确 schema/迁移/测试设计，仍是阻塞，不能用本次客户端修复抵消。

用户经协调者于 2026-10-23 明确批准“已审查、秘密扫描后的隔离 codex 候选可先提交/推送供 GitHub-hosted 验证”例外；未通过不得正式合并或部署。没有在云 VM 自建/运行 Java 后端、MySQL、Redis，没有请求/复制 VPS 或生产凭据。

## 基线与隔离

- 仓库：`https://github.com/liboyang42-cpu/hengyin.git`，原分支 `work` 干净，实际 HEAD 与指定基线一致：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 独立工作树：`/workspace/hengyin-c`；任务分支：`codex/architecture-day-c-play-command`。原工作树 `/workspace/hengyin` 未切分支、未回退/清理他人修改。
- 代码补丁身份：`candidate.patch.gz`（解压后补丁），SHA-256 `8b51531146c7666be4a7ed6e169f68794e124414f3189df63cc3894bcc7db86b`。完整候选 commit SHA 以远端分支及本会话最终回报为准；补丁供审阅/校验，已应用到候选分支，切勿在同一提交上重复 apply。
- 未迁入私有 chengyin 的历史、分支、日志、备份或秘密。没有创建额外 Agent/会话。环境曾短暂断开，恢复后核对工作树和 HEAD 均保留。

## 调用链与失败机制

1. 旧章节入口 `pages/play/index.js leadUnlock` → `/api/club/lead/unlock-chapter` → `ensureState` → 当前章索引 +1 → 无条件 update。同一点击丢响应后重试会继续下一章。
2. 当前基础动作调用者：`pages/play/index.js` 的 `arrive/checkin/submitGame`，及 `pages/play/merchant/index.js` 的到店扫码/答题。其余小程序命中为测试、审计脚本和已删除旧路径的说明；没有发现第三个生产写调用者。
3. 原控制器在共享 ANSWER/SCAN/GPS 缺 routeActionId 时生成 UUID；个人 `recordPlayerArrival` 也随机生成，且可缺 expectedVersion。重试不能绑定原意图；原小程序 `_retriedRouteWriteBody` 还把同一 ID 的 expectedVersion 换成新值。
4. 旧个人 arrival 回执只检查节点，未绑定 kind/expected。个人 gameplay/theme 的摘要未绑定 expected，且分隔符串接可能有输入边界歧义。
5. H 补充的导演链：`director.js executeAction` → adapter submit → 异步结果 → 动态当前账号 key 的清理；UNKNOWN retry 又动态保存当前账号 key。A→B 会误删 B 的 pending 或用 B token 重发 A 命令。H 工作树在本执行器不可见，已独立补负例。
6. Java 直接调用者/旧测试调用搜索保存在 `evidence/java-caller-search.txt`。新增 HTTP 合同不保留危险缺字段执行 fallback；旧 Java 重载只显式拒绝，存量直接调用的测试夹具需由受控全套 Java 验证继续核对，不能宣称它们已绿。

## 修复合同

章节：强制 commandId、expectedChapterId、targetChapterId；显式 `expected=0` 代表尚未放行。先锁 club（与领队委派/交接同一权威锁），再锁活动、校验当前领队、读原回执；新写还须活动可用、lead status=2、原章一致、目标为同主题相邻下一章。条件 UPDATE 同时校验活动归属、原缓存领队、期望章/状态及 SQL 当前有效领队（含委派到期），回执终态与章变化在同一事务提交。回执失败抛异常回滚。原已执行结果先于当前章/状态冲突；移交后的旧队长不能借原 ID 读取受限结果。导演 runtime 的场次不能从旧章节入口推进另一份 state。

基础动作：显式节点、routeActionId、expectedRouteVersion、routeSessionId，团队动作还绑定原 runtimeInstance/authorityEpoch。新增只读 `/action-context` 为首次意图提供权威身份，客户端若已有原 session 与返回不一致直接拒绝，不绑定另一局。显式无 runtime 的 scope 使用返回的 session=0/version=0。POST 在验证读取授权后优先读原回执，获取业务聚合锁后再次回读并发赢家，随后才判断原版本/实例/epoch。完整请求语义以 SHA-256 绑定，原回执、尝试日志/业务效果和终态同事务，错误答案同 key 也只执行一次；新 attempt 新 ID 可再次作答。缺字段在任何业务效果前拒绝。复用 `game_command_receipt`，独立 `play:` / `lead:` 命名空间，未新建全站 AOP/表/共享接口。

个人运行时：移除随机 arrival ID fallback，强制期望版本；arrival/theme/gameplay 的现有回执摘要用版本化 JSON tuple + 完整 SHA-256 绑定原输入边界与 expected。新 arrival 身份不按 kind 分开释放；发现原旧 namespace 的同身份行会拒绝，不把历史身份当成未执行。旧回执无法推导原 expected/input 的，保留且拒绝猜测性重放。

当前小程序：同一意图冻结原目标、输入和 expected；持久化只保存主体/场次/runtime 下的脱敏身份索引，原答案、扫码正文、坐标、照片地址不落盘。跨页共享原内存请求；重启从原身份读回执，不从新输入重构请求。网络/HTTP/缺终态标记均保留 UNKNOWN。缺回执不证明未执行；原错误答案终态才解锁下一 attempt。服务端明确 intentNotExecuted 后，须用户确认才释放原身份；确认弹窗也核验原账号/场次/runtime。章节当前进度不能代替原命令回执，成功回执须匹配 commandId/原 expected/target。

账号隔离：导演 key 显式绑定原 owner，清理还比较原 requestId。请求、回执、重试、投影、弹窗及离线起点绑定 owner、授权快照（仅内存比较）、现有会话失效代次/授权签发时间、场次、页面代次和 pending 引用。异步先核验，换号后保留 A 未知，不写入/删除 B key，不用 B token 重发；A→B→A、页面移出栈、同页重新 init 都拒绝旧回调。玩法页同样校验账号与登录上下文。现有 `_featureFlagsEpoch` 会在登出/账号变更/重新登录与 token 刷新时失效；普通 feature flags 重载也会保守阻断旧页面，需重新进页核对原回执。

## 已实际执行的验证

- 先红后绿：新意图测试最初 3 项实际失败；H 对应导演负例最初 3 项实际失败。均未跳过或加 baseline，修复后保留并扩展。
- 最终 Node：18 文件，191 项 PASS、0 FAIL、0 SKIP。逐文件真实命令、退出码、条数见 `evidence/node-results.json`；完整输出在同目录各 `.log`。主意图专属 7 项、导演隔离专属 5 项；原导演合同 43 项，恢复合同 30 项均实际通过。
- `node --check`、`git diff --check` 通过；ClubLeadMapper.xml 用 Python XML parser 解析通过。后者不是 MyBatis 映射执行或 MySQL 验证。
- `git apply --reverse --check`（解压后的补丁） 通过，用于核对补丁与当前候选工作树一致；未回退工作树。
- 提交前秘密扫描结果另见 `evidence/secret-scan.log` 与 `evidence/secret-scan.json`；扫描工具官方包校验后仅在 `/tmp` 使用，不新增项目依赖、不扩大 `.gitleaksignore`。

## 尚未执行 / 合并门槛

Java 编译、Java 单测、MySQL IT、Redis/HTTP/真机链路全部 NOT RUN，不能把 mock/Node/XML 结果当成真实事务或数据库证据。已编写 `ClubLeadChapterIntentTest`、`PlayCommandIntentTest`、`ClubLeadChapterIntentMySqlIT`，并更新章节流、个人 gameplay 与 template slot 专属夹具。真实 IT 覆盖同键并发/顺序、回执写失败全回滚、领队交接先提交；只操作本类独立的 CI 测试库，未执行。

集成人需在已批准 VPS 或 GitHub-hosted 路径执行仓库编译与上述 Java 类，再核对旧直接重载测试夹具及全套相关回归；共享基础动作并发、跨运行时切换、旧 epoch 和奖励零漂移还需在真实后端/MySQL 链路验证。本会话不自行设置 runner、启动 Java/数据库或改 CI。

Advanced act/replay 指纹仍阻塞：见 `advanced-fingerprint-design.md` 的一列 schema、旧 NULL 保留式拒绝、预检/迁移/回退和测试设计。根独占 PhotoCheckVisionService、其测试及已列 AI quota/照片 runtime 夹具全部未改。

## 迁移、发布与回退限制

本批无新增 DDL、无新依赖、无根 pom/CI/共享接口/schema 修改。上线前仍需只读确认 `game_command_receipt` 的 InnoDB、actor/request 唯一键和现有 topic_route_action 唯一键就绪；缺表/缺键不得绕过。

新小程序与后端要求同批准备；缺字段/旧语义不再执行。历史个人回执无新指纹无法安全回填；部署前按 scope 只读统计/核验未完结意图，保留全部回执/账本/审计，不清表不删历史。响应正文的保留策略不得删除业务唯一身份；本次搜索未发现生产代码清理这两个回执表的路径。

产生新命令后不能回退到随机 ID、当前章+1、换 expected 或无输入指纹校验的实现；如需回退，停止新写并保留回执读取/调证，不能通过释放 key、清空 pending/数据库或切到另一 runtime 恢复可写。进程在发出请求前崩溃也可能留下脱敏索引，此时查无回执继续 UNKNOWN，不能据此自动造新意图；这是可用性限制，不是已证明失败。

## 文件清单

代码/测试共 25 文件，逐文件 SHA-256 见 `files.json`。本目录报告、补丁、设计与日志为附属交付物，均在 C 独占目录。

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubLeadController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/GameplayTemplateSlotRuntimeTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PersonalGameplayRuntimeServiceTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ClubLeadFlowTest.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/ClubLeadMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java`
- `chengyinhub-system/src/main/resources/mapper/business/ClubLeadMapper.xml`
- `chengyinhub-xcx/pages/club/topic-detail/director.js`
- `chengyinhub-xcx/pages/play/index.js`
- `chengyinhub-xcx/pages/play/merchant/index.js`
- `chengyinhub-xcx/pages/play/utils/play-route-state.js`
- `chengyinhub-xcx/tests/unit/club-game-director-contract.test.js`
- `chengyinhub-xcx/tests/unit/longrun-own-c01-24.test.js`
- `chengyinhub-xcx/tests/unit/longrun-own-c01-26.test.js`
- `chengyinhub-xcx/tests/unit/longrun-own-c01-34.test.js`
- `chengyinhub-xcx/tests/unit/play-generic-flow.test.js`
- `chengyinhub-xcx/tests/unit/play-proof-receipt-contract.test.js`
- `chengyinhub-xcx/tests/unit/play-request-recovery.test.js`
- `chengyinhub-xcx/tests/unit/v4-scan-cancel.test.js`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ClubLeadChapterIntentMySqlIT.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ClubLeadChapterIntentTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlayCommandIntentTest.java`
- `chengyinhub-xcx/tests/unit/architecture-day-c-director-isolation.test.js`
- `chengyinhub-xcx/tests/unit/architecture-day-c-intent.test.js`
