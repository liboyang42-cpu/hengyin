#!/usr/bin/env python3
"""Static source/patch check only; no Java, DB, provider or CI execution."""
from pathlib import Path
import ast
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[5]
DOCS = Path(__file__).resolve().parent
BASE = '10426a532822ee8053cd75e3d77da5b6393d30c2'
CLIENT = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/DashScopeSpeechToTextService.java'
COMMON = 'chengyinhub-common/src/main/java/com/chengyinhub/common/utils/http/HttpUtils.java'
CONFIG = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/LlmClientConfig.java'
OLD_TEST = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/DashScopeSpeechToTextServiceTest.java'
OLD_ADMISSION_TEST = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiHttpAdmissionTest.java'


def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True)


parser = ast.parse(original('docs/delivery/architecture-day/E/VOICE-TRANSPORT/verify-static.py'))
function = next(item for item in parser.body if isinstance(item, ast.FunctionDef) and item.name == 'methods')
namespace = {'re': re}
exec(compile(ast.Module(body=[function], type_ignores=[]), '<frozen-method-reader>', 'exec'), namespace)
methods = namespace['methods']
old, new = original(CLIENT), (ROOT / CLIENT).read_text()
before, after = methods(old), methods(new)
assert len(before) == 7 and len(after) == 8
assert [key[0] for key in before if before[key] != after[key]] == ['transcribe']
old_body = next(v for k, v in before.items() if k[0] == 'transcribe')
new_body = next(v for k, v in after.items() if k[0] == 'transcribe')
assert old_body[old_body.index('        JSONObject result;'):] == new_body[new_body.index('        JSONObject result;'):]
start = old_body.index('        JSONObject audioInput'); end = old_body.index('        String raw;')
old_json = old_body[start:end]
start = new_body.index('                JSONObject audioInput'); end = new_body.index('                raw = HttpUtils.sendJsonPost')
assert re.sub(r'\s+', '', old_json) == re.sub(r'\s+', '', new_body[start:end])
assert new_body.index('audio.length > MAX_AUDIO_BYTES') < new_body.index('acquireAdmission(target)') < new_body.index('Base64.getEncoder()')
assert 'static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;' in new
assert 'VOICE_MAX_BYTES = 2 * 1024 * 1024;' in original('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiNpcController.java')
with tempfile.TemporaryDirectory(prefix='e-stt-patches-') as tmp:
    for path in [COMMON, CONFIG, OLD_TEST, OLD_ADMISSION_TEST]:
        target = Path(tmp) / path; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(original(path))
    voice_patch = Path(tmp) / 'voice.patch'
    voice_patch.write_text(original('docs/delivery/architecture-day/E/VOICE-TRANSPORT/wiring.review.patch'))
    subprocess.run(['git', 'apply', str(voice_patch)], cwd=tmp, check=True)
    subprocess.run(['git', 'apply', str(DOCS / 'common-and-wiring.review.patch')], cwd=tmp, check=True)
    a, b = original(COMMON), (Path(tmp) / COMMON).read_text()
    ma, mb = methods(a), methods(b)
    assert [key[0] for key in ma if ma[key] != mb[key]] == ['sendJsonPost', 'executeAttempt', 'executeBlocking']
    assert len(mb) == len(ma) + 4
    marker = '    public static final class AttemptLease'
    assert a[a.index(marker):] == b[b.index(marker):], 'lease/CAS/policy/cleanup tail changed'
    for key, value in ma.items():
        if key[0] not in ['sendJsonPost', 'executeAttempt', 'executeBlocking']: assert mb[key] == value, key
    extended = next(v for k, v in mb.items() if k[0] == 'sendJsonPost' and 'additionalHeaders' in k[1])
    assert extended.index('checkedJsonHeaders') < extended.index('handedToAttempt = true')
    blocking = next(v for k, v in mb.items() if k[0] == 'executeBlocking' and 'headers' in k[1])
    assert blocking.index('headers.entrySet()') < blocking.index('state.enterDispatch()')
    current_test = (Path(tmp) / OLD_TEST).read_text()
    assert len(re.findall(r'@Test\b', current_test)) == 9
    assert len(re.findall(r'@ParameterizedTest\b', current_test)) == 1
    assertions = lambda s: [l for l in s.splitlines() if re.search(r'\bassert\w+\(', l)]
    assert assertions(original(OLD_TEST)) == assertions(current_test)
    assert 'if (voice.isAsrUsable()) endpoints.add(voice.asrUrl());' in (Path(tmp) / CONFIG).read_text()
print('STATIC PASS: existing 2MiB guard before encoding/admission; six other STT bodies, wire JSON and success parse exact; legacy common APIs/CAS tail retained; both assembly patches apply; old assertions retained')
