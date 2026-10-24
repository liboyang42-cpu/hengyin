# F：三项真实编译负控入口

Parent `e4e9f6042ba34fb571c3bcfbfe59e248000ef637`；独立 `codex/architecture-day-f-compiled-controls`。不改业务Java、POM、现有gate/producer/policy/baseline/测试、workflow或registry；5caf及原9Python控制继续冻结。**真实Java/jdeps/主JAR/DB/CI全部NOT_RUN，无真实负控成功结论。**

## 已实现的后置入口

`ci/architecture/compiled_controls.py` 只允许root批准的现有GitHub Backend环境。先固定加载**干净5caf** verifier，要求实际source为e4e9后代、实际HEAD/run/attempt/JAR identity一致，并复用原 `check_private_ports` 完整回读11项证据和原3Mapper/55类型切口。原制品没通过或缺任意输入：UNKNOWN/exit2，根本不执行javac/jdeps。

| case | 实际完整源类 | 唯一插入的class引用 |
|---|---|---|
| sdk-router | System `service.impl.WechatPaymentSdkGateway` | `service.impl.WechatPaymentCallbackRouter.class` |
| dto-facade | System `domain.dto.CashWithdrawTransferIntent` | `service.impl.WechatPayServiceImpl.class` |
| common-framework | Common `common.utils.LogCorrelation` | Framework `framework.web.trace.RequestIdFilter.class` |

完整前缀均为 `com.chengyinhub`（System类型再接 `.business`）。从**实际HEAD Git blob**复制完整生产源，只在最终class/record闭括号前加未调用的private static `Class<?> __architectureNegativeControl()`，返回表中FQN.class；无业务方法调用/实例化、无SDK/网络/数据库操作、禁annotation processor。每case先negative后restored；restored重新取原Git blob，不用字符串删除猜测恢复。

Common现有POM不能引用Framework，直接负控分支会先编译失败，不能将此算作jdeps命中。本入口在获准CI中用**控制专属全reactor classpath**对实际完整源运行JDK21 javac，生成独立控制module JAR；不新增生产依赖，不修改Common POM，不替换已测试主JAR。System两项同样处理，避免额外构建/测试/runner批次。

复用固定producer的 `stage_inputs/class_entries/parse_output/technical_dependency_findings`：原Boot/original/target字节先验证；每个控制JAR只替换实际javac输出的同一class family，资源和其余class保留；未改模块复用原JAR。所有index来自真实ZIP字节，所有边来自真实 `jdeps -verbose:class -filter:none` stdout，不写入预造边或伪class。严格要求六模块覆盖、控制源类原始行出现、无missing/parse UNKNOWN/工具失败，负控必须精确命中source+target+owner及实际两端SHA；然后真实原源编译回到scoped PASS。缺证据UNKNOWN；完整证据却未拦/未恢复CONTROL_FAILED/exit1；六阶段全部满足才EXPECTED_RED_THEN_RESTORED/exit0。这不是新主业务required gate或新scanner。

**控制制品身份明确分开**：原CI source/tree/run/attempt/Boot hash与原post-check保留；派生每阶段记录原Git源SHA256、实际控制源SHA256、actual module JAR/class index、工具路径/版本/hash、argv、raw/stdout/stderr及解析结果。`DERIVED_REAL_JAVAC_CONTROL_NOT_TESTED_APPLICATION`不冒称该JAR经过主业务测试/startup，也不伪造新GITHUB_SHA。最终确认原主JAR hash及source干净状态未变。仅保证这三条真实class引用能被对应规则拦截，不证明生产Common可加该Maven依赖、不证明运行时/反射/Bean全图或资金/锁语义。

## root现有Backend单job后置命令（尚未执行）

root先把5caf策略/两个trusted ref纳入完整源，并取得原build/startup/同源Jar+11项jdeps产物。工具从本候选审定后合入的源码执行，复用原 `target/f-private-port-verifier` checkout，无新job/依赖/runner/并行。

```sh
python3 ci/architecture/compiled_controls.py \
  --source "$GITHUB_WORKSPACE" \
  --artifact "$GITHUB_WORKSPACE/chengyinhub-admin/target/chengyinhub-admin.jar" \
  --identity "$GITHUB_WORKSPACE/chengyinhub-admin/target/chengyinhub-admin.build.json" \
  --observation "$GITHUB_WORKSPACE/chengyinhub-admin/target/architecture-jdeps" \
  --verifier "$GITHUB_WORKSPACE/chengyinhub-admin/target/f-private-port-verifier" \
  --output "$GITHUB_WORKSPACE/chengyinhub-admin/target/architecture-boundary-controls"
```

输出必须全新、在忽略的admin/target内。普通producer仍只采集原主JAR一次；本次特定负控后置顺序执行3×negative/restored，不默认每PR重复。由root将此输出目录纳入原artifact上传，原JAR/identity/XML/原jdeps不覆盖。审 `controls.json` 和每case两端实际class hash/raw：不能仅凭job绿或exit1、编译失败/缺产物充当目标负控通过。

## 实际完成的轻量检查

- `python3 -m unittest discover -s ci/architecture -p 'test_compiled_controls.py' -v`：5项/OK/0skip（[输出](prerequisite-tests.txt)）。测试只读取实际源、校验单方法插入不改原正文、拒绝重复/坏目标、无CI时UNKNOWN且禁止所有子进程、输出路径限制；**没有合成JAR/class图或调用Java**。
- Python3.9语法、限定diff/秘密扫描提交前完成。已有9Python控制的文件本轮不改；下一窄补丁在临时5caf副本另实跑原9+新1，共10项/0.953s/OK/0skip（[输出](source-row-targeted.txt)），仍仅原协议double，不当真实编译证据。

## 下一项已交窄补丁：声明类型source行覆盖

只读复现：在原协议夹具删除TopicContentWriteGuard所有raw source行、仍保留真实形态class/index及其它模块行并重新配对fixture hash，当前实际checker返回PASS_SCOPED_COMPILED_REFERENCES、scope_complete=true。**这是合成协议负控揭示的覆盖盲点，不是已观察到的生产故障或真实JDK结果。**

[source-row-coverage.review.patch](source-row-coverage.review.patch) 基于不可变5caf：仅给现有parse_output增加可选source_rows集合（原五返回值/模块图不变），原compiled回读要求每个声明member至少有一条被原解析器识别的source行；缺失只能UNKNOWN，确定违规仍优先BLOCKED。新增一个原fixture用例。没有新parser/scanner/owner/baseline例外。

在临时5caf CI文件副本 `git apply --unidiff-zero` 后，原9控制+新缺行负控10/10通过。**该review.patch未应用到冻结5caf/本分支生产checker。** root若采纳须独立新verifier SHA/唯一双ref repin；本三项工具仍固定5caf，不偷换信任锚点。空依赖类型缺raw行只能未知，不能因此宣称违规；真实JDK覆盖边界待root统一回读。

## 后续已授权实现（以此为当前合同）

缺行协议已实现并冻结0e00，再追加E_TOPIC_QUERY冻结最终verifier `bd33ed0be7fafaf89daaec2eb772c15b034d3ec0`（56/45/51，原55型/164控制保留）；工具现只加载该完整SHA及干净树，不再接受5caf/0e00或浮动ref。此前source-row-coverage.review.patch仅留历史证据，**不要重新应用旧提案**。新后置full模式也复用同一owner_source_reviews，任意声明类型缺行UNKNOWN。
新增 `--leaf-only` 独立真实JDK空class/interface对照，不需要主JAR，却仍要求实际CI/clean source/run/attempt/JDK21和固定verifier；其LEAF_SHAPE_CONFIRMED不是应用验收或三项负控通过。当前指令、精确repin补丁和验证见 `F/compiled-controls-pinned/HANDOFF.md`。实际Java仍NOT_RUN，原文0.953s/9+1是旧提案协议实验，不能当最终完整verifier或真实leaf结果。
