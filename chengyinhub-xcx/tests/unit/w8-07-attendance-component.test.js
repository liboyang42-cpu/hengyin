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
 return {c,requests,modals,calls,store,hide:()=>def.pageLifetimes.hide.call(c),show:()=>def.pageLifetimes.show.call(c),switch:()=>{owner='8';},receipt(r,state,data){r.success({code:200,data:{requestId:JSON.parse(r.data).requestId,state,data}});},read(){requests.at(-1).success({code:200,data:{config:{phone:'123456',role:'值班',dutyStart:1,dutyEnd:2,checkinOpenAt:1,checkinCloseAt:2},configVersion:'1',serverNow:3,dutyState:'ENDED',activeParticipation:true,mine:{},receiving:false}});}};
}
test('public contact shows expired shift and dialing never claims acceptance or sends coordinates',()=>{
 const h=mount();h.read();assert.equal(h.c.data.snapshot.dutyLabel,'值班已结束');h.c.contact({currentTarget:{dataset:{}}});
 assert.equal(h.calls[0].phoneNumber,'123456');assert.match(h.c.data.notice,/无法判断/);assert.equal(h.requests.length,1);assert.equal(h.calls[0].latitude,undefined);
});
test('claim UNKNOWN survives cold start; original frozen command retry then result readback',async()=>{
 const h=mount();h.read();h.c.claim();h.modals[0].success({confirm:true});await tick();const req=h.requests.at(-1),original=req.data;req.fail();
 assert.equal(h.c.data.recovery.state,'UNKNOWN');h.hide();const cold=mount(false,h.store);
 assert.equal(cold.c.data.recovery.state,'UNKNOWN');cold.c.commandAction({currentTarget:{dataset:{action:'retry'}}});assert.equal(cold.requests.at(-1).data,original);
 cold.receipt(cold.requests.at(-1),'APPLIED',{bib:'44',state:'ISSUED'});assert.match(cold.requests.at(-1).url,/attendance$/);assert.equal(cold.c.data.result.data.bib,'44');
});
test('hidden confirmation and switched-account reads cannot mutate or expose contact snapshot',async()=>{
 const h=mount();h.read();h.c.claim();h.hide();h.modals[0].success({confirm:true});await tick();assert.equal(h.requests.length,1);
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
