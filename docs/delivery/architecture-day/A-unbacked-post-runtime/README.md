# A95 真 MySQL 复现补充与最小整改建议

结论：**后台裸建报名后取消，已在隔离 MySQL 复现无原扣分却返还1000积分：期望37，实际1037。问题未修。** root 已下载原 XML/receipt，确认95实际执行=94通过+新增1失败、0error/skip，独立 cleanup=CLEAN。库存、另一会员积分及流水的后续断言因首个积分断言失败而未执行，不能宣称实际量测库存涨1或流水增加。不是95通过，不是HTTP/RBAC或生产资损复现。

本报告补充冻结的 `7e1fc9c26c9e0ce29c2489833bbefd121fbaf4b4:docs/delivery/architecture-day/A-unbacked-post/README.md`；旧候选及其“当时尚未运行”记录保留，不追改旧测试或旧输出。本批仅新增本 A 目录，**生产 Controller.add 继续冻结，用户新入口产品选择仍未答。**

## 源码、执行与证据责任

| 项目 | 精确身份 / 结论 |
| --- | --- |
| 复现源码 | `2a2f37e8a7e7c357be390b586d889ec7535a1937`，独立 `codex/architecture-day-admin-post-evidence-20261023`，不正式集成/合并/部署 |
| 真实执行 | [run40040570934](https://github.com/liboyang42-cpu/hengyin/actions/runs/40040570934)，root 回读原 XML/receipt：95 executed、94 pass、1 failure、0 error、0 skip、CLEAN |
| 唯一失败 | `RegistrationFundsMybatisIntegrationTest.bareAdminPostCannotCreateUnbackedPointsOrInventoryToRelease`，复现源码第407行：`expected: <37> but was: <1037>` |
| 本批基线/parent | 当前公共主候选 `26f3764ce0d81594be02c0eeace04aacdd483e58`，仍为 Funds94；不是该源码已运行95 |
| 本批隔离 | `/workspace/hengyin-a-unbacked-post-runtime`，`codex/architecture-day-a-unbacked-post-runtime`，创建时干净，仅文档及轻量证据脚本 |
| 原测试身份 | H patch `c75ab442b572700df1d0289ba636f6f541168e4a:docs/delivery/architecture-day/H/a-de31-recheck/unbacked-post.review.patch`；A7e1 原样51行新增，root复现没有修改该方法 |
| 本会话实际核对 | Git已fetch复现/当前源码；脚本核验H新增块、95→去掉51行→当前94全文、Controller/Service/Mapper原blob逐字相同，输出 `source-identity.json` |
| 原始制品边界 | 真实运行结果来自root本轮明确通知的原XML/receipt回读；A用`gh api`读取run/artifacts返回Forbidden，未独立取得原XML/receipt，不声称二次下载核验，不生成虚构哈希 |

可确认的因果链：真实建单先占1席并读回remaining=9；授权后台handler直调透传裸实体，真实Service/Mapper落行；原会员取消路径执行成功；紧接着数据库积分查询返回1037。该行 `pointUsed=1000` 没有对应原扣分动作。这是测试隔离数据上的真实本地经济效果，不是渠道付款、生产数据或外网HTTP结果。

调用链继续沿原报告：Vue `submitForm→addRegistration(this.form)` 是活跃后台入口；`CmsRegistrationController.add→insertCmsRegistration→Mapper INSERT` 未建立扣分/预占证据；`cancelRegistration` 按裸行 `pointUsed` 返还。只删除UI或清零积分字段都不足以证明整条交易依据安全。内部 `createRegistrationInternal`、`createSelfPlayPassInternal` 的合法Mapper写保留，不扩大禁写范围。

## 精确执行边界

- **实际观察**：第407行查询会员8积分，期望37、实际1037；新增负控失败，旧94通过，独立清理CLEAN，均按root原制品回读记录。
- **未执行的后续断言**：会员7仍100、remaining仍9、积分流水数量不变。不得以源码推导或“取消已执行”替代这些数据库读回；库存9→10仍仅为待验证源码风险。
- **未覆盖**：真实HTTP、过滤器/AOP、权限授权拦截、真实token/外部身份、供应商、真实付款、生产数据库、生产资损。测试设置有权限的LoginUser并直接调handler，不能冒充HTTP/RBAC验收。
- **本批新执行**：只有Git/Python静态核验、秘密文件/有限格式扫描与diff检查；Java/MySQL/DDL/CI dispatch均0，没有新跑资金用例。
- **整改状态**：生产代码0改，未合并部署；此处记录红测证据，不改变门禁、基线、断言、登记阈值或原receipt。

## 最小整改建议（等用户选择，不实施）

共同技术底线：后台客户端填写的会员、票、数量、积分、状态、原号及金额不能直接变成可返还交易依据。首次合法交易必须沿已批准计价、原身份、库存预占及积分扣减命令，同一事务留下原依据；拒绝请求无报名、积分、库存、资金或外部动作效果。取消不能因修新增入口而丢掉原合法返还。

| 待选业务方向 | 最小入口改动与必要验收 |
| --- | --- |
| 关闭通用新增，统一正常报名 | `Controller.add`明确拒绝裸实体交易创建，保留原权限/路由语义；Vue新增动作同步移除或接获准正常报名。内部合法Mapper不动。A95应进入拒绝分支并完整执行所有零效果断言，原94保留 |
| 后台代建正式订单、买家支付 | 先定义操作者/买家scope、买家积分授权、原request身份、计价/预占及支付取回协议，再复用正常建单。不得直接写已付、代扣积分或猜赠送。原单重放、取消按原扣分/原预占读回，正控及拒绝零效果均须真库验证 |
| 人工补录独立审计 | 定义原凭证、受益人、操作者/复核、原单关联；补录不能直接写普通可取消交易行。若需schema另提最小迁移/预检/回退，未经批准不执行DDL。不猜历史金额、不清理旧账 |

用户答复前三者均非已获选实现。未来若正式订单响应身份改变，可仅适配A95取回原身份的方式；保留“真实占席仍属原单、积分无凭空增加、流水不冒增”的原经济断言，不删负控或放宽校验。root统一安排修复后的同源95及必要正控，A不dispatch。

历史裸建记录若已有可取消经济字段，修新入口不能反证历史都有原扣分/预占。应由root另行安排只读原凭证调查及合法恢复方案；不能按当前字段猜扣款、批量补假证据、清空报名/资金/回执/审计，或普遍拒绝合法退款冒充闭环。

## 交 H 的证据定位（仅 root 统一发消息）

请 root 向 H 转交本报告、`runtime-root-attestation.json`、`source-identity.json`，并附已下载的原制品；本A不直接发消息、不新派CI：

1. run40040570934，source2a2f37e8a7e7c357be390b586d889ec7535a1937，类 `com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest`。
2. 对应类artifact命名模板 `rs-class-RegistrationFundsMybatisIntegrationTest-a<run_attempt>`；解压后 `receipt.json` 及 `surefire-reports/TEST-com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest.xml`。命名/相对路径由固定workflow/runner源码核验；实际artifact ID、attempt及文件SHA256本A未取得，须沿root已下载原件，不猜值。
3. 原XML须逐case确认唯一failure在新增方法:407且expected37/actual1037；原receipt/source/run及cleanup独立对应。后续库存/流水断言未执行的限制同时转交，不能只转“95失败”汇总。
4. 原H依据保留：`c75ab442b572700df1d0289ba636f6f541168e4a:docs/delivery/architecture-day/H/a-de31-recheck/{REVIEW.md,RUNTIME.md,unbacked-post.review.patch}`。本次真实证据补足当时未执行状态，不改H原正文。

无生产/schema/迁移/依赖变更；回退只撤本补充目录，不能撤红测证据来宣称问题消失。当前主候选94与隔离负控95保持独立，不拼接成95通过。
