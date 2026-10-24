#!/usr/bin/env python3
"""Fixed-source fault-injection witness. No Java execution or production incident claim."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import runpy

ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
BASE='40fdffaa345c4c239553f54f36a4ae9147191a1b'
GATEWAY='chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java'
CREATOR='chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java'
TEST='chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayPreparedLatePreflightTest.java'
method=runpy.run_path(str(ROOT/'docs/delivery/architecture-day/A-theme-prepared/verify_static.py'))['method']


def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def before(p):return git('show',BASE+':'+p)
def sha(data):return hashlib.sha256(data).hexdigest()


def baseline_witness():
    old=before(GATEWAY).decode();creator=before(CREATOR).decode()
    entry=method(old,'generateThemeDraftPrepared');flow=method(old,'generateReserved')
    call='return generateReserved(memberId, plan.role(), "theme_draft", systemPrompt, userPrompt,\n                reservation, plan.nonce(), snapshot, plan, openid);'
    assert entry.index('consumePlannedQuota(')<entry.index(call)
    assert entry.endswith(call+'\n    }')
    input_catch=flow.split('catch (RuntimeException unavailable)',1)[1].split('if (secIn != null)',1)[0]
    assert 'throw new AiServiceException' in input_catch and 'unavailable);' in input_catch
    reject=flow.split('if (secIn != null)',1)[1].split('// 3. 选模型',1)[0]
    assert 'retainQuota(memberId,bizType,reservation,"INPUT_REJECTED");' in reject
    assert 'catch (' not in reject
    creator_flow=method(creator,'executeThemeDraft')
    assert 'if (plan != null && reservation == null && !"player".equals(role)) throw failure;' in creator_flow
    return {'baseline':BASE,'gateway_sha256':sha(before(GATEWAY)),'creator_sha256':sha(before(CREATOR)),
     'gen_consumed_before_unprotected_reserved_call':True,
     'input_stage_and_review_already_wrapped_as_ordinary_ai_service_exception':True,
     'actual_unprotected_site':'generateReserved -> retainQuota(..., INPUT_REJECTED)',
     'fault_schedule':['valid gen plan preflight','consume original quota succeeds',
       'input review returns explicit rejection','INPUT_REJECTED retain throws AiThemeDraftPlanRejectedException',
       'old Gateway returns that outer type; old Creator gen branch rethrows it'],
     'conclusion':'STATIC_FAULT_INJECTION_ESCAPE_WITNESS; not a production occurrence or executed JUnit failure',
     'old_stage_review_negative_controls':'EXPECTED_TO_ALREADY_PASS; not claimed as old red tests',
     'java_mysql_ci_executed':0}


def main():
    witness=baseline_witness()
    new=(ROOT/GATEWAY).read_text()
    entry=method(new,'generateThemeDraftPrepared')
    assert 'return generateReserved(' in entry
    call=entry.index('return generateReserved(')
    catch=entry.index('catch (AiThemeDraftPlanRejectedException lateFailure)',call)
    assert entry.index('consumePlannedQuota(')<call<catch
    suffix=entry[catch:]
    assert 'new AiServiceException' in suffix and 'AI 草稿原计划执行失败' in suffix
    assert 'lateFailure);' not in suffix
    assert 'catch (RuntimeException' not in suffix and 'catch (AiServiceException' not in suffix
    # The intended production delta is only the post-consumption call boundary.
    expected=before(GATEWAY).decode().replace(
      '        return generateReserved(memberId, plan.role(), "theme_draft", systemPrompt, userPrompt,\n                reservation, plan.nonce(), snapshot, plan, openid);',
      '        try {\n            return generateReserved(memberId, plan.role(), "theme_draft", systemPrompt, userPrompt,\n                    reservation, plan.nonce(), snapshot, plan, openid);\n        } catch (AiThemeDraftPlanRejectedException lateFailure) {\n            // Quota/review may already have effects; only this local type loses preflight authority.\n            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,\n                    "AI 草稿原计划执行失败");\n        }',1)
    assert new==expected
    protected=[CREATOR,'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiThemeDraftPlanRejectedException.java',
     'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorServiceImplTest.java',
     'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorPreparedThemeDraftTest.java',
     'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayPreparedThemeDraftTest.java']
    hashes={}
    for p in protected:
      assert (ROOT/p).read_bytes()==before(p),p
      hashes[p]=sha(before(p))
    changed=git('diff','--name-only',BASE).decode().splitlines()
    untracked=git('ls-files','--others','--exclude-standard').decode().splitlines()
    assert all(p in {GATEWAY,TEST} or p.startswith('docs/delivery/architecture-day/A-theme-late-preflight/') for p in changed+untracked)
    text=(ROOT/TEST).read_text()
    singles=len(re.findall(r'@Test\b',text))
    parameters=[len(re.findall(r'"[^"]*"',v)) for v in re.findall(r'@ValueSource\(strings=\{(.*?)\}\)',text,re.S)]
    assert singles+sum(parameters)==13
    result={'new_source_expected_cases':13,'baseline_witness':witness,'candidate_gateway_sha256':sha((ROOT/GATEWAY).read_bytes()),
      'production_delta_exact_boundary_only':True,'protected_files_byte_preserved':hashes,
      'java_compile':'NOT_RUN_E_PLAN_REQUIRED','junit_executed':0,'ci_dispatches':0}
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
