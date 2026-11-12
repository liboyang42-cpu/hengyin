# 内容清单与创作者交付元数据（BE-10 / BE-13）

本目录只放**当前公开交付合同**的机器可读形状与跨端样例，不冒充完整玩法 schema。

## 覆盖范围（严格）

- `content-manifest-v1.schema.json` — `GET /api/play/content-manifest` 返回的 `data` 对象。
- `content-manifest-revision.example.json` — 明确修订（`status=REVISION`）。
- `content-manifest-run-snapshot.example.json` — 已开局按本局快照（`status=RUN_SNAPSHOT`）。
- `content-manifest-online-legacy.example.json` — 老内容无修订清单（`status=ONLINE_LEGACY`，在线读取）。
- `content-delivery-metadata.example.json` — 模板详情里的 `contentDelivery`（BE-13）。

## 不覆盖（不要当成已完成的合同）

- 不含 32 种玩法的运行时配置形状（那仍由 `AdvancedGamePublicProjection` 白名单与各端既有合同定义）。
- 不是服务端运行时校验器：服务端以投影代码为唯一实现，本 schema 只用于端侧类型生成与回归比对。
- 不含任何答案、别名、提示、揭示、判题配置、商家秘密或私有快照字段；出现即视为缺陷。

## 投影纪律

结构字段是**逐字段允许清单**投影：`stations` 只取 `nodeId/nodeName/chapterId/scanReady`，能力只取 `key/minVersion`,
资产只取真实引用；新增字段默认不公开。递归键剥离只作补充防线，不是唯一泄漏防线。

## 事实纪律

- `sha256` / `bytes` / `mime` 没有权威值时一律 `null` 且 `verified=false`；请求期不抓取任意 URL。
- `assetKey` 是稳定内容身份，签名 URL 刷新不改变它。
- `requiredCapabilities=null` 表示未声明/无法推导，不是「无要求」。
- 许可摘要只来自已冻结的销售 canonical；缺失即 `UNKNOWN`，前端必须显示「未提供」。
- `sourceLocale` 本基线恒为 `zh-CN`；`translations` 为空且 `translationStatus=SINGLE_LOCALE`，不假装多语已支持。
