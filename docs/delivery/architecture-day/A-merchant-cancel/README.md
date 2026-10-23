# A-WP12 后续：商家报名取消/中标的原单互斥

结论：修复一组可达竞争漏口。商家取消原先把预读的完整旧记录按 ID 写回，可覆盖刚提交的中标、归属或其他资料；原中标 CAS 又不检查删除态，可选中已经取消的待审行。本候选只将取消改为当前 owner+未删除+未中标的单条 UPDATE，并给中标 CAS 补未删除条件。不改变既有权限、合作邀约条款、支付/退款或结算。

**代码与回归已写；Java编译/单元/MySQL测试均未在本会话执行。** 实际通过的是轻量 Python 源码/XML/保护谓词负控、修改范围、秘密文件及 diff 检查，不能当作真实 MySQL 并发或 HTTP 权限验收。根统一登记/执行 CI，本批没有启动 CI。

## 基线与独立范围

- 仓库仅 `https://github.com/liboyang42-cpu/hengyin`。
- 开工 HEAD/直接 parent：`dee20f264de16d7b155c414cd5ebcfffff82908d`；源自公共 `2ec68899099a1e250df0949a8383bf85b5d3ffe0` 加前一批通用 PUT 生命周期保护。
- 独立目录 `/workspace/hengyin-a-merchant-cancel`，分支 `codex/architecture-day-a-merchant-cancel`。不包含 A 提现/P2/fixture 或 F parser 变更，不重写冻结候选。
- 沿用用户批准的隔离 CI 候选 commit/push 例外；Java未验证，不正式合并/集成/部署。

为避免混淆，冻结 `dee20` 是**源码修复+报告**：代码只改 `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java` 和 `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/CmsRegistrationControllerSafetyTest.java`；另6个文件为 A-WP12 报告/索引/静态复核/输出/哈希清单。实际漏口是通用 PUT 不剥离 registrationStatus、可绕过支付/取消命令改生命周期。新增1测试、扩展1测试，安全测试类10项，实际 Java执行0项；38套件/76 SQL语句是核查索引数量，不是通过数。未覆盖真实 HTTP AOP鉴权、数据库状态变化/并发及端到端资金向量；过期拒绝释放、NOT_FOUND合同、退款历史空号/CLOSED、后台其他字段更正规则仍列未决。

## 真实调用链与失败机制

| 入口/调用者 | owner/scope与本批边界 |
| --- | --- |
| `POST /api/registration/merchant/cancel` → `CmsRegistrationMerchantServiceImpl.cancelByMerchant` | 当前登录商家/有效操作员经既有 PROJECT_MANAGE gate 取得 ownerMemberId；Service先读时查本人并拒绝 audit1，再由新SQL在写入瞬间复查归属/删除态/审核态 |
| 后台 `PUT /business/registrationmerchant/select/{id}` → `DispatchServiceImpl.confirmRegistration` | 既有 edit权限及topic scope；原锁商家/topic、竞争位唯一键、扫落选逻辑不变；最终中标CAS新增 del_flag=0 |
| `ApiCoopController` 确认候选 → 同一 dispatch；`confirmNodeMerchant`/邀请补录 → `confirmRegistration` | 既有主题发布者/邀请scope不变；新增中标条件覆盖所有实际 Service→Mapper 写调用者 |
| `confirmWinnerIfPending` | 生产搜索仅 Service透传并由 Dispatch调用；本批不改公共 Service API |

确定的可达顺序：

1. 取消读到 owner=A、audit0、del0；调配提交 audit1/status1。旧取消把旧对象整体传给按ID的通用 UPDATE，写回 audit0/status0/del2，既成中标被覆盖；若窗口中归属改成B，旧取消还可写回member=A并取消他人的当前记录。
2. 或取消先提交del2；调配即使已预读，原最终CAS只看audit0/null，会在del2行上置audit1并返回成功。该问题无需缺测试推断，由真实入口与SQL谓词直接确定。

当前 dispatch 已明确取消“中标即复制活动/票”的旧行为，本报告**不把遗留C5注释当作今后必然产生孤儿活动的证据**；已证明的是报名记录的审核/归属/取消事实可被覆盖和已取消行被错误选中。

## 最小修复合同与文件

| 精确文件 | 修改 |
| --- | --- |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationMerchantServiceImpl.java` | 仅 `cancelByMerchant` 的写侧改为专用CAS，保留原不存在/异主/已中标拒绝信息，不修改预读对象 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMerchantMapper.java` | 新增内部 `cancelUnwonByMember(id,memberId,username,updateTime)`，confirm注释补未删除；不改公共服务接口 |
| `chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMerchantMapper.xml` | 取消仅SET del_flag=2、update_by/time，WHERE id+member_id+del_flag0+audit∈null/0/2；原中标CAS补del_flag0；其余Mapper语句原样 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MerchantCancelAuditGateTest.java` | 新增2项，原允许取消的3项加强为assertTrue；既有已中标/异主拒绝保留，总7项 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/MerchantRegistrationCancellationMySqlIT.java` | 新增8项真实MySQL fixture回归，复用原MyBatis XML、P0AcceptanceSchema/base快照与原audit_time ALTER，无新schema文件/依赖/框架 |

两条 UPDATE 在同一原单行锁上串行：取消先成功则中标0行；中标先成功则取消0行或预读拒绝；归属变化使取消0行；驳回仍是既有可取消态，但取消不再回写旧审核结果/资料。SQL0行不写audit/业务/余额/权益/外部动作；成功取消也只有原软删除与操作者/时间变化。重复取消返回false且行不变。只接受明确del_flag=0的活动记录，NULL删除态保持未知，不凭缺字段恢复资格；既有明确允许的null审核态保留。没有把通用Mapper能力删除，内部合法编辑/审核仍可使用其原接口。

## 测试与实际证据

新增/加强测试先写，再替换写侧。修改前轻量源码反例实际确认“旧取消通用全对象写”和“中标无删除条件”；未运行 Java红/绿，不能将此称数据库复现。

7项单元与8项新MySQL回归均 **NOT_RUN**。新MySQL类覆盖：取消先提交→中标拒绝且全行不变；中标先提交→取消拒绝且全行不变；取消预读后中标提交不被覆盖；预读后归属改变不被旧owner改回；并发驳回/资料更新被取消保留；同原单两线程取消/中标只一方胜出；null/0/2审核态合法首次取消确实成功、重放不变；已删除/未知删除态两条命令均零变化。

时间窗测试使用spy在真实读后提交对端真实SQL，返回旧对象再调用Service写侧，确定性复现读写窗口；另有独立连接同号竞争。仅两条命令的MySQL行状态合同，不是完整Dispatch业务、HTTP鉴权、真实商家/主题竞争位或G结算验收。断言全行向量/归属/审核结果，不以Mock/H2替代MySQL。

MySQL类要求显式 `CHENGYIN_TEST_MYSQL_*` 六个受控绑定，随机新库，复用原DDL/原audit ALTER且只清理自己创建的库。原ALTER的COMMENT含分号，因此复用 `ItSchema.splitStatements` 处理引号内分号，不裸切SQL。仅编写fixture，未执行任何DDL或申请凭据。建议根后续登记此新IT，并复用现有 `MerchantRegistrationEditGateTest`、`DispatchServiceImplTest`、`CmsRegistrationMerchantControllerSelectTest`、`RegistrationMerchantAuditTimeContractTest` 检查调用合同；本批不改 registry/CI。

实际静态命令/输出见 `verification.txt`，可复跑本目录 `verify-static.py`；源码/XML检查不证明MySQL锁/回滚。无新迁移、无生产数据变更；历史已被覆盖的审核事实不能靠此补丁自动推回，须从原证据调查。代码可独立revert，但会重开窗口；已有取消/中标记录不能清空或伪造历史回执。B Checkout/网关、G结算、RBAC、根pom/CI/共享schema均未修改。
