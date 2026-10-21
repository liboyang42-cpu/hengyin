const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const FILE = 'pages/play/components/playkit-shout/index';
const audio = require('../../pages/play/utils/play-audio-level.js');
function pcm(value=0) { const a=new Int16Array(4);a[0]=Math.round(value*32767);return a.buffer; }
function host(options={}) {
 let now=1000,id=0,stage,definition;
 const settingCallbacks=[];
 const tasks=new Map(),history=[],events=[],toasts=[],sets={frame:new Set(),error:new Set(),start:new Set(),stop:new Set()},callbacks=[];
 let starts=0,stops=0;
 const rec={};
 for(const [kind,suffix] of Object.entries({frame:'FrameRecorded',error:'Error',start:'Start',stop:'Stop'})){
  rec['on'+suffix]=fn=>{sets[kind].add(fn);callbacks.push({kind,fn});};
  rec['off'+suffix]=fn=>sets[kind].delete(fn);
 }
 rec.start=()=>{starts++;if(options.syncStart!==false)for(const fn of [...sets.start])fn();if(options.syncError)for(const fn of [...sets.error])fn(options.syncError);if(options.throwStart)throw Error('busy');};
 rec.stop=()=>{stops++;for(const fn of [...sets.stop])fn();};
 if(options.missing)delete rec[options.missing];
 const wx={getRecorderManager:()=>rec,getSetting:({success})=>{if(options.throwSetting)throw Error('setting');if(options.deferSetting)settingCallbacks.push(success);else success({authSetting:{'scope.record':options.permission}});}};
 if(options.noRecorder)delete wx.getRecorderManager;
 function schedule(fn,ms,repeat){const n=++id,t={id:n,fn,ms,due:now+ms,repeat};tasks.set(n,t);history.push(t);return n;}
 function load(file){let def;vm.runInNewContext(fs.readFileSync(path.join(ROOT,file+'.js'),'utf8'),{Component:x=>{def=x;},wx,Date:{now:()=>now},Math,Number,ArrayBuffer,Int16Array,require:p=>p.endsWith('play-audio-level.js')?audio:p.endsWith('/toast.js')?message=>toasts.push(message):p.endsWith('motion-preference.js')?{readReducedMotion:()=>true}:{haptic(){}},setTimeout:(fn,ms)=>schedule(fn,ms,false),clearTimeout:n=>tasks.delete(n),setInterval:(fn,ms)=>schedule(fn,ms,true),clearInterval:n=>tasks.delete(n)},{filename:file});return def;}
 definition=load(FILE);const stageDef=load('pages/play/components/play-stage/index');
 function instance(def,props){const data={};for(const[k,v]of Object.entries(def.properties))data[k]=v.value;Object.assign(data,JSON.parse(JSON.stringify(def.data)),props);return Object.assign({data,setData(p,cb){Object.assign(data,p);if(cb)cb();}},def.methods);}
 const c=instance(definition,{show:true,seconds:options.seconds||1});
 const view=fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8');const keyed=/wx:for="{{stageKeys}}"/.test(view);
 function mount(){const key=c.data.stageKeys?c.data.stageKeys[0]:0;const s=instance(stageDef,{show:true,calibrateLabel:'校准',reducedMotion:true});s.triggerEvent=(name,detail)=>{const e={detail,currentTarget:{dataset:{stageKey:key}}};if(name==='calibratetick')c.onSample(e);if(name==='calibrated')c.onCalibrated(e);if(name==='run')c.onRun(e);if(name==='verdict')c.onVerdict(e);if(name==='back')c.onClose(e);};stage=s;return s;}
 mount();c.selectComponent=()=>stage;c.triggerEvent=(name,detail)=>events.push({name,detail});c.setData=(p,cb)=>{const old=c.data.stageKeys&&c.data.stageKeys[0];Object.assign(c.data,p);if(keyed&&c.data.stageKeys&&old!==c.data.stageKeys[0])mount();if(cb)cb();};
 function tick(ms){const end=now+ms;while(true){const t=[...tasks.values()].filter(t=>t.due<=end).sort((a,b)=>a.due-b.due||a.id-b.id)[0];if(!t)break;now=t.due;if(t.repeat)t.due+=t.ms;else tasks.delete(t.id);t.fn();}now=end;}
 function emit(kind,value){for(const fn of [...sets[kind]])fn(value);}
 return{c,definition,sets,callbacks,history,tasks,events,toasts,rec,tick,emit,wx,settingCallbacks,get stage(){return stage;},get starts(){return starts;},get stops(){return stops;},jump(ms){now+=ms;},frame(value=0){emit('frame',{frameBuffer:pcm(value)});},begin(){c.onStart();},run(){c.onStart();this.frame(0);stage.onCalibrateTick();stage.onCalibrated();},interrupt(kind){if(kind==='hide')definition.pageLifetimes.hide.call(c);if(kind==='close')c.onClose();if(kind==='false'){c.data.show=false;definition.observers['show, seconds'].call(c,false,c.data.seconds);}if(kind==='detach')definition.lifetimes.detached.call(c);},resume(kind){if(kind==='hide'&&definition.pageLifetimes.show)definition.pageLifetimes.show.call(c);if(kind==='close'||kind==='false'){c.data.show=true;definition.observers['show, seconds'].call(c,true,c.data.seconds);}}};
}
const results=h=>h.events.filter(e=>e.name==='submit'||e.name==='verdict');
for(const phase of ['starting','calibrating','counting','running'])for(const kind of ['hide','close','false','detach'])test(`${kind} aborts ${phase} recorder and real stage without result`,()=>{
 const h=host({syncStart:phase!=='starting'});h.begin();if(phase==='counting'){h.stage.data.limitSeconds=1;h.frame();h.stage.onCalibrateTick();h.stage.onCalibrated();}if(phase==='running'){h.frame();h.stage.onCalibrateTick();h.stage.onCalibrated();}
 const old=h.stage,callbacks=h.callbacks.slice();h.interrupt(kind);assert.equal(h.c.data.running,false);assert.equal(h.tasks.size,0);for(const set of Object.values(h.sets))assert.equal(set.size,0);assert.ok(h.stops>=1);assert.equal(old.data.calibrating,false);assert.equal(old.data.countingIn,false);
 for(const cb of callbacks)cb.fn(cb.kind==='frame'?{frameBuffer:pcm(.9)}:{});old.onCalibrated();old.onCountedIn();old.fail(false);h.tick(4000);assert.equal(results(h).length,0);assert.equal(h.c.data.running,false);
});
for(const missing of ['start','stop','onStart','offStart','onStop','offStop','onFrameRecorded','offFrameRecorded','onError','offError'])test(`missing ${missing} fails safely without stage/listener`,()=>{const h=host({missing});assert.doesNotThrow(()=>h.begin());assert.equal(h.stage.data.calibrating,false);assert.equal(h.c.data.running,false);assert.equal(h.tasks.size,0);for(const s of Object.values(h.sets))assert.equal(s.size,0);assert.equal(results(h).length,0);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);assert.ok(h.toasts.length);});
for(const opts of [{noRecorder:true},{throwStart:true},{syncError:{errMsg:'recorder busy'}},{syncStart:false}])test(`start failure ${JSON.stringify(opts)} never starts stage`,()=>{const h=host(opts);h.begin();if(opts.syncStart===false)h.tick(3000);assert.equal(h.stage.data.calibrating,false);assert.equal(h.tasks.size,0);assert.equal(results(h).length,0);assert.equal(h.c.data.running,false);for(const s of Object.values(h.sets))assert.equal(s.size,0);});
test('onStart success must wait for start() return; sync success followed by failure cannot begin stage',()=>{const h=host({syncError:{errMsg:'busy'}});h.begin();assert.equal(h.stage.data.calibrating,false);assert.equal(h.starts,1);assert.equal(results(h).length,0);});
test('async start success begins calibration and async busy failure is retryable, not permission denial',()=>{const h=host({syncStart:false});h.begin();assert.equal(h.stage.data.calibrating,false);h.emit('start');assert.equal(h.stage.data.calibrating,true);h.emit('error',{errMsg:'busy'});assert.equal(h.c.data.running,false);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);assert.ok(h.toasts.length);});
test('only actual denial or actual scope.record false emits micdenied',()=>{for(const opts of [{permission:false},{syncError:{errMsg:'operateRecorder:fail auth deny'}}]){const h=host(opts);h.begin();if(!opts.syncError)h.emit('error',{});assert.equal(h.events.filter(e=>e.name==='micdenied').length,1);}for(const value of [undefined,true,'false',0]){const h=host({permission:value});h.begin();h.emit('error',{errMsg:'busy'});assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);}});
test('old callbacks, old stage, old timer and broadcast stop cannot kill a new attempt',()=>{const h=host();h.run();const old=h.callbacks.slice(),s=h.stage,t=h.history.map(t=>t.fn);h.interrupt('hide');h.resume('hide');h.run();for(const cb of old)cb.fn(cb.kind==='frame'?{frameBuffer:pcm(.9)}:{errMsg:'auth deny'});for(const fn of t)fn();s.onCalibrated();s.fail(false);s.onBack();h.emit('stop');assert.equal(h.c.data.running,true);assert.equal(results(h).length,0);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);});
test('empty malformed PCM never calibrates or becomes true zero',()=>{for(const frameBuffer of [null,undefined,{},[],new Uint8Array(4),new ArrayBuffer(0),new ArrayBuffer(3)]){const h=host();h.begin();h.emit('frame',{frameBuffer});h.stage.onCalibrateTick();assert.equal(h.c._samples.length,0);h.tick(3000);assert.equal(results(h).length,0);assert.equal(h.c.data.running,false);assert.ok(h.toasts.length);}});
test('true zero PCM is valid calibration, but first running sample cannot borrow calibration duration',()=>{const h=host();h.run();assert.ok(Number.isFinite(h.c._hot));h.jump(300);h.frame(.8);assert.equal(h.c._held,0);assert.equal(h.c.data.running,true);});
test('one loud running frame cannot extrapolate a hold and gap interrupts',()=>{const h=host({seconds:.2});h.run();h.frame(.8);h.tick(500);assert.equal(results(h).length,0);assert.equal(h.c.data.running,false);assert.ok(h.toasts.length);});
test('delayed timer or late frame cannot hide a stale gap or bypass finish',()=>{for(const entry of ['timer','frame','finish']){const h=host();h.run();h.frame(.8);h.jump(500);if(entry==='frame')h.frame(.8);else if(entry==='finish')h.c._finish();else h.tick(100);assert.equal(results(h).length,0);assert.equal(h.c.data.running,false);}});
test('continuous valid samples accumulate only consecutive sample intervals and submit unchanged heldMs once',()=>{const h=host({seconds:1});h.run();for(let i=0;i<12;i++){h.frame(.8);h.tick(100);}const submit=h.events.filter(e=>e.name==='submit');assert.equal(submit.length,1);assert.deepEqual(Object.keys(submit[0].detail),['heldMs']);assert.ok(Number.isFinite(submit[0].detail.heldMs)&&submit[0].detail.heldMs>=1000);assert.equal(h.tasks.size,0);for(const set of Object.values(h.sets))assert.equal(set.size,0);h.c._finish();assert.equal(h.events.filter(e=>e.name==='submit').length,1);});
test('strict hot threshold and below-line percentage never claim 100',()=>{const h=host();h.run();const hot=h.c._hot;h.frame(hot-.000001);assert.equal(h.c.data.loud,false);assert.ok(h.c.data.levelPct<100);h.frame(hot);assert.equal(h.c.data.loud,false);assert.ok(h.c.data.levelPct<100);});
test('voice UI describes local relative PCM, no dB or excessive voice instruction',()=>{const s=fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8');assert.match(s,/本机.*PCM/);assert.match(s,/相对.*阈值/);assert.match(s,/舒适|不适/);assert.doesNotMatch(s,/按住一口气|吼满|用力喊|分贝值|dB/);});
test('original retry after a completed local run creates a new recording and isolates prior callbacks',()=>{
 const h=host({seconds:.2});h.run();const old=h.callbacks.slice(),stage=h.stage;
 for(let i=0;i<5;i++){h.frame(.8);h.tick(100);}assert.equal(h.events.filter(e=>e.name==='submit').length,1);
 h.run();assert.equal(h.c.data.running,true);assert.notEqual(h.stage,stage);assert.equal(h.starts,2);
 for(const cb of old)cb.fn(cb.kind==='frame'?{frameBuffer:pcm(.9)}:{errMsg:'auth deny'});stage.fail(false);stage.onBack();
 assert.equal(h.c.data.running,true);assert.equal(h.events.filter(e=>e.name==='submit').length,1);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);
});
test('delayed permission read from an old error cannot deny a new recording',()=>{
 const h=host({deferSetting:true});h.run();h.emit('error',{errMsg:'busy'});assert.equal(h.settingCallbacks.length,1);
 h.run();h.settingCallbacks[0]({authSetting:{'scope.record':false}});assert.equal(h.c.data.running,true);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);
});
test('throwing setting lookup keeps the visible generic recovery and never reports denial',()=>{
 const h=host({throwSetting:true});h.run();assert.doesNotThrow(()=>h.emit('error',{errMsg:'busy'}));assert.ok(h.toasts.length);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);
});
test('repeated start taps during asynchronous startup and calibration register one owned listener set',()=>{
 const h=host({syncStart:false});h.begin();h.begin();assert.equal(h.starts,1);for(const set of Object.values(h.sets))assert.equal(set.size,1);
 h.emit('start');h.begin();assert.equal(h.starts,1);for(const set of Object.values(h.sets))assert.equal(set.size,1);
});

test('completion cleanup ignores queued error/watchdog during synchronous stage verdict and submits once',()=>{
 const h=host({seconds:.2});h.run();const error=h.callbacks.find(cb=>cb.kind==='error').fn;const watchdog=h.history.find(t=>!t.repeat).fn;
 const win=h.stage.win;h.stage.win=function(...args){error({errMsg:'busy'});watchdog();return win.apply(this,args);};
 for(let i=0;i<5;i++){h.frame(.8);h.tick(100);}
 assert.equal(h.events.filter(e=>e.name==='submit').length,1);assert.equal(h.toasts.length,0);assert.equal(h.events.filter(e=>e.name==='micdenied').length,0);
});
test('malformed running PCM breaks loud continuity without treating it as a zero measurement',()=>{const h=host();h.run();h.frame(.8);h.tick(100);h.frame(.8);assert.ok(h.c._held>0);h.tick(100);h.emit('frame',{frameBuffer:new ArrayBuffer(3)});h.tick(100);h.frame(.8);assert.equal(h.c._held,0);assert.equal(results(h).length,0);});
