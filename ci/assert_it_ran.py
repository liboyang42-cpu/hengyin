#!/usr/bin/env python3
"""回读观测:确认点名的 *IT 真的跑了,且一条都没被 skip。

为什么需要它(2026-08-14):
  · 类名以 IT 结尾,而本仓没有任何 pom 配 surefire/failsafe ⇒ `mvn test` 默认 include
    收不到它们。曾经 9 套一次都没执行过,而**其中 6 条真跑起来是红的**。
  · 它们内部原本还有 `Assumptions.assumeTrue(false, ...)`:库连不上就整套 skip,
    而 skip 在构建里算**绿** —— 一个永远不会红的测试比没有测试更危险。
  · mvn 命令返回 0 只是「我的动作被接受」,不等于「那几套真的跑了」。回执 ≠ 观测,
    所以这里从 surefire 的 TEST-*.xml 把结果读回来。

判红条件:报告缺失(压根没跑) / skipped != 0 / failures / errors。
"""
import glob
import os
import sys
# 解析的是**本次构建自己产出的** surefire 报告(本地路径、非外部输入),
# 与同仓 ci/funds_it_gate.py 口径一致,因此用标准库 ET 即可,不额外引 defusedxml。
import xml.etree.ElementTree as ET


def load(reports_dir):
    found = {}
    for path in glob.glob(os.path.join(reports_dir, "TEST-*.xml")):
        root = ET.parse(path).getroot()
        short = root.get("name", "").rsplit(".", 1)[-1]
        found[short] = {
            "tests": int(root.get("tests", "0")),
            "skipped": int(root.get("skipped", "0")),
            "failures": int(root.get("failures", "0")),
            "errors": int(root.get("errors", "0")),
        }
    return found


def enforce(reports_dir, expected_suites):
    found = load(reports_dir)
    problems = []
    for short in expected_suites:
        report = found.get(short)
        if report is None:
            problems.append(
                "%s: 无 surefire 报告 —— 该套没被执行(默认 include 收不到 *IT?),fail-closed" % short)
            continue
        if report["tests"] == 0:
            problems.append("%s: tests=0,等于没验证" % short)
        if report["skipped"] != 0:
            problems.append(
                "%s: skipped=%d(必须为 0)—— 又出现「连不上就跳过」,skip 算绿等于假绿"
                % (short, report["skipped"]))
        if report["failures"] or report["errors"]:
            problems.append("%s: failures=%d errors=%d"
                            % (short, report["failures"], report["errors"]))
    return problems


def main(argv):
    if len(argv) < 3:
        print("用法: assert_it_ran.py <surefire-reports 目录> <套件短名...>", file=sys.stderr)
        return 2
    problems = enforce(argv[1], argv[2:])
    if problems:
        print("❌ 真库 IT 观测未通过:")
        for p in problems:
            print("  - %s" % p)
        return 1
    print("✅ 真库 IT 观测通过:%d 套均真跑、skipped=0、无错无失败。" % (len(argv) - 2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
