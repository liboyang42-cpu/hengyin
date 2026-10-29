# TG3 全部游戏的现有前端复用入口

结论：当前源码已有统一目录、完整模板采用/节点绑定与运行分发；本片修复实际发现入口只取推荐切片/固定首屏、异步绑定可能落到新目标的接缝。新主题配置既有机制并绑定个人模板，不另造主题专用引擎。QA 实例隔离补丁保持冻结于 `0e6b05e617f2099c5e0d32659ac3f99968ff7c3c`（父 `fa5c263440573992701263d6bee60830844a49b8`），本片以它为增量父。完整目的按 `ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f` 的 root 施工方案执行。

**SOURCE_CONFIRMED_NOT_RUNTIME_REPRODUCED。所有 Node/Java/DB/HTTP/WeChat/CI 测试 NOT_RUN。** 以下数量从本快照声明推导，不是硬编码产品总数，也不是生产官方模板记录数。

## 真实公共入口

- `pages/publish/utils/publish/node-game-catalog.js` 的 `GROUPS/ALL/findGame`：可发现条目；`BASE_GAMES/PICKER_GROUPS`：当前 R5 编辑选择，`MODIFIERS`：修饰。不要维护另一份主题专用列表。
- `pages/template/index.js` → `pages/templatedetail/templatedetail.js` → `pages/publish/temp/index.js` / `temp/r5-editor.js`。官方目录分类只是发现内容，不是独立引擎。已有 `/api/play/theme/support-matrix` 限制仍由原消费者执行。
- `pages/publish/fabu/index.js::_adoptPublicTemplate` 先 POST `/api/template/adopt-draft`，携原 `originalTemplateId/adoptSourceOnly/scope`；读回 `/api/template/myinfo` 的会员草稿，`_bindNodeTemplate` 才写 `nodesForm.templateId`。公共模板 ID 不能直接冒充会员绑定 ID；采用 UNKNOWN 不自动重发。
- `pages/publish/temp/index.js::getData` 从公共详情进入也先 `/api/template/adopt-draft`，只信返回个人ID，再切 `isMyScope` 回读 `/api/template/myinfo`。没有用剥秘密的公开JSON伪造新游戏；已有错误/UNKNOWN边界原样保留。
- `pages/play/utils/playkit-view.js::pickPlayKit/serverAction/serverPayload` → `components/playkit/index.wxml/index.json` → 各原 renderer → `pages/play/index.js::onPlayKitAction/_submitPlayKitAction` → `components/advanced-game/index.js::action`。原请求身份、版本、UNKNOWN 和单位/字段适配不变；组件不判定答案。
- `advanced-game-preview.js::BUILDERS` 只是编辑预览，仍使用原组件；demo/预览结果不落真实配置，不算玩家通关。主题模板 `topic-template/list|use` 是整主题复制，和玩法模板是不同资源身份。

## 从实际声明推导的全部目录条目

本快照：34 个可发现条目、31 个 R5 具体形式、34 个运行优先段、37 个 playkit 组件注册。四组集合不同。

下表 `renderer` 均位于 `pages/play/components/playkit-*/index`；普通动作通过上面的原 Advanced 提交适配。`R5 只读/非节点` 不因可渲染而重新开放。

| key / 名称 | 配置段 / mode | renderer | 原提交/专门适配 | 编辑/复用边界 |
| --- | --- | --- | --- | --- |
| album / 相册 | album | album | submitAlbum | 原 R5 形式；模板预览复用 playkit-album；正式相册为 dream 展示块，不作为待通关节点 |
| qaText / 文字问答 | qa / TYPE | qa | SUBMIT_QA | 原 R5 形式 |
| qaPick / 选项问答 | qa / PICK | qa | SUBMIT_QA | 原 R5 形式 |
| qaShot / 拍照打卡 | qa / SHOT | qa | _submitKitPhoto | 原 R5 形式；复用 QA SHOT；shoot 先上传再提交，不经过一步直发动作表 |
| branch / 分支剧情 | branch | branch | CHOOSE | R5 只读旧规则；旧独立 branch 只读保留；R5 outcomeRules.version=1 的本站反馈不是新独立基础玩法 |
| sort / 排序 | sort | sort | SUBMIT_SORT | 原 R5 形式 |
| match / 连线 | match | match | SUBMIT_MATCH | 原 R5 形式 |
| classify / 分类 | classify | classify | SUBMIT_CLASSIFY | 原 R5 形式 |
| estimate / 猜数字 | estimate | estimate | SUBMIT_ESTIMATE | 原 R5 形式 |
| pricePair / 猜图 | pricePair | pricepair | SUBMIT_PRICE_PAIR | 原 R5 形式 |
| hidden / 找东西 | hiddenObject | hidden | SUBMIT_HIDDEN_OBJECT | 原 R5 形式 |
| predict / 竞猜 | predict | predict | SUBMIT_PREDICT | 原 R5 形式 |
| random / 抽卡 | random | random | DRAW | 原 R5 形式 |
| profile / 角色建档 | profile | profile | SUBMIT_PROFILE | 原 R5 形式 |
| note / 留言 | note | note | SUBMIT_NOTE | 原 R5 形式 |
| objectCard / 拍物成卡 | photoCheck / CARD | objectcard, photocheck | _submitKitPhoto -> SUBMIT_PHOTO_CHECK | 原 R5 形式；photoCheck.mode=CARD：整屏 playkit-objectcard，inline 为 playkit-photocheck；不是新判题引擎 |
| bingo / 九宫格 | 主题级 | bingo（主题级视图） | 服务端状态投影，verdict 为页面本地事件，不登记虚构提交动作 | 非 R5 节点选项；目录 section 为空；R5 节点选择禁用，在主题里配置；运行视图仍可投影 bingo |
| steps / 计步挑战 | steps | walk | SUBMIT_STEPS | 原 R5 形式 |
| react / 变色就点 | reaction | reaction | START_CHALLENGE, SUBMIT_REACTION | 原 R5 形式 |
| shake / 弹球 | ballShake | ballshake | START_CHALLENGE, SUBMIT_BALL_SHAKE | 原 R5 形式 |
| quiet / 安静挑战 | quietHold | quiethold | START_CHALLENGE, SUBMIT_QUIET_HOLD | 原 R5 形式 |
| compass / 罗盘指向 | compass | compass | START_CHALLENGE, SUBMIT_COMPASS | 原 R5 形式 |
| shout / 喊一嗓子 | shout | shout | START_CHALLENGE, SUBMIT_SHOUT | 原 R5 形式 |
| countdown / 倒计时 | countdown | countdown | START_CHALLENGE, SUBMIT_COUNTDOWN | 原 R5 形式 |
| stopwatch / 精准停表 | stopwatch | stopwatch | START_CHALLENGE, SUBMIT_STOPWATCH | 原 R5 形式 |
| photoCheck / 拍照审核 | photoCheck | objectcard, photocheck | _submitKitPhoto -> SUBMIT_PHOTO_CHECK | 原 R5 形式；拍照后先上传再向原会话提交；临时路径不能作为结果 |
| check / D20 | check | journey-check（玩家页直挂/编辑预览） | onJourneyCheckAction -> /api/play/check/roll / reroll / settle; r5 -> _r5CheckAction | 原 R5 形式；journey-check：玩家页直接挂卡；编辑预览通过 playkit；不同于 diceRoll/d20 |
| d20 / D20 加值 | diceRoll / d20 | diceroll | ROLL_DICE | 原 R5 形式 |
| typeIn / 输入挑战 | typeIn | typein | START_CHALLENGE, SUBMIT_TYPE_IN | 原 R5 形式 |
| coin / 抛硬币 | coinFlip | coinflip | FLIP_COIN | 原 R5 形式 |
| dice / 掷骰子 | diceRoll / d6 | diceroll | ROLL_DICE | 原 R5 形式 |
| scan / 扫码参与 | scan | scan | SUBMIT_SCAN | 非 R5 节点选项；findGame 可读旧 key；R5 基础选择列表不列出，原扫码提交合同保留 |
| blindTaste / 闭眼盲品 | blindTaste | blindtaste | SUBMIT_BLIND_TASTE | 原 R5 形式 |
| dailySign / 今日城市签 | dailySign | dailysign | CLAIM_DAILY_SIGN | 原 R5 形式 |

## 不属于上述新节点形式的现存段与修饰

| 原段/修饰 | 实际复用与保留边界 |
| --- | --- |
| diyName → diyname | 存量取名合同，R5 legacyRows 只读，不重新开放编辑；SUBMIT_DIY_NAME |
| silentOrder → silentorder | 存量沉默点单；giveup 本地收尾，不编造通关动作，R5 只读；本地/只读事件 |
| slowTask → slowtask | 存量慢任务，START/CLAIM 原合同，R5 只读；START_SLOW_TASK, CLAIM_SLOW_TASK |
| musicCorner → musiccorner | 存量音乐角，toggle 播放器事件，不当成新判题，R5 只读；本地/只读事件 |
| timeWindow → timewindow | MODIFIERS 时段限定，open 只读刷新原状态；subscribe 走原动作；SUBSCRIBE_TIME_WINDOW |
| bingo → bingo | 目录 section 为空；R5 节点选择禁用，在主题里配置；运行视图仍可投影 bingo；本地/只读事件 |
| modifier:timer | 原玩法的限时修饰；不创建独立判题 renderer |
| modifier:leaderboard | 排行榜修饰，保留原后端 support-matrix 限制 |
| modifier:multiplayer | 旧配置只读，不因组件复用重新启用 |

- `r5-editor.js::legacyRows` 明确只读保留 branch、silentOrder、diyName、musicCorner、slowTask、multiplayer 和旧 variants 等设置。本片不改变支持范围。
- `steps` 的正式投影是 `walk`；旧半屏 `steps` renderer 已由原 `ORPHAN_BRANCHES` 单独登记保留。不是本轮新增引擎，也不在此删除兼容路径。
- 旧 `validationMethod` 的到达/文字/拍照/选项/扫码/GPS/偏好/估数/猜图/找物保留原适配；尤其旧 `3` 不凭标题强并新 `qa/PICK`。profile、branch、Encounter、主题 bingo、addon 合同不同，保持各自 authority。

## 已共用、入口分散与真实重复的界线

- **已共用**：QA TYPE/PICK/SHOT 共用 `playkit-qa`；d6/d20 共用 `diceRoll/playkit-diceroll`；对象卡与照片审核共用 photoCheck 会话及照片提交、保留不同展示；其余目录项共用编辑器、模板绑定、会话分发与原提交适配。
- **入口分散但同链路**：官方资源库、我的玩法、编辑器试玩、节点绑定、玩家运行分别是发现/编写/预览/绑定/执行阶段；实际采用和绑定 caller 已串联，不是每个入口一个判题实现。
- **本次没有证实新的可达同合同重复引擎**：37 个组件包含不同机制、展示壳和兼容壳；预览 BUILDERS 不能当第二套真实判题。不会为统一而复制答案逻辑、强合旧协议或再建注册平台。此结论仅限本清单所列可达前端源，外部参考由 root 汇总。

## 整套主题游戏与小游戏分别复用

主题货架 `getTopicTemplates` 读原 POST `/api/template/topic-template/list`；`goTopicConfig` 按原权限/登录门禁发 POST `/api/template/topic-template/use {id,scope}`，仅拿返回 `data.topicId` 导航 `/pages/publish/fabu/index?id=<新主题草稿>`。原服务端负责整包复制；客户端不靠把名字交给AI重造主题。此正确链保持原样，新增直接caller测试源码。

小游戏是另一身份链：公共 library ID → adopt-draft 返回个人模板 ID → node.templateId。preset.playTemplateId 仍是公共库ID，不能直接塞进节点。整个主题模板、新主题草稿、公共小游戏、个人小游戏模板和原运行session均不合为一个ID。发布冻结/后台采用细节由G/B交付；本片不代签其运行结果。

## 最小源码修补与装配

- **完整发现**：原 `fabu::getPublicTempList` 只合并 homeData 的推荐/热门/最新，推荐外的库内容不可选；现复用 `/api/template/list {pageNum,pageSize}` 及真实 `data.rows/total` 完整分页，不加分类猜值、不伪造内容、不把失败当空库。`ApiTemplateController.commonTemplateList` 已证明 category_id 可省略，服务端仍只返回原合法公开记录。库主页 `getGameRows` 也解除固定200条首屏，原关键词/分类置顶和两tab缓存不变。
- **目标冻结**：公共采用、个人模板补读、编辑器返回分别复用原 memberId、节点对象和 nodeKey、主题ID、scope、选择代次；网络回包同时绑定H原页面代次。正常子编辑器导航的hide不会错杀同目标 EventChannel，换账号/节点/主题后不再绑定旧结果。采用到个人详情完成前保持singleflight，原UNKNOWN不自动重发。
- **内容无损**：现有本人模板另存 `template-author-fields.COPY_FIELDS` 补原 interactionType，不按 validationMethod 推断、不客户端复制公共详情。依赖 B 同字段DTO接线，无新接口。
- 依赖的 QA 候选 `0e6b05e617f2099c5e0d32659ac3f99968ff7c3c`：原 session/node 身份透传、切实例/隐藏/detach 清旧回调与 burning 状态；两个主题加载同一个生产组件的 6 条测试源码已交，不更改提交 payload/判题/API。
- 本片 `playkit-view-contract.test.js::unfedOf` 仅在原会话夹具中补 `nodeId:12`，对应 QA 新增的实际节点 prop。原“所有目录段必须产出/绑字段必须有来源”、负控和空 `KNOWN_UNFED` 均保留，未靠豁免消除缺字段。
- `fabu-template-binding-copy.test.js` 保留原13case/断言并追加11case；`template-tab-failure-state-contract.test.js` 保留原7case/断言并追加4case，覆盖上述分页、失败/迟到、采用/个人ID、实例目标和整包主题原入口。它们是配套源码，未运行。
- 原 `playkit-view-contract`、`generic-gameplay-r5-editor`、`legacy-game-template-compat` 的目录覆盖/映射/旧配置保护源码继续保留；加上 QA 原错误分支与新实例隔离源码。没有执行这些测试或声称设备表现已验证。
- `all-games-source-index.json` 提供逐项配置/动作/绑定、全部注册组件与字面事件、原源码 git blob/SHA256，可与 A/B 的后端机制/模板身份表按真实 key 对齐。未读取模板数据库、未调用在线服务，正式库内容数/可用性和端到端运行仍待 root 同源统一验收。

## 本片边界

只改上述直接发现/采用/绑定caller和作者字段清单，不修改判题机制/API、auth/session、合作站点、ledger/baseline、依赖/schema；不生成台账、不运行测试/微信工具。回退本片会恢复库发现截断和旧异步绑定接缝，不能把回退当验收；QA候选保持冻结。
