# qx-b4 结果：模板换城、预设、组队与合作

分支 `claude/qx-b4-20261109`，基线 `origin/integrate/questo-ui-20261110` @ `449fb80d`。
截图在 `shots/`，全部来自走查 rig：真实后端（基线 jar，见下文“rig 补丁”）、真实 MySQL 8 / Redis，以及按本分支小程序源码打出的浏览器包。截图 390×844，JPEG q70。

## 逐项结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机仍未验 |
|---|---|---|---|---|---|
| U02-01 采用条件 | fixed | 主题模板详情「能力」页底部新增「采用条件」卡，七行：地点、投入、验证、端侧、语言、许可、版本。只翻译 `/info` 的 `contentDelivery`。没有事实时写「待补充」、「待核实」或「许可信息未提供」，不写 0、已验证或两端可玩。`PARTNER_ONLY` 显示「仅对合作商家开放」。整段缺失时整块不画。结构复用同页能力 tab 的 `xb-caps` 行。 | `pages/templatedetail/topic-detail/*`、`pages/templatedetail/utils/topic-adoption.js` | 单测 `qx-b4-topic-adoption`（5 例）。rig 截图 `U02-01-before/after` 读的是真实 `/info`：4 个地点、第 5 版、许可未提供。 | 真机字体与安全区 |
| U02-02 沿用 / 换城采用 | fixed | 「应用」先弹 peek 弹层：「沿用原地点」或「换一个城市」，后者用原生地区选择器选到市级 adcode。`/use` 带 `bindingIntent`、`targetCityCode`、`sourceContentRevisionId`（取当前发布修订），仍读对象 `data.topicId`。服务端拒绝原文写在选项下面，保留已选方式。网络结果未知时不重发（`/use` 没有请求号，重发会多复制一份），按钮改成「查看我的草稿」。来源没有地点事实时按旧版直接采用。 | 同上 | 单测（`usePayload`/`useResult`）。截图 `U02-02-after*`：未选、同城、换城、同城被本地拦下、服务端拒绝原文。rig 补丁后（见下）换城采用真实成功：topic 990016/990017 回 4 条 `LOCATION_REBIND_REQUIRED/error`。 | 真机原生地区选择器 |
| U02-02 后端阻塞 | fixed（后端） | `TopicContentCopyServiceImpl` 里来源快照与发布头逐字比较。`published_route_graph_json` 是 MySQL `JSON` 列，读回的是库规范化文本，所以在真库上逐字比较永远不等。结果是：只要模板有发布修订，`/use` 一律报「来源修订与当前发布快照不一致」，不带修订号也一样。现改为按 JSON 文档语义比较，内容有任何不同仍判不一致，解析失败也判不一致。 | `chengyinhub-system/.../TopicContentCopyServiceImpl.java`、`TopicContentCopyCityAdoptionTest.java` | `mvn -q -pl chengyinhub-admin -am -Dtest=TopicContentCopyCityAdoptionTest test`：21/21 通过，新增 2 例（规范化文本仍匹配；不同内容、数组换序、非 JSON 仍拒）。rig 真库复现与修复后回读如上。 | — |
| U02-03 地点待办 | fixed | 编辑器第 1 页章节区上方新增一行「地点待办」，值为「N 项挡住发布」或「N 项待复核」，点开为 full 档弹层（挂在 `root-portal`）。每个地点显示看什么、怎么做、看不到时、状态。只有 `level=error` 标红「挡住发布」，`warn` 只写「待复核」，端上不拦发布。「选择节点」用已保存且有地点的节点调 `/binding`，带 `expectedRevision`；服务端原文写在该行下面，409 时重读。没有地点合同的主题整块不渲染。 | `pages/publish/components/location-todos/*`、`pages/publish/utils/publish/location-todos.js`、`pages/publish/fabu/index.{wxml,json}`（各加一行） | 单测 `qx-b4-location-todos`（4 例）。截图 `U02-03-before/after/after-todos/after-no-bindable-node`，读真实 `binding-status`。 | 真机 `root-portal` 层级：rig 不实现 `root-portal`，截图里编辑器底栏压在弹层上，属 HARNESS |
| U02-04 来源版本 | fixed | 地点待办弹层顶部显示「来源模板 #id · 来源修订 #n · 换城采用」；修订为空时写「未锁定来源修订」，不补 1。 | 同 U02-03 | 单测；截图 `U02-03-after-todos` 显示真实「来源模板 #990014 来源修订 #5 换城采用」。 | 「查看新版差异 / 另存新草稿」没做：服务端没有对应动作，按规则不出按钮 |
| U02-05 / §27.5 素材署名 | 部分 fixed | 玩家侧：主题模板详情的节点卡片按图读 `GET /api/media/asset/attribution`，只显示署名、「AI 生成」、「来源待确认」；读不到或未审核（422）不画。不显示许可备注、模型、时间或存储 key。 | `pages/templatedetail/*` | 单测 `creditText`（1 例）。rig 夹具节点没有配图，这条路径在 rig 里没走到。 | 作者侧 `PUT /api/media/asset/{id}/provenance` 声明编辑**没做**（见 needs owner）。语言与公开预览的缺译提示：服务端只有 `SINGLE_LOCALE`，已在采用条件「语言」行如实显示。 |
| §21 预设列表与草稿 | fixed | 新页 `pages/publish/preset/index`（发布分包），结构照抄队伍详情页：标题、卡片、行。分三步：列表、填写、草稿已建好。控件按 `requiredFields.kind` 出：text、number、choice、order（上下移，轻震）、bin_map（分段）。选项显示作者在同组字段里填的文字，没填显示「第 N 项」。「还要自己做」原样列出 `authorTodos`。`requestId` 每张表单一个，结果未知时按钮改「查询原请求」，同号同体重发；服务端拒绝后换新号。入口是「创建节点玩法」引导页的「从预设开始」，商家岗位不出，因为预设草稿只属于本人。 | `pages/publish/preset/*`、`pages/publish/utils/publish/gameplay-presets.js`、`pages/publish/template-intro/*`、`app.json`（发布分包 +1 页）、`scripts/uiaudit/route-matrix.json` | 单测 `qx-b4-gameplay-presets`（7 例）。rig 真实 `/list` 返回 8 个预设，真实 `/draft` 建出草稿：`cms_member_template` 6005，member 9004，`draft_status/status/publish_status=0`。截图 `S21-*`。 | 「去编辑草稿」跳 `pages/publish/temp/index?id=` 后，rig 里编辑器没有发出读模板请求，页面为空（HARNESS，没查到原因），草稿以库回读为证 |
| U06-01 席位与邀请 | fixed | 队伍详情新增席位卡：持有效票、票已失效（>0 才出，红字）、待审申请（>0 才出）、还可加入、邀请到期或已失效。加入页同卡加「邀请」到期行，并按 `viewer.joinBlockers` 码显示提示，例如「需要先买这一场的票」。提示不禁用按钮，末席和票仍由 `/join` 回包裁决。不读已撤回的 `claimable` 等字段。 | `pages/team/{detail,join}/*`、`pages/team/utils/team-seats.js` | 单测 `qx-b4-team-seats`（4 例）。另外两个旧测试的纯函数白名单各加一项（`team-p1-contract`、`guest-browse-login-on-write`）。截图 `U06-01-before/after`、`U06-01-join-before/after`，读真实 `/api/team/info` 席位投影：rig 夹具队 501。 | — |
| U06-02 角色与私密线索 | already fixed | 本轮服务端没有新合同；角色区与投影在原页面，未改。 | — | 未改代码 | 同局多身份真机 |
| U06-03 队长掉线、退队、接管 | already fixed | 写失败沿用原页面的 `commandReject.message`（服务端原文）。清单明确本轮不新增预留席、接管入口或角色唯一提示，所以不加。 | — | 未改代码 | — |
| U06-04 组织者与共同成果 | already fixed | 俱乐部进度与商家接待页沿原实现，本轮没有新字段。 | — | 未改代码 | — |
| §26.1 合作玩法 | fixed（未截图） | 合作 TEAM 局背包的任务行加「提示」，打开 half 档面板：`POST /api/play/help/shared` VIEW；层级三态为免费、消耗 N 积分、不可用（带原因），已解锁的层显示「重看」不扣；顶部「本局剩余免费 N 次」；位置类不画。USE、SKIP 带 `requestId`，未知时同层同号重查。跳过只在服务端给出可用 SKIP 层时出现，二次确认「本题记 0 分并标辅助完成」，成功后不放动画，直接刷新权威状态。合作局 `sharedPreview` 与 `command` 的 QUEST_RESOLVE 在有真实 `answerRevision` 时追加原题 `runtimeInstance` 与 `answerRevision`。不画共享计时、共享跳过额度或全队一个分数。 | `pages/play/utils/coop-help.js`、`coop-help-methods.js`、`theme-backpack.{js,wxml,wxss}`、`pages/play/index.js`（3 行 mixin） | 单测 `qx-b4-coop-help`（5 例）。play 页与 theme-backpack 的旧测试全部通过。 | **rig 没有合作 TEAM 局**（需要冻结 `cooperationVersion=1` 的已发布主题与团队局），面板没截到图，真实回包未走。没有用假数据重画。 |

## 新页面的结构模板

- `pages/publish/preset/index`：照抄 `pages/team/detail`（`team-page` 深色、`cy-page-title`、白卡与行），选中样式照抄 `cy-option-sheet`。
- 地点待办弹层：沿用 `cy-sheet fixed="full"` 和 `cy-option-sheet`。
- 采用方式弹层：`cy-sheet` peek，行样式照抄 `cy-option-sheet` 的平列表与右侧圆点。
- 合作提示面板：沿用同文件 theme-backpack 的 `cy-sheet` 玩家色变量和 `tb-button`。

## 验证

- `node --test` 新增 5 个文件共 25 例全过：`qx-b4-topic-adoption`、`qx-b4-location-todos`、`qx-b4-gameplay-presets`、`qx-b4-team-seats`、`qx-b4-coop-help`。
- 受影响的旧测试全过：`r12-k9-pages`、`merchant-operator-ui-contract`（正则随 `usePayload` 更新一处）、`team-p1-contract`、`guest-browse-login-on-write`、template-intro 6 份、theme-backpack 与 play 页全部引用测试。
- `get-auth-matrix-contract` 在基线上本来就挂 4 例，与本分支无关。
- Java：`TopicContentCopyCityAdoptionTest` 21/21 通过。
- UI 门禁：`node chengyinhub-xcx/tests/lint/ui-integrity.js` 在最终树上打印 `UI-GATE-0:PASS`（中途发现合作面板混入扫描与基线行号漂移，已按门禁改代码修好，没有加基线）。`action-ledger.test.js` 在本容器跑超过 25 分钟未出结果，未取得结论。
- `nav-page-title-spacing-contract`：共享标题页 89 → 90（新增预设页），间距断言通过。
- `build-route-matrix.js --json` 新增 `pages/publish/preset/index`（player）。action ledger 已用 `--write` 重建。

## rig 补丁（HARNESS，均不在产品代码里）

- Docker Hub 429，dockerd 改用 `mirror.gcr.io` 镜像拉取同 digest 的镜像。
- `app.py` 透传 `CHENGYIN_OSS_*` 假值，只用于离线 URL 签名；否则 `/info` 序列化封面 URL 时 500。
- `mpweb/src/nav.js`：地区选择器补 `detail.code`（微信真实会给 adcode）。
- 夹具：topic 990014 置为模板（`is_template=1`、`city_code=310100`）；用 SQL 种了队伍 501，成员 9004、9009，另有一条 9001 的待审申请。
- 基线后端对 rig 副本打了两处补丁才走通采用。① 本分支的 JSON 比较修复。② `WorkshopMapper.lockLicenseForDraft` 的 `FOR UPDATE OF o` 改成 `FOR UPDATE`。② **只在 rig 副本，不在本分支**，见 Cross-lane。

## Cross-lane

- **阻塞（工坊 / b5 或后端 owner）：** `chengyinhub-system/src/main/resources/mapper/business/WorkshopMapper.xml:102` 的 `lockLicenseForDraft` 用了 `... for update of o`，被 Druid wall 拒绝（`sql injection violation ... syntax error: not supported`）。主题 `/use` 复制带节点模板的主题时会走到这里，在真库上整条采用 500。本分支没改，原因是改锁范围要工坊 owner 判断：`FOR UPDATE` 会连带锁 m、i、l 行。
- 共享文件最小改动：`app.json`（发布分包 +`preset/index`）、`scripts/uiaudit/route-matrix.json`、`pages/publish/fabu/index.{wxml,json}`（a5/a5d 也在改，各 +1 行）、`pages/play/theme-backpack.wxml`（a1 V3-16 也在改；只在任务行加一个按钮，文件尾加一个弹层）、`pages/play/index.js`（b1 也可能改；3 行 mixin）、`pages/publish/template-intro/*`（a5 V3-47 附近）。
- b1 的「帮助面板」是个人局 `/api/play/help`；本 lane 的合作面板是 `/api/play/help/shared`。两者各自独立 mixin，没有共享状态。

## needs owner（附已实现的默认）

1. 作者侧素材来源声明编辑（`PUT /api/media/asset/{id}/provenance`）该挂在哪个上传位？**默认：本轮没做**，等 owner 指定入口。
2. 预设入口放在「创建节点玩法」引导页的「从预设开始」，而不是「我的模板」（那里是 a5 V3-47「自己建一个」）。**默认：引导页**，商家岗位不出。
3. `/use` 结果未知时不自动重发、改成「查看我的草稿」，因为接口没有请求号。**默认如此。** 若要做到真正幂等，需要后端给 `/use` 加 `requestId`。
4. 预设缺项清单超过 120 字时，会被 `UserSafeMessage` 掩码成「系统繁忙」。**默认：端上先按 `requiredFields` 本地标红「必填」再发**，服务端仍是最终校验。是否放宽后端长度上限由 owner 决定。

STATUS: PARTIAL - §26.1 合作帮助面板没截到图（rig 没有合作 TEAM 局）；U02-05 作者侧来源声明编辑未做；采用流程在真库上仍被 WorkshopMapper `FOR UPDATE OF` 阻塞（Cross-lane）
