# W2 F0559 / F0560 / F0561：新草稿配置消费

日期：2026-10-13。公共基线：`8d53d3d`，独立 worktree `w2-config-newdraft`。

落实已确认策略：**仅新草稿主动选用；首次保存后来源冻结，既有草稿、已发布与已售内容不采用后台新版本。** 不新增编辑器、数据库迁移、依赖或运行时实时配置查询。

## 合同与实现范围

编码前已通过 Orca 提交字段/方法/文件合同，root 批准私有玩法派生、有限端点登记及实际消费者参数绑定的最小增补。认证、owner/scope、能力位、审核与售卖锁策略保留原入口；root 负责共享鉴权矩阵、台账、基线与最终 DevTools 集成。

| 编号 | 本轮实际消费 | 冻结与边界 |
|---|---|---|
| F0559 | 原 `/api/world/home` 目录选择，复制标题、简介、副标题与可用 HTTPS 封面；保存城市章节和任务来源信息。 | 来源保存为独立 JSON；城市季、任务执行、外部业务目标与奖励发放没有新增运行规则，界面明确仅为来源信息。 |
| F0560 | 原公开路线列表/详情选择，复制地点到既有 fabu 章节；新节点只生成客户端键，不沿用预设节点主键。公共玩法派生为本人私有草稿，同来源只复制一次。 | 首次保存由现有 V2 事务生成章节/节点/私有玩法 ID 并绑定；随后只用私有 ID，重开/重建不再读取公共库。非公开玩法或引用外部人物、状态、券的来源明确拒绝，不静默丢弃。 |
| F0561 | 受控颜色作用于现有发布确认预览；同时声明当前组件与 v1 文案别名，避免继承旧色。封面加载失败回到现有空图占位。 | 保存选用时的色值和资源 URL；损坏旧配置使用正常主题。玩家全局页面没有在本 lane 增加换肤消费者，也没有给既有实例接入实时目录。 |

新增 `themeRuntimeConfig.configSource`：`schemaVersion=1`，可选 `route/style/world` 内容快照，以及服务端生成的 `templateCopies`（公共库 ID → 本人私有 ID）。来源 ID 保留来源含义，不能当作草稿节点 ID。白名单、类型、正数 ID、数组与 60 KB 尺寸上限均验证；颜色只接受指定 token 的六位 hex，不接受脚本/CSS 表达式。

复用 `themeRuntimeConfigJson / publishedThemeRuntimeConfigJson`、`configVersion / publishedConfigVersion`。更新显式换源、删源或再次提供 `sourceLibraryTemplateId` 会拒绝；未传运行配置的 null 更新沿用既有持久化语义，保留来源。原无来源客户端仍可编辑原无来源内容。

`TemplatePublishServiceImpl.copyPublicTemplateToDraft` 先确认公开投影及已发布真源，再复用 `saveOrUpdateDraft` 的能力、传感器与配置校验。私有副本重新处于草稿/未上架状态，不继承公开批准。副本与主题/章节/节点写入处于同一个既有事务，失败整体回滚。公共玩法内容在首次草稿保存时复制，之后不跟随库更新。

`ConfigDraftSource.assertSourceUnchanged` 与 `ThemeRuntimeConfig` 的原重映射一起使用；来源目录不参加节点引用重映射，真正的运行规则仍参加。`ApiTopicController.edit-detail` 仅新增明确 `isDraft`，不把 FULL 编辑窗口当作草稿事实。已使用的 route GET 和 world home 参数有明确名字，保留原方法和授权语义。

## 保存、回读与恢复

新增保存动作仍由 fabu 的原 payload 组装入口产生数据，调用原 create/update（`saveDraft=1`），再独立调用本人 `edit-detail`。回读核对主题 ID/版本、关键主题字段、章节与节点数量/顺序/归属/内容、玩法绑定、票种归属/价格/库存/时间/内容、协作者、剧情块与节点引用、开局/结局/阶段配置、初始状态、重映射后的运行规则及冻结来源。拿到真实回读后才接力本地草稿身份与版本，保留待编排素材。

发布继续使用原发布预检和提交状态机：来源草稿先保存并回读，再以同一个 ID 调用原 `publish-draft`。没有创建另一套发布平台。普通自由探索的路线副本也走 V2，以复用本人私有草稿组合审核门禁；V1 对该派生来源明确报错。

请求发出前同步保存完整本地信封及未核对标记，存储失败则不发送。网络异常、通用 500、空/错号回执都不证明未提交：新建保留未知结果并禁止重建；已有 ID 只回读原保存 payload，不重发修改后的内容。重启未知状态复用现有本地/已保存版本选择；本机修改未经选择不会被覆盖。账号变化、卸载、重复/过期回调失效。保存期间继续编辑保留本机版本，不报告核对成功或继续发布。

**限制：原 create 没有持久请求号/按请求号查找原稿的 API。** 未取得 topicId 的未知创建仍需到“我的项目”核对原稿，本轮没有宣称自动对账。回读门禁核对实际发送的来源、节点、票种、协作者与剧情字段；票务资金到账及复杂剧情运行的完整业务验收仍需 root 实跑。

新本地草稿记录可选用标记；历史本地草稿缺该标记时不会出现选源入口。配置选择弹层位于原 `theme-topic-editor` 包裹内，复用原 node-sheet 标题、关闭与最大高度；物理返回先关闭弹层，不离开编辑器。

## 验证记录

- Node 最终相关用例 **91/91**，0 failures/skipped：独立深复制/新键、坏/缺/重复配置、风格与封面回退、来源信息与奖励分离、ID/版本/顺序/绑定回读、防重与未知结果、实际 Page 保存/重开/同 ID 发布、账号与卸载、物理返回、旧本地稿保留、丢票/协作者/剧情块/开局/结局的拒绝反例及既有编辑器回归。日志：`/tmp/w2-config-node-final.log`。
- Java/MVC/MySQL：11 个相关 suite 合计 **40/40**，0 failures/errors/skipped；原组合中的 38 个先通过，两项新增 IT 修复夹具后单独 2/2 通过。日志：`/w2/config/java-final.log`；实际 MockMvc 参数绑定、公开可见性、私有副本/能力门禁、来源冻结、既有草稿与发布回归均已执行。
- MySQL：`ConfigNewDraftMySqlIT` **2/2**，0 failures/errors/skipped，原始 Surefire 已由 worker 与 root 独立回读；quiet 成功日志 `/w2/config/java-it-verified.log` 为空，不以空日志冒充通过。使用 root 注入的专属 fixture 库，仅创建/清理本类随机命名的任务表，真实原 Mapper、私有模板保存服务与 Spring 事务；其他业务依赖为 mock，非 HTTP/真实身份验收。
- 首轮未通过项已如实定位：新测试缺 nodeTime/私有草稿状态、归档缺既有 frost-solo 测试输入、Date 导入歧义、root 指定 fixture 库尚未创建、节点 Mapper 需空供给表、不能 spy 最终 JDK 代理、DDL 注释内分号。没有压制错误或改业务/审核规则来修测试；DDL 切分复用现有 Spring ScriptUtils。
- UI lint：U1/U2/U3 与 A11Y 通过；整套 UI-GATE 仍有 **29** 项既有 fabu 基线行号漂移与原 play 债务。root 负责准确重新锚定，worker 未修改基线。日志：`/tmp/w2-config-lint-ui-final.log`。
- root 离线截图发现新弹层位于主题包裹外的问题，本轮已修复并补返回行为测试；root 已独立复拍 `visual-error-fixed.jpg`，浅色主题、标题、关闭与失败重试显示正确。实际色值样片/真实身份/设备效果仍待 root 核验，worker 未打开或切换共享窗口。

Java 构建、单测、MySQL 均仅在批准 VPS runner `099eb30714c0c5d61c9c26e220a1e2ca0be542dde54e3a0a367a7611d758885e`、显式 `chengyin-vps-sandbox`、`flock /tmp/w2-java.lock` 下执行；Maven 448 MB、Surefire 320 MB。环境文件只用于 `docker exec --env-file` 注入，没有输出或提交凭据。

后端归档 SHA-256：`e7dcf315741bd5b13bea4387fbbe5b185cd43ff208c82829d17c6322031fa959`，准确回读后解包到 `/w2/config`，只额外包含本用例所需的现有公共 frost-solo fixture。IT 夹具后续单文件修正 SHA-256：`32920e955699db4e3c1e7189460755f357f4042bc1830de73c0cfc92e0b5a2b5`，同样独立校验后执行。

后端提交：`84c5efb08fce48896c16399e713a7ac98efdae46`；准确暂存 tree：`2615c28de19ae0fba0f95a860bee31dd5fd85d8b`。正常提交使用 root 批准的 `/tmp/w2-followup-hooks-config`，对暂存准确源码在专属 VPS 编译；不使用 no-verify；该后端提交的 hook 全 compile exit 0，hook SHA-256 为 `65c4b1b301cbc7ae15ff8281605f9ec1bea666f141dc4a5c8461940d96c64a48`。公共主工作区、其他 lane、共享台账/基线/根 README/部署文件未触碰，未导入私有历史。

## 仍需验收

真实作者/跨身份正常 UI 选择与保存重开、真实已售/已发布实例业务读取、付款/奖励/外部任务执行、完整复杂剧情与票务、微信上传、真机、CI、合并及部署均不是本 worker 的通过证据。颜色/文案/URL 的内容副本已冻结，但没有存储外部资源字节副本，原 URL 后资源删除或替换的永久可用性仍有边界。未调用付费服务、生产写入、公共 push 或部署。
