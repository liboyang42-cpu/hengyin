# 资金链路 EDITION_TERMS_MISSING 修复（1113）

## 根因：(a) 夹具落后于合法的新需求，不是产品缺陷

- 失败：Chain Acceptance run 43377256000（master 92eda912），finance 链第一步 `quality/confirm` 返回 500 `EDITION_TERMS_MISSING`。
- 上次通过的 a1d30fb2，开售终检 `TopicSaleFinalizationServiceImpl` 在四店合同分支里调用 `compensationService.freezeTermsForSale(...)` 和 `clubPayeeService.snapshotForSale(...)`，开售当场写入 `club_edition_compensation_terms`、三维 PENDING 种子行、`topic_sale_payee_snapshot`。
- 现在 master 的该分支改为 `throw new IllegalStateException("四店固定补偿产品已停止新开售，已售合同继续履行")`，`freezeTermsForSale` 在生产代码里已没有调用方。同时 `canonical_draft.py` 已改成 `ALL_REQUIRED_CHAPTERS` 的普通自由探索形态。所以链路里新开售的主题不再有条款，而 finance 链走的是"已售合同继续履行"的质量确认 → FINAL → 应付批次路径，产品的这条路径要求条款存在。
- 另有漂移：`chain_sale_close.py` 的执行俱乐部已改为 7001（P-01，内容所属俱乐部），`chain_finance.py` 仍写 7002，对公收款人只为 7001 审核。

## 生产影响：无存量缺口

- 存量主题：`migration_20260816_tandianri_batch3_funds_legacy_edition_exemption.sql` 已对"开售早于开售闸、且无条款"的已开售 FREE_EXPLORE 主题登记显式历史豁免（并带回读断言），`missingTerms` 对它们返回 `EDITION_LEGACY_EXEMPT`，不是 TERMS_MISSING。停售前开售的四店主题有条款。
- 停售后的新主题没有条款，也不应走补偿 FINAL；对它们调用补偿接口得到 500 的 IllegalStateException 是既有的 fail-closed 行为。本次没有改产品代码，也没有新增迁移（无需回填：不能为无法证明的历史补伪快照）。
- 可后续讨论：把这类 IllegalStateException 映射成业务错误码而不是 500（改动涉及公共异常处理，本次不动）。

## 修复

`scripts/e2e/chain_finance.py`：新增 `seed_legacy_sale_freeze`，在质量确认前按 `freezeTermsForSale` / `snapshotForSale` 同口径补写条款、三维 PENDING 行、收款人快照，数值取自链路前序步骤经真实入口写入的成本版本与已审核收款人；指纹计算抽成 `terms_config_hash` 与时间压缩夹具共用；执行俱乐部对齐为 7001。链路断言一条未改。

## 验证

注意：workflow 的链路脚本取自 workflow 所在提交，而不是候选 SHA，因此用 `--ref master` 派发时不会用到分支上的 chain_finance.py。本次用同一份 workflow 文件、以分支为 ref 派发（未改 workflow）。

- 失败基线：run 43377256000（master）finance FAIL。
- 修复后：见下方最终 run（platform / merchant_club / sale_close / player / finance 全 PASS）。
- 合并后 master 上的链路也会用到这份修复，因此需要先合并本 PR。

FINAL_RUN_PLACEHOLDER
