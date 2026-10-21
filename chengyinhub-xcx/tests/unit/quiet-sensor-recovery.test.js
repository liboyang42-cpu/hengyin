const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {serverPayload} = require('../../pages/play/utils/playkit-view.js');
const ROOT=path.resolve(__dirname,'../..');
const FILE='pages/play/components/playkit-quiethold/index';
function host(options={}) {
  let now=1000, serial=0, stage, beginCount=0;
  const tasks=new Map(), history=[], callbacks={}, removed=[], events=[], messages=[];
  const rec={startCalls:0,stopCalls:0};
  for(const name of ['Start','Stop','Error','FrameRecorded']) {
    callbacks[name]=new Set();rec['on'+name]=fn=>{callbacks[name].add(fn);};rec['off'+name]=fn=>{removed.push({name,fn});callbacks[name].delete(fn);};
  }
  const emit=(name,value)=>{for(const cb of [...callbacks[name]])cb(value);};
  rec.start=()=>{rec.startCalls++;if(options.syncStart)emit('Start');if(options.syncError)emit('Error',{errMsg:'device busy'});if(options.throwStart)throw Error('device busy');};
  rec.stop=()=>{rec.stopCalls++;emit('Stop');};
  const settings=[]; const wx={getRecorderManager:()=>rec,getSetting:arg=>{settings.push(arg);if(!options.delayedSetting)arg.success({authSetting:{'scope.record':options.permission}});}};
  if(options.missingManager)delete wx.getRecorderManager;
  if(options.missingMethod)delete rec[options.missingMethod];
  const timer=(fn,ms,repeat)=>{const t={id:++serial,fn,ms,repeat,due:now+ms};tasks.set(t.id,t);history.push(t);return t.id;};
  function load(file) {
    let def;
    vm.runInNewContext(fs.readFileSync(path.join(ROOT,file+'.js'),'utf8'),{
      Component:d=>{def=d;}, require:name=>name.includes('play-audio-level')?require('../../pages/play/utils/play-audio-level.js'):name.endsWith('/toast.js')?s=>messages.push(s):{haptic(){}},
      wx,Date:{now:()=>now},Math,Number,ArrayBuffer,Int16Array,
      setTimeout:(fn,ms)=>timer(fn,ms,false),clearTimeout:id=>tasks.delete(id),setInterval:(fn,ms)=>timer(fn,ms,true),clearInterval:id=>tasks.delete(id),
    },{filename:file});return def;
  }
  const def=load(FILE), sd=load('pages/play/components/play-stage/index');
  function make(d,props={}) {const data={};for(const [k,v]of Object.entries(d.properties))data[k]=v.value;Object.assign(data,JSON.parse(JSON.stringify(d.data)),props);return Object.assign({data,setData(p,cb){Object.assign(data,p);if(cb)cb();}},d.methods);}
  const c=make(def,{show:true,seconds:1,...options.props});
  const keyed=/wx:for="{{stageKeys}}"[\s\S]*?wx:key="\*this"/.test(fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8'));
  function mount(){const key=c.data.stageKeys?c.data.stageKeys[0]:0;const child=make(sd,{show:true,calibrateLabel:'听一下',countIn:!!options.countIn});const begin=child.begin;child.begin=function(){beginCount++;return begin.apply(this,arguments);};child.triggerEvent=(name,detail)=>{const e={detail,currentTarget:{dataset:{stageKey:key}}};if(name==='calibratetick')c.onSample(e);if(name==='calibrated')c.onCalibrated(e);if(name==='run')c.onRun(e);if(name==='verdict')c.onVerdict(e);if(name==='back')c.onClose(e);};stage=child;}
  mount();c.selectComponent=()=>stage;c.triggerEvent=(name,detail)=>events.push({name,detail});c.setData=(patch,cb)=>{const key=c.data.stageKeys&&c.data.stageKeys[0];Object.assign(c.data,patch);if(keyed&&c.data.stageKeys&&c.data.stageKeys[0]!==key)mount();if(cb)cb();};
  def.observers['show, seconds'].call(c,true,c.data.seconds);
  function tick(ms){const end=now+ms;while(true){const t=[...tasks.values()].filter(t=>t.due<=end).sort((a,b)=>a.due-b.due||a.id-b.id)[0];if(!t)break;now=t.due;if(t.repeat)t.due+=t.ms;else tasks.delete(t.id);t.fn();}now=end;}
  const pcm=value=>{const a=new Int16Array(8);a.fill(Math.round(value*32767));return a.buffer;};
  return {c,def,rec,callbacks,removed,history,tasks,events,messages,settings,tick,emit,pcm,
    get stage(){return stage;},get begins(){return beginCount;},
    start(){c.onStart();if(!options.syncStart)emit('Start');},
    frame(value=0){emit('FrameRecorded',{frameBuffer:pcm(value)});},
    raw(value){emit('FrameRecorded',{frameBuffer:value});},
    run(){this.start();this.frame(0);stage.onCalibrateTick();stage.onCalibrated();if(options.countIn)stage.onCountedIn();},
    interrupt(kind){if(kind==='hide')def.pageLifetimes.hide.call(c);if(kind==='close')c.onClose();if(kind==='false'){c.data.show=false;def.observers['show, seconds'].call(c,false,c.data.seconds);}if(kind==='detach')def.lifetimes.detached.call(c);},
    resume(kind){if(kind==='hide'&&def.pageLifetimes.show)def.pageLifetimes.show.call(c);if(kind==='false'||kind==='close'){c.data.show=true;def.observers['show, seconds'].call(c,true,c.data.seconds);}},
    jump(ms){now+=ms;},
  };
}
const count=(h,n)=>h.events.filter(e=>e.name===n).length;
for(const kind of ['hide','close','false','detach'])for(const phase of ['starting','calibrating','counting','running'])test(`quiet ${kind} stops ${phase} without submit; stale real stage callbacks inert`,()=>{
  const h=host({countIn:phase==='counting'});h.c.onStart();if(phase!=='starting')h.emit('Start');if(phase==='counting'||phase==='running'){h.frame();h.stage.onCalibrateTick();h.stage.onCalibrated();}
  const old=h.stage, listeners=Object.fromEntries(Object.entries(h.callbacks).map(([k,v])=>[k,[...v]]));h.interrupt(kind);
  assert.equal(h.c.data.running,false);assert.equal(h.tasks.size,0);assert.ok(h.rec.stopCalls>=1);for(const set of Object.values(h.callbacks))assert.equal(set.size,0);assert.equal(old.data.calibrating,false);assert.equal(old.data.countingIn,false);
  for(const fn of listeners.Start)fn();for(const fn of listeners.Error)fn({errMsg:'auth deny'});for(const fn of listeners.FrameRecorded)fn({frameBuffer:h.pcm(0)});for(const fn of listeners.Stop)fn();old.onCalibrated();old.onCountedIn();old.fail(false);h.tick(10000);
  assert.equal(count(h,'submit'),0);assert.equal(count(h,'micdenied'),0);assert.equal(h.c.data.running,false);
});
for(const kind of ['hide','close','false'])test(`quiet ${kind} retry has exact single handlers and ignores old frame/error/stop/watchdog`,()=>{
  const h=host();h.run();const old=Object.fromEntries(Object.entries(h.callbacks).map(([k,v])=>[k,[...v]])), timers=h.history.map(t=>t.fn), stage=h.stage;
  h.interrupt(kind);h.resume(kind);h.run();const before=JSON.stringify(h.c.data);
  for(const cb of old.Error)cb({errMsg:'auth deny'});for(const cb of old.Start)cb();for(const cb of old.Stop)cb();for(const cb of old.FrameRecorded)cb({frameBuffer:h.pcm(1)});for(const fn of timers)fn();stage.onCalibrated();stage.fail(false);
  assert.equal(JSON.stringify(h.c.data),before);for(const set of Object.values(h.callbacks))assert.equal(set.size,1);assert.equal(count(h,'start'),2);assert.equal(count(h,'submit'),0);assert.equal(count(h,'micdenied'),0);
});
for(const opt of [{missingManager:true},{missingMethod:'start'},{missingMethod:'offError'},{syncError:true},{throwStart:true},{syncStart:true,syncError:true},{syncStart:true,throwStart:true}])test('quiet start failure does not begin stage/register after failure '+JSON.stringify(opt),()=>{
  const h=host(opt);assert.doesNotThrow(()=>h.c.onStart());assert.equal(h.begins,0);assert.equal(h.tasks.size,0);for(const s of Object.values(h.callbacks))assert.equal(s.size,0);assert.equal(count(h,'submit'),0);assert.equal(count(h,'micdenied'),0);assert.ok(h.messages.some(m=>/重试|退出/.test(m)));
});
test('quiet actual onStart confirmation required; late start after timeout cannot calibrate',()=>{const h=host();h.c.onStart();assert.equal(h.begins,0);const fn=[...h.callbacks.Start][0];h.tick(3000);fn();assert.equal(h.begins,0);assert.equal(h.tasks.size,0);});
test('quiet no or malformed PCM cannot calibrate or finish; even true zero is valid',()=>{
  for(const raw of [undefined,null,{},new ArrayBuffer(0),new ArrayBuffer(1),new ArrayBuffer(3),new Int16Array([0]),[0,0]]){const h=host();h.start();assert.doesNotThrow(()=>h.raw(raw));h.stage.onCalibrateTick();h.stage.onCalibrated();h.tick(4000);assert.equal(count(h,'submit'),0);assert.equal(count(h,'start'),0);assert.equal(h.c.data.running,false);}
  const h=host();h.run();assert.equal(count(h,'start'),1);assert.equal(h.c.data.running,true);
});
test('quiet no runtime frames, one old zero frame or stale final entry cannot award held time',()=>{
  for(const mode of ['none','one','finish']){const h=host();h.run();if(mode!=='none')h.frame(0);if(mode==='finish'){h.jump(500);h.c._finish(true);}else h.tick(2000);assert.equal(count(h,'submit'),0);assert.equal(h.c.data.running,false);}
});
test('quiet genuinely continuous zero PCM completes once with original seconds payload and parent milliseconds',()=>{
  const h=host();h.run();h.frame(0);for(let i=0;i<12&&h.c.data.running;i++){h.tick(100);h.frame(0);}const result=h.events.find(e=>e.name==='submit');assert.ok(result);assert.equal(result.detail.heldSeconds,1);assert.equal(result.detail.passed,true);assert.deepEqual(Object.keys(result.detail).sort(),['heldSeconds','passed']);assert.equal(serverPayload('quiethold','submit',result.detail).heldMs,1000);for(const fn of h.history.map(t=>t.fn))fn();assert.equal(count(h,'submit'),1);
});
test('quiet real hot PCM fails existing rule only with a valid measured frame',()=>{const h=host();h.run();h.frame(.9);const r=h.events.find(e=>e.name==='submit');assert.ok(r);assert.equal(r.detail.passed,false);assert.equal(Number.isFinite(r.detail.heldSeconds),true);});
for(const [error,permission,denied] of [[{errMsg:'operateRecorder:fail auth deny'},undefined,true],[{errMsg:'busy'},false,true],[{errMsg:'device busy'},true,false],[{errMsg:'not support'},undefined,false],[{},undefined,false]])test('quiet permission evidence '+JSON.stringify([error,permission]),()=>{
  const h=host({permission});h.c.onStart();h.emit('Error',error);assert.equal(count(h,'micdenied'),denied?1:0);assert.equal(h.c.data.running,false);assert.equal(h.tasks.size,0);if(!denied)assert.ok(h.messages.some(m=>/重试|退出/.test(m)));
});
test('quiet view describes relative local PCM without dB or reward guarantees',()=>{const s=fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8');assert.match(s,/本机.*PCM/);assert.match(s,/阈值|校准/);assert.doesNotMatch(s,/\bdB\b|保障奖励|保证奖励/);});
test('quiet delayed permission readback cannot deny a new healthy recording',()=>{const h=host({delayedSetting:true});h.c.onStart();h.emit('Error',{errMsg:'device busy'});h.run();h.settings[0].success({authSetting:{'scope.record':false}});assert.equal(count(h,'micdenied'),0);assert.equal(h.c.data.running,true);});
test('quiet original entry can retry a measured failure, while old error and verdict stay inert',()=>{const h=host();h.run();const error=[...h.callbacks.Error][0], stage=h.stage;h.frame(.9);assert.equal(count(h,'submit'),1);h.run();error({errMsg:'auth deny'});stage.fail(false);assert.equal(h.c.data.running,true);assert.equal(count(h,'start'),2);assert.equal(count(h,'submit'),1);});
test('quiet queued native stop broadcast cannot stop a newly owned recording',()=>{const h=host();h.run();h.interrupt('hide');h.resume('hide');h.run();h.emit('Stop');h.frame(0);for(let i=0;i<12&&h.c.data.running;i++){h.tick(100);h.frame(0);}assert.equal(count(h,'submit'),1);});
test('quiet one fresh frame cannot bypass required measured duration through final success entry',()=>{const h=host();h.run();h.frame(0);h.c._finish(true);assert.equal(count(h,'submit'),0);assert.equal(h.c.data.running,false);});
test('quiet ambiguous device error mentioning permission is not authoritative denial',()=>{const h=host();h.c.onStart();h.emit('Error',{errMsg:'device busy; previous auth deny?'});assert.equal(count(h,'micdenied'),0);assert.ok(h.messages.length);});
test('quiet duplicate start while waiting for native start or calibrating remains single-flight',()=>{const h=host();h.c.onStart();h.c.onStart();assert.equal(h.rec.startCalls,1);h.emit('Start');h.c.onStart();assert.equal(h.rec.startCalls,1);assert.equal(h.begins,1);});
test('quiet malformed runtime frame breaks consecutive sample duration instead of bridging the gap',()=>{const h=host();h.run();h.frame(0);h.tick(100);h.frame(0);assert.ok(h.c._held>0);h.tick(100);h.raw(new ArrayBuffer(3));h.tick(100);h.frame(0);assert.equal(h.c._held,0);assert.equal(count(h,'submit'),0);});
