## 2026-10-18 两项既有合同已确认：NO_SOURCE_CHANGE

两项真人选择已由本地协调者明确转交、C00实际签收并更新原剩余表及owner记录：[决定与签收](CORE_COMPATIBILITY_DECISIONS_20261018.json)。

- OWN-C04-01：保留同玩家／同NPC／同请求号重放第一次结果，新问题换新请求号；原scope检查与既有terminalReplay回归合同不变。不加内容指纹、列或迁移，不推测旧回执身份。收口 **NO_SOURCE_CHANGE**，不计新增修复BUG。
- OWN-C05-22：保留照片AI异常退还照片池次数，沿既有照片合同；不改为通用网关UNKNOWN占用，不改降级／放行／奖励。收口 **NO_SOURCE_CHANGE**，不新增政策源码、迁移或付费调用。

CORE这两项待真人裁决数现为 **0**；历史BLOCKED提案保留，原owner与文件锁不变，不唤起作者重扫或测试、不复跑旧PASS。当前已确认可行动未交源码仍0，不造任务。四夹具源码已交；B05仅test-compile PASS／实际0，E02空间自然恢复后已沿原212完成test-compile PASS／实际0，原ENOSPC保留历史；各3项真MySQL仍NOT_RUN，现有入口不新增，验证状态未受本决定改变。下方旧“待真人选择”只为历史，已由本段覆盖。

# 当前恢复入口：四文件源码完成，两个独立候选均仅编译通过

四文件 grant、逐作者 ACK 与源码交付均已完成，原作者停写、独占锁保留。C00已核读 PR24 固定证据 [`8fdb9fdb72975f7617a4ac70eb4e4eeacd27c3ac`](https://github.com/liboyang42-cpu/hengyin/commit/8fdb9fdb72975f7617a4ac70eb4e4eeacd27c3ac) 的两份 README/summary；E02后续恢复编译的新证据为 [`90b81ee7af715f0c72598a7b7a9f59e235910ea6`](https://github.com/liboyang42-cpu/hengyin/commit/90b81ee7af715f0c72598a7b7a9f59e235910ea6)，C00亦已核读其README/summary。以下为当前结果，后文较早“均未编译/待 ACK”仅为历史快照。

- CORE/B05 源码 `2ac55d20a9f4932be8d411e53191c69e5141772c`：现有212 `test-compile` **PASS**，guard0/completed/BUILD SUCCESS，指定 fresh class1；无 Surefire XML，实际测试 **0**，原3项真实MySQL仍 **NOT_RUN**。见[B05编译证据签收](B05_2AC55_TEST_COMPILE_RESULT.json)。
- EXTRA/E02 源码 `6c44636974220ca9876386ee638c99981488db42`：4328文件再次逐hash一致，资源自然恢复后沿原212于02:23:12.350784Z—02:25:47.051161Z完成 **test-compile PASS**，guard0/completed/BUILD SUCCESS，三个指定fresh class及hash已核；无Surefire XML，实际测试 **0**，真MySQL3项仍 **NOT_RUN**。精确自有容器已确认清理，当前无活动Java。见[E02恢复编译签收](E02_6C446_TEST_COMPILE_RESULT.json)。原8fdb ENOSPC准备失败、原summary及远端preparation-result-disk-full-020540Z.json完整保留在[历史准备回执](E02_6C446_PREPARATION_BLOCKED_RESULT.json)，不再作为当前磁盘阻塞。

原空间阻塞已自然恢复并完成未完编译；无需源码修复槽、不再重试或Cloud替代，不新增入口/网络/CI。两个候选仍为同父26d的独立分支，未组合成四文件集成候选。原失败历史保留；无真实MySQL/Redis、HTTP、设备业务、迁移、合并或部署验收。两项CORE业务选择均已由真人确认保留现状，按CORE_COMPATIBILITY_DECISIONS_20261018.json收口NO_SOURCE_CHANGE，无待裁决源码。无需EXTRA为本回执重复改本队文档。完整索引见[四文件批次](FOUR_FIXTURE_SOURCE_BATCH.json)。

## 先前记录（按原SHA保留历史，不覆盖上述当前状态）

# 当前恢复入口

E02协调者已将原child f74的完全相同3个补丁blob串行交到现有PR36：完整rolling源`6c44636974220ca9876386ee638c99981488db42`，父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`。C00已实际读完整commit并逐blob SHA核与原child相同；没有新增源码或测试结果。CORE/B05 `2ac55d20...`是同父的另一单文件候选，未静默合成四文件分支，仍各STATIC_ONLY/实际0。下文f74保作原作者child交付来源，以本行rolling完整SHA作为EXTRA完整候选。

四个旧夹具均已在公共grant fa3f及逐作者ACK后源码交付：[批次](FOUR_FIXTURE_SOURCE_BATCH.json)。原E02三文件`f74dd9be0c17a17db506ee8e0c46c60804a1a785`（父`9a27f6b0d8fe532320cbe646775464cf49ba1d10`，PR30）；CORE/B05单文件`2ac55d20a9f4932be8d411e53191c69e5141772c`（父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`，独占branch）。总共4文件各仅+1空表行/-0，逐文件移除新增行即恢复原文；原gate/6方法/全部断言和created清理不变。原E02已明确B05冻结交接，双方完成停写/锁保留。均STATIC_ONLY、实际测试0/未编译/真库未验，不把6143普通4PASS或b034普通8PASS套用。无新生产BUG/新入口/合并部署。

## 四文件精确交付

| 责任 | 公共源码/直接父 | 原作者接收与范围 |
|---|---|---|
| 原EXTRA/E02三夹具 | `f74dd9be0c17a17db506ee8e0c46c60804a1a785` / `9a27f6b0d8fe532320cbe646775464cf49ba1d10` | E02_01Test/E02_11Test/E02_06Test，各一行；原作者亲自读完整fa3f后ACK开写并停写 |
| CORE/B05单夹具 | `2ac55d20a9f4932be8d411e53191c69e5141772c` / `26d8245b458d18497eb9040a4c7d9f1509b41bb1` | B05CommunityVisibilityMySqlIT仅一行；原测试创建者EXTRA/E02已冻结交接，新接收CORE已明确ACK，不冒称原作者在线 |

完整路径/基线hash/门禁/文本前提见[精确grant](FOUR_FIXTURE_MINIMAL_GRANT.json)、[E02交付](EXTRA_E02_THREE_FIXTURE_SOURCE_DELIVERY.json)、[B05交付](CORE_B05_FIXTURE_SOURCE_DELIVERY.json)。45开放PR全分页仅原PR30和集成PR36触达，无第二writer。新4文件不含已完成6c6的E02_25MySqlIT；未改任何生产/依赖/SQL mapper/gate/方法/断言。各自created保护清理本就正确，不改以免误伤别库。

新夹具均SOURCE_DELIVERED_UNVERIFIED/STATIC_ONLY，实际0/未编译/真实MySQL NOT_RUN。仅局部建表字符串修正不需要重跑旧普通4项来冒充真库。原作者源writer当前0，锁保留；EXTRA协调者维护本队rolling源码/三账，C00只全局账与CORE精确B05分支，未强改对方分支/合并代码。双方内部协调走私有聊天和授权读取，不发公开PR内部协调评论；非敏感源码/授权清单按用户要求持久化公共GitHub。

## 本次补齐的独立证据

[公共证据索引](RECENT_EXTERNAL_EVIDENCE_ACK.json)逐份README/summary已由C00实际读取，未取原VPS日志/XML重解析、未Cloud补跑。

| 固定源码 | 实际范围 | 结论与保留历史 |
|---|---|---|
| 923170abd8e468d62eb8404b16b990609a4da42a | 6类35=28P/0F/7E/0S | H2别名夹具导致2类7异常，原失败和两次0项内存拒绝保留；不是35项均失败或7个生产BUG |
| b034a4aabdacffbf7a2ef4f400b56eb2c6eed6cf | 原两异常类8实际PASS | 原E02已ACK并释放修复槽；不新35全跑或继承旧28 |
| 530e72281a9bd1a443f58a21119367d55eca3623 | E01/E03直接影响4类60实际PASS | 原59静态漏一个全限定@Test、初始收集器NOT_PASS与计数审计保留，无源码改/重跑 |
| 6143c37f3c32753bd22208ace9c4d3c4520b9fd6 | E02_03Test4实际PASS | 仅H2生产XML合同；不是真MySQL/HTTP/完整业务 |
| 6c6f82d7d9049f3f2d65f609be6b01c4a5643ec4 | E02_25MySqlIT单行前提 | STATIC_ONLY，实际0/未编译/真实MySQL未跑，不继承6143的4PASS |

E02旧18声明遗漏6CsvSource调用，已更正24；再加E04普通11为原35实际选择。只算@Test注解不是执行调用数。各SHA成绩不跨候选累计；旧CORE5752定向31、adf51失败、EXTRA1bfa2、第三d592编译0/真库11未跑等历史继续原证据保留。

## 可继续范围与验证约束

E01-13/E03-04/E02-03合同已由后续真实候选交付并各有60/4普通证据，不再说待产品裁决。复用现有CORE清单，当前合同明确+精确写权可继续生产/架构项0，NPC不同命令请求身份和AI发送后计次政策仍未决，不泛审计或补任务凑数。F3 READY0：最大日期缺真实MySQL DATE_ADD/warning/原指标对照且聚合Mapper表外；RR缺实际隔离与合法并行caller，不由当前packet直接改生产。

本地root统一现有212必要新源验证；Cloud不Java/Maven/Node/编译测试/lint/手动CI/DB/HTTP/VPS。用户已定“保持现有入口，不新增”，新真库入口方案关闭、不再催批，212/CI/网络不改，未覆盖实库继续NOT_RUN_EXISTING_ENTRY_COVERAGE_GAP。既有自动CI自然运行不等于当前候选有实际结果。

既有三项身份/券决策源码链A dedb→E02 13be→B9231保留，各候选/层级分别登记；B未来history_id迁移仅源码，旧行NULL不回填，未执行迁移/合并部署。Claude UI/新功能、原生iOS独立、Flutter排除；最多6个gpt-6.1-sol/队，失败仅原作者1修复槽。运行HTTP/真实身份、设备业务、跨候选集成仍未验，TEST_DEPLOYED=NO。见[源账](LONGRUN_SOURCE_OWNERS.json)、[余量](LONGRUN_REMAINING.json)、[验证移交](BACKEND_VALIDATION_VPS_HANDOFF.json)。
