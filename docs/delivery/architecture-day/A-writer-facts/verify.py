#!/usr/bin/env python3
"""Cross-check observation against existing gate and fixed-source files; no Java/DB execution."""
from pathlib import Path
import gzip
import hashlib
import importlib.util
import json
import re
import subprocess

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[3]
SOURCE='0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16'


def rows(name):
    p=OUT/name
    text=gzip.decompress(p.read_bytes()).decode() if name.endswith('.gz') else p.read_text()
    return [json.loads(x) for x in text.splitlines()]


def main():
    spec=importlib.util.spec_from_file_location('existing_gate',ROOT/'ci/architecture/gate.py')
    gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
    details={};raw=gate.inventory(ROOT,SOURCE,details)
    statements=rows('mapper-writes.jsonl');indexed={s['key']:s for s in statements}
    assert len(indexed)==len(statements)==1327
    expected={k for k,v in raw['statements'].items() if v['kind'] in {'insert','update','delete'}}
    assert set(indexed)==expected
    for key,row in indexed.items():
        assert all(row[k]==v for k,v in raw['statements'][key].items()),key
        assert row['literal_write_target']==gate.literal_write_target(row['kind'],details[key]),key
    observed=json.loads((OUT/'observed_table_field_writers.json').read_text())
    assert observed['observed_table_field_writers']==raw['observed_table_field_writers']
    files=rows('source-files.jsonl');file_index={f['path']:f for f in files}
    names=gate.tree(ROOT,SOURCE)
    xml_count=0
    for f in files:
        data=(ROOT/f['path']).read_bytes()
        assert hashlib.sha256(data).hexdigest()==f['sha256'],f['path']
        assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==f['git_blob'],f['path']
        if '/src/main/resources/' in f['path'] and f['path'].endswith('.xml'):
            node=gate.xml(data)
            if node.tag=='mapper':xml_count+=sum(n.tag in {'insert','update','delete'} for n in node)
    assert xml_count==1327
    assert {p for p in names if '/src/main/java/' in p and p.endswith('.java')}=={p for p in file_index if '/src/main/java/' in p}
    methods={m['id']:m for m in rows('java-methods.jsonl.gz')}
    calls=rows('java-write-calls.jsonl');assert len({c['id'] for c in calls})==len(calls)==1877
    for call in calls:
        path,line,offset=call['id'].rsplit(':',2);data=(ROOT/path).read_text();offset=int(offset);line=int(line)
        assert data.count('\n',0,offset)+1==line
        assert call['caller_method'] in methods
        assert methods[call['caller_method']]['line']<=line<=methods[call['caller_method']]['end_line']
        assert call['file_sha256']==file_index[path]['sha256']
        assert all(indexed[k]['namespace']==call['receiver_type'] and indexed[k]['statement_id']==call['method'] for k in call['statement_keys'])
    entries={e['method_id']:e for e in rows('entrypoints.jsonl')}
    for entry in entries.values():
        data=(ROOT/entry['path']).read_text()
        assert all(a['text'] in data for a in entry['class_annotations']+entry['method_annotations'])
    for edge in rows('java-source-call-edges.jsonl.gz'):
        assert edge['caller'] in methods and edge['callee'] in methods
        assert edge['status']=='SOURCE_CANDIDATE_NOT_RUNTIME_PROOF'
    for reach in rows('statement-entry-candidates.jsonl'):
        assert reach['statement_key'] in indexed
        assert set(reach['entry_method_candidates'])<=set(entries)
    manual=json.loads((OUT/'manual-field-observations.json').read_text())
    for row in manual['observations']:
        assert row['statement_sha256']==indexed[row['statement_key']]['sha256']
    for candidate in json.loads((OUT/'priority-owner-questions.json').read_text())['candidates']:
        assert candidate['approved_owner'] is None and candidate['approved_writers'] is None
        assert candidate['status']=='PROPOSAL_NOT_APPROVED_NOT_ALLOWLIST'
        assert all(indexed[r['key']]['sha256']==r['sha256'] for r in candidate['statement_evidence'])
    changed=subprocess.check_output(['git','diff',SOURCE,'--name-only'],cwd=ROOT,text=True).splitlines()
    new=subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    prefix='docs/delivery/architecture-day/A-writer-facts/'
    assert all(p.startswith(prefix) for p in changed+new),changed+new
    result={'source_sha':SOURCE,'exact_existing_gate_statement_and_observed_writer_match':'PASS',
            'all_production_java_source_files_manifested':2442,'xml_write_nodes_independent_count':xml_count,
            'source_file_sha256_and_git_blob_identity':'PASS','call_offsets_namespace_method_and_entry_annotation_links':'PASS',
            'policy_candidates_unapproved_not_allowlist':'PASS','scope_own_docs_only':'PASS',
            'runtime_authorization_grants_schedule':'UNKNOWN_NOT_READ','java_mysql_redis_ddl_http_ci_runs':0}
    (OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
