#!/usr/bin/env python3
"""Static evidence only: no Java/JUnit/server/DB/CI invocation."""
import ast
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

BASE = "4197f6a6b11d62cb0a742aae73d7167c93114585"
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiThemeDraftHttpSecurityTest.java"
DOC = "docs/delivery/architecture-day/A-theme-http-security/"
PROTECTED = [
    "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java",
    "chengyinhub-framework/src/main/java/com/chengyinhub/framework/security/filter/JwtAuthenticationTokenFilter.java",
    "chengyinhub-framework/src/main/java/com/chengyinhub/framework/web/service/AppTokenService.java",
    "chengyinhub-framework/src/main/java/com/chengyinhub/framework/web/service/TokenService.java",
    "chengyinhub-common/src/main/java/com/chengyinhub/common/core/controller/BaseController.java",
    "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiController.java",
    "chengyinhub-admin/src/test/java/com/chengyinhub/framework/config/AppSessionSecurityChainTest.java",
    "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiControllerTest.java",
    "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java",
    "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/ai/AiThemeDraftIntentView.java",
]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    changed = set(git("diff", "--name-only", BASE).decode().splitlines())
    changed |= set(git("ls-files", "--others", "--exclude-standard").decode().splitlines())
    assert all(p == TEST or p.startswith(DOC) for p in changed)
    hashes = {}
    for path in PROTECTED:
        raw = (ROOT / path).read_bytes()
        assert raw == git("show", f"{BASE}:{path}"), path
        hashes[path] = digest(raw)
    source = (ROOT / TEST).read_text()
    for component in ("SecurityConfig.class", "JwtAuthenticationTokenFilter.class", "AppTokenService.class",
                      "TokenService.class", "ApiAiController.class", "AuthenticationEntryPointImpl.class"):
        assert component in source
    assert 'webAppContextSetup(context).addFilters((Filter) chain)' in source
    for forbidden in ("extends ApiAiController", "getAppUserId(", "setAuthentication(", "setContext(",
                      "standaloneSetup", "@WithMockUser", "SecurityMockMvcRequestPostProcessors",
                      "mock(JwtAuthenticationTokenFilter.class)", "mock(SecurityConfig.class)",
                      "mock(AppTokenService.class)", "mock(TokenService.class)", "@Disabled", "Assumptions."):
        assert forbidden not in source, forbidden
    for required in ('assertInstanceOf(LoginAppUser.class', 'principal.getAppUser().getUserId()',
                     'verifyNoInteractions(requests, creator, insights, merchants)',
                     'appTokens.createAppToken(', 'adminTokens.createToken(', 'adminTokens.getLoginUser(backend)',
                     'CacheConstants.LOGIN_TOKEN_KEY', 'CacheConstants.LOGIN_APP_TOKEN_KEY',
                     'HttpMessageNotReadableException.class', 'verifyNoMoreInteractions(requests)',
                     'requests).readPrepared(A, PREPARE)', 'requests).readRecovery(actor, CURSOR)'):
        assert required in source, required
    routes = re.search(r'enum Route \{([^}]+)\}', source).group(1).split(',')
    credentials = re.search(r'enum InvalidCredential \{([^}]+)\}', source).group(1).split(',')
    assert len(routes) == 4 and len(credentials) == 4
    counts = {"anonymous": len(routes), "invalid_app": len(routes) * len(credentials),
              "valid_admin_wrong_entry": len(routes), "valid_actual_app_actors": len(routes) * 2,
              "body_member_spoof": 2, "prepare_key_read": 1}
    assert sum(counts.values()) == 35
    assert source.count('@ParameterizedTest') == 5
    assert len(re.findall(r'^\s*@Test\s*$', source, re.M)) == 1
    secret = re.search(r'SIGNING_KEY = "([^"]+)"', source).group(1)
    assert base64.b64decode(secret) == bytes(64)
    stripped = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/', '', source)
    stack = []
    for char in stripped:
        if char in '({[':
            stack.append(char)
        elif char in ')}]':
            assert stack.pop() == {')': '(', '}': '{', ']': '['}[char]
    assert not stack
    ast.parse(Path(__file__).read_text())
    git("diff", "--check", BASE)
    print(json.dumps({
        "base": BASE, "new_test": TEST, "new_test_sha256": digest(source.encode()),
        "authored_cases": counts, "total_authored": sum(counts.values()), "executed_cases": 0,
        "protected_source_sha256": hashes,
        "checks": "PASS: scope, exact protected bytes, component wiring witnesses, no auth override/mock, lexical delimiters, git diff --check",
        "baseline_compile_risk": "existing ApiAiControllerTest uses 9-argument AiThemeDraftIntentView; current canonical record has 10 fields, no overload",
        "not_run": ["Java production/test compilation", "JUnit/MockMvc", "JWT runtime", "MySQL/Redis", "CI", "provider/HTTP/backend"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
