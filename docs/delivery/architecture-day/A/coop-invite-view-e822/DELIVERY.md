# 邀约列表三态回归夹具

源码已补齐，所有测试、parse/import 控制、编译、Java/DB/HTTP/微信与 CI **NOT_RUN**。

- parent：`e82229ef85e58159078a42956560356074192541`。
- 分支：`codex/architecture-day-a-coop-invite-view-e822`。
- 唯一 Java 文件：`chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiCoopControllerInviteAuthzTest.java`。
- 原文件 Git blob：`ed44aabfade77f9a22851f903e1fae7c77dbd766`。
- 原失败由 root 提供：`run40228119362/job121273974170/artifact12612221509` 中 `listPreservesSupplyStationUnknownConfirmedAndNotApplicableStates`，expected TRUE / actual null。本次未下载或重新解析日志。

## 已证实的源码机制

`ApiCoopController.list` 不克隆邀约行，但在进入 `fillTopicFacts` 前通过 `isInviteVisible` 过滤：topic 查询为 null 的行不可见；club 行还需其原俱乐部存在。

原回归夹具给三行同一个 topicId、没有独立 invite ID，并把唯一 topic 查询设为 null。所以这些行均被过滤，原断言检查的是没有进入输出的输入对象，未量测返回卡片。该条件不证明生产分类错误。

当前原分类保留不动：`fillTopicFacts` 对 type0/merchant/无 node 的候选先按原 `supplyStationId` 投影 null/TRUE，再由已知 topic.productType 投影是否经典定向；其他 club/node 为 FALSE。`CoopInviteServiceImpl.selectCoopInviteListReadOnly` 经真实 `attachSupplyStationNames`，仅用 `(stationId,topicId,toId)` 精确匹配原站名称。

## 本次唯一源码修补

- 原测试方法名及其 TRUE/FALSE/null、原站 601 断言意义保留，断言目标改为 `result.data.sent/received` 的实际返回对象，按独立 invite ID 读回。
- 每侧五条事实：可见但 productType 缺失的历史未知、经典主题原站已确认、经典主题尚未绑定站点、club 邀约、只读 legacy node 邀约。总计十个独立 invite/topic 身份，**仍是一条 `@Test`，不是新增十条执行声明**。
- 使用原 actor/商家 COOP_MANAGE context、各主题真实 publisher、存在的当前/受邀俱乐部、商家档案和原站 topic/member/audit=1/del=0 元数据；未知历史不是用已删除 topic 冒充。
- 本用例装配真实 `CoopInviteServiceImpl` 只读 owner，不 mock 它的补全算法。仅 Mapper double 按本用例使用的 from/to/topic/status 条件返回 detached 原行；原状态均为 accepted=1，本夹具不重写 SQL 过期算法。
- 真实站名补全读取 Mapper double 返回的精确原元组；原 SELECT 的 ID/topic/member 语义沿现有 XML，不按当前池选新站，不创建 V1 或经济事实。
- 经典未绑定仍为 TRUE、stationId/name 仍 null；历史未知仍为 null；club/node 为 FALSE 且不自动补站；已确认保持原 601/602 及其精确原名称。
- 新增断言源码核对视图与原行不是同一对象、原身份/角色字段不变、卡面属性未回写原行，并拒绝 insert/update/expire 与通知交互。

## 保留与交付边界

原类 **24 个 `@Test` 声明全部保留**；除上述原失败方法及必要 imports，其余原方法正文不变。原断言期望没有改宽，原 held-deposit 正例也不改。

Controller、Domain、Service、Mapper/XML、RBAC、Boolean 对象分支、站点绑定政策、金额、恢复开关及共享源均未修改。

这仍是原 controller 方法直调的 HTTP view unit：登录 actor 沿原 spy，未声称执行 Security filter/真实 HTTP 或 SQL。Mapper doubles 的 detached 读回模拟原数据库行/DTO 边界，不把内存断言冒充数据库零写证据。未来集中验证由 root 安排；本次只做源编辑、git diff/空白检查、后像摘要与秘密扫描。
