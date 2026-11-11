# 上线部署步骤（f-release，launch/questo-20261110）

这份清单给业主照着做。每一步都由你本人操作，并单独留一份回执（命令输出、截图或哈希），不能拿后一步的结果冒充前一步。

准备这份清单时，我**没有**做下面任何一件事：连生产、执行 SQL、部署、上传小程序、动任何开关。文件里只写配置项的**名字**，不写任何值。主机地址、AppID、密钥、证书都不进这个公开仓。

相关文件（都在本目录）：

| 文件 | 用途 |
|---|---|
| `MIGRATIONS.md` | 151 个迁移的逐项清单：执行序、类别（必须 / 可选 / 需业主授权 / 不执行）、阶段、重跑规则 |
| `release-precheck.sql` | 只读预检，每个迁移一条 SELECT，判出 APPLIED / NEEDED / PARTIAL 等状态 |
| `release-migrations.json` | 上面两份的机器版本 |
| `release-manifest.json` | `ci/release_manifest.py manifest` 对候选头的输出：源码树、小程序导出身份、全部 636 个迁移的哈希（生产状态一律 UNKNOWN） |
| `gen_release_set.py` | 生成以上三份的脚本，只读 git，不连库 |

更早的发布包 `docs/delivery/release-closeout-20261029/release-steps.md` 仍然有效：它写的 88 项特殊规则（广播三件套、D-PAYEE、单次执行清单）全部保留，下文会指过去。

---

## 0 固定输入

| 项 | 值 |
|---|---|
| 候选头（写这份清单时） | `2256f79e792cb56e2afbcf5dfc6ca60944002e39`（`launch/questo-20261110`，含 l-payout3） |
| 最终 SHA | PR #779 合入 `master` 后的合并提交。**jar 和小程序必须都用这一个 SHA**（审计 B-4「0.1 版本对齐」） |
| 生产参考基线 | 10-29 发布包的基线 `19408d49`（Merge #452）。10-22、10-28、10-29 三个包都**没有**在生产确认执行过，所以这次的发布集把它们全部算进来 |
| 迁移发布集 | 151 项 = 10-29 包 88 项 + 10-29 之后 63 项（其中 launch 波次 34 项）。必须 100、可选 28、需业主授权 19、不执行 4 |
| 迁移执行序 | 候选头上 `ci/migration_ledger.py` 的全量拓扑序，依赖来自 `ci/migration-dependencies.json` |

**最终 SHA 定下来以后，先重算一次发布集**，确认合并没有带进新迁移：

```bash
mkdir -p /tmp/f-release-final
python3 docs/delivery/questo-ui-20261109/f-release/gen_release_set.py --head <最终 SHA> --out /tmp/f-release-final
diff <(grep -v '候选' docs/delivery/questo-ui-20261109/f-release/MIGRATIONS.md) <(grep -v '候选' /tmp/f-release-final/MIGRATIONS.md)
```

没有差异就继续。有差异就停下，按新输出走。脚本发现没标注的新迁移会直接报错退出，不会悄悄漏掉。

---

## 1 备份（任何 SQL 之前）

- ☐ 按 `ops/db-backup-restore.md` 做一次全库备份。记下备份 ID 和 sha256。**没有备份就不做后面任何一步**。
- ☐ 记下应用机上当前 jar 的 sha256 和它内嵌的 `git.commit.id`。这是 jar 回滚的目标。
- ☐ 能做还原演练就做。没做就在回执里写 `UNKNOWN`，不要写「通过」。

---

## 2 只读预检

- ☐ 用只读账号（或会话里先 `SET SESSION TRANSACTION READ ONLY`）对生产库执行本目录的 `release-precheck.sql`。文件里只有 SELECT，生成脚本逐句校验过。
- ☐ 再完整跑一遍 `docs/delivery/release-closeout-20261029/release-migration-precheck.sql`。要它的 [3/7]–[7/7] 节：D-PAYEE 授权矩阵、种子键当前值、依赖前置、大表体量、长事务。
- ☐ 两份输出都存下来，作为**预检回执**。

按 `release-precheck.sql` 每一行的 `status` 处置：

| status | 做法 |
|---|---|
| `APPLIED` | 对象已就位。`MIGRATIONS.md` 里「重跑」列写着「APPLIED 时必须跳过」的项**一定跳过**；其余项可以跳过 |
| `NEEDED` | 按下文轮次执行 |
| `PARTIAL` | 部分对象已在。**停下**，把输出给我看，不要整文件重跑 |
| `REPLACED_UNAPPLIED` | 跳过，永不执行 |
| `CONFLICT` / `BLOCKED_PREREQ` | **停下**，后面的迁移都不跑 |
| `DATA_PREVIEW` | 先跑 10-29 预检附录里对应的预览，抽检通过再执行 |

10-29 包沿用的三条硬规则：

1. `migration_20261013_admin_role_page_grants.sql`（本清单 step 130）判 `NEEDED` 时**禁止执行**，停下交我。它被 step 134 `migration_20261109_play_help_audit_permission.sql` 依赖，所以在本清单里升进了第 1 轮；它停下时 134 也不能跑，其余迁移不受影响。
2. 广播三件套（`20261023_d_official_broadcast_delivery` / `20261026_official_broadcast_delivery` / `20261028_official_broadcast_protocol_union`）按 10-29 `release-steps.md` 第 2 节的表处理。10-26 那个永不执行。
3. 10-29 `release-steps.md` 第 2 节列出的 25 个「单次执行」项，判 `APPLIED` 时不得重跑。

---

## 3 部署 jar 之前的迁移（第 1、2 轮）

每个文件都走 **chengyin-db-migrate** skill：只读预检 → 展示文件和状态 → 你确认 → 执行 → 回读，留回执。按 `MIGRATIONS.md`「执行轮次」表的 step 顺序。任何一步报错或断言为假就**立即停**，存输出，重跑预检，不要跳过往下走。

- **第 1 轮 `1_PRE_REQUIRED`（100 项里的 96 项）**：新 jar 直接读写这些表和列。不跑完不能上新 jar。
- **第 2 轮 `2_PRE_OPTIONAL`（27 项）**：新 jar 在缺失时能容忍（多是开关种子、菜单、可选索引）。建议同一个窗口里跑完。审计 B-6 说的「D1 积分配置是可选项」就是这里的 `migration_20261023_points_value_config_seed.sql`。

这一段要特别注意的几项：

| step | 文件 | 注意 |
|---|---|---|
| 96、146 | `migration_public_content_catalog_20261108.sql`、`migration_z_marketing_revenue_channel_reverse_kind_20261108.sql` | **不执行**。它们的断言把两列唯一索引数成 1 行：前者断言必失败，后者的 ALTER 会被静默跳过。由紧跟其后的 `*_index_shape_20261109.sql`（step 97、147）替代，DDL 一字不差、断言已修。只跑替代文件 |
| 11 | `migration_20261024_d_committed_official_notice_wx.sql` | 既建表又插一条暂停的 `sys_job`。调度器会对每一行 `invoke_target` 做 bean 查找，旧 jar 如果没有这个 bean，**旧 jar 的定时任务会整体停掉**，直到新 jar 起来。接口不受影响。所以把第 1 轮和 jar 替换安排在同一个窗口，跑完第 1、2 轮马上换 jar |
| 98 | `migration_public_content_catalog_node_topic_index_20261108.sql` | 给 `cms_topic_node` 加索引。先看 10-29 预检 [6/7] 的行数，放低峰 |
| 150、151 | `migration_z_workshop_paid_review_20261109.sql`、`migration_zz_questo_collation_align_20261109.sql` | 都改新建的空表（工坊、Questo 营销表），表里应当没有数据。如果预检显示这些表已有行，先停下 |
| 32、33、38 | 主题类别、商家行业及其读回 | 改的是参考数据，不删行、不改 id。主题类别改名是业主 11-01 走查定的，被商家行业迁移依赖，所以放在必须里 |

---

## 4 应用机配置核对（只核对名字是否已注入，不要把值发给任何人）

先在应用机上跑仓库自带的存在性检查，它只报缺哪个名字，不打印值：

```bash
bash scripts/production-preflight.sh --service <你的服务名>
```

然后逐项核对下表。

| 用途 | 配置名 | 期望 | 不对会怎样 |
|---|---|---|---|
| 微信小程序 AppSecret | `CHENGYIN_WXMINI_SECRET`（`wx.mini.secret`） | 已注入，和线上小程序是同一个 AppID | 登录失败；**全站二维码空白**（邀请码、入场码、票夹码、分享卡、队伍码都走服务端 `getwxacodeunlimit`，审计 B-1） |
| OSS | `CHENGYIN_OSS_ENDPOINT`、`CHENGYIN_OSS_ACCESS_KEY_ID`、`CHENGYIN_OSS_ACCESS_KEY_SECRET`、`CHENGYIN_OSS_BUCKET_NAME` | 四项都在，桶就是图片和二维码实际存放的那个 | 上传、二维码图片落盘失败 |
| Redis | `spring.data.redis.host` / `port` / `password` / `database`（按你应用机现有的注入方式）、`CHENGYIN_REDIS_POOL_MAX_WAIT` | 指向生产 Redis，能连通 | 登录态、微信 access_token 缓存失效，二维码也会空白 |
| 腾讯地图 | `CHENGYIN_TENCENT_MAP_ENABLED`、`CHENGYIN_TENCENT_MAP_KEY` | **开关要设成 true**（代码默认是 false），key 已注入 | 首页城市识别、漫游、编辑器地点搜索不可用（审计 B-2） |
| 深色地图 subkey | 不在应用机上，见第 8 节：`CY_MAP_SUBKEY`、`CY_MAP_STYLE_BLACK`、`CY_MAP_STYLE_WHITE`、`CY_MAP_STYLE_PAPER`、`CY_MAP_STYLE_2077` | 上传小程序前填进上传拷贝 | 地图落回官方默认底图（审计 B-3） |
| 告警 | `ALARM_WEBHOOK`、`ALARM_FAILURE_WEBHOOK` | `ALARM_WEBHOOK` 必须已注入（审计 B-5，目前没有证据） | 连接池、支付等告警发不出去 |
| 审核回调 token | `sys_config` 键 `wx.msgpush.token`；公众平台「开发管理 → 消息推送」URL 指向 `/prod-api/api/content-sec/callback`，数据格式**明文 JSON** | token 两边一致；`wx.sec-check.enabled` 为 true | 先发后审时违规图片不会被自动下架（审计 A-3，F-L5C-08）。虚拟支付的发货、退款推送也走这个地址 |
| 微信支付 | `CHENGYIN_WXPAY_APIV3_KEY`、`CHENGYIN_WXPAY_NOTIFY_URL`、`CHENGYIN_WXPAY_REFUND_NOTIFY_URL`；`wx.pay.private-key-path` 指向的证书文件存在 | 两个回调地址都是 https 并指向生产域名 | 下单被拒（回调地址未配）或退款通知丢失 |
| 其他必需密钥 | `CHENGYIN_DB_PASSWORD`、`CHENGYIN_JWT_SECRET`、`CHENGYIN_DRUID_PASSWORD`、`COUPON_QR_SECRET`、`CHENGYIN_WITHDRAWAL_PII_KEY`、`CHENGYIN_SMS_ACCESS_KEY_ID`、`CHENGYIN_SMS_ACCESS_KEY_SECRET` | 全部已注入（`production-preflight.sh` 会逐个检查） | 启动失败或对应功能失败 |

**资金与通行证开关（全部保持默认，这次上线不打开）**：

| 配置名 | 默认 | 说明 |
|---|---|---|
| `platform-pass.enabled` | false | 通行证总开关 |
| `platform-pass.jsapi-enabled` | false | 安卓 / 开发者工具 JSAPI 渠道 |
| `platform-pass.xpay.enabled` | false | iOS 虚拟支付渠道 |
| `platform-pass.xpay.offer-id` / `platform-pass.xpay.app-key` / `platform-pass.xpay.env` | 空 / 空 / 1（沙箱） | app-key 是密钥，只经环境变量注入 |
| `payout.ecommerce.enabled` | false | 电商收付通总开关；关闭时两个电商回调直接回 FAIL |
| `payout.ecommerce.seller-routing-enabled` | false | 按卖家二级商户下单 |
| `payout.ecommerce.profit-sharing-enabled` | false | 按账本分账 |
| `payout.ecommerce.combined-enabled` | false | 合单支付（l-payout3）；还需上面三个同时为 true |
| `payout.ecommerce.sp-mchid` / `sp-appid` / `platform-receiver-mchid` / `platform-seller-sub-mchid` | 空 | 商户号类 |
| `payout.ecommerce.private-key-path` / `merchant-serial-number` / `api-v3-key` | 空 | 密钥类，只放服务器 |
| `payout.ecommerce.notify-url` / `refund-notify-url` / `combine-notify-url` | 空 | 三个电商回调地址 |
| `payout.ecommerce.max-share-percent` | 30 | 分账比例上限 |
| `payout.creator.enabled` / `payout.creator.provider` | false / none | 创作者打款 |
| `withdrawal.fast-withdraw.enabled` | false | **必须保持关闭**，直到你定下提现口径（审计 A-5，P0-11） |
| `marketing.product.cash-enabled` | false | 营销商品现金收款 |
| `workshop.wechat-app-enabled` / `workshop.apple-iap.enabled` | false / false | 工坊支付 |
| `CHENGYIN_MERCHANT_SELF_CHECKOUT_ENABLED` | false | 商家自助收银 |
| `CHENGYIN_ROAM_RECALL_ENABLED` | false | 漫游召回订阅消息（模板未建） |
| `registration.seat-invitation-ttl-hours` | 168 | 席位邀请有效期 |

要打开任何资金开关，先按审计 A-2 在开关全关和目标状态下各跑一遍支付、退款、对账真机验收，并确认不是平台代收再分。打开顺序见 `l-payout2/RESULT.md` 第四、五节和 `l-payout3/RESULT.md`。

---

## 5 部署 jar（和小程序同一个 SHA）

- ☐ 第 1、2 轮全部完成（或按预检跳过）并有回执。
- ☐ 用 `.github/workflows/backend-jar.yml` 手动触发，`source_sha` 填**最终 SHA**（40 位）。制品只保留很短时间，过期就用同一个 SHA 重新触发。
- ☐ `sha256sum chengyinhub-admin.jar`，记进回执。
- ☐ `unzip -p chengyinhub-admin.jar BOOT-INF/classes/git.properties | grep git.commit.id`，必须等于最终 SHA。
- ☐ 按你现有的生产流程替换 jar 并重启。本仓不启用、不修改任何生产 workflow。
- ☐ 健康检查：
  - 启动日志里没有 `Unknown column`、`Table ... doesn't exist`、`Check constraint`、`NoSuchBeanDefinitionException`；
  - 健康检查接口正常；
  - 新连接读回的版本号等于最终 SHA。
  - 健康检查结果单独留一份回执。失败就按第 11 节回滚 jar，**不回滚 DDL**。

---

## 6 新 jar 健康之后的迁移（第 3 轮 `3_POST_DEPLOY`）

这 5 项只往 `sys_job` 插**暂停**的任务（或随之建表），必须等包含对应 bean 的新 jar 起来再跑：

| step | 文件 | 插入的任务 |
|---|---|---|
| 36 | `migration_20261104_content_safety_jobs_post_deploy.sql` | `contentSafetyRecheckTask.run()`、`sensitiveContentRescanTask.run()` |
| 49 | `migration_20261110_ecommerce_payout_wiring.sql` | `ecommercePayoutTask.shareAndRetry()`、`ecommercePayoutTask.syncOnboarding()`（另建 `ecom_refund` 表和菜单；开关关时新 jar 不读这张表） |
| 51 | `migration_20261111_ecommerce_combine.sql` | 不插任务；只建 6 张合单表，按依赖跟在 49 后面 |
| 66 | `migration_marketing_task_registration_20261108.sql` | `channelRefundConsumptionTask.run()`、`marketingRevenuePostingTask.run()` |
| 90 | `migration_o14_reco_search_jobs_20261022.sql` | `recommendationScoreDailyTask.run()`、`contentSearchProjectionTask.refresh()`（10-29 包原排在 jar 前，这里挪到 jar 后，理由同上） |

- 任务插入后全部保持**暂停**。启用按 `chengyinhub-xcx/docs/发版checklist.md` 的 C-1：先确认 bean 在新 jar 里，启用后跨一个 cron 周期看日志，没有 bean-not-found 再继续。
- 电商、营销分配、渠道退款三类任务，在对应资金开关打开之前**不要启用**。

---

## 7 需要你逐项授权的数据迁移（第 4、5 轮）

这些迁移改的是现有业务数据或资金口径。每一项你单独点头，先跑文件头的只读预览（或 `release-precheck.sql` 的对应行），执行后再跑一次回读。第 4 轮在 jar 前后都可以，但要在打开对应功能之前做完；第 5 轮要等新 jar 健康。

| step | 文件 | 改什么 | 关注点 |
|---|---|---|---|
| 25 | `migration_20261027_user_invoice_processing_backfill.sql` | 给旧发票申请补处理行 | 映射缺失的申请不回填，交你核对 |
| 37 | `migration_20261104_invitee_signup_points_off.sql` | 关掉「被邀请人报名送分」积分规则（id 6） | 业主 11-04 已定 |
| 39（第 5 轮） | `migration_20261104_topic_total_time_seconds.sql` | 把误按分钟存的主题总时长换算成秒 | 台账规定新 jar 起来后才能跑（旧 jar 仍按分钟写） |
| 41 | `migration_20261105_welcome_points_fifty.sql` | 注册赠送积分规则（id 1）改为 50 | 新 jar 的余额和流水都读这条规则，不跑的话两边会对不上 |
| 54、55 | `migration_20261102_club_session_gather_point.sql` 及其读回 | 给未结束的俱乐部场次补集合点坐标（只填空值） | |
| 73 | `migration_20261027_merchant_brand_images_to_gallery.sql` | 商家品牌图搬进图库（审计 M-31） | 先跑 10-29 预检附录的预览 |
| 82 | `migration_o14_content_city_code_backfill_20261022.sql` | 主题 / 活动城市编码回填 | |
| 100 | `migration_20261029_unlist_test_topics.sql` | 软删 5 个测试主题（`del_flag=1`，可恢复） | 审计 B-7 / E-3。有未结束报名、对局的主题会被跳过。文件头有预览和回滚语句。990052–990056、990068、990069 不在范围 |
| 104 | `migration_20261103_settlement_rule_three_percent.sql` | 通用结算规则的平台抽成改为 3% | **资金口径**。只影响执行后新结算 |
| 107、108 | `migration_20261030_merchant_game_mechanics.sql`、`migration_20261104_merchant_activity_limit.sql` | 商家默认档位：开放分支 / 博弈玩法；同时 3 场活动 | 改的是 `user_permission` 默认行 |
| 109 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | 到访点 poi_key 回填 | |
| 118 | `migration_20261101_prefab_life_template_copy.sql` | 官方模板 990059 文案修订 | 只改仍是种子原文的行 |
| 121 | `migration_20261028_remove_wildlife_theme.sql` | 下架野生动物主题 | |
| 122 | `migration_20261028_template_shelf_list_free_explore.sql` | 模板货架上架自由探索 | |
| 123 | `migration_20261030_merchant_game_covers_to_oss.sql` | 平台商家玩法封面 URL 改指 OSS | **先确认文件里列出的 OSS 对象都已存在**，否则封面变空 |
| 133 | `migration_20261104_review_wording_seeded_text.sql` | 种子文案「盲盒 / 探店日 / 竞猜」改名 | 只改逐字等于原文的行 |
| 136（第 5 轮） | `migration_20261023_g_wp08_delivery_jobs_paused.sql` | 登记两个暂停任务 | 纯 sys_job，所以放 jar 后 |

审计 B-7 说的「E-2 删除玩家账号的商家行」和「QA 分群清理」在仓库里**没有对应迁移**，这次不处理，见 RESULT.md 待你决定。

---

## 8 OSS 与微信后台

**剧情手写体字体（审计 B-3，P-60）**，步骤详见 `chengyinhub-xcx/docs/发版checklist.md`「故事手写体」：

- ☐ 把 Long Cang 字体文件上传到 OSS，路径与 `chengyinhub-xcx/utils/config.js` 的 `storyFontUrl` 一致；同目录放 `OFL.txt`。核对 sha256 和清单里写的一致。
- ☐ 对象公共读，`Content-Type: font/ttf`，只走 https。
- ☐ 桶的 CORS 规则：来源 `https://servicewechat.com`，方法 `GET, HEAD`。用 `curl -sI -H 'Origin: https://servicewechat.com' <字体地址>` 确认返回头里有 `Access-Control-Allow-Origin`。
- ☐ 公众平台「开发管理 → 服务器域名」的 **downloadFile 合法域名**里有这个 OSS 主机。

**其他 OSS 对象**：

- ☐ step 123 引用的商家玩法封面对象都已在 OSS。
- ☐ 漫游转场视频（可选）按发版清单「漫游大气层转场视频」处理；没传就不播，不影响其他功能。

**公众平台**：

- ☐ 服务器域名：request、uploadFile、downloadFile 合法域名和上一版一致，且包含生产 API 域名与 OSS 主机。
- ☐ 消息推送：URL、明文 JSON、token，见第 4 节「审核回调 token」。
- ☐ 用户隐私保护指引和实际用到的接口（定位、相册、摄像头、麦克风、剪贴板）一致。
- ☐ 只有准备打开通行证时才需要虚拟支付的设置（qx-final RESULT 第七节）。这次开关都关，可以先不做。

---

## 9 开发者工具上传（和 jar 同一个 SHA）

- ☐ 干净检出**最终 SHA**，只用 `chengyinhub-xcx/` 目录。核对它的 tree hash 和 `app.json` 的 blob 与 `ci/release_manifest.py manifest --commit <最终 SHA>` 输出里的 `miniProgram.treeHash`、`appJsonBlob` 一致（候选头上分别是 `68c2c2bb…`、`bdcdb7d2…`，合并后会变，以最终 SHA 的输出为准）。
- ☐ 复制一份**上传拷贝**，对它跑深色地图样式脚本（仓库本身永远是空 key）：
  `CY_MAP_SUBKEY=… CY_MAP_STYLE_BLACK=… CY_MAP_STYLE_WHITE=… CY_MAP_STYLE_PAPER=… CY_MAP_STYLE_2077=… node scripts/release-map-style.js --dir <上传拷贝> --require`
  （四个序号取腾讯位置服务控制台里绑到这个 key 的样式序号；说明见 `components/cy/free-map/DARK_MAP.md`）。
- ☐ 在上传拷贝里跑 `node scripts/preflight-release.js`，没有 BLOCK。
- ☐ 开发者工具打开上传拷贝。保持 `project.config.json` 现有设置，**不要勾**「不校验合法域名」。AppID 和 API 目标在你的私有环境里配。
- ☐ 先「编译」并真机预览，再「上传」。版本号按现有规则递增，备注写最终 SHA。用 `--info-output` 存包体 JSON，跑 `node scripts/wechat-release-contract.js --info <info.json>`，确认主包和每个分包都 < 2 MB（本地估算主包 2037.2 KiB，余量很薄）。

---

## 10 真机冒烟（体验版，iOS 和安卓各一台）

每项打勾并截图。失败的项写下页面、操作和看到的结果。

**二维码（审计 B-1）**

- ☐ 邀请码：俱乐部 / 队伍邀请页出码，另一台手机扫码进到加入页。
- ☐ 入场码：报名成功后票夹里出码；核销台能扫。
- ☐ 票夹码：点票翻面出二维码。
- ☐ 分享卡：结算后生成分享卡，卡上二维码清晰、能扫。
- ☐ 队伍小程序码：码固定落到正式版，**新版发布后**才能扫进加入页；体验版阶段只确认能出码。

**地图（审计 B-2、B-3）**

- ☐ 首页城市识别正确。
- ☐ 漫游主地图打开，深色底图生效。
- ☐ 编辑器里地点搜索有结果。
- ☐ 纸本 / 夜城外观主题的地图样式各看一眼。

**游玩（审计 C-2）**

- ☐ 从主题详情开始一局：主题开场、章节开场、故事流（手写体生效）、站点全屏卡。
- ☐ 玩法：看图选择、逐字猜词、判案（翻页卡、指认、结果插图）、摇一摇骰子。
- ☐ 进度游标、提示、背包。
- ☐ 章节结束、通关、通关后上滑回看、选章。
- ☐ 相册全屏、结算 → 排行 → 评分 → 分享。
- ☐ 三条进入路径（主题详情、票夹、邀请链接）都能进同一局。

**支付与退款（审计 A-2，开关全关状态）**

- ☐ 买一张小额主题门票：拉起微信支付 → 支付成功 → 票夹出现 → 入场码可核销。
- ☐ 对这张票发起退款：退款到账，订单状态变为已退款，结算侧没有记入。
- ☐ 「我的 → 通行证与礼品」：开关关闭时看不到通行证入口；直接打开游戏主题库页显示「通行证暂未开放」。
- ☐ 商家「收款账户」页可以打开，显示未开通状态。

**内容与审核**

- ☐ 发一条带图的帖子：先发后审，作者自己可见；在后台看到复检队列记录。
- ☐ 剧情字体：断网时回落系统字体，没有报错。

**商家**

- ☐ 商家首次进入用旧版首屏（业主决定 W2-16），不是积木 logo。
- ☐ 商家创建活动：同时最多 3 场（如果第 7 节 108 已执行）。

---

## 11 提交微信审核

- ☐ 体验版冒烟全部通过、回执齐了，再在公众平台「提交审核」。
- ☐ 审核说明写清：本版支付只有主题门票（微信支付）；通行证与虚拟支付未开放。
- ☐ 审核通过后「发布」。上传、开发版本、体验版、审核、发布每个状态各留一份证据。

---

## 12 回滚

- **出问题先关开关**：资金类开关本来就是关的。如果中途打开过，先关 `platform-pass.*`、`payout.*`，并在后台暂停电商收付通两条任务。注意 l-payout3：有合单没结清时关 `combined-enabled`，这些单的查单和退款会被拒绝。
- **jar 回滚**：重新部署第 1 节记下的旧 jar。**jar 回滚不撤销 DDL**，旧 jar 能在新表、新列上运行。
  - 回滚到旧 jar **之前**，要先删掉旧 jar 没有 bean 的 `sys_job` 行，否则旧 jar 的调度器起不来（`migration_20261104_content_safety_jobs_post_deploy.sql` 文件头有同样的说明）。这是一条写操作，需要你单独授权。涉及的 `invoke_target`：
    - `contentSafetyRecheckTask.run()`、`sensitiveContentRescanTask.run()`
    - `ecommercePayoutTask.shareAndRetry()`、`ecommercePayoutTask.syncOnboarding()`
    - `channelRefundConsumptionTask.run()`、`marketingRevenuePostingTask.run()`
    - 以及旧 jar 里没有的这几个：`committedOfficialNoticeTask.deliverPending()`、`recommendationScoreDailyTask.run()`、`contentSearchProjectionTask.refresh()`、`imNotificationDeliveryTask.deliverPending()`、`roamRewardDeliveryTask.run()`
  - 删之前先用 `SELECT job_id, invoke_target, status FROM sys_job WHERE invoke_target IN (...)` 读一遍。再次前滚到新 jar 后，重跑对应迁移即可恢复（暂停态）。
- **不要删表、删列**：已写入的订单、分账、提现事实要留着对账。迁移文件头里的 `DROP` 和反向 UPDATE 不会自动执行；要撤销就另写前向迁移，经你批准再执行。
- **数据迁移回滚**：只用各文件头写好的回滚语句，例如测试主题下架只恢复本迁移改过的行。
- **不要**把旧的全库备份直接覆盖到已经有新业务数据的库上。
- **小程序**：在公众平台把线上版本回退到上一版。
