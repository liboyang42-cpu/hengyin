# Writes pages2.json: fixture runs (dummy data, browser only) for states the seed cannot reach.
import json, sys
def wrap(pattern, js_mutate, delay=0):
    # wraps window.fetch: responses whose URL matches `pattern` get `js_mutate` applied to their JSON (j), optionally delayed
    return ("(function(){const of=window.fetch;window.fetch=async function(u,o){const s=String(u&&u.url||u);"
            "if(!new RegExp(%s).test(s))return of.apply(this,arguments);" % json.dumps(pattern) +
            ("await new Promise(r=>setTimeout(r,%d));" % delay if delay else "") +
            "const r=await of.apply(this,arguments);const j=await r.clone().json();try{%s}catch(e){}" % js_mutate +
            "return new Response(JSON.stringify(j),{status:r.status,headers:{'content-type':'application/json'}});};return 'wrapped';})()")
SD = "(function(){const p=getCurrentPages().slice(-1)[0];p.setData(%s);return 'ok';})()"
gate = [{"tap": "暂不定位"}, {"wait": 600}, {"tap": "我已阅读并同意"}, {"tap": "同意并继续"}, {"wait": 1500}, {"tap": "暂不定位"}, {"wait": 800}]
home_fill = ("(function(){const p=getCurrentPages().slice(-1)[0];const t=p.data.bottomTopicList||[];if(!t.length)return 0;"
             "const reco=t.slice(0,1).concat(t.slice(0,1)).map((x,i)=>Object.assign({},x,{id:x.id+'-'+i}));"
             "const near=[0,1,2].map(i=>Object.assign({},t[0],{id:'n'+i,distance:'1.2'+i+'km'}));"
             "const up=[0,1,2].map(i=>Object.assign({},t[0],{id:'u'+i}));"
             "p.setData({recommendedTopicList:reco,nearbyActivityList:near,upcomingActivityList:up},()=>{p._buildRecoHero&&p._buildRecoHero();});return t.length;})()")
rows = [dict(memberId=900+i, displayName=n, avatarName=n[-1], tier='done', tierText='已核销', actionText='上次到店 11 月 2 日 · 探店日一期', latestNote=(note if i == 1 else ''), phoneText='138****000%d' % i)
        for i, (n, note) in enumerate([('测试玩家甲', ''), ('测试玩家乙', '喜欢靠窗的位置，下次留座'), ('测试玩家丙', ''), ('测试玩家丁', '')])]
items = lambda k: [dict(refundId='r%s%d' % (k, i), titleText='测试玩家%s · 探店日一期' % '甲乙丙'[i], timeText='14:0%d' % i, statusText='待处理', statusTone='warning', refundAmountText='¥23.00') for i in range(2)]
groups = [dict(key='g1', label='11 月 3 日', totalText='¥46.00', items=items('a')), dict(key='g2', label='11 月 2 日', totalText='¥46.00', items=items('b'))]
P = [
 {"name": "fx-home-content", "path": "pages/index/index", "id": "e2e-openid-9009", "steps": gate + [{"wait": 2000}, {"eval": home_fill}, {"wait": 1500}, {"scroll": 380}]},
 {"name": "fx-home-top", "path": "pages/index/index", "id": "e2e-openid-9009", "steps": gate + [{"wait": 2000}, {"eval": home_fill}, {"wait": 1500}]},
 {"name": "fx-merchant-customer-rows", "path": "pages/merchant/customer/index", "id": "e2e-openid-9002", "steps": [{"wait": 1500}, {"eval": SD % json.dumps({"rows": rows, "loading": False, "total": 4}, ensure_ascii=False)}, {"wait": 800}]},
 {"name": "fx-merchant-aftercare-rows", "path": "pages/merchant/aftercare/index", "id": "e2e-openid-9002", "steps": [{"wait": 1500}, {"eval": SD % json.dumps({"pageState": "ready", "groups": groups, "items": items('a') + items('b'), "hasMore": False}, ensure_ascii=False)}, {"wait": 800}]},
 {"name": "fx-owner0-self-header-missing", "path": "subpackageMember/points-history/index", "id": "e2e-openid-9009", "steps": [{"eval": wrap("/api/user/info", "if(j.data){delete j.data.friendNum;delete j.data.followNum;delete j.data.fansNum;}")}, {"open": "pages/member/index/index"}, {"wait": 2000}]},
 {"name": "fx-owner0-self-header-preload", "path": "subpackageMember/points-history/index", "id": "e2e-openid-9009", "steps": [{"eval": wrap("/api/user/info", "", 8000)}, {"open": "pages/member/index/index"}]},
 {"name": "fx-owner0-public-achievements-missing", "path": "subpackageMember/points-history/index", "id": "e2e-openid-9009", "steps": [{"eval": wrap("/api/user/public-info", "if(j.data){delete j.data.topicNum;delete j.data.activityNum;delete j.data.likeNum;delete j.data.fansNum;}")}, {"open": "pages/userinfo/userinfo?id=9008"}, {"wait": 1500}, {"tap": "成就"}, {"wait": 1200}]},
 {"name": "fx-owner0-public-achievements-real", "path": "pages/userinfo/userinfo?id=9008", "id": "e2e-openid-9009", "steps": [{"wait": 1500}, {"tap": "成就"}, {"wait": 1200}]},
 {"name": "fx-anniversary-loading", "path": "subpackageTools/anniversary/index", "id": "e2e-openid-9009", "steps": [{"eval": SD % json.dumps({"busy": True, "rows": []})}, {"wait": 900}], "settle": 300},
 {"name": "fx-myteams-loading", "path": "subpackageRoam/my-teams/index", "id": "e2e-openid-9009", "steps": [{"eval": SD % json.dumps({"loading": True, "groups": [], "tab": "mine"})}, {"wait": 900}], "settle": 300},
 {"name": "fx-club-joinreq-pending", "path": "pages/club/join-requests/index?clubId=7001", "id": "e2e-openid-9003", "steps": [{"wait": 1500}]},
 {"name": "fx-merchant-ledger-settlement", "path": "pages/merchant/ledger/index?view=settlement", "id": "e2e-openid-9002", "steps": [{"wait": 2000}]},
 {"name": "fx-merchant-batch-ledger", "path": "pages/merchant/ledger/index", "id": "e2e-openid-9002", "steps": [{"wait": 2000}]},
]
json.dump(P, open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
print(len(P))
