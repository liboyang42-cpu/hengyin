# F：固定最终source-row/TopicQuery verifier的真实JDK入口

本工具候选parent `1709210d17b83d8e3fb17cb8e50f4144bd67f25d`，独立 `codex/architecture-day-f-controls-pinned-source-rows`。**只取该parent之后工具/专属测试/docs增量，不把170的旧35型policy或生产树覆盖root。**

独立最终verifier：`bd33ed0be7fafaf89daaec2eb772c15b034d3ec0`，parent `0e00ce639dea8e7f266dd326878532f45c59a032`，再parent5caf。它包含原55型+E_TOPIC_QUERY（56/45/51）、缺source行UNKNOWN、原3Mapper/2facts/2ec baseline不变。root集成要取 **5caf→bd33完整工具delta**（或按顺序纳0e00和bd33），不能只拿E策略而漏producer协议。该verifier190项Python/0skip通过，原164方法正文保留；无真实Java/CI通过结论。

控制入口代码字面固定bd33；load_verifier要求实际Git HEAD等于该SHA且无dirty，拒绝5caf/0e00/浮动ref及候选自改。没有--expected-ref逃生口，不改rootworkflow或新增job。full模式与原回读复用同一个parse_output/source_rows/owner_source_reviews；任意声明类型缺raw行继续UNKNOWN，不因同模块其它输出/JSON complete声称覆盖。

## 先核对真实空leaf形态（仅root获准现有CI）

```sh
python3 ci/architecture/compiled_controls.py --leaf-only \
  --source "$GITHUB_WORKSPACE" \
  --verifier "$GITHUB_WORKSPACE/chengyinhub-admin/target/f-private-port-verifier" \
  --output "$GITHUB_WORKSPACE/chengyinhub-admin/target/architecture-leaf-shape"
```

root先审定并将该既有可信checkout固定为bd33。leaf-only可在JDK设置后单独执行，不依赖Backend编译成功/主JAR；不启动任何业务类，仅编译真实空class/空interface两个独立测试源，实际JAR/class-index/raw来自标准JDK21。复用新producer解析，要求二者的java.lang.Object/java.base行、source_rows、无项目外向边/缺依赖/未知；记录源/实际class/JAR/tool hash、CI source/tree/run/attempt和argv。若实际JDK没有给出预期行，只能UNKNOWN，不能凭想象给leaf豁免。

结果 `LEAF_SHAPE_CONFIRMED` **只证明这两个空leaf的当前JDK raw形态**，不代表主应用JAR测试、业务纯读或三项越界负控通过。读回leaf-shape/result.json及实际stdout/index/hash后再签该形态；本VM无Java，迄今真实leaf仍NOT_RUN。官方JDK21 GA源码核对是静态依据，不冒充当前Temurin21.0.12实测；历史raw API本次Forbidden，没有补造证据。

## 再运行完整三项negative→restored

沿170原命令传入实际artifact/identity/observation；固定verifier路径同上。full模式要求原build/startup/source/JAR/11项证据及原3Mapper/56型切口先完整通过，再真实双leaf对照，再SDK→router、DTO→facade、Common→Framework各negative/restored。缺产物或source行UNKNOWN，完整证据未拦/未恢复CONTROL_FAILED；全流程才EXPECTED_RED_THEN_RESTORED。派生控制JAR不是tested应用JAR，主JAR/hash/source保持不变，不更改生产Common Maven依赖。

root唯一[双ref review.patch](trusted-ref.review.patch)来自实际公开 `7da3e4ea385596aa850dc760f2cf6adc6c554602`：仅两行5caf→bd33，临时Git `git apply --unidiff-zero --check`/实际应用逐字模拟PASS。若root已改过ref/文件，逐hunk适配，不能整旧workflow覆盖。source/compiled两处应同审同pin。

## 实际验证与剩余

工具原5项前置控制完整保留，再补leaf-only无CI禁止工具/返回UNKNOWN、真实空源不含业务调用或预造边、旧/脏verifier拒绝三例，共8 Python tests/0skip/OK（python-tests.txt）；没有运行Java或生成任何假JAR/raw/index。实际从干净bd33 checkout加载producer，解析56个真实policy成员及缺行API成功，未调用JDK。Python3.9语法/源文件范围/原配置秘密扫描提交前检查。

remaining：root在同一批获准CI实际执行leaf-only并读回，再执行三边完整负控；缺结果不签。全图/反射/Bean循环、Query传递纯读与实际资金/授权语义仍不由此工具证明。原冻结0e00/5caf/170不重写；没有执行DDL、部署、真实模型/支付或生产操作。
