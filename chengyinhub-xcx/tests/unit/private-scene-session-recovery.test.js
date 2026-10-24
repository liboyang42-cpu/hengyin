const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');const vm=require('node:vm');const {createRequire}=require('node:module');
const {createSessionStore}=require('../../utils/session/session-store');const {createSessionManager}=require('../../utils/session/session-manager');
function mount(kind = 'income') {
 const root=path.resolve(__dirname,'../..');let app,definition;const requests=[];const wx={request:r=>{requests.push(r);return{abort(){}};}};
 vm.runInNewContext(fs.readFileSync(path.join(root,'app.js'),'utf8'),{App:value=>app=value,wx,console,require:file=>file==='./utils/toast.js'?Object.assign(()=>{},{hide(){}}):require(path.join(root,file))});
 const storage=new Map();const session=createSessionStore({target:app.globalData,wx:{getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k)}});
 app.getSession=()=>session;app.isDevEnv=()=>false;app.loadFeatureFlags=()=>{};app.globalData.siteBaseUrl='http://127.0.0.1:8129';session.setUserID('7');session.setAuthorization('fixture-old');
 app.getSessionManager=()=>createSessionManager({session,isDevEnv:()=>false,wxLogin:async()=>({code:'fixture'}),exchangeCode:async()=>({code:401})});
 const paths={income:'components/cy/scene-asset-income-detail/index.js',withdraw:'components/cy/scene-member-withdraw-history/index.js',access:'components/cy/access-gate/index.js',predict:'pages/merchant/predict/index.js',order:'subpackageMember/components/cy/scene-member-order-history/index.js',board:'subpackageP3/pages/growthcenter/leaderboard/index.js'};
 const file=path.join(root,paths[kind]);vm.runInNewContext(fs.readFileSync(file,'utf8'),{Component:d=>definition=d,Page:d=>definition=d,getApp:()=>app,getCurrentPages:()=>[],wx,require:createRequire(file),setTimeout,clearTimeout});
 const scene=Object.assign({},definition,definition.methods,{data:JSON.parse(JSON.stringify(definition.data)),setData:p=>Object.assign(scene.data,p)});
 const load=()=>kind==='access'?scene.check():kind==='predict'?scene.loadAccess():kind==='board'?scene.loadBoard():scene.getList();
 if(kind==='access')scene.data.kind='merchant';
 const loading=()=>scene.data.loading===true||['checking','loading'].includes(scene.data.pageState||scene.data.accessState||scene.data.state);
 const cleared=()=>kind==='access'?scene.data.state==='login':kind==='predict'?scene.data.pageState==='login':kind==='order'?scene.data.orderAuthRequired===true:kind==='board'?scene.data.boardAuthRequired===true:scene.data.authRequired===true;
 return{app,scene,session,requests,load,loading,cleared,show:()=>definition.pageLifetimes.show.call(scene),hide:()=>definition.pageLifetimes.hide.call(scene)};
}
const settle=async()=>{for(let n=0;n<35;n++)await Promise.resolve();};
function reply(r,status,data){r.success({statusCode:status,data});if(r.complete)r.complete({});}
const rows={code:200,data:{rows:[],total:0}};
test('actual App transport automatic renewal succeeds and releases loading for the real private scene',async()=>{
 const h=mount();h.app.getSessionManager=()=>createSessionManager({session:h.session,isDevEnv:()=>false,wxLogin:async()=>({code:'fixture'}),exchangeCode:async()=>({token:'fixture-renew',data:{id:'7',role:'player',userType:1}})});
 h.scene.getList();reply(h.requests[0],401,{code:401});await settle();assert.equal(h.requests.length,2);reply(h.requests[1],200,rows);assert.equal(h.scene.data.loading,false);assert.equal(h.scene.data.authRequired,false);
});
test('owned refresh failure clears private snapshot and shows login without hanging',async()=>{
 const h=mount();h.scene.getList();h.scene.data.list=[{id:99}];reply(h.requests[0],401,{code:401});await settle();assert.equal(h.scene.data.loading,false);assert.equal(h.scene.data.list.length,0);assert.equal(h.scene.data.authRequired,true);assert.equal(h.requests.length,1);
});
for(const actor of ['7','8'])test('external session '+actor+' discards old200 and rereads the current owner',()=>{
 const h=mount();h.scene.getList();h.scene.data.list=[{id:99}];h.session.setUserID(actor);h.session.setAuthorization('fixture-external');reply(h.requests[0],200,{code:200,data:{rows:[{id:100}],total:1}});
 assert.equal(h.scene.data.list.length,0);assert.equal(h.requests.length,2);reply(h.requests[1],200,rows);assert.equal(h.scene.data.loading,false);assert.equal(h.scene.data.authRequired,false);
});
test('hidden session change still clears and releases loading, then show resumes the current read',()=>{
 const h=mount();h.scene.getList();h.scene.data.list=[{id:99}];h.hide();h.session.setAuthorization('fixture-external');reply(h.requests[0],200,rows);assert.equal(h.requests.length,1);assert.equal(h.scene.data.list.length,0);assert.equal(h.scene.data.loading,false);h.show();assert.equal(h.requests.length,2);
});
test('superseded read cannot reset or restart the new epoch on sessionChanged',()=>{
 const h=mount();h.scene.getList();h.scene.getList();h.scene.data.list=[{id:101}];h.session.setAuthorization('fixture-external');reply(h.requests[0],200,rows);assert.equal(h.requests.length,2);assert.equal(h.scene.data.list.length,1);
});

for(const kind of ['withdraw','access','predict','order','board']) {
 test(kind+' actual App own refresh failure clears and exits loading',async()=>{
  const h=mount(kind);h.load();reply(h.requests[0],401,{code:401});await settle();assert.equal(h.loading(),false);assert.equal(h.cleared(),true);assert.equal(h.requests.length,1);
 });
 test(kind+' actual transport external token response is rejected then current owner rereads',()=>{
  const h=mount(kind);h.load();h.session.setAuthorization('fixture-external');reply(h.requests[0],200,{code:200,data:{old:true}});assert.equal(h.requests.length,2);
  const accepted=kind==='access'||kind==='predict'?{code:200,data:{active:false}}:rows;
  reply(h.requests[1],200,accepted);assert.equal(h.loading(),false);
 });
}
