# 本地验收清单汇总·第十一批（BL-124–BL-130，2026-10-16）

> BL-131：汇总合入 `claude/mini-ui-features-20261015` 的第十一批未合并 PR 的「本地验收清单」，原文照抄、不增删；验证阶段由用户统一执行。BLOCKED 项单列。生成方式：读取各 PR 正文（#168–#174），快照时间 2026-10-16 00:35Z；#174 一节于 01:40Z 按其当前正文（head `fd16fbdf`）补入。PR 正文之后若再修改，以 PR 正文为准，执行清单前请先回看对应 PR。
> 本文件续接 BL-123（#167）`ACCEPTANCE-BATCH10.md`（以及更早的 #101、#126、#151、#159）。前述文件均未合并，为免文本冲突单独成文；合并后可原样追加到 `ACCEPTANCE.md` 末尾。

> **依赖与冲突提示**：本批没有代码改动，全部是文档或 BLOCKED 记录，不需要任何协调者统筹补丁。#170、#171、#172、#174 各新增一份独立文档，彼此和与其他 PR 都没有文本冲突。

## 一、可验收的文档 PR

### #170 BL-126 协调者补丁汇总（COORDINATOR-PATCHES.md）

分支：`claude/bl-126-coordinator-patches`

- [ ] 协调者整合前，抽查 3 个 PR 的补丁段与本文件一致。
- [ ] 全部合入后实测 `app.json` 页面数、`get-auth-matrix` 直接/包装 GET、nav-page-title 计数与本文件累计值一致；不一致时以实测为准并回写本文件。

### #171 BL-127 PR 依赖与建议合并顺序（MERGE-ORDER.md）

分支：`claude/bl-127-merge-order`

- [ ] 协调者按第 2 节顺序试合前 3 步，确认无意外冲突；有则回写本文件。
- [ ] 确认关闭 #75，并删除 §3.2 列出的重复/作废分支（由所有者或协调者操作）。

### #172 BL-128 待产品决定汇总（DECISIONS-NEEDED.md）

分支：`claude/bl-128-decisions-needed`

- [ ] 用户在第一节的「选择」栏里逐项填写决定，交回开发线执行。
- [ ] 协调者确认第二、三节已转给 Codex。

> 注：#172 已按监督意见（01:40Z 前后，head `68351c75`）把已定/已作废的 #61、#66、#53 移出第一节，第一节现为 14 项；上面「逐项填写」一条仍适用，只针对这 14 项。

### #174 BL-129 监督未决意见汇总（REVIEW-OPEN.md）

分支：`claude/bl-129-review-open`

- [ ] 随便抽 3 个 §1 的 PR，打开最新 supervisor 评论，确认结论和未决项与表格一致
- [ ] 确认 §2 的 CLOSE 建议要不要批量关闭（本卡没有关任何 PR）
- [ ] 确认 §3 里「本线」归属的 nit 要不要另开卡统一处理

## 二、BLOCKED 项（无代码改动，仅核实/解锁条件）

| PR | BL | 原因 | 核对项（PR 正文原话） |
|---|---|---|---|
| #168 | BL-124 商家公开详情口径核对 | 现有信息行已等价；ops/public-detail 多出的电话/官网/评分汇总都不是现有行，电话/官网被公开白名单刻意排除 | 确认是否需要在公开商家页展示电话/官网/评分汇总（若需要，单独立项并决定隐私口径）。 |
| #169 | BL-125 模板库分类分组口径核对 | 现有品类 chips + 置顶排序已等价；groupByCategory 按 `category_id` 分组、不含主题模板，且未剥离答案字段 | 确认是否要后端修复 groupByCategory 的答案剥离与 status 过滤。 |
| #173 | BL-130 单测与合同测试·第十一批 | BL-124/125 均 BLOCKED，无代码可测 | 无。若以后 #168 或 #169 解锁并改了代码，再重开本项。 |

## 三、附带发现（交 Codex / 用户判断，本线未改）
- #169：`POST /api/template/groupByCategory` 直接查出 `question_answer`、`correct_answer`、`answer_reveal`、`hint1/2`、`sensor_config` 等字段，未经 `TemplateSecrets::strip`，SQL 也不过滤 `status=1`。
- #168：`GET /api/merchant/ops/public-detail`（`@Anonymous`）在 quickActions 里匿名返回原始电话和官网，可见性判定用 `delFlag != 2`，而现用接口用 `== 0`。
- #170：#130 的 `self-submitter`、#132 的 `public-taxonomy`、#144 的 `MARKETING_READ` 都不在 `get-auth-matrix` 的 `VALID_SCOPES` 里，按原样落补丁会让断言失败；#84、#109、#112 新增的直接 GET 在正文里漏列。
- #171：#75（BL-34，BLOCKED）的分支带着 #73 的全部代码，不能合；无 PR 的 `claude/bl-51-report-reason-note` 带代码，与 #95 重复。
