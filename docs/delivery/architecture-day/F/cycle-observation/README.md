# 重现只读观察

仅Python标准库/Git，不跑Java、DB、CI或网络。先在自己独占且origin确认为公共 hengyin 的仓库准备源27471和公共helper02e691对象；不要切换他人的工作树。已有02e691工作树可以直接复用；否则可在自己的仓库创建独立只读输入工作树：

```sh
git fetch --no-tags origin 02e69111a83d19d2ad53051a44c629d5e0a5a38a
git worktree add --detach ../public-f-cycle-helper 02e69111a83d19d2ad53051a44c629d5e0a5a38a
git worktree add --detach ../public-f-cycle-source 27471b00989e15f7641f4ee77c497cca2f9e53c1
PYTHONDONTWRITEBYTECODE=1 python3 docs/delivery/architecture-day/F/cycle-observation/analyze.py \
  --source ../public-f-cycle-source --sha 27471b00989e15f7641f4ee77c497cca2f9e53c1 \
  --helper-root ../public-f-cycle-helper --output /tmp/f-cycle-observation --all-references
```

若当前checkout仍是27471加本doc-only候选，--source也可用`.`；在root较新集成树重现旧27471时必须指向独立27471输入工作树，避免混用较新gate/SQL helper。

helper是已有public源码查询工具，不合入它的旧policy/baseline；脚本核对helper HEAD与4个被加载文件字节。当前source gate/SQL helper也须与指定SHA一致。源码均用git cat-file immutable blob读取；不读凭据或生产，不调用Maven/Java/数据库；禁止输出到本仓库生产目录。输出只允许显式报告目录（仓库内须F交付目录）/仓库外临时目录，不删除输入文件，不自动fetch、不安装依赖。

输出：summary.json、package-edges.tsv、mapper-writes.tsv、unknown.json。`--all-references`另输出8059条可复算引用到all-import-references.jsonl；本候选不重复提交该大文件，EVIDENCE保留hash。下例独立重算前三包对的暴露面：

```python
import json
from pathlib import Path
out = Path('/tmp/f-cycle-observation')
summary = json.loads((out/'summary.json').read_text())
refs = [json.loads(line) for line in (out/'all-import-references.jsonl').read_text().splitlines()]
for cycle in summary['ranked_reciprocal_package_cycles'][:3]:
    pair = set(cycle['packages'])
    callers = {r['path'] for r in refs if r['target_package'] in pair and r['source_package'] not in pair}
    assert len(callers) == cycle['direct_outside_dependent_units']
    print(cycle['packages'], len(callers))
```

所选6条源码证据及额外utility环2条在cycle-traces.json。原行号不从会压缩多行注释的旧lexical helper计算；import行使用raw唯一声明定位，具体call/type位置从固定Git blob逐行核对。核对示例（在repo根执行）：

```python
import hashlib, json, subprocess
from pathlib import Path
report = Path('docs/delivery/architecture-day/F/cycle-observation')
data = json.loads((report/'cycle-traces.json').read_text())
rows = [s for c in data['selected_package_cycles'] for s in c['sites']] + data['additional_actual_type_cycle']
for site in rows:
    raw = subprocess.check_output(['git', 'show', data['source_sha'] + ':' + site['path']])
    assert hashlib.sha256(raw).hexdigest() == site['source_sha256']
    assert raw.decode().splitlines()[site['line']-1].strip() == site['code']
```

清单是观察，不是CI gate或自动批准baseline。没有编译制品时compiled graph为UNKNOWN；同包隐式/FQN/static/wildcard等未知边不被当不存在。Graph degree是此查询的值，不能当方法/SQL次数或具体修改必然影响的文件总数。
