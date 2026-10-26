# voice-gap：按原 NPC 范围匹配声音请求

结论：这是 reader 把 NPC 范围内的请求误当全局唯一的 test-harness 缺陷，不是原声音业务身份丢失或生产缺陷。本片只修这一个已定位失败，不推断四个旧宿主失败同根因。

Parent：`6713fb6de418f21b45f918524b3eb7aa4d9758fd`。
分支：`codex/architecture-day-f-voice-request-6713`。
原事实来源：root 已独立核实 full7 run40279626410 唯一 voice-gap job121430508255 失败；artifact12631403465 ZIP2554/SHA256 `c64fb4c8e59de2bcba82271991c7f026829b2e856f6cddae041b4fa1e5ef4231`/CRC PASS；SOURCE exit0，canonical restore 后 TARGET DOMAIN_EVALUATE，VOICE_ORIGINAL_REQUEST。schema 实际 absence / outer EXACT_ABSENCE 不受本片影响。本 F 不重复下载、执行或把该清理结果移称新 SHA 已过。

## 精确根因来源链

1. `RestoreVoiceReconciliationFixture.enroll(long npc)` 对 555/556 均调用真实 owner，样本都是固定 `media-asset:73`，只是 NPC/受控 provider 输入不同。
2. `NpcVoiceEnrollmentService.enroll` 用同一样本 SHA，requestId 缺省时生成 `sample:` + hash；request_id 为 String，两个 NPC 合法得到同一个 label。不是 JDBC 数字/string 差异，也不是修改了 journal 原 admission version。
3. 生产 `find(npcId, identity)` 始终保留 NPC 参数。NpcVoiceEnrollmentMapper.find 以 npc_id + sample_sha256 + sample request 查原记录；findRequest 以 npc_id + request_id 查原记录。既有 canonical `migration_npc_voice_confirmed_attempt_20261024.sql` 明确唯一键 `uk_nvei_request(npc_id,request_id)`。
4. FixtureVoiceProvider.enroll 在真实 IN_FLIGHT 已提交后，由对应 npc 的 ORIGINAL_ROW 查询提取原 JDBC 行字段；identity 直接保留 row.request_id / npc_id / id / sample / actor / scope / admission version。journal SUBMIT、RESULT 在该 provider 调用中保存；known 的 QUERY 继续保留原 identity，UNKNOWN 无 QUERY。
5. SOURCE 先完成 555 READY、556 UNKNOWN 各自 requireOriginalProjection，再发 BACKUP_READY。556 不是备份后新单；之后 AFTER_BACKUP 只发 FAULT_READY，无新 enroll/写入。
6. 宿主按原两表/原参数 capture SOURCE before → canonical backup → AFTER_BACKUP/fault → STOP/exit0 → canonical restore → capture TARGET restored → domains.evaluate。table_rows 普通字段 CAST AS CHAR，request_id 本来就是文本；journal JSON 保留文本，未规范化或重命名请求。evaluate 的原 backup==restored 已先于本次失败执行。
7. 原 reader 只按 request_id 匹配，合法两行令 len(local)==2；这与实际 VOICE_ORIGINAL_REQUEST 相符。旧合成 voice() 给 known/unknown 造了不同 request_id，掩盖该缺口。本次用公开固定源码推导该形状，不宣称已读取私有真实行值。

## 必要代码及调用者

- `ci/restore_business_domain_facts.py`：仅在 evaluate 的 local 过滤中增加 `npc_id == str(identity['npc_id'])`，与原唯一键/Mapper 一致。原 request_id exact equality、len(local)==1、原 intent id/NPC/actor/scope/sample/version/profile/state/NULL/provider 数量、backup equality/high-water/UNKNOWN 不重发和 gap 禁 normalBoot 全部保留。
- `ci/test_restore_business_domain_facts.py`：voice() 使用实际样本 SHA 与同一个 sample request label；旧 45 方法原体保留。新增 7 方法 SOURCE，分别覆盖原全局谓词实际拒绝两行而正确 NPC 匹配仍 BLOCKED、missing UNKNOWN 不借 known、错 NPC/request 拒绝、同复合键重复仍拒绝、原 ID/actor/scope/sample/state 拒绝、真实 journal parser 的合成文件 roundtrip、实际生产/Mapper/unique key/备份时序静态合同。
- `RestoreVoiceReconciliationFixtureTest.java`：原 5 例保留，新增 1 例在既有 per-NPC mapper/事务 model 中调用两个真实 production owner，验证同 String request label、两个 NPC、journal admission1、READY projection3、UNKNOWN projection1/null、各一次 SUBMIT、UNKNOWN 零 QUERY。它是 unit SOURCE，mapper/provider doubles 不冒充真实 MySQL 或 restore 证据。

实际生产 fixture、shared RestoreProviderJournal（含 root/A 已合排序修补）、所有生产服务/Mapper/XML/DDL、C host/probe、workflow 均逐字不改。无需新公共接口、固定诊断字段或 host hunk；现 evaluate / verify_owner / JSON 口保持原签名，verify_owner 不变。

## 验证与剩余项

仅允许的 Git diff/apply-check、摘要、有限新增行秘密模式与 base64 传输回读。所有 Python 测试/parse/import、Java/unit/MySQL/Redis、编译、HTTP、容器及 CI 均 NOT_RUN。

SOURCE 为原 45 + 新 7 = 52 个 Python 方法、原 5 + 新 1 = 6 个直接 Java fixture unit 方法，不把源码计数当通过；不减少或重跑六个既有成功 cohort。root 收齐后只安排受影响 voice-gap 及必要控制/fixture 验证；原 UNKNOWN 仍必须事实差异可读但禁止 normalBoot/dispatchRelease，实际后续 owner boots 与精确清理等待同源验证。

`owned-source.patch.b64` 包含全部 3 个源码文件的完整增量；公共提交另含本说明与逐文件后像摘要。解码后按 PATCHES.sha256 校验，或直接 fetch/cherry-pick 独立公共提交，勿重复应用。
