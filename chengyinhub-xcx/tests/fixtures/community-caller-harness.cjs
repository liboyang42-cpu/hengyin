const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const COMPOSE = 'pages/square/components/cy/post-compose/index.js';
const LIKE = 'pages/mylike/mylike.js';
const PROFILE = 'subpackageMember/components/cy/profile/index.js';
const flush = () => new Promise(resolve => setImmediate(resolve));
function bundle(id=77, extra={}) {
 return {post:{id,authorId:1,version:2,lifecycle:'DRAFT',body:'original',title:null,postType:'MOMENT',audience:'PUBLIC',commentPolicy:'EVERYONE',communityId:null,replyApprovalEnabled:0,slowModeSeconds:0,locationPrecision:'NONE',cityCode:null,poiName:null,approximateGeohash:null,disclosureType:'NONE',safetyLabelsJson:'[]',guidelineVersionId:1,clientRequestId:'original-request',viewerLiked:false,viewerBookmarked:true,likeCount:4,bookmarkCount:2,...extra},media:[],references:[],mentions:[],topicCodes:[],viewerIsAuthor:true,viewerCanComment:true,viewerFollowing:false};
}
function mount(rel, options={}) {
 let definition, owner=1; const requests=[],events=[],toasts=[],modals=[],draftCalls=[],storage=options.storage||new Map(); let picker;
 const app={globalData:{},getUserID:()=>owner,getUserRole:()=> 'player',getUserType:()=>0,getAvatar:()=>'',getNickname:()=>'',getPageSize:()=>2,getTotalPage:()=>1,getRequestErrorMessage:(r,f)=>(r&&r.msg)||f,tips:m=>toasts.push(m),sendRequest:r=>{requests.push(r);return {abort(){}}},chooseImage:(cb,count,opts)=>{app.upload={cb,count,opts}}};
 const wx={getStorageSync:k=>storage.get(k)||'',setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),getWindowInfo:()=>({windowWidth:375,windowHeight:812}),getMenuButtonBoundingClientRect:()=>({bottom:64}),stopPullDownRefresh(){},hideTabBar(){}};
 const empty=new Proxy(function(){},{get:()=>function(){return {} }});
 function req(id) {
  if (id.includes('pro-editor-draft')) { const actual=require(path.resolve(path.dirname(path.join(ROOT,rel)),id)); return Object.fromEntries(Object.entries(actual).map(([k,v])=>[k, typeof v==='function' ? (...args)=>{draftCalls.push([k,...args]);return v(...args)} : v])); }
  if (/community-post-api|community-post-view|roam-geo|publish-intent|pro-editor-draft/.test(id)) return require(path.resolve(path.dirname(path.join(ROOT,rel)),id));
  if (id.includes('/toast')) return Object.assign(m=>toasts.push(m),{success:m=>toasts.push(m)});
  if (id.includes('/modal')) return {show:o=>{modals.push(o)}};
  if (id.includes('nav-safe-area')) return {resolveMenuChrome:()=>({sheetTop:60})};
  if (id.includes('location-manager')) return {pickLocation:o=>{picker=o}};
  if (id.includes('datetime')) return {toTimestamp:x=>Date.parse(x),chinaParts:()=>null};
  if (id.includes('response-shape')) return {isRecord:x=>!!x&&typeof x==='object'&&!Array.isArray(x),isRecordList:x=>Array.isArray(x)&&x.every(y=>y&&typeof y==='object')};
  return empty;
 }
 vm.runInNewContext(options.source || fs.readFileSync(path.join(ROOT,rel),'utf8'),{getApp:()=>app,wx,Component:x=>definition=x,Page:x=>definition=x,require:req,console,setTimeout,clearTimeout,Date,JSON,Map,Set}, {filename:rel});
 const instance={data:JSON.parse(JSON.stringify(definition.data)),setData(patch){for(const [key,v] of Object.entries(patch)){const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let at=this.data;for(const p of parts.slice(0,-1))at=at[p]||(at[p]={});at[parts.at(-1)]=v}},triggerEvent:(name,detail)=>events.push({name,detail})};
 if(definition.properties)for(const[k,p]of Object.entries(definition.properties))instance.data[k]=p.value;
 Object.assign(instance.data,options.props||{});
 Object.assign(instance,definition.methods||definition);
 if(rel===COMPOSE)definition.lifetimes.attached.call(instance);
 if(rel===PROFILE)instance._profileAttached=true;
 return {instance,definition,requests,events,toasts,modals,draftCalls,storage,app,wx,switchOwner:id=>{owner=id},pick:poi=>picker.onPick(poi)};
}
function reply(req,data,code=200){assert.ok(req,'expected actual request');req.success({code,data});}
function request(h,part){return h.requests.findLast(r=>r.url.includes(part));}


async function editOpen(h,original){h.instance.data.editPost={id:77,contents:'original'};h.definition.observers.show.call(h.instance,true);await flush();reply(request(h,'/posts/77'),original);await flush();}
module.exports={mount,bundle,reply,request,flush,editOpen,ROOT,COMPOSE,LIKE,PROFILE};
