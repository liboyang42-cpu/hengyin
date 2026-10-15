# E02 隐私数据库读边界回归交付

CY-HANDOFF: CODEX / E02

当前状态：`SOURCE_DELIVERED_UNVERIFIED`。用户最新要求全部暂停验证；下文命令仅供用户下次开电脑后由本地验证方重新授权统一执行，现在不运行、请求运行或等待任何 Node/Java/Maven/数据库/DevTools/业务验证，也不手动重跑 CI。既有历史结果保留，不迁移为新提交的验收。
- 公共基线：`1e403b135206844a34219753a36e2dee39c7dd59`。
- 工作分支：`codex/extra-e02-regression-20261015`；控制快照 `b8541ad2407d0a360588ab77ad32fe3d1c9eb71e`，协议 `CY-COORD-20261015-v1`。
- NO_API_CHANGE；TEST_DEPLOYED=NO。仅新增测试及本文，没有修改业务、共享测试工具、依赖、迁移或权限政策。
- Java/编译/Maven/MySQL/Redis/服务均未在 Cloud 运行；本次没有真实数据库通过证据。冻结 231 项结果不适用于本新增套件。没有提交、推送或部署。

## 已有覆盖与新增差异

`CommunityPostMapperMyBatisTest` 已有 `b05DeletedPostOldLinkIsUnavailableToAuthorAndOtherViewer` 及退出社区旧详情回归，使用 H2 MySQL 模式，覆盖 fresh Mapper selectById、已删作者/非作者不可见、未删作者 DRAFT/CHECKING/LIMITED/HIDDEN/REMOVED 合法读和社区边界。

本次不复制其 feed/互动/通知等套件。新增 `B05CommunityVisibilityMySqlIT` 用真实 MySQL、生产 CommunityPostMapper XML、真实 CommunityPostServiceImpl.detail/revisions 与生命周期策略；独立 JdbcTemplate 连接读取提交后的数据库事实。SqlSessionTemplate 每个非事务调用重新获取会话，没有持有首读的一级缓存。

API 链审计：`ApiCommunityPostController` GET `/api/v1/community/posts/{id}` 调用 service.detail，GET `/api/v1/community/posts/{id}/revisions` 调用 service.revisions；后者先经过同一 selectById 可见性读，再执行作者校验及 selectRevisions。此处只验 Service/Mapper，不声称验证 controller 登录/feature gate/HTTP 结果。

专属 scratch 最小 DDL 复用旧 Mapper 测试字段，仅添加读链所需 revision/media/reference/mention 表。没有执行生产 migration 或验证完整迁移约束。fixture 作者 11、非作者 8、帖文 1、社区 55 均为 synthetic。写侧协作者为 null 且不调用写服务，不以 mock 当真库；删除事实由真实 Mapper.transitionCas 形成，退群事实由 JDBC 更新形成，**不声称验过删除/退群写入口**。

## 方法与预期

预期业务方法 **3**，成功验收必须 Surefire tests=3 / failures=0 / errors=0 / skipped=0；此处是预期，不是实跑结果。

| 方法 | 验证 |
|---|---|
| `deletedOldDetailAndRevisionReadsRejectBothIdentities` | 删除前双身份详情、作者历史可读，非作者历史403；真实CAS删除后两身份详情/历史404；独立JDBC确认DELETED/version及旧正文/历史仍在，排除空夹具假通过 |
| `legitimateAuthorDraftKeepsDetailAndHistoryWhileOtherViewerIsDenied` | PRIVATE草稿作者详情与历史仍可用，历史不返回immutable snapshot正文；非作者详情与历史404，JDBC确认状态不漂移 |
| `leavingCommunityClosesOldDetailWithoutRemovingAuthorWorkspace` | ACTIVE成员旧详情先成功；status=0与del_flag=1两个独立失效向量重读均404；作者保留既定详情/历史权限，JDBC确认会员状态及COMMUNITY受众未被改公开 |

ServiceException.code 为现有业务码，不能推定为 HTTP 状态。没有发现经运行确认的新缺陷；一旦验证失败保留失败断言并交C00/原作者修复。

## 获准本地验证方执行（Cloud 禁止执行）

仅由已获准本地验证协调者，在隔离 MySQL 8.0.45（至少 MySQL8，拒绝 MariaDB）、既有 Maven 3.9.11/Java21 串行单容器环境运行。使用现有依赖缓存与 mysql-connector；budget212 离线无网络入口不具备真实数据库前提，不使用旧驱动补跑。无需 Redis 或应用服务。

先由验证方在获准环境显式提供下列变量，不把凭据写入仓库或日志：

```bash
export CHENGYIN_B05_MYSQL_IT=1
# 以下均由获准环境注入；HOST/PORT必须明确指向专属测试实例。
: "${CHENGYIN_TEST_MYSQL_HOST:?}"
: "${CHENGYIN_TEST_MYSQL_PORT:?}"
: "${CHENGYIN_TEST_MYSQL_DATABASE_BASE:?}"
: "${CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX:?}"
: "${CHENGYIN_TEST_MYSQL_USER:?}"
# PASSWORD变量必须已定义，允许获准隔离实例使用空密码；不要打印其值。
[ "${CHENGYIN_TEST_MYSQL_PASSWORD+x}" = x ] || exit 1
mvn -B -pl chengyinhub-admin -am \
  -Dtest=B05CommunityVisibilityMySqlIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

命令从候选仓库根目录执行；交付提交由C00统一形成后，验证方应记录实际完整SHA及无源码补丁事实。显式点名新 IT，不能依赖默认Surefire发现 `*IT`。

schema 精确计算为 `${CHENGYIN_TEST_MYSQL_DATABASE_BASE}_b05_visibility_it${CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX}`；suffix必须本轮独占且非空。账号需具有该临时schema的CREATE/DROP及DML权限。测试CREATE DATABASE不带IF NOT EXISTS，不预删同名库；碰撞直接失败，不占有/删除旧库。只有本测试创建成功的schema才在AfterAll删除；外部中断时由验证方按实际记录核对后清理。

未设置 opt-in 时 JUnit 条件禁用不构成验收；启用后任何缺变量、版本不符、连接/DDL/映射/业务断言失败均硬失败，无 assumption/捕获后skip。必须核验Surefire新鲜XML和三个方法名、退出码及清理结果。

## 未验证边界

Java编译与三项数据库运行尚未进行；HTTP登录/真实账号、写服务删除/退群事务、媒体旧URL/OSS隔离及撤销、Redis/服务部署、微信/iOS设备均未验证。本套件不声称媒体URL已经隔离，也不改变RBAC/隐私/留存政策。
