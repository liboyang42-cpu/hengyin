#!/usr/bin/env python3
"""仅核对来源/范围/XML/正文指纹，不运行或模拟资金业务。"""
import hashlib
import json
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
BASE = '051c1c108653eeb1cbc4f3b69f8ae25728c99e34'
REPORT = ROOT / 'docs/delivery/architecture-day/G/WP04'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode()

def digest(data):
    return hashlib.sha256(data).hexdigest()

def masked(text):
    pattern = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), text)

def method(text, name):
    code = masked(text)
    match = re.search(r'(?m)^    (?:private |public |protected )?[^\n;={}]*\b' + re.escape(name) + r'\([^;{}]*\)\s*\{', code)
    assert match, name
    depth = 0
    for i in range(code.index('{', match.start()), len(code)):
        depth += (code[i] == '{') - (code[i] == '}')
        if depth == 0:
            return match.start(), i + 1
    raise AssertionError('unclosed method ' + name)

def main():
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=ROOT, check=True)
    assert git('config', '--get', 'remote.origin.url').strip() == 'https://github.com/liboyang42-cpu/hengyin.git'
    manifest = json.loads((REPORT / 'source-hashes.json').read_text())
    sources = manifest['files']
    changed = set(git('diff', '--name-only', BASE).splitlines())
    changed.update(git('ls-files', '--others', '--exclude-standard').splitlines())
    assert all(p in sources or p.startswith('docs/delivery/architecture-day/G/WP04/') for p in changed), sorted(changed - set(sources))
    for path, sha in sources.items():
        assert digest((ROOT / path).read_bytes()) == sha, path
    source_id = digest(''.join(f'{path}\0{sources[path]}\n' for path in sorted(sources)).encode())
    assert source_id == manifest['source_sha256']
    parent = json.loads((ROOT / 'docs/delivery/architecture-day/G/artifact-manifest.json').read_text())
    frozen = [p for p in parent['files'] if p.endswith(('.java', '.xml'))]
    assert len(frozen) == 7
    for path in frozen:
        assert (ROOT / path).read_bytes() == subprocess.check_output(['git', 'show', f'{BASE}:{path}'], cwd=ROOT), path
    cms = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java'
    old, new = git('show', f'{BASE}:{cms}'), (ROOT / cms).read_text()
    bname = 'resolvePendingDepositPaymentForSettlement'
    a,b = method(old,bname); c,d = method(new,bname)
    assert old[a:b] == new[c:d], 'B method changed'
    b_hash = digest(new[c:d].encode())
    assert b_hash == manifest['b_method_sha256']
    allowed = ['settleTopic','settleCoopTopic','applyUnpaidDepositSetoffs','requireClassicTopicCashBasis']
    def remaining(text, names):
        for name in names:
            if name == 'requireClassicTopicCashBasis' and text == old:
                continue
            try: start,end = method(text,name)
            except AssertionError:
                assert name == 'requireClassicTopicCashBasis'
                continue
            text = text[:start] + text[end:]
        text = masked(text)
        text = re.sub(r'@Autowired(?:\(required\s*=\s*false\))?\s*private ICoopSettlementClaimService coopSettlementClaimService;', 'CLAIM_FIELD', text)
        text = re.sub(r'@Autowired\s*private CoopSettlementEvidenceMapper coopSettlementEvidenceMapper;', '', text)
        return re.sub(r'\s+', '', text)
    assert remaining(old,allowed) == remaining(new,allowed), 'CMS non-settlement area changed'
    xml_path = ROOT / 'chengyinhub-system/src/main/resources/mapper/business/CoopSettlementEvidenceMapper.xml'
    xml = ET.parse(xml_path).getroot()
    ids = {node.attrib['id'] for node in xml if 'id' in node.attrib}
    for inc in xml.iter('include'):
        assert inc.attrib['refid'] in ids
    java = (ROOT / 'chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CoopSettlementEvidenceMapper.java').read_text()
    for node in xml:
        if node.tag in {'select','insert','update','delete'}:
            assert re.search(r'\b' + node.attrib['id'] + r'\s*\(', java)
    blocked = [r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', r'\bghp_[A-Za-z0-9]{30,}',
               r'\bgithub_pat_[A-Za-z0-9_]{30,}', r'\bAKIA[A-Z0-9]{16}\b',
               r'(?i)(?:password|secret|access_token)\s*[:=]\s*["\'][^"\'\s]{12,}["\']']
    for path in changed:
        data = (ROOT / path).read_text(errors='replace')
        if path.endswith('verify_static.py'):
            continue  # scanner contains pattern literals; other changed files scanned.
        assert not any(re.search(pattern,data) for pattern in blocked), 'credential pattern: ' + path
    subprocess.run(['git','diff','--check',BASE],cwd=ROOT,check=True)
    print(json.dumps({'kind':'STATIC_ONLY','source_files':len(sources),'source_sha256':source_id,
                      'frozen_source_files_unchanged':len(frozen),'b_method_sha256':b_hash,
                      'cms_method_scope':'PASS','xml_and_mapper_ids':'PASS','credential_pattern_scan':'PASS',
                      'java':'NOT_RUN','mysql':'NOT_RUN','funds_behavior':'NOT_VERIFIED'},ensure_ascii=False,indent=2))

if __name__ == '__main__':
    main()
