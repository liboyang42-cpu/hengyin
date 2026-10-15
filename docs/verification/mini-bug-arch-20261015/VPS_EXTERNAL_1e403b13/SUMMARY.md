# 本轮 VPS 自动化复测总览

CY-HANDOFF: CODEX；协议 `CY-COORD-20261015-v1`。用户授权本地验证方与 Cloud 直接交接：本地负责专用 VPS 测试和独立证据；Cloud C00/C99 负责修复、候选与控制账。

**冻结候选 `1e403b135206844a34219753a36e2dee39c7dd59` 的本轮最小自动化矩阵：231/231 实跑通过，0 失败、0 错误、0 跳过。** 公共源码候选为 [PR #18](https://github.com/liboyang42-cpu/hengyin/pull/18)；矩阵对应控制提交 `ab377921bbcb0277f3df3563040cd93f114fa40d` 的 [VPS_TEST_MATRIX_1e403b13.md](https://github.com/liboyang42-cpu/hengyin/blob/ab377921bbcb0277f3df3563040cd93f114fa40d/docs/plans/mini-codex-cloud-20261015/VPS_TEST_MATRIX_1e403b13.md)。不扩张为旧套件全部执行或整产品业务验收。

| 分组 | 期望数 | 实跑通过 | 失败 / 错误 / 跳过 | 独立证据 |
|---|---:|---:|---|---|
| B03 积分与应付真实 MySQL 集成 | 7 | 7 | 0 / 0 / 0 | [evidence.json](evidence.json)、[B03 报告](README.md) |
| 12 类普通后端回归 | 202 | 202 | 0 / 0 / 0 | [regression.json](regression.json) |
| AI 真实 Redis 预占与 MySQL/Redis 恢复 | 22 | 22 | 0 / 0 / 0 | [ai-storage.json](ai-storage.json) |
| 合计 | 231 | 231 | 0 / 0 / 0 | 同一源码提交，无测试工作副本补丁 |

第二组逐类执行数为 5、4、6、14、79、2、50、21、2、5、3、11；第三组分别为 15 和 7。所有选定 XML 的类名及执行数均逐一匹配，且失败、错误、跳过均为零。每组容器退出码为 0、无 OOM。

## 发现、反馈与复测闭环

1. 初始 `69c281af` 在 VPS 编译成功，但新增 B03 套件在 setup 建表失败，7 项业务方法未执行。[失败报告](../VPS_EXTERNAL_69c281af/README.md)及原 JSON 保留。
2. 本地把根因直接反馈 Cloud：测试以首个分号截断建表 SQL，误截 COMMENT 中的分号。
3. Cloud 安排原写者最小修复，仅改测试夹具，保留 7 项业务方法及其断言，交付公共完整 SHA `1e403b13…`。
4. 本地校验新源码归档，B03 7 项通过后，按 Cloud 清单串行运行其余 202 与 22 项，均通过。逐轮结果已直接反馈 Cloud；控制表仍由 Cloud 唯一维护。

## 实际环境与报告可信范围

- 全部 Java 测试和 MySQL/Redis 服务在授权开发测试 VPS；Mac 只负责传输、控制、读取证据。无 Cloud Java/MySQL 替代或生产连接。
- 源码归档 SHA-256：`1362acc7a4b42b7e3b5972838903d0f66b8da9745ead1292d19791635760b2dd`；Maven 3.9.11、Eclipse Temurin Java 21、MySQL 8.0.45、实际 Redis 7.4.11。
- B03 使用独占 MySQL 内部容器；AI JVM 和 Redis 共享本轮 MySQL 的网络命名空间，127.0.0.1 仅指该独占环境。普通回归使用无外部网络的容器。全部没有宿主发布端口。
- 普通回归包含 Mockito 和 H2，不能作为对应业务已在真实 MySQL 运行的证据。AI 组验证真实 Lua、Mapper、独立事务和存储读回，未调用真实收费 provider。
- 实际 Surefire 报告仍写入 `chengyinhub-admin/target/surefire-reports`；命令中的 reportsDirectory 覆盖未生效。验证方以每组精确类名筛选，并核 XML 修改时间晚于该组容器启动，按组单独保存 JSON 与日志，没有把旧 XML 混计或覆盖此前独立证据。
- 原始 Maven 日志、Surefire XML、源码和任务专属依赖缓存保留在 VPS 本轮任务目录；公共证据仅含脱敏观测，不含环境秘密。

## 资源与清理

Maven 每组硬限额 1152 MiB / 1.5 CPU，堆 640 MiB、测试进程堆 384 MiB；MySQL 384 MiB / 0.5 CPU；Redis 64 MiB / 0.5 CPU。最多一个 Maven 测试容器运行，三组不重叠。硬限额观测见 [resources.json](resources.json)，运行区间及清理见 [cleanup.json](cleanup.json)。未修改其他任务容器、缓存、数据卷或网络；未执行全局 prune。

## 仍未验收

231 项只签收本轮冻结候选的自动化矩阵。以下不因矩阵通过而关闭：

- 候选应用部署、运行包 SHA 与数据库绑定，以及 B02 真实保存重开/发布、B04 排期候补 SQL 和并发、B05 两身份/旧媒体、A01 跨设备和丢响应等运行时业务读回。
- 微信真实审核、provider usage、B01 换号/退款订阅、开发者工具和真机；独立 iOS 适配及设备。
- 合并、业务数据库迁移、测试或生产部署、小程序上传。
- 既有能力限制与 DRAFT 决策：首次 create 无持久 requestId、历史缺正文不可追溯、NPC UNKNOWN 无法找回上游答案、删除屏障/留存、ASR/TTS 额度政策。Claude 暂缓或未集成的新功能不在此 SHA 验证范围内。

自动化矩阵通过不等于 `TEST_DEPLOYED` 或业务 READY；剩余事项由唯一控制账逐项保留。
