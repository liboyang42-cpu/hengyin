const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { serverPayload } = require('../../pages/play/utils/playkit-view.js');
const ROOT = path.resolve(__dirname, '../..');
const FILE = 'pages/play/components/playkit-reaction/index';

function host(props = {}) {
  let now = 1000, nextId = 0;
  const tasks = new Map(), history = [], events = [];
  const math = Object.create(Math); math.random = () => 0;
  function load(file) {
    let def;
    vm.runInNewContext(fs.readFileSync(path.join(ROOT, file + '.js'), 'utf8'), {
      Component(value) { def = value; }, require: name => name.endsWith('/sensor-contract.js') ? require('../../pages/play/utils/sensor-contract.js') : ({haptic() {}}), Math: math,
      Date: {now: () => now}, Number,
      setTimeout(fn, ms) { const t = {id:++nextId, fn, due:now + ms}; tasks.set(t.id,t); history.push(t); return t.id; },
      clearTimeout(id) { tasks.delete(id); }, clearInterval(id) { tasks.delete(id); },
    }, {filename:file});
    return def;
  }
  function instance(def, props) {
    const data = {};
    for(const [key, value] of Object.entries(def.properties)) data[key] = value.value;
    Object.assign(data,JSON.parse(JSON.stringify(def.data)),props);
    return Object.assign({data,setData(patch,cb) {Object.assign(data,patch);if(cb)cb();}},def.methods);
  }
  const def = load(FILE), stageDef = load('pages/play/components/play-stage/index');
  const component = instance(def,{show:true,...props});
  const stage = instance(stageDef,{show:true,reducedMotion:true});
  stage.triggerEvent = (name,detail) => {if(name==='verdict') component.onVerdict({detail});};
  component.selectComponent = () => stage;
  component.triggerEvent = (name,detail) => events.push({name,detail});
  def.observers['show, rounds'].call(component,true,component.data.rounds);
  function tick(ms) {
    const end = now + ms;
    while(true) {
      const t = [...tasks.values()].filter(t=>t.due<=end).sort((a,b)=>a.due-b.due)[0];
      if(!t)break; now=t.due;tasks.delete(t.id);t.fn();
    }
    now=end;
  }
  return {component, stage, def, tasks, history, events, tick,
    tap() {component.onTap();},
    measure(ms=250) {component.onTap();tick(1400);tick(ms);component.onTap();},
    interrupt(kind) {
      if(kind==='hide')def.pageLifetimes.hide.call(component);
      if(kind==='close')component.onClose();
      if(kind==='false'){component.data.show=false;def.observers['show, rounds'].call(component,false,component.data.rounds);}
      if(kind==='detach')def.lifetimes.detached.call(component);
    },
    resume(kind) {
      if(kind==='hide'&&def.pageLifetimes.show)def.pageLifetimes.show.call(component);
      if(kind==='false'||kind==='close'){component.data.show=true;def.observers['show, rounds'].call(component,true,component.data.rounds);}
    },
  };
}
const count = (h,name) => h.events.filter(e=>e.name===name).length;
function phaseHost(phase) {
  const h=host();
  if(phase==='idle'||phase==='early')h.measure();
  if(phase==='wait'||phase==='go'||phase==='early')h.tap();
  if(phase==='go')h.tick(1400);
  if(phase==='early')h.tap();
  assert.equal(h.component.data.phase,phase);
  return h;
}
for(const kind of ['hide','close','false','detach']) for(const phase of ['wait','go','idle','early']) test(`${kind} invalidates ${phase}, clears partial samples/arming/unit, rejects hidden taps and timers`,()=>{
  const h=phaseHost(phase), old=h.history.map(t=>t.fn);
  const starts=count(h,'start'); h.interrupt(kind);
  assert.equal(h.tasks.size,0);
  assert.equal(h.component._times.length,0);
  assert.equal(h.component._armed,false);
  assert.equal(h.component._goAt,null);
  assert.equal(h.component.data.phase,'idle');
  assert.equal(h.component.data.big,'—'); assert.equal(h.component.data.unit,'');
  const before=JSON.stringify(h.component.data);
  for(const fn of old)fn(); h.tap(); h.tick(10000);
  assert.equal(JSON.stringify(h.component.data),before);
  assert.equal(count(h,'submit'),0);assert.equal(count(h,'start'),starts);
});
for(const kind of ['hide','close','false']) test(`${kind}: original entry restarts with a fresh start and samples`,()=>{
  const h=phaseHost('idle'); const old=h.history.map(t=>t.fn); h.interrupt(kind);h.resume(kind);h.tap();
  assert.equal(count(h,'start'),2);assert.equal(h.component.data.phase,'wait');
  for(const fn of old)fn();assert.equal(h.component.data.phase,'wait');
  h.tick(1400);h.tick(270);h.tap();h.measure(260);h.measure(280);
  const result=h.events.filter(e=>e.name==='submit');assert.equal(result.length,1);
  assert.deepEqual(Array.from(result[0].detail.times),[270,260,280]);
});
for(const rounds of [1,2,3,5]) test(`normal ${rounds} round session: units, exact local result, threshold and unchanged parent mapping`,()=>{
  const h=host({rounds,goalMs:250}); const values=Array.from({length:rounds},(_,i)=>260+i*10); if(rounds>1)values[rounds-1]=240;
  for(let i=0;i<rounds;i++) {
    h.tap();assert.equal(h.component.data.unit,'');assert.equal(h.component.data.phase,'wait');
    h.tick(1400);assert.equal(h.component.data.unit,'');h.tick(values[i]);h.tap();
    assert.equal(h.component.data.unit,'毫秒 · 本机测量');
    if(i<rounds-1)assert.match(h.component.data.sub,new RegExp(`${i+1}/${rounds}`));
  }
  assert.equal(h.component.data.phase,'done');assert.match(h.component.data.sub,new RegExp(`${rounds} 轮`));
  const result=h.events.find(e=>e.name==='submit').detail;
  assert.deepEqual(Array.from(result.times),values);assert.equal(result.bestMs,Math.min(...values));assert.equal(result.passed,Math.min(...values)<=250);
  assert.deepEqual(Object.keys(result).sort(),['bestMs','passed','times']);
  assert.deepEqual(Array.from(serverPayload('reaction','submit',result).roundsMs),values);
  assert.equal(count(h,'start'),1);h.tap();assert.equal(count(h,'submit'),1);
});
test('goalMs equality and strict below-human early rule retain existing decision',()=>{
  const h=host({rounds:1,goalMs:120});h.tap();h.tick(1400);h.tick(119);h.tap();
  assert.equal(h.component.data.phase,'early');assert.equal(h.component.data.unit,'');assert.equal(count(h,'submit'),0);
  h.measure(120);assert.equal(h.events.find(e=>e.name==='submit').detail.passed,true);assert.equal(count(h,'start'),1);
});
test('early tap invalidates its old green timer even after a fresh retry',()=>{
  const h=host();h.tap();const old=h.history[0].fn;h.tap();assert.equal(h.component.data.phase,'early');assert.equal(h.component.data.unit,'');
  old();assert.equal(h.component.data.phase,'early');h.tap();old();assert.equal(h.component.data.phase,'wait');h.tick(1400);assert.equal(h.component.data.phase,'go');assert.equal(count(h,'start'),1);
});
for(const kind of ['hide','close','false','detach']) test(`completed measurement ${kind} cannot submit again and clears retained captures`,()=>{
  const h=host({rounds:1});h.measure();h.interrupt(kind);
  assert.equal(h.component._times.length,0);assert.equal(h.component._armed,false);assert.equal(h.component._goAt,null);assert.equal(h.component.data.unit,'');
  for(const task of h.history)task.fn();h.tap();
  if(kind==='hide'){h.resume(kind);h.tap();}
  assert.equal(count(h,'submit'),1);assert.equal(count(h,'start'),1);
});
test('show/rounds reconfiguration invalidates the old timer and uses the configured new round count',()=>{
  const h=host();h.tap();const old=h.history[0].fn;h.component.data.rounds=2;h.def.observers['show, rounds'].call(h.component,true,2);
  old();assert.equal(h.component.data.phase,'idle');h.measure(300);h.measure(280);
  assert.match(h.component.data.sub,/2 轮/);assert.equal(count(h,'submit'),1);
});
test('late stage verdict while hidden cannot report a local result',()=>{
  const h=phaseHost('go');h.interrupt('hide');h.stage.fail(false);assert.equal(count(h,'verdict'),0);assert.equal(count(h,'submit'),0);
});
test('view explains local measurement, consumes numeric unit separately and does not promise anti-cheat or rewards',()=>{
  const view=fs.readFileSync(path.join(ROOT,FILE+'.wxml'),'utf8');
  assert.match(view,/wx:if="{{unit}}"[^>]*>{{unit}}/);
  assert.match(view,/本机.*屏幕变绿.*点击.*时间差/);
  assert.doesNotMatch(view,/防作弊|保障奖励|保证奖励|三轮/);
});
test('synchronous hide from real stage verdict prevents a late submit from the interrupted call stack',()=>{
  const h=host({rounds:1}); const trigger=h.component.triggerEvent;
  h.component.triggerEvent=(name,detail)=>{trigger(name,detail);if(name==='verdict')h.interrupt('hide');};
  h.measure(250); assert.equal(count(h,'submit'),0); assert.equal(h.component._times.length,0);assert.equal(h.component.data.unit,'');
});
test('reentrant hide from start cannot schedule a green timer',()=>{
  const h=host();const trigger=h.component.triggerEvent;
  h.component.triggerEvent=(name,detail)=>{trigger(name,detail);if(name==='start')h.interrupt('hide');};
  h.tap();assert.equal(h.tasks.size,0);assert.equal(h.component.data.phase,'idle');h.tick(10000);assert.equal(count(h,'submit'),0);
});

// DOC2 FXA(#478-3 后续):服务端在这一局开表后新发的挑战身份随成绩交,诚实玩家不被封顶;
// 开表那一刻手里的旧号不算(拿它去交只会被判作废),新号没回来就不带,走存量复核口径。
const plain=x=>JSON.parse(JSON.stringify(x)); // vm 里造的对象跨 realm,按值比
test('attemptId issued after this run starts is submitted; the stale one is not',()=>{
  const h=host({rounds:1,goalMs:400,attemptId:11});
  h.tap();assert.equal(count(h,'start'),1);
  h.component.data.attemptId=22;                 // START_CHALLENGE 回包刷新 kit.attemptId
  h.tick(1400);h.tick(250);h.tap();
  const detail=h.events.find(e=>e.name==='submit').detail;
  assert.equal(detail.attemptId,22);
  assert.deepEqual(plain(serverPayload('reaction','submit',detail)),{roundsMs:[250],attemptId:22});
  const stale=host({rounds:1,goalMs:400,attemptId:11});
  stale.tap();stale.tick(1400);stale.tick(250);stale.tap();
  const legacy=stale.events.find(e=>e.name==='submit').detail;
  assert.equal('attemptId' in legacy,false,'开表时手里的旧号不随成绩交');
  assert.deepEqual(plain(serverPayload('reaction','submit',legacy)),{roundsMs:[250]});
});
