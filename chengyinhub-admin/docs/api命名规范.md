# 城瘾 /api 命名规范(BE-05)

> 现状:44 个 api 控制器、252 个端点(241 POST + 11 GET),RuoYi 风格「动词入路径 + 几乎全 POST」,
> 命名混用 kebab(`my-list`)、snake(`invite_list`)、camel(`updateLibraryStatus`)。
> **存量 252 个端点全部冻结**——重命名会全面破坏小程序契约,收益<风险,不动。
> 本规范只约束**新增**端点,让新代码收敛到一种风格。

## 1. 路径命名(新端点强制)

- 资源用**名词复数 + kebab-case**:`/api/coupon/my-list`、`/api/registration/create`
- 动作后缀也用 kebab:`/coop-profile/save`、`/income/topic-list`
- **禁止** snake_case(`invite_list` ❌)、camelCase(`updateLibraryStatus` ❌)混入路径
- 层级:`/api/<域>/<资源>[/<子资源>]/<动作>`,域名与控制器 `@RequestMapping` 一致

| 反例(存量,别再新增此风格) | 新端点应写成 |
|---|---|
| `/invite_list` (snake) | `/invite-list` |
| `/updateLibraryStatus` (camel) | `/library/status` (PUT 语义) 或 `/update-library-status` |
| `/mypublishlist` (无分隔) | `/my-publish-list` |
| `/myrecvlist` (无分隔) | `/my-recv-list` |

## 2. HTTP 方法(新端点)

存量因历史全用 POST。新端点**按语义选**,前端 request-client 已支持:

- 纯查询、可缓存、无副作用 → `GET`(参数走 query)
- 创建 → `POST`;整体更新 → `PUT`;部分更新 → `PATCH`;删除 → `DELETE`
- 拿不准或需带 body 的复杂查询 → `POST`(与存量一致,可接受)

## 3. 返回体

统一 `AjaxResult`:`code`(200 成功 / 401 未登录 / 409 冲突 / 500 业务失败)+ `msg` + `data`。
错误码见交易接口约定(401 走 request-client 静默重登;409 促前端重新报价)。

## 4. 落地方式

- 新写控制器方法时对照本文;评审时把「路径 kebab + 方法语义」作为一条 checklist。
- 存量端点如确需改名,必须前后端同版本 + 灰度双挂(旧路径保留一版),不可裸改。
