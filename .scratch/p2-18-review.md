# PR #409 P2-18 双轴复核报告

**PR**: `liboyang42-cpu/chengyin#409`
**基线**: `github/master` = 21d236d9
**HEAD**: 85fdc609
**标题**: `fix(audit): 过审放行途中彩蛋的异常不再静默吞掉 (P2-18)`
**改动**: 2 文件, +72 / -3 行

---

## 结论: **PASS**

---

## CI 状态分层

| 维度 | 来源 | 结论 |
|------|------|------|
| **本地定向测试** | `mvn -pl chengyinhub-admin test -Dtest=AuditTopicApprovalQuotaTest` | ✅ 5/5 PASS |
| **本地全量测试** | `mvn -pl chengyinhub-admin -am test` (全模块) | ✅ 2640/2640 PASS, 0 FAIL, 91 skipped |
| **GitHub CI `Backend tests`** | required hosted job | ⚠️ SKIPPED — 因 `Detect changed areas` FAIL (zero-step billing failure) 导致下游被跳过 |
| **GitHub CI 其他 hosted** | `Secret scan`, `SBOM`, `Migration layout`, `Migration safety` | ⚠️ 均 FAIL — zero-step billing failure (非代码问题) |
| **Funds MySQL IT (self-hosted)** | `Funds IT gate self-test` + `Funds MySQL IT` | ✅ 均 SUCCESS |

---

## Standards 轴

| 检查项 | 结果 | 证据 |
|--------|------|------|
| **变更范围** | ✅ | 严格 2 文件: `AuditTaskServiceImpl.java` (3 行旧→9 行新), `AuditTopicApprovalQuotaTest.java` (+63 行测试) |
| **无 migration 泄漏** | ✅ | diff 不包含 `sql/` 目录, 不包含 DDL/DML |
| **无 RBAC 改动** | ✅ | 未触 `roleService`/`resolveRole`/`checkPermission` |
| **无 frontend 改动** | ✅ | `chengyinhub-ui/` 与 `chengyinhub-xcx/` 均未出现在 diff |
| **无 config 改动** | ✅ | `application*.yml`/`pom.xml` 均未出现在 diff |
| **日志框架合规 (SLF4J)** | ✅ | `AuditTaskServiceImpl.java:320-322` — 使用已有 `logger` 实例 (`private static final Logger logger = ...`), 与同文件第 306/370 行用法一致 |
| **日志消息规范** | ✅ | 格式 `"途中彩蛋随主题过审放行失败 topicId={} errorType={}"` — 含业务标识 (topicId)、异常类型 (errorType)、throwable, 无敏感体 (审核理由/body/snapshot) |
| **Java 8 兼容** | ✅ | 无 Java 9+ API, 无 Stream 新方法, 无 var |
| **测试质量** | ✅ | 使用 Mockito, logback `ListAppender` 捕获日志, `InOrder` 验证调用顺序, 精确断言消息格式 + throwable 存在性 |
| **遵循现有 RuoYi 风格** | ✅ | Controller 薄/Service 业务逻辑分层不改; 使用项目既有 `AiServiceException`/`ServiceException` 体系; `@Transactional` 不变 |

### 非阻断提示

- **`AuditTaskServiceImpl.java:320`** — `logger.error` 用 3 参数匹配 2 个 `{}`, 第三个 `eggEx` 依赖 SLF4J 1.7+ varargs autodetect 机制识别为 Throwable。这是正确的 SLF4J 用法且在测试中得到验证, 但 SLF4J 1.6 需显式 `logger.error("msg", throwable)` 两参数重载。

---

## Spec 轴 (P2-18)

### 核心变更: `AuditTaskServiceImpl.java:316-322`

**旧代码** (`:317`):
```java
try { npcRouteEggMapper.updateStatusByTopic(bizId, 0, 1); } catch (Exception ignore) {}
```

**新代码** (`:317-322`):
```java
try {
    npcRouteEggMapper.updateStatusByTopic(bizId, 0, 1);
} catch (Exception eggEx) {
    // best-effort:彩蛋放行失败不回滚审核结论与主题上架,但必须留痕——
    // 静默吞会造成「主题已过审上架、途中彩蛋却仍卡在待审」且无任何线索
    logger.error("途中彩蛋随主题过审放行失败 topicId={} errorType={}",
            bizId, eggEx.getClass().getSimpleName(), eggEx);
}
```

| Spec 需求 | 结果 | 证据 |
|-----------|------|------|
| **异常处理从 `catch(Exception ignore) {}` 改为 logger.error/warn** | ✅ PASS | `AuditTaskServiceImpl.java:319` — 移除 `ignore`, 命名为 `eggEx`, 调用 `logger.error(...)` |
| **日志含 topicId** | ✅ PASS | `:321` — `bizId` 填入 `topicId={}` 占位符, 测试验证 `topicId=77` |
| **日志含异常类型** | ✅ PASS | `:321` — `eggEx.getClass().getSimpleName()` 填入 `errorType={}`, 测试验证 `errorType=DataIntegrityViolationException` |
| **日志含 throwable** | ✅ PASS | `:322` — `eggEx` 作为第三个参数传入 (SLF4J varargs Throwable autodetect), 测试 `assertNotNull(evt.getThrowableProxy())` 通过 |
| **日志不含敏感审核理由/body** | ✅ PASS | 消息仅含 `topicId` + `errorType`; 测试断言 `"途中彩蛋随主题过审放行失败 topicId=77 errorType=DataIntegrityViolationException"` — 逐字断言无 reason/snapshot/body |
| **审核结论语义不变** | ✅ PASS | `cmsTopicMapper.updateCmsTopic(t)` 在 `:315` 执行于 try 块外层, 不依赖 egg 结果 |
| **主题上架语义不变** | ✅ PASS | status/userStatus 更新在 `:310-314` 先于 egg 处理, 与旧行为逐行一致 |
| **配额闸语义不变** | ✅ PASS | `lockAndCheckThemeOnline` 在 `:296-303`, 与 egg 块无耦合 |
| **事务/调用顺序不变** | ✅ PASS | `@Transactional(rollbackFor = Exception.class)` (`:211`) 不变; egg 未 rethrow, 不触发回滚 — 与旧行为一致 |
| **mapper 异常仍 best-effort** | ✅ PASS | `:319-322` — catch 块不 rethrow, 客户端不感知 egg 失败 |
| **成功路径无告警噪音** | ✅ PASS | `AuditTopicApprovalQuotaTest.java:103-118` (`approveUnderQuota_gatesThenOnlines`) — `assertTrue(events.stream().noneMatch(e -> level >= WARN))` |
| **失败路径留痕** | ✅ PASS | `AuditTopicApprovalQuotaTest.java:148-187` (`eggReleaseFailure_isLoggedAndDoesNotBreakApproval`) — 捕获 ERROR 级别日志事件, 精确断言消息格式, 验证 throwable 存在 |
| **负控: 回退到 `catch(Exception ignore)` 能红** | ✅ PASS | 若生产代码退回空吞, `eggReleaseFailure_isLoggedAndDoesNotBreakApproval` 的 `events.stream().filter(ERROR).findFirst().orElseThrow(AssertionError)` 将因找不到 ERROR 事件而 FAIL |
| **外层 applyModeration 兜底 warn 不被错误触发** | ✅ PASS | `AuditTopicApprovalQuotaTest.java:175-176` — `noneMatch(e -> e.getFormattedMessage().contains("applyModeration"))` — 异常见在内层 catch 已收口, 不泄漏到外层 `:370` 的 `logger.warn("applyModeration best-effort 失败")` |

### 非阻断提示

- `AuditTaskServiceImpl.java:316` 注释写 "P0.5" 但实际是 P2-18。注释更新不是本次 scope, 属历史遗留。
- `AuditTopicApprovalQuotaTest.java:97-118` `approveUnderQuota_gatesThenOnlines` 新增了 `npcRouteEggMapper` 的 `InOrder` 校验和日志噪音断言 — 这是测试加固, 将 egg 路径纳入调用顺序契约, 超出"最小改动"但能防回归。

---

## 执行摘要

PR #409 将 `catch (Exception ignore) {}` 替换为 `catch (Exception eggEx) { logger.error(...) }`, 在保持 best-effort 不中断审核流程的前提下, 让彩蛋放行失败产生可追溯的 ERROR 日志。改动精确限定 2 文件, 不触 migration/RBAC/frontend/config。本地定向 5/5 PASS, 全量 2640 无回归。CI 层面: GitHub hosted 门禁因 zero-step billing failure 未真正执行 (非代码问题), self-hosted Funds MySQL IT 全绿。Standards 和 Spec 双轴均 PASS, 无阻断问题。
