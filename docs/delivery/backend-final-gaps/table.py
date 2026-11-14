import json
D="/data/workspace/be-int/final-gaps/"
rows=json.load(open(D+"probe.json")); their=set(open(D+"their404.txt").read().split())
NOTE={
"/api/club/update":("app literal, not called","the app sends `POST api/club/update-mine` (`ClubOperationsContracts.swift:135`, `RePoint03ClubUpdate.swift:11`), which is served; `api/club/update` is only a label in `ChannelDiscordMessaging.swift:3029`"),
"/api/config/public":("app literal, not called","only text in `AuthAndSessionAdmissionParityAudit.swift:90` and `BackendSyncAndContractGateAudit.swift:61`; no request is built from it"),
"/api/order/":("app literal, not called","only a label in `App/NativeSupersetOverMiniProgramSectionView.swift:108` (`api/order/* · …`)"),
"/api/roam/check-in.":("app literal, not called","sentence text in `App/RoamAndExplorationParitySectionView.swift:134`; the backend route is `POST /api/roam/checkin`"),
"/api/roam/finished":("app literal, not called","summary text in `App/RoamAndExplorationParitySectionView.swift:109`; backend has `POST /api/roam/finish`"),
"/api/roam/point/{}":("app literal, not called","summary text in `App/RoamAndExplorationParitySectionView.swift:109`"),
"/api/roam/route/{}":("app literal, not called","summary text in `App/RoamAndExplorationParitySectionView.swift:109`"),
"/api/roam/routes":("app literal, not called","summary text in `App/RoamAndExplorationParitySectionView.swift:109`"),
"/api/stamps":("app literal, not called","summary text in `App/RoamAndExplorationParitySectionView.swift:49,125`; backend stamps are `POST /api/roam/stamp/{list,received}`"),
"/api/user/stamps/received":("app literal, not called","summary text in `App/RoamAndExplorationParitySectionView.swift:49,125` and `MiniProgramVsNativeSupersetClassifierSectionView.swift:92`; backend is `POST /api/roam/stamp/received`"),
"/api/user/coupons/{}/use":("app literal, not called","text \"… remain fail-closed\" in `App/RoamAndExplorationParitySectionView.swift:118`"),
"/api/template/report-receipt":("app literal, not called","`TemplateContentReportTarget.receiptPath`/`readPaths` (`TemplateContentReportContracts.swift:11-13`) are never referenced; the report write goes through `api/template/report` / `personal-report` (served) with no receipt decoder to implement"),
"/api/topic/detail":("app literal, not called","repointed to `POST /api/topic/info-to-user` (`RePoint10Routes.swift:22`, `Adapt2MerchantTopicRoutes.swift:39`); otherwise a default display string in `PublishTopicAndCommentModerationSectionView.swift:78`"),
}
def short(x): return "%s `%s` → %s/%s%s" % (x["verb"], x["url"], x["http"], x["code"], (" "+(x["errorCode"] or x["msg"][:28])) if x["code"]!=200 else "")
out=[]; cnt={}
for r in rows:
    p=r["path"]; ps=[x for x in r["probes"] if x["route"]!="(prefix itself)" and not x["route"].endswith("(app verb)")]
    s=sum(1 for x in ps if x["k"]=="served")
    if p in NOTE: cls,ev=NOTE[p]
    elif r["st"]=="closed": cls="intentionally closed"; ev="`ApiAppGapsWorkshopController` OFF stub (`%s`), %d/%d routes; %s" % (ps[0]["errorCode"] or ps[0]["msg"], len(ps), len(ps), short(ps[0]))
    elif r["st"]=="served":
        cls="served (app table wrong)" if r["kind"]=="exact" else "served via children (prefix artifact)"
        if r["kind"]=="exact" and p in their: cls="served via children (`{}` is an action segment)"
        ex=[x for x in ps if x["k"]=="served"][:2]
        ev="%d/%d backend routes answer; %s" % (s,len(ps),"; ".join(short(x) for x in ex))
        if p.startswith("/api/reward-pledge") and any(x["errorCode"]=="POLICY_CLOSED" for x in ps): ev+="; `POLICY_CLOSED` rows are the reviewer gate (9004 reviewer gets 200)"
    else: cls="404"; ev=short(ps[0]) if ps else ""
    if p=="/api/merchant/offer-withdrawal/request/": ev+="; with the app's query items and body the full protocol is 200 and WITHDRAWN (see §1, `smoke-ow.log`)"
    cnt[cls]=cnt.get(cls,0)+1
    out.append("| `%s` | %s | %s | %s |" % (p, "404" if p in their else "answers", cls, ev.replace("|","\\|")))
open(D+"table.md","w").write("\n".join(out)+"\n")
print(cnt, len(out), "their404 rows:", sum(1 for r in rows if r["path"] in their))
import collections
c2=collections.Counter()
for r,l in zip(rows,out):
    if r["path"] in their: c2[l.split(" | ")[2]]+=1
print("their 59 →",dict(c2))
