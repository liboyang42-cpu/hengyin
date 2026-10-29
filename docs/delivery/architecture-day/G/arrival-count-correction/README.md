# G 到达候选测试计数更正（仅文档）

结论：此前我将普通`@Test`声明数误称为总case数，漏计原类六个全限定`@org.junit.jupiter.params.ParameterizedTest`及其24个输入展开。**ThemeRuntime全类应为原58、negative预期60、positive预期63；四类合计原81、negative预期83、positive预期90。**以下更正覆盖旧交付中把34/36/39、57/66作为全类/全套实跑数量的表述。源码和原参数测试均保持冻结，未拆分/删减/跳过任何分支。

## 三种计数与执行证据

每个普通@Test贡献一次预期执行，每个ValueSource输入贡献一次预期参数调用；六个参数方法是六个声明，不能按六次执行计，也不能因全限定注解而漏掉。实际执行数仍必须以对应source/run的原XML裁决，源码枚举不是运行结果。

| ThemeRuntime源 | 普通@Test声明 | 参数方法声明 | 全部测试方法声明 | ValueSource展开调用 | 源码预期总调用 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa | 34 | 6 | 40 | 24 | **58** |
| acc4bf5ea10bbefabb9d5db52dd17dc800b3a955 | 36 | 6 | 42 | 24 | **60** |
| 29c9fc90233c01fe6ba4efc93d008e33727bd44c | 39 | 6 | 45 | 24 | **63** |

| 四类集合 | ShopDayProof | GameThenPhoto H2 | ArrivalSharing H2 | ThemeRuntime展开 | 源码预期总调用 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 原4cf | 5 | 9 | 9 | 58 | **81** |
| negative acc | 5 | 9 | 9 | 60 | **83** |
| positive 29c | 9 | 9 | 9 | 63 | **90** |

- root通报原full4cf的ThemeRuntime实际58，本次源码枚举与其一致；G本批未独立读取该run的XML，不冒充已复验。
- root通报`run40057912348`在窄real-storage入口precheck正确拒绝ParameterizedTest，**0 SQL、0 case**。它不能证明两条业务反例已红，也不是60条或36条已运行；不将harness拒绝算业务失败或成功。
- root将撤销不适配登记并使用现有支持参数测试的Backend CI路径；本批不改CI/registry/harness、不放宽计数、不派CI。
- G本批Java编译、JUnit/H2/MySQL、Redis执行仍全部0。negative的60与positive的63/90仍为源码预期，等待root对应source/XML实际读回，不提前报红绿。

## 六个原参数方法与24个输入

路径：`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ThemeRuntimeMySqlIT.java`，行号绑定原4cf；输入按原ValueSource顺序保存，false/true保持boolean类型。

| 原行 | 方法 | 原输入（顺序不变） | 展开 |
| --- | --- | --- | ---: |
| 227 | r5PredictActualRoundSettlementWaitsForRevealAndUsesOriginalAttempt | PASS, FAIL, VOID, CLOSED, TIMEOUT, CLOSED_TIMEOUT, CONCURRENT | 7 |
| 261 | r5TypeInActualFrozenTimerOverridesLegacySeconds | false, true | 2 |
| 315 | r5ActualCompletionSaveUsesOnePendingConfirmationAndReplaysNoSecondFee | profile, note, dailySign | 3 |
| 329 | r5PhotoAlbumAndStepsPublishFreezeAndConfirmTheirRealSave | steps, qaShot, photoCheck, objectCard, album | 5 |
| 342 | photoOriginalResultSurvivesLocalRollbackAndSameCommandAppliesOnce | CAS, RECEIPT, APPLIED, CONCURRENT | 4 |
| 438 | r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult | PASS, FAIL, ENDED | 3 |

`parameter-inputs.json`保存原ValueSource文本、原值/类型/顺序、24条展开输入、原行号、注解+方法源码和SHA256；24条标识是源码枚举，**不是伪造的JUnit XML display name**。`PARAMETERIZED-SOURCE.md`保留六段原完整注解与方法正文；逐字比较确认三source都相同。`count-reconciliation.json`区分预期数量、root通报执行状态与G未运行身份。

## 对旧交付工件的解释更正

- arrival-negative/source-manifest.json的34/36仅普通@Test声明数，不是原类全展开数量。
- arrival-fix/verify_static.py的`cases()`只枚举普通@Test；它的source-checks.json中`original_total_cases=57`与`candidate_total_cases=66`实际仅表示四类普通@Test声明数，**禁止作为runner exact-count或实跑case数**。加上24参数调用应为81/90。
- arrival-fix/CASES.md只列普通@Test。本补充保存其遗漏的全部原24参数输入；负控仍只新增2个普通case，正向相对负控仍只新增3个普通MySQL case及4个普通unit case；没有新增参数分支或业务断言。
- 旧工件作为冻结历史保留，不改写acc/29c提交/源码/参数方法，不用文档修正掩盖原误计或precheck拒绝。

## 本批实际范围与检查

独立分支`codex/architecture-day-g-arrival-counts-20261024`，父提交29c9fc90233c01fe6ba4efc93d008e33727bd44c；仅新增本G文档目录。实际使用只读git show与轻量Python定向核对六个参数方法、原输入和四个类声明数量；逐字比较三source参数方法，SHA256记录与docs范围/whitespace/现有secret-file-gate检查。无Java/业务测试/数据库/CI执行。root可只取本doc-only增量，不需要重推或改动正负候选生产代码。
