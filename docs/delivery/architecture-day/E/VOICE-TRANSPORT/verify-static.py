#!/usr/bin/env python3
"""Source/assembly-patch equivalence only; never starts Java, a provider, or a database."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[5]
DOCS = Path(__file__).resolve().parent
BASE = 'de7f432ec6f0c1c6225168ecf2e4808210de42e0'
CLIENT = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/DashScopeVoiceClient.java'
CONFIG = CLIENT.replace('DashScopeVoiceClient.java', 'LlmClientConfig.java')
FIXTURE = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiHttpAdmissionTest.java'


def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True)


def methods(source):
    masked = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                    lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), source, flags=re.S)
    pattern = re.compile(r'^    (?![ \t])(?:(?:public|private|protected|static|final|synchronized)\s+)*'
                         r'(?:[\w.$<>?,\[\] ]+?\s+)?(?P<name>\w+)\s*\([^;{}]*?\)\s*'
                         r'(?:throws\s+[\w.,\s]+)?\{', re.M)
    result = {}
    for match in pattern.finditer(masked):
        begin = match.end() - 1
        depth = 0
        for end in range(begin, len(masked)):
            if masked[end] == '{': depth += 1
            elif masked[end] == '}':
                depth -= 1
                if depth == 0: break
        header = source[match.start():begin]
        parameters = re.sub(r'\s+', '', header[header.index('('):header.rindex(')') + 1])
        result[(match['name'], parameters)] = source[begin:end + 1]
    return result


old, new = original(CLIENT), (ROOT / CLIENT).read_text()
before, after = methods(old), methods(new)
assert len(before) == len(after) == 7
assert [key[0] for key in before if before[key] != after[key]] == ['post']
assert old[old.index('        JSONObject json;'):] == new[new.index('        JSONObject json;'):]
post = next(value for key, value in after.items() if key[0] == 'post')
assert post.count('HttpUtils.sendJsonPost(') == 1 and 'HttpRequest.post' not in new
assert post.index('admission.acquire(url)') < post.index('body.toJSONString()') < post.index('JSONObject json;')
assert 'new AiServiceException(what + "失败,请稍后再试", e)' in post
assert old == original(CLIENT)
assert (ROOT / CONFIG).read_text() == original(CONFIG)
assert (ROOT / FIXTURE).read_text() == original(FIXTURE)
with tempfile.TemporaryDirectory(prefix='e-voice-wiring-') as tmp:
    for path in [CONFIG, FIXTURE]:
        target = Path(tmp) / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(original(path))
    subprocess.run(['git', 'apply', str(DOCS / 'wiring.review.patch')], cwd=tmp, check=True)
    configured = (Path(tmp) / CONFIG).read_text()
    assert 'AiVoiceConfig voice)' in configured and 'endpoints.add(voice.baseUrl())' in configured
    old_test, new_test = original(FIXTURE), (Path(tmp) / FIXTURE).read_text()
    assert len(re.findall(r'@Test\b', old_test)) == len(re.findall(r'@Test\b', new_test)) == 8
    assert [line for line in old_test.splitlines() if re.search(r'\bassert\w+\(', line)] == [line for line in new_test.splitlines() if re.search(r'\bassert\w+\(', line)]
print('STATIC PASS: six public/helper bodies and parse tail exact; one existing admitted POST call; required fixed-origin/old-fixture patch applies; eight original cases/assertions retained')
