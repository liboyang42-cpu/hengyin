const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const BALL = 'pages/play/components/playkit-ballshake/index';
const STAGE = 'pages/play/components/play-stage/index';

// Load both production components. The view host preserves keyed child instances,
// so a stopped old stage can still deliver its captured callbacks in negative controls.
function host(options = {}) {
  let now = 1000, serial = 0, stage;
  const tasks = new Map(), history = [], listeners = new Set(), removed = [], starts = [], events = [], toasts = [];
  const timer = (fn, ms, repeat) => { const id = ++serial; const t = { id, fn, due: now + ms, ms, repeat }; tasks.set(id, t); history.push(t); return id; };
  const wx = {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 700 }),
    onAccelerometerChange(fn) { listeners.add(fn); if (options.listenThrow) throw Error('listen'); },
    offAccelerometerChange(fn) { removed.push(fn); listeners.delete(fn); },
    startAccelerometer(arg) { starts.push(arg); if (options.startThrow) throw Error('start'); if (options.syncFail) arg.fail(); },
    stopAccelerometer() {},
    showToast(arg) { toasts.push(arg); },
  };
  if (options.missingStart) delete wx.startAccelerometer;
  if (options.missingListen) delete wx.onAccelerometerChange;
  if (options.missingOff) delete wx.offAccelerometerChange;
  if (options.missingStop) delete wx.stopAccelerometer;
  function load(file) {
    let def;
    vm.runInNewContext(fs.readFileSync(path.join(ROOT, file + '.js'), 'utf8'), {
      Component: x => { def = x; }, require: () => ({ haptic() {} }), wx,
      Date: { now: () => now }, Math, Number,
      setTimeout: (fn, ms) => timer(fn, ms, false), clearTimeout: id => tasks.delete(id),
      setInterval: (fn, ms) => timer(fn, ms, true), clearInterval: id => tasks.delete(id),
    }, { filename: file });
    return def;
  }
  const ballDef = load(BALL), stageDef = load(STAGE);
  const keyed = /wx:for="{{stageKeys}}"[\s\S]*?wx:key="\*this"/.test(fs.readFileSync(path.join(ROOT, BALL + '.wxml'), 'utf8'));
  function instance(def, props) {
    const data = {};
    for (const [k, v] of Object.entries(def.properties)) data[k] = v.value;
    Object.assign(data, JSON.parse(JSON.stringify(def.data)), props);
    return Object.assign({ data, setData(patch, cb) { Object.assign(data, patch); if (cb) cb(); } }, def.methods);
  }
  const ball = instance(ballDef, { show: true, timed: true, seconds: 12, ...options.props });
  function mount() {
    const key = ball.data.stageKeys ? ball.data.stageKeys[0] : 0;
    const child = instance(stageDef, { show: true, limitSeconds: ball.data.timed ? ball.data.seconds : 0, calibrateLabel: '校准', reducedMotion: true });
    child.triggerEvent = (name, detail) => {
      const e = { detail, currentTarget: { dataset: { stageKey: key } } };
      if (name === 'run') ball.onRun(e);
      if (name === 'verdict') ball.onVerdict(e);
      if (name === 'back') ball.onClose(e);
    };
    stage = child;
    return child;
  }
  mount();
  ball.selectComponent = () => stage;
  ball.triggerEvent = (name, detail) => events.push({ name, detail });
  ball.setData = (patch, cb) => {
    const old = ball.data.stageKeys && ball.data.stageKeys[0];
    Object.assign(ball.data, patch);
    if (keyed && ball.data.stageKeys && old !== ball.data.stageKeys[0]) mount();
    if (cb) cb();
  };
  function tick(ms) {
    const end = now + ms;
    let count = 0;
    while (true) {
      const next = [...tasks.values()].filter(t => t.due <= end).sort((a,b) => a.due - b.due || a.id - b.id)[0];
      if (!next) break;
      if (++count > 100000) throw Error('timer loop');
      now = next.due;
      if (next.repeat) next.due += next.ms; else tasks.delete(next.id);
      next.fn();
    }
    now = end;
  }
  return { ball, ballDef, wx, tasks, history, listeners, removed, starts, events, toasts, tick,
    get stage() { return stage; },
    run() { ball.onStart(); stage.onCalibrated(); stage.onCountedIn(); },
    sample(value) { for (const fn of [...listeners]) fn(value); },
    interrupt(kind) {
      if (kind === 'hide') ballDef.pageLifetimes.hide.call(ball);
      if (kind === 'close') ball.onClose();
      if (kind === 'false') { ball.data.show = false; ballDef.observers.show.call(ball, false); }
      if (kind === 'detach') ballDef.lifetimes.detached.call(ball);
    },
    resume(kind) {
      if (kind === 'false' || kind === 'close') { ball.data.show = true; ballDef.observers.show.call(ball, true); }
      if (kind === 'hide' && ballDef.pageLifetimes.show) ballDef.pageLifetimes.show.call(ball);
    },
    jump(ms) { now += ms; },
  };
}
const writes = h => h.events.filter(e => e.name === 'submit' || e.name === 'verdict');

for (const kind of ['hide', 'close', 'false', 'detach']) {
  for (const phase of ['calibrate', 'count', 'run']) test(`${kind} interrupts ${phase}, stops real stage and emits no result`, () => {
    const h = host(); h.ball.onStart();
    if (phase !== 'calibrate') h.stage.onCalibrated();
    if (phase === 'run') h.stage.onCountedIn();
    const old = h.stage;
    h.interrupt(kind);
    assert.equal(h.ball.data.running, false);
    assert.equal(h.listeners.size, 0);
    assert.equal(h.tasks.size, 0);
    assert.equal(old.data.calibrating, false);
    assert.equal(old.data.countingIn, false);
    // Force callbacks even though timer cancellation succeeded.
    old.onCalibrated(); old.onCountedIn(); old.failByTime(); h.tick(20000);
    assert.equal(writes(h).length, 0);
    assert.equal(h.ball.data.running, false);
    assert.equal(h.listeners.size, 0);
  });
}
for (const kind of ['hide', 'close', 'false']) test(`${kind}: original start control can retry; old stage/sensor/failure cannot affect retry`, () => {
  const h = host(); h.run();
  const oldStage = h.stage, oldListener = [...h.listeners][0], oldFail = h.starts[0].fail;
  h.interrupt(kind); h.resume(kind); h.run();
  assert.equal(h.ball.data.running, true);
  assert.notEqual(h.stage, oldStage);
  oldListener({ x: 500, y: 500 }); oldFail(); oldStage.onCountedIn(); oldStage.fail(true); oldStage.onBack();
  assert.equal(h.ball.data.running, true);
  assert.equal(h.listeners.size, 1);
  assert.equal(writes(h).length, 0);
  assert.equal(h.events.filter(e => e.name === 'start').length, 2);
});
for (const option of ['missingStart','missingListen','missingOff','missingStop','startThrow','listenThrow','syncFail']) test(`${option}: no owned listener, timer or verdict survives`, () => {
  const h = host({ [option]: true });
  assert.doesNotThrow(() => h.run());
  assert.equal(h.ball.data.running, false);
  assert.equal(h.listeners.size, 0);
  assert.equal(h.tasks.size, 0);
  assert.equal(writes(h).length, 0);
  assert.equal(h.toasts.length,1);
});
test('async start failure clears listener and real deadline; can restart', () => {
  const h = host(); h.run(); h.starts[0].fail();
  assert.equal(h.listeners.size, 0); assert.equal(h.tasks.size, 0); assert.equal(h.ball.data.running, false);
  h.run(); assert.equal(h.ball.data.running, true);
});
test('first frame watchdog and malformed frames cannot leave a running attempt', () => {
  for (const value of [undefined, {}, {x:true,y:0}, {x:'1',y:0}, {x:NaN,y:0}, {x:0,y:Infinity}, {x:[],y:0}]) {
    const h = host(); h.run(); h.sample(value); h.tick(3500);
    assert.equal(h.ball.data.running, false); assert.equal(h.listeners.size, 0); assert.equal(h.tasks.size, 0); assert.equal(writes(h).length, 0);
  }
});
test('valid zero sample calibrates; stalled stream cannot score through inertia even before watchdog task', () => {
  const h = host(); h.run(); h.sample({x:0,y:0});
  assert.equal(h.ball._zero.x, 0); assert.equal(h.ball._zero.y, 0);
  h.sample({x:1,y:1}); h.ball._vx = 1000; h.ball._x = 1000;
  h.jump(3500); h.ball._step();
  assert.equal(h.ball.data.hits, 0); assert.equal(h.ball.data.running, false); assert.equal(writes(h).length, 0);
});
test('invalid frames do not refresh watchdog after valid stream', () => {
  const h = host(); h.run(); h.sample({x:0,y:0}); h.tick(2000);
  h.sample({x:0,y:'0'}); h.tick(1500);
  assert.equal(h.ball.data.running, false); assert.equal(h.listeners.size, 0);
});
test('normal finite frames score and submit unchanged payload exactly once', () => {
  const h = host({props:{goal:1,challenge:{},sharedChallenge:true}}); h.run(); h.sample({x:0,y:0});
  h.ball._x = 0; h.ball._step();
  const submit = h.events.filter(e=>e.name==='submit');
  assert.equal(submit.length,1); assert.equal(submit[0].detail.hits,1); assert.equal(submit[0].detail.passed,true);
  assert.deepEqual(Object.keys(submit[0].detail).sort(),['hits','passed']);
  h.ball.onSubmitHits(); assert.equal(h.events.filter(e=>e.name==='submit').length,1);
  assert.equal(h.ball.data.awaitingResult,true);
  h.interrupt('hide'); h.resume('hide'); h.ball.onStart();
  assert.equal(h.ball.data.awaitingResult,true); assert.equal(h.events.filter(e=>e.name==='start').length,1);
});
test('real visible deadline submits partial hits with existing fail protocol', () => {
  const h = host({props:{seconds:1,challenge:{},sharedChallenge:true}}); h.run(); h.sample({x:0,y:0}); h.tick(1060);
  const submit=h.events.filter(e=>e.name==='submit'); assert.equal(submit.length,1); assert.equal(submit[0].detail.hits,0); assert.equal(submit[0].detail.passed,false);
});
test('duplicate taps during calibration do not begin twice, challenge success/disabled block start', () => {
  const h=host(); h.ball.onStart(); const attempt=h.ball._attempt; h.ball.onStart(); assert.equal(h.ball._attempt,attempt);
  for(const challenge of [{success:true},{disabledReason:'不可开始'}]) { const b=host({props:{challenge}}); b.ball.onStart(); assert.equal(b.stage.data.calibrating,false); }
});
test('finite pure tilt calculation rejects coercible and malformed coordinates', () => {
  const h=host();
  for(const x of [true,'1',[],{},NaN,Infinity]) { const a=h.ball._tiltAccel({x,y:0},{x:0,y:0}); assert.equal(a.gx,0); assert.equal(a.gy,0); }
  assert.equal(h.ball._tiltAccel({x:0,y:1},{x:0,y:0}).gy,.05);
});
test('finished free-play retry also replaces old stage and ignores its queued callbacks', () => {
  const h=host({props:{goal:1}}); h.run(); h.sample({x:0,y:0}); const old=h.stage, fail=h.starts[0].fail;
  h.ball._x=0; h.ball._step(); const count=writes(h).length;
  fail(); assert.equal(h.toasts.length,0);
  h.run(); assert.notEqual(h.stage,old); old.fail(true); old.onCountedIn();
  assert.equal(h.ball.data.running,true); assert.equal(writes(h).length,count); assert.equal(h.listeners.size,1);
});
test('UNKNOWN awaiting result survives every interruption and show transition', () => {
  for(const kind of ['hide','close','false','detach']) {
    const h=host({props:{challenge:{},sharedChallenge:true}}); h.run(); h.sample({x:0,y:0}); h.ball.onSubmitHits();
    h.interrupt(kind); h.resume(kind); h.ball.onStart();
    assert.equal(h.ball.data.awaitingResult,true); assert.equal(h.events.filter(e=>e.name==='submit').length,1); assert.equal(h.events.filter(e=>e.name==='start').length,1);
  }
});
test('queued old physics/watchdog callbacks do not touch next attempt', () => {
  const h=host(); h.run(); h.sample({x:0,y:0}); const old=h.history.map(t=>t.fn);
  h.interrupt('hide'); h.resume('hide'); h.run(); h.sample({x:0,y:0});
  const before=JSON.stringify(h.ball.data); for(const fn of old) fn();
  assert.equal(JSON.stringify(h.ball.data),before); assert.equal(h.ball.data.running,true); assert.equal(h.listeners.size,1);
});
test('ongoing healthy stream renews watchdog beyond 3.5 seconds and manual partial submit remains valid', () => {
  const h=host({props:{sharedChallenge:true,challenge:{}}}); h.run();
  for(let i=0;i<5;i++){h.sample({x:0,y:0}); h.tick(1000);}
  assert.equal(h.ball.data.running,true); h.ball.onSubmitHits();
  const submit=h.events.filter(e=>e.name==='submit'); assert.equal(submit.length,1); assert.equal(submit[0].detail.passed,false);
  assert.equal(h.listeners.size,0); assert.equal(h.tasks.size,0);
});
test('current challenge success/disabled readback cancels active work without submitting local result', () => {
  for(const challenge of [{success:true},{disabledReason:'CHALLENGE_LIMIT'}]) {
    const h=host(); h.run(); h.ball.data.challenge=challenge; h.ballDef.observers.challenge.call(h.ball,challenge);
    assert.equal(h.ball.data.running,false); assert.equal(h.listeners.size,0); assert.equal(h.tasks.size,0); assert.equal(writes(h).length,0);
    if(challenge.success) assert.equal(h.stage.data.verdict,'win');
    h.ball.onStart(); assert.equal(h.events.filter(e=>e.name==='start').length,1);
  }
});
test('a frame arriving after an expired gap cannot revive the run before delayed watchdog fires', () => {
  for(const first of [true,false]) {
    const h=host(); h.run(); if(!first) h.sample({x:0,y:0}); h.jump(3500); h.sample({x:1,y:1});
    assert.equal(h.ball.data.running,false); assert.equal(h.listeners.size,0); assert.equal(writes(h).length,0);
  }
});
test('submitted UNKNOWN ignores a late stage failure without forwarding another verdict', () => {
  const h=host({props:{challenge:{},sharedChallenge:true}}); h.run(); h.sample({x:0,y:0}); h.ball.onSubmitHits();
  const before=writes(h).length; h.stage.fail(true); h.starts[0].fail();
  assert.equal(writes(h).length,before); assert.equal(h.ball.data.awaitingResult,true);
});
test('a close blocks a queued start tap until the original entry opens the component again', () => {
  const h=host(); h.run(); h.ball.onClose(); h.ball.onStart(); assert.equal(h.ball._attempt,null);
  h.resume('close'); h.run(); assert.equal(h.ball.data.running,true);
});
