# Advanced act/replay 请求指纹：待集成人裁定的最小持久合同

结论：H 报告的服务端漏洞存在于指定公共基线。本轮尚未修改 AdvancedGameRuntimeServiceImpl、PlayAttemptLog、它们的 mapper 或任何 schema。仅修客户端不能满足验收；此项是合并/上线阻塞，不能按已修复计。

## 已核验的调用链与失败机制

`AdvancedGameRuntimeServiceImpl.act(memberId, sessionId, expectedVersion, idempotencyKey, action, payload)` → `requireAccessible` → 按主题/挑战 scope 处理 key → `PlayAttemptLogMapper.selectByIdempotencyKey` → `replay(log, memberId, sessionId)`。原请求的 action、payload、expectedVersion 不进入 replay 比较。`logAction` 仅持久化结果 JSON、key、主体/节点信息，DuplicateKeyException 分支也调用同一个 replay。

`PlayAttemptLog` 与 `PlayAttemptLogMapper.xml` 没有请求指纹字段。不能从结果 JSON 反推原请求，不能靠内存 map、客户端换 key、TTL 或 session 当前版本替代原指纹。

## 精确字段提案（不执行、不加入共享迁移目录）

新增一列：

```sql
ALTER TABLE play_attempt_log
  ADD COLUMN request_fingerprint CHAR(64)
    CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT 'SHA256 of versioned canonical command; NULL means unbound historic attempt';
```

保留现有 idempotency 唯一索引，不增加放宽/删除条件。NULL 用于不带命令的普通埋点及已有行；新高级动作写入时应用层必须要求非空且匹配 `[0-9a-f]{64}`。不为历史行制造默认 hash，不回填猜测值。

编码包含固定 schema 标识 `advanced-act:v1`、memberId、sessionId、活动/主题 scope、playInstanceId、ownerType/ownerId、原 expectedVersion、统一 trim/upper 后的 action、递归规范化 payload。对象键排序，数组顺序保留，字符串不做业务外的裁剪/转换，null/缺字段、数字/字符串区分。规范化必须在任何可能改写 payload 的业务 helper 之前完成；hash 本身不保存输入正文。所有有 key 的新高级回执写 fingerprint 与业务结果同事务。

首次 lookup 与 `logAction` 的重复键回读均比较主体/原 session 与 fingerprint。相同指纹返回原结果，必须早于当前实体版本冲突；不同指纹返回明确冲突，无业务/权益/经济/外部动作变化；历史 fingerprint=NULL 的高级命令 key 返回“历史身份无法证明”的冲突，保留旧记录，不自动切 key 重做。其他 attempt 类型 nullable 行不受影响。

## 预检、迁移、部署与回退顺序

1. 批准的只读预检确认目标库/表、InnoDB、现有 key 唯一索引、列不存在或与上述定义完全相同；对带 key 的高级行按 attempt_type 聚合计数，不导出正文或秘密。
2. 在批准的 MySQL CI 专属测试库执行真实迁移、mapper round trip 和回滚故障注入；不得用 H2 代替。先确认 online DDL 的锁等待和执行窗口，再由集成人/用户授权执行真实 DDL。
3. 先部署列，再部署写入/读取指纹的代码。schema readiness 必须在放量前确认；不能 catch 缺列错误后绕过校验。
4. 存量 NULL 高级回执保留且 fail closed；需要操作侧核验的历史 UNKNOWN 单列，不按 TTL 释放身份。
5. 回退保留列、回执和业务记录。产生新指纹回执后禁止回退到无指纹校验的代码；如必须关闭功能，停止该入口的新写并保留读取/调证。不得 DROP 列、清表或删除已执行 key 以“恢复兼容”。

## 最小测试清单（未编写、未运行；待 scope/schema 决定后实施）

- Unit：同 key 同原 expected/action/payload 顺序重放；同 key 改 action、嵌套 payload、数组顺序、expectedVersion 分别冲突；对象字段排列不同但语义相同允许重放；同答案新 attempt 允许；缺 expected/空 hash/非法 key 拒绝。
- Unit：先校验读取授权，再返回已执行旧版本回执；不同账号/原 session/旧实例拒绝；历史 NULL 高级指纹 fail closed；普通埋点 NULL 不受影响。
- 真实 MySQL：同键并发相同输入只一份效果与终态回执；同键并发异输入只一个赢家，另一方冲突；状态写后回执写失败全事务回滚；提交后丢响应从原 key 读回；新旧列部署顺序及 mapper hash 字段 round trip。
- 外部动作：照片 AI 的 UNKNOWN/原意图合同由根协调者独占 PhotoCheckVisionService 及所列夹具，不能在此指纹补丁中覆盖或绕过。

待决定：集成人是否接受上述一列 schema、历史 NULL 高级命令的保留式拒绝策略，以及 AdvancedGameRuntimeServiceImpl/PlayAttemptLog/mapper/专属测试的额外独占范围。本文件不代表这些变更已实现。
