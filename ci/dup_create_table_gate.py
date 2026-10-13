#!/usr/bin/env python3
"""同一个测试文件里,同一张表不许被 CREATE 两次。

★ 为什么有这道闸(2026-08-18 实事):
   PR #739 给 RegistrationFundsMybatisIntegrationTest 的 @BeforeEach 补了
   `CREATE TABLE cms_topic (id, product_type)`;PR #741 为票源冻结**另外**补了
   `CREATE TABLE cms_topic (id, club_id, selling_started_at, sale_end_at)`。
   两条落在语句数组的不同位置 —— **git 不认为冲突**,两边单独跑都绿,
   只有 `refs/pull/741/merge` 的合并结果红:同一批 DDL 建了两次同名表,
   第二条必报 "Table 'cms_topic' already exists",整套 60 条全 ERROR。

   这类缺陷的特征是:**两个作者各自都没错,合并也没冲突,只有合并结果是坏的。**
   人在 review 单边 diff 时看不见它,所以只能靠这道闸。

用法:
    python3 ci/dup_create_table_gate.py [根目录...]        # 默认扫 chengyinhub-admin/src/test
    python3 ci/dup_create_table_gate.py --selftest         # 负控:必须能抓出构造出来的重复
退出码:0 干净 / 1 发现重复 / 2 自检失败。
"""
import os
import re
import sys

# 只认「建表」这一种写法;`cms_topic_node` 不会被 `cms_topic` 误伤,因为表名按
# 完整标识符取(后面必须是空白或左括号)。
CREATE_RE = re.compile(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s*\(',
                       re.IGNORECASE)
# DROP 也要认:同一张表「建 → 丢 → 再建」是**正常**的(一个文件里两个独立 setup
# 各自重置 schema),不能判红。真正坏的是「建 → 再建」中间没有 DROP。
DROP_RE = re.compile(r'DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?',
                     re.IGNORECASE)
TOKEN_RE = re.compile(CREATE_RE.pattern + '|' + DROP_RE.pattern, re.IGNORECASE)

DEFAULT_ROOTS = ['chengyinhub-admin/src/test']

# ★ 显式豁免:只给「职责就是把撞名造出来」的文件。
#   本闸的判据是「同一文件里同名表建两次 ⇒ 合并结果会撞」,它对**同一个库**成立;
#   而类间隔离契约那两份文件恰恰是在**两个不同的库**里各建一次同名表,
#   用来证明「共用库会撞、一类一库不会撞」—— 它们要是不撞,那两条负控就白写了。
#   ⚠️ 豁免不是逃生口:key 必须是精确相对路径,value 必须写清为什么;
#   自检里有一条负控盯着「豁免只对登记过的文件生效」。
EXEMPT = {
    'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/MybatisItSchemaIsolationIT.java':
        '类间隔离契约:两次 CREATE 落在两个不同的库(共用 suite vs 独立 suite),'
        '第一条用例断言的就是「共用时第二条必须报 already exists」。',
    'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MysqlItSchemaIsolationTest.java':
        '同上,资金 IT 家族那一份(2026-08-19 随 #741 一起进来)。',
}


def is_exempt(path):
    """路径归一到仓库根相对,再比对登记表;登记表里没有的一律不豁免。"""
    normalized = os.path.normpath(path).replace(os.sep, '/')
    return normalized in EXEMPT


def scan_text(text):
    """返回 {表名: 未经 DROP 的重复建表次数}。

    按出现顺序走一遍:CREATE 把表标成「在场」,DROP 把它清掉。
    对已经「在场」的表再 CREATE = 违规(合并结果里必然报 already exists)。
    「建 → 丢 → 再建」不算 —— 那是同一文件里两个独立 setup 各自重置,正常写法。
    """
    live = set()
    bad = {}
    for m in TOKEN_RE.finditer(text):
        created, dropped = m.group(1), m.group(2)
        if created:
            name = created.lower()
            if name in live:
                bad[name] = bad.get(name, 1) + 1
            live.add(name)
        elif dropped:
            live.discard(dropped.lower())
    return bad


def scan_repo(roots):
    hits = []
    for root in roots:
        for dirpath, _dirnames, filenames in os.walk(root):
            for fn in filenames:
                if not fn.endswith(('.java', '.sql')):
                    continue
                path = os.path.join(dirpath, fn)
                if is_exempt(path):
                    continue
                with open(path, encoding='utf-8', errors='replace') as fh:
                    dups = scan_text(fh.read())
                for table, n in sorted(dups.items()):
                    hits.append((path, table, n))
    return hits


def selftest():
    """负控:构造样本必须被抓到,且不许误伤。"""
    # ① #741 合并结果那种:同一批 DDL 里建了两次,中间没有 DROP。
    bad = 'execute("CREATE TABLE cms_topic (id BIGINT)", "CREATE TABLE cms_topic (id BIGINT, x INT)");'
    # ② 合法:两个独立 setup 各自 drop 后重建(TopicTransferToClubTest 就是这样)。
    good_drop_recreate = ('jdbc.execute("drop table if exists cms_topic");'
                          'jdbc.execute("create table cms_topic (id bigint)");'
                          'jdbc.execute("drop table if exists cms_topic");'
                          'jdbc.execute("create table cms_topic (id bigint, club_id bigint)");')
    good_similar = 'execute("CREATE TABLE cms_topic (id BIGINT)", "CREATE TABLE cms_topic_node (id BIGINT)");'
    bad_backtick = 'CREATE TABLE `cms_topic` (id BIGINT); CREATE TABLE cms_topic (id BIGINT);'
    failures = []

    if 'cms_topic' not in scan_text(bad):
        failures.append('同批内两次 CREATE 同名表(#741 合并那种)—— 没抓到')
    if scan_text(good_drop_recreate):
        failures.append('「建→丢→再建」被误判成违规 —— 那是正常的独立 setup')
    if scan_text(good_similar):
        failures.append('cms_topic 与 cms_topic_node 被误判为同一张表')
    if 'cms_topic' not in scan_text(bad_backtick):
        failures.append('反引号与裸表名没有归一为同一张表')
    # ★ 豁免的负控:登记过的文件放过,没登记的照旧抓 —— 否则 EXEMPT 就成了新的逃生口。
    registered = next(iter(EXEMPT))
    if not is_exempt(registered):
        failures.append('登记过的豁免文件没有被放过:' + registered)
    if is_exempt(registered.replace('IsolationIT.java', 'IsolationITNotRegistered.java')):
        failures.append('没登记的文件也被豁免了 —— 豁免必须精确到路径')
    if any(not reason.strip() for reason in EXEMPT.values()):
        failures.append('有豁免项没写理由')

    if failures:
        for f in failures:
            print('自检失败: ' + f)
        return 2
    print('自检通过:抓得住「建→再建」、放得过「建→丢→再建」、不误伤同前缀表名、反引号已归一。')
    return 0


def main(argv):
    if '--selftest' in argv:
        return selftest()
    roots = [a for a in argv[1:] if not a.startswith('-')] or DEFAULT_ROOTS
    roots = [r for r in roots if os.path.isdir(r)]
    if not roots:
        print('没有可扫描的目录(检查工作目录);判红而不是跳过。')
        return 1

    hits = scan_repo(roots)
    if not hits:
        print('通过:%s 下没有同一文件内重复建同名表。' % ', '.join(roots))
        return 0

    print('发现同一文件内重复 CREATE 同名表 —— 合并结果会报 "already exists":')
    for path, table, n in hits:
        print('  %s  表 `%s` 被建了 %d 次' % (path, table, n))
    print('')
    print('处置:把多条合成一条、列取并集,并在语句旁注明「全仓只能有这一条」,')
    print('     让下次从任一侧加列的人知道要合并而不是新增。')
    return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
