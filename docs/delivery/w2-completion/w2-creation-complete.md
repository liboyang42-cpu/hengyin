# W2 creation：story-create / a11y / L6 完成交接

本任务在公共基线 `0f0c32f` 的独立工作树审阅并融合三线源码差分，另补弱网恢复、同步回读、共享动效即时更新、线段遮挡与快照一致读。没有导入私有历史、refs、整包研究资料或旧测试日志，没有恢复暂停的新 22 项。本报告中的“已实现”指明确代码范围，**不表示完整业务编号已验收**。

## 冻结合同与文件所有权

- 媒体：`ChapterBlockDTO.altText`，`ChapterFlowCompiler` 媒体校验/物化，`JourneyReviewTexts` 两种媒体替代文字收集；新增字段可选，旧数据兼容，无新媒体权限或新依赖。
- 作者端：`pro-editor-story` 的媒体往返及 `setMediaAltText`；`fabu/index.js` 仅 `onStoryMediaAltText`、`insertStoryImageAt`、`uploadStoryAudio`、`_validateWhitelistLockedFields`、`_openCopiedDraft`，WXML 仅两类媒体块替代文字输入；未覆盖整个发布文件。
- 游玩端：`play/index.js` 仅媒体错误/重试/音频暂停、心声待同步方法及读取成功后的补发、减动效保存反馈和 `onShow` 的现有字阶消费；不动 NPC、完赛推荐段。
- 无障碍：共享 btn/field/cell/search/tabs，21 个明确隔离组件的系统静态样式，motion-preference、reduced-motion behavior；sheet/modal 仅叠层隐藏、处理中与禁用语义。
- L6：roam/privacy/settings/session、roam-consent/player-memory/route-privacy/share-snapshot、官方活动任务列表与经验来源、分享 Controller/Service/Mapper 和新增版本迁移。
- `TopicCreateDTO`、`ApiTopicController`、server-draft、app.json、token/鉴权、公共配置、动作台账及门禁 baseline 均未改。

保留旧方法 `publish(Long,String)` / `revoke()` / `revoke(String)` / Service `publish(Long,Long,String)`；映射入口增量采用已批准的可选 `expectedVersion`。`selectSnapshotByToken(String):Map<String,Object>` 单 SQL 读取内容与版本，旧 Mapper 读方法保留。客户端 `revokeShareSnapshot(app,token,storage?)` 的第三参数只持久化本人撤回待办；不改变服务端归属规则。`writeReducedMotion(storage,value)` 返回 `null` 表示主键保存失败，`false` 仍表示已保存的正常动效。

## 逐编号实施和证据

“JS”是实际 helper/页面方法或源码契约测试；“Java”是真实 VPS 编译和相关单元测试。它们都不包含微信、读屏、来电、真实身份或业务验收。下表逐编号区分 DB、正常 UI、外部验收，不把能力桩记为真机证据。

| 编号 | 已实现源码 | 缺实现 / 真实断点 | 代码验证 | DB | 正常 UI | 外部验收 / 真正阻塞 |
| --- | --- | --- | --- | --- | --- | --- |
| F0263 | altText 编辑→序列化→编译物化→原审核文本收集→图片失败文字/重试；上传防重和换章迟到保护 | 上传归属回执、逐版本媒体审核与角色/队伍/秘密媒体的受控 URL 合同 | JS + Java 通过 | 不需迁移，未跑真实作者保存回读 | 未验，root 集成截图 | 未验；需明确现有公开 URL 和新受控媒体引用的权限合同 |
| F0264 | 同段文字替代、加载/暂停/失败重播；后台暂停、段首重播说明；音频上传防重 | 自动转写、受控音频访问及跨端续播未实现 | JS + Java 通过 | 不需迁移，真实读回未验 | 未验 | 来电/后台/过期链接未验；媒体访问合同同 F0263 |
| F0522 | **App 现有实现可得**：预制人生入口、路线/照片/定位消费者、SecureStorage 和按场次/主题存档 | 存档 key 未含账号；读存储异常未收尾；坏档/未知版本直接回初态，无备份/显式恢复入口 | 只读核对源码和已有测试，未运行 Flutter 测试 | 未验 | App 未验 | 当前 Task 不修改另一私有 App；root 单独划定 App 范围 |
| F0523 | App `PrefabState.save/restore` 已有 v1/v2、场景状态往返与 v1 step 重置，终章已有权威完成回读 | 异步写盘失败无用户反馈；迁移未给用户说明；坏档备份/容量不足/正式存档与预览隔离需修 | 只读核对，未运行 Flutter 测试 | 未验 | App 未验 | 与 F0522 同一 App 责任边界，不能写成“源码缺失” |
| F0487 | 加载/空/失败/重试保留，列表明确教程版本未标注 | 模型没有发布状态/受众/适用版本字段；现列表接口无公开过滤 | JS 通过 | 未验 | 未验 | M0091 前置和教程管理合同未闭合；不编造草稿政策 |
| F0488 | 页面与真实教程弹层都结束异常响应 loading；版本未知和任务/奖励权威说明 | 详情仍按 ID 读取；状态/受众/版本过滤未定义 | JS 通过 | 未验 | 未验 | 同 F0487；没有新增读取端点或状态字段 |
| F0458 | 当前主题详情显示“玩法支持情况暂未确认”，明确用户反馈处理未开放 | 公共基线已删除旧 supportMatrix 面板，不能恢复旧 UI 假装存在；没有反馈处理→关闭→通知链 | JS 通过 | 未验 | 未验 | 需真实工单责任/可见范围合同；当前状态文案修正已经完成 |
| F0498 | 本机草稿按账号+clubId 隔离，恢复/放弃、坏档保留、存储失败提示；保存前读当前资料核对冲突 | 原 update-mine 没有条件版本写，读到提交之间仍有并发窗口，代码明确 shortcut | JS 通过 | 未验，未称 CAS 完成 | 未验 | 需要将原写接口扩展为条件版本写的单独合同，不是本机恢复未做 |
| X026 | 已过审锁定字段变更被拒时复制新本机草稿，原主题/订单不改；本机保存失败不导航 | 原编辑详情不可读时无法凭空复制；新旧 ID/订单隔离需要真实保存回读 | JS 通过 | 未验 | 未验 | X025 前置未验；保留原编辑窗口政策 |
| X042 | 账号/场次/主题待同步队列，失败保留、重进/阶段写前重试；只清服务端 thoughts 回读确认的 key，部分确认保留剩余 | 真实弱网、进度写幂等与阶段事件独立回读待验 | JS 正反例通过 | 未验 | 未验 | 队列保存失败会提示勿退出；未新增奖励规则 |
| M0154 | 共享名称/值/必填/错误/选中/忙碌语义；叠层和退场隐藏下层，忙碌不提供假可点击关闭 | 任意 view 的读屏焦点转移/关闭恢复没有可验证的平台实现；秘密媒体授权同 F0263 | JS 行为与变异负控通过 | 不涉及 | 未验 | 仅读屏完成业务与真实焦点顺序必须实机，未造假的焦点 API |
| M0155 | 共享盒内触达和大字换行；play 接现有 font-scale 真源和 page-meta | 最大系统字号/小屏/键盘/底栏遮挡、其它业务页面仍需实测；原字号上限不擅改 | JS 通过 | 不涉及 | 未验 | 共享组件尺寸变化须 root 集成截图检查 |
| M0156 | 主键保存失败保留旧状态；已挂载组件即时广播、失败不广播、卸载退订；页面显式减动效优先；静态加载仍有文字 | 系统媒体查询、传感器/canvas/音频的设备组合未验；兼容键失败仍可能影响旧客户端 | JS 通过 | 不涉及 | 未验 | 没有将 CSS 或计时器桩记为真机省电/读屏通过 |
| F0385 | 先本机停所有持有者再留痕；撤回后重新同意；迟到启动保护；标记写盘失败本进程仍拒重启；清同意失败不启动；complete-only 不假报停止 | 重启后的真实存储故障、跨页/锁屏/杀进程/耗电待验 | JS 通过 | 同意留痕未真实回读 | 未验 | 保存失败明确要求勿重启并核对系统位置权限；M0107 未验 |
| F0386 | 清理前列恢复/未结算/待上报清单，未核对会话阻清；只清展示缓存保留恢复记录 | 原会话真实弱网回读与清理后再同步未验；原始轨迹云同步没新增 | JS 通过 | 未验 | 未验 | M0137 前置未验；不声称删本机轨迹能从云恢复 |
| M0112 | 区域点/POI 同源遮挡；有限线段含边界检查；抽稀后再过闸；只分享最长连续安全段，明确路线可能不完整 | 服务器拿不到区域坐标，声明不能证明客户端遵循区域；量化不保证不可反推，照片 EXIF 未接 | JS 正反例通过，Java 声明校验通过 | 快照 IT 通过，声明并不证明区域选择不可绕过 | 未验 | M0113 前置与真实产物回读未验；没有匿名保证 |
| M0117 | 可选版本条件更新/冲突；内容+版本单 SQL 读；自报回忆标识；接收页从后台回来重验，迟到旧回包不复活缓存 | 无自然 TTL、细受众和不可变版本历史；旧客户端无 expectedVersion 保留原行为 | JS + Java 通过 | 快照 IT 7/7 通过 | 未验 | TTL/细受众未知，不擅定；M0113 未验 |
| F0384 | member+token WHERE 逐条撤回，其余链接保留；单条弱网待办账号隔离、发布前补撤；丢回包后以匿名 gone 回读确认；全撤迟到只清原账号标记 | 撤回不是召回已导出图片；不新增逐版本撤回/受众编辑政策 | JS + Java 通过 | 快照 IT 7/7 通过 | 未验 | 真实旧链接/站内缓存失效未验；导出不能召回已明确说明 |
| N015 | 原敏感地点添加/移除和本机保存，沿用区域数/半径；未保存取消不改变设置；线段/POI 与最终快照同源遮挡 | 没有完整前后双地图预览、跨端区域同步或服务端不可绕过去敏校验；区域坐标仍仅本机 | JS 通过，Java 声明校验通过 | 快照存取 IT 通过，跨端区域仍缺实现 | 未验 | 原合同允许的部分已经实现；新增跨端隐私真源需独立合同，不扩大本轮 |
| F0460 | 官方活动任务列表按本人报名事实标注可参与与来源 | 实际资格变化、所有任务类型来源及履约闭环未验 | JS 通过 | 未验 | 未验 | M0053 前置未验；奖励和资格规则未改 |
| F0403 | 入口明确“官方活动任务”，沿真实本人加入事实与原详情入口 | 不另造通用个人 GPS 任务/路线节点任务系统 | JS 通过 | 未验 | 未验 | 真实活动失效后的显示更新待业务验收 |
| F0395 | GrowthMission/Progress 下发 verified_store / self_reported 来源，JSON 序列化保留 | 经验纠错、重算、来源去重的完整账务链未改变 | Java 通过 | 未验 | 未验 | M0053 与真实核销/自报来源独立回读待验，未增奖励 |

## App 源码核对（仅只读，不导入）

已核验现有 App 的 `lib/feature/prefab/prefab_life_page.dart`：`initState` 165 行使用存档 key；`_boot` 185 行读盘未捕获存储错误；`_commit` 247 行 unawaited 写入未提示失败。`prefab_story_engine.dart` 337 行 restore 对坏档/未知版本回初态，653 行 key 仅场次/主题/preview。已有 `test/feature/prefab/prefab_life_page_test.dart`、`prefab_story_engine_test.dart` 和 `test/feature/play/playkit_prefab_journey_test.dart`；涵盖预览续读、v1/v2、现场签到和终章权威回读，但本任务未执行它们。

单独 App 工作应优先沿现有 SecureStorage/provider 补账号隔离、读写失败收尾、保留坏档及用户确认重置；不在本公共后端/小程序任务里复制私有 App 源码。

## 本轮验证

- 最终 JS：34 个直接相关测试文件，**452 项，451 pass、0 fail、1 skip**。唯一 skip 是工作区外设计 vault 文档不可得，未放宽门禁；日志仅留任务临时目录 `/tmp/w2-creation-final-js.log`，不提交原始日志。
- Java：指定专属测试 VPS、统一 `flock` 串行执行，源码 tree `c4a43e4477e269e38f1e11c756dbca78440bfeaa`；归档 SHA256 `24c1886a044ca09149cb0d5ae83eae6212c3c78e9d78b5461f63b78ca8354ca5`，远端独立重读一致。`ChapterFlowWriteServiceTest` 42、`JourneyReviewTextsTest` 17、`ApiRoamShareControllerTest` 11、`GrowthMissionEvidenceSourceTest` 2，**72/72，0 failure/error/skip，BUILD SUCCESS，退出 0**。不启动后端应用，不连接真实业务 DB。
- MySQL：两套 IT 在已核验的专属 MySQL 8.0.45 / REPEATABLE-READ 上执行，沿原 localhost scratch 保护；`RoamShareSnapshotConcurrencyMySqlIT` 3、`ShareSnapshotRunSessionMybatisIntegrationTest` 4，**7/7，0 failure/error/skip，BUILD SUCCESS，退出 0**，日志仅留 `/tmp/w2-creation-mysql-it.log`。真实 Mapper 同版本并发只允许一个更新、单 SQL 内容版本读取、他人撤回拒绝、本人单撤、其余保留及旧编辑不得复活均通过，AfterAll 本人随机 schema 清理断言通过。这是隔离 fixture/DB 验证，不能当真实身份/业务验收。
- 公开补丁：gitleaks 已扫描审阅差分，发现一处测试令牌熵误报后改为等价低熵值，复扫零泄漏；没有加 ignore。该测试常量调整后 `ApiRoamShareControllerTest` 精确源码在 VPS **11/11、0 failure/error/skip、BUILD SUCCESS、退出 0**；此 11 项是复验，不重复加到 79 个不同 Java 用例总数。
- 最终 17 个变更后端文件逐一 SHA256 与 VPS 意图源码回读一致；文件清单 manifest SHA256 `c82d0fa58833684896d89bd1bbda80cb4dab69eed71af368055a27220aa8e344`。生产源码与首次测试快照相同，后续仅测试常量降熵，已复验。
- `git diff --check` 通过。未运行全量 test:unit、共享台账、UI baseline 或 CI；root 负责整合验收。
- 模拟器前后截图、真机、读屏、外部媒体、真实身份、迁移应用、部署/微信上传均未验，root 已明确统一安排。

## 集成插入点

`shared-a11y.js` 模块和正负控已交付。按 root 明确所有权，不改集成 gate 文件；root 在 `tests/lint/ui-integrity.js` 接线：

```js
const { scanSharedA11y } = require('./shared-a11y');
// runRepoScan 内，在其他 scan 后：
const a11y = scanSharedA11y();
// violations 数组的末尾加入：
...a11y,
// 与 U1-U5 输出一起加入：
console.log(`A11Y ${a11y.length ? 'FAIL' : 'PASS'}:共享组件语义、触达与静态替代契约`);
```

不改 baseline、页面计数、全局配置。fabu 及 play 应只融合上述方法和 WXML 小片段，保留其他任务的草稿、NPC、推荐和投影改动。

## 最小待决事项

1. 媒体：当前引用仍是现有上传链给出的 URL；若媒体必须私有，应明确上传回执字段和 PUBLIC/已获得/TEAM/主持秘密访问规则，再接受控资源读取，不能以客户端隐藏代替权限。
2. 教程：当前 CmsInfomation 仅标题/内容/排序，无发布状态、受众或玩法版本。需确定是否在既有后台管理器增加这些字段以及哪些人可读；本轮已完成现有异常与未知版本提示。
3. 模板反馈：目前没有反馈处理链。需确定处理责任人、作者查看范围与关闭通知契约；本轮不新增假工单入口。
4. 俱乐部资料：建议沿 `update-mine` 增可选条件版本，冲突要求重读；当前本机恢复和保存前核对已完成，但不能消除并发窗口。
5. 分享期限/细受众：原合同继续可更新 token、配额淘汰/本人撤回失效；在用户决定 TTL、受众前不增规则。区域跨端保存与服务端去敏校验也应单独确认隐私存储真源。

## 正常提交门禁

root 已明确批准仅本 Task 使用临时 hook 副本，把原 Java 编译执行位置改为专属 VPS 容器并使用同一 `flock`；不是放宽编译要求。原 endpoint/Linux 检查、暂存 JS/JSON 语法、禁止修改既有 migration/生产配置、DS 门禁和失败阻止提交均保留，没有改共享 hook/config，没有 `--no-verify`。

原 hook SHA256 `74a873bf05614e1fcd4acdc6462b2bbd84bb069010ed35c57707832d64e5aa5d`；临时 hook SHA256 `5925ac57cb8556015b8e0eaf24d22fc13fa985cbf9215132c323cd42610d2f6c`。精确差分留 `/tmp/w2-creation-hook.diff`，只将 `docker run` 新容器替换为 `git archive`→`docker cp`→既定容器内锁住解包和完整 `mvn compile`，额外核对批准容器 ID。

源码提交 `d97fc4aecf936fc08c90a4e831cf8cfb8d08cb67`，tree `edd689f7523e1884e736b2c880a2fb8a36078893`。正常 hook 对该 tree 的完整后端 compile、原 JS/JSON/保护/DS 门禁执行完成，提交退出 0，日志 `/tmp/w2-creation-commit.log`；没有取消或重发活跃请求。

452 项 JS 验证结束后前端源码未改，最终 97 个变更前端文件 manifest SHA256 `9e6e9e4b44c7e27b5c81ea6ee09d8c8408d846ecfc75f7d44cd7af467d2b1bff`；后端测试对应关系为 c4a43e4 的 72 单元 + 7 IT，同生产源码，最终唯一测试常量变更已 11/11 复验，17 后端文件逐一远端哈希一致。后续本报告补记提交只改文档。

root 已接管 shared-a11y 门禁接线，并已另派全新 App worker 处理两项真实差距。本 Task 没有 push/merge/deploy、付费模型、OSS 写入或生产访问；正常 UI 和外部业务编号验收仍按上表保留，不宣称 all done。
