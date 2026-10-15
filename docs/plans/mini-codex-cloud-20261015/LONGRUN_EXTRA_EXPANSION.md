# EXTRA六线新增独立后端边界

CY-HANDOFF: CODEX。精确文件与既有调用候选见 [授权JSON](LONGRUN_EXTRA_EXPANSION.json)，本页覆盖仅原客户端文件耗尽的等待。

新组一律以公共 `7e5882addb7269373b2b95daa0a4ceeb4485fd10`（CORE PR44）源文件为基线，在独立worktree/分支修，仅精确变更cherry-pick回EXTRA候选，不以原1e全文件覆盖CORE已有修复。原source最后public commit及四方PR冲突核对已在JSON记录。此表是源码授权，不是测试/部署签收。

E05独占PlayEncounterServiceImpl与RoamSharePrivacyService；E06独占ApiRoamShareController、RoamShareSnapshotService、RoamShareSnapshotMapper Java/XML。两个lane文件交集为空，不转交UI/客户端、共享会员mapper或全局控制表。各lane可在边界内独立补真实任务，保持唯一child写者；新测试/证据沿自己队列唯一新ID，旧组合fixture只读，确需改时申领精确路径。

E05先处理E03-08已读完整packet：只有键缺席才live fallback；现有冻结节点对象内playables/branchOutcomes键存在但null或错类型，复用现有ServiceException("玩法配置不可用")，禁止穿透实时配置；正常{}和[]不改变，正常[]仍沿原本局图合并。此为实现原注释/既有不可用错误，不新增错误结果政策，不修改NodeJourneySnapshot生产者。

CORE/C06-27将把当前private currentScopedSession逻辑提成共享findCurrent opt-in函数，原find不动；精确签名在JSON，消费者等实际公共SHA。随后E05 Encounter、E03 current-story调用点先核真实当前局合同，按producer接入，不重复复制scope逻辑。CORE C06-24场景有效story冻章已公共（具体小提交由C00回PR16）；旧adc packet不能覆盖最新7e实际源，先去重。

Share模块是现有roam页面真实调用，只有既存快照存储/50上限/opaque token/精度白名单/归一化边界；匿名token读、撤销策略、留存、权限、新generation/tombstone、坐标精度政策不得猜改。既有guard成立即反证，不为补50造代码。后台cart没有证实当前小程序caller，暂不转交；run-session旧缺行clear=0和C04时间单调保留，暂不放锁。

EXTRA协调者读到公共控制后回ACK及实际E05/E06 child/分支SHA/路径，更新自己的QUEUE/CLAIMS/BACKLOG，原E01–04源锁保持；无本地中转或一般再批准依赖。全部SOURCE_DELIVERED_UNVERIFIED/NOT_RUN，不合并部署。目标50与实际数分开披露，源码/回归条目数不等于已验收bug数。


## E01-18 / E04-14 精确追加（覆盖旧只方案状态）

C00已完整读取公共 ae4113d21e8983d295c68831983de0aa8f875b74 的E01-18和 1b69abff8dec5c4e5cf547a02c3acbcc220db621 的E04-14，以及当前factory/maintenance源和旧夹具影响。新增唯一写权见SOURCE_OWNERS：

- EXTRA6/E01：`chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/ClubEventOpsMapper.java`。仅新增同原维护kind3谓词的新OFFERED当前活动读取声明，与本队原service/XML一起最小修复；新hold前不可用return false，不撤销旧hold/不挡取消归还/不改WAITING策略。先逐调用核锁序，无法保持原activity→club→ticket顺序则交具体阻塞，不猜。end_date NULL/等于now沿kind3严格规则，不能外扩全部报名时间政策。手写mapper/mock确需改时继续精确报路径，不给生产fail-open。
- EXTRA6/E04：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/`下 SmsCouponRedemptionAuditTest.java、SmsCouponRedemptionStartTimeTest.java、SmsCouponRedemptionTransactionBoundaryTest.java、CouponLifecycleMybatisIntegrationTest.java。仅同步本队Service首读既有ForUpdate，原权限/时间/审计/零副作用和回滚断言保留。旧普通读后同线程独立撤销窗口已不可达，改撤销先提交→锁读，数量保持；新独立真库方法承担锁等待证据，仅写NOT_RUN。不得把H2/mock当MySQL锁证明。

E04-14只固定Service锁读到提交的当前history关联，不增加token身份版本，也不宣称签发/验签到锁读前窗口已修；旧owner/scope校验谓词不改。两项均SOURCE_DELIVERED_UNVERIFIED/NOT_RUN，无验证、部署、迁移或合并。完整公共源基线f9e2932ca4b14e541caba356d2c1b016332f199f；原EXTRA已交patch必须保留，不能全文件回灌CORE。


## 正式ACK与第二批精确fixture/接口转交

已主动读PR16 comment6195023061，EXTRA实际E05/E06 child/worktree/分支及findCurrent生产者提取42cd374d均登记JSON；可确认新增域开始源码工作。其他4个slot支援独占新证据/回归，不能称6个业务源作者同时改同一域。

追加EXTRA6/E01三旧fixture：ClubWaitlistServiceImplTest、ClubWaitlistPromotionMembershipRaceTransactionTest、ClubWaitlistTokenMybatisIntegrationTest（完整路径SOURCE_OWNERS）。前两只按原正常活动前提接新查询，原会员/库存/回滚断言不放宽；H2不冒称真MySQL活动锁。Token真库套件必须seed真实合法activity、日期覆盖原now边界，保留新query真实XML委托，不硬编码恒true。未运行。

追加EXTRA6/E02：CommunityPostMapper.java仅现有upsertNotificationPreferences的三个boolean→Boolean；CommunityPostServiceImplTest.java仅notificationPreferencesPatchMergesAndGovernanceCannotBeDisabled的必要fixture。原Service/XML仍本队source owner。E02-20 nullable PATCH按原合同：无预读、直接原请求nullable；INSERT COALESCE(param,1)，UPDATE COALESCE(原param,当前列)，不准VALUES(default)覆盖未给字段，治理恒开不变；同字段按DB写序，响应不承诺随后提交。保留旧mention/governance/保留字段断言，新独占真库并发回归NOT_RUN。接口表中grant不等于允许其他点赞请求身份新查询；E02-19/22须给精确公共packet/路径后另裁定兼容边界，不猜升级前已删除身份。

E04原4fixture及E01 Mapper.java已在2f050fd7389f36653a07f742ec7f698687a959ac生效，无需重复等批准。以上不新增API/DDL/依赖/权限/收费政策，无编译测试、迁移、部署或合并。
