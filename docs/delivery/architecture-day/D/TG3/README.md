# TG3：复用原 qa/PICK 客户端

状态：`SOURCE_CONFIRMED_NOT_RUNTIME_REPRODUCED`。父源码 `fa5c263440573992701263d6bee60830844a49b8`；本批未运行 Node、Java、DB、HTTP、CI 或微信工具，不继承此前候选绿色。

## 新主题应使用的原入口

- 选项玩法 key：`qaPick`；已有编辑入口 `/pages/publish/temp/index?game=qaPick`。`pages/publish/temp/index.js` 的 `onLoad` 用 `options.game` 调原 `nodeGameCatalog.findGame/pickGame`。只配置原 `advanced.qa` 的 `PICK` 题面、选项和参数，不新建主题专属组件。
- `pages/publish/utils/publish/advanced-game-config.js` 的原 qa 配置/校验继续复用。`prepareFormData` 保留高级 QA 与历史 `validationMethod=3` 的原保存规则；创作者私有预览与玩家公开投影不是同一保密边界。
- 原主题编辑器 `pages/publish/fabu/index.js::_bindNodeTemplate` 用 `selectedTemplate.id` 写 `nodesForm.templateId`；已存在模板应从原玩法选择/绑定入口选取。此算法本批不修改，不重造模板/主题引擎。
- 原发布编译/快照归 TG1/TG2/TG4 与后端 owner；本批只透传已经存在的运行 `sessionId/nodeId`，不构造版本、实例业务身份或接口。

## 实际渲染与权威路径

`pages/play/utils/playkit-view.js::pickPlayKit` 将会话 `playKit.qa/PICK` 投影为 `type=qa, mode=pick` → `pages/play/components/playkit/index.wxml` 唯一的 `cy-playkit-qa` 分支 → 同一 `playkit-qa/index.js`。两份不同主题只更换数据和原运行身份。

组件的 submit 事件保留原格式；`playkit/index.js::_emit` → 玩家页 `onPlayKitAction/_submitPlayKitAction` → 原 `serverAction('qa','submit') = SUBMIT_QA` 与 `serverPayload`（optionId/optionIds/input）→ `advanced-game/index.js::action` 的原 session/version/idempotencyKey → 原 `/api/play/advanced/action`。root/A 已确认普通 QA 与引导题组最终同 `AdvancedActionJudgement.submitQa`；完成事实仍由服务端产生。

本切口未发现可达的 qaPick2/主题专属选项引擎。历史 `validationMethod=3`、profile、branch 的合同不同，保留原适配，不能因界面相似强行替换。现有 `matchesAnswer/_matchesAnswer` 私有测试 seam 未新增调用，玩家选择事件不在客户端判题。公开配置/正确答案的服务端保密合同仍由 A 负责；已有按授权揭晓回执渲染的分支不扩大。

## 本批最小补丁

1. 仅在 QA view 带原 `view.nodeId`，与原 sessionId 一起穿过分发器；缺失时不生成替代 ID，不影响其他 kit 的投影。
2. 原 QA observer 增加 sessionId/nodeId。组件复用到另一运行时，清除 burning、输入、选择和旧反馈；同一运行的原 burning 行为保留。
3. intro 定时器与 chooseMedia 回调绑定原渲染代次及原 session/node。切换、隐藏、detach 后旧回调不再落 UI 或发 shoot；代次仅是 UI 生命周期围栏，不是业务身份/完成事实。原错误提示、事件 payload、判题/UNKNOWN/重试通道不变。
4. 两份旧测试仅适配真实 observer 名称，原 case 和断言不改。新增 `tg3-qa-shared-instance-contract.test.js` 源码：两主题加载同一生产组件；实例切换与并列状态隔离；相同内容换运行身份；旧定时器/照片回调；当前照片错误/成功；服务端错误/通过回执。

修改范围：三份既有生产文件、两份旧测试接线、一份新测试和本说明。无新依赖、schema、auth/session/journal、站点源码、baseline 或 ledger 改动。

所有测试源码均为 **NOT_RUN**。最终同源审查、运行和设备验收由 root 统一安排；源码夹具不证明真实发布、HTTP 判题、并发资金链路或微信渲染已通过。
