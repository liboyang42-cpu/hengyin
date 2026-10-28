# WP11 后续查询次数与权限集合验证准备

结论：新增 `ImConversationQueryScopeMySqlIT` 的 7 个测试已准备；没有修改 WP11 生产实现、既有测试、缓存或已发布候选。Java、MySQL、EXPLAIN 均未执行。此批只准备进一步受控验证，不能作为全部查询 O(1) 或真实跨域权限已验收的证据。

## 冻结身份与本批范围

- 公共基线：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本地实现候选 `4516029348b12dc7b784b03f1cb640a06bfb7488` 保持冻结。
- GitHub 插件发布的实现候选 `3c48996106c63ee75d4d1c93403396a3d7a8bf15` 与本地不是同一 commit；两者完整 tree 均为 `0d72d6e273d4aacd8f6560676aac0f9c21b58a2e`，唯一 parent 都是公共基线。远端实现分支保持不动。
- 首批缓存 tree `9fcff59e1dfe0da5fbf265125d35acd30a7214b1` 保持冻结，未混入 IM 分支。
- 测试准备分支：`codex/architecture-day-e-wp11-scope-tests`，本地目录 `/workspace/hengyin-e-wp11-tests`，从本地冻结实现候选建立；它与已发布实现拥有同一初始 tree。此批仅准备测试 tree，不创建新 commit/ref、不改实现分支。若协调者随后创建新的完整 CI 候选，仍须以公共基线 2ec 为 parent，并另记身份映射。
- 唯一新增 Java 文件：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImConversationQueryScopeMySqlIT.java`。其余新增仅在本交付目录；不改其他任务文件、生产 SQL/Java、CI、schema、依赖或既有门禁。

## 测试矩阵（全部未运行）

| 用例 | 结果集合/副作用断言 | 查询边界 |
| --- | --- | --- |
| 当前读者成员集合 | 读者 1/2/3/4/空账号各自精确会话 ID 集合；无重复；删除成员/关闭会话后精确排除 | 每次真实 IM batch statement 恰为 1 |
| 待审图片集合 | 自己发送的待审图片可见、别人不可见；非成员即使 sender 相同也无摘要；放行后按 visible_at 更新 | 每次 batch 恰为 1，核对具体 latest ID |
| 红点筛选集合 | 静音、群、软删、关闭、孤儿和外部成员不计入；其他非群 type 延续旧口径；读取前后未读/游标/资格数据逐行相同 | SUM 恰为 1，不加载列表 |
| 路线读者投影集合 | 相同真实 IM 卡片在不同当前主题资格下公开名/不可用投影；原 content/extra/hash 不改 | 真实 IM 1 条，另显式断言每读者一次 topic mock 调用 |
| 群读者资格集合 | 相同 IM 成员索引，按当前 club 资格返回精确不同集合；红点仍排除所有群 | 真实 IM 1 条；读者 1 另有 3 次 club + 2 次 club-member 调用，读者 2 各 2 次，明确非全部 O(1) |
| 不同列表大小 | 混合单聊/系统/客服，N=0/1/64/257 时返回精确集合且无重复，未读总数正确 | 列表/SUM 分别 1 条；只约束 statement 次数，不推导时间/容量 |
| EXPLAIN 观察 | 实际生成批量 SQL 和 SUM 的 EXPLAIN FORMAT=JSON，与当前 git HEAD/VALIDATED_SOURCE_SHA 一致的回执 | 仅记录执行计划，不设拍脑袋 rows/cost/RPS 阈值 |

真实层：沿用既有受控 MySQL 8+ 独占 UUID 库及三个 IM mapper，成员/消息可见性过滤与 statement 计数是真 SQL。群/路线用例仍执行原业务守卫，但跨域 club/topic mapper 是显式 Mockito，**不能冒充真实跨域存储权限验证**。全链路群撤权竞态、主题资格变化竞态仍需真实域存储和事务测试。

测试复用已存在的 `ImConversationBatchReadMySqlIT` 夹具，不新增通用测试框架；每个套件实例创建自己的随机库。所有连接仅为受控端 loopback+显式端口，缺开关/配置失败，不 Assume/skip，不从生产复制数据或凭据。临时库 DDL 仅在测试源码中，本地没有执行 Java/SQL。

## 执行交接

在已批准 VPS / GitHub-hosted 执行端设置 `CHENGYIN_IM_BATCH_MYSQL_IT=1` 和既有显式 `CHENGYIN_MYBATIS_IT_PORT/USER/PASSWORD`。EXPLAIN 用例另要求 `VALIDATED_SOURCE_SHA` 为本次实际 checkout HEAD，测试会执行只读 `git rev-parse HEAD` 并比较。

```sh
mvn -B -pl chengyinhub-admin -am test \
  -Dtest=ImConversationQueryScopeMySqlIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

核对新鲜 XML：恰好 7 例，failures/errors/skipped 都为 0，并收取 `chengyinhub-admin/target/surefire-reports/ImConversationQueryScopeMySqlIT-explain.json`。EXPLAIN 回执必须带真实 MySQL 版本和被测 SHA，执行计划仍须人工/数据量复核，不代表性能验收。若与前批 87 例一起执行，总计 94 例；既有发送 IT 还需 `CHENGYIN_IM_SEND_MYSQL_IT=1`。

前批 `check_static.py` 可继续核对被冻结的八文件；前批 `scan_candidate.py` 是八文件范围门禁，不能拿它证明本批新增测试已被扫描，也不修改它来扩大旧候选范围。本批范围/扫描结果另记，不放松任何既有 baseline。完整 gitleaks 仍待根协调者用固定版本复扫；有限模式扫描不能写成完整秘密扫描通过。

## 尚未验证

未运行 Java 编译、7 个新增用例、真实 MySQL、跨域真实权限、EXPLAIN、负载/堆/延迟验证。没有引入缓存、索引/DDL 或改变权限政策来争取查询预算；没有合并、部署或触发真实外部动作。
