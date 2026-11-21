import re,glob,os,collections,json
# target -> (new cron, frequency note, heavy?)
plan = [
 # every minute
 ("roamPresenceRevokeAuditTask.run()",          "1 * * * * ?"),
 ("clubEventOpsTask.run()",                     "7 * * * * ?"),
 ("clubPostMediaReviewTask.run()",              "13 * * * * ?"),
 ("communityPostMaintenanceTask.run()",         "19 * * * * ?"),
 ("merchantCrmDeliveryRecoveryTask.run()",      "25 * * * * ?"),
 ("storeVerifyGrowthTask.run()",                "31 * * * * ?"),
 ("aiQuotaRecoveryTask.run()",                  "37 * * * * ?"),
 ("imNotificationDeliveryTask.deliverPending()","45 * * * * ?"),
 ("committedOfficialNoticeTask.deliverPending()","27 * * * * ?"),
 # 2 min
 ("withdrawTransferQueryTask.run()",            "43 1/2 * * * ?"),
 # 5 min
 ("metricsProbeTask.run()",                     "0 0/5 * * * ?"),
 ("orderCloseTask.closeExpired()",              "3 0/5 * * * ?"),
 ("officialEventTask.run()",                    "49 1/5 * * * ?"),
 ("merchantCommerceReconcileTask.reconcile()",  "55 2/5 * * * ?"),
 ("contentSafetyRecheckTask.run()",             "21 1/5 * * * ?"),
 ("platformPassDeliveryTask.run()",             "23 0/5 * * * ?"),
 ("channelRefundConsumptionTask.run()",         "39 3/5 * * * ?"),
 ("marketingRevenuePostingTask.run()",          "51 4/5 * * * ?"),
 # 10 min
 ("coopInviteExpireTask.run()",                 "11 3/10 * * * ?"),
 ("topicRecruitDeadlineTask.run()",             "17 4/10 * * * ?"),
 ("autoRefundTask.autoRefundUnformed()",        "29 6/10 * * * ?"),
 ("verificationQrcodeRepairTask.repairMissing()","35 7/10 * * * ?"),
 ("playTeamExpireTask.run()",                   "41 8/10 * * * ?"),
 ("roamRewardDeliveryTask.run()",               "47 9/10 * * * ?"),
 ("merchantAftercareTimeoutTask.run()",         "53 0/10 * * * ?"),
 ("orderCloseTask.reconcileExpiredPaying()",    "59 1/10 * * * ?"),
 ("ecommercePayoutTask.shareAndRetry()",        "9 5/10 * * * ?"),
 ("sensitiveContentRescanTask.run()",           "33 2/10 * * * ?"),
 # 15 min
 ("refundReconcileTask.reconcile()",            "5 11/15 * * * ?"),
 # 30 min
 ("auditBacklogMonitorTask.run()",              "15 12/30 * * * ?"),
 ("contentSearchProjectionTask.refresh()",      "57 13/30 * * * ?".replace("57","2")),
 ("roamHangoutExpireTask.run()",                "4 16/30 * * * ?"),
 ("roamRecallTask.run()",                       "6 17/30 * * * ?"),
 ("ecommercePayoutTask.syncOnboarding()",       "57 18/30 * * * ?"),
 # hourly
 ("settlementTask.run()",                       "8 14 * * * ?"),
 ("beforeStartNotifyTask.run()",                "10 0 * * * ?"),
 ("registrationAutoVerifyTask.run()",           "12 31 * * * ?"),
 # daily
 ("analyticsAggregationTask.aggregateRecentWindow()","14 10 0 * * ?"),
 ("autoSettlementTask.run()",                   "16 30 2 * * ?"),
 ("coopSettlementPayoutTask.run()",             "18 0 3 * * ?"),
 ("predictVoidExpiredTask.run()",               "20 2 3 * * ?"),
 ("settlementReconciliationTask.run()",         "22 10 3 * * ?"),
 ("clubSessionReconciliationTask.run()",        "24 20 3 * * ?"),
 ("recommendationScoreDailyTask.run()",         "26 25 3 * * ?"),
 ("dataRetentionCleanupTask.run()",             "28 35 3 * * ?"),
 ("withdrawReconciliationTask.run()",           "30 5 4 * * ?"),
 ("piiBackfillTask.run()",                      "32 15 4 * * ?"),
 ("wxTradeBillDownloadTask.download()",         "34 30 10 * * ?"),
]
secs=[c.split()[0] for _,c in plan]
assert len(plan)==48 and len(set(t for t,_ in plan))==48, len(plan)
dup=[s for s,n in collections.Counter(secs).items() if n>1]; assert not dup, dup
# known historical values per target
files=sorted(glob.glob('chengyinhub-admin/sql/*.sql'))+sorted(glob.glob('sql/*.sql'))
pat=re.compile(r"'((?:[0-9*/?,\-LW#A-Z]+\s+){5}[0-9*/?,\-LW#A-Z]+)'")
known=collections.defaultdict(set)
for f in files:
    if '20261115' in f: continue
    t=open(f,encoding='utf-8',errors='replace').read()
    for stmt in t.split(';'):
        if 'sys_job' not in stmt or 'sys_job_log' in stmt: continue
        tg=set(re.findall(r"'([a-zA-Z][\w]*\.[\w]+\([^']*\))'",stmt)); cr=pat.findall(stmt)
        for g in tg:
            for c in cr: known[g].add(c)
# 11-01 job_id stagger: original -> new values
st=open('chengyinhub-admin/sql/migration_20261101_stagger_sys_job_seconds.sql',encoding='utf-8').read()
orig2new=collections.defaultdict(set)
for new,old in re.findall(r"SET cron_expression = '([^']+)'.*?AND cron_expression = '([^']+)'",st): orig2new[old].add(new)
out=[]
for tgt,new in plan:
    vals=set(known[tgt])
    for v in list(vals): vals|=orig2new.get(v,set())
    vals.discard(new)
    out.append((tgt,new,sorted(vals)))
json.dump(out,open('/tmp/stagger-plan.json','w'),indent=1)
missing=set(known)-set(t for t,_ in plan)
print('targets in sql not in plan:',missing)
for o in out: print(o)

def family(c):
    f=c.split(); m,h=f[1],f[2]
    if m in ('*','*/1') or m.endswith('/1'): return 'm1'
    if '/' in m: return 'm'+m.split('/')[1]
    if h=='*': return 'h'
    return 'd'
lines=[]
lines.append("""-- R9 BE-28 定时任务全量错峰(2026-11-15)
-- 背景:20261101 按生产 job_id 错开了 23 个任务,但之后新加的任务(营销记账、推荐分、搜索投影、AI 配额回收、
--       自动核销、漫游召回等)仍在 0 秒起跑;03:00 / 03:20 / 04:00 还各有两个日任务同分同秒。
-- 做法:按 invoke_target(不依赖各环境不同的 job_id)给 48 个已知任务各分配一个**独一无二的秒位**(0..59 内不重复),
--       频率不变,只改秒位与分钟偏移 ⇒ 任意两个任务永远不会在同一秒起跑。
--       指标采样 metricsProbeTask 保留 0 秒(与 20261101 的口径一致)。
-- 只覆盖「仍是历史上某个默认/错峰值」的行;人工改过的表达式不动,文件末尾列出这些行供人工核对。
-- 生效:应用启动时 SysJobServiceImpl.init 按 sys_job 重建调度 —— 部署/重启前执行。可重复执行。
-- 计划真源 = 本文件;SysJobStaggerPlanTest 解析本文件,逐秒模拟 24 小时,断言没有两个任务同秒。
--       以后新增 sys_job 必须在新的错峰迁移里分配未用的秒位,否则该用例失败。
-- 回退:把每行 SET 的新值换回 IN (...) 中原来的值即可(只动 cron_expression)。
""")
lines.append("-- 计划表(SysJobStaggerPlanTest 解析这些行):")
for tgt,new,vals in out:
    lines.append("-- plan: %s = %s" % (tgt,new))
lines.append("-- retired: activityRecallTask.run()  (deleted by migration_20260717_recall_job_decommission.sql)")
lines.append("-- retired: analyticsAggregationTask.aggregateYesterday()  (paused, replaced by aggregateRecentWindow, 20260909)")
lines.append("")
for tgt,new,vals in out:
    fam=family(new)
    vals=[v for v in vals if family(v)==fam]
    cond = "cron_expression IN (" + ", ".join("'%s'"%v for v in vals) + ")" if vals else None
    if cond:
        lines.append("UPDATE sys_job SET cron_expression = '%s', update_time = NOW() WHERE invoke_target = '%s' AND %s;" % (new,tgt,cond))
    else:
        lines.append("-- %s already at '%s' (no prior default to replace)" % (tgt,new))
lines.append("""
-- 人工核对(只读):计划内任务若不是计划值,说明被人工改过,本迁移没有覆盖。
SELECT job_id, job_name, invoke_target, cron_expression AS current_cron FROM sys_job
 WHERE status = '0' AND invoke_target IN (%s)
   AND cron_expression NOT IN (%s);
""" % (", ".join("'%s'"%t for t,_,_ in out), ", ".join(sorted(set("'%s'"%n for _,n,_ in out)))))
lines.append("""-- 核对(只读,不作发布闸):计划内且启用的任务秒位是否两两不同。1 = 是;0 = 上面列出的人工改过的行与别的任务撞秒,请人工改。
-- 不设 runner assert:人工改过的表达式本迁移刻意不覆盖,不能因此卡住发布。
SELECT COUNT(*) = COUNT(DISTINCT SUBSTRING_INDEX(cron_expression, ' ', 1)) AS sys_job_seconds_unique
  FROM sys_job WHERE status = '0' AND invoke_target IN (%s);
""" % ", ".join("'%s'"%t for t,_,_ in out))
open('chengyinhub-admin/sql/migration_20261115_stagger_all_sys_jobs.sql','w',encoding='utf-8').write("\n".join(lines))
