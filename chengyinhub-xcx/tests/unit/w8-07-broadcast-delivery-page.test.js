const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const {createRequire}=require('node:module');const file=path.resolve(__dirname,'../../pages/activity/official-mine/index.js');
function mount(){let page,actor='7';const requests=[],modals=[];const app={getUserID:()=>actor,getAuthorization:()=>actor+'token',sendRequest:r=>requests.push(r),getRequestErrorMessage:(_,fallback)=>fallback};const real=createRequire(file);
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:p=>page=p,getApp:()=>app,wx:{},require:p=>p.endsWith('/modal.js')?{show:o=>modals.push(o)}:real(p)});
 page.data.broadcasts=[{id:'9007199254740993'}];page.setData=patch=>{for(const [k,v]of Object.entries(patch)){const m=k.match(/^broadcasts\[(\d+)\]\.(.*)$/);if(m)page.data.broadcasts[Number(m[1])][m[2]]=v;else page.data[k]=v;}};
 return {page,requests,modals,switch(){actor='8';}};
}
test('publisher sees channel acceptance and UNKNOWN separately, never total-delivered',()=>{
 const h=mount();h.page.openBroadcastStats({currentTarget:{dataset:{index:0}}});h.requests[0].success({code:200,data:{reached:1,clicked:0,deliveryCounts:{INAPP_STORED:1,CHANNEL_ACCEPTED:1,UNKNOWN:1},canRecoverInapp:false}});
 const text=h.page.data.broadcasts[0]._statsText;assert.match(text,/订阅渠道受理 1/);assert.match(text,/UNKNOWN 1/);assert.match(text,/不代表已读/);assert.equal(h.page.data.broadcasts[0]._canRecover,false);
});
test('account changes reject late stats and recovery confirmations',()=>{
 const h=mount();h.page.openBroadcastStats({currentTarget:{dataset:{index:0}}});h.switch();h.requests[0].success({code:200,data:{reached:9,clicked:0}});assert.equal(h.page.data.broadcasts[0]._statsText,'正在读取复盘…');
 const g=mount();g.page.data.broadcasts[0]._canRecover=true;g.page.recoverBroadcast({currentTarget:{dataset:{index:0}}});g.switch();g.modals[0].success({confirm:true});assert.equal(g.requests.length,0);
});
test('recovery transport failure reads original broadcast without creating or expanding it',()=>{
 const h=mount();h.page.data.broadcasts[0]._canRecover=true;h.page.recoverBroadcast({currentTarget:{dataset:{index:0}}});h.modals[0].success({confirm:true});assert.equal(h.requests[0].url,'/api/official/broadcast/9007199254740993/recover');h.requests[0].fail();assert.equal(h.requests[1].url,'/api/official/broadcast/9007199254740993/stats');
});

test('typed rejection and definite non-submission are distinct from UNKNOWN',()=>{
 const h=mount();h.page.openBroadcastStats({currentTarget:{dataset:{index:0}}});h.requests[0].success({code:200,data:{reached:0,clicked:0,deliveryCounts:{REJECTED:2,NOT_SUBMITTED:3,UNKNOWN:1},canRecoverInapp:true}});
 const text=h.page.data.broadcasts[0]._statsText;assert.match(text,/明确拒绝 2/);assert.match(text,/未提交 3/);assert.match(text,/UNKNOWN 1/);assert.equal(h.page.data.broadcasts[0]._canRecover,true);
});
