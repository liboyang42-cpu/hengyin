'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {createRequire}=require('node:module');
const file=path.resolve(__dirname,'../../components/cy/event-attendance/index.js');
const tick=async()=>{for(let i=0;i<6;i++)await Promise.resolve();};
function mount(organizer=false,store={}){
 let def,owner='7',serial=0;const requests=[],modals=[],calls=[];
 const app={getUserID:()=>owner,getAuthorization:()=>owner+'token',sendRequest:r=>requests.push(r)};
 const wx={getStorageSync:k=>store[k],setStorageSync:(k,v)=>{store[k]=JSON.parse(JSON.stringify(v));},removeStorageSync:k=>delete store[k],getRandomValues:o=>o.success({randomValues:new Uint8Array(16).fill(++serial).buffer}),makePhoneCall:o=>calls.push(o),scanCode:o=>calls.push(o)};
 const real=createRequire(file);vm.runInNewContext(fs.readFileSync(file,'utf8'),{Component:d=>def=d,getApp:()=>app,wx,setTimeout:()=>1,clearTimeout(){},require:p=>p.endsWith('/modal.js')?{show:o=>modals.push(o)}:real(p)});
 const c={...def.methods,properties:{eventId:'19',organizer},data:JSON.parse(JSON.stringify(def.data)),setData:p=>Object.assign(c.data,p)};
 def.lifetimes.attached.call(c);
 return {c,requests,modals,calls,store,hide:()=>def.pageLifetimes.hide.call(c),show:()=>def.pageLifetimes.show.call(c),switch:()=>{owner='8';},receipt(r,state,data){r.success({code:200,data:{requestId:JSON.parse(r.data).requestId,state,data}});},att(){return requests.filter(r=>/\/attendance(\/manage)?$/.test(r.url)).at(-1);},contact(data){requests.filter(r=>r.url==='/api/play/contact').at(-1).success({code:200,data});},read(){this.att().success({code:200,data:{config:{phone:'123456',role:'值班',dutyStart:1,dutyEnd:2,checkinOpenAt:1,checkinCloseAt:2},configVersion:'1',serverNow:3,dutyState:'ENDED',activeParticipation:true,mine:{},receiving:false}});}};
}
test('public contact asks before dialing, never shows the number or sends coordinates',()=>{
 const h=mount();h.read();assert.equal(h.c.data.snapshot.dutyLabel,'值班已结束');assert.equal(h.c.data.player.host,'','没有主办电话时整行不出,不回退到值班电话');
 h.contact({kind:'club',name:'',phone:'123456'});assert.equal(h.c.data.player.host,'联系主办');
 const q=h.requests.find(r=>r.url==='/api/play/contact');assert.equal(q.method,'GET');assert.equal(JSON.stringify(q.data),JSON.stringify({activityId:'19'}));
 h.c.contact({currentTarget:{dataset:{}}});assert.equal(h.calls.length,0,'原型 B B9:先确认再拨');
 assert.equal(h.modals[0].content,'拨打「主办」的电话？');h.modals[0].success({confirm:true});
 assert.equal(h.calls[0].phoneNumber,'123456');assert.equal(h.requests.length,2,'只有两次读,拨号不发请求');assert.equal(h.calls[0].latitude,undefined);
 const wxml=fs.readFileSync(file.replace('.js','.wxml'),'utf8').split('<!-- 玩家端')[1];
 for(const banned of ['config.phone','backupPhone','WGS84','requestId','110','120','紧急'])assert.ok(!wxml.includes(banned),banned+' must not reach the player view');
});
test('player view: meeting row, organizer name, check-in chips and unlimited code rotation',async()=>{
 const h=mount();h.att().success({code:200,data:{config:{phone:'1',meetingName:'东门广场',meetingAt:Date.parse('2026-10-25T18:00:00+08:00'),latitude:31,longitude:121,checkinOpenAt:Date.parse('2026-10-25T18:30:00+08:00'),checkinCloseAt:Date.parse('2026-10-25T19:20:00+08:00')},
  configVersion:'1',serverNow:Date.parse('2026-10-25T18:00:00+08:00'),dutyState:'ON_DUTY',activeParticipation:true,mine:{},receiving:true}});
 h.contact({kind:'club',name:'城南徒步俱乐部',phone:'2'});
 assert.equal(h.c.data.player.meet,'10月25日 18:00 · 东门广场集合');assert.equal(h.c.data.player.host,'联系主办 · 城南徒步俱乐部');assert.equal(h.c.data.player.phone,'2');
 assert.equal(h.c.data.player.checkin.chip,'18:30 开放');assert.equal(h.c.data.player.checkin.show,true);
 h.c.showCode();assert.equal(h.c.data.codeShow,true);await tick();const claim=h.requests.at(-1);assert.equal(JSON.parse(claim.data).action,'attendance-claim');
 h.receipt(claim,'APPLIED',{bib:'27',state:'ISSUED'});assert.equal(h.c.data.recovery.state,'','领取结果读回即归档');
 for(let i=0;i<2;i++){h.c.rotate();const m=h.modals.at(-1);assert.equal(m.title,'更换签到码？');assert.equal(m.content,'旧码会失效，签到记录保留。');m.success({confirm:true});await tick();
  const r=h.requests.at(-1);assert.equal(JSON.parse(r.data).action,'attendance-rotate');h.receipt(r,'APPLIED',{});}
 const done=mount();done.att().success({code:200,data:{config:{checkinOpenAt:1,checkinCloseAt:2},serverNow:3,activeParticipation:true,mine:{checkedAt:'2026-10-25 18:42:00'}}});
 assert.equal(done.c.data.player.checkin.chip,'已签到');assert.equal(done.c.data.player.checkin.doneAt,'18:42');
 const late=mount();late.att().success({code:200,data:{config:{checkinOpenAt:1,checkinCloseAt:2},serverNow:3,activeParticipation:true,mine:{}}});
 assert.equal(late.c.data.player.checkin.chip,'已截止');assert.equal(late.c.data.player.checkin.show,false);
 const guest=mount();guest.att().success({code:200,data:{config:{checkinOpenAt:1,checkinCloseAt:2},serverNow:3,activeParticipation:false,mine:{}}});
 assert.equal(guest.c.data.player.checkin,null,'未报名整段不出');assert.equal(guest.c.data.player.host,'');
});
test('claim UNKNOWN survives cold start; original frozen command retry then result readback',async()=>{
 const h=mount();h.read();h.c.claim();h.modals[0].success({confirm:true});await tick();const req=h.requests.at(-1),original=req.data;req.fail();
 assert.equal(h.c.data.recovery.state,'UNKNOWN');h.hide();const cold=mount(false,h.store);
 assert.equal(cold.c.data.recovery.state,'UNKNOWN');cold.c.commandAction({currentTarget:{dataset:{action:'retry'}}});assert.equal(cold.requests.at(-1).data,original);
 cold.receipt(cold.requests.at(-1),'APPLIED',{bib:'44',state:'ISSUED'});assert.match(cold.requests.at(-1).url,/attendance$/);assert.equal(cold.c.data.result.data.bib,'44');
});
test('hidden confirmation and switched-account reads cannot mutate or expose contact snapshot',async()=>{
 const h=mount();h.read();h.c.claim();h.hide();h.modals[0].success({confirm:true});await tick();assert.equal(h.requests.filter(r=>r.method==='POST').length,0,'隐藏后确认不能派发写请求');
 h.switch();h.show();h.requests[0].success({code:200,data:{config:{phone:'old'}}});assert.equal(h.c.data.snapshot,null);
});
test('bad code cannot dispatch; valid code freezes pending verification and restores terminal result',async()=>{
 const h=mount(true);h.read();h.c.setData({code:'wrong'});h.c.verify();assert.equal(h.modals.length,0);
 h.c.setData({code:'a'.repeat(64)});h.c.verify();h.modals[0].success({confirm:true});await tick();const req=h.requests.at(-1);
 assert.equal(JSON.parse(req.data).action,'attendance-verify');assert.equal(req.url,'/api/official/command');h.receipt(req,'APPLIED',{bib:'42',state:'CHECKED_IN'});
 h.hide();const cold=mount(true,h.store);assert.equal(cold.c.data.result.data.bib,'42');assert.equal(cold.requests.length,1,'cold recovery reads but never replays a write');
});
test('failed business read keeps pending receipt operations available',async()=>{
 const h=mount();h.read();h.c.claim();h.modals[0].success({confirm:true});await tick();h.requests.at(-1).fail();h.c.reload();h.requests.at(-1).successStatusAbnormal();
 assert.ok(h.c.data.error);assert.equal(h.c.data.recovery.state,'UNKNOWN');h.c.commandAction({currentTarget:{dataset:{action:'query'}}});assert.equal(h.requests.at(-1).url,'/api/official/signup-command/query');
});
test('config freezes explicit publication consent, Beijing times and original version through UNKNOWN',async()=>{
 const h=mount(true);h.read();const values={phone:'123456',role:'值班',backupPhone:'',backupRole:'',meetingName:'集合点',meetingAddress:'活动入口',latitude:'31',longitude:'121',dutyStart:'2026-10-26T09:00',dutyEnd:'2026-10-26T18:00',checkinOpenAt:'2026-10-26T10:00',checkinCloseAt:'2026-10-26T12:00'};
 h.c.setData({fields:h.c.data.fields.map(f=>({...f,value:values[f.key]})),publicConfirmed:true});h.c.saveConfig();h.modals[0].success({confirm:true});await tick();
 const req=h.requests.at(-1),body=JSON.parse(req.data),config=JSON.parse(body.attendanceConfig);assert.equal(body.configVersion,'1');assert.equal(config.checkinOpenAt,Date.parse('2026-10-26T10:00:00+08:00'));assert.equal(config.publicConfirmed,true);req.fail();
 h.c.input({currentTarget:{dataset:{key:'phone'}},detail:{value:'new'}});assert.equal(h.c.data.fields.find(f=>f.key==='phone').value,'123456');
 h.c.reload();h.read();assert.equal(h.c.data.fields.find(f=>f.key==='meetingName').value,'集合点');h.c.commandAction({currentTarget:{dataset:{action:'retry'}}});assert.equal(h.requests.at(-1).data,req.data);
});
