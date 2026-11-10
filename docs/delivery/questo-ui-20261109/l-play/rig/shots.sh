#!/bin/bash
# l-play shots. usage: XCX=<bundled candidate xcx> RIG_WALK=<rig>/.../mpweb/walk.mjs shots.sh <before|after> [id]
S=$(cd "$(dirname "$0")" && pwd); P=$1; ONLY=${2:-all}; X=${XCX:?set XCX}
OUT=${RIG_SHOTS:-$S/../shots}; mkdir -p "$OUT"
pv(){ (cd $S && GEN=$S/gen.js XCX=$X RIG_SHOTS=$OUT timeout 240 node $S/../../r2-play/rig/play.mjs "$@" 2>&1 | grep -E "^(ERR|CON|Error|Terminated|EVAL)" ); }
PG='getCurrentPages().slice(-1)[0]'
HUD="(()=>{const p=$PG;p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,hpEnabled:true,hp:3,hpMax:5,names:Object.assign({},p.data.themeBag.names,{hp:'体力'}),inventoryEnabled:true,capacity:6,used:2})});return 1})()"
ITEMS="[{id:1,name:'黄铜钥匙',initial:'钥',image:'/pages/play/images/d_topic2.png',quantity:1,effectText:'打开钟楼侧门'},{id:2,name:'邮戳 · 1924',initial:'邮',quantity:2,effectText:''},{id:3,name:'第二封信',initial:'信',quantity:1,effectText:''}]"
BAG3="(()=>{const p=$PG;p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,capacity:12,used:4,items:$ITEMS,pendingRows:[],valueRows:[],clues:[{id:9,name:'侧门没有锁',content:'侧门的锁芯里有新的划痕，钥匙是从外面插进去的。'}]}),themeBackpackOpen:true,themeBackpackTab:'items'});return 1})()"
BAGC="(()=>{const p=$PG;p.setData({themeBackpackTab:'clues'});return 1})()"
SHEET="(()=>{const p=$PG;p.setData({isPreview:false});p.openSheet(p.data.nodes[0]);return 1})()"
QR="(()=>{const p=$PG;p.setData({isPreview:false});p.openScene('qr-ticket',{registrationId:'71',title:'老城三封信',until:''});return 1})()"
QRREADY="(()=>{const c=$PG.selectComponent('cy-scene-qr-ticket');if(c)c.setData({state:'ready',qr:'/subpackageMember/images/d_qrcode.png'});return !!c})()"
END="(()=>{const p=$PG;p._wonMedals=[{nodeId:11,name:'邮差的钥匙',img:'',rank:1}];p._showEnding({show:true,real:true,idx:0,list:[{code:'a',title:'邮差的钥匙',summary:'老城三封信 · 7 / 7 站 · 58 分钟'}],archive:{identity:[],avatarUrl:'',photos:[],notes:[],stations:[],walk:''},hasArchive:false});return 1})()"
GATE2="(()=>{const p=$PG;const cards=p.data.chapterCards;p.setData({chapter:Object.assign({},p.data.chapter,{id:2,name:'老书店',description:'店门虚掩着，柜台后面没有人。'})});p._seenChapterGates=new Set();p.showChapterGate();return JSON.stringify([cards.map(c=>c.id),p.data.chapterGate.endInfo&&p.data.chapterGate.endInfo.number])})()"
INTRO='{"tap":"开始游戏"},{"wait":1200}'
run(){ local id=$1; shift; [ "$ONLY" = all ] || [ "$ONLY" = "$id" ] || return 0; echo "== $id"; pv "$@"; }
run ch-start route "" "[$INTRO,{\"wait\":1800},{\"shot\":\"ch-start-$P\"},{\"wait\":2600},{\"shot\":\"ch-start-2-$P\"}]"
run ch-end two "" "[$INTRO,{\"wait\":5000},{\"eval\":\"$GATE2\"},{\"wait\":1500},{\"shot\":\"ch-end-$P\"},{\"wait\":4200},{\"shot\":\"ch-end-dissolve-$P\"},{\"wait\":1600},{\"shot\":\"ch-end-next-$P\"}]"
run station route "" "[$INTRO,{\"wait\":5000},{\"eval\":\"$SHEET\"},{\"wait\":1500},{\"shot\":\"station-$P\"}]"
run hud route "" "[$INTRO,{\"wait\":5000},{\"eval\":\"$HUD\"},{\"wait\":1500},{\"shot\":\"hud-$P\"}]"
DOWN='{"scroll":600},{"wait":1500},{"scroll":600},{"wait":1500},{"scroll":600},{"wait":3500}'
run kit-start ballShake "" "[$INTRO,{\"wait\":5000},$DOWN,{\"shot\":\"kit-entry-$P\"},{\"tryTap\":\"谁在钟楼留下了钥匙\"},{\"wait\":2500},{\"shot\":\"kit-start-$P\"}]"
run branch branch "" "[$INTRO,{\"wait\":5000},$DOWN,{\"shot\":\"branch-entry-$P\"},{\"tryTap\":\"谁在钟楼留下了钥匙\"},{\"wait\":2500},{\"shot\":\"branch-$P\"}]"
run ext tasks "" "[{\"tryTap\":\"开始游戏\"},{\"wait\":5000},{\"scroll\":900},{\"wait\":2000},{\"shot\":\"ext-entry-$P\"},{\"tryTap\":\"延伸任务\"},{\"wait\":1500},{\"shot\":\"ext-$P\"},{\"tapSel\":\".xd__arrow\"},{\"wait\":900},{\"shot\":\"ext-2-$P\"}]"
run bag route "" "[$INTRO,{\"wait\":5000},{\"eval\":\"$BAG3\"},{\"wait\":1800},{\"shot\":\"bag-$P\"},{\"eval\":\"$BAGC\"},{\"wait\":1200},{\"shot\":\"bag-clues-$P\"}]"
run qr route "" "[$INTRO,{\"wait\":5000},{\"eval\":\"$QR\"},{\"wait\":1500},{\"eval\":\"$QRREADY\"},{\"wait\":2200},{\"shot\":\"qr-$P\"},{\"tapSel\":\".voucher__flip\"},{\"wait\":1200},{\"shot\":\"qr-front-$P\"}]"
run run-end route "" "[$INTRO,{\"wait\":5000},{\"eval\":\"$END\"},{\"wait\":2600},{\"shot\":\"run-end-$P\"}]"
