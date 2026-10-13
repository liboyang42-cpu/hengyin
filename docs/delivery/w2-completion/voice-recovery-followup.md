# W2 F0226 商家 NPC 声音请求恢复补交

基线为公共源码 `efba970132d25c3285071e6e1ec909e013b83804`，工作树为 `w2-voice-recovery`。本补交仅覆盖商家声音登记、状态查询、重置及必要的小程序恢复；源码和自动测试证据不代表线上、供应商或真机验收。暂停的 22 项新功能仍未引入。

## 交付行为

- **原子领取**：使用现有 Spring/MyBatis 事务管理器锁定当前商家 NPC，校验原请求后推进 `voice_generation`、写入 `npc_voice_request`。事务成功提交后才调用 `enroll`；网络期间不持有数据库锁。
- **精确恢复**：现代调用携带 `voiceRequestId`，以商家 + 请求编号持久存储原会员、形象 ID、版本、规范化录音地址、真实 voiceId 和结果。同编号改变录音或会员会被拒绝；重置、重建 Controller 和重新打开页面都不会让该编号再次消费供应商。
- **兼容旧客户端**：缺少 requestId 时依据形象、当前版本与录音生成稳定编号；当前原录音会复用原编号。明确重置开启新版本，所以旧客户端可以有意重新登记同一录音；未重置的未知请求不允许再次消费。
- **阻止迟到覆盖**：商家声音完成及状态晋升必须匹配形象、商家、版本和请求编号。迟到的原结果可保存在原请求记录中，但不会覆盖重置后的空状态或新的音色。
- **查询边界不变**：不带 requestId 的状态查询保留现有 `PROFILE_WRITE` 商家当前形象读取范围；带 requestId 的原请求查询额外绑定原会员。小程序不会把其他授权人员的当前请求编号当作自己的登记恢复记录。
- **重置恢复**：现代重置携带原形象 ID、版本和请求编号；同版本已清除可幂等确认，版本变化会拒绝清除新声音。小程序先保存重置意图，响应丢失或退出重开后只读取当前形象版本：原形象、原版本 + 1、未配置且无请求编号时，才确认已清除。否则保留原意图，允许显式继续清除原版本。
- **安全未知**：供应商异常、空音色编号、缺失或损坏的结果回读保留 UNKNOWN (`voiceStatus=4`)，不伪装未配置或可重试失败。供应商实际返回的 voiceId 在查就绪前先落库；查询只调用已有 `isReady` / `query_voice`，不新增模型或登记调用。
- **端侧存储与身份**：只保存不透明 requestId、可得的形象 ID/版本和重置意图，按现有会员 ID 隔离；不保存录音 URL、token 或供应商密钥。写后同步回读一致才提交登记；存储失败、坏记录、旧账号延迟回调及形象替换均保守保留未知，禁止自动登记。

原 `merchantAccessService.require(..., PROFILE_WRITE)`、明确声音授权声明、录音 URL 长度/协议验证以及供应商额度/费用策略均保留。没有修改登录、RBAC、Redis/JWT、声音模型、依赖或其他业务范围。

## 文件范围

- `chengyinhub-admin/.../ApiMerchantController.java`：声音三个入口、对应私有辅助方法及事务管理器注入。
- `chengyinhub-system/.../NpcProfile.java`、`NpcProfileMapper.java`、`NpcProfileMapper.xml`：声音请求字段、商家声音读写方法及 SQL；节点 NPC 方法未改。
- `sql/migration_npc_voice_request_guard_20261013.sql`：只新增两个形象列和声音请求记录表；没有修改既有 migration。
- `chengyinhub-xcx/pages/merchant/decor/ai-npc/index.js`：声音恢复和重置逻辑；`index.wxml` 仅增加使用已有重置 handler 的“继续清除原声音”按钮。
- 专用 Java/MySQL 与端侧声音测试；相邻装修测试仅补现有 `getUserID` 身份桩。

没有修改 NPC 对话状态、游玩、日历、俱乐部、共享 action-ledger 或 baseline。

## 验证证据

仅使用批准的 `cy-w2-followup-20261013-test` runner，容器完整 ID：
`099eb30714c0c5d61c9c26e220a1e2ca0be542dde54e3a0a367a7611d758885e`。

所有 Java 构建/测试通过 `docker --context chengyin-vps-sandbox`，在 `flock /tmp/w2-java.lock` 内运行；`MAVEN_OPTS=-Xmx448m`，Surefire `-DargLine=-Xmx320m`。隔离 MySQL 参数仅由本机已有 `/tmp/w2-followup-it.env` 传入 runner，没有复制或展示该文件。新套件创建独立 `w2_voice_recovery` 测试 schema，使用仓库真实迁移和真实 MyBatis/Spring 事务。

源码先传输并校验 SHA-256，再解压到 `/w2/voice`；没有使用 Mac 路径作为 VPS bind mount。初始源码归档 SHA-256：
`68cfc7dfcc53ec761a1ac66202a063c848a5827675993299b22ab759176f888c`。
最终受影响 Java 文件补丁归档 SHA-256：
`adbb6074274621a546d7a57ef97cbb999c679f72e1923acd7fff16a75526ed81`。

| 验证 | 结果 | 实际范围 |
| --- | --- | --- |
| `W2VoiceRecoveryMyBatisIT` | 10/10，0 失败/错误/跳过 | 真实 MySQL 并发、版本、恢复与身份隔离 |
| `ApiMerchantNpcVoiceTest` | 20/20，0 失败/错误/跳过 | 登记、状态、重置及 HTTP JSON/旧空 body 合同 |
| `NodeNpcServiceTest` | 18/18，0 跳过 | 原节点 NPC 业务未回归 |
| `NpcProfileServiceTest` | 4/4，0 跳过 | 原形象消费选择逻辑 |
| `ChapterBlockVoiceOddFlowTest` | 6/6，0 跳过 | 原章节声音异常流 |
| `ApiAiNpcVoiceChatFormatTest` | 16/16，0 跳过 | 原声音格式消费者 |
| 小程序声音 + 相邻装修/人设合同 | 43/43，0 跳过 | 26 项声音合同 + 17 项相邻合同 |
| 暂存树 VPS pre-commit compile | 随本提交强制执行，未绕过 | 指定 hook，正常迁移/配置/语法门禁保留 |

后端共 74 项通过：最终受影响套件 30 项，原相邻套件 44 项。VPS Surefire 文本报告位于 `/w2/voice/chengyinhub-admin/target/surefire-reports/`；最终受影响套件日志为 `/w2/voice-affected-final-tests.log`。本地 Node 的 43 项合同测试均无失败、错误或跳过。

真实 MySQL 的 10 项用例覆盖：提交领取后供应商仅消费一次；同时启动四个领取者；重置/新登记与旧完成竞争；重置与迟到查询竞争；重建 Controller 后使用原 voiceId 查询；丢失供应商响应并重启/重置后仍精确复用原现代请求；同 ID 改载荷/改会员及其他商家不能查询原记录；mapper 版本/形象/商家范围保护；旧客户端未知请求复用与明确重置后的新版本；现代重置幂等确认与延迟重置拒绝新版本。

原商家声音测试的语义等价项保留：未配置供应商不得写入/消费、领取先提交、就绪前保存真实音色编号、pending/ready/查询不可达、归属范围、重置清除、无效 URL/长度/授权声明。原“供应商异常直接标失败”替换为“异常保留 UNKNOWN 且不重复消费”，因为现有客户端无法区分可信拒绝与响应丢失；这一变化有专门测试，未删除相关覆盖。

端侧新增证据包含：登记响应丢失后退出重开只查原编号、同样本旧编号不能解锁、账号切换屏蔽迟到回调、供应商登记前身份变化停止提交、先确认后端重置再重录、重置响应丢失后重开恢复、不同版本/新形象不能误清除、原版本显式重置重试、存储读/写/写后回读故障不提交、其他授权人员请求不被采用。

## 发布及供应商剩余边界

迁移应先于新后端部署；这是一次性增量迁移，须由发布负责人正常预检与执行，不在本任务中触碰生产。所有供应商测试均为 mock，没有供应商调用、消费费用、生产/OSS 写入、push、部署或微信上传。

如果供应商已创建音色但登记响应丢失，**实际 vendor voiceId 从未到达或未能持久化**，当前 `VoiceCloneClient` 不提供按业务 requestId 查询或供应商幂等登记合同。此时只能保留原请求/录音元数据和 UNKNOWN；不能伪造 voiceId 查询，也不能声称已恢复，更不能自动重新登记。需供应商提供可靠幂等/原任务查询能力或人工核对后才能解除这一外部限制。

明确重置允许用户开始新请求，但不等于旧供应商音色被删除或旧消费被退款。旧空 body 重置保留兼容语义，缺少客户端预期版本；版本 CAS 对携带版本的新客户端生效。应用重启通过重建 Controller/页面并回读持久数据库/Storage 验证，未执行真实设备强杀。

本交付不声明真实微信账号、麦克风/录音硬件、权属回执、真实训练、模拟器截图或生产全链路验收通过；协调者已明确接管现有 task-only s3 离线、阻断网络的集成截图，并将在合并后补拍按钮；本 worker 不借用其他 DevTools 会话。其他设备与供应商证据仍需发布负责人补验。
