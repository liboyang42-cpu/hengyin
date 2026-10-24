import ast,hashlib,json,re,subprocess
from pathlib import Path
import yaml
base='4197f6a6b11d62cb0a742aae73d7167c93114585'
def old(path):return subprocess.check_output(['git','show',base+':'+path])
def ast_funcs(text):return {n.name:ast.dump(n,include_attributes=False) for n in ast.parse(text).body if isinstance(n,(ast.FunctionDef,ast.ClassDef))}
harness='ci/real_storage_it.py';tests='ci/test_real_storage_it.py';wf='.github/workflows/real-storage-it.yml'
a,b=ast_funcs(old(harness)),ast_funcs(Path(harness).read_bytes());unchanged=[]
for name,node in a.items():
 if name=='main':continue
 assert b[name]==node,name
 unchanged.append(name)
def cases(text):
 t=ast.parse(text);return {(c.name,m.name):ast.dump(m,include_attributes=False) for c in t.body if isinstance(c,ast.ClassDef) for m in c.body if isinstance(m,ast.FunctionDef) and m.name.startswith('test_')}
a,b=cases(old(tests)),cases(Path(tests).read_bytes());assert len(a)==47 and len(b)==64
assert all(b[key]==node for key,node in a.items())
x=yaml.load(old(wf).decode(),Loader=yaml.BaseLoader);y=yaml.load(Path(wf).read_text(),Loader=yaml.BaseLoader)
assert set(y['on'])=={'workflow_dispatch'}
for key,value in x['on']['workflow_dispatch']['inputs'].items():assert y['on']['workflow_dispatch']['inputs'][key]==value
assert set(y['on']['workflow_dispatch']['inputs'])-set(x['on']['workflow_dispatch']['inputs'])=={'old_source_sha'}
orig=dict(x['jobs']['summary']);now=dict(y['jobs']['summary']);now['if']=orig['if'];assert now==orig
orig=dict(x['jobs']['plan']['steps'][3]);now=next(s for s in y['jobs']['plan']['steps'] if s.get('id')=='plan');now=dict(now);now.pop('if');assert now==orig
oldsteps=x['jobs']['storage_it']['steps'];newsteps=y['jobs']['storage_it']['steps']
for before,after in zip(oldsteps,newsteps):
 if before.get('name') not in ('Checkout candidate source','Upload class receipt'):assert before==after
assert len(oldsteps)==len(newsteps)
inline=0
for job in y['jobs'].values():
 for step in job['steps']:
  if 'run' not in step:continue
  r=subprocess.run(['bash','-n'],input=step['run'],text=True,capture_output=True);assert r.returncode==0,r.stderr
  if "python3 - <<'PY'\n" in step['run']:
   compile(step['run'].split("python3 - <<'PY'\n",1)[1].rsplit('\nPY',1)[0],'<workflow-inline>','exec');inline+=1
r=Path('ci/real-storage-it-registry.json').read_bytes();assert r==old('ci/real-storage-it-registry.json')
protected=['chengyinhub-admin/src/main','chengyinhub-system/src/main','chengyinhub-admin/src/test/java']
assert not subprocess.check_output(['git','diff',base,'--',*protected]).strip()
print(json.dumps(dict(status='PASS',baseline=base,original47_test_bodies_ast_unchanged=True,new_tests=17,
 original_harness_functions_ast_unchanged_except_parser_main=unchanged,
 original_single_source_plan_step_and_summary_body_unchanged=True,original_storage_steps_unchanged_except_checkout_and_upload_names=True,
 original_inputs_unchanged=True,registry_sha256=hashlib.sha256(r).hexdigest(),registry_unchanged=True,
 no_java_source_or_fixture_diff=True,yaml_bash_inline_python_parse_only=True,
 frozen_comparator_source='1c138a000387583e224c9acb5369140fdf2dba18'),indent=2))
