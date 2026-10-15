"""A02 source guard only; does not execute or compile Java."""
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[4]
prefix = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/'
def read(path):
    if len(sys.argv) > 1:
        return subprocess.check_output(['git', 'show', sys.argv[1] + ':' + path], cwd=root, text=True)
    return (root / path).read_text()
router = read(prefix + 'AiSceneRouter.java')
gateway = read(prefix + 'impl/AiGatewayServiceImpl.java')
checks = {
    'unknown route cannot fall back to mock': 'FALLBACK = "mock"' not in router and 'Reason.UPSTREAM_UNAVAILABLE' in router,
    'neither provider resolver falls back to mock': 'm.get("mock")' not in gateway and 'requireAvailableClient' in gateway,
    'full assembled input enters review': 'checkTextFully(assembledInput, openid)' in gateway and 'truncate(userPrompt, 2000)' not in gateway,
    'output is reviewed using same complete review helper': 'checkTextFully(modelText, openid)' in gateway,
    'output review outage classified as unavailable': 'isDetectionUnavailable(secOut)' in gateway,
    'empty provider output cannot succeed': 'modelText == null || modelText.trim().isEmpty()' in gateway,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + name)
print('SOURCE GUARD ONLY: %d/%d passed; Java runtime remains unverified' % (sum(checks.values()), len(checks)))
sys.exit(0 if all(checks.values()) else 1)
