#!/usr/bin/env python3
"""
拓展人激活赏金对账器 —— 只读,不改库。

为什么要有它
------------
赏金这条链路上,「库里说发了」和「钱真的动了」是两件事,而**它们不一致时不会炸、不会报错、
零告警**。这正是本轨要消灭的病(旧的 inviter 佣金挂在 sharing_amount 这笔死账上,至今没人
能回答「谁因为什么拿了多少」)。代码 review 读 diff 看不见这种落差,单测 mock 掉 mapper 更
看不见 —— **只有连库对账能看见**。

项目约定 的总纲「回执 ≠ 观测」:CI 绿 / PR 合了 / jar 部署了,都只证明我的动作被接受,
不证明库里的状态是我要的样子。这个脚本就是这条链路末端「从对面读回来」的那次观测。

查七类落差
----------
  1. 已发放却无入账流水     ★最重:记录说发了、余额没动 = 假账
  2. 已追回却无冲销流水     ★同上,反方向
  3. 流水金额与 bounty_amount 对不上
  4. 孤儿流水(event_type=91 却没有对应赏金记录)
  5. 封顶被突破(单拓展人已发放 > 20 家)
  6. 超窗仍卡在待激活(见下方「已知设计缺口」)
  7. 拓展人余额被追成负数(挂账抵扣依赖收付通、尚未到位,只能靠这里暴露)

已知设计缺口(第 6 类不是 bug,是刻意的取舍,别照着「修」)
------------------------------------------------------------
markExpiredIfPending 只在**核销发生时**触发。商家超窗后如果再不核销,这条记录就永远停在
status=0。它不影响钱(status=0 永远发不出去),只是脏数据。要收干净得加定时任务,而
项目约定 明说「新增 @Component 任务必须自带注册 SQL,否则 jar 部署了也永不执行且零告警」
—— 为一堆无害的脏数据引入一个会静默失效的任务,不划算。所以这里只报数、不修。

用法
----
  export CHENGYIN_DB_PASSWORD=...
  python3 scripts/audit_referral_bounty.py

exit 0 = 无落差;exit 1 = 有落差。

负控实测(2026-07-17,生产库)
-----------------------------
「只会打勾的对账器」证明不了任何事 —— 我自己就写过一个 Redis 全挂时仍无条件回显
「⇒ 场景②坐实」的验证脚本。所以这个脚本被真实打红过一次:
  · 干净态 ⇒ ✓ exit 0
  · 注入 status=1 却无流水的记录 + event_id 悬空的孤儿流水
    ⇒ ✗ exit 1,如实报出 [已发放无流水] / [金额对不上] / [孤儿流水] 三处
  · 删除注入数据、回读两表均归 0 ⇒ ✓ exit 0
"""
import os
import sys

SKILL_SCRIPTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ci")
if not os.path.isdir(SKILL_SCRIPTS):
    sys.exit(f"找不到 chengyin-db-migrate 的脚本目录:{SKILL_SCRIPTS}\n"
             "本脚本靠它连库(生产 3306 不对公网开放,必须走它的 ssh 隧道)。")
sys.path.insert(0, SKILL_SCRIPTS)
from run_migration import DEFAULT_CONFIG, connect  # noqa: E402

EVENT_TYPE = 91          # 与 MerchantReferralBountyService.EVENT_TYPE_REFERRAL_BOUNTY 对齐
INVITER_CAP = 20         # 与 MerchantReferralBountyService.INVITER_CAP 对齐


def q(cur, sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()


def main():
    conn, db = connect(DEFAULT_CONFIG)
    findings = []
    try:
        with conn.cursor() as cur:
            exists = q(cur, """
                SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = %s AND TABLE_NAME = 'merchant_referral_bounty'
            """, (db,))[0][0]
            if not exists:
                # 迁移没落库。这不是「没有落差」—— 是整个功能静默不存在。
                print("✗ merchant_referral_bounty 表不存在 —— migration_merchant_referral_bounty.sql 没跑。")
                print("  代码部署了也不会报错,赏金只是**静默地根本不发**。先落库再说。")
                return 1

            total, paid, clawed, pending, expired = q(cur, """
                SELECT COUNT(*),
                       SUM(status = 1), SUM(status = 2), SUM(status = 0), SUM(status = 3)
                  FROM merchant_referral_bounty WHERE del_flag = '0'
            """)[0]
            print(f"赏金记录 {total} 条:已发放 {paid or 0} / 已追回 {clawed or 0} / "
                  f"待激活 {pending or 0} / 已失效 {expired or 0}")

            # ① 已发放却无入账流水 —— 记录说发了、余额没动
            rows = q(cur, """
                SELECT b.id, b.inviter_member_id, b.bounty_amount
                  FROM merchant_referral_bounty b
                 WHERE b.status = 1 AND b.del_flag = '0'
                   AND NOT EXISTS (
                       SELECT 1 FROM ums_member_balance_detail d
                        WHERE d.event_type = %s AND d.event_id = b.id
                          AND d.change_balance > 0)
            """, (EVENT_TYPE,))
            for bid, inviter, amt in rows:
                findings.append(f"[已发放无流水] bounty#{bid} inviter={inviter} 金额={amt} "
                                f"—— 库里记为已发放,但没有入账流水。钱可能压根没到账。")

            # ② 已追回却无冲销流水
            rows = q(cur, """
                SELECT b.id, b.inviter_member_id, b.bounty_amount
                  FROM merchant_referral_bounty b
                 WHERE b.status = 2 AND b.del_flag = '0'
                   AND NOT EXISTS (
                       SELECT 1 FROM ums_member_balance_detail d
                        WHERE d.event_type = %s AND d.event_id = b.id
                          AND d.change_balance < 0)
            """, (EVENT_TYPE,))
            for bid, inviter, amt in rows:
                findings.append(f"[已追回无冲销] bounty#{bid} inviter={inviter} 金额={amt} "
                                f"—— 库里记为已追回,但没有负向流水。钱没真扣回来。")

            # ③ 流水净额与记录状态对不上
            rows = q(cur, """
                SELECT b.id, b.status, b.bounty_amount, COALESCE(SUM(d.change_balance), 0)
                  FROM merchant_referral_bounty b
                  LEFT JOIN ums_member_balance_detail d
                         ON d.event_type = %s AND d.event_id = b.id
                 WHERE b.del_flag = '0' AND b.status IN (1, 2)
                 GROUP BY b.id, b.status, b.bounty_amount
            """, (EVENT_TYPE,))
            for bid, status, amt, net in rows:
                expect = amt if status == 1 else 0      # 已发放净额=金额;已追回净额=0(发+追抵消)
                if amt is not None and net != expect:
                    findings.append(f"[金额对不上] bounty#{bid} status={status} 记录金额={amt} "
                                    f"流水净额={net} 期望={expect}")

            # ④ 孤儿流水
            rows = q(cur, """
                SELECT d.id, d.member_id, d.change_balance, d.event_id
                  FROM ums_member_balance_detail d
                 WHERE d.event_type = %s
                   AND NOT EXISTS (SELECT 1 FROM merchant_referral_bounty b WHERE b.id = d.event_id)
            """, (EVENT_TYPE,))
            for did, member, amt, eid in rows:
                findings.append(f"[孤儿流水] detail#{did} member={member} 金额={amt} "
                                f"event_id={eid} 查无此赏金记录")

            # ⑤ 封顶被突破
            rows = q(cur, """
                SELECT inviter_member_id, COUNT(*) c
                  FROM merchant_referral_bounty
                 WHERE status = 1 AND del_flag = '0'
                 GROUP BY inviter_member_id HAVING c > %s
            """, (INVITER_CAP,))
            for inviter, c in rows:
                findings.append(f"[封顶突破] inviter={inviter} 已发放 {c} 家 > 封顶 {INVITER_CAP}")

            # ⑦ 被追成负余额(挂账抵扣依赖收付通、尚未到位 ⇒ 只能靠这里暴露)
            rows = q(cur, """
                SELECT DISTINCT m.id, m.balance
                  FROM ums_member m
                  JOIN merchant_referral_bounty b ON b.inviter_member_id = m.id
                 WHERE b.status = 2 AND m.balance < 0
            """)
            for mid, bal in rows:
                findings.append(f"[负余额] member={mid} 余额={bal} —— 赏金追回时钱已被提走。"
                                f"挂账抵后续合作款(定稿 D14)依赖收付通、尚未到位,需人工跟进。")

            # ⑥ 超窗仍卡在待激活 —— 已知设计缺口,只报数不算落差
            stuck = q(cur, """
                SELECT COUNT(*) FROM merchant_referral_bounty
                 WHERE status = 0 AND window_expires_at < NOW() AND del_flag = '0'
            """)[0][0]
            if stuck:
                print(f"提示:{stuck} 条超窗仍停在待激活(status=0)。这是已知取舍,不影响钱 "
                      f"—— status=0 永远发不出去;markExpiredIfPending 只在核销时触发,"
                      f"商家超窗后不再核销就收不干净。详见本文件头注释。")

            # 冻结敞口:当前有多少钱因 48h 冻结提不出去(不是落差,是运营该知道的数)
            frozen = q(cur, """
                SELECT COALESCE(SUM(bounty_amount), 0), COUNT(*)
                  FROM merchant_referral_bounty
                 WHERE status = 1 AND frozen_until > NOW() AND del_flag = '0'
            """)[0]
            if frozen[1]:
                print(f"提示:{frozen[1]} 笔赏金合计 {frozen[0]} 元仍在 48h 冻结期内,暂不可提现。")
    finally:
        conn.close()

    if findings:
        print(f"\n✗ 发现 {len(findings)} 处落差:")
        for f in findings:
            print("  " + f)
        return 1
    print("\n✓ 无落差:每一笔「库里说发了/追了」都有对应的钱真的动了。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
