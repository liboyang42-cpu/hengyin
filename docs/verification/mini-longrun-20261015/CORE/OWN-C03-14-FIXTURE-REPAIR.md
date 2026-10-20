# OWN-C03-14 关联旧测试生成身份 fixture 修复

授权：公共控制 `e1928416d051627ecef80a5d0bd7121ff64a4417` 的 `C03_TOPIC_UPDATE_FIXTURE_REPAIR.json`。业务源码基线为公共 CORE `adf597917da2654b6b753141180bbbcb6db92fc5`；本次仅修改 `TopicUpdateByOwnerTest` 的 setUp 及必要 imports，并新增本文。不修改生产代码、新 `OWN_C03_14Test` 或其他测试。

## 已收到的本地实跑事实

root 报告公共 adf 精确关联套件共 51：35 PASS、1 FAIL、15 ERROR、0 SKIP。其中 `OWN_C03_14Test` 10、`OWN_C03_12Test` 4、`TopicCreateBusinessErrorPassthroughTest` 6，三类共 20 全部 PASS；旧 `TopicUpdateByOwnerTest` 共 31，其中 15 ERROR 与 1 FAIL 提前遇到“章节保存失败，未生成有效章节身份”。唯一 FAIL 的免费转商业校验断言也被同一章节身份前提提前挡住。以上为 root 已提供的本地结果，本云端没有执行或独立复现；完整公开证据由 root 发布，本文不虚构日志 hash 或额外实测方法名。

## fixture 根因与最小修复

旧 setUp 的章节、节点、活动 Mapper insert 是 Mockito 默认答案（0 行且无回填 ID）。真实保存合同要求 insert 返回 1 并产生正 ID：章节通过后，节点会检查同一身份合同；非草稿保存随后沿 `publishBeforeReview` / `ensureSelfHostActivityShell` / `generateSelfHostActivity` 创建活动壳，再检查活动 ID。只修章节会把缺失前提推迟到节点/活动。

本次在 setUp 对三类 insert 配置成功答案：章节 ID 从 801、节点 ID 从 901、活动 ID 从 1001 起分别递增，实际实体参数回填 ID 并返回 1。默认答案遇 Mockito 重 stub 的 null 占位实参只返回 1，不解引用、不消耗序列。原分支图测试局部的节点 101/102 回答与其所有断言原样保留，继续覆盖默认节点回答。

31 个 `@Test` 方法与全部业务断言不变：免费转商业拒绝、owner、缺席完整性、清空、身份映射及各负控均保留。不新增 skip，不放松生产 guard，不将真实 validator 替换成 mock，也不泛注入依赖。既有 quota、债务闸、票复制服务已具备 mock；双节点分支图测试已有局部模板/距离依赖与路线 CAS 回答，无需改动。

章节/节点生成身份 guard 已存在于父 CORE e0；adf 新增的是协作者 null/非正 ID 保护，旧 fullDto 的 collaboratorIds 整体为 null，在新检查中直接返回。因此本次定位为旧 fixture 未履行成功 insert 前提，不能据此称已确认生产回归；活动身份前提也来自既有真实发布链。

## 修后验证状态

`SOURCE_ONLY / NOT_RUN`：本云端没有运行 Java、Maven、Node、编译、测试、lint、CI、HTTP 业务验证、数据库、VPS 或生产访问，没有 Git 暂存/提交。修后尚无通过结论。

新 SHA 发布后，最小本地复测仅 `TopicUpdateByOwnerTest`，预期仍为 31 个测试方法，不重复完整 51。由 root 审查、串行提交发布及提供实际复测证据。

## C00公开证据核读与本地移交

C00已核读原失败[PR24@cd57aef3 README及summary](https://github.com/liboyang42-cpu/hengyin/blob/cd57aef324b03c6c5d3f3f2580bbba91d5d295b4/docs/verification/mini-bug-arch-20261015/VPS_CORE_adf59791/README.md)：51实际35PASS/1FAIL/15ERROR/0SKIP，4份fresh XML哈希与精确容器清理记录保留，未独立重跑、未读回全部VPS原始XML。该原失败不由修后源码覆盖。

修复只涉及本旧测试与本文，31个@Test及全部业务断言未改，无新依赖/配置、MySQL/Redis前提。由本地唯一验证者在既有212环境运行的最小命令文本（Cloud未执行）：

```bash
mvn -o -B -ntp -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=com.chengyinhub.business.service.TopicUpdateByOwnerTest test
```

31仅静态预期，真实执行数以新SHA/fresh XML为准，不重复全51，不继承adf已通过三类20为新SHA实跑。未合并、部署、运行HTTP或真数据库验收。
