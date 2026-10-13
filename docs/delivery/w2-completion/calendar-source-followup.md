# F0064 月历实际场次数据源 follow-up

日期：2026-10-13。接受的公共候选：`efba970132d25c3285071e6e1ec909e013b83804`。

已实现按所选月完整分页读取真实 `cms_activity` 场次，俱乐部月历不再拿 `/api/club/topics` 的主题日期代替场次。正常 UI、合法真实身份与真机业务验收仍未完成；源码及隔离 MySQL 夹具通过不能代替这些证据。

## 字段与现有时间语义

- 旧月历把主题 `startDate` 的日期前缀归到日格；主题不是实际承办场次。
- 原 `/api/club/open-session` 的 `ClubSessionServiceImpl.open` 把按 Asia/Shanghai 解析的集合时刻写入 `CmsActivity.startDate`，未改原开场服务或时间策略。
- 场次事实为 `CmsActivity.startDate/endDate`、数据库 `cms_activity.start_date/end_date`（DATETIME）。`CmsActivity` 与现有公开 VO 格式是 `yyyy-MM-dd HH:mm:ss`；`ApplicationConfig` Jackson 时区为 Asia/Shanghai，`application-druid.yml` JDBC 时区为 GMT+8。沿既有中国时间字符串语义，不接受客户端改时区。
- 月份归属沿旧月历的开始日期口径：`start_date >= 月初 00:00:00 AND start_date < 下月月初 00:00:00`。跨月长场次只归开始月，不额外铺每一天；已结束场次仍是日期事实，不称可报名或可用。
- `start_date IS NULL` 没有可安全归月的字段，明确排除；页面说明只统计有明确开始日期的已发布可见场次。没有猜日期、补周期场次或修改发布表单。
- 初始所选月改为复用 `chinaDateKey`，设备时区不改变默认北京时间月份；日格仍按公布日期文本生成，不把裸日期字符串解析为 UTC 瞬时。

## 接口与消费者合同

新增 GET `/api/club/calendar`；独立 `ApiClubCalendarController`，没有编辑共享 `ApiActivityController`。

| 请求/响应 | 语义 |
|---|---|
| `clubId`, `month` | 正俱乐部 ID；严格 YYYY-MM，技术范围 1970–9998，保证下月边界仍在 MySQL 日期范围内 |
| `afterId`, `throughId` | 首页省略；续页两者均正、after<=through，并核实两端仍属于当前月份、俱乐部及可见范围 |
| `rows` | 复用 `PublicActivityListVO` 白名单，实际只查询 id/memberId/topicId/name/startDate/endDate |
| `throughId`, `nextAfterId`, `hasMore` | 首页可见最大 ID 作追加上界；ID 升序，读取 101 行、返回至多 100 行，游标取最后返回 ID |
| `clubId`, `month`, `timeZone` | 回显筛选与 Asia/Shanghai，消费者拒绝不一致响应 |
| `visibilityScope` | CLUB_MEMBER / ACTIVITY_OWNER / ANONYMOUS，复用现有 active membership 查询，禁止把非成员空集合称为俱乐部空月 |
| `readAt` | 北京时间服务端读取时间字符串；消费者展示最后一页读回时间，可重读 |

公开 status=1、publish_status=1、del_flag=0、cancel_time=NULL、父主题存活、活动所属俱乐部存活及父主题俱乐部存活条件，从 `CmsActivityMapper` 原查询原样抽到 `publicActivityVisibility` 片段供两者复用；只将片段引用写全 namespace，原条件和原调用方参数不变。新场次查询沿 `activityInfo` 原边界：本人活动创建者或当前有效俱乐部成员；不赋予主理人额外权限，不把公开俱乐部当作游客读取场次详情的授权。

月历状态独立于主题列表；进月历、切月、下拉刷新及回到月历页时读取场次。全页成功才提交整月结果，空与错误分开；校验严格游标、ID 顺序、重叠/重复、日期、上界、回显范围与可见范围。中途失败或游标失效提示重新读取，不把部分月份冒充完整月份。快速切月、俱乐部变更、当前账号变更及 unload 隔离旧响应。场次直接导航既有 `/pages/activity/detail/index?id=...`，保留原主题列表导航与管理行为。

## 验证与版本

- 后端测试源码 tree：`655851e3c5d82ae8a5f321557a497fa42d8853a0`；后端压缩包 SHA-256：`e552bb83355c5929600e4a9d2c31054c3f20a97a786d258d5c0e5bb50c43c4f8`，传到专属 VPS runner 后逐字验证。
- Node：6 文件 **41/41**，0 failures/cancelled/skipped。`calendar-month-source`、`club-event-calendar`、`w2-coop-calendar`、`nav-polish-single-title-contract`、`coop-accepted-card-actions-contract`、`coop-invited-partner-project-view`；含超过一页、闰月、空/失败、中途失败、重复/重叠页、身份范围变化、旧响应隔离及原主题列表行为。
- 严格 UI：`node tests/lint/ui-integrity.js --warn-as-error` **UI-GATE-0:PASS**；scope：558 个生产 JS，未解析标识符/不存在导出均 0。初轮唯一 U1 为消费者转发变量请求对象，改成将 `app.sendRequest.bind(app)` 传给持有字面量 URL 的 helper 后通过；没有 waiver 或 baseline 改动。随后 frontend 41/41 复验；这两处回调/缩进收尾不改变后端测试源码。
- 首轮 VPS 正常 Maven compile/testCompile + **5/5**，0 failures/errors/skipped；实际 `ClubCalendarMyBatisIT` 4 与原 `PublicActivityScopeGateMyBatisTest` 1。最终加入 controller 身份与失败测试、跨年/已结束场次及明确 readAt 字符串后，**8/8**，0 failures/errors/skipped，退出 0、BUILD SUCCESS；全部 backend 正常编译并 testCompile 1289 份测试源码。
- VPS 唯一授权 runner：`cy-w2-followup-20261013-test`，精确 ID 已与协调者给定值核对；源码在 `/w2/calendar`，日志 `/w2/calendar/java-final.log`。专属测试 schema 使用 calendar 后缀；只建删本任务测试库，不连生产，不修改 DB 配置。
- Java 命令使用 `docker --context chengyin-vps-sandbox exec --env-file /tmp/w2-followup-it.env -e MAVEN_OPTS=-Xmx448m ... flock /tmp/w2-java.lock`，正常 `mvn -B -pl chengyinhub-admin -am test -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx320m -Dtest=ClubCalendarMyBatisIT,PublicActivityScopeGateMyBatisTest,ApiClubCalendarControllerTest`。环境文件由本机 Docker CLI 注入，不打印或提交凭据。
- 首个远端命令在 Maven 前因环境文件不在 runner 而退出；已采用本机 `--env-file`。曾复制的唯一临时文件立即移除，后续不在 runner 存凭据。没有将这次前置失败称为应用测试失败或通过。

## 剩余边界及接管

1. ID 上界只是追加边界，**不是跨页不可变版本**。两端游标发生日期/可见性变化会拒绝续页；中间行修改、删除或搬月仍可能改变跨页结果。页面显示读取时间、支持刷新，不宣称固定版本或原子快照。
2. 没有明确开始日期的实际场次仍不能安全归月；本轮不编造 recurring、availability、个人推荐或新的时区业务政策。暂停的 22 项保持缺席。
3. 协调者需在同版开发后端与合法真实身份下补正常 UI 截图、进月历→切月→刷新→真实场次详情、成员/非成员与创建者差异的业务 readback。本 worker 未占用共享 DevTools 窗口，没有上传小程序或启动应用后端。
4. 没有推送、合并、部署、生产操作、付费服务、新依赖、auth/RBAC 政策改动；没有改共享台账、UI baseline、root README、全局 fabu/publish 或其他 worker 文件。提交使用获批任务 hook `/tmp/w2-followup-hooks-calendar`，不绕过门禁；源码测试与提交编译是不同证据。
