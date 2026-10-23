# A：报名通用编辑与商家批量删除修复候选

结论：**已实现 H-A136-01/02 的最小源码修复及 12 个新增回归用例源码；Java 编译、Java/MySQL 测试未运行，未验收。** 没有改资金 fence、D 通知、B 网关、G 金额/结算或权限策略。候选供 root 统一受控 CI，不能用此前取消 8 项 PASS 代签本候选。

## 基线、隔离与审查证据

- 仓库仅 `https://github.com/liboyang42-cpu/hengyin`。
- 实际起点/root 基线 `6788c741c43d9516aa7c691ca93110677c7e1acb`，参考公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。未套用旧行号或迁入私有历史。
- 独立工作树 `/workspace/hengyin-a-registration-write`，分支 `codex/architecture-day-a-registration-write`；创建时干净，其他 A 冻结工作树/分支未切换、未回退。
- 已读 H `7ceff5da883b08e4c12b9145476c27fb2dac9c3b:docs/delivery/architecture-day/H/a136-registration/REVIEW.md`。用户简称的 `review.patch` 实际文件名是 `economic-basis-and-admin-delete.review.patch`；已读取，先 `git apply --check`，再采纳其中两个负控。**没有在本 VM 执行 Java 红测，不把源码反例说成已跑红。**
- 依用户先前明确授权的隔离 CI 候选 commit/push 例外提交，供 root 编译和验证；不代表绕过正式集成、合并或部署门槛。

## 失败机制与修复合同

H-A136-01：后台 `PUT /business/registration` 原 helper 只清状态/金额一部分，仍把 `pointUsed/memberId/ticketId/orderNum` 等传给 ID-only 通用更新。原会员取消时实际按保存的 pointUsed 加积分、按 ticketId/orderNum 回库存，故编辑权限可改掉原返还依据。UI 有完整表单调用，不是不可达方法。

修复在真实后台边界构造新 patch，只复制 `id, activityTitle, ticketName, realName, phone, email, address, addressId`。沿用原 PII 权限剥离；没有 PII 权限的脱敏值仍不能回写。原单/父单/request/fingerprint、会员/票/数量、owner/topic/purchaseKind、原金额/积分/已返标记、费率/来源/退款窗口、支付/报名/核销/二维码及创建审计依据全部默认不复制；未来新增领域字段也默认不可经此入口改写。内部多票首单汇总、付款 CAS、取消/库存/积分算法及通用 Mapper 原能力保留，普通标签编辑仍可落库。

H-A136-02：Controller 普通预读 audit0/del0 后，Dispatch 可提交 audit1/status1，旧 Service 随后 ID-only DELETE 会删掉中标行。只包 Controller 事务或复用普通 RR 快照不能修复。

修复由 Service 的实际 REQUIRED 事务承担：校验输入、去重并按主键升序锁**全部**目标，专用 `selectForDeletionByIdForUpdate` 禁用二级缓存并刷新本地缓存；所有锁齐后，按当前行复验 topic scope 和原“已中标不得删”条件，再调用原删除 SQL。缺失、scope 不符、中标、受影响行数不等于去重目标数都抛冲突并整批回滚。单条删除也进入相同事务协议。Controller 保留原预检提示，只在 Service 异常越过事务代理并完成回滚后转换为错误响应。

授权仍用 Controller 原来的 `ITopicOperationsScopeAuthorizer.canViewTopic`，现在在锁内再检查相同规则；**未改 canView/canEdit 政策，未改授权器实现/接口，未新增角色或 grant**。当前实现仅固定超管全局 topic scope，不声称普通商家取得后台删除权。无需移交共享授权器接口。两个 Controller 的原权限/路由注解逐字不变（10+11 条权限注解），证据见 `source-checks.json`。

删除持锁顺序为 registrationMerchant.id 升序，不再取 Dispatch 的 merchant/topic 锁，因而没有引入 registrationMerchant→merchant/topic 的回边。先选中则锁后拒删；先删除则 `confirmWinnerIfPending` 找不到原行、不能选中。未添加 A/G 共同 topic fence；该跨域协议仍待 root/G 成组安排。

## 精确修改文件与方法

| 文件（公共仓库相对路径） | 实际触点 |
| --- | --- |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java` | `edit`；旧 denylist helper 改为 `editableRegistrationFields` 白名单 |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationMerchantController.java` | 只改 `remove` 的事务异常返回及 import；原预检、权限不变 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationMerchantServiceImpl.java` | 仅新 scope 依赖、两个 delete 方法的锁/校验/行数合同；cancel/confirm/reject/edit 逻辑不变 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ICmsRegistrationMerchantService.java` | 两个 delete 方法的合同注释；方法签名不变 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMerchantMapper.java` | 新增专用锁读方法；原写方法不变 |
| `chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMerchantMapper.xml` | 只新增 `selectForDeletionByIdForUpdate`：按原 id 当前读，`useCache=false/flushCache=true`；原 SQL 不改 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/CmsRegistrationControllerSafetyTest.java` | 采纳 H 积分/库存/会员负控；新增原身份/上下文/审计标量保护和缺 ID 拒绝；原 10 方法正文不变 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java` | 仅新增两个测试及其专用 helper；原 92 方法正文及原 fixture、D 通知依赖、资金/退款/库存断言逐字不变 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/MerchantRegistrationCancellationMySqlIT.java` | 采纳 H 删除交错负控，新增 6 项；专用 fixture 增事务代理/相同 scope 桩；原 8 方法正文、已修 Mapper 资源名不变 |

现有调用者及 UI 调用已搜索，见 `callers.txt`（24 行生产/UI 匹配）。后台更新仍是 Controller→Service→Mapper；内部二维码、汇总、TeamGroup 调用的通用更新能力未动。商家 Service 批量删除生产调用者为后台 remove，单条 Service 也已封口；未发现其他生产调用者绕过 Service 直调这两个删除 Mapper。不能把未使用的底层 Mapper 方法当成新暴露 HTTP 入口。

## 新增回归源码及边界

| 套件 | 基线 → 候选源码数量 | 新增合同 |
| --- | --- | --- |
| `CmsRegistrationControllerSafetyTest` | 10→13 | H 原积分/库存/会员负控；所有原身份/经济上下文及审计字段不复制；null/缺 ID 不触发 writer |
| `RegistrationFundsMybatisIntegrationTest` | 92→94 | 零原扣分、非零原扣分分别由真实建单事务扣库存/积分并记原消费流水，经真实后台 handler→真实 Service/Mapper 编辑，再由原会员事务取消；回读全部原列/库存/积分/订单/流水及同号取消重放 |
| `MerchantRegistrationCancellationMySqlIT` | 8→15 | H 预读后中标；多 ID 一行中标全批不删；预读后 topic scope 变化；合法乱序/重复 ID 先锁后删及删除后选中 0 行；真实部分 DELETE 后计数失配整批回滚；缺失目标/单条中标直接 Service 拒绝；旧 RR view 后另线程提交赢家仍被锁定当前读看见 |

合计原 110→候选 122 个 `@Test`，**新增 12，实际 Java/MySQL 执行 0**。`verify_static.py` 对原 10/92/8 方法逐方法比较源码，正文全部保留。没有删测、跳测、放宽校验或降低 baseline。

资金正控使用真实 `createRegistration`（合法姓名/联系信息、未来票窗、真实预扣两单位库存）、真实用户积分及原消费 ledger，不手填一笔虚假扣分。管理员篡改积分/会员/票/数量/原号/父号/指纹/owner 的请求经真实 handler 白名单后，原会员应回到原 100 分、原票库存 10，另一会员 37 分/另一票库存 3 不变；非零只返原实际扣的 100 分。同号重复取消不能二次返积分或库存。取消当前不新增积分 ledger，测试读回并保留原消费 ledger 合同，不虚构新的返还流水。

测试边界明确：handler 直接调用并设置后台 LoginUser，**没有执行 HTTP/AOP/RBAC 验收**；商家 IT 只 mock 已获准 scope，SQL/事务/原记录均真实，未声称验证授权撤销并发或完整 Dispatch 的商家/主题竞争位锁。既有 8 项取消结果来自其他源，不是本 15 项通过证据。没有微信外呼或真实支付。

## 实际执行与未执行

- `git apply --check`：H 两负控对本 root 基线可应用，已采纳。
- `python3 docs/delivery/architecture-day/A-registration-write/verify_static.py`：退出 0；白名单 getter/setter、XML 锁/缓存、稳定顺序/事务源码合同、21 条权限/路由注解、原 110 测试正文及保留源码指纹检查通过；输出 `source-checks.json`。
- `python3 -m unittest discover -s ci/architecture -p test_locking_select.py`：**19 项通过**，退出 0。这是 Python 门禁测试，不是业务并发验证。
- `python3 -m unittest discover -s ci -p test_prelaunch_business_seeds.py`：**8 项，7 通过、1 failure**，退出 1。失败是两处 Controller 合法源码变化尚未由 root 更新其独占 provenance 指纹；测试首次遇到 Merchant Controller 旧 SHA 就失败，不能把未检查到的后续项当已通过。此候选不是全门禁绿。
- `python3 ci/secret-file-gate.py`、A 交付限定文件扫描、`git diff --check`：通过。有限凭据格式扫描通过；gitleaks 不可用，不声称做了完整熵/历史扫描。
- **未执行** Java 生产/测试编译、122 Java 测试、真实 MySQL 两业务闭环/锁竞争/回滚、HTTP 身份、供应商链路。未启动 Java/数据库，未 dispatch CI，未访问生产或执行 DDL。

## root 精确集成动作与限制

仅由 root 复核后刷新 `ci/prelaunch-seed-provenance.json.sourceFiles` 的以下两个精确值，其他条目/角色/菜单不动：

| Controller | root6788 原 SHA256 | 本候选 SHA256 |
| --- | --- | --- |
| `CmsRegistrationController.java` | `07724d3f3217833271389adcb73e6e10b13d97332cdf0a5e71fe1d522ecb7a85` | `16767b31b2d6d5ba48e0a6aa2795ab0fa5860f10f88efb4ded20b3d76c18b002` |
| `CmsRegistrationMerchantController.java` | `2e68901515fdd8b680246d19294401635f7d06dba244f656f3a577d3a11ff3b3` | `7e3997f7a17c50a759cf87a7488d411379a640fac15deb9c09fddfb922a5739e` |

root 需登记源码数量 13/94/15，先真实编译，再安排 MySQL 94/15 与 Controller/原 PII 测试；不能把新增 12 个源码测试当已跑过，不能拼接异源 PASS。本批无需 schema/数据迁移、无新依赖。现有被篡改历史订单不做猜测回填，应由原账本调查。

回退代码会重开这两个写侧漏口，不能作为已验收的安全降级；若候选尚未部署，只保留原冻结源码即可。原报名、原扣分/库存依据、中标记录及既有回执/审计都不能因回退删除或改写。A/G fence、完整退款归一与迟到 SUCCESS 的跨域 P1 仍按 `59eeae48` 协议单独协调，本补丁不声称关闭它们。
