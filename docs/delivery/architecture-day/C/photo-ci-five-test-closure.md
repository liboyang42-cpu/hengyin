# C：66e9 照片单测五处失败闭合候选

结论：只调整三个指定测试类及一个窄测试断言 helper，生产源码、接口、schema、台账均不改。五处失败的修订已写入候选；Java 编译、JUnit、MySQL 尚未执行，不能称五处已通过。此提交供 root 受控 CI 验证，不正式集成、合并或部署。

## 基线、隔离与身份

- 公共仓库：`https://github.com/liboyang42-cpu/hengyin`。
- 精确 parent：`66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329`（root 完整 CI 源）；参考原基线仍为 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 开工已有 C 工作树：`/workspace/hengyin-c`，`codex/architecture-day-c-play-command`，HEAD `d359cd2de8d4b5f78fc2f998db969ad8ae657327`，clean，原样保留。
- 本轮独立工作树：`/workspace/hengyin-c-photo-tests`，分支 `codex/architecture-day-c-photo-contract-tests`，由公共 66e9 建立。这样直接保留 root 已合入的两份真实 Vision/quota 夹具，不把历史 C tree 覆盖回 root。
- diff / 候选身份：本报告所在提交，`git diff 66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329..HEAD -- <下列四个 Java 文件>`；交付时附完整 SHA。

## 失败机制、调用链和修订合同

已只读核对生产 `ApiAdvancedPlayController.action → AdvancedGameRuntimeServiceImpl.act / admitPhoto → GlobalExceptionHandler.handleServiceException`。`admitPhoto` 只有在锁 session 后，对原 receipt / intent 两次当前读均证明不存在时，才将新的资格拒绝包装为 `ServiceException(400)` 并附 `intentNotExecuted=true`。Controller 对此异常重新抛出，真实全局处理器保留 code/msg/data；因此不能继续要求旧 IllegalArgumentException / IllegalStateException，也不能只接受任意 RuntimeException。

| 原红测 | 最小修订和保留断言 |
| --- | --- |
| PlayKit 模型判过后再交 | 精确 `assertThrowsExactly(ServiceException.class)`，code=400，msg=`这一张已经过了`，data 恰为 `intentNotExecuted=true`；经真实全局处理器再断言同一响应。已通过的 state/version/score 不变，零新增 quota / Vision / prepare / execute / mark / CAS / receipt。 |
| PlayKit 客户端 score 不能代替照片 | 原“满分不能改模型拒绝、零分不能改模型通过”正向断言保留；缺照片的新命令精确 msg=`照片地址不能为空且最多512字`、400 / 原 data，state={}、version=0、score=0 和零新增外部/本地效果。 |
| ObjectCard 过后再交不二次铸卡 | 同样精确拒绝/响应，并保留真实首次 `Vision.review` / `consumeQuota` 调用和总 mint 恰一次；第二次零新增其余效果，state/version/score 不漂移。 |
| ObjectCard 旧 act 注解检查 | 用真实 `AbstractPlatformTransactionManager` 驱动生产 act 的两个 writable / READ_COMMITTED 短事务。断言 prepare→commit→provider→CAS / 持久回执 / markApplied→commit→mint 的阶段顺序；provider 的传播属性读取生产 `PhotoCheckIntentService.executeOnce` 的真实注解并用 `TransactionTemplate` 执行 NEVER，附进入外层事务时精确拒绝的负控。mark 校验原 scoped key 查出的 receipt id / member / fingerprint / type8，mint 在提交后且无实际事务。真实本地图片编码、Vision.review、quota supplier 不替换成固定成功。 |
| AdvancedPhoto 新 attempt 同照片 | 重设 `AdvancedActionTestFixtures.photos` 时 Mockito 会执行上一份 `selectByIdempotencyKeyForUpdate` answer，原严格 answer 要求可写实际事务。将该测试的 helper 安装包在已有真实测试 TransactionTemplate 中，不删除或关闭 assertTx，不改业务调用的原 key/version/payload；两次新 attempt、两次原命令回放仍断言两次 review / 两次 receipt / tries2 / score0 / version2。 |

补一条同阶段回滚负控：`markApplied` 已将内存意图置 APPLIED 后抛错，真实测试事务管理器的 rollback 同时恢复 session、receipt、intent。断言版本/得分/次数效果未提交、receipt 为空、首次 provider 结果仍 RESULT_READY / RECEIVED；原命令恢复只应用一次，provider 总调用仍一次。此为**内存替身的事务回调表征**，不是 MySQL 原子性证据；root 独占的 Theme/APPLIED 真库测试未修改。

## 精确文件清单

路径相对公共仓库根：

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PlayKitPhotoCheckRuntimeTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ObjectCardMintHookTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AdvancedPhotoOrchestrationTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AdvancedActionTestFixtures.java`：仅新增共用的精确异常 / 全局响应断言；原 transactions/photos 和 assertTx 语义不改。
- 本报告及 `evidence/photo-ci-five/source-proof.json`、`static-checks.txt`。

未碰 ThemeRuntime、APPLIED 真库测试、APIController、E IntentService、root Vision / Gateway、台账 / UI baseline / migration / schema。未新增依赖、Agent 或 CI workflow。

## 实际执行与未验证

- 已执行：`git diff --check`；Python 对四个 Java 源的字面量/注释剥离后括号平衡、原/新 `@Test` 名称与源 SHA256 核对。详细输出在 `evidence/photo-ci-five/`。
- 源方法计数仅用于防删覆盖：PlayKit 17→17，ObjectCard 18→18（替换旧注解方法），AdvancedPhoto 16→17，总 51→52；**不是 JUnit 实际执行数**。
- Gitleaks 使用既有 `.gitleaks.toml` / `.gitleaksignore` 扫描四个候选源、交付材料及原始 diff；结果见静态检查证据。未扩展 baseline / ignore。
- **未运行**：Java 编译、这 52 个 JUnit 用例、MySQL / Redis、Spring 应用启动、真实 HTTP / provider。不得把本轮 source-only 检查或内存回滚替身计成真实业务验收。
- root 已报告 run40036832794 原始结果：14031 tests（含633skip），5 failure / 0 error；后续 MyBatis / Theme / APPLIED 未执行。本轮没有生成新的 CI 结论。未重跑与本轮 Java-only 差异无关且 root 已绿的 Node / 台账。
- 建议 root 首先真实编译并执行这三个测试类，再跑被原五红挡住的 MyBatis / Theme / APPLIED；不得省略原失败断言来放行。

## 迁移、回退和余项

没有 schema / 数据 / 资金 / 配额生产迁移。本提交可独立撤回测试差异；撤回会恢复旧公共拒绝期望和过时注解断言，不能作为四阶段生产代码的回退。保留 66e9 所含 root 修复与所有冻结候选。

WP09 生产抽取仍未编码；下一项仅限已授权的 W09-T 独立 MySQL 表征测试，生产接口及 14 helpers 移动仍待 root 另审。Photo 四阶段并不等同 WP13 可观测与资源隔离完成。
