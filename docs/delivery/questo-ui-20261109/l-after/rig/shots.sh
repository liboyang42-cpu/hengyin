#!/bin/bash
# l-after 拍照脚本。usage: shots.sh <outdir> <before|after> [only-id]
# 拖动帧用 eval 直接喂页面自己的 onDeckStart/onDeckMove(rig 只有鼠标没有真触摸)。
OUT=$1; SUF=$2; ONLY=$3
cd $HOME/rig/docs/delivery/cloud-walk-20261101/rig
export RIG_SHOTS=$OUT
W() { id=$1; shift; [ -n "$ONLY" ] && [ "$ONLY" != "$id" ] && return; echo "== $id"; timeout 240 node mpweb/walk.mjs "$1" "$2" 2>&1 | grep -E "^(ok|FAIL|ERR|fail)|page errors|^\[pageerror|Error|TEXT" | head -12; }
ID='"identity":"e2e-openid-9004","agree":true'
P='var p=getCurrentPages().pop();'
T() { echo "{\"eval\":\"$P p.onDeckMove({touches:[{clientX:$1,clientY:420}]}); true\"},{\"wait\":60}"; }
W wallet "{\"path\":\"subpackageMember/signup/index\",$ID}" "[{\"wait\":4000},{\"shot\":\"wallet-$SUF\"},{\"tapSel\":\".wc\"},{\"wait\":900},{\"shot\":\"wallet-flip-$SUF\"},{\"tapSel\":\".wc\"},{\"wait\":900},{\"eval\":\"$P p.onDeckStart({touches:[{clientX:330,clientY:420}]}); true\"},$(T 290),$(T 230),{\"shot\":\"wallet-drag-1-$SUF\"},$(T 160),{\"shot\":\"wallet-drag-2-$SUF\"},{\"eval\":\"$P p.onDeckEnd(); true\"},{\"wait\":180},{\"shot\":\"wallet-drag-3-$SUF\"},{\"wait\":900},{\"shot\":\"wallet-drag-4-$SUF\"}]"
W album "{\"path\":\"pages/member/index/index\",$ID}" "[{\"wait\":3000},{\"tap\":\"成就\"},{\"wait\":1500},{\"eval\":\"var c=getCurrentPages().pop().selectComponent('#profile'); c && c.setData({profileMetricState:'ready'}); !!c\"},{\"wait\":800},{\"scroll\":400},{\"wait\":800},{\"tap\":\"邮票\"},{\"wait\":1900},{\"shot\":\"album-ring-$SUF\"},{\"wait\":1300},{\"shot\":\"album-ring-2-$SUF\"},{\"tapSel\":\".ar__next\"},{\"wait\":120},{\"shot\":\"album-flip-$SUF\"},{\"wait\":700},{\"shot\":\"album-flip-2-$SUF\"}]"
W passes "{\"path\":\"subpackageMember/market/passes/index\",$ID}" "[{\"wait\":3500},{\"shot\":\"passes-$SUF\"}]"
# 结算四步(玩家 9004 在 rig 里真实通关了主题 990014:两站到达 + 答题「3」)
W settle "{\"path\":\"subpackageJourney/pages/settle/index?topicId=990014&topicName=%E5%9F%8E%E5%B8%82%E5%AE%9A%E5%90%91%E6%A0%B7%E6%9D%BF\",$ID}" "[{\"wait\":1200},{\"shot\":\"settle-overview-f1-$SUF\"},{\"wait\":3300},{\"shot\":\"settle-overview-$SUF\"},{\"tap\":\"看排行榜\"},{\"wait\":260},{\"shot\":\"settle-rank-f1-$SUF\"},{\"wait\":2400},{\"shot\":\"settle-rank-$SUF\"},{\"tap\":\"评价与分享\"},{\"wait\":1200},{\"shot\":\"settle-rate-$SUF\"},{\"tapSel\":\".st-star\"},{\"wait\":700},{\"shot\":\"settle-rate-stars-$SUF\"},{\"tap\":\"提交\"},{\"wait\":800},{\"shot\":\"settle-rate-error-$SUF\"},{\"tap\":\"跳过\"},{\"wait\":2500},{\"shot\":\"settle-share-$SUF\"},{\"tap\":\"故事\"},{\"wait\":1500},{\"shot\":\"settle-share-story-$SUF\"}]"
W library "{\"path\":\"subpackageMember/market/library/index\",$ID}" "[{\"wait\":3500},{\"shot\":\"library-$SUF\"},{\"tap\":\"自由探索\"},{\"wait\":2500},{\"shot\":\"library-free-$SUF\"}]"
# 「邀请好友」行在「我的」页的资料段里,rig 滚不到那一段,直接调用页面自己的 goInvite(与真实点击同一方法)
W invite-me "{\"path\":\"pages/member/index/index\",$ID}" "[{\"wait\":3000},{\"eval\":\"var c=getCurrentPages().pop().selectComponent('#profile'); c && c.goInvite(); !!c\"},{\"wait\":2500},{\"shot\":\"invite-me-$SUF\"}]"
W wallet-filter "{\"path\":\"subpackageMember/signup/index\",$ID}" "[{\"wait\":4000},{\"tapSel\":\".wl-nav__flt\"},{\"wait\":500},{\"shot\":\"wallet-filter-$SUF\"},{\"tap\":\"已通关\"},{\"wait\":1500},{\"shot\":\"wallet-filter-done-$SUF\"}]"
