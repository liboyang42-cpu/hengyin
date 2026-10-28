# C：后台完整游戏内容管理接线

结论：源代码已交付，所有行为验收 **NOT_RUN**。后台继续使用唯一模板中心；高级内容可查看、按原字段维护并沿原 PUT 保存，未建立第二内容库、玩法注册表或运行引擎。

## 基线与范围

- 生产父 SHA：`fa5c263440573992701263d6bee60830844a49b8`。
- 已读取完整方案：`ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f:docs/delivery/architecture-day/COORDINATOR/全游戏资源库与主题复用完整施工方案-20261025.md`。
- 独立工作树：`/workspace/hengyin-c-complete-template-admin`；分支：`codex/architecture-day-c-complete-template-admin`。
- 不覆盖 B Java、D 小程序、G 发布、E validator、既有迁移、API、router、权限、legacy 重定向页或其他 owner 文件。

## 原源结论与最小改动

原 `handleEdit` 已 `Object.assign(createTemplateForm(), response.data)`，原保存也提交整个 editForm；因此不能把旧 UI 描述为必然只写 1/2/3。实际缺口是完成方式只给 1/2/3 选择，高级配置无编辑入口、详情把其他值显示为未设置，用户无法核对完整内容。

现有 Vue 组件只有富文本 Editor，没有跨机制的现成结构化游戏配置组件；小程序已有完整 WXML 作者编辑器，不能直接装进 Vue。本片复用 Element 的输入、折叠和错误提示，一份局部字段组件共用于详情只读和中心编辑；不复制 42 套玩法编辑器，不把富文本当 JSON 或执行任意脚本。

| 生产文件 | 实际职责与调用者 |
|---|---|
| `chengyinhub-ui/src/views/business/membertemplate/index.vue` | 原模板中心路由 `Membertemplate`；列表增加实际配置摘要；编辑选择沿现有 0–10 的基础完成方式并保留未知原值；原完整详情作为保存来源；加载核对原 ID、拒绝旧回包、取消/离开使请求失效；在途只提交一次，失败留输入且不自动重发。 |
| 同目录 `detail.vue` | 原 `membertemplateDetail` 路由；既有权限、优惠券/分类读取保留；高级配置用同一字段组件只读展示，不把基础方式数量当作全部玩法数量。 |
| 同目录 `TemplateContentFields.vue` | 局部展示/输入适配，无 API；只按固定原实体字段发 input，readonly/disabled 不发修改，不能从此修改 memberId；原文转义展示，无 v-html、脚本执行或动态资源加载。 |
| 同目录 `template-content.js` | 原字段的标签、JSON语法/根类型检查、实际已启用配置段摘要、完整 payload 的浅复制；不实现判题、状态机、玩法业务校验或公共库采用。 |

### 精确字段合同

除旧表单的标题、说明、基础题面、封面等字段外，新增管理入口覆盖：

- `advancedConfigJson`、`preferenceJson`、`sensorType/sensorConfig`、`interactionType`。
- `questionA/B/C/D`、`correctAnswer`、`questionImg/questionAudio/questionOptionMediaJson`、`photoRequireDesc/photoReview`。
- `storyText/storyImg/storyJson`、`hint1/hint2/answerReveal`、`audioUrl/audioDuration`。
- `feedbackText`、`merchantGuide`、`couponId`、`medalName/medalImg/medalStyle`、`xpValue`、`rewardRulesJson`。

资源槽保持嵌套在原 `advancedConfigJson.interactionRules.resourceSlots`，没有创造独立 resourceSlots 顶层字段。描述型配置只检查 JSON 是否可解析、根对象/数组是否合乎该原字段形状；选项媒体保留原允许的 JSON null。**不重排、重写或删减原 JSON 字符串**，未知配置段与未展示的实体属性仍随原 DTO 回传；null、0、false 不被 truthy fallback 替换。传入的字符串 ID 不再对分类做 Number 转换；这不声称修复服务端已作为不精确 JS number 下发的 ID。

`validationMethod` 表是既有 0–10 基础完成方式展示，不是 32+10 机制注册表；App 传感器明确标注渠道限制。高级摘要来自实际配置段，不声明这些段已通过平台/设备/完成证明验收。

### 原真实调用链和边界

- `Membertemplate` → 原 `listMembertemplate/getMembertemplate` → `GET /business/membertemplate/list`、`GET /business/membertemplate/{id}`。
- 编辑 → `buildTemplatePayload` → 原 `updateMembertemplate` → `PUT /business/membertemplate` → `CmsMemberTemplateController.edit` → `TemplatePublishServiceImpl.updateAdminTemplate`。
- 原 `business:membertemplate:query/edit/remove` 与后端 `@PreAuthorize` 保留；后台不是小程序作者身份。不能替换原 owner/memberId、原库来源 ID、优惠券归属、媒体资格、审核/下架逻辑。
- 保存后仍由原业务设置待审/下架并同步投影；提交审核仍走原 `/{id}/review`，不直接写库投影或假造审核成功。
- `templatelibrary/index.vue` 仍只转 `Membertemplate`，`templatelibrary/detail.vue` 仍经 projection.memberTemplateId 转真源详情。没有新增第二库页或把 projection ID 当个人模板 ID。
- 可视化内容制作继续由原作者在小程序 `pages/publish/temp/index`（真实参数为 `id` + `scope=my`）续编。本后台不构造代登录链接，不把后台管理员当原作者。本片不改变 B 的 adopt-draft/公共详情秘密裁剪，也不改 D 的内容发现/绑定入口。

## 配套测试源码（全部 NOT_RUN）

`chengyinhub-ui/tests/unit/template-center-contract.test.js`：保留原 **10** 个 test 的正文、断言及场景，仅装入新增实际组件和字段模块；旧路由跳转/原权限/较早回包等原断言不删。

新增 `chengyinhub-ui/tests/unit/template-complete-content.test.js` 的 **8** 个 UI 定点源码：

1. 配置原字节、资源槽、秘密字段、0/null、未知字段及个人/库身份完整往返。
2. 实际中心 handleEdit→submitEdit 只改标题仍提交原配置/素材。
3. 非法 JSON/根类型在写前拒绝，输入不丢；无 eval/v-html/脚本执行。
4. 在途重复只写一次；响应丢失不假报成功、不清输入、不自动二发。
5. A/B 详情交错、取消后回包、错误返回身份不覆盖当前编辑目标。
6. 0–10 完成方式展示、未知原值保留、配置摘要取自原内容。
7. 同一组件的原字段清单、只读/禁用/未登记身份字段不可修改、单字段编辑保全其余内容。
8. 两页面共用实际组件；原 PUT/审核 API/权限与旧库单向重定向保留。

上述为源码级 UI 夹具，不模拟服务端判题通过、优惠券授权或真实发布；没有用模拟成功代替 B/G/E 的原验收。

## 未验证、接缝与交付

- 按本轮指令，Node/Python/Java/DB/HTTP/CI/构建/浏览器/可视化验收一律未执行。仅阅读源码、编辑和 git diff/摘要操作；不能记为行为 PASS。
- Vue 字段编辑不是小程序 42 项可视化作者器。丰富新内容制作保留原小程序入口；本片只补后台可读/可维护/完整回传，完整机制索引和设备可用性由 A/D/root 合成。
- 数据库最终值仍经过 B 原 normalizer、Mapper 和审核 validator：例如 method!=6 时原 Mapper 会清 preference_json，感应器清空依赖原服务端语义，advanced 空白是显式关闭。不能把前端原文往返测试说成数据库逐字相等。本片不私造清空协议或改变业务规则。
- Admin 原 PUT 没有新增命令/receipt/version API；本片只保持原请求目标、在途围栏、失败保留和无自动重发，不声称解决跨端并发覆盖或证明丢包请求终态。
- 本片无 schema/DDL/依赖迁移。回退仅撤本片 Vue/局部模块与测试接线；不会回退已经经原审核写入的内容、资金或运行事实。
- 完整 owned patch 由候选 commit 的 `.patch` 提供。`postimages.sha256` 列出生产/测试/本报告后像（摘要文件自身不作自哈希）；源码 SHA/摘要不是验收通过。
