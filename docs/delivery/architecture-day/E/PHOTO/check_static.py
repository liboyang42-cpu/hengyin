#!/usr/bin/env python3
"""Exact-scope and XML checks only; does not compile Java or execute SQL."""
import hashlib,json,pathlib,re,subprocess,tempfile,os,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[5]
meta=json.loads((pathlib.Path(__file__).with_name('source-manifest.json')).read_text())
def git(*args,env=None):return subprocess.check_output(['git','-C',str(root),*args],env=env).decode().strip()
assert git('rev-parse','HEAD')==meta['dependencyCommit'] or git('merge-base',meta['dependencyCommit'],'HEAD')==meta['dependencyCommit']
for item in meta['files']:
 p=root/item['path'];assert hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256'],item['path']
 changed=set(git('diff',meta['dependencyCommit'],'--name-only').splitlines())
 untracked=set(git('ls-files','--others','--exclude-standard').splitlines())
 allowed={i['path'] for i in meta['files']}
 assert all(p in allowed or p.startswith('docs/delivery/architecture-day/E/PHOTO/') for p in changed|untracked),changed|untracked
with tempfile.TemporaryDirectory(prefix='e-photo-index-') as d:
 env=dict(os.environ,GIT_INDEX_FILE=d+'/index');git('read-tree',meta['dependencyCommit'],env=env)
 for i in meta['files']:git('update-index','--add','--cacheinfo','100644',git('hash-object','-w',i['path']),i['path'],env=env)
 assert git('write-tree',env=env)==meta['sourceOnlyTree']
mapper=root/'chengyinhub-system/src/main/resources/mapper/business/PhotoCheckIntentMapper.xml'
tree=ET.parse(mapper).getroot();statements={n.attrib['id'] for n in tree if n.tag in ('select','insert','update','delete')}
interface=(root/'chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/PhotoCheckIntentMapper.java').read_text()
assert statements==set(re.findall(r'(?:int|PhotoCheckIntentRow)\s+(\w+)\(',interface))
fields=set(re.findall(r'private \w+ (\w+);',(root/'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/PhotoCheckIntentRow.java').read_text()))
assert all(n.attrib['property'] in fields for n in tree.find('resultMap'))
counts={name:len(re.findall(r'@Test\b',(root/f'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/{name}.java').read_text())) for name in ('PhotoCheckIntentServiceTest','PhotoCheckIntentMySqlIT')}
assert counts=={'PhotoCheckIntentServiceTest':16,'PhotoCheckIntentMySqlIT':10}
patterns=[rb'AKIA[0-9A-Z]{16}',rb'gh[pousr]_[A-Za-z0-9]{30,}',rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']
assert not [(i['path'],p.decode()) for i in meta['files'] for p in patterns if re.search(p,(root/i['path']).read_bytes())]
subprocess.run(['git','-C',str(root),'diff','--check',meta['dependencyCommit']],check=True)
print(json.dumps({'scope':'PASS','sourceOnlyTree':meta['sourceOnlyTree'],'xmlStatements':len(statements),'plannedJavaCases':counts,'limitedSecretPatterns':'PASS','javaExecuted':False,'mysqlExecuted':False},ensure_ascii=False))
