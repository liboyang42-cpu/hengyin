# qx-final7 结果：发布迁移演练（rehearsal）转绿

## 一、结论
- PR #779（launch/questo-20261110）上 `Release migration rehearsal / rehearsal` 在提交 b547919c 上已 success（run 43078323150）。
- 本车道上次中断时的 b220e2e07 从未推送；其四项修复已由分支上的 b547919c 等价落地，本次未重复提交代码，避免冲突。

## 二、b547919c 已覆盖的四项发现
1. 基线 unexpected（wildlife / shelf 两项 DATA_PREVIEW）：合成行按 10-29 无种子基线对齐。
2. 20261101_stagger_sys_job_seconds：sys_job 合成行改用生产编号，迁移按写死 job_id 真正执行。
3. 20261027_template_adoption_receipt 预检：接受 20261107 落地后的表形状；stagger 的 expectedAfterApply 改为 APPLIED；pack 与 gen_release_set.py 保持一致。
4. 二次重跑漂移：theme_categories、settlement_rule 仅对 update_time 列声明 rerunKnownDelta，其余断言未放宽。

## 三、验证与限制
- 云端 CI：rehearsal job 全步骤 success（MySQL 8.0.45 一次性实例，应用、重跑零变更、预检回读）。
- 本地未跑：本沙箱无 Docker / mysqld，且仓库规则禁止本地起数据库，故没有本地完整演练输出；以上以 CI 结果为准。
- 未改 ci/architecture/*、ci.yml、既有迁移；未合并 master、未标记 #779 ready、未触生产。

STATUS: DONE（rehearsal 在 CI 上已绿；本地演练因环境限制未跑）
