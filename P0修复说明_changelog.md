# 城瘾（Questify）· P0 修复说明 / Changelog

日期：2026-06-18
范围：审计报告中的 P0 阻断项。**只改了后端 Java**，未动小程序前端、未动数据库结构。

---

## P0-1 越权漏洞（IDOR）— 已修复 ✅

**问题**：任意登录用户可删除 / 上下架**任意他人**的活动与主题（SQL 仅 `WHERE id`，无归属校验）。

**修复方式**：在写操作前先按 id 查出资源，校验 `资源.memberId == 当前登录用户`，不符返回「无权操作」。共 4 个端点：

| 文件 | 方法 | 端点 |
|---|---|---|
| `ApiActivityController.java` | `activityDelete` | `POST /api/activity/delete` |
| `ApiActivityController.java` | `activityUpdatePublishStatus` | `POST /api/activity/update_publish_status` |
| `ApiTopicController.java` | `topicDelete` | `POST /api/topic/delete` |
| `ApiTopicController.java` | `topicUpdateUserStatus` | `POST /api/topic/update_user_status` |

**已核对为安全、未改动**的同类写接口（本就有归属校验）：
`template/delete`、`template/updateLibraryStatus`（service 层 `equals(memberId)` 抛错）、`creativesquare/delete`、`creativesquare/action`（编辑）、`registration/cancel`、`registration/info`、`registration/scan_qr_code`（核销前校验活动归属）。

> 结论：IDOR 仅存在于上述 4 个端点，现已全部堵住。

---

## P0-2 测试图片域名 — 按你的要求暂缓 ⏸️

`utils/config.js` 的 `baseImgUrl = http://test2.h5.org.cn/...` **保持不变**（你选择保留以便调 UI 样式和测试数据）。

提醒：仅 `signup / wallet / userinfo` 三页的占位图（`zt1.jpg`/`pro1.jpg`）用到它，真实图片都走 HTTPS OSS。**上线前**必须换成生产 HTTPS 域名，否则提审会被拒（HTTP 明文 / request 合法域名未配）。

---

## P0-3 微信内容安全（UGC）后端骨架 — 已搭建 ✅

**关键发现**：`comment/add` 与 `creativesquare/action` **此前已有**文本内容安全检查（控制器内私有 `checkTextSecurity`，基于 `WxPayConfig`）。真正缺检测的是**主题发布**和**活动发布**两条 UGC 链路。

**新增**（中心化、可复用，采用 msgSecCheck **v2**，带 openid + suggest 判定）：

- `chengyinhub-system/.../business/service/IWxContentSecurityService.java`（接口）
  - `checkText(content, openid)`：返回 null=放行，非 null=拦截提示文案
  - `checkMediaAsync(mediaUrl, openid)`：图片异步送检，返回 trace_id
  - `isEnabled()`、静态 `joinText(...)` 拼接多字段
- `chengyinhub-system/.../business/service/impl/WxContentSecurityServiceImpl.java`（实现）
  - **复用现有配置开关与凭据** `WxPayConfig`（`secCheckEnabled` / `miniAppId` / `miniSecret`），与 comment/square 同一套开关，不引入第二套配置
  - **复用同一 access_token 缓存**（redis key `wx:mini:access_token`），与现有控制器共享 token
  - token 过期自动刷新重试；命中违规（87014 或 suggest=risky）拦截；接口异常/未配置时**容错放行**并告警（fail-open，避免误伤正常用户）

**接入点**（新增文本前置校验）：

| 文件 | 方法 | 端点 |
|---|---|---|
| `ApiTopicController.java` | `createTopic` | `POST /api/topic/create`（校验 名称+副标题+简介） |
| `ApiActivityController.java` | `publish` | `POST /api/activity/publish`（校验 名称+简介） |

### 如何启用
默认**关闭**（骨架态，不影响现有流程）。上线前：
1. 在 `application.yml` 配置 `wx` 的 `secCheckEnabled: true`、`miniAppId`、`miniSecret`（与现有 comment 检查同一份配置）。
2. 启用后，主题/活动/评论/广场发布的**文本**都会被微信内容安全拦截违规内容。

### 仍需后续补（非本次骨架范围）
- **图片异步检测**：`checkMediaAsync` 已提供，但结果回调需在微信公众平台「开发管理-消息推送」配置服务器 URL，监听 `wxa_media_check` 事件并落库处理（建议命中后下架对应内容）。目前各发布接口尚未调用 `checkMediaAsync`，可作为 P1 接上。
- **统一收口**：建议后续把 comment / creativesquare 里重复的私有 `checkTextSecurity`（v1）迁移到中心化 `IWxContentSecurityService`（v2），消除两套机制（P2 清理）。

---

## 编译/验证说明
本环境无 Maven，未能整包构建。改动均经过人工核对：所用方法/字段（`selectCmsActivityById`、`selectCmsTopicById`、`getMemberId():Long`、`getDelFlag():Integer`、`getWxOpenId()`、`WxPayConfig` getter、`Convert`/`HttpUtils`/`RedisCache` API、DTO getter）均已确认存在；模块依赖（system→common）已确认。**建议你本地 `mvn -pl chengyinhub-admin -am compile` 跑一次确认。**

## 改动文件清单
```
新增  chengyinhub-system/src/main/java/com/chengyinhub/business/service/IWxContentSecurityService.java
新增  chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxContentSecurityServiceImpl.java
改动  chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiActivityController.java
改动  chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java
```
