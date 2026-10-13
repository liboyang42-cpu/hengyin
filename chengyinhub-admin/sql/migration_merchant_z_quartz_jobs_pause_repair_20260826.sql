-- runner: requires=transaction
-- 止血 repair：三条 Quartz 注册曾在旧 jar 不包含对应 bean 时提前启用。
-- 本文件只收敛持久态为 status='1'，并写入可追责的启用交接；它永不启用任务。
--
-- 可移植边界：job_id 是每个库自行分配的 surrogate key，不属于任务身份。
-- 本迁移只用三个精确 invoke_target 及各自的 job_name/job_group/cron/misfire/concurrent
-- 识别任务，因此可用于新库、灾备库以及 job_id 不同的合法环境。sys_job 使用的
-- utf8mb4_general_ci 会忽略大小写和尾随空格；所有身份/配置比较必须显式 CAST AS BINARY，
-- 与 Spring 大小写敏感的 bean 查找保持一致。
--
-- fail-closed 边界：
--   1. 三个 target 必须各且仅各一行，且精确匹配各自名称和固定 Quartz 配置；
--   2. target 重复、配置漂移、状态未知，或任一预期 job_name 出现在额外 target 上，
--      都在 UPDATE 前红并回滚；
--   3. UPDATE 只按 target 集合收敛，不读写任何固定 job_id；
--   4. 已是正确暂停态后重放，UPDATE 0 行也能通过 postimage 守卫且不刷新 update_time。
--
-- 生产执行计划（独立于 canonical SQL）：本次事故环境在执行前必须用新只读连接另行断言
-- 当前映射恰为 clubEventOpsTask=125、clubPostMediaReviewTask=126、
-- merchantCrmDeliveryRecoveryTask=127，且名称/组/cron/misfire/concurrent 与下方完全一致；
-- 任一 ID 或配置漂移都停止，不得为迁就现场去改本迁移的 target 身份合同。
--
-- 重要运行时边界：直接 UPDATE sys_job 只改持久态；当前 JVM 里已装载的 Quartz 调度器
-- 不会因这份 SQL 立即 pause。因此 COMMIT OK 和库表 status='1' 不能单独作为线上已止血证据。
-- 生产执行必须搭配调度器侧 pause（后台/API），或者 SQL 后受控重启旧 jar 使调度器重载暂停态；
-- 随后用新连接按三个 target exact-three 回读，并跨越 cron 至少一个周期观察无新增 bean-not-found。
--
-- 后续启用是另一个独立受控步骤：全部前置 schema 新连接回读通过，且生产新 jar
-- 自报 commit 等于本次 master SHA，sha256 指纹/唯一进程/启动时间和三个 Quartz bean
-- 存在性全部回读通过后，再取得新的独立授权。本 repair 不得被重用为启用授权。

CREATE TEMPORARY TABLE merchant_club_quartz_pause_guard (
  guard_name VARCHAR(64) NOT NULL,
  PRIMARY KEY (guard_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- preimage：target/name 并集必须恰好三行；三个 target 各自只能有一行且配置精确。
-- 将预期 job_name 也纳入并集，确保“同名但换 target”的额外注册不会被忽略。
-- runner: assert=rowcount-positive
INSERT INTO merchant_club_quartz_pause_guard (guard_name)
SELECT 'exact-three-preimage'
WHERE (
    SELECT COUNT(*)
    FROM sys_job
    WHERE CAST(invoke_target AS BINARY) IN (
            CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
            CAST('clubEventOpsTask.run()' AS BINARY),
            CAST('clubPostMediaReviewTask.run()' AS BINARY)
          )
       OR CAST(job_name AS BINARY) IN (
            CAST('商家CRM触达恢复' AS BINARY),
            CAST('俱乐部活动运营维护' AS BINARY),
            CAST('俱乐部动态图片送审恢复' AS BINARY)
          )
  ) = 3
  AND (
    SELECT COUNT(*)
    FROM sys_job
    WHERE CAST(invoke_target AS BINARY) IN (
      CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
      CAST('clubEventOpsTask.run()' AS BINARY),
      CAST('clubPostMediaReviewTask.run()' AS BINARY)
    )
  ) = 3
  AND (
    SELECT COUNT(DISTINCT CAST(invoke_target AS BINARY))
    FROM sys_job
    WHERE CAST(invoke_target AS BINARY) IN (
      CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
      CAST('clubEventOpsTask.run()' AS BINARY),
      CAST('clubPostMediaReviewTask.run()' AS BINARY)
    )
  ) = 3
  AND (
    SELECT COUNT(*)
    FROM sys_job
    WHERE CAST(job_group AS BINARY) = CAST('DEFAULT' AS BINARY)
      AND CAST(cron_expression AS BINARY) = CAST('0 * * * * ?' AS BINARY)
      AND CAST(misfire_policy AS BINARY) = CAST('3' AS BINARY)
      AND CAST(concurrent AS BINARY) = CAST('1' AS BINARY)
      AND CAST(status AS BINARY) IN (CAST('0' AS BINARY), CAST('1' AS BINARY))
      AND (
        (CAST(invoke_target AS BINARY)
            = CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY)
          AND CAST(job_name AS BINARY) = CAST('商家CRM触达恢复' AS BINARY))
        OR (CAST(invoke_target AS BINARY) = CAST('clubEventOpsTask.run()' AS BINARY)
          AND CAST(job_name AS BINARY) = CAST('俱乐部活动运营维护' AS BINARY))
        OR (CAST(invoke_target AS BINARY)
            = CAST('clubPostMediaReviewTask.run()' AS BINARY)
          AND CAST(job_name AS BINARY) = CAST('俱乐部动态图片送审恢复' AS BINARY))
      )
  ) = 3;

-- 只按 target 集合收敛；preimage 已证明集合中恰好三行且每行配置精确。
-- 可重放：状态与 remark 已正确时 WHERE 为假，不刷新 update_time。
UPDATE sys_job
SET status = '1',
    update_by = 'admin',
    update_time = NOW(),
    remark = CONCAT(
      CASE
        WHEN CAST(invoke_target AS BINARY)
            = CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY)
          THEN '每分钟恢复过期PROCESSING；version+token CAS；未知外部结果不自动重发'
        WHEN CAST(invoke_target AS BINARY) = CAST('clubEventOpsTask.run()' AS BINARY)
          THEN '每分钟维护候补到期/报名终态/晋级与站内通知失败项；CAS有界批处理'
        WHEN CAST(invoke_target AS BINARY)
            = CAST('clubPostMediaReviewTask.run()' AS BINARY)
          THEN '每分钟最多50条；运行25秒后不再启动下一条；瞬时故障本轮熔断；CAS lease可重入；持续积压告警'
      END,
      '；默认暂停；触发时机=全部前置schema新连接回读通过+生产jar自报commit等于本次master SHA+sha256指纹/唯一进程/启动时间回读通过+三个Quartz bean存在性回读通过，另行独立授权启用；负责人=城瘾发布负责人；到期日=2026-08-31（到期未启用必须复核，不得自动启用）'
    )
WHERE CAST(invoke_target AS BINARY) IN (
    CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
    CAST('clubEventOpsTask.run()' AS BINARY),
    CAST('clubPostMediaReviewTask.run()' AS BINARY)
  )
  AND (
    NOT (CAST(status AS BINARY) <=> CAST('1' AS BINARY))
    OR NOT (CAST(remark AS BINARY) <=> CAST(CONCAT(
      CASE
        WHEN CAST(invoke_target AS BINARY)
            = CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY)
          THEN '每分钟恢复过期PROCESSING；version+token CAS；未知外部结果不自动重发'
        WHEN CAST(invoke_target AS BINARY) = CAST('clubEventOpsTask.run()' AS BINARY)
          THEN '每分钟维护候补到期/报名终态/晋级与站内通知失败项；CAS有界批处理'
        WHEN CAST(invoke_target AS BINARY)
            = CAST('clubPostMediaReviewTask.run()' AS BINARY)
          THEN '每分钟最多50条；运行25秒后不再启动下一条；瞬时故障本轮熔断；CAS lease可重入；持续积压告警'
      END,
      '；默认暂停；触发时机=全部前置schema新连接回读通过+生产jar自报commit等于本次master SHA+sha256指纹/唯一进程/启动时间回读通过+三个Quartz bean存在性回读通过，另行独立授权启用；负责人=城瘾发布负责人；到期日=2026-08-31（到期未启用必须复核，不得自动启用）'
    ) AS BINARY))
  );

-- postimage：重放时也必须能插入守卫；不依赖 UPDATE affected rows。
-- target/name 并集仍须恰好三行，三行必须保持精确配置、暂停态和完整交接。
-- runner: assert=rowcount-positive
INSERT INTO merchant_club_quartz_pause_guard (guard_name)
SELECT 'exact-three-postimage'
WHERE (
    SELECT COUNT(*)
    FROM sys_job
    WHERE CAST(invoke_target AS BINARY) IN (
            CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
            CAST('clubEventOpsTask.run()' AS BINARY),
            CAST('clubPostMediaReviewTask.run()' AS BINARY)
          )
       OR CAST(job_name AS BINARY) IN (
            CAST('商家CRM触达恢复' AS BINARY),
            CAST('俱乐部活动运营维护' AS BINARY),
            CAST('俱乐部动态图片送审恢复' AS BINARY)
          )
  ) = 3
  AND (
    SELECT COUNT(*)
    FROM sys_job
    WHERE CAST(invoke_target AS BINARY) IN (
      CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
      CAST('clubEventOpsTask.run()' AS BINARY),
      CAST('clubPostMediaReviewTask.run()' AS BINARY)
    )
  ) = 3
  AND (
    SELECT COUNT(DISTINCT CAST(invoke_target AS BINARY))
    FROM sys_job
    WHERE CAST(invoke_target AS BINARY) IN (
      CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
      CAST('clubEventOpsTask.run()' AS BINARY),
      CAST('clubPostMediaReviewTask.run()' AS BINARY)
    )
  ) = 3
  AND (
    SELECT COUNT(*)
    FROM sys_job
    WHERE CAST(job_group AS BINARY) = CAST('DEFAULT' AS BINARY)
      AND CAST(cron_expression AS BINARY) = CAST('0 * * * * ?' AS BINARY)
      AND CAST(misfire_policy AS BINARY) = CAST('3' AS BINARY)
      AND CAST(concurrent AS BINARY) = CAST('1' AS BINARY)
      AND CAST(status AS BINARY) = CAST('1' AS BINARY)
      AND CAST(update_by AS BINARY) = CAST('admin' AS BINARY)
      AND update_time IS NOT NULL
      AND CAST(remark AS BINARY) <=> CAST(CONCAT(
        CASE
          WHEN CAST(invoke_target AS BINARY)
              = CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY)
            THEN '每分钟恢复过期PROCESSING；version+token CAS；未知外部结果不自动重发'
          WHEN CAST(invoke_target AS BINARY) = CAST('clubEventOpsTask.run()' AS BINARY)
            THEN '每分钟维护候补到期/报名终态/晋级与站内通知失败项；CAS有界批处理'
          WHEN CAST(invoke_target AS BINARY)
              = CAST('clubPostMediaReviewTask.run()' AS BINARY)
            THEN '每分钟最多50条；运行25秒后不再启动下一条；瞬时故障本轮熔断；CAS lease可重入；持续积压告警'
        END,
        '；默认暂停；触发时机=全部前置schema新连接回读通过+生产jar自报commit等于本次master SHA+sha256指纹/唯一进程/启动时间回读通过+三个Quartz bean存在性回读通过，另行独立授权启用；负责人=城瘾发布负责人；到期日=2026-08-31（到期未启用必须复核，不得自动启用）'
      ) AS BINARY)
      AND (
        (CAST(invoke_target AS BINARY)
            = CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY)
          AND CAST(job_name AS BINARY) = CAST('商家CRM触达恢复' AS BINARY))
        OR (CAST(invoke_target AS BINARY) = CAST('clubEventOpsTask.run()' AS BINARY)
          AND CAST(job_name AS BINARY) = CAST('俱乐部活动运营维护' AS BINARY))
        OR (CAST(invoke_target AS BINARY)
            = CAST('clubPostMediaReviewTask.run()' AS BINARY)
          AND CAST(job_name AS BINARY) = CAST('俱乐部动态图片送审恢复' AS BINARY))
      )
  ) = 3;

DROP TEMPORARY TABLE merchant_club_quartz_pause_guard;

-- COMMIT 后必须换新连接跑等价读回（这一段是运维合同，不由本事务自证）：
-- SELECT job_id, job_name, invoke_target, job_group, cron_expression,
--        misfire_policy, concurrent, status, update_by, update_time, remark
-- FROM sys_job
-- WHERE CAST(invoke_target AS BINARY) IN (
--         CAST('merchantCrmDeliveryRecoveryTask.run()' AS BINARY),
--         CAST('clubEventOpsTask.run()' AS BINARY),
--         CAST('clubPostMediaReviewTask.run()' AS BINARY))
--    OR CAST(job_name AS BINARY) IN (
--         CAST('商家CRM触达恢复' AS BINARY), CAST('俱乐部活动运营维护' AS BINARY),
--         CAST('俱乐部动态图片送审恢复' AS BINARY))
-- ORDER BY invoke_target, job_id;
