#!/bin/bash
# usage: shots.sh <outdir> <suffix: before|after> [only-id]
OUT=$1; SUF=$2; ONLY=$3
cd $HOME/rig/docs/delivery/cloud-walk-20261101/rig
export RIG_SHOTS=$OUT
W() { id=$1; shift; [ -n "$ONLY" ] && [ "$ONLY" != "$id" ] && return; echo "== $id"; timeout 150 node mpweb/walk.mjs "$1" "$2" 2>&1 | grep -E "^(ok|FAIL|ERR|fail)|page errors|^\[pageerror|Error" | head -12; }
ID='"identity":"e2e-openid-9004","agree":true'
W a2-v3-18 "{\"path\":\"pages/roam/index\",$ID}" "[{\"wait\":2500},{\"eval\":\"getCurrentPages().pop().openEntryLayers()\"},{\"wait\":1200},{\"shot\":\"a2-v3-18-$SUF\"}]"
W a3-v1-p53 "{\"path\":\"pages/shezhi/shezhi\",$ID}" "[{\"wait\":2000},{\"tap\":\"投诉建议\"},{\"wait\":1800},{\"shot\":\"a3-v1-p53-$SUF\"}]"
W a3-v3-22 "{\"path\":\"subpackageMember/order/order\",$ID}" "[{\"wait\":3000},{\"shot\":\"a3-v3-22-$SUF\"}]"
W a3-v3-02 "{\"path\":\"subpackageMember/order/order\",$ID}" "[{\"wait\":3000},{\"tap\":\"查看详情\"},{\"wait\":3000},{\"shot\":\"a3-v3-02-$SUF\"},{\"scroll\":700},{\"wait\":800},{\"shot\":\"a3-v3-03-$SUF\"}]"
W a4-v3-34 "{\"path\":\"pages/member/index/index\",$ID}" "[{\"wait\":3000},{\"tap\":\"成就\"},{\"wait\":1500},{\"eval\":\"var c=getCurrentPages().pop().selectComponent('#profile'); c && c.setData({profileMetricState:'ready'}); !!c\"},{\"wait\":800},{\"scroll\":400},{\"wait\":800},{\"shot\":\"a4-v3-34-$SUF\"},{\"tap\":\"邮票\"},{\"wait\":300},{\"shot\":\"a4-v3-27-open1-$SUF\"},{\"wait\":450},{\"shot\":\"a4-v3-27-open2-$SUF\"},{\"wait\":500},{\"shot\":\"a4-v3-27-open3-$SUF\"},{\"wait\":1500},{\"shot\":\"a4-v3-27-$SUF\"}]"
W a4-v3-36 "{\"path\":\"pages/roam/index\",$ID}" "[{\"wait\":2500},{\"tap\":\"漫游护照\"},{\"wait\":2500},{\"shot\":\"a4-v3-36-$SUF\"},{\"scroll\":600},{\"wait\":800},{\"shot\":\"a4-v3-36-2-$SUF\"}]"
W a7-v1-p59 "{\"path\":\"subpackageMember/myinvite/myinvite\",$ID}" "[{\"wait\":3500},{\"shot\":\"a7-v1-p59-$SUF\"}]"
W b8-x-p3-01 "{\"path\":\"pages/topic/catalog/index?city_code=310100\",$ID}" "[{\"wait\":3500},{\"shot\":\"b8-x-p3-01-$SUF\"},{\"tap\":\"上海\"},{\"wait\":900},{\"shot\":\"b8-x-p3-01-city-$SUF\"}]"
