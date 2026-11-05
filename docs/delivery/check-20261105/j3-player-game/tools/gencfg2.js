// j3 second pass: one advanced config per editor game key, built with the editor's own
// defaultConfig/applyToConfig/validate/serialize, plus what the picker itself adds (outcomeRules for check/album,
// pages/publish/temp/index.js:2799) and, for "<key>+fb", author-written 结果反馈 (pass/fail reply) in the shape
// the 结果反馈 editor writes (outcome-after.js). "combo:a+b" enables two sections in one template (fixture for 248/267 shapes).
const P='/home/user/j3/chengyinhub-xcx/pages/publish/utils/publish/';
const a=require(P+'advanced-game-config.js'), c=require(P+'node-game-catalog.js'), oa=require(P+'outcome-after.js');
const IMG=process.argv[2]||'';
const fill={
 qaPick:s=>Object.assign(s,{title:'老洋房门口那棵树是什么树？',options:[{id:'opt_1',label:'梧桐',fb:'对，法国梧桐。',correct:true},{id:'opt_2',label:'银杏',fb:'再看看叶子。',correct:false}]}),
 qaText:s=>Object.assign(s,{title:'门楣上刻的年份是哪一年？',answerText:'1873',reveal:true}),
 qaShot:s=>Object.assign(s,{title:'拍下街角的旧邮筒',lead:'邮筒要在画面里'}),
 pricePair:s=>Object.assign(s,{title:'哪张是这家店的招牌？',items:s.items.map((x,i)=>Object.assign(x,{name:'图'+(i+1),imageUrl:IMG}))}),
 blindTaste:s=>Object.assign(s,{title:'闭眼尝一口',steps:'闭上眼，喝一口',options:[{key:'A',label:'乌龙'},{key:'B',label:'红茶'}]}),
 predict:s=>Object.assign(s,{question:'明天会下雨吗？',options:[{key:'A',label:'会'},{key:'B',label:'不会'}]}),
 photoCheck:s=>Object.assign(s,{title:'拍下钟楼',requirement:'画面里有钟楼的钟面'}),
 objectCard:s=>Object.assign(s,{title:'拍一件旧物',requirement:'一件旧物',cardTitle:'旧物卡'}),
 coin:s=>{s.heads.action='向左走';s.tails.action='向右走';return s;},
 dice:s=>Object.assign(s,{faces:['往前十步','原地转圈','拍一张天','找一只猫','唱一句','回到起点']}),
 random:s=>Object.assign(s,{deckName:'线索牌',items:[{id:'item_1',label:'钟楼卡',weight:1,content:'去看看门牌号'},{id:'item_2',label:'石阶卡',weight:1,content:'数一数台阶'}]}),
 check:s=>Object.assign(s,{skill:'观察',successText:'你看见了暗门。',failText:'什么也没发现。'}),
 d20:s=>Object.assign(s,{successText:'锁开了。',failText:'锁纹丝不动。'}),
 sort:s=>Object.assign(s,{prompt:'按建成先后排序',items:[{id:'item_1',label:'海关大楼'},{id:'item_2',label:'和平饭店'},{id:'item_3',label:'上海中心'}]}),
 match:s=>Object.assign(s,{prompt:'把楼和年份连起来',left:[{id:'left_1',label:'海关大楼'},{id:'left_2',label:'和平饭店'}],right:[{id:'right_1',label:'1927'},{id:'right_2',label:'1929'}]}),
 classify:s=>Object.assign(s,{prompt:'哪些在江边？',bins:[{id:'bin_1',label:'江边'},{id:'bin_2',label:'不在江边'}],items:[{id:'item_1',label:'外滩'},{id:'item_2',label:'静安寺'}]}),
 estimate:s=>Object.assign(s,{title:'钟面直径大约几米？',unit:'米',min:1,max:20,answer:5,tolerance:1,reveal:'约 5 米。'}),
 hidden:s=>Object.assign(s,{title:'找出画里的猫',imageUrl:IMG,hotspots:s.hotspots.map((h,i)=>Object.assign(h,{label:'猫'+(i+1)}))}),
 profile:s=>{s.title='建一份档案';s.questions[0].label='你叫什么';return s;},
 note:s=>Object.assign(s,{title:'给下一个人留句话',prompt:'写一句'}),
 dailySign:s=>Object.assign(s,{signer:'城隍',sealText:'吉',poems:['今日宜出门','今日宜慢行']}),
 album:s=>Object.assign(s,{images:[{url:IMG,caption:'老照片'}]}),
 compass:s=>Object.assign(s,{bearing:90}),
 countdown:s=>Object.assign(s,{seconds:20,doneText:'时间到，出发'}),
 typeIn:s=>Object.assign(s,{title:'照着打',target:'外滩钟声'}),
};
const keys=process.argv[3].split(',');
const out={};
const OUTCOME_REQUIRED=['check','album'];
for(const raw of keys){
  let m, label, sec, fb=false, k=raw;
  if(k.endsWith('+fb')){fb=true;k=k.slice(0,-3);}
  if(k.startsWith('combo:')){
    const [x,y]=k.slice(6).split('&');
    m=c.applyToConfig(a.defaultConfig(),x); const sx=c.findGame(x).section; if(fill[x]) fill[x](m[sx]);
    const m2=c.applyToConfig(a.defaultConfig(),y); const sy=c.findGame(y).section; if(fill[y]) fill[y](m2[sy]);
    m[sy]=m2[sy]; m[sy].enabled=true; label=c.findGame(x).label+'与'+c.findGame(y).label; sec=sx+'+'+sy;
    if(false) m.outcomeRules=Object.assign({version:1,pass:{}}, oa.once(m)?{}:{fail:{}});
  } else {
    m=c.applyToConfig(a.defaultConfig(),k); sec=c.findGame(k).section; label=c.findGame(k).label; if(fill[k]) fill[k](m[sec]);
    if(k==='typeIn' && !m.outcomeRules) m.outcomeRules={version:1,pass:{},fail:{}};
    if(OUTCOME_REQUIRED.includes(sec) && !m.outcomeRules) m.outcomeRules=Object.assign({version:1,pass:{}}, oa.once(m)?{}:{fail:{}});
    if(m.check && m.check.enabled && !m.check.checkId) m.check.checkId='check_j3'+k.replace(/[^a-z]/gi,''); if(0) m.check.checkId='check_j3'+k;
  }
  if(fb){ m.outcomeRules=Object.assign({version:1,pass:{reply:'对了，这一站过了。'}}, oa.once(m)?{}:{fail:{reply:'没过，再看看四周。'}}); }
  const e=a.validate(m); let j='';try{j=a.serialize(m)}catch(x){ }
  out[raw]={section:sec,label:label+(fb?'·反馈':''),err:e||'',json:j};
}
console.log(JSON.stringify(out));
