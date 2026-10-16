# 验收清单 · 第十二至十三批（BL-147，2026-10-16 快照）

> 汇总 BL-132–146 已开 PR 的「本地验收清单」。步骤以各 PR 正文为准，本文件只做归并；PR 未合并前请在对应分支上验收。
> 依赖：BL-138 `ACCEPTANCE-INDEX.md`（#182，本分支从其切出；对应页面行已加注 B13）。
> 用户要求：验证延后——以下各项均**未**在 CI、开发者工具或真机上验证过。

## 一、需要上设备走查

### #183 BL-140 俱乐部解散阻断明细回读（分支 `claude/bl-140-club-dissolve-blockers`）
身份：主理人；数据：俱乐部有待退保证金或待结算款。协调者补丁：可能需重建 action-ledger（新调用 `POST /api/club/dissolution-blockers` + 组件 `pageLifetimes.show`）。
1. 编辑俱乐部 → 解散 → 弹「解散前待处理」。
2. 点保证金行「重试原路退款」：先显示退款反馈，随后追加「已重新核对…」；退款终态后该行消失，其余行保留。
3. 点结算行 → 进结算页处理 → 返回：阻断弹层自动重新打开并显示核对结果。
4. 全部处理完：文案提示可重新发起解散，再次解散以后端结果为准。
5. 断网点重试：提示未能重新核对，行不变。

### #184 BL-141 商家营业状态写后回读（分支 `claude/bl-141-merchant-status-readback`）
身份：商家主账号 / 有 PROFILE_WRITE 岗位；无写权限岗位。协调者补丁：可能需重建 action-ledger（两页新调用 `POST /api/merchant/business-status`）。
1. 商家工作台切营业中/已打烊：回读完成后才出 toast，开关与回读一致。
2. 店铺装修页切营业状态：回读完成前无「已保存」回执；成功后回执出现。
3. 写成功后断网（回读失败）：开关回滚，提示「状态未同步，请刷新后核对」，可点重试。
4. 无写权限账号：开关不可切（原逻辑不变）。

## 二、只跑命令（纯测试 PR，不上设备）

| PR | 卡 | 命令 | 期望 | 合并前置 |
|---|---|---|---|---|
| #186 | BL-143 漫游筛选单测 | `node --test tests/unit/roam-poi-filter-helpers.test.js` | 0 fail，1 todo（原型键） | #37 之后 |
| #187 | BL-144 官方主办规则单测 | `node --test tests/unit/official-organizer-rules-helpers.test.js` | 0 fail | #27 之后 |
| #188 | BL-145(1/2) 积分月份分组单测 | `node --test tests/unit/points-history-month-helpers.test.js` | 0 fail，1 todo（月份越界） | #40 之后 |
| #189 | BL-145(2/2) 商家 ID 校验单测 | `node --test tests/unit/merchant-correction-merchant-id.test.js` | 0 fail，1 todo（Number 宽松） | #110 之后 |
| #190 | BL-146 第十三批合同测试 | `node --test tests/unit/batch13-api-contract.test.js` | 0 fail | 无（只读后端源码） |

三个 `todo` 都是测试发现、未改被测代码的小缺口，交原 PR 决定是否修：#37 `VISITED_STATES` 原型键、#40 月份未校验 01–12、#110 `Number()` 放行 `0x10/1e3/true`。

## 三、BLOCKED / 文档（不需要走查）

| PR | 卡 | 说明 |
|---|---|---|
| #185 | BL-142 合作候选池 | BLOCKED：`pages/coop/list?topicId=` 已按主题展示 invites/registrations/clubApplies，与 `/api/coop/candidates` 等价；唯一差异是 `/candidates/received` 的截断上限，是否替换数据源待产品决定。可选核对：从主办主题页「查看收到的申请」进入，三组只显示该主题 |
| #176 | BL-132 `BACKEND-FINDINGS.md` | 文档 |
| #177 | BL-133 `ZERO-CALL.md` | 文档 |
| #178 | BL-134 `CLOSE-CANDIDATES.md` | 文档 |
| #179 | BL-135 `CONFLICTS.md` | 文档 |
| #180 | BL-136 `TEST-GAPS.md` | 文档（BL-143–145 的依据） |
| #181 | BL-137 `LINT-AUDIT.md` | 文档 |
| #182 | BL-138 `ACCEPTANCE-INDEX.md` | 文档（本文件的上游） |
| — | BL-139 协调者补丁累计校验 | 已认领（`claude/bl-139-coordinator-patches-check`），快照时尚无 PR，未收录 |
