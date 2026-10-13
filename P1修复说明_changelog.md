# 城瘾（Questify）· P1 修复说明 / Changelog

日期：2026-06-18
原则：**保留双身份（user_type 1用户/2商家 + role player/club/merchant），不删不合并；不动数据库结构。**

---

## P1-c 写接口防重 ✅（纯前端节流）

给关键写接口加「在途禁止重复提交」，成功跳走 / 失败可重试，避免重复扣款、重复下单、重复发布。

| 文件 | 场景 | 处理 |
|---|---|---|
| `pages/member/tixian/tixian.js` | 提现（资金） | 加 `_submitting` 守卫 |
| `pages/settlement/settlement.js` | 报名支付 | 已有 `isPaying` 标志但入口没判断，补上入口判断 |
| `pages/publish/fabu/index.js` | 发布主题 | 加 `_submitting` 守卫 |
| `pages/publish/activity/index.js` | 发布活动 | 加 `_submitting` 守卫 |
| `pages/publish/temp/index.js` | 发布模板 | 加 `_submitting` 守卫 |
| `pages/square/list/index.js` | 广场发帖 | 加 `_submitting` 守卫 |
| `pages/topic/merchantapply1/merchantapply1.js` | 商家报名·定向 | 加 `_submitting` 守卫 |
| `pages/topic/merchantapply2/merchantapply2.js` | 商家报名·自由 | 加 `_submitting` 守卫 |

本就有 guard、未改：`baoming`、`topic/settlement`、`order`。
全部通过 `node --check` 语法校验。

---

## P1-d 前端稳定区分 user/merchant/club ✅（安全核心；保留双身份）

**根因**：前端身份散落在 `globalData` / `storage` / 后端三处，且 `getUserRole()` 兜底只认 `user_type`，**装不下 club**，导致俱乐部用户被当普通玩家。

**只做加法，不删 user_type**（`app.js` + `shezhi.js`）：
- 登录成功 / 静默重登：`setUserRole(role)` 把后端 `role` 落地。
- `setUserRole` 现在**同时写 globalData 和 storage**（单一真源）。
- 启动 `onLaunch`：从 storage 恢复 `role` 到 globalData。
- `getUserRole()` 改为**三级回退**：globalData.role → storage role → 最后才按 user_type 猜（兼容旧逻辑）。
- 退出登录：一并清 `globalData.role`，避免内存残留导致身份错乱。

> 效果：俱乐部（club）身份现在能被前端稳定识别；三处不同步的根因消除。
> **未做（移到 P2）**：把各页面里直接读 `user_type==2` 的判断逐页改读 `role`、补齐俱乐部专属入口 UI——这是较大重构，且正是你叮嘱要谨慎的双身份区域，留待你确认后再逐页推进。

---

## P1-e 达人页空态治理 ✅（纯展示层）

`pages/talent/list/index`：
- 「开放的挑战」空态：从 `暂无开放的挑战 / 敬请期待官方发起`（死胡同）→ `还没有开放的挑战 / 成为第一个发起团体挑战的人` + **「发起挑战」按钮**（复用 `onHeroTap`）。
- 「挑战」发现页空态：从 `暂无内容` → `这里还很安静 / 换个分类看看，或自己发起一个挑战` + **「发起挑战」按钮**。
- 新增 `.empty-cta` 样式（圆角实心按钮）。

---

## P1-f 图片内容安全（异步检测）✅（后端骨架）

承接 P0-3 的文本检测，补图片异步检测链路：

- **service 扩展**（`IWxContentSecurityService` / impl）：
  - `submitMediaCheck(bizType, bizId, imgArr, openid)`：拆分图片串逐张 `media_check_async` 送检，把 `trace_id → "类型:ID"` 映射写入 redis（保留 2 天）。
  - `handleMediaCheckResult(traceId, risky)`：按 trace_id 找回内容，**命中违规自动下架**（主题 userStatus=0 / 活动 publishStatus=0，**只改状态、可逆、不删数据、不加表**）。
- **送检接入**：`activity/publish` 发布成功后异步送检封面/图集（best-effort，不阻断）。
  - 主题 `topic/create` 暂未接：其 `createTopic` 返回 void 拿不到新 id，接入需改方法签名，留作跟进（不擅自改签名）。
- **回调接口**（新增 `ApiContentSecurityController`，`/api/content-sec/callback`，`@Anonymous`）：
  - GET：微信「消息推送」URL 接入验证（SHA1 签名校验，token 读 sys_config `wx.msgpush.token`；未配置则骨架回显）。
  - POST：接收 `wxa_media_check` 结果，解析 `trace_id` + `result.suggest`，交给 service 处理。

### 启用步骤
1. 微信公众平台-开发管理-消息推送：配置 URL=`https://你的域名/prod-api/api/content-sec/callback`，数据格式选 **JSON**，Token 任意填一串。
2. 把同一个 Token 写进 sys_config `wx.msgpush.token`。
3. 内容安全总开关沿用 P0-3 的 `WxPayConfig.secCheckEnabled`（与文本检测同一开关）。

---

## 按你的决定·保留不动（P1-a / P1-b）
- **mock 兜底**（`app.js useDevUser`）：现已由 `isDevEnv()` 门控——仅开发环境降级，生产不会伪造假用户。功能保留，方便你测试。
- **设置页「切换身份」开关**（`shezhi.js switchIdentity`）：原样保留。
- 两者推迟到真正上线前再评估。

---

## 改动文件清单（P1）
```
前端
  app.js                                   role 单一真源(登录/重登/启动/回退/退出)
  pages/shezhi/shezhi.js                   退出清 role
  pages/member/tixian/tixian.js            防重
  pages/settlement/settlement.js           防重(补入口判断)
  pages/publish/fabu/index.js              防重
  pages/publish/activity/index.js          防重
  pages/publish/temp/index.js              防重
  pages/square/list/index.js               防重
  pages/topic/merchantapply1/merchantapply1.js  防重
  pages/topic/merchantapply2/merchantapply2.js  防重
  pages/talent/list/index.wxml             空态CTA
  pages/talent/list/index.wxss             .empty-cta 样式

后端
  IWxContentSecurityService.java           +submitMediaCheck/handleMediaCheckResult/常量
  WxContentSecurityServiceImpl.java        实现图片送检+违规自动下架
  ApiActivityController.java               发布后接入图片送检
  ApiContentSecurityController.java         新增·消息推送回调(接入验证+结果处理)
```

## 验证
- 前端 11 个 JS 文件均过 `node --check`。
- 后端无 Maven 无法整包构建；新增/改动文件已人工核对：大括号平衡、引用的方法/字段/服务均存在、模块依赖正常。**建议本地 `mvn -pl chengyinhub-admin -am compile` 跑一次。**
- **未碰数据库结构、未删双身份。**
