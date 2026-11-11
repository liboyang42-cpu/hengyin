# 发布迁移清单（f-release，候选 `52193cc2`）

由 `gen_release_set.py` 生成，勿手改。生产执行状态一律 **UNKNOWN**，以 `release-precheck.sql` 的结果为准。

| 项 | 值 |
|---|---|
| 候选头 | `52193cc26cc80830f5a8f9b021d1a80ef0d64024` |
| 生产参考基线 | 10-29 发布包基线 `19408d49`（Merge #452）；10-22、10-28、10-29 三个包都没有在生产确认执行 |
| 发布集 | 155 项 = 10-29 包 88 项 + 10-29 之后 67 项（其中 launch 波次 38 项） |
| 候选头全量迁移数 | 640（执行序 order 取自其拓扑序） |
| 必须 / 可选 / 需业主授权 / 不执行 | 102 / 29 / 20 / 4 |

## 执行轮次

| 轮次 | 何时 | 项数 | 迁移（按 step） |
|---|---|---|---|
| 1_PRE_REQUIRED | 部署 jar 前 | 98 | 4、7、9、11、13、14、15、16、17、18、19、20、21、22、23、24、26、27、28、29、30、32、33、34、35、38、40、42、43、44、45、46、47、48、50、51、53、54、58、59、63、64、65、66、67、69、70、71、72、74、78、79、80、83、86、88、90、91、94、95、98、100、101、102、105、106、108、109、113、114、115、116、117、118、119、120、123、129、130、131、134、135、138、139、141、142、143、144、145、146、147、148、149、151、152、153、154、155 |
| 2_PRE_OPTIONAL | 部署 jar 前（可选，建议同批） | 28 | 1、2、3、5、6、8、10、31、55、60、61、62、73、76、77、81、82、85、89、92、96、97、104、122、128、132、133、136 |
| 3_POST_DEPLOY | 新 jar 健康后 | 5 | 36、49、52、68、93 |
| 4_OWNER_DATA_PRE | 业主逐项授权后（jar 前后均可，开对应功能前） | 18 | 25、37、41、56、57、75、84、103、107、110、111、112、121、124、125、126、127、137 |
| 5_OWNER_DATA_POST | 业主授权后，且新 jar 健康后 | 2 | 39、140 |

## 逐项清单

| step | order | 文件 | 类别 | 阶段 | 重跑 | 来源 | 依赖（集内） | 说明 |
|---|---|---|---|---|---|---|---|---|
| 1 | 45 | `migration_20261018_infomation_change_audit.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 cms_infomation_change_audit（玩法说明追加审计） |
| 2 | 46 | `migration_20261022_audit_activity_switch_seed.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_config 落种 audit.activity.pre_review_all='false'、audit.activity.high_risk_categories=''（= 代码现行默认） |
| 3 | 47 | `migration_20261022_f7_minor_guardian.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 minor_guardian_authorization（F7 未成年监护授权；feature.flag.minorMode 缺省关） |
| 4 | 48 | `migration_20261022_f7_user_invoice.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 user_invoice_title / user_invoice_application / user_invoice_application_order（F7 发票；feature.flag.userInvoice 缺省关）（被必须项依赖，升为必须、提前到第 1 轮） |
| 5 | 49 | `migration_20261022_location_guard_switch_seed.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_config 落种 play.arrive.location_guard.mode='off'、play.arrive.require_accuracy='false'、roam.visited_poi.stable_identity='false' |
| 6 | 50 | `migration_20261022_merchant_desk_switch_seed.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_config 落种 merchant.desk.f4.enabled='false' |
| 7 | 51 | `migration_20261023_creative_square_like_unique.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | ums_creative_square_like 增加唯一键 uk_square_like_member_data(member_id,data_id)，每个会员/帖子只保留一个态度状态；不自动删除历史重复行 |
| 8 | 52 | `migration_20261023_points_value_config_seed.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_config 缺失时落种 points.deduct.rate_yuan_per_point='0.02' 与 points.deduct.max_yuan_per_order='50.00'；PointsValuePolicy 缺失/无效时有同值缺省，不覆盖已配置值 |
| 9 | 53 | `migration_20261023_wx_pay_order_intent.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 wx_pay_order_intent（微信支付下单意图冻结；WechatPayServiceImpl 下单前读写） |
| 10 | 54 | `migration_20261024_community_open_flags.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_config 3 键：专业广场浏览/发帖开关 true、灰度账号 *（owner 2026-10-24：上线即对所有玩家开放；键缺失时网关按关闭处理） |
| 11 | 55 | `migration_20261024_d_committed_official_notice_wx.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 committed_official_notice_wx（官方通知微信冻结意图）+ 一条默认暂停的 sys_job（committedOfficialNoticeTask.deliverPending()，不自动启用） |
| 12 | 56 | `migration_20261024_c_before_start_notice_typed.sql` | 不执行 | PRE_DEPLOY | 永不执行 | 10-29 发布包（未确认执行） | 11 | 已被替代，永不执行（10-29 包 REPLACED_UNAPPLIED） |
| 13 | 57 | `migration_20261024_d_npc_asr_intent.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 npc_asr_intent（NPC 语音识别意图身份；NpcChatService 读写） |
| 14 | 58 | `migration_20261024_d_npc_asr_shared_quota.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 13 | npc_asr_intent 加 quota_reservation / quota_refund_pending 两列 + 索引 |
| 15 | 59 | `migration_20261024_g_before_start_notice_metadata_v2.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 11 | committed_official_notice_wx 加 notice_type / wx_payload_json、im_intent_id 改可空、DROP 旧 recipient CHECK、加 typed CHECK 与索引（替代旧 C 文件） |
| 16 | 60 | `migration_20261024_registration_verified_seats.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 cms_registration_seat |
| 17 | 61 | `migration_20261024_registration_seat_activity_scope.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 16 | cms_registration_seat 的 chk_registration_seat_fact 放宽为允许独立活动（topic_id 可空） |
| 18 | 62 | `migration_20261025_coop_invite_request_receipt.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 coop_invite_request_receipt（协作邀请原请求回执） |
| 19 | 63 | `migration_20261026_official_event_attendance.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 official_event_attendance_config、official_event_attendance（官方赛事签到） |
| 20 | 64 | `migration_20261026_team_event_command_receipt.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 team_event_command_receipt（组队/赛事命令原请求回执） |
| 21 | 65 | `migration_20261026_theme_attribute_registry_version.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 theme_attribute_registry_version（主题属性注册表版本；ThemeStateKeyMapper 读写） |
| 22 | 66 | `migration_20261026_user_invoice_processing.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 4 | 新表 x6：user_invoice_issuer、user_invoice_issuer_owner、user_invoice_scope、user_invoice_processing、user_invoice_processing_audit、user_invoice_issued_reference（发票处理台；空配置，无授权） |
| 23 | 67 | `migration_20261026_user_invoice_title_receipt.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 4 | 新表 user_invoice_title_receipt（发票抬头保存原请求回执） |
| 24 | 68 | `migration_20261027_template_adoption_receipt.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 template_adoption_receipt（模板采用原请求回执） |
| 25 | 69 | `migration_20261027_user_invoice_processing_backfill.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 4、22 | 为 W8 之前提交、全部订单行能经 user_invoice_issuer_owner 映射到唯一开票主体的发票申请补 user_invoice_processing 行（version=0、reconciliation_status=NONE）；映射缺失或跨主体的申请不回填，留人工 |
| 26 | 70 | `migration_20261028_topic_play_setup.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 cms_topic_play_setup（主题可玩时段与开局前准备；/api/topic/play-setup 读写） |
| 27 | 71 | `migration_20261029_activity_cancellation_receipt.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 之后的 master | — | cms_activity 新增取消回执列；新 jar 读写 |
| 28 | 72 | `migration_20261029_topic_cancellation_receipt.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 之后的 master | 27 | cms_topic.cancellation_epoch + 取消回执表；新 jar 读写 |
| 29 | 73 | `migration_20261031_coop_invite_read_time.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | coop_invite.read_time 已读回执列 |
| 30 | 74 | `migration_20261101_author_write_receipt.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 作者写入幂等回执表 |
| 31 | 75 | `migration_20261101_stagger_sys_job_seconds.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 只改既有 sys_job 的 cron 秒位错峰（缓解整点连接池等待）；须在重启 jar 前执行才生效 |
| 32 | 76 | `migration_20261101_theme_categories.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 改名 4 个主题类别 + 新增 7 个（不删行、不改 id；业主 11-01 走查已批）；商家行业迁移依赖它 |
| 33 | 77 | `migration_20261102_merchant_industry_categories.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | 32 | 新增 sys_category type=6 商家行业；新 jar 商家类目读 type 1+6，缺了商家行业为空 |
| 34 | 78 | `migration_20261103_content_safety_recheck.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 内容安全自动复检队列表 |
| 35 | 79 | `migration_20261103_sensitive_content_library.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 敏感内容库 4 表 + 开关（默认只报告）+ 后台菜单（零授权） |
| 36 | 80 | `migration_20261104_content_safety_jobs_post_deploy.sql` | 必须 | POST_DEPLOY | 幂等 | 10-29 之后的 master | 34、35 | 两条暂停的 sys_job；台账 POST_DEPLOY，新 jar 健康后再跑；回滚旧 jar 前须先删这两行 |
| 37 | 81 | `migration_20261104_invitee_signup_points_off.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 积分规则 id 6（被邀请人报名送分）status/value 置 0 |
| 38 | 82 | `migration_20261104_merchant_industry_categories_readback.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | 33 | 只读断言（紧跟 20261102 商家行业） |
| 39 | 83 | `migration_20261104_topic_total_time_seconds.sql` | 需业主授权 | POST_DEPLOY | 幂等 | 10-29 之后的 master | — | 把编辑器误按分钟落库的 cms_topic.total_time 换算成秒；台账 POST_DEPLOY（旧 jar 仍按分钟写） |
| 40 | 84 | `migration_20261105_chapter_video.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | cms_topic_chapter 开场/结局视频两列 |
| 41 | 85 | `migration_20261105_welcome_points_fifty.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 积分规则 id 1（注册赠送）value 改 50，与新 jar 余额口径一致 |
| 42 | 86 | `migration_20261107_template_adoption_inherited_config_hash.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 之后的 master | 24 | template_adoption_receipt 新列 + 索引 |
| 43 | 87 | `migration_20261108_play_help_run_quota.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 整局共享提示额度计数表 |
| 44 | 88 | `migration_20261108_registration_seat_invitation.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 多人票席位邀请两张事实表 |
| 45 | 89 | `migration_20261108_topic_content_revision.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | — | 不可变内容修订索引表 |
| 46 | 90 | `migration_20261109_app_media_provenance.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | app_media_asset 来源 5 列（素材来源） |
| 47 | 91 | `migration_20261109_play_run_feedback.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 完局问题反馈表 |
| 48 | 92 | `migration_20261110_ecommerce_payout.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 电商收付通 6 表（开关默认关） |
| 49 | 93 | `migration_20261110_ecommerce_payout_wiring.sql` | 必须 | POST_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 48 | ecom_refund 表 + 两条暂停 sys_job（ecommercePayoutTask）+ 菜单；含 sys_job，放新 jar 之后（开关关时新 jar 不读本表） |
| 50 | 94 | `migration_20261110_platform_pass.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 通行证 4 表 + 两个未上架套餐（开关默认关） |
| 51 | 95 | `migration_20261111_aigc_filing_config_seed.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | sys_config 新增空值键 aigc.algorithm.filing-no（备案号公示位，空值不显示；拿到备案号后运营在后台填） |
| 52 | 96 | `migration_20261111_ecommerce_combine.sql` | 必须 | POST_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | 49 | 合单支付 6 表（开关默认关）；依赖 ecommerce_payout_wiring，随它放新 jar 之后 |
| 53 | 104 | `migration_20261111_ai_generation_log_retention_index.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | ai_generation_log 加 create_time 索引（f-compliance P0-10 留存清理按时间分批删，缺索引会全表扫） |
| 54 | 109 | `migration_ai_theme_draft_request_intent_20261024.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 ai_theme_draft_request_intent |
| 55 | 114 | `migration_audit_problem_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 audit_problem（审核逐项驳回原因）；必须先于打开 chengyin.audit.item-problems.enabled |
| 56 | 125 | `migration_20261102_club_session_gather_point.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 回填未结束俱乐部场次的集合点坐标（只填空值） |
| 57 | 126 | `migration_20261104_club_session_gather_point_readback.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | 56 | 只读断言（紧跟集合点回填） |
| 58 | 132 | `migration_20261024_d_chapter_application_round.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | merchant_chapter_application 加 application_round 列 + CHECK |
| 59 | 147 | `migration_20261029_club_post_repost.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 俱乐部帖文转发表 + club_post.repost_count |
| 60 | 157 | `migration_club_welcome_note_20261022.sql` | 可选 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | club 增加 welcome_note VARCHAR(600) NULL DEFAULT NULL；代码独立读取，旧行保持默认欢迎文案 |
| 61 | 190 | `migration_doc2_f3_bundle_ledger_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 explore_bundle_order/grant/unit/reservation/waiver/exit_case（F3 套票台账；ledger-enabled 默认关） |
| 62 | 191 | `migration_doc2_f3_refund_hold_20261026.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 explore_bundle_refund_hold（F3 履约冻结；本 SHA 主代码尚无读写方） |
| 63 | 230 | `migration_marketing_attribution_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 营销归因 2 表 |
| 64 | 231 | `migration_marketing_product_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 营销商品/礼品 5 表 |
| 65 | 232 | `migration_marketing_extra_seat_20261108.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 64 | 加座：放宽 CHECK 到 (1,2,3) + 索引 |
| 66 | 233 | `migration_marketing_revenue_allocation_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 收入分配 4 表 |
| 67 | 234 | `migration_marketing_z_channel_voucher_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | 64 | 渠道兑换码 5 表 |
| 68 | 235 | `migration_marketing_task_registration_20261108.sql` | 必须 | POST_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | 66、67 | 两条暂停的 sys_job（渠道退款补消费、收入分配队列）；纯 sys_job，放新 jar 之后 |
| 69 | 238 | `migration_20261023_cash_withdraw_fund_fact.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 member_cash_withdraw_fund_fact（提现资金事实，只追加）+ 触发器 cash_fund_fact_no_update / cash_fund_fact_no_delete；FK 指向 member_cash_withdraw |
| 70 | 239 | `migration_20261024_cash_withdraw_transfer_intent.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 69 | 新表 member_cash_withdraw_transfer_intent（提现扣款/转账原始身份；CashWithdrawTransferIntentMapper 读写） |
| 71 | 240 | `migration_20261025_cash_withdraw_fund_request_compat.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 69 | member_cash_withdraw_fund_fact 加 8 列请求身份 + CHECK ck_cash_fund_request |
| 72 | 245 | `migration_member_profile_version_20261022.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 ums_member_profile_version（资料版本回执；新 jar 表缺失只 WARN） |
| 73 | 247 | `migration_member_visited_poi_poi_key_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | member_visited_poi 加可空列 poi_key VARCHAR(32) AFTER geo_key + 唯一键 uk_member_poi_key(member_id, poi_key) |
| 74 | 253 | `migration_merchant_category_ids_20261022.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | mms_merchant 增加 category_ids VARCHAR(255) NULL；将为空且 category_id>0 的行回填为原主行业，保留已有多选值 |
| 75 | 259 | `migration_20261027_merchant_brand_images_to_gallery.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 把入驻品牌形象图 mms_merchant.derivatives（逗号/分号分隔）追加进门店相册 gallery（JSON 数组，上限 9 张）；derivatives 原值保留 |
| 76 | 261 | `migration_merchant_desk_receipt_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 merchant_desk_receipt（F4 工作台幂等回执；merchant.desk.f4.enabled 默认关） |
| 77 | 272 | `migration_merchant_redemption_anchor_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 merchant_redemption_anchor（F4 核销锚点配置） |
| 78 | 276 | `migration_merchant_wechat_qrcode_20261022.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | mms_merchant 增加 wechat_qrcode VARCHAR(512) NULL；原 wechat 中的 URL 搬入新列并清空 URL 文本，保留已有二维码及文本微信号 |
| 79 | 298 | `migration_npc_voice_enrollment_intent_20261024.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 npc_voice_enrollment_intent |
| 80 | 299 | `migration_npc_voice_confirmed_attempt_20261024.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 79 | npc_voice_enrollment_intent 加 request_id / previous_intent_id，回填 request_id='sample:'／／sha，换唯一键 uk_nvei_request、加 CHECK |
| 81 | 300 | `migration_o14_city_code_backfill_log_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 o14_city_code_backfill_log（O-14 回填来源标记） |
| 82 | 301 | `migration_o14_cms_activity_city_code_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | cms_activity 加可空列 city_code VARCHAR(32)（ALGORITHM=INSTANT）+ 索引 idx_cms_activity_city_code（INPLACE, LOCK=NONE） |
| 83 | 302 | `migration_o14_cms_topic_city_code_20261022.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | cms_topic 加可空列 city_code VARCHAR(32)（INSTANT）+ 索引 idx_cms_topic_city_code（INPLACE, LOCK=NONE）（被必须项依赖，升为必须、提前到第 1 轮） |
| 84 | 303 | `migration_o14_content_city_code_backfill_20261022.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 81、82、83 | 按 club.city 白名单回填 cms_topic / cms_activity.city_code，并在 o14_city_code_backfill_log 记来源；只改 city_code IS NULL 或本回填写过且未被改动的行 |
| 85 | 304 | `migration_o14_content_search_document_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 content_search_document（O-14 统一搜索投影） |
| 86 | 308 | `migration_20261023_d_official_broadcast_delivery.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | official_broadcast_log 加 6 列（delivery_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'、delivery_title、delivery_sub、failure_code、message_id、reached_time），按旧 reached/channel 回填 delivery_state/failure_code，修正 official_broadcast.reached_count/status；带 SQL 前置/后置守卫 |
| 87 | 309 | `migration_20261026_official_broadcast_delivery.sql` | 不执行 | PRE_DEPLOY | 永不执行 | 10-29 发布包（未确认执行） | — | 已被替代，永不执行（10-29 包 REPLACED_UNAPPLIED） |
| 88 | 310 | `migration_20261028_official_broadcast_protocol_union.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 86、87 | 在 D 前缀上组合 10-26 旧协议：delivery_state 放宽为 VARCHAR(32)，official_broadcast_log 加 frozen_title/frozen_sub/intent_id/attempts/update_time，official_broadcast 加 audience_frozen/client_request_id/request_owner/request_hash + 唯一键，新表 official_broadcast_creation |
| 89 | 323 | `migration_orient_geo_ar_team_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 orient_node_geo / orient_task_alternative / orient_task_attempt / orient_task_completion / orient_safety_rule + sys_config orient.* 5 键（默认关） |
| 90 | 324 | `migration_orient_score_recovery_20261026.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 orient_score_recovery（定向计分 UNKNOWN 人工处置；OrientScoringMapper 读写） |
| 91 | 325 | `migration_orient_score_sync_receipt_20261026.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 orient_score_sync_receipt（定向计分同步回执；OrientScoringMapper 读写） |
| 92 | 334 | `migration_20261111_creator_income_detail_idempotency.sql` | 可选 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | creator_income_detail 加 idempotency_key 列与唯一键（f-money P0-17，备将来写入用；新 jar 不读不写） |
| 93 | 357 | `migration_o14_reco_search_jobs_20261022.sql` | 可选 | POST_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 85 | sys_job 登记 recommendationScoreDailyTask.run() 与 contentSearchProjectionTask.refresh()，status='1'（暂停）（f-release 改到新 jar 之后：只插 sys_job 行：CanonicalJobTarget.canonical 会对每一行 invoke_target 做 getBean，旧 jar 没有该 bean 时调度器整体失效（参见 migration_20261104_content_safety_jobs_post_deploy.sql 头注释），所以放到新 jar 健康之后执行） |
| 94 | 368 | `migration_marketing_redemption_constraint_name_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 改名 redemption 表的 CHECK 约束（为下一文件让名） |
| 95 | 369 | `migration_marketing_promotion_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | 94 | 促销 7 表 |
| 96 | 373 | `migration_play_arrive_guard_log_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 play_arrive_guard_log（到达守卫留痕；mode=off 时不读写） |
| 97 | 382 | `migration_pms_sku_booking_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 pms_sku_booking_rule / pms_sku_capacity_slot（F4 SKU 预约容量） |
| 98 | 386 | `migration_preset_draft_source_20261108.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | — | cms_member_template.preset_source |
| 99 | 397 | `migration_public_content_catalog_20261108.sql` | 不执行 | PRE_DEPLOY | 永不执行 | launch 波次（master 2bb81143 之后） | — | 被 public_content_catalog_index_shape_20261109 逐字替代（原断言把两列唯一索引数成 1 行，必失败）；只执行替代文件 |
| 100 | 398 | `migration_public_content_catalog_index_shape_20261109.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | 99 | 公开目录 2 表（替代上一文件，DDL 逐字相同、断言已修） |
| 101 | 399 | `migration_public_content_catalog_node_topic_index_20261108.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | — | cms_topic_node(topic_id) 索引（大表加索引，看锁） |
| 102 | 408 | `migration_20261023_advanced_command_fingerprint.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | play_attempt_log 加 request_fingerprint CHAR(64) ascii_bin NULL（高级动作请求指纹） |
| 103 | 409 | `migration_20261029_unlist_test_topics.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 软删 5 个测试/演示主题（id+名称+member_id 三者钉住；del_flag=1、update_by='unlist_test_topics_20261029'、update_time=NOW()）；有未取消报名/支付中或已支付/进行中会话的主题跳过 |
| 104 | 410 | `migration_orient_scoring_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 orient_score_ledger / settlement / appeal / orient_result_dispute + sys_config orient.scoring.* 5 键（默认不发奖） |
| 105 | 411 | `migration_orient_session_lifecycle_20261022.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | topic_route_session 加可空列 phase VARCHAR(16) AFTER status；status 列只改注释（类型/默认不变）；新表 orient_session_clock |
| 106 | 412 | `migration_photo_check_intent_20261023.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 102 | 新表 photo_check_intent |
| 107 | 419 | `migration_20261103_settlement_rule_three_percent.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 通用结算规则平台抽成改 3%（资金口径） |
| 108 | 424 | `migration_20261102_club_checkin_phone_visible.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | club_role_assignment.customer_phone_visible |
| 109 | 432 | `migration_club_host_reservation_20261109.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | — | club_event_series.host_reservation_required |
| 110 | 457 | `migration_20261030_merchant_game_mechanics.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 商家默认档位开放分支/博弈玩法（user_permission） |
| 111 | 458 | `migration_20261104_merchant_activity_limit.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 商家默认档位同时 3 场活动（user_permission） |
| 112 | 459 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 73 | 回填 member_visited_poi.poi_key（poi:{地标id} 或 pt:{5位坐标}；判为重复的行保持 NULL）；不删行、不改 geo_key |
| 113 | 469 | `migration_20261031_roam_session_track.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | roam_session.track_json |
| 114 | 471 | `migration_roam_share_version_20261022.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | roam_share_snapshot 加列 version INT NOT NULL DEFAULT 1（旧行即为 1） |
| 115 | 476 | `migration_20261024_official_roam_challenges.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | roam_poi 加 gallery_json/photo_required/badge_code；roam_poi_reward_log 加 photo_url/submitted_answer（RoamMapper 直接 SELECT 新列） |
| 116 | 477 | `migration_20261025_official_poi_quotes.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 115 | roam_poi 加 quotes_json/photo_credit（官方地点公开引述与照片署名；RoamMapper 直接 SELECT） |
| 117 | 486 | `migration_20261023_g_cash_evidence_expand.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 coop_settlement_cash_close / _contribution / _event；coop_settlement merchant_id 改可空 + 加 cash_head_kind/cash_close_id + 3 个 STORED 生成列 + 5 索引 + 3 FK + CHECK；coop_settlement_adjustment 加 2 列 + 3 索引 + 2 FK + CHECK |
| 118 | 512 | `migration_20261022_theme_state_key_attribute_def.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | theme_state_key 加 9 个可空列 min_value/max_value/display_type/color/icon/sort_order/visible/persist_scope/rules_json（ALGORITHM=INSTANT） |
| 119 | 513 | `migration_20261022_theme_character.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 118 | 新表 theme_character（F2 跨局角色卡） |
| 120 | 526 | `migration_20261028_d20_attribute_registry.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 118 | theme_state_key 加 is_attribute/attribute_preset（ALGORITHM=INSTANT）；把老主题 journey_rules_json.attributes 的 counter.* 登记进 theme_state_key 并标为属性（ThemeAttributeMapper 直接读写新列） |
| 121 | 530 | `migration_20261101_prefab_life_template_copy.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 官方模板 990059 文案修订（只改仍是种子原文的行） |
| 122 | 531 | `migration_20261101_prefab_life_template_state_keys.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | 118 | 为官方模板 990059 补注册两个状态键（不补则套用后发布被拒） |
| 123 | 532 | `migration_topic_location_contract_20261107.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | 83 | cms_topic.content_contract_json；selectCmsTopicVo 读它 |
| 124 | 541 | `migration_20261028_remove_wildlife_theme.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 软删测试主题「野生邻居观察局」（990052–990056 区间同名行）：del_flag=2、user_status=0、is_recommend=0 |
| 125 | 553 | `migration_20261028_template_shelf_list_free_explore.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 6 个自由探索主题模板 user_status 0→1，出现在模板页货架（templateStatus 仍为 EXPERIMENTAL） |
| 126 | 562 | `migration_20261030_merchant_game_covers_to_oss.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 平台商家玩法封面 URL 改指 OSS（先确认 OSS 上对象已存在） |
| 127 | 566 | `migration_20261111_rule_seed_split_steps.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 官方模板种子规则改成一步一行（f-pages P-41；只改仍是种子原文的行） |
| 128 | 574 | `migration_verification_review_evidence_20261022.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 新表 verification_review_evidence（F4 复核补材料） |
| 129 | 575 | `migration_video_bounty_host_tip_20261108.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 视频悬赏 + 主持小费 6 表 |
| 130 | 576 | `migration_video_bounty_group_verification_20261108.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 129 | video_bounty_submission.verified_group_key + 索引 |
| 131 | 579 | `migration_20261025_coop_order_station_fulfillment.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 117 | coop_invite 加 supply_station_id + 索引 + CHECK + FK；新表 coop_order_station_fulfillment（6 FK）+ 两个不可改触发器 |
| 132 | 580 | `migration_w8_refund_fulfillment_recovery_20261026.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | refund_application 加 fulfillment_sync_queue_at（DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)）与索引 idx_refund_fulfillment_recovery（本 SHA 主代码尚无读写方） |
| 133 | 585 | `migration_20261026_user_invoice_menu.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 22、23 | sys_menu 新增「用户发票处理」C 菜单（funds-aftercare 下）与 query/taxView/process/reconcile 4 个 F 按钮；不授任何角色 |
| 134 | 600 | `migration_20261013_admin_role_page_grants.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | 仅对「一个页面授权都没有」的 cy_operator/cy_auditor/cy_support/cy_finance 按 20260719 矩阵补授权并补祖先菜单；已有页面授权的角色不动（已有页面时 no-op；生产状态未知）（被必须项依赖，升为必须、提前到第 1 轮） |
| 135 | 601 | `migration_20261016_admin_role_payee_separation_forward.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | 134 | D-PAYEE：撤销 cy_operator→business:clubpayee:verify 与 cy_finance→business:clubpayee:payout，补齐 cy_operator→payout 与 cy_finance→verify；全新安装（sys_user 为空且角色无页面）才走批量初始化分支（被必须项依赖，升为必须、提前到第 1 轮） |
| 136 | 602 | `migration_20261027_orient_scoring_menu_grants.sql` | 可选 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_menu 在官方主题 C 菜单下补 business:orientScoring:list/review 两个 F 按钮；sys_role_menu 只授 cy_operator（owner 2026-10-27） |
| 137 | 603 | `migration_20261104_review_wording_seeded_text.sql` | 需业主授权 | PRE_DEPLOY | 幂等 | 10-29 之后的 master | — | 平台种子文案「盲盒/探店日/竞猜」改名（只改逐字等于原文的行） |
| 138 | 604 | `migration_20261109_play_help_audit_permission.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 134、135 | 后台按钮权限 business:playhelp:query（零角色授权） |
| 139 | 605 | `migration_20261109_questo_admin_menus.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | Questo 后台新页面菜单（零角色授权） |
| 140 | 612 | `migration_20261023_g_wp08_delivery_jobs_paused.sql` | 需业主授权 | POST_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | sys_job 登记两个默认暂停的补偿任务：imNotificationDeliveryTask.deliverPending()、roamRewardDeliveryTask.run()（已存在则原样保留）（f-release 改到新 jar 之后：只插 sys_job 行：CanonicalJobTarget.canonical 会对每一行 invoke_target 做 getBean，旧 jar 没有该 bean 时调度器整体失效（参见 migration_20261104_content_safety_jobs_post_deploy.sql 头注释），所以放到新 jar 健康之后执行） |
| 141 | 614 | `migration_club_post_media_review_budget_20261024.sql` | 必须 | PRE_DEPLOY | 幂等 | 10-29 发布包（未确认执行） | — | club_post_media_review_outbox 两个 CHECK（owner/trace）放宽为允许 state=5（停止/人工）保留原 owner/trace |
| 142 | 618 | `migration_20261024_wx_verified_payment_fact.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | — | 新表 wx_verified_payment_fact、wx_verified_payment_fact_rejection、wx_payment_callback_fact_link |
| 143 | 619 | `migration_20261024_clm_commerce_payment_proof.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 142 | club_membership_invoice、oms_order 各加 verified_payment_fact_id / verified_payment_fact_hash + 唯一键 + FK(→wx_verified_payment_fact) + CHECK |
| 144 | 620 | `migration_20261024_coop_original_refund_components.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 117、142 | 新表 coop_settlement_original_component、coop_settlement_refund_receipt、coop_settlement_original_adjustment |
| 145 | 621 | `migration_20261024_coop_negative_margin_manual.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 144 | 新表 coop_settlement_manual_case |
| 146 | 622 | `migration_20261024_registration_payment_fact.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 142 | 新表 cms_registration_payment_fact |
| 147 | 623 | `migration_20261024_wx_success_fact_business_index.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 发布包（未确认执行） | 142 | wx_verified_payment_fact 加索引 ix_wx_fact_business(business_out_trade_no, mchid) |
| 148 | 630 | `migration_x12_topic_look_20261029.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | 10-29 之后的 master | — | cms_topic.look_json 主题画面风格 |
| 149 | 631 | `migration_z_channel_voucher_snapshot_20261109.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 67 | marketing_channel_voucher.grant_snapshot_json |
| 150 | 632 | `migration_z_marketing_revenue_channel_reverse_kind_20261108.sql` | 不执行 | PRE_DEPLOY | 永不执行 | launch 波次（master 2bb81143 之后） | 66 | 被 *_index_shape_20261109 逐字替代（原守卫按 1 行数两列唯一索引，ALTER 会被静默跳过）；只执行替代文件 |
| 151 | 633 | `migration_z_marketing_revenue_channel_reverse_kind_index_shape_20261109.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 150 | 收入投递队列 CHECK 放宽到 task_kind (1,2,3)（替代上一文件） |
| 152 | 634 | `migration_z_workshop_apple_iap_20261109.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 工坊 Apple IAP 3 表 |
| 153 | 635 | `migration_z_workshop_purchase_20261109.sql` | 必须 | PRE_DEPLOY | 幂等 | launch 波次（master 2bb81143 之后） | — | 工坊购买 3 表 |
| 154 | 636 | `migration_z_workshop_paid_review_20261109.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 153 | 工坊付费审核：workshop_version/order 加列与唯一键 + 2 表 |
| 155 | 638 | `migration_zz_questo_collation_align_20261109.sql` | 必须 | PRE_DEPLOY | APPLIED 时必须跳过 | launch 波次（master 2bb81143 之后） | 43、44、45、63、65、64、95、94、66、67、99、100、130、129、149、150、151、152、154 | Questo 新表隐式 0900_ai_ci 字符列改到 utf8mb4_general_ci（修 1267 排序规则冲突） |
