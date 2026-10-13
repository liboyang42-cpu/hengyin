# 广场 + 评论 UGC 图片「先发后审」改造 — 设计

- 日期:2026-07-14
- 分支:`feat/s0714-1504-2`
- 前置:本分支已落两提交(广场 HOLD 展示 + 后端作者可见性)。本设计在其上继续。

## 1. 背景与目标

当前广场帖 / 评论走内容安全门禁 `ContentSafetyGate.decide()`,规则里「含图片 → HOLD(先审后发,仅作者可见,等人工)」。因图片机审(`mediaCheckAsync`)在这两个入口没接通,导致**带图内容一律扣住不可见**,这是「发了帖却在广场找不到」的病根。

已查证(微信官方 + 行业实践):图片正常做法是**先发后审**——微信主推的 `security.mediaCheckAsync` 本身就是异步模型(送检→立即发→回调命中违规再撤下),旧同步 `imgSecCheck` 正在下线;小红书/微博等 UGC 产品图片均先发后审 + 事后处置。

**目标**:把广场帖 + 评论从「有图先审后发」改为「先发后审」——发布即可见,图片异步送检,命中违规由已有回调自动下架(status=2),作者在广场看到自己被下架的帖(「未通过审核」徽章,上轮已建)。

## 2. 现状:异步管线已建成,广场/评论未接

城瘾后端**已有完整的图片异步审核管线**(topic/activity/俱乐部/会员/商家/官方活动/据点模板 七类都在用):

- `IWxContentSecurityService.submitMediaCheck(bizType, bizId, imgArr, openid)`:拆图逐张 `mediaCheckAsync` 送检,把 `trace_id → "bizType:bizId"` 写 redis(2 天 TTL)。
- `handleMediaCheckResult(traceId, risky)`:回调命中违规时按 trace_id 反查,自动下架对应内容(可逆,仅改状态)。
- `ApiContentSecurityController`(`/api/content-sec/callback`):GET 接入验证(echostr+签名)、POST 收 `wxa_media_check` 事件 → 解析 `result.suggest=risky` / `errcode=87014` → 调 `handleMediaCheckResult`。

**缺口**:`BIZ_TYPE_*` 枚举里**没有广场、没有评论**;这两个入口仍走 gate 的 HOLD。canonical 范式见 `ApiTopicController`:`checkText`(命中→BLOCK 拦截)→ 建题发布可见 → `submitMediaCheck(BIZ_TYPE_TOPIC, …)`。

## 3. 设计

### 3.1 后端 — 内容安全服务(`IWxContentSecurityService` / `WxContentSecurityServiceImpl`)

- 枚举新增:`BIZ_TYPE_SQUARE = 9`、`BIZ_TYPE_COMMENT = 10`。
- `handleMediaCheckResult` 的下架 switch 增两分支(违规时,可逆,仅改状态):
  - `BIZ_TYPE_SQUARE` → `UmsCreativeSquare` set `status=2`(下架/驳回)。
  - `BIZ_TYPE_COMMENT` → `UmsComment` set `status=2`(下架,列表只显示 status=1,自动隐藏)。

### 3.2 后端 — 广场发布(`ApiCreativeSquareController.creativeSquareActoin`)

改为 topic 先发后审范式,**去掉 gate 调用**:

1. 入库(status 先给 1);
2. `checkText(contents, openid)`:命中 → 回滚/删记录 + `error("内容含违规信息,发布失败")`(BLOCK);
3. 未命中 → 保持 `status=1` 可见;
4. `submitMediaCheck(BIZ_TYPE_SQUARE, id, pics, openid)` 异步送检(best-effort,不阻断);
5. 结构化字段(定位/众筹)**不再 HOLD**(直发,符合 7-14)。

返回体带 `status`,前端据此提示(见 3.4)。编辑路径同样重送检新图。

### 3.3 后端 — 评论发布(`ApiCommentController.addComment`)

同范式,**去掉 gate 调用**:

1. 入库 status=0;
2. `checkText` 命中 → `deleteUmsCommentById` + error(BLOCK,与现状一致);
3. 未命中 → `status=1` 公开 + 首条评论积分(沿用现有 `firstOnTarget` guard)；
4. `submitMediaCheck(BIZ_TYPE_COMMENT, id, imgArr, openid)`。

**积分说明(评论独有)**:先发后审下,带图评论从「HOLD 不得分」变为「发布即得分」;若之后被异步下架,分已发(仅限每目标首条,刷分面极小)。本设计**接受此风险不做积分回收**(YAGNI);如需回收,可在 `handleMediaCheckResult` 的 COMMENT 分支反冲积分,列为可选后续。

### 3.4 前端 — 小程序

- **移除广场 HOLD(status=0)相关**(已决策):
  - `pages/square/list/index.js` 与 `components/touch/index.js` 的「审核中·仅自己可见」HOLD 弹窗分支(先发后审后广场发布不再返回 status=0);恢复为直接「发布成功」。
  - `pages/square/list/index.wxml` 的 `status==0`「审核中」徽章 + 对应 wxss。
- **保留 status=2「未通过审核·仅自己可见」徽章**(被异步下架时作者可见,本次核心展示)。
- **保留**后端作者可见性(`selfPendingMemberId`,上轮已提交):`status in (0,2)` 依旧正确覆盖 status=2;`getList` 登录带 token 不变。
- **保留** `pages/square/detail` 拉取失败 `app.tips` 提示(禁静默失败)。
- 评论前端:被下架评论直接从列表消失(列表恒 status=1),**不加评论级徽章**(评论轻量,发布即可见已满足禁静默失败;与广场帖的非对称是刻意的)。

### 3.5 gate 子系统去留(决策点)

迁移后 `IContentSafetyGate` / `ContentSafetyGateImpl` / `ContentSafetyGateImplTest` 零消费者,`AuditTaskService.submitSafetyHold` 仅被死 gate 调用。

**推荐:留驻不删,代码注释标注「publish 链路已弃用,保留供高危选择性 HOLD / 后台手工待审」**。理由:①是完整合规子系统 + AuditTask HOLD плumbing,删除牵连 DI/测试/DTO,风险大;②未来若要对高危账号做选择性先审后发可复用。删除列为可选后续。

## 4. 数据流(先发后审 happy/violation path)

```
用户发带图广场帖
  → checkText(文本)  ── 命中 → BLOCK,删记录,返回错误(发布失败)
                      └ 未命中 → status=1 入库(立即可见)
  → submitMediaCheck(SQUARE, id, pics)  → 每图 trace_id→"9:id" 写 redis
  → 前端「发布成功」,帖立即出现在广场
  ...(微信异步检测)...
  → POST /api/content-sec/callback (wxa_media_check, suggest=risky)
  → handleMediaCheckResult(traceId, true) → 反查 "9:id" → UmsCreativeSquare status=2
  → 作者刷新广场:自己的帖带「未通过审核·仅自己可见」徽章;他人已不可见
```

## 5. 边界 / 风险 / 非目标

- **送检失败=逃检**:`submitMediaCheck` 若 token/网络失败,该图逃过回调,现有实现落 ERROR 日志供人工复审(与 topic 等一致)。先发后审接受此 best-effort 语义。
- **fail-open**:`wx.sec.check.enabled=false` 或 checkText 异常时按现有 `checkText` 契约容错放行(与 topic 一致)。
- **无迁移**:trace_id 走 redis(已有),status 列已存在。**不需 chengyin-db-migrate**。
- **非目标**:不改 topic/activity 等已在用的七类;不做评论积分回收;不删 gate 子系统(留驻)。

## 6. 部署与运维前置(非代码,用户侧)

1. 微信公众平台 → 开发管理 → 消息推送:URL 配 `https://<域名>/api/content-sec/callback`,数据格式 JSON,Token 配到 `sys_config` 的 `wx.msgpush.token`。
2. `wx.sec.check.enabled=true`(否则送检与文本检测均容错放行,内容不受审)。
3. 部署两轨:后端(Flow ①)+ 小程序(微信上传 ②),无迁移。

## 7. 测试

- 单测:`WxContentSecurityServiceImpl` 的 `handleMediaCheckResult` 对 SQUARE/COMMENT 分支下架(Mockito 存/取 redis + 断言 setStatus(2))。
- 负控:非 risky 回调不下架;未知 bizType 落 ERROR 不抛。
- automator 自证:①广场发带图帖 → 断言返回 status=1 + 「发布成功」路径(不再 HOLD);②setData 注 status=2 帖 → 截图「未通过审核」徽章仍在、status=0 徽章已移除;③评论发布 → status=1 立即可见。
- 编译 + mapper XML 良构 + `/code-review` 自审(合规敏感)。
