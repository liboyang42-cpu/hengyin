# 本地验收清单汇总·第九批（BL-108–BL-114，2026-10-15）

> BL-115：汇总合入 `claude/mini-ui-features-20261015` 的第九批未合并 PR 的「本地验收清单」，原文照抄、不增删；验证阶段由用户统一执行。BLOCKED 项单列。生成方式：读取各 PR 正文（#152–#158）。
> 本文件是 BL-59（#101）`ACCEPTANCE.md`、BL-83（#126）`ACCEPTANCE-BATCH3-4.md`、BL-107 `ACCEPTANCE-BATCH5-8.md` 的续篇：前述文件均未合并，为免文本冲突单独成文；合并后可原样追加到 `ACCEPTANCE.md` 末尾。

> **依赖与冲突提示**：#158（BL-114）叠在 #152（BL-108）之上，须在 #152 之后合并。#152 如台账按请求 URL 统计，需协调者重建 `action-ledger.json`（新增只读 `POST /api/merchant/subscription`）。

## 一、可验收的功能/测试 PR

### #152 BL-108 商家已生效权益（只读）

分支：`claude/bl-108-merchant-subscription`

- [ ] 有生效权益的商家：装修页 →「升级权益」展开，顶部出现「已开通」+ 权益名 + 有效期，下面「可开通」后接原有四项。
- [ ] 无权益商家：面板与改动前完全一致。
- [ ] 无 PROFILE_WRITE 的岗位：不发该请求。
- [ ] 接口失败/断网：不 toast、面板与改动前一致。
- [ ] 自助开通支付成功后，「已开通」自动出现新权益。

### #158 BL-114 第九批单测与接口合同测试

分支：`claude/bl-114-batch9-tests`

- [ ] #152 合并后 rebase/合并本 PR，`node --test "tests/unit/**/*.test.js"` 全绿。

## 二、BLOCKED 项（无代码改动，需用户/产品决定）

| PR | BL | 原因 |
|---|---|---|
| #153 | BL-109 创作者中心 | 个人页无现成创作者信息块；`applyStatus` 脱离申请入口无意义（申请入口需产品决定），`recentIncome` 属收益展示 |
| #154 | BL-110 为你推荐 | 首页「推荐主题」有意只取运营人工推荐位（`/api/topic/list is_recommend=1`）；`RecommendationItemVO` 缺卡片字段且要求登录 |
| #155 | BL-111 发现页筛选对齐 | 小程序现有筛选取值与 `DiscoveryFilterContract.schema` 完全一致（PR 附对照表）；核销列表无分页属另一问题 |
| #156 | BL-112 IP 世界 | 小程序没有 IP 世界/城市世界入口 |
| #157 | BL-113 经营台账汇总 | 台账页已有服务端汇总（资金域）；`/orders/ledger` 是订单域，口径不等价。附带发现：核销列表只拉第 1 页，应走 `finance/redemptions` 的 `pageNum` 另开卡 |
