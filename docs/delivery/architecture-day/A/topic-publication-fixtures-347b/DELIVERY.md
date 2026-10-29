# 三个主题发布单测的真实 owner/事务装配

代码已写齐；所有编译、语法/import 控制、JUnit、Java、DB、HTTP、供应商及 CI **NOT_RUN**。
这不是原 15 项失败已通过的结论，也不继承任何历史绿色。

## 来源与范围

- 原失败公共基线：`347bcd0226c3bed71616e38ea7c1391fc76efe5c`。
- 最终增量 parent：D 原 WIP `2b68c327d0c6f247e76af108700ca626b6fb7197`，其父为公共 helper `bc2b738fbd58dea6c8eabdb9d00eea22b21f364c`。
- 独立分支：`codex/architecture-day-a-topic-publication-final-347b`。
- 原实际失败来源：`run40220558637/job121251116626/artifact12609615426`，由 root 提供精确方法及首帧；本次没有重新执行或解析 XML。
- 仅修改 `TopicCreateOpenClubPoolTest`、`TopicCreateSaveDraftSideEffectTest`、`TopicUpdateByOwnerTest` 与本交付目录。
- 生产、`TopicPublicationTestWiring`、`TopicPublicationTestFixtures`、`TopicGraphTestWiring` 和其他 owner 文件不变。
- 已 fetch/逐 hunk 读 D helper 与 WIP，并先按原 blob 取用三个 owned 文件；更新类原 writtenChapters/writtenNodes insert/current-read 捕获及三类 `transactional(svc)` 均保留。最终片只提交相对 WIP 的三个 owned 文件及本目录，不包含供体另外四类或共同 helper 的再改动。
- 先行 `90e5c0bedf6d27e17a99109cd57d8678cae9af0b` 单独保留在旧独立工作树，未发布；它是供体到达前的施工快照，**不作为本次交付候选或要求 root 合入**。最终版已按 root 要求复用现成 helper，撤去逐类 H2/事务装配。

## 实际调用与失败机制

三个测试原来直接 `new CmsTopicServiceImpl`，通过既有 `TopicGraphTestWiring` 装配内容 authoring/publication owner，但没有跨过真实事务代理。

创建正式主题：`createTopic` → 原 authoring 保存章节/节点 → `persistTemplateInteractionRules` → `TopicPublicationService.persistCreatedRouteSnapshot` 或 `publishBeforeReview` → `prepareDeclarations` → `deriveTemplateCluesForPublication` / `deriveOptionTagsForPublication` → `listCurrentDeclarations` → 原完整冻结与 snapshot 写入。

本人重发：`updateTopicByOwner` → 原 owner/scope/编辑窗口/资金校验 → 原重建 → `publishBeforeReview` → 同一声明派生、当前读与冻结链。

当前生产强制上述派生和正式冻结加入写事务；编译器还需同一个 topic 的当前 topic/chapter/node/template/declarations。旧创建正例使用空章节；旧更新夹具缺 runtime、旧 SaveDraft 的引用 reader 还连着另一组空 Mapper。这些不足均由夹具接线修复，不删除生产 guard。

## 已写源码

1. 三类保留 D 供体 `transactional(svc)`，使用共同 helper 的真实 Spring/JDBC 事务 interceptor 与原 service owner。没有逐类重造事务设施、`setActualTransactionActive`、全量 mock freeze 或恒成功 snapshot。
2. 共同 helper 按真实非 mock `ThemeStateKeyService` 装配真实 `TopicRouteRuntimeServiceImpl`，由其真实 `TopicRoutePublicationCompiler` 编译；原 topic mapper、原 chapter/node insert 的实体和生成 ID 同源供给当前读。
3. 真实 `ThemeStateKeyService` 和 `TopicStateKeyRefService` 连接同组 Mapper；SaveDraft 改掉旧 reader 指向另一组空 Mapper 的装配。这些夹具原本没有作者状态键和绑定模板，普通节点的 `templateId` 保持 null，不填正模板 ID、不注入空 gameplay JSON。
4. 新建正例 DTO 使用一章一地点；原自由探索产品、招募截止、俱乐部/券归属与发布模式不变。草稿仍可没有节点；AI 简单版无地点的原负控显式继续使用空章节。
5. 发布 Mapper double 先读取并核对**实际 compiler 字符串**，断言原 Spring 事务及同源 DataSource 资源存在、快照版本与原生成节点 ID 一致后才确认该写入端口。OpenClub 另断无模板仍为 null；不是 mock compiler/freeze 的成功结果。
6. 更新夹具模拟原动态 mapper 的非 null patch 语义，使锁下回读看到真正保存的当前 topic 内容；原公开状态/重审次数/归属/商业审核/票价/库存等断言保留。

## 场景保留与新增源码

按源码 `@Test` 声明计数，未运行测试：

| 类 | 原声明 | 当前声明 | 原场景 |
| --- | ---: | ---: | --- |
| TopicCreateOpenClubPoolTest | 15 | 15 | 全保留：承接池/俱乐部绑定/自由探索/招募截止/章节招商/名额/券归属/AI 简单版负控 |
| TopicCreateSaveDraftSideEffectTest | 5 | 7 | 原草稿私有、pro 后审、simple 免审、自办壳及欠款闸全保留 |
| TopicUpdateByOwnerTest | 38 | 38 | 原本人/他人/删除/已开始/冻结白名单/资金/商业转型/重建/隐藏节点/路线重映射/字段省略保护全保留 |

原方法身份与业务断言由同一公共父 blob 和完整增量核对，不以声明数代替保留：

| 文件短名 | 347b 原 Git blob | D WIP Git blob |
| --- | --- | --- |
| TopicCreateOpenClubPoolTest | `18cf55aa65f6da61cf07d50ff4f080e704a36f08` | `84572046` |
| TopicCreateSaveDraftSideEffectTest | `6fc43cc70bd701fa6e3176ecb5d79418a46a8ca5` | `eb94cdb1` |
| TopicUpdateByOwnerTest | `d197f0f6fabd45ce76a42bb8d2bff8834c75911a` | `6658267e` |

三个文件没有删除/重命名原测试方法，没有删除原 assertion/verify。创建 DTO 仅补正式发布当前合同所需的实际章节/节点；原 absent-location 负控仍显式为空，保存草稿仍为空。最终 diff 不修改既有测试的 expected 来迁就发布失败。

新增两条普通 `@Test` 源码位于 SaveDraft：

- `publishedCreateWithoutNodesIsRejectedBeforeSnapshotOrPublicShell`：真实 compiler 拒绝无可运行节点，原 snapshot/public shell 不发生。
- `snapshotWriteFailureRollsBackOriginalSpringTransactionBeforePublicShell`：发布写入端口取得原 `ConnectionHolder` 后抛错；原真实 Spring interceptor 对同一 controlled JDBC resource 调用 rollback、没有 commit，活动壳与审核发送不发生。

## 验证边界

- 本次只做源读取/编辑、git diff/差异空白检查、后像摘要与秘密扫描；没有测试/编译/parse/import 控制。
- 本最终片没有 H2/新测试表或新 JDBC 设施；使用 D 已交 controlled 无 SQL Connection。
- topic/chapter/node 是原业务 Mapper doubles；新增 rollback 只覆盖真实 Spring 对其同一 controlled JDBC resource 的回滚调用，不声称物理 SQL rollback、不把内存对象当数据库，也不冒称 MySQL/全业务库已经验证。
- 三个正例仍执行真实 publication/content guard/runtime/compiler；审核、配额、券等原服务边界 doubles 保留原场景和 verifications，不借本片声明其实现通过。
- 无生产政策、资金、权限、表/迁移、依赖、回执/UNKNOWN 改动。最终同源编译与必要集中验收由 root 执行。
