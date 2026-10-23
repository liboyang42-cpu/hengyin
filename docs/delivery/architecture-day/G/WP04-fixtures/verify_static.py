#!/usr/bin/env python3
"""Source-scope/unchanged original assertions only. Not a Java/business test."""
import hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5]
BASE='65780b036d9e44e3ab8705bb8c244558a09213af'
REPORT=ROOT/'docs/delivery/architecture-day/G/WP04-fixtures'
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT).decode()
def digest(data): return hashlib.sha256(data).hexdigest()
def mask(text):
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',lambda m:' '*len(m[0]),text)
def assertions(text):
    code=mask(text);out=[]
    for m in re.finditer(r'\b(?:assertEquals|assertNull|assertNotNull|assertMoney|assertTrue|assertThrows)\s*\(',code):
        depth=0
        for i in range(code.index('(',m.start()),len(code)):
            depth+=(code[i]=='(')-(code[i]==')')
            if depth==0:
                out.append(re.sub(r'\s+','',text[m.start():i+1]));break
    return out
manifest=json.loads((REPORT/'source-hashes.json').read_text());files=manifest['files']
changed=set(git('diff','--name-only',BASE).splitlines())|set(git('ls-files','--others','--exclude-standard').splitlines())
assert all(p in files or p.startswith('docs/delivery/architecture-day/G/WP04-fixtures/') for p in changed),changed
retained={}
for path,expected in files.items():
    data=(ROOT/path).read_bytes();assert digest(data)==expected,path
    old,new=git('show',f'{BASE}:{path}'),data.decode()
    oldtests=re.findall(r'@Test\s+void\s+(\w+)\(',old);newtests=re.findall(r'@Test\s+void\s+(\w+)\(',new)
    assert oldtests==newtests,path
    assert all(a in assertions(new) for a in assertions(old)),path+' original assertion removed'
    assert not re.search(r'@Disabled|Assumptions\.|assumeTrue|@EnabledIf',new),path
    retained[path]={'original_tests':len(oldtests),'original_assertions_retained':len(assertions(old))}
identity=digest(''.join(f'{p}\0{files[p]}\n' for p in sorted(files)).encode());assert identity==manifest['source_sha256']
subprocess.run(['git','diff','--check',BASE],cwd=ROOT,check=True)
print(json.dumps({'kind':'STATIC_ONLY','source_sha256':identity,'production_files_changed':0,'fixtures':retained,'java':'NOT_RUN','mysql':'NOT_RUN'},ensure_ascii=False,indent=2))
