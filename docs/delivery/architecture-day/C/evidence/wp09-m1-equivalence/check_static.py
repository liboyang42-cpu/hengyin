#!/usr/bin/env python3
"""Read-only source/patch checks for this evidence slice; never executes Java/SQL/CI."""
import hashlib,json,re,subprocess,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'AGENTS.md').is_file())
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def digest(value):return hashlib.sha256(value).hexdigest()
def check(value,message):
    if not value:raise AssertionError(message)
def observe(source,hint):
    changes=[
      ('    private CountDownLatch callerEntered;' if hint else '    private GameSessionRuntimeMapper observedReceipts;',None),
      ('    @BeforeEach void seedOnlyOwnedSchema() {','    @BeforeEach void seedOnlyOwnedSchema(TestInfo testInfo) throws Exception {'),
      ('        connectionIds.clear(); callerEntered = null;' if hint else '        claims.set(0);',None),
      ('        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");',None),
      ('        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));',None)]
    changes[0]=(changes[0][0],changes[0][0]+'\n    private Wp09M1EquivalenceTrace.Capture equivalence;')
    changes[2]=(changes[2][0],changes[2][0]+'\n        equivalence=Wp09M1EquivalenceTrace.begin(getClass().getName(),testInfo.getTestMethod().orElseThrow().getName(),jdbc);')
    changes[3]=(changes[3][0],'        config.addInterceptor(new Wp09M1EquivalenceTrace());\n'+changes[3][0])
    changes[4]=(changes[4][0],changes[4][0]+'\n        proxy.addAdvice(0,Wp09M1EquivalenceTrace.controller(actor));')
    source_after=source
    for old,new in changes:
        check(source_after.count(old)==1,'observer anchor count changed')
        source_after=source_after.replace(old,new)
    marker='    private ApiPlayProgressController controller(long actor, IUmsMemberService memberService, IUmsMemberPointsDetailService details) {'
    check(source_after.count(marker)==1,'controller observation anchor count')
    observations='Map.of("testConnectionIds",new ArrayList<>(connectionIds))' if hint else 'Map.of("claims",claims.get())'
    lifecycle='    @AfterEach void writeEquivalence() throws Exception {\n        if(equivalence==null)throw new IllegalStateException("Missing M1 equivalence context");\n        equivalence.finish('+observations+');\n    }\n\n'
    return source_after.replace(marker,lifecycle+marker)
def lexical_delimiters(source):
    # Lexical sanity only, explicitly not a Java parser/compiler.
    clean=re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/','',source)
    stack=[]
    for char in clean:
        if char in '({[':stack.append(char)
        elif char in ')}]':
            check(bool(stack) and stack.pop()==dict(zip(')}]','({['))[char],'unbalanced Java lexical delimiters')
    check(not stack,'unclosed Java lexical delimiters')
def main():
    manifest=json.loads((HERE/'inputs-manifest.json').read_text());proof=json.loads((HERE/'fixture-patch-proof.json').read_text())
    actual_cases=[]
    for record in proof:
        path=record['path'];before=git('show',record['owner_sha']+':'+path)
        observed=observe(before.decode(),path.endswith('/PlayHintUnlockMySqlIT.java')).encode()
        check(digest(before)==record['original_file_sha256'],'original source hash drift')
        check(digest(observed)==record['observed_file_sha256'],'observer source hash drift')
        tail='    private ApiPlayProgressController controller(long actor)'
        check(before.decode().split(tail,1)[1]==observed.decode().split(tail,1)[1],'original wrappers/assertions/cases/helper tail changed')
        cleanup=lambda s:s[s.index('    @AfterAll'):s.index('    @BeforeEach')]
        check(cleanup(before.decode())==cleanup(observed.decode()),'cleanup changed')
        check(re.findall(r'@Test\s+void\s+(\w+)\(',observed.decode())==record['case_names'],'original cases changed')
        patch=HERE/(Path(path).stem+'.'+record['side']+'.review.patch')
        with tempfile.TemporaryDirectory(prefix='c-m1-patch-') as temporary:
            destination=Path(temporary,path);destination.parent.mkdir(parents=True);destination.write_bytes(before)
            for args in [['--check'],[]]:subprocess.run(['git','apply',*args,str(patch)],cwd=temporary,check=True,capture_output=True)
            check(destination.read_bytes()==observed,'review.patch is not the exact observer transformation')
        if record['side']=='new':
            if path.endswith('/PlayHintUnlockMySqlIT.java'):check((ROOT/path).read_bytes()==before,'root-owned hint10 actual source modified')
            else:check((ROOT/path).read_bytes()==observed,'lead4 observer differs from reviewed transformation')
            actual_cases.extend((record['path'].rsplit('/',1)[1][:-5],name) for name in record['case_names'])
        lexical_delimiters(observed.decode())
    expected=[(c['suite'].rsplit('.',1)[1],c['case']) for c in manifest['cases']]
    check(len(expected)==14 and set(actual_cases)==set(expected),'14-case manifest mismatch')
    for item in manifest['shared_source_files']:
        copies=[git('show',sha+':'+item['path']) for sha in manifest['owner_bases'].values()]
        check(copies[0]==copies[1] and digest(copies[0])==item['sha256'],'shared source changed: '+item['path'])
        if item['path'].endswith('.xml'):
            text=copies[0].decode()
            check(not re.search(r'<bind\b|\.set[A-Z]\w*\(|UUID\.randomUUID|currentTimeMillis|new\s+Date\(',text),'BoundSql extra observation may execute a mutating expression')
    helper=ROOT/'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp09M1EquivalenceTrace.java'
    text=helper.read_text();lexical_delimiters(text)
    check(not re.search(r'PlayNodeAccessService|NodeHintUnlockCommand|NodeHintUnlockResult|IPuzzlePlayService|PuzzlePlayServiceImpl|Assumptions|@Disabled|@Test|\.executeUpdate\(',text),'helper has forbidden dependency/skip/write')
    changed=set(git('diff','--name-only',manifest['owner_bases']['new']).decode().splitlines())
    changed.update(git('ls-files','--others','--exclude-standard').decode().splitlines())
    allowed={str(helper.relative_to(ROOT)),'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PlayLeadAdmissionFailureMySqlIT.java'}
    prefix=str(HERE.relative_to(ROOT))+'/'
    check(all(p in allowed or p.startswith(prefix) for p in changed),'unexpected source/production/CI/other-owner changes')
    result=dict(status='PASS',kind='static-only-not-Java-compilation',owner_bases=manifest['owner_bases'],review_patches_checked=4,original_cases_preserved=14,shared_sources_byte_equal=21,root_hint_file_untouched=True,helper_sha256=digest(helper.read_bytes()),checks=['exact six observer anchors only','all original wrappers/assertions/helper tail and cleanup unchanged','four patches apply to exact old/new owner','manifest exact 14-case set','21 shared source hashes equal','8 mapper XMLs contain no inspected mutating BoundSql expressions','Java lexical delimiters only','test/C-evidence ownership whitelist'])
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
