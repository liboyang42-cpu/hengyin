#!/usr/bin/env python3
"""Coop T+7 存量重复入账风险预检（只读）。

旧 writer 把余额流水 event_id 写成 topicId，而不是 settlementId。若它在余额已加后、
结算 status 置 1 前失败，存量 status=0 会被修复后的定时任务再次捞起。因此必须在迁移前、
部署前和部署后重复运行本脚本；发现疑似行时 exit 1，干净时 exit 0。

用法：
  export CHENGYIN_DB_PASSWORD=...
  python3 scripts/audit_coop_payout_legacy.py

本地负控（不连任何外部数据库）：
  python3 scripts/audit_coop_payout_legacy.py --self-test
"""

import argparse
import io
import os
import sqlite3
import sys


AUDIT_SQL = """
SELECT settlement.id AS settlementId,
       settlement.merchant_id AS merchantId,
       settlement.topic_id AS topicId,
       settlement.amount AS amount,
       detail.id AS suspiciousBalanceDetailId,
       detail.create_time AS balanceDetailCreatedAt
  FROM coop_settlement settlement
  JOIN ums_member_balance_detail detail
    ON detail.event_type = 5
   AND detail.change_type = 1
   AND detail.del_flag = 0
   AND detail.event_id = settlement.topic_id
   AND detail.member_id = settlement.merchant_id
   AND detail.change_balance = settlement.amount
   AND detail.change_reason LIKE '商家核销分润 - 主题%'
 WHERE settlement.del_flag = 0
   AND settlement.status = 0
 ORDER BY settlement.id, detail.id
"""

HEADERS = (
    "settlementId",
    "merchantId",
    "topicId",
    "amount",
    "suspiciousBalanceDetailId",
    "balanceDetailCreatedAt",
)


def query_suspects(connection):
    cursor = connection.cursor()
    try:
        cursor.execute(AUDIT_SQL)
        return cursor.fetchall()
    finally:
        cursor.close()


def render_result(rows, output):
    if not rows:
        print("✓ 未发现 status=0 且疑似已被旧 writer 入账的 Coop 结算。", file=output)
        return 0

    print("✗ 发现 {} 条疑似存量重复入账风险；停止迁移/部署并逐行人工对账。".format(len(rows)),
          file=output)
    print("\t".join(HEADERS), file=output)
    for row in rows:
        print("\t".join("NULL" if value is None else str(value) for value in row), file=output)
    return 1


def run_self_test():
    raw = sqlite3.connect(":memory:")
    try:
        raw.executescript("""
            CREATE TABLE coop_settlement (
                id INTEGER PRIMARY KEY,
                topic_id INTEGER,
                merchant_id INTEGER,
                amount NUMERIC,
                status INTEGER,
                del_flag INTEGER
            );
            CREATE TABLE ums_member_balance_detail (
                id INTEGER PRIMARY KEY,
                member_id INTEGER,
                change_balance NUMERIC,
                change_reason TEXT,
                event_type INTEGER,
                event_id INTEGER,
                change_type INTEGER,
                del_flag INTEGER,
                create_time TEXT
            );
            INSERT INTO coop_settlement VALUES (700, 200, 300, 30, 0, 0);
        """)

        clean = query_suspects(raw)
        if clean:
            raise AssertionError("没有旧式流水时必须为干净态")

        raw.execute("""
            INSERT INTO ums_member_balance_detail
                (id, member_id, change_balance, change_reason, event_type, event_id,
                 change_type, del_flag, create_time)
            VALUES (900, 300, 30, '商家核销分润 - 主题200', 5, 200, 1, 0,
                    '2026-08-09 12:00:00')
        """)
        suspects = query_suspects(raw)
        if suspects != [(700, 300, 200, 30, 900, "2026-08-09 12:00:00")]:
            raise AssertionError("疑似旧流水必须输出完整定位字段：{}".format(suspects))
        captured = io.StringIO()
        if render_result(suspects, captured) != 1:
            raise AssertionError("发现疑似行时必须非 0")
        for expected in HEADERS + ("700", "900", "2026-08-09 12:00:00"):
            if expected not in captured.getvalue():
                raise AssertionError("输出缺少 {}".format(expected))
        print(captured.getvalue().rstrip())

        raw.execute("UPDATE coop_settlement SET status=1 WHERE id=700")
        clean_again = query_suspects(raw)
        captured = io.StringIO()
        if clean_again or render_result(clean_again, captured) != 0:
            raise AssertionError("已打款结算不得继续报疑似，干净态必须 exit 0")
        print(captured.getvalue().rstrip())
    finally:
        raw.close()

    print("SELF-TEST PASS: clean=exit0, injected legacy pending payout=exit1, paid=exit0")
    return 0


def connect_production(config_path):
    skill_scripts = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ci")
    if not os.path.isdir(skill_scripts):
        raise RuntimeError("找不到 chengyin-db-migrate 脚本目录：{}".format(skill_scripts))
    sys.path.insert(0, skill_scripts)
    from run_migration import DEFAULT_CONFIG, connect  # pylint: disable=import-outside-toplevel
    return connect(config_path or DEFAULT_CONFIG)


def main(argv=None):
    parser = argparse.ArgumentParser(description="只读检查 Coop T+7 存量疑似重复入账")
    parser.add_argument("--self-test", action="store_true", help="仅跑本地 SQLite 正负控，不连接外部数据库")
    parser.add_argument(
        "--config",
        default=None,
        help="交给 chengyin-db-migrate connector 的 datasource 配置路径",
    )
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()

    connection, database = connect_production(args.config)
    try:
        cursor = connection.cursor()
        try:
            cursor.execute("START TRANSACTION READ ONLY")
        finally:
            cursor.close()
        rows = query_suspects(connection)
        print("只读检查库：{}".format(database))
        return render_result(rows, sys.stdout)
    finally:
        try:
            connection.rollback()
        finally:
            connection.close()


if __name__ == "__main__":
    sys.exit(main())
