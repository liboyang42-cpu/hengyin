# E / H-QB453-01：主题草稿 typed UNKNOWN 最小修复

结论：已完成源码与新增回归候选；本候选未运行 Java。仅将专用 `AiProviderOutcomeUnknownException` 从误报 FAILED 改为保存并返回 UNKNOWN。24 小时 Redis 失效后的请求身份耐久性没有在本补丁中解决，另交 02 设计提案，不能将本补丁称为完整幂等验收。

## 基线与范围

- 公共仓库：`liboyang42-cpu/hengyin`，实际 parent `26f3764ce0d81594be02c0eeace04aacdd483e58`，基线 tree `0ef0b436292cb36a81088285a69a4da0dab9a97b`。不套用最初 2ec 的行号。
- 独立工作目录 `/workspace/hengyin-e-theme-unknown`；分支 `codex/architecture-day-e-theme-unknown-01`。既有 E/F、IM、Photo、缓存和调度候选保持冻结。
- 依据 H `60fc464b01fcb4cca25a5f0b0fd932444bc113df` 的 `docs/delivery/architecture-day/H/theme-quota-b453/REVIEW.md` 和 `request-unknown.review.patch`，复用第一条负例；第二条 TTL 负例仍是 02 未闭合风险，没有修改成绿色假验收。
- 唯一生产修改：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiThemeDraftRequestService.java`。
- 测试：原有 `AiThemeDraftRequestServiceTest.java`、`ApiAiThemeDraftRequestTest.java` 末尾追加用例；既有 7 + 5 条测试正文不改。本报告为第四个候选文件。
- Controller、Page、ApiAi 协议、Creator、Gateway、Vision、F transport 均未修改；没有 schema、依赖、配置和公共状态编码变化。

## 调用链与失败机制

有 rid 的入口为 `ApiAiController.themeDraft` → `themeDraftWithRequestId` → request service `submit` → `IAiCreatorService.themeDraft`；GET `themeDraftRequest` → request service `read`。Controller 已有 UNKNOWN → `409 / AI_DRAFT_OUTCOME_UNKNOWN / 原 requestId` 分支，Page 已支持保留原 rid 并回读，因此不改调用协议。没有 rid 的旧 Controller 直调 Creator 分支仍存在，未扩大本次修复范围。

原服务用 Redis `member + lower-case rid` 占位；专用 provider UNKNOWN 是 `AiServiceException` 子类，被普通 catch 标成 FAILED，且尾部 JSON 再次硬写 FAILED。前端因此可把不确定的原请求当成已失败。

修复先捕获专用子类，设置 UNKNOWN；尾部持久化实际 `out.state`。不根据 cause、reason 枚举或异常文字识别 UNKNOWN。原普通 `AiServiceException`、输入阶段 `Reason.UNKNOWN`、RuntimeException、无效稿失败分类保持原样。没有改收费、退额、原 nonce 或模型执行代码。

原 read 对已有 UNKNOWN 记录走既有 UNKNOWN 回读路径；同键重放读原记录且不会再次调用 Creator。终态 Redis 写入失败仍遵循既有行为：本次可返回 UNKNOWN，回读可先看到原 PROCESSING，租约后 UNKNOWN；这不是数据库持久性保证。Redis TTL 到期或丢失仍能释放业务身份，是 02 的阻塞事项。

## 新增回归与证据边界

Service 新增 4 条：

1. H 原负例：typed provider UNKNOWN 在提交、JSON 存储、read、同 rid replay 均 UNKNOWN，保留原 rid，无结果，Creator 仅一次。
2. 输入阶段普通 `Reason.UNKNOWN` 仍 FAILED，read/replay 保持原分类。
3. 包装专用异常的普通 AiServiceException / RuntimeException，以及相似文字均 FAILED；不遍历 cause。
4. 无效稿故障仍 FAILED，同 rid 不再次生成。

Controller 新增 1 条：真实 request service 接真实 Controller，Redis/Creator 为单测边界 double；两次 POST 都是既有 `409 / AI_DRAFT_OUTCOME_UNKNOWN / 原 rid`，GET `200` 返回 UNKNOWN 与原 rid，Creator 一次。不是 HTTP/JWT/真 Redis 测试。

共 17 个 Java `@Test`（Service 11、Controller 6），新增 5 条尚未执行。交 root 点名运行这两个旧测试类；不新增 skip 或修改原用例期望。

本机实际检查：`git diff --check` 通过；轻量 Python 括号/测试数/精确 catch 顺序检查通过；未改生产 Controller、Creator、Gateway、Vision 的 blob 对照通过。`python ci/secret-file-gate.py` 与有限补丁敏感信息扫描结果见候选发布回执；未安装 gitleaks，不把有限扫描冒充固定版本 gitleaks，需 root 对最终 commit 复扫。

另实际运行过既有 `node --test chengyinhub-xcx/tests/unit/simple-ai-request-state.test.js`：Node v24.19.0 输出 tests=1、pass=1、fail=0、skip=0，为文件级结果，未据此声称内部六条逐例执行，更不是本次 Java/HTTP 证据。

未运行：Java 编译、JUnit、新增 5 条、原 12 条 Java、MySQL、Redis、HTTP、CI、真实模型。用户已明确允许隔离候选未验提交/推送，供 root 统一 CI；不据此合并或部署。

## 迁移、回退与余项

本修复无需 DDL 或数据迁移；继续使用原 Redis 键和既有 UNKNOWN 状态。旧进程本来能读 UNKNOWN；若回退源码，新的 provider 不确定故障会再次误报 FAILED，因此不得把回退视为安全关闭该缺陷。

02 仍需持久 member/rid/payload 身份与原配额 nonce/receipt 绑定、发送前独立 claim、崩溃与 TTL 恢复、历史 rid 切换方案，均未在 01 实现。root 已回读 a0f / run40043910446 的 E/F 真 MySQL+Redis 四例 PASS/CLEAN，该证据属于冻结经济接线候选，不能覆盖本补丁、HTTP、全 NPC 或原 rid 耐久性。

候选最终 commit/tree/blob 身份在发布后通过 GitHub commit/ref/compare 核对，单独回报，不把 API commit 与本地时间戳不同的 commit 伪称相同身份。
