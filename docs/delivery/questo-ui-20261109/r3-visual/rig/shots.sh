#!/bin/bash
# usage: shots.sh <outdir> <suffix: before|after> [only-id]
# r3-visual 拍照脚本。拖动帧用 eval 直接喂页面自己的 onDeckStart/onDeckMove(rig 只有鼠标没有真触摸)。
OUT=$1; SUF=$2; ONLY=$3
cd $HOME/rig/docs/delivery/cloud-walk-20261101/rig
export RIG_SHOTS=$OUT
W() { id=$1; shift; [ -n "$ONLY" ] && [ "$ONLY" != "$id" ] && return; echo "== $id"; timeout 180 node mpweb/walk.mjs "$1" "$2" 2>&1 | grep -E "^(ok|FAIL|ERR|fail)|page errors|^\[pageerror|Error" | head -12; }
ID='"identity":"e2e-openid-9004","agree":true'
P='var p=getCurrentPages().pop();'
T() { echo "{\"eval\":\"$P p.onDeckMove({touches:[{clientX:$1,clientY:420}]}); true\"},{\"wait\":60}"; }
W r3-ticket "{\"path\":\"subpackageMember/signup/index\",$ID}" "[{\"wait\":4000},{\"shot\":\"r3-ticket-$SUF\"},{\"eval\":\"$P p.onDeckStart({touches:[{clientX:330,clientY:420}]}); true\"},$(T 290),{\"shot\":\"r3-ticket-drag-1-$SUF\"},$(T 230),{\"shot\":\"r3-ticket-drag-2-$SUF\"},$(T 160),{\"shot\":\"r3-ticket-drag-3-$SUF\"},{\"eval\":\"$P p.onDeckEnd(); true\"},{\"wait\":180},{\"shot\":\"r3-ticket-drag-4-$SUF\"},{\"wait\":900},{\"shot\":\"r3-ticket-drag-5-$SUF\"}]"
W r3-album "{\"path\":\"pages/member/index/index\",$ID}" "[{\"wait\":3000},{\"tap\":\"成就\"},{\"wait\":1500},{\"eval\":\"var c=getCurrentPages().pop().selectComponent('#profile'); c && c.setData({profileMetricState:'ready'}); !!c\"},{\"wait\":800},{\"scroll\":400},{\"wait\":800},{\"tap\":\"邮票\"},{\"wait\":350},{\"shot\":\"r3-album-1-$SUF\"},{\"wait\":700},{\"shot\":\"r3-album-2-$SUF\"},{\"wait\":1300},{\"shot\":\"r3-album-3-$SUF\"},{\"wait\":1200},{\"shot\":\"r3-album-4-$SUF\"},{\"tapSel\":\".ar__next\"},{\"wait\":0},{\"shot\":\"r3-album-flip-1-$SUF\"},{\"wait\":120},{\"shot\":\"r3-album-flip-2-$SUF\"},{\"wait\":700},{\"shot\":\"r3-album-flip-3-$SUF\"},{\"tapSel\":\".ar__card--r0\"},{\"wait\":2500},{\"shot\":\"r3-album-enter-$SUF\"}]"

# 首屏动效太快,页面就绪后用 eval 让组件自己再走一次首屏(load(true) + playEarn),逐帧拍
W r3-invite "{\"path\":\"subpackageMember/myinvite/myinvite\",$ID}" "[{\"wait\":3000},{\"shot\":\"r3-invite-$SUF\"},{\"eval\":\"var c=getCurrentPages().pop().selectComponent('#inviteHistory'); c._earnPlayed=false; c.setData({earnStage:0}); c.load(true); true\"},{\"wait\":120},{\"shot\":\"r3-invite-1-$SUF\"},{\"wait\":300},{\"shot\":\"r3-invite-2-$SUF\"},{\"wait\":400},{\"shot\":\"r3-invite-3-$SUF\"},{\"wait\":1500},{\"shot\":\"r3-invite-4-$SUF\"}]"
