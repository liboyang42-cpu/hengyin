# 部署交接清单(2026-07-11 下午批)

> 分支 `feature/app-backend-support`,以下提交**已 push 未部署**。**push≠部署**,下列服务器侧动作交你在 `47.103.124.64` / 微信开发者工具执行。
> 本机已备好:`chengyinhub-admin/target/chengyinhub-admin.jar`(103MB,17:14 新编,mvn 成功)+ xcx 预检 ✅ 无阻断。
> ⚠️ 与并行会话的 `_部署交接_20260711.md`(11:19)不冲突,本文件是当日下午新增批(cy-sheet 大批 + 商家发布主题 + 图片 + coop 结算/提现)。

---

## 〇、两件上线前 CRITICAL(与代码无关,只有你能做)

| # | 事 | 影响 |
|---|---|---|
| **C1** | **阿里云 RAM 轮换/禁用旧 OSS 密钥**(仍在 git 历史 `60e4493c`,已泄露) | 安全。不轮换=密钥暴露 |
| **C2** | 服务器配齐 env:`CHENGYIN_OSS_*`(endpoint/bucket/ak/sk)、`CHENGYIN_WITHDRAWAL_PII_KEY` 等 | ★好消息:`c8fc5b7a`/`cb815ea7` 已加优雅降级——**没配 env 服务照常启动**,但**OSS 上传 / 提现资料处理会拒绝**,直到配上。所以不配不崩,但相关功能不可用 |

> C2 的 env 建议**在部署 jar 前**配好(否则新上线的图片上传/提现走不通)。

---

## 一、未部署提交清单(按部署方式分类)

### A. 纯 xcx 前端 → 只需**微信上传**(无 jar、无迁移)
| commit | 内容 |
|---|---|
| `3f28a1a0` | **商家发布广场浅色主题**(新增 `style/merchant-light-scope.wxss` + template/index 条件 theme-merchant 类) |
| `0f95ceda` | 修 template/index.wxss @import 路径(曾致白屏) |
| `f567f289`·`8a36417d`·`82a0505b`·`b628111c`·`d3bcd016`·`0b22713d`·`b0d191fc`·`1293fd8e`·`78db5493`·`287694fd`·`26b26b78`·`5090bdb0`·`75dc8901`·`f9563ff0`·`aae80202` 等 | **cy-sheet 收编全套**(22 页 30+ 弹窗,纯 wxml/wxss/json)+ cy-modal + cy-nav-bar 波C/D |
| `88a1118b` | revert(视觉走查回退 2 页,已被后续正确收编覆盖) |

### B. xcx 前端 + 1 个幂等迁移 → **微信上传 + 迁移**(无 jar)
| commit | 内容 | 迁移 |
|---|---|---|
| `ac2ecbef` | 展示位上传接原生裁剪(封面/头像/logo) | 无 |
| `2e8e560d` | 节点/故事/报名图 单图→多图(**后端零改**,复用原列存 CSV + WXS 拆) | `migration_multi_image_20260711.sql` |
| `90f2689c` | 多图上传器 UI(九宫格 + n/N 计数) | 无 |

### C. 后端 java + 迁移 + xcx 前端 → **①迁移 ②重打 jar ③微信上传**
| commit | 内容 | 迁移 |
|---|---|---|
| `eb868ec1` T5 | 真实出金——微信零钱提现(ApiWalletController/CashWithdraw 全套 + coop/finance·withdraw 前端) | `migration_member_cash_withdraw.sql` |
| `69a50b4c` T8·`3f0ae359` T9·`64a3c651` T10 | coop 结算守恒/核销审计关联/perkCost 真实成本 | `migration_coop_settlement.sql` |
| `a944cee2` T6·`b0530fa2` T7·`caf59255` T12·`94279e2f` T13·`2ff863ba` T14·`2fa7ac26` T15·`b5af74ca` T16·`9a5ab116` T17 | coop 条款字段/财务页/双口径文案/发布引导/relation tab/票夹引导/发现能力/删死壳 | (随 T8/T5) |

### D. 后端 java only → **重打 jar**(无迁移/前端)
| commit | 内容 |
|---|---|
| `4a61fcc5` | Gate A 安全硬化 + 资金功能(审并修 2 处 HIGH) |
| `8e652aad` | harden p0 financial + config gates |
| `c8fc5b7a` | OSS 配置缺失回退外部 config(优雅降级) |
| `cb815ea7` | 提现 PII 密钥缺省时应用照常启动(仅处理提现时拒绝) |

### E. 后台管理前端(admin ui,单独部署,非 jar/xcx)
| commit | 内容 |
|---|---|
| `f65b8001` | 修 memberwithdrawal/index.vue 文件损坏(**后台 Vue,若你要更新后台面板才需重新构建 ui;否则可跳**) |

---

## 二、迁移清单(先跑预检,只落 ❌待落)

> 先在服务器跑 `sql/_precheck_migration_status.sql`(纯 SELECT 只读),看每个是 ✅已落 / ❌待落。**只跑 ❌ 的**(多数 `ADD COLUMN` 已落再跑报 Duplicate)。旧的 `c1/c3/r10` 大概率已落(见既往 deploy_state)。

**Schema 迁移(必须先于 jar):**
| 文件 | 幂等? | 归属 |
|---|---|---|
| `migration_multi_image_20260711.sql` | ✅ 幂等(MODIFY COLUMN 加宽 varchar→2048,不丢数据,可重复跑) | 多图(B) |
| `migration_member_cash_withdraw.sql` | 核对预检 | T5 提现(C) |
| `migration_withdrawal_pii_encryption_20260710.sql` | 核对预检 | 提现 PII(C/D) |
| `migration_coop_settlement.sql` | 核对预检 | coop 结算(C) |
| `migration_c1_per_member_limit.sql`·`c3_reward_pending.sql`·`r10_merchant_city_nodes_quota.sql` | 大概率已落,预检确认 | 旧批 |

**seed / view(schema 后、jar 前后皆可):**
- `seed_official_content_template.sql`(官方内容模板种子,若未落)
- `report_coupon_reconciliation.sql`(核销对账视图/报表)

**sys_job 任务(★必须后于 jar——依赖 jar 内 Task 类):**
- `migration_settlement_reconciliation_job_20260710.sql`(`INSERT INTO sys_job … invoke_target='settlementReconciliationTask.run()'`,已带 `WHERE NOT EXISTS` 守卫)

---

## 三、按序执行(服务器侧)

1. **配 env**(C2:CHENGYIN_OSS_* / CHENGYIN_WITHDRAWAL_PII_KEY)+ **轮换旧 OSS 密钥**(C1)
2. **备份库**:`mysqldump -uchengyinhubdb -p --single-transaction --force chengyinhubdb > bak_20260711pm.sql`(★宝塔一键备份会因坏视图失败,用 mysqldump)
3. **跑预检**:`source sql/_precheck_migration_status.sql` → 记下 ❌待落
4. **落 ❌待落 schema 迁移**(第二节 Schema 部分,先于 jar)
5. **部署 jar**:备份旧 `/www/wwwroot/bin/chengyinhub-admin.jar` 留热回滚 → 传本机新 jar → 重启
6. **落 sys_job**:`migration_settlement_reconciliation_job_20260710.sql`(jar 起来后)
7. **微信上传前端**:先 `cd chengyinhub-xcx && node scripts/preflight-release.js`(应无 BLOCK)→ 微信开发者工具上传 → 对照 `docs/发版checklist.md`

---

## 四、部署后真机验收

- **cy-sheet**:随手开几个底部弹窗(报名选票/评价/时间选择器/优惠券/分享足迹卡)——圆角卡 + 上滑 + 遮罩正常、内层不撑大
- **商家发布主题**:商家账号点底部「发布」→ 发布广场应为**白底黑按钮**;玩家账号仍**黑底紫按钮**
- **图片**:节点/报名图能传**多张**、封面/头像上传走**裁剪**;查库 `cms_topic_node.img_url` 存多个 URL(CSV/2048 未溢出)
- **提现**:coop 财务页 → 提现流程能走(需 CHENGYIN_WITHDRAWAL_PII_KEY 配好)
- **杀手锏**:小程序发一条帖 → 查库 `ums_creative_square` 落没落(落=连对库、jar 活着)
- 证书剩余天数 + `/prod-api` 可达(`[历史工具路径或分支标识已移除]`)

---

## 五、回滚

- jar:换回备份的旧 jar 重启
- 迁移:本批 schema 均为 `ADD/MODIFY COLUMN`(加列/加宽),**不破坏旧数据**,旧 jar 兼容;`multi_image` 加宽列对旧代码透明。sys_job 可 `DELETE FROM sys_job WHERE invoke_target='settlementReconciliationTask.run()'`
- 前端:微信后台回退到上一版本
