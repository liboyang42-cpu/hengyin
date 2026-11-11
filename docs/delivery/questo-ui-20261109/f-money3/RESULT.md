# f-money3 结果：自由探索主题禁止收费自玩票（发布 / 编辑 / 锁价 / 开售失败关闭）

分支 `claude/f-money3-20261111`，从 `origin/claude/f-money2-20261111` 切出。依据：f-money2 RESULT 第五节 2，业主默认「在发布侧加闸」（待复核，未回复视为同意）。

## 一、先看结论

1. **收费自玩票只会从一个地方进入自由探索主题：定价页「无人带」页签。** `confirmPrice` 会把无人带价写进 `cms_topic.self_play=1 / self_play_price`，还会用它补齐未定价的门票。其他入口都写不进去：
   - 主题 v2 创建 / 更新原来会静默落 `self_play=0`。
   - 后台编辑 DTO 没有自玩字段。
   - 后台新建不写自玩字段。
   - 开新一期只复制开关，不复制价格。
2. **后端统一用一处判据 `FreeExploreSelfPlayPricePolicy`，各入口全部失败关闭。** 判定条件是 `product_type=2`、`self_play=1`、`self_play_price≠0`（负数也算）。提示统一为「自由探索主题不能设置收费自玩票，请改为给主题门票定价」。
   - **主题创建 / 更新（发布和存草稿都算）：** 拒绝并返回 `issues[]{code: FREE_EXPLORE_SELF_PLAY_PRICED, field: selfPlayPrice, message}`。更新请求没带 `selfPlay` 时，会沿用库里原来的值，所以存量违规行编辑时也会被拒，不再被静默改成 0。
   - **发布检查与发布闸（`publishReadinessProblems` / `assertPublishReady`）：** 列出同一条 issue。
   - **锁价 `confirmPrice`：** 无人带价非零就拒。这道闸放在原有各闸之后、任何写库之前，原有拒绝原话和顺序都不变。定价预览新增 `selfPriceError`，内容与这句提示相同。
   - **开售终检 `finalizeForSale`：** 行锁读到违规行就拒，排在审核源、合作单、开售 CAS 之前。
3. **小程序：**
   - **定价页：** 自由探索主题切到「无人带」时，终价栏下方显示红字（`cy-inline-error kind="field"`，不弹窗），确认按钮置灰。切回「有人带 / 门票」后红字消失。经典定向不受影响。
   - **发布编辑器：** 自玩票栏位已在 a5-v3-29 下线，没有可以挂红字的字段。草稿里残留的 `selfPlay*` 现在只在经典定向时上送，作者不会被一个看不见的字段卡住。自由探索上送 0，库里的结果与改动前一样（后端原来就会落 0）。
4. **不改存量数据，没有新迁移。** 存量违规行只会在编辑、发布、开售时被拒，判断依据见第三节的只读预检 SQL。

## 二、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| FM3-1 后端判据与发布 / 编辑闸 | fixed | 新判据类，新 issue 码 `FREE_EXPLORE_SELF_PLAY_PRICED`。创建 / 更新在原 M2 自玩分支前调用；发布检查加入同一条 issue | `FreeExploreSelfPlayPricePolicy.java`（新）、`PublicationProblem.java`、`CmsTopicServiceImpl.java`、`TopicPublicationContentGuard.java` | `FreeExploreSelfPlayPricePolicyTest`（3，新）、`FreeExploreSelfPlayPublishReadinessTest`（2，新）、`TopicDraftLegacyFieldPreservationTest`（+1 拒绝用例；原「旧票 mode 选产品类型」用例改为显式 `selfPlay=0`） |
| FM3-2 锁价与定价预览 | fixed | `confirmPrice` 遇到自由探索 + 非零无人带价就拒；`previewTopic(subType=self)` 返回 `selfPriceError` | `PricingServiceImpl.java`、`PricingResult.java` | `FreeExplorePriceLockAcceptanceTest`：+2 条（拒绝零写入、预览同句）。原来 3 条成功用例在自由探索上传无人带价，改为用门票价，并断言不写自玩列。原有 6 条拒绝用例的原话不变 |
| FM3-3 开售终检 | fixed | `finalizeForSale` 遇到违规行就拒，审核、合作单、CAS 都零调用 | `TopicSaleFinalizationServiceImpl.java` | `TopicSaleFinalizationServiceImplTest` +1 |
| FM3-4 小程序定价页与编辑器 | fixed | 无人带红字加置灰；编辑器残留自玩票只在经典定向上送 | `pages/topic/pricing/index.{js,wxml}`、`pages/publish/fabu/index.js` | `tests/unit/f-money3-free-explore-self-play.test.js`（4，新）；`publisher-free-identity-contract` 改了上送正则；UI 基线和操作台账按门禁重算 |
| FM3-5 真 MySQL IT | 已登记，**本线未执行** | `FreeExploreSelfPlayPriceGateMySqlIT`（4 条）：真 `CmsTopicMapper` XML。覆盖生产 `finalizeForSale` 的拒绝、原行逐列不动、改免费后放行到下一道闸；生产发布检查读真行；本文件第三节的预检 SQL 原文在真 DDL 上执行（只命中违规行，前后 `CHECKSUM TABLE` 不变）。新批次 `launch-f-money3` | IT、`ci/real-storage-it-registry.json`、`ci/test_real_storage_it.py`、`.github/workflows/real-storage-it.yml` | 编译通过；`python3 -m unittest ci.test_real_storage_it` 92 条通过。本会话不允许启动 Docker daemon，没有 MySQL，IT 没有跑过，执行证据以真库 IT 工作流的回执为准 |

## 三、生产只读预检 SQL（找出存量违规行）

只有 SELECT，先查 information_schema。①少于 12 行就先停，不要跑②③（说明列名和本仓 schema 对不上）。在 chengyinhubdb 的只读账号上执行，经 chengyin-db-migrate skill 只读预检流程。本仓不写主机地址。

```sql
-- f-money3-precheck:begin
-- ① 列存在性：应返回 12 行(cms_topic 10 列 + cms_registration 2 列)
SELECT TABLE_NAME, COLUMN_NAME
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND ((TABLE_NAME = 'cms_topic'
         AND COLUMN_NAME IN ('id', 'name', 'member_id', 'club_id', 'product_type', 'self_play',
                             'self_play_price', 'lifecycle', 'user_status', 'del_flag'))
     OR (TABLE_NAME = 'cms_registration' AND COLUMN_NAME IN ('topic_id', 'payment_status')))
 ORDER BY TABLE_NAME, COLUMN_NAME;
-- ② 自由探索 + 自玩开 + 非零自玩价(含已删除行,del_flag 供判断)
SELECT t.id, t.name, t.member_id, t.club_id, t.product_type, t.self_play, t.self_play_price,
       t.lifecycle, t.user_status, t.del_flag
  FROM cms_topic t
 WHERE t.product_type = 2
   AND t.self_play = 1
   AND t.self_play_price IS NOT NULL
   AND t.self_play_price <> 0
 ORDER BY t.id;
-- ③ 这些主题上已产生的自玩通行证订单(topic_id 只由自玩通行证写),按支付状态计数:2 已支付 4 已退款
SELECT r.topic_id, r.payment_status, COUNT(*) AS orders
  FROM cms_registration r
  JOIN cms_topic t ON t.id = r.topic_id
 WHERE t.product_type = 2
   AND t.self_play = 1
   AND t.self_play_price IS NOT NULL
   AND t.self_play_price <> 0
 GROUP BY r.topic_id, r.payment_status
 ORDER BY r.topic_id, r.payment_status;
-- f-money3-precheck:end
```

- **②为空：** 生产上没有这种组合，闸只防新增。
- **②有行、③没有已支付单：** 处置是请作者在定价页改用门票价，或由运营把这行的自玩价清 0。这一步要改数据，需要你另行批准，本线不做。
- **③有已支付单：** 这些钱就是 f-money2 第五节 2 里「没有结算出口」的款，需要人工结算或退款，请你定。

## 四、待你决定（括号里是现在的默认）

1. **业主默认「拦」本身。**（默认：已按「拦」实现。如果你改成「允许并定结算规则」，删掉 `FreeExploreSelfPlayPricePolicy` 的调用点即可回退，其他代码不需要改。）
2. **存量违规行的处置。** 先跑第三节 SQL。（默认：不改数据。这些行在编辑、发布、开售时会被拒，并提示原因。）
3. **定价页「无人带」页签在自由探索主题上是否直接隐藏。**（默认：不隐藏，只显示红字并置灰，与「不弹窗、字段下红字」的口径一致。隐藏属于 UI 改版，需要你定。）
4. **编辑器实名判定。** `topicRequiresIdentity` 仍按草稿里的 `formData` 判断。自由探索草稿如果残留收费自玩票，客户端会多要求一次实名，后端按实际上送判断，不会因此误放行。（默认：不改，偏严。）

## 五、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -q -DskipTests install` | 通过 |
| 后端相关单测（`chengyinhub-admin` 中涉及 confirmPrice / finalizeForSale / createTopic / updateTopicByOwner / 发布闸的 75 个类，加上新增和改动的类，共 826 条） | 821 条通过。失败的 5 条都在 `PublicVisibilityMyBatisTest`，原因是连不上 MySQL（Communications link failure），本机没有库，与改动无关 |
| 真 MySQL IT | **没有执行**：本会话不允许启动 Docker daemon。`FreeExploreSelfPlayPriceGateMySqlIT` 已编译、已登记，等 `real-storage-it.yml` 批次 `launch-f-money3` 跑出回执 |
| `python3 -m unittest ci.test_real_storage_it` | 92 条通过 |
| `python3 ci/test_ci_cost_contract.py` | PASS |
| 小程序 `node --test "tests/unit/**/*.test.js"` | 17231 条。台账重建前只有 1 条失败（action-ledger inventory），重建后 `action-ledger.test.js` 48/48 通过 |
| 改动涉及的小程序测试（定价 6 个、发布者实名 2 个、编辑存量主题、新增 f-money3） | 116 条通过 |
| `node tests/lint/ui-integrity.js --warn-as-error` | `UI-GATE-0:PASS`（基线两条锚点只是行号位移，digest 按门禁算法重算）；selftest PASS |
| 新迁移 / 改既有迁移 | 无 |
| 没有运行 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规定不跑） |
| 密钥 / 生产 | 都没有。没有连生产 |

## 六、Cross-lane

- **f-money2 / qx-final：** f-money2 第五节 2 的默认处置已落在这里；部署清单不需要新增开关。
- **rig（f-money2 `scripts/e2e`、`money_chains.py`）：** rig 的「自由探索主题门票 39.90」夹具是直接写 `self_play=1、self_play_price=39.90`。合入后这类主题无法开售，也无法重新编辑。rig 夹具要改成门票价，或者保留这条，作为「存量违规行不卡钱」的回归。这条交 rig owner 处理。
- **共享文件：**
  - `ci/real-storage-it-registry.json`：新增批次 `launch-f-money3` 和 1 个类。
  - `ci/test_real_storage_it.py`：新增批次计数 4。
  - `.github/workflows/real-storage-it.yml`：新增批次选项。
  - `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`：2 条锚点行号位移，digest 重算。
  - `chengyinhub-xcx/scripts/uiaudit/action-ledger.json`：按 `--write` 重建。

STATUS: PARTIAL - 自由探索收费自玩票已在创建、更新、发布检查、锁价、开售各入口失败关闭，带 issues[] 码；小程序定价页显示栏下红字并置灰；单测、小程序测试和 UI-GATE-0 都通过；真 MySQL IT 已登记但本会话没有 MySQL，没有执行；存量行处置等你决定
