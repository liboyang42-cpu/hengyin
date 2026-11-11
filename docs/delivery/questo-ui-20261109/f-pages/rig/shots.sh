#!/bin/bash
# f-pages 前后截图。用法: RIG=<cloud-walk rig 目录> RIG_SHOTS=<输出目录> shots.sh <before|after> [id]
# before = 用 launch 底座的 chengyinhub-xcx 打包,after = 用本分支打包(CAND 指向对应 worktree 后跑 mpweb/build.sh)。
P=$1; ONLY=${2:-all}; R=${RIG:?set RIG}
W(){ (cd $R && timeout 200 node mpweb/walk.mjs "$1" "$2" 2>&1 | grep -E "^(ERR|Error|fail|---.*page errors|\[pageerror)" ); }
ID='"identity":"e2e-openid-9004","agree":true'
run(){ local id=$1; shift; [ "$ONLY" = all ] || [ "$ONLY" = "$id" ] || return 0; echo "== $id"; W "$@"; }
# P-20 / M-33 本人主页帖文 tab(空态上方的发帖行)
run P-20 "{\"path\":\"pages/member/index/index\",$ID}" "[{\"wait\":3000},{\"tap\":\"帖文\"},{\"wait\":1500},{\"shot\":\"P-20-$P\"}]"
# P-56 个人资料手机号行
run P-56 "{\"path\":\"pages/gerenziliao/gerenziliao\",$ID}" "[{\"wait\":2500},{\"shot\":\"P-56-$P\"}]"
# 页签是 cy-tabs:文字点不中时走页面自己的 onFavoriteTabChange(与点页签同一处理)
NODESTAB="(()=>{const p=getCurrentPages().slice(-1)[0];if(p.data.activeFavoriteTab!=='nodes'&&p.data.favoriteTabs.some(t=>t.key==='nodes'))p.onFavoriteTabChange({detail:{key:'nodes'}});return p.data.activeFavoriteTab})()"
# OFF-FAV-01 我的收藏「据点」页签
run OFF-FAV-01 "{\"path\":\"pages/mylike/mylike\",$ID}" "[{\"wait\":2500},{\"shot\":\"OFF-FAV-01-tabs-$P\"},{\"tryTap\":\"据点\"},{\"eval\":\"$NODESTAB\"},{\"wait\":1500},{\"shot\":\"OFF-FAV-01-$P\"}]"
# 横滑行里第二张大卡在屏外:穿透 shadow DOM 找到文字为「官方路线」的节点滚进视口;点卡走页面自己的 onPassportPlayTap(key=routes)
SCROLLROW="(()=>{const walk=(r)=>{for(const el of r.querySelectorAll('*')){if(el.shadowRoot){const f=walk(el.shadowRoot);if(f)return f}if(el.childElementCount===0&&el.textContent.trim()==='官方路线')return el}return null};const t=walk(document);if(t)t.scrollIntoView({inline:'center',block:'nearest'});return !!t})()"
OPENROUTES="(()=>{const p=getCurrentPages().slice(-1)[0];const k=p.data.passport.plays.map(x=>x.key);p.onPassportPlayTap({currentTarget:{dataset:{key:'routes'}}});return JSON.stringify(k)})()"
# P0-79 漫游护照「官方路线」大卡(rig 库里放了两条已上架路线)
run P0-79 "{\"path\":\"pages/roam/index\",$ID}" "[{\"wait\":3500},{\"tap\":\"漫游护照\"},{\"wait\":2500},{\"scroll\":500},{\"wait\":1200},{\"eval\":\"$SCROLLROW\"},{\"wait\":800},{\"shot\":\"P0-79-$P\"},{\"eval\":\"$OPENROUTES\"},{\"wait\":2500},{\"shot\":\"P0-79-list-$P\"}]"
