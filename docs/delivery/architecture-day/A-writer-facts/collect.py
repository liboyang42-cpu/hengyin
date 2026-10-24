#!/usr/bin/env python3
"""WP00/WP01 observation, not authorization. Reuses existing gate SQL shapes.
Java indexing is lexical source evidence, never a compiler/callgraph proof.
"""
from pathlib import Path
import collections
import hashlib
import gzip
import importlib.util
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
SOURCE = '0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def emit(name, rows):
    data = ''.join(json.dumps(row, ensure_ascii=False, sort_keys=True) + '\n' for row in rows).encode()
    if name.endswith('.gz'):
        (OUT / name).write_bytes(gzip.compress(data, mtime=0))
    else:
        (OUT / name).write_bytes(data)


def mask_java(text):
    # Only lexical spans; preserves offsets/newlines. No SQL/Java semantic parser.
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), text)


def closing(masked, start, left='{', right='}'):
    depth = 0
    for i in range(start, len(masked)):
        if masked[i] == left:
            depth += 1
        elif masked[i] == right:
            depth -= 1
            if depth == 0:
                return i + 1
    return len(masked)


def annotations(raw, masked, start, end):
    items = []
    for m in re.finditer(r'@([\w.]+)', masked[start:end]):
        a = start + m.start()
        b = start + m.end()
        p = b
        while p < end and masked[p].isspace():
            p += 1
        if p < end and masked[p] == '(':
            b = closing(masked, p, '(', ')')
        items.append({'name': m.group(1), 'text': raw[a:b], 'line': raw.count('\n', 0, a) + 1})
    return items


METHOD = re.compile(r'(?m)^[ \t]*(?:(?:public|private|protected|static|final|synchronized|abstract|default|native|strictfp)\s+)*(?:<[\w ,?]+>\s*)?([\w.$][\w.$<>,? \[\]\n]*?)\s+(\w+)\s*\(([^;{}]*?)\)\s*(?:throws\s+[\w., \n]+)?\s*([;{])')
FORBIDDEN_RETURN = {'return', 'throw', 'new', 'if', 'else', 'case', 'import', 'package', 'assert', 'yield', 'break', 'continue', 'try', 'catch', 'while', 'for', 'switch', 'do'}


def main():
    spec = importlib.util.spec_from_file_location('existing_architecture_gate', ROOT / 'ci/architecture/gate.py')
    gate = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gate)
    names = gate.tree(ROOT, SOURCE)
    # All production Java and XML resources, independent of domain directory names.
    java_paths = [p for p in names if '/src/main/java/' in p and p.endswith('.java')]
    xml_paths = [p for p in names if '/src/main/resources/' in p and p.endswith('.xml')]
    template_paths = [p for p in names if '/src/main/' in p and p.endswith('.vm')]
    selected = sorted(set(java_paths + xml_paths + template_paths + ['ci/architecture/gate.py', 'ci/architecture/baseline.json', 'ci/sql_splitter.py']))
    # Read immutable Git blobs in one batch. No dirty working-tree source dependence.
    query = ''.join(SOURCE + ':' + p + '\n' for p in selected).encode()
    data = subprocess.check_output(['git', 'cat-file', '--batch'], input=query, cwd=ROOT)
    blobs, blob_ids, offset = {}, {}, 0
    for p in selected:
        cut = data.index(b'\n', offset)
        oid, typ, size = data[offset:cut].decode().split()
        assert typ == 'blob', p
        offset = cut + 1
        blobs[p] = data[offset:offset + int(size)]
        blob_ids[p] = oid
        offset += int(size) + 1
    assert (ROOT / 'ci/architecture/gate.py').read_bytes() == blobs['ci/architecture/gate.py']
    assert (ROOT / 'ci/sql_splitter.py').read_bytes() == blobs['ci/sql_splitter.py']
    details = {}
    inventory = gate.inventory(ROOT, SOURCE, details)
    baseline = json.loads(blobs['ci/architecture/baseline.json'])
    (OUT / 'observed_table_field_writers.json').write_bytes(gate.encode({
        'source_sha': SOURCE, 'status': 'RAW_EXISTING_GATE_HINTS_NOT_APPROVED_POLICY',
        'observed_table_field_writers': inventory['observed_table_field_writers']}))
    statements, sql_by_method = [], collections.defaultdict(list)
    suspect_selects = []
    unknown_select_effects = []
    for key, shape in sorted(inventory['statements'].items()):
        path, sid = key.rsplit('#', 1)
        kind = shape['kind']
        match = re.match(r'\s*(insert|replace|update|delete)\b', details[key]['text'], re.I)
        if kind == 'select':
            if 'text_substitution' in shape['unknown'] or b'statementType="CALLABLE"' in blobs[path] or b'affectData="true"' in blobs[path]:
                unknown_select_effects.append({'key':key,'namespace':gate.xml(blobs[path]).get('namespace'),
                    'shape':shape,'status':'UNKNOWN_POSSIBLE_EFFECTS; not counted as a proven write'})
            if not match:
                continue
            suspect_selects.append(key)
            kind = match.group(1).lower()
            kind = 'insert' if kind == 'replace' else kind
        node = next(n for n in gate.xml(blobs[path]) if n.get('id') == sid and n.tag in {'insert','update','delete','select'})
        namespace = gate.xml(blobs[path]).get('namespace')
        target = gate.literal_write_target(kind, details[key])
        row = dict(shape, key=key, namespace=namespace, statement_id=sid,
                   xml_file_sha256=sha(blobs[path]), xml_file_git_blob=blob_ids[path],
                   xml_node_sha256=sha(ET.tostring(node)), literal_write_target=target,
                   literal_target_status='KNOWN_LITERAL_TARGET_ONLY' if target else 'UNKNOWN_TARGET',
                   column_status='HINTS_ONLY_NOT_COMPLETE_COLUMN_PROOF',
                   line=next((i for i,l in enumerate(blobs[path].decode().splitlines(),1) if re.search(r'<'+node.tag+r'\s+[^>]*id="'+re.escape(sid)+r'"',l)),None),
                   authorization='NOT_DETERMINED', baseline_statement=baseline['statements'].get(key)==shape)
        statements.append(row)
        sql_by_method[(namespace, sid)].append(key)
    mapper_namespaces = {r['namespace'] for r in statements}
    write_names = {r['statement_id'] for r in statements}
    classes, by_fqn, methods = {}, {}, {}
    direct, dynamic, templates = [], [], []
    for p in java_paths:
        raw = blobs[p].decode()
        masked = mask_java(raw)
        package = re.search(r'\bpackage\s+([\w.]+)\s*;', masked)
        package = package.group(1) if package else ''
        cm = re.search(r'\b(class|interface|enum|record)\s+(\w+)([^\{;]*)\{', masked)
        if not cm:
            continue
        fqn = package + '.' + cm.group(2)
        imports = {m.group(2): m.group(1)+'.'+m.group(2) for m in re.finditer(r'\bimport\s+(?!static\b)([\w.]+)\.(\w+)\s*;', masked)}
        imports.update({n: package+'.'+n for n in [cm.group(2)]})
        impl = re.search(r'\bimplements\s+(.+)', cm.group(3))
        supers = re.findall(r'\b(?:extends)\s+([\w.]+)', cm.group(3))
        interfaces = re.findall(r'[\w.]+', impl.group(1)) if impl else []
        obj = {'path': p, 'class': fqn, 'kind': cm.group(1), 'raw':raw, 'masked':masked,
               'imports': imports, 'super_names': supers+interfaces, 'methods': [],
               'class_annotations':annotations(raw,masked,0,cm.start()), 'fields':{}}
        for fm in re.finditer(r'\b(?:private|protected|public)\s+(?:(?:static|final|volatile|transient)\s+)*([\w.$]+(?:<[^;=(){}]+>)?(?:\[\])?)\s+(\w+)\s*(?:=|;)', masked):
            obj['fields'][fm.group(2)] = re.sub(r'<.*', '', fm.group(1))
        prior_end = cm.end()
        for m in METHOD.finditer(masked):
            returns, name, params, terminal = m.groups()
            if set(returns.split()) & FORBIDDEN_RETURN or name in FORBIDDEN_RETURN or m.start()<cm.end():
                continue
            if any(old['start'] <= m.start() < old['end'] for old in obj['methods']):
                continue  # anonymous/nested method: explicit unknown elsewhere, not attributed as own declaration
            end = closing(masked,m.end()-1) if terminal=='{' else m.end()
            line = raw.count('\n',0,m.start())+1
            mid=p+'#'+name+'@'+str(line)
            ann=annotations(raw,masked,prior_end,m.start())
            method={'id':mid,'path':p,'class':fqn,'name':name,'line':line,'end_line':raw.count('\n',0,end)+1,
                    'signature':raw[m.start():m.end()].strip(),'method_sha256':sha(raw[m.start():end].encode()),
                    'file_sha256':sha(blobs[p]),'annotations':ann,'start':m.start(),'body_start':m.end(),'end':end,
                    'has_body':terminal=='{','params_masked':params}
            obj['methods'].append(method); methods[mid]=method
            prior_end=end
        classes[p]=obj; by_fqn[fqn]=obj
        # Exhaustive lexical bypass observations; comments/strings are masked so not counted as execution sites.
        patterns={
            'DIRECT_SQL_API':r'\b(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|SqlSession|EntityManager)\b|\.(?:prepareStatement|prepareCall|createStatement|createNativeQuery|executeUpdate|executeBatch)\s*\(',
            'MYBATIS_SQL_ANNOTATION':r'@(?:Insert|Update|Delete|Select|InsertProvider|UpdateProvider|DeleteProvider|SelectProvider)\b',
            'RUNTIME_DISPATCH':r'\bClass\s*\.\s*forName\s*\(|\.(?:getBean|getMapper|getDeclaredMethod|getMethod|invoke)\s*\(|::\s*\w+',
        }
        for category, pattern in patterns.items():
            for hit in re.finditer(pattern,masked):
                line=raw.count('\n',0,hit.start())+1
                entry={'path':p,'line':line,'category':category,'file_sha256':sha(blobs[p]),
                       'line_text':raw.splitlines()[line-1].strip(), 'status':'LEXICAL_OBSERVATION_RUNTIME_UNKNOWN'}
                (direct if category!='RUNTIME_DISPATCH' else dynamic).append(entry)
    simple=collections.defaultdict(list)
    for fqn in by_fqn:
        simple[fqn.rsplit('.',1)[-1]].append(fqn)

    def resolve(obj, name):
        if name in by_fqn or name in mapper_namespaces:
            return name, 'EXPLICIT_FQN'
        if name in obj['imports']:
            return obj['imports'][name], 'EXPLICIT_IMPORT_OR_SAME_CLASS'
        own=obj['class'].rsplit('.',1)[0]+'.'+name
        if own in by_fqn:
            return own,'SAME_PACKAGE'
        choices=simple.get(name,[])
        return (choices[0],'UNIQUE_SIMPLE_NAME_CANDIDATE') if len(choices)==1 else (None,'UNKNOWN_TYPE')

    implementations=collections.defaultdict(set)
    for obj in classes.values():
        for parent in obj['super_names']:
            resolved,_=resolve(obj,parent)
            if resolved:
                implementations[resolved].add(obj['class'])
    method_targets=collections.defaultdict(list)
    for method in methods.values():
        if method['has_body']:
            method_targets[(method['class'],method['name'])].append(method['id'])
    writes,edges,entries,unresolved=[],[],[],[]
    edge_seen=set()
    for obj in classes.values():
        raw,masked=obj['raw'],obj['masked']
        task_class=bool(re.search(r'(?:Task|Job)$',obj['class']) or '/task/' in obj['path'])
        for method in obj['methods']:
            if not method['has_body']:
                continue
            ann=method['annotations']
            routes=[a for a in ann if a['name'].endswith('Mapping')]
            scheduled=[a for a in ann if a['name'].split('.')[-1]=='Scheduled']
            if routes or scheduled or (task_class and method['signature'].startswith('public ')):
                entries.append({'method_id':method['id'],'path':method['path'],'line':method['line'],
                                'kind':'HTTP_ANNOTATED' if routes else 'SCHEDULED_ANNOTATED' if scheduled else 'QUARTZ_BEAN_METHOD_CANDIDATE',
                                'routes':routes,'class_annotations':obj['class_annotations'],
                                'method_annotations':ann,
                                'permission_expressions':[a['text'] for a in obj['class_annotations']+ann if a['name'].split('.')[-1] in {'PreAuthorize','Secured','RolesAllowed','RequiresPermissions','Anonymous'}],
                                'roles':'UNKNOWN_RUNTIME_RBAC_ASSIGNMENTS; NO_DATABASE_READ',
                                'reachability':'SOURCE_ONLY; runtime schedule/HTTP/security not executed'})
            body=masked[method['body_start']:method['end']]
            local_types=dict(obj['fields'])
            # Typed parameters/local declarations; shadowing is a candidate, never runtime resolution.
            context=method['params_masked']+';'+body
            for dm in re.finditer(r'\b([A-Z][\w.$]+)(?:\s*<[^;=(){}]*>)?\s+(\w+)\s*(?=[,;=)])',context):
                local_types[dm.group(2)]=dm.group(1)
            for call in re.finditer(r'(?<![\w$.])(?:(\w+)\s*\.\s*)?(\w+)\s*\(',body):
                receiver,name=call.groups()
                if name in FORBIDDEN_RETURN or name in {'catch','synchronized','super','this'}:
                    continue
                absolute=method['body_start']+call.start()
                line=raw.count('\n',0,absolute)+1
                if receiver is None or receiver=='this':
                    if receiver is None and call.start()>0 and body[call.start()-1:call.start()] in {'.','@'}:
                        continue
                    fqn=obj['class'];type_status='SAME_CLASS_NAME_ONLY'
                else:
                    typename=local_types.get(receiver,receiver if receiver[:1].isupper() else '')
                    fqn,type_status=resolve(obj,typename)
                call_id=method['path']+':'+str(line)+':'+str(absolute)
                if fqn and (fqn,name) in sql_by_method:
                    writes.append({'id':call_id,'caller_method':method['id'],'path':method['path'],'line':line,
                                   'receiver':receiver,'receiver_type':fqn,'method':name,
                                   'statement_keys':sql_by_method[(fqn,name)],'resolution':type_status,
                                   'file_sha256':method['file_sha256'],
                                   'runtime_authorization':'UNKNOWN; typed source call is not authorization'})
                    continue
                targets=list(method_targets.get((fqn,name),[])) if fqn else []
                owners=set()
                todo=list(implementations.get(fqn,[])) if fqn else []
                while todo:
                    impl=todo.pop()
                    if impl in owners:continue
                    owners.add(impl);todo.extend(implementations.get(impl,[]))
                    targets.extend(method_targets.get((impl,name),[]))
                for target in sorted(set(targets)):
                    ek=(method['id'],target,line)
                    if ek in edge_seen:continue
                    edge_seen.add(ek)
                    edges.append({'caller':method['id'],'callee':target,'line':line,'receiver':receiver,
                                  'declared_type':fqn,'resolution':type_status,
                                  'status':'SOURCE_CANDIDATE_NOT_RUNTIME_PROOF',
                                  'multiple_targets':len(set(targets))>1})
                if name in write_names and not targets:
                    unresolved.append({'id':call_id,'caller_method':method['id'],'line':line,'receiver':receiver,
                                       'method':name,'receiver_type':fqn,'status':'UNKNOWN_UNRESOLVED_SAME_METHOD_NAME; not counted as a confirmed writer',
                                       'file_sha256':method['file_sha256']})
    # Whole-file typed receiver sweep catches constructor/initializer sites outside indexed methods.
    unindexed=[]
    indexed_ids={w['id'] for w in writes}
    for obj in classes.values():
        for call in re.finditer(r'\b(\w+)\s*\.\s*(\w+)\s*\(',obj['masked']):
            receiver,name=call.groups()
            fqn,status=resolve(obj,obj['fields'].get(receiver,receiver if receiver[:1].isupper() else ''))
            if fqn and (fqn,name) in sql_by_method:
                absolute=call.start();line=obj['raw'].count('\n',0,absolute)+1
                cid=obj['path']+':'+str(line)+':'+str(absolute)
                if cid not in indexed_ids:
                    unindexed.append({'id':cid,'path':obj['path'],'line':line,'receiver':receiver,'receiver_type':fqn,'method':name,
                                      'statement_keys':sql_by_method[(fqn,name)],'status':'UNKNOWN_UNINDEXED_OR_SHADOWED_CONTEXT',
                                      'file_sha256':sha(blobs[obj['path']])})
    # Chained receivers / method references are not resolved by the field-type index.
    chained=[]
    for obj in classes.values():
        for call in re.finditer(r'\)\s*\.\s*(\w+)\s*\(|\b(\w+)\s*::\s*(\w+)',obj['masked']):
            name=call.group(1) or call.group(3)
            if name in write_names:
                line=obj['raw'].count('\n',0,call.start())+1
                chained.append({'path':obj['path'],'line':line,'method':name,'file_sha256':sha(blobs[obj['path']]),
                                'line_text':obj['raw'].splitlines()[line-1].strip(),
                                'status':'UNKNOWN_CHAIN_OR_METHODREF_SAME_WRITE_NAME; NOT_CONFIRMED_SQL_WRITER'})
    # Reverse source graph: candidates only. Do not promote to authorized/reachable runtime writers.
    reverse=collections.defaultdict(set)
    for edge in edges: reverse[edge['callee']].add(edge['caller'])
    entry_ids={e['method_id'] for e in entries}
    by_statement=collections.defaultdict(list)
    for w in writes:
        for key in w['statement_keys']:by_statement[key].append(w)
    reachable=[]
    writer_ancestors=set()
    for row in statements:
        seen=set();todo=[w['caller_method'] for w in by_statement[row['key']]]
        while todo:
            mid=todo.pop()
            if mid in seen:continue
            seen.add(mid);todo.extend(reverse.get(mid,()))
        writer_ancestors.update(seen)
        reachable.append({'statement_key':row['key'],'direct_call_ids':[w['id'] for w in by_statement[row['key']]],
                          'entry_method_candidates':sorted(seen & entry_ids),
                          'status':'SOURCE_GRAPH_CANDIDATES; ABSENCE_IS_UNKNOWN_NOT_UNREACHABLE; NO_AUTHORIZATION_INFERENCE'})
    tables=collections.defaultdict(lambda:{'literal_target_statements':[],'hint_only_statements':[],'column_hints':collections.defaultdict(list),'namespaces':set()})
    for row in statements:
        target=row['literal_write_target']
        for table in set(row['table_hints']+([target] if target else [])):
            rec=tables[table]
            rec['literal_target_statements' if table==target else 'hint_only_statements'].append(row['key'])
            rec['namespaces'].add(row['namespace'])
            for column in row['column_hints']:rec['column_hints'][column].append(row['key'])
    table_rows=[]
    for table,record in sorted(tables.items()):
        table_rows.append(dict(table=table,**{k:sorted(v) if isinstance(v,set) else dict(v) if isinstance(v,collections.defaultdict) else v for k,v in record.items()},
                               status='OBSERVED_HINT_INDEX_NOT_POLICY; DELETE_EMPTY_COLUMNS_DOES_NOT_MEAN_NO_EFFECT'))
    for p in template_paths:
        if any(word in blobs[p].lower() for word in [b'<insert',b'<update',b'<delete',b'insert${',b'update${',b'delete${']):
            templates.append({'path':p,'sha256':sha(blobs[p]),'status':'GENERATOR_TEMPLATE_NOT_DEPLOYED_WRITER; future output UNKNOWN'})
    # Resource XML outside the existing gate's mapper pattern remains explicitly audited.
    residual=[]
    for p in xml_paths:
        if gate.MAPPER.fullmatch(p):continue
        if re.search(rb'<(?:mapper|insert|update|delete)\b',blobs[p]):
            residual.append({'path':p,'sha256':sha(blobs[p]),'status':'UNKNOWN_OUTSIDE_GATE_PATTERN'})
    emit('nonstandard-select-observations.jsonl',unknown_select_effects)
    emit('mapper-writes.jsonl',statements)
    emit('java-write-calls.jsonl',sorted(writes,key=lambda x:x['id']))
    retained_edges=[e for e in edges if e['caller'] in writer_ancestors and e['callee'] in writer_ancestors]
    emit('java-source-call-edges.jsonl.gz',sorted(retained_edges,key=lambda x:(x['caller'],x['callee'],x['line'])))
    retained_ids=writer_ancestors | entry_ids
    emit('java-methods.jsonl.gz',({k:v for k,v in m.items() if k not in {'start','body_start','end','params_masked'}} for m in sorted(methods.values(),key=lambda x:x['id']) if m['id'] in retained_ids))
    emit('entrypoints.jsonl',sorted(entries,key=lambda x:x['method_id']))
    emit('statement-entry-candidates.jsonl',reachable)
    emit('table-column-index.jsonl',table_rows)
    emit('chained-or-methodref-write-name-sites.jsonl',chained)
    emit('unindexed-mapper-write-calls.jsonl',unindexed)
    emit('unknown-java-write-name-calls.jsonl',sorted(unresolved,key=lambda x:x['id']))
    emit('direct-sql-observations.jsonl',direct)
    emit('runtime-dispatch-observations.jsonl',dynamic)
    emit('generator-and-residual-xml.jsonl',templates+residual)
    emit('source-files.jsonl',({'path':p,'git_blob':blob_ids[p],'sha256':sha(blobs[p])} for p in selected))
    summary={'source_sha':SOURCE,'source_tree':subprocess.check_output(['git','rev-parse',SOURCE+'^{tree}'],cwd=ROOT,text=True).strip(),
             'production_java_files':len(java_paths),'resource_xml_files':len(xml_paths),'mapper_statements':len(inventory['statements']),
             'write_statements':len(statements),'mapper_write_shape_counts':dict(collections.Counter(r['analysis'] for r in statements)),
             'literal_target_counts':dict(collections.Counter(r['literal_target_status'] for r in statements)),
             'raw_gate_table_hint_count':len(inventory['observed_table_field_writers']),
             'literal_target_tables':len({r['literal_write_target'] for r in statements if r['literal_write_target']}),
             'select_tag_leading_dml':suspect_selects,'unknown_select_effect_observations':len(unknown_select_effects),'java_methods_indexed':len(methods),'typed_mapper_write_calls':len(writes),
             'source_graph_edges_before_pruning':len(edges),'source_graph_writer_ancestor_edges':len(retained_edges),'retained_java_methods':len(retained_ids),'unindexed_mapper_call_observations':len(unindexed),'entry_candidates':len(entries),'entry_kind_counts':dict(collections.Counter(e['kind'] for e in entries)),
             'write_statements_without_typed_calls':sum(not by_statement[r['key']] for r in statements),
             'java_same_write_name_unresolved':len(unresolved),'chained_or_methodref_same_write_name_sites':len(chained),'direct_sql_api_and_annotation_observations':len(direct),
             'runtime_dispatch_observations':len(dynamic),'generator_sql_templates':len(templates),'residual_mapper_xml':residual,
             'gate_sha256':sha(blobs['ci/architecture/gate.py']),'baseline_sha256':sha(blobs['ci/architecture/baseline.json']),
             'scope':'OBSERVATION_NOT_APPROVED_POLICY_NOT_RUNTIME_PROOF','java_mysql_ci_runs':0}
    (OUT/'summary.json').write_bytes(gate.encode(summary))
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
