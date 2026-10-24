# 上线前验收链路（2026-10-24）

被测：master `05c91471e0b0e8a34343065d777156d1063c94fc`（~03:33 UTC 手动派发）。
修复分支：`fix/acceptance-20261024`，头 `a9575d15fd53b49857a883a17e4f829acb867398`，draft PR #582。

**结论：三处失败都是夹具 / 断言过期（HARNESS/FIXTURE），没有发现产品 bug，也没有基础设施抖动；未改任何 main 代码。**

| run | job | 结果 | 根因 | 修复 | 重跑 run id |
|---|---|---|---|---|---|
| 40058692709 Chain Acceptance（through=finance） | Real-HTTP chains（platform / merchant_club / sale_close / player / finance） | ✅ success | — | — | — |
| 40058698680 Real Storage IT core-funds | 全部 class | ✅ success | — | — | — |
| 40058701509 Real Storage IT ai-quota | 全部 class | ✅ success | — | — | — |
| 40058704570 Real Storage IT e04-07-coupon | 全部 class | ✅ success | — | — | — |
| 40058707647 Real Storage IT community-e02 | 全部 class | ✅ success | — | — | — |
| 40058710772 Real Storage IT b05-visibility | 全部 class | ✅ success | — | — | — |
| 40058713881 Real Storage IT f3-growth | 全部 class | ✅ success | — | — | — |
| 40058716940 Real Storage IT extra-storage | FreeSelfPlayCreateRealDbIT | ❌ → ✅ | FIXTURE：生产 DDL 快照之后 `MmsMerchantMapper` 已读 `mms_merchant.wechat_qrcode` / `category_ids`（20261022 两份迁移），建单门禁 `MerchantBusinessStatusServiceImpl.isTargetMerchantClosed` 报 `Unknown column`，把后面整段建单覆盖都挡掉了 | IT 按真实落库顺序叠加 `migration_merchant_wechat_qrcode_20261022.sql`、`migration_merchant_category_ids_20261022.sql`（复用迁移原文件，没有手写 DDL） | 40059520152 ✅ |
| 40058716940 Real Storage IT extra-storage | GameplayReskinMySqlIT | ❌ → ✅ | FIXTURE：草稿发布读 `cms_topic.review_flag`（#380，`migration_audit_level_20261020.sql`），IT 手写 schema 缺这一列 | 按迁移同形状补列 `TINYINT NOT NULL DEFAULT 0`（该迁移的守卫还要求 `community_post` 表，本 IT 不建这张表，所以没整份重放） | 40059520152 ✅ |
| 40058695771 Gameplay HTTP fixtures | http (GenericGameplayHttpE2eIT)：`realHttpUsesTheProductionControllerAndRejectsAnonymousState`、`terminalVoteAdoptionUsesThePersistedFinalChapter` | ❌ → ✅ | 断言过期：owner 2026-10-22 裁决（cc79f341 / L07，见 `TopicTemplateVisibilityContractTest`）让 `/api/template/topic-template/use` 与货架同口径，只要求已审核、在架、是模板，不再要求 VERIFIED。夹具仍断言「无 VERIFIED 必拒」，实际返回 200 | 按新口径改写：另存为模板之前直链 `/use` 必须被拒（「该主题不是可用模板」）且不落新行（负控保留）；另存之后复制成功，新行归属本人，源主题 `theme_runtime_config_json` 不变 | 40059522017 ✅ |
| 40058695771 Gameplay HTTP fixtures | http (FreezeReuseThemeHttpE2eIT) / http (FreezeGameHttpE2eIT) | ✅ success | — | — | 40059522017 ✅ |

## 本地验证
- `LANG=C.UTF-8 mvn -q -DskipTests -pl chengyinhub-admin -am test-compile`：exit 0
- `python3 ci/real_storage_it.py precheck`（两个改动 class）：exit 0；`python3 -m unittest ci/test_real_storage_it.py`：44 项 OK
- 本地没有 MySQL / Docker 守护进程，真库验证以上面的重跑为准

## 留给 owner
- 没有产品 bug 需要处理。
- `/use` 现在允许复制「已发布、未 VERIFIED」的模板，这是 10-22 的裁决。本次只是让 HTTP 夹具和这条裁决对齐；如果裁决本意不包括 `/use` 直链复制，需要 owner 另行确认。
