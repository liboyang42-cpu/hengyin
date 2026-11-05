#!/usr/bin/env python3
"""Offline check of the version-1 export in k10-theme-check.md; never connects to a DB."""
import argparse
import copy
import json
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def check(data):
    issues = []

    def add(level, where, message):
        issues.append(dict(level=level, path=where, message=message))

    def parsed(value, where, kind=dict):
        if value is None or value == "":
            return kind()
        try:
            result = json.loads(value) if isinstance(value, str) else value
            if not isinstance(result, kind):
                raise ValueError()
            return result
        except (ValueError, TypeError):
            add("BLOCKER", where, "JSON 格式或类型错误")
            return kind()

    def media(value, where):
        if isinstance(value, list):
            for i, item in enumerate(value):
                media(item, f"{where}[{i}]")
        elif isinstance(value, dict):
            if value.get("enabled") is False:
                return
            if value.get("type") in ("audio", "video", "image") and not value.get("url"):
                add("BLOCKER", where, "媒体块缺少地址")
            for key, item in value.items():
                path = f"{where}.{key}"
                if key in ("url", "audioUrl", "videoUrl", "questionAudio") and item:
                    if not isinstance(item, str):
                        add("BLOCKER", path, "媒体地址不是字符串")
                        continue
                    url = urlsplit(item)
                    if url.scheme != "https" or not url.netloc:
                        add("REVIEW", path, "不是完整 HTTPS 媒体地址，需确认客户端可读")
                    if set(parse_qs(url.query)) & {"Expires", "Signature", "x-oss-signature", "x-oss-expires"}:
                        add("REVIEW", path, "可能是会过期的签名地址")
                media(item, path)

    def graph(ids, start, terminals, edges, where):
        ids = [str(i) for i in ids]
        start = str(start)
        terminals = {str(i) for i in terminals}
        known = set(ids)
        if len(ids) != len(known):
            add("BLOCKER", where, "编号重复")
        if start not in known or not terminals or not terminals <= known:
            add("BLOCKER", where, "起点或终点不存在")
        for a, b in edges:
            if a not in known or b not in known:
                add("BLOCKER", where, "连接指向不存在的节点")

        def reachable(seeds, links):
            found, todo = set(seeds), list(seeds)
            while todo:
                current = todo.pop()
                for a, b in links:
                    if a == current and b not in found:
                        found.add(b)
                        todo.append(b)
            return found

        reached = reachable({start}, edges)
        exits = reachable(terminals, [(b, a) for a, b in edges])
        for item in sorted(known - reached):
            add("BLOCKER", where, f"节点 {item} 从起点不可达")
        for item in sorted((known & reached) - exits):
            add("BLOCKER", where, f"节点 {item} 没有通向终点的路径")

    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        raise ValueError("需要 k10-theme-check.md 定义的 schemaVersion=1 导出")
    topic = parsed(data.get("topic"), "topic")
    chapters = parsed(data.get("chapters"), "chapters", list)
    nodes = parsed(data.get("nodes"), "nodes", list)
    if not topic.get("id") or not chapters or not nodes:
        add("BLOCKER", "export", "主题、章节或站点缺失；先检查导出是否完整")
    if any(not isinstance(row, dict) for row in chapters + nodes):
        raise ValueError("章节和站点必须是对象数组")
    chapter_ids = {str(row.get("id")) for row in chapters}
    node_ids = {str(row.get("id")) for row in nodes}
    if len(chapter_ids) != len(chapters) or len(node_ids) != len(nodes):
        add("BLOCKER", "export", "章节或站点编号重复")
    story = parsed(topic.get("journeyStoryJson"), "topic.journeyStoryJson")
    opening = story.get("openingChapterId")
    if opening is None:
        add("MISSING", "opening", "未配置开局章")
    elif str(opening) not in chapter_ids:
        add("BLOCKER", "opening", "开局章不存在")
    ending = parsed(story.get("ending"), "ending")
    endings = parsed(ending.get("endings"), "ending.endings", list)
    if not endings:
        add("MISSING", "ending", "未配置结局清单")
    elif sum(row.get("fallback") is True for row in endings) != 1:
        add("BLOCKER", "ending", "必须恰好有一个默认结局")
    for row in endings:
        if row.get("chapterId") is not None and str(row["chapterId"]) not in chapter_ids:
            add("BLOCKER", "ending", "结局章不存在")
    if not topic.get("audioUrl"):
        add("MISSING", "topic.audioUrl", "未配置主题背景音乐")
    media(topic, "topic")
    for chapter in chapters:
        where = f"chapter[{chapter.get('id')}]"
        media(chapter, where)
        blocks = parsed(chapter.get("blocks"), where + ".blocks", list)
        media(blocks, where + ".blocks")
        for block in blocks:
            if block.get("type") == "node" and str(block.get("nodeId")) not in node_ids:
                add("BLOCKER", where, "故事块引用的站点不存在")
    for node in nodes:
        where = f"node[{node.get('id')}]"
        if str(node.get("chapterId")) not in chapter_ids:
            add("BLOCKER", where, "站点所属章节不存在")
        dependency = node.get("unlockAfterNodeId")
        if dependency and (str(dependency) not in node_ids or str(dependency) == str(node.get("id"))):
            add("BLOCKER", where, "前置站点缺失或引用自己")
        by_id = {str(row.get("id")): row for row in nodes}
        seen, current = set(), node
        while current and current.get("unlockAfterNodeId"):
            key = str(current.get("id"))
            if key in seen:
                add("BLOCKER", where, "前置站点循环依赖，没有可开始的站点")
                break
            seen.add(key)
            current = by_id.get(str(current["unlockAfterNodeId"]))
        template = parsed(node.get("template"), where + ".template")
        if not template.get("id"):
            add("BLOCKER", where, "站点没有可读取的玩法模板")
            continue
        advanced = parsed(template.get("advancedConfigJson"), where + ".advanced")
        if template.get("validationMethod") is None:
            add("REVIEW", where, "未提供完成方式，不能确认本站有提交入口")
        media(template, where + ".template")
        media(advanced, where + ".advanced")
        media(parsed(template.get("storyJson"), where + ".story", list), where + ".story")
        branch = parsed(advanced.get("branch"), where + ".branch")
        if branch.get("enabled"):
            steps = parsed(branch.get("steps"), where + ".branch.steps", list)
            graph([s.get("id") for s in steps], branch.get("startStepId"),
                  [s.get("id") for s in steps if s.get("terminal")],
                  [(str(s.get("id")), str(o.get("nextStepId"))) for s in steps
                   if not s.get("terminal") for o in s.get("options", [])], where + ".branch")
    if topic.get("routeMode") == "BRANCH_GRAPH":
        route = parsed(topic.get("publishedRouteGraphJson"), "route")
        if not route:
            add("BLOCKER", "route", "缺少已发布路线图；不拿草稿冒充玩家配置")
        else:
            graph(node_ids, route.get("startNodeId"), route.get("terminalNodeIds", []),
                  [(str(e.get("fromNodeId")), str(e.get("toNodeId"))) for e in route.get("edges", [])], "route")
            add("REVIEW", "route", "仅检查拓扑；条件、资格、随机结果和冻结订单仍需实跑")
    return dict(topicId=topic.get("id"), status="STATIC_FINDINGS" if issues else "STATIC_NO_STRUCTURAL_FINDINGS",
                issues=issues, unverified=["媒体实际可读、解码、大小和预加载", "每站实际完成、GPS、拍照与扫码",
                "开场和结尾视频、各分支条件、彩蛋命中与错过", "冻结订单配置、音频切换、四种外观和手机听感"])


def selftest():
    good = dict(schemaVersion=1, topic=dict(id=1, audioUrl="https://example.invalid/bgm.mp3",
        journeyStoryJson=dict(openingChapterId=10, ending=dict(endings=[dict(fallback=True, chapterId=10)]))),
        chapters=[dict(id=10, blocks=[dict(type="node", nodeId=20)])],
        nodes=[dict(id=20, chapterId=10, template=dict(id=30, validationMethod=0, advancedConfigJson={}))])
    assert not check(good)["issues"]
    bad = copy.deepcopy(good); bad["chapters"][0]["blocks"].append(dict(type="audio", url=""))
    assert any(i["message"] == "媒体块缺少地址" for i in check(bad)["issues"])
    bad = copy.deepcopy(good); bad["nodes"][0]["template"]["advancedConfigJson"] = dict(branch=dict(enabled=True,
        startStepId="a", steps=[dict(id="a", options=[dict(nextStepId="a")]), dict(id="end", terminal=True)]))
    findings = check(bad)["issues"]
    assert any("不可达" in i["message"] for i in findings)
    assert any("没有通向终点" in i["message"] for i in findings)
    bad = copy.deepcopy(good); bad["topic"]["routeMode"] = "BRANCH_GRAPH"
    assert any("已发布路线图" in i["message"] for i in check(bad)["issues"])
    bad = copy.deepcopy(good); bad["nodes"][0]["template"]["advancedConfigJson"] = "{"
    assert any("JSON" in i["message"] for i in check(bad)["issues"])
    bad = copy.deepcopy(good); bad["nodes"][0]["unlockAfterNodeId"] = 21
    bad["nodes"].append(dict(id=21, chapterId=10, unlockAfterNodeId=20, template=dict(id=31, validationMethod=0)))
    assert any("循环依赖" in i["message"] for i in check(bad)["issues"])
    bad = copy.deepcopy(good); bad["nodes"][0]["template"] = None
    assert any("模板" in i["message"] for i in check(bad)["issues"])
    bad = copy.deepcopy(good); bad["topic"]["journeyStoryJson"]["ending"] = {}
    assert any(i["path"] == "ending" for i in check(bad)["issues"])
    bad = copy.deepcopy(good); bad["nodes"][0]["template"]["audioUrl"] = "https://example.invalid/a?Signature=never-print-this"
    report = check(bad)
    assert any("签名" in i["message"] for i in report["issues"])
    assert "never-print-this" not in json.dumps(report)
    print("k10-theme-check selftest: PASS (10 cases, including cycles, missing template/ending and URL redaction)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    try:
        if args.selftest:
            selftest()
        elif args.file:
            report = check(json.loads(Path(args.file).read_text()))
            print(json.dumps(report, ensure_ascii=False, indent=2))
            sys.exit(1 if report["issues"] else 0)
        else:
            parser.error("需要本地导出 JSON 路径或 --selftest")
    except (ValueError, TypeError, KeyError, AttributeError, OSError):
        print("导出无法检查：请核对文件、JSON 类型和 k10-theme-check.md 的导出格式；原始配置未输出", file=sys.stderr)
        sys.exit(2)
