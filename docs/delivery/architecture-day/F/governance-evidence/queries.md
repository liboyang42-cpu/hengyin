# 只读查询与再现

本笔记只查询公共 immutable Git blob；不是新增 scanner、policy 或 CI gate。历史 6788 的原聚合保留在 historical-6788-evidence.json；它的 Markdown 命中结果只是当时搜索结果，现由 4a6e3cf 的完整规范取代，不能继续当缺规格或缺实现结论。

源代码观察固定为 07172e6fddf28f99ea4ad214b5c92cfe7608e441；规范固定为 4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e。查询复用已有公共 helper：gate 的 155c88ef52f7f75023de464c8a886c29d017cdba、import helper 的 02e69111a83d19d2ad53051a44c629d5e0a5a38a。后者仅用于只读查询，未因此接入 CI。

在自己独占的公共仓库工作树中准备上述 Git 对象。QUERY_VERIFIER_ROOT 指向冻结 155 工作树，QUERY_IMPORT_HELPER_ROOT 指向冻结 02e691 工作树，QUERY_OUTPUT_DIR 指向自己独占的临时输出目录。不要复用其他任务工作目录。变量不是凭据；不打印环境或网络配置。脚本仅写查询输出，不改公共源码。

```sh
# 在已有、remote 已确认是公共 liboyang42-cpu/hengyin 的 checkout 内读取对象：
git fetch --no-tags origin 07172e6fddf28f99ea4ad214b5c92cfe7608e441
git fetch --no-tags origin 4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e
# 两个 helper 工作树按上述完整 SHA 创建/核对；不要切换他人的分支。
# 设置 QUERY_VERIFIER_ROOT、QUERY_IMPORT_HELPER_ROOT、QUERY_OUTPUT_DIR 后，
# 将下列 Python 代码作为一次性 stdin 查询执行（PYTHONDONTWRITEBYTECODE=1）。
```

```python
import ast, collections, hashlib, importlib.util, json, os, pathlib, re, subprocess
ROOT = pathlib.Path(os.environ["QUERY_VERIFIER_ROOT"]).resolve()
OUT = pathlib.Path(os.environ["QUERY_OUTPUT_DIR"]).resolve()
SHA = "07172e6fddf28f99ea4ad214b5c92cfe7608e441"
SPEC_SHA = "4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e"
BASE = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module
g = load("f_frozen_gate", ROOT / "ci/architecture/gate.py")
dep_path = pathlib.Path(os.environ["QUERY_IMPORT_HELPER_ROOT"]).resolve() / "ci/architecture/dependencies.py"
dep = load("f_frozen_import_queries", dep_path)
git = lambda *args: subprocess.check_output(["git", "-C", str(ROOT), *args], text=True)
data = {"kind": "READ_ONLY_STATIC_QUERIES_NOT_NEW_GATE_OR_RUNTIME_PROOF",
        "source_sha": SHA, "comparison_sha": BASE,
        "specification_sha": SPEC_SHA, "specification_path": "docs/delivery/architecture-day/COORDINATOR/governance-scope.md",
        "helpers": {"gate": {"commit": git("rev-parse", "HEAD").strip(), "sha256": g.digest((ROOT/"ci/architecture/gate.py").read_bytes())},
                    "dependencies": {"commit": subprocess.check_output(["git","-C",str(dep.ROOT),"rev-parse","HEAD"],text=True).strip(), "sha256": g.digest(dep_path.read_bytes())}}}
di = dep.inventory(ROOT, SHA)
data["module_query"] = {"modules":di["modules"], "edges":di["module_edges"], "compiled_dependency_status":"UNKNOWN", "compiled_scc":None,
                       "explicit_cross_module_imports":len(di["references"]), "graph_complete":False,
                       "unknown_counts":dict(collections.Counter(r["reason"] for r in di["unknown"]))}
paths = [n for n in g.tree(ROOT,SHA) if any(n.startswith(m+"/src/main/java/") and n.endswith(".java") for m in di["modules"])]
files = dep.blobs(ROOT,SHA,paths)
types = collections.defaultdict(set); sources = {}; annotations = []
for path,raw in files.items():
    text = raw.decode("utf-8-sig")
    if re.search(r"\\u+[0-9a-fA-F]{4}",text): continue
    code = dep.lexical.strip_java_comments(text,keep_strings=False)
    package = dep.PACKAGE.match(code)
    if package is None: continue
    name = package.group(1)+"."+pathlib.Path(path).stem
    types[name].add(path)
    if '"""' not in text: sources[path]=(name,code)
    annotation_code = dep.lexical.strip_java_comments(text,keep_strings=True)
    for match in re.finditer(r"^\s*@(?P<qualified>org\.apache\.ibatis\.annotations\.)?(?P<kind>Select|Insert|Update|Delete|SelectProvider|InsertProvider|UpdateProvider|DeleteProvider)\s*\(",annotation_code,re.M):
        if match["qualified"] or re.search(r"\bimport\s+org\.apache\.ibatis\.annotations\.(?:"+match["kind"]+r"|\*)\s*;",annotation_code):
            annotations.append({"path":path,"kind":match["kind"]})
fanin=collections.defaultdict(set); fanout=collections.defaultdict(set)
for path,(name,code) in sources.items():
    for match in dep.IMPORT.finditer(code):
        target=match.group(2)
        if target.endswith(".*"): continue
        parts=target.split("."); resolved=None
        for length in range(len(parts),0,-1):
            outer=".".join(parts[:length])
            if outer in types and len(types[outer])==1: resolved=outer; break
        if resolved is not None:
            fanin[resolved].add(path);fanout[path].add(resolved)
def top(mapping,n=8):
    return [{"key":k,"degree":len(v),"members":sorted(v)} for k,v in sorted(mapping.items(),key=lambda p:(-len(p[1]),p[0]))[:n]]
data["import_query"]={"kind":"DISTINCT_RESOLVED_EXPLICIT_PROJECT_TYPE_IMPORTS_NOT_CALL_GRAPH",
  "tracked_java_units":len(paths),"supported_source_units":len(sources),
  "system_java_units":sum(p.startswith("chengyinhub-system/") for p in paths),
  "business_mapper_immediate_units":sum(p.startswith("chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/") and "/" not in p.split("/mapper/",1)[1] for p in paths),
  "top_fanin":top(fanin),"top_fanout":top(fanout),
  "top_mapper_fanin":top({k:v for k,v in fanin.items() if ".mapper." in k},6),
  "top_service_fanin":top({k:v for k,v in fanin.items() if ".service." in k},6)}
details={}; current=g.inventory(ROOT,SHA,details)
before_details={}; before=g.inventory(ROOT,BASE,before_details)
tables=collections.defaultdict(set); owners=collections.defaultdict(set); statements=collections.defaultdict(set)
for key,record in current["statements"].items():
    if record["kind"]=="select": continue
    target=g.literal_write_target(record["kind"],details[key])
    if target:
        tables[target].add(record["owner"]);owners[record["owner"]].add(target);statements[target].add(key)
data["writer_query"]={"kind":"EXISTING_LEADING_LITERAL_TARGET_HELPER_NOT_ALL_SQL",
 "literal_tables":len(tables),"writer_namespaces":len(owners),
 "multi_writer_tables":{t:sorted(v) for t,v in sorted(tables.items()) if len(v)>1},
 "top_mapper_table_fanout":top(owners),"top_table_statement_fanin":top(statements),
 "hint_multi_writer_tables":sum(len(v)>1 for v in current["observed_table_field_writers"].values())}
data["annotation_query"]={"kind":"LEXICAL_MYBATIS_ANNOTATION_CANDIDATES_NOT_ANNOTATION_SQL_PARSER",
  "count":len(annotations),"matches":annotations,"independent_raw_git_grep":"no direct production @Select/@Insert/@Update/@Delete/*Provider matches; git grep exit 1"}
changed=set(git("diff","--name-only","--no-renames",BASE,SHA).splitlines())
production={p for p in changed if "/src/main/java/" in p or "/src/main/resources/mapper/" in p}
tests={p for p in changed if "/src/test/" in p}
changed_types={name for path,(name,_) in sources.items() if path in production}
for key,record in current["statements"].items():
    if key.split("#",1)[0] in production:changed_types.add(record["owner"].split(":",1)[1])
onehop={p for name in changed_types for p in fanin.get(name,set())}
reached=set(changed_types); pending=set(changed_types)
while pending:
    next_names={sources[p][0] for name in pending for p in fanin.get(name,set()) if p in sources}-reached
    reached |= next_names; pending=next_names
data["change_query"]={"all_paths":len(changed),"production_java_mapper_paths":len(production),
 "test_paths":len(tests),"production_by_module":dict(collections.Counter(p.split("/")[0] for p in production)),
 "test_by_module":dict(collections.Counter(p.split("/")[0] for p in tests)),
 "changed_production_paths":sorted(production),"changed_type_or_mapper_namespace_seeds":len(changed_types),
 "onehop_importer_paths":len(onehop),"onehop_other_paths":len(onehop-production),
 "transitively_reached_supported_types":len(reached),
 "not_a_safe_test_selector":"same-package uses/wildcards/interface dispatch/Spring/XML/runtime dependencies unresolved"}
history=git("log","--format=%H","--name-only",BASE+".."+SHA,"--","*/src/main/java/*","*/src/main/resources/mapper/*","*/src/test/*")
commit_paths=collections.defaultdict(set);commit=None
for line in history.splitlines():
    if re.fullmatch("[0-9a-f]{40}",line):commit=line
    elif line and commit:commit_paths[commit].add(line)
counts=collections.Counter(p for ps in commit_paths.values() for p in ps)
data["change_query"]["path_touch_commits"]=len(commit_paths)
data["change_query"]["top_touched_paths"]=[{"path":p,"commits":n} for p,n in counts.most_common(10)]
query=subprocess.run(["git","-C",str(ROOT),"grep","-n","-E",r"\bWP(0[0-9]|1[0-7])\b",SHA,"--","*.md"],capture_output=True,text=True)
wp=collections.defaultdict(set)
for line in query.stdout.splitlines():
    if len(line)>2200:continue
    for number in re.findall(r"\bWP(?:0[0-9]|1[0-7])\b",line):wp[number].add(line.split(":",2)[1])
data["historical_markdown_reference_query_not_scope_definition"]={("WP%02d"%n):sorted(wp["WP%02d"%n]) for n in range(18)}
scope=g.blob(ROOT,SPEC_SHA,data["specification_path"])
data["governance_scope"]={"sha256":g.digest(scope),"all_wp_definitions":re.findall(r"^### (WP[0-9]{2}) (.+)$",scope.decode(),re.M),"status":"ALL_18_TARGETS_DEFINED_NOT_IMPLEMENTATION_OR_ACCEPTANCE_PROOF"}
data["not_proven"]=["full WP00-WP17 acceptance not proved by static queries","business-domain owner assignments","column/command authorization","runtime/compiled full dependency graph","actual test execution","SQL traces/EXPLAIN/query latency","causal change propagation"]
OUT.mkdir(exist_ok=True)
(OUT/"evidence.json").write_bytes(g.encode(data))
print("Evidence:",OUT/"evidence.json")
print("Static production delta:",len(production),"test paths:",len(tests),"onehop importer paths:",len(onehop),"other importer paths:",len(onehop-production),"reached types:",len(reached))
print("Repo stays:",git("rev-parse","HEAD").strip(),"; status:",git("status","--porcelain").strip() or "CLEAN")
```

独立交叉核对命令：

```sh
git diff --name-only 07172e6fddf28f99ea4ad214b5c92cfe7608e441 4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e
git diff --name-only --no-renames 2ec68899099a1e250df0949a8383bf85b5d3ffe0 07172e6fddf28f99ea4ad214b5c92cfe7608e441
git grep -n 'import com.chengyinhub.business.mapper.CmsTopicMapper;' 07172e6fddf28f99ea4ad214b5c92cfe7608e441 -- '*/src/main/java/*'
git grep -n -E '@(org\.apache\.ibatis\.annotations\.)?(Select|Insert|Update|Delete|SelectProvider|InsertProvider|UpdateProvider|DeleteProvider)[[:space:]]*\(' 07172e6fddf28f99ea4ad214b5c92cfe7608e441 -- 'chengyinhub-*/src/main/java/*.java'
```

无命中时 git grep exit 1 是查询结果，不是测试通过。import 数、路径变化、源代码子图不能替代真实 jdeps、SQL trace、MySQL/HTTP/真机/故障恢复或 Q5/T15 三项变更演练。
