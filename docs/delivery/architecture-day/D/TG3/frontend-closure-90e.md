# D 前端复用闭环收口（90e 增量）

父：`90e18550ae75e83641e85bf4bb218f08922c7d3e`，tree `55ad70d6e20746d7b47ce368ca1d08f2014b59ff`。独立工作目录 `/workspace/architecture-day-d-tg3-frontend-closure-90e`；原 `/workspace/hengyin` 的 2ec/work 及历次交付目录未修改。只读了本包实际源与指定协调文档，没有运行 Node/Python/Java/DB/HTTP/CI/WeChat 或编译。

结论：已有全库分页、库采用/个人稿读回/节点绑定、主题整包采用、原 renderer/adapter 和后台完整字段组件继续复用。本片不重做这些实现，只补读回字段接缝；全部行为状态 **SOURCE_CONFIRMED_NOT_RUNTIME_REPRODUCED / NOT_RUN**，不代表全游戏验收完成。

## 源码修补与直接用例

`pages/publish/temp/index.js::fillFormWithTemplateData` 原先不把服务端 `interactionType` 放进 formData 或本页互动类型状态；而实际 B DTO 已有此字段，`copyPropertiesToMemberTemplate` 消费它，`toNodeTemplate` 读本页选择。现保留原读回值，不从 validationMethod/名称推断，也不默选第一项；旧缺值仍为空。普通草稿保存/发布沿原 formData 传递，据点仍走专属 submit 和原选择。另将原 audioDuration=0 原样回填，避免 `|| ''` 丢掉合法零值；不改配额、奖励、时长校验或外呼规则。

`tests/unit/template-adopt-draft-and-preview-summary-contract.test.js` 原 **39** 个 case 名/原断言与错误/UNKNOWN/账号/卸载/ID保护保留，追加 **4** 个 SOURCE case：本人续编保原类型及零秒音频；公共采用只信个人稿读回类型且正确转换 ID；据点沿原专属提交；旧缺值不继承上一模板、不补第一项。fixture 调用原页面和原请求封装接口，仅记录 UI 端请求，不冒充真实后端/媒体/审核通过。全部未运行。

## 本包已有闭环：保留而非重写

| 原 owner / caller | 90e 实际合同与限制 |
| --- | --- |
| node-game-catalog / r5-editor | 原 GROUPS/BASE_GAMES/MODIFIERS 和 support-matrix 决定真实选择；原 type/addon/legacy 各合同不强合。已交全游戏索引继续有效，不把 42 key 当游戏总数。 |
| pages/template::getGameRows / fabu::getPublicTempList | 原 list rows/total 完整分页；空、错误和读取代次分开，节点库不退回 homeData 推荐切片；只读受限项不因出现在列表就变为可采用。 |
| temp::getData / fabu::_adoptPublicTemplate | POST /api/template/adopt-draft 的 originalTemplateId 是公共库ID；返回个人ID后回读 myinfo，再 node.templateId 绑定个人ID。没有客户端复制公开剥秘密详情，也不把 preset.playTemplateId 直接当个人ID。 |
| fabu::_bindNodeTemplate / onCreateGame | 保留原 member/page/scope/topic/node/选择代次围栏和正常编辑器返回；旧迟到回包不更换目标。原 UNKNOWN 不自动重发。 |
| pages/template::getTopicTemplates / goTopicConfig | 原 topic-template/list → topic-template/use {id,scope} → 返回 topicId → 原 fabu 草稿；整主题与小游戏是不同资源/ID，不新建复制器。 |
| playkit-view / playkit / playkit-qa / advanced-game | 原 renderer 与 serverAction/serverPayload、session/node/instance、原请求 journal 和服务器判定继续使用；已交 QA 0e6 与全库 e8a 源保持，不改 H session 或完成证明。 |
| admin membertemplate/index + TemplateContentFields + template-content | getMembertemplate 完整详情合并 editForm；共用内容组件编辑/只读；buildTemplatePayload 保留原 JSON 字节、未知字段、原 ID、0/null，经原 PUT 保存再审核。JSON 仅语法/根形状提示，不造统一玩法判定或越权身份。复杂可视化制作仍在原小程序编辑器。 |
| admin templatelibrary/index + detail | 旧列表转原中心，旧详情读真实 projection.memberTemplateId 再跳个人真源，不拿库ID冒充个人ID。不改原 API 或权限。 |

后台这条实际调用链已由 C 的 820a9ef2 源在 90e 集成；本轮阅读了两个 API、四个页面、共用组件/模型及原 template-complete-content 测试源码，未发现本包需要另写实现的接缝，故没有为凑范围改后台。后端 API/DTO 仅只读核对，没有重查 A 已关闭的运行消费 owner。

## root 派生元数据接缝

不修改 baseline 或 action-ledger。该生产文件净增 3 行，原两个动态写锚需 root 最后同步，reason/count/cap 不变：

- `pages/publish/temp/index.js:3740 → 3743`：原完整 statement `      ...couponPatch`。上下文仍为 outcomeContractErrors / difficultyLabel / 原 setData 对象结束。
- `pages/publish/temp/index.js:3867 → 3870`：原完整 statement `    if (Object.keys(patch).length) that.setData(patch);`。上下文仍为 difficultyIndex/difficultyLabel 的有限字段 patch。此前 1458/1459/1502/2942 均未移位。

未新增 UI 控件、跳转或 API；root 按最终同源树唯一生成台账。本片无新依赖、DDL、权限/金额政策或迁移。全部运行验证、设备/浏览器表现、实际库库存和全范围交付仍待 root 统一验收；源码哈希/差分不算测试通过。
