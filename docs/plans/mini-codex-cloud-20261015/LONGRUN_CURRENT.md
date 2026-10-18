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
