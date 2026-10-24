# Task 2：真库夹具与发布迁移包

PR：[hengyin #584](https://github.com/liboyang42-cpu/hengyin/pull/584)，分支 `codex/launch-real-storage-migration-20261023`。不合并、不部署、不上传微信、不连接生产。

## 源与范围

- 独立分支起点：当前公开 master `05c91471e0b0e8a34343065d777156d1063c94fc`；tree `f5444ba4712d828644b141f58cf89f7d861bdb73`。
- 仓库参考基线保留 `19408d49172c1d8fd16ec012f4498ff8ea584a37`，不声明生产已应用。
- 迁移包已刷新到合入 #567 后的当前 master `11c8055e179930cd63a1d6e296b8285b0586dd16`；源 tree `99042a25713445028217379d87fc8ba83cb14562`，SQL 子树 `15977e02aa702b90b34c46d6f13c7b2ee89d51b7`；依赖台账 SHA-256 `59fa9f5ff6f26511e88a52b517cebf00411466ea834f1918631bad381b5ec9f7`。
- 按当前台账重算的发布集为 35 项（34 新增、1 个相对参考基线的既有历史差异、0 删除），全量受管迁移 520 项。数量由源树与台账计算，不作为断言常量。
- 已纳入 `migration_20261023_points_value_config_seed.sql`；尚未合入上述源的支付或其他新迁移不在本覆盖范围；协调者整合后须使用 `--refresh-pack` 统一刷新并重新演练。
- 本补丁不编辑任何历史 SQL、不改 mapper、支付代码、RBAC、登录或业务规则。

两类夹具补齐当前 mapper 所需的真实迁移。Gameplay 使用平台迁移中的完整 `community_guideline_version` / `community_post` CREATE TABLE 及外键，再执行完整审核列迁移；不另造缩小 schema，不执行无关社区历史回填。FreeSelfPlay 使用真实商家两列迁移，保留形状守卫与回填。两类原有独立清理及清理后回读保留。

包、阶段、风险、预检、预期状态和文档同步更新。商家两项包含数据迁移，主分类只核结构并保留 DATA_PREVIEW，另有真实回填值回读。welcome_note 原 SQL 不可重跑，保留原字节与预期 1060 负控，仅允许 APPLIED 预检后跳过，且与实际执行逐项分别记账。

另按协调者明确要求，仅纳入 #582 的 `GenericGameplayHttpE2eIT.java` 过期断言修复。已核对当前 `TopicTemplateVisibilityContractTest`、`publicTopicTemplateEligibility`、`ApiTopicTemplateController.use/saveAs` 与复制服务：公开模板准入是未删除、is_template=1、status=1、user_status=1，不要求 VERIFIED。保留未标模板直链拒绝/零增行、save-as 后非 VERIFIED 也能复制、单行新增、owner 与源配置回读。没有改变产品准入或授权。

#582 的历史 [run 40059522017](https://github.com/liboyang42-cpu/hengyin/actions/runs/40059522017) 实际 source 是 `a9575d15fd53b49857a883a17e4f829acb867398`，不是其后来的 `9aac7e65…`。已查证该次 HTTP 三类 job 与 summary success，且 HTTP 测试文件 blob 与 #582 后续头一致；这仍不是 #584 或整合候选的实跑证据。

## 实际验证证据

| 层级 | source / 入口 | 当前证据 |
|---|---|---|
| 基线失败 | `05c91471e0b0e8a34343065d777156d1063c94fc`；[run 40058716940](https://github.com/liboyang42-cpu/hengyin/actions/runs/40058716940) | 原始两类缺列失败；不是本补丁结果 |
| 托管 scratch MySQL 两类 | `50588bf1ea7b5721c55d46ba6ed65136759e3360`；[run 40061981677](https://github.com/liboyang42-cpu/hengyin/actions/runs/40061981677) | 两类 job 均 success，批次汇总尚待回读 |
| FreeSelfPlayCreateRealDbIT | [job 120777335726](https://github.com/liboyang42-cpu/hengyin/actions/runs/40061981677/job/120777335726) | 日志确认 SOURCE_SHA=50588bf1…；Tests run 1 / failures 0 / errors 0 / skipped 0；BUILD SUCCESS；PASS 1/1，cleanup CLEAN |
| GameplayReskinMySqlIT | [job 120777335728](https://github.com/liboyang42-cpu/hengyin/actions/runs/40061981677/job/120777335728) | 日志确认同一 SOURCE_SHA；Tests run 1 / failures 0 / errors 0 / skipped 0；BUILD SUCCESS；PASS 1/1，cleanup CLEAN |
| 当前 HTTP 测试 | 本分支新增的单文件断言修复 | NOT_RUN，待协调者现有 hosted HTTP 入口验证 |
| 当前包 Python 静态回归 | `python3 -m unittest -v ci.test_release_migration_rehearsal`；保留原 6 项并新增 5 项 | NOT_RUN，待当前包提交触发的现有 hosted workflow 回执；未使用 shell |
| 当前包 hosted MySQL 演练 | `release-migration-rehearsal.yml` | PENDING；需实际证明首次全部落库、welcome_note 原始重复列拒绝、受控跳过、其余真实重跑、所有负控和风险探针 |
| VPS | 本任务未使用 | NOT_RUN |
| 生产 | 本任务未连接 | UNKNOWN，未执行 SQL |

后续提交保持 Java 夹具字节不变时，上述两类回执只证明 `50588bf1…` 的已测输入；不能称为新 head 的重新实跑，更不能外推为整合候选通过。最终交付 head 以 PR 的实际 head SHA 为准，验证记录必须保留各自的精确 source。

## 环境限制与复现

云执行环境返回 `observed_phase=pending`、`connectivity=offline`；终端请求返回 `No usable execution environment is available`。本阶段通过 GitHub 连接器读取精确源、审阅并提交；没有声称本地编译、Python 或真库已运行。没有创建替代环境、添加依赖或启动额外资源。

合并新迁移后，先人工补齐新增项风险/阶段/回读合同和预检分类，再运行：

```sh
python3 ci/release_migration_rehearsal.py --inventory
python3 ci/release_migration_rehearsal.py --refresh-pack
python3 -m unittest -v ci.test_release_migration_rehearsal
git diff --check
```

刷新器只写包 JSON、只读预检分类顺序和文档生成区；新/缺元数据或预检不齐时明确拒绝，不绕过失败。真实数据库验收只使用已批准的开发/测试 VPS 或现有 GitHub 托管 CI。生产所有项始终 UNKNOWN。
