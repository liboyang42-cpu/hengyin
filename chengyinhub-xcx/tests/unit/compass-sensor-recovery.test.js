const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const FILE = 'pages/play/components/playkit-compass/index';
function host(options={}) {
  let now=1000,id=0,stage;const tasks=new Map(),history=[],handlers=new Set(),starts=[],events=[],toasts=[],stageCalls=[];
  const timer=(fn,ms,repeat)=>{const t={id:++id,fn,due:now+ms,ms,repeat};tasks.set(t.id,t);history.push(t);return t.id;};
  const wx={
    onCompassChange(cb){assert.equal(typeof cb,'function');handlers.add(cb);if(options.listenThrow)throw Error('listen');},
    offCompassChange(cb){handlers.delete(cb);},stopCompass(){},
    startCompass(arg){starts.push(arg);if(options.syncSuccessThenFail){arg.success();arg.fail();}else if(options.syncSuccessThenThrow){arg.success();throw Error('busy');}else if(options.syncFail)arg.fail();else if(options.startThrow)throw Error('start');else if(!options.asyncStart)arg.success();},
  };
  if(options.missingStart)delete wx.startCompass;if(options.missingListen)delete wx.onCompassChange;if(options.missingOff)delete wx.offCompassChange;if(options.missingStop)delete wx.stopCompass;
  function load(file){let definition;vm.runInNewContext(fs.readFileSync(path.join(ROOT,file+'.js'),'utf8'),{Component:x=>definition=x,require:name=>name.endsWith('/toast.js')?title=>toasts.push(title):{haptic(){},readReducedMotion:()=>false},wx,Date:{now:()=>now},Math,Number,setTimeout:(fn,ms)=>timer(fn,ms,false),clearTimeout:x=>tasks.delete(x),setInterval:(fn,ms)=>timer(fn,ms,true),clearInterval:x=>tasks.delete(x)},{filename:file});return definition;}
  const def=load(FILE),stageDef=load('pages/play/components/play-stage/index');
  function instance(d,props){const data={};for(const [key,value]of Object.entries(d.properties))data[key]=value.value;Object.assign(data,JSON.parse(JSON.stringify(d.data)),props);return Object.assign({data,setData(p,cb){Object.assign(data,p);if(cb)cb();}},d.methods);}
  const kit=instance(def,{show:true,target:0,holdSeconds:1,...options.props});
  const keyed=/wx:for="{{stageKeys}}"/.test(fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8'));
  function mount(){const key=kit.data.stageKeys?kit.data.stageKeys[0]:0;const child=instance(stageDef,{show:true,reducedMotion:true});child.triggerEvent=(name,detail)=>{const e={detail,currentTarget:{dataset:{stageKey:key}}};if(name==='run')kit.onRun(e);if(name==='verdict')kit.onVerdict(e);if(name==='back')kit.onClose(e);};const begin=child.begin;child.begin=function(){stageCalls.push('begin');return begin.apply(this,arguments);};stage=child;}
  mount();kit.selectComponent=()=>stage;kit.triggerEvent=(name,detail)=>events.push({name,detail});kit.setData=(p,cb)=>{const key=kit.data.stageKeys&&kit.data.stageKeys[0];Object.assign(kit.data,p);if(keyed&&kit.data.stageKeys&&key!==kit.data.stageKeys[0])mount();if(cb)cb();};
  function tick(ms){const end=now+ms;while(true){const t=[...tasks.values()].filter(t=>t.due<=end).sort((a,b)=>a.due-b.due||a.id-b.id)[0];if(!t)break;now=t.due;if(t.repeat)t.due+=t.ms;else tasks.delete(t.id);t.fn();}now=end;}
  return {kit,def,events,toasts,handlers,starts,tasks,history,stageCalls,tick,get stage(){return stage;},sample(direction,accuracy){for(const cb of [...handlers])cb({direction,accuracy});},continuous(n=15,d=0){for(let i=0;i<n;i++){this.sample(d);tick(100);}},jump(ms){now+=ms;},interrupt(kind){if(kind==='hide')def.pageLifetimes.hide.call(kit);if(kind==='close')kit.onClose();if(kind==='false'){kit.data.show=false;def.observers.show.call(kit,false);}if(kind==='detach')def.lifetimes.detached.call(kit);},resume(kind){if(kind==='hide'&&def.pageLifetimes.show)def.pageLifetimes.show.call(kit);if(kind==='close'||kind==='false'){kit.data.show=true;def.observers.show.call(kit,true);}}};
}
const results=h=>h.events.filter(e=>e.name==='submit'||e.name==='verdict');
for(const kind of ['hide','close','false','detach'])for(const phase of ['starting','running'])test(`${kind} stops ${phase}, retires callbacks, no result`,()=>{
  const h=host({asyncStart:phase==='starting'});h.kit.onStart();if(phase==='running')h.sample(0);const old=h.stage,callbacks=[...h.handlers],timers=h.history.map(t=>t.fn);h.interrupt(kind);
  assert.equal(h.handlers.size,0);assert.equal(h.tasks.size,0);assert.notEqual(h.kit.data.phase,'run');assert.equal(h.kit.data.hasHeading,false);
  h.starts[0].success();h.starts[0].fail();for(const cb of callbacks)cb({direction:0});for(const fn of timers)fn();old.onCountedIn();old.fail(true);h.tick(10000);assert.equal(results(h).length,0);assert.equal(h.handlers.size,0);
});
for(const mode of ['missingStart','missingListen','missingOff','missingStop','syncFail','startThrow','listenThrow','syncSuccessThenFail','syncSuccessThenThrow'])test(`${mode} cannot leave listener/timer or begin stage`,()=>{
  const h=host({[mode]:true});assert.doesNotThrow(()=>h.kit.onStart());assert.equal(h.stageCalls.length,0);assert.equal(h.handlers.size,0);assert.equal(h.tasks.size,0);assert.equal(results(h).length,0);assert.equal(h.toasts.length,1);
});
test('async success gates real stage; async failure cleans exact listener and permits retry',()=>{
  const h=host({asyncStart:true});h.kit.onStart();assert.equal(h.stageCalls.length,0);h.starts[0].fail();assert.equal(h.handlers.size,0);assert.equal(h.tasks.size,0);h.kit.onStart();h.starts[1].success();assert.equal(h.stageCalls.length,1);h.continuous();assert.equal(h.events.filter(e=>e.name==='submit').length,1);
});
for(const direction of [null,false,true,'', '0',[],{},NaN,Infinity,undefined])test(`invalid heading ${String(direction)} does not become zero`,()=>{
  const h=host();h.kit.onStart();h.sample(direction);h.tick(3000);assert.equal(h.kit.data.hasHeading,false);assert.equal(results(h).length,0);assert.equal(h.handlers.size,0);assert.equal(h.tasks.size,0);
});
test('no sample shows no fake zero degrees, first sample timeout enables retry',()=>{
  const h=host();h.kit.onStart();assert.equal(h.kit.data.hasHeading,false);assert.match(fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8'),/wx:if="{{phase === 'run' && hasHeading/);h.tick(3000);assert.notEqual(h.kit.data.phase,'run');assert.equal(h.handlers.size,0);h.kit.onStart();h.continuous();assert.equal(h.events.filter(e=>e.name==='submit').length,1);
});
test('single direction sample cannot complete a hold, and stale _finish cannot bypass sampling',()=>{
  const h=host();h.kit.onStart();h.sample(0);h.tick(1000);assert.equal(results(h).length,0);assert.equal(h.kit.data.hasHeading,false);assert.equal(h.handlers.size,0);h.kit._finish();assert.equal(results(h).length,0);
});
test('late direction after a missed watchdog gap cannot revive hold',()=>{
  const h=host();h.kit.onStart();h.sample(0);h.jump(500);h.sample(0);assert.equal(h.handlers.size,0);assert.equal(results(h).length,0);
});
test('continuous valid zero samples submit actual zero with nonzero target and no start event',()=>{
  const h=host({props:{target:10,tolerance:15}});h.kit.onStart();h.continuous(15,0);const submit=h.events.filter(e=>e.name==='submit');assert.equal(submit.length,1);assert.equal(submit[0].detail.bearing,0);assert.deepEqual(Object.keys(submit[0].detail),['bearing']);assert.equal(h.events.filter(e=>e.name==='start').length,0);assert.equal(h.handlers.size,0);assert.equal(h.tasks.size,0);h.kit._finish();assert.equal(h.events.filter(e=>e.name==='submit').length,1);
});
test('old fail/change/timer/stage callbacks cannot corrupt a fresh run',()=>{
  const h=host();h.kit.onStart();const old=h.stage,fail=h.starts[0].fail,cb=[...h.handlers][0],timers=h.history.map(t=>t.fn);h.interrupt('hide');h.resume('hide');h.kit.onStart();fail();cb({direction:120,accuracy:'low'});for(const fn of timers)fn();old.onCountedIn();old.fail(true);old.onBack();h.continuous();assert.equal(h.events.filter(e=>e.name==='submit').length,1);
});
test('unknown/numeric accuracy is not invented as precision; low enums remain warnings',()=>{
  for(const accuracy of [undefined,{},0,42,'unknown']){const h=host();h.kit.onStart();h.sample(20,accuracy);h.tick(100);assert.equal(h.kit.data.lowAccuracy,false);}
  const h=host();h.kit.onStart();h.sample(20,'low');h.tick(100);assert.equal(h.kit.data.lowAccuracy,true);
});
test('finished submission ignores late start failures and watchdog callbacks',()=>{
  const h=host();h.kit.onStart();const fail=h.starts[0].fail,timers=h.history.map(t=>t.fn);h.continuous();const before=JSON.stringify(h.kit.data);fail();for(const fn of timers)fn();assert.equal(JSON.stringify(h.kit.data),before);assert.equal(h.toasts.length,0);assert.equal(h.events.filter(e=>e.name==='submit').length,1);
});
test('finite extreme headings and targets remain finite for all presentation numbers',()=>{
  const h=host({props:{target:-1e308}});h.kit.onStart();h.sample(1e308);h.tick(100);for(const key of ['heading','needleDeg','roseDeg','holdPct'])assert.ok(Number.isFinite(h.kit.data[key]),key);assert.equal(results(h).length,0);
});
test('authoritative done readback stops a live run without another submission',()=>{
  const h=host();h.kit.onStart();h.sample(0);h.kit.data.done=true;h.def.observers.done.call(h.kit,true);h.tick(5000);h.kit.onStart();assert.equal(h.handlers.size,0);assert.equal(h.tasks.size,0);assert.equal(results(h).length,0);assert.equal(h.starts.length,1);
});
test('invalid direction inside otherwise aligned samples breaks measured hold continuity',()=>{const h=host();h.kit.onStart();h.sample(0);h.tick(100);h.sample(0);assert.ok(h.kit._held>0);h.tick(100);h.sample(null);h.tick(100);h.sample(0);assert.equal(h.kit._held,0);assert.equal(results(h).length,0);});
