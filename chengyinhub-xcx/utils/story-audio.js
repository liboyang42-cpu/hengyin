/**
 * utils/story-audio.js —— 故事流的环境音与音效(2026-09-23 用户裁决「优化顺序第 2 项」)
 *
 * 照《预制人生故事流原型.html》的 AU / tone / burst / bed / sfx 移植:全部 WebAudio 合成,
 * 不用任何音频素材(包体零增量)。环境音跟作者已经在配的章节氛围 / 氛围块走,梦块播放时换成梦的和弦。
 *
 * 用法:create({ createContext }) → { bed(name), sfx(name), setOn(bool), dispose() }
 * createContext 注入(页面给 wx.createWebAudioContext),不支持时返回 null → 全部静默降级。
 */

// 章节氛围 → 环境音。名字沿用原型的场景名。
const MOOD_BED = { DEFAULT: 'city', BLUE: 'hush', RED: 'grown', YELLOW: 'afternoon', WHITE: 'office' };

function bedForMood(mood) {
  const key = String(mood || '').replace(/^atmosphere--/, '').toUpperCase();
  return MOOD_BED[key] || 'city';
}

function create(options) {
  const opts = options || {};
  const schedule = opts.schedule || ((fn, ms) => setTimeout(fn, ms));
  const state = { ctx: null, master: null, on: true, bed: null, bedName: '' };

  function ctx() {
    if (state.ctx === false) return null;
    if (!state.ctx) {
      let c = null;
      try { c = opts.createContext ? opts.createContext() : null; } catch (e) { c = null; }
      if (!c) { state.ctx = false; return null; }
      state.ctx = c;
      state.master = c.createGain();
      state.master.gain.value = 0.7;
      state.master.connect(c.destination);
    }
    return state.ctx;
  }

  function noise(c, secs) {
    const b = c.createBuffer(1, Math.floor(c.sampleRate * secs), c.sampleRate);
    const d = b.getChannelData(0);
    for (let i = 0; i < d.length; i += 1) d[i] = Math.random() * 2 - 1;
    return b;
  }

  function tone(freq, dur, type, vol, delay, to) {
    const c = state.on && ctx();
    if (!c) return;
    const t = c.currentTime + (delay || 0);
    const o = c.createOscillator();
    const g = c.createGain();
    o.type = type || 'sine';
    o.frequency.setValueAtTime(freq, t);
    if (to) o.frequency.exponentialRampToValueAtTime(to, t + dur);
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vol, t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g); g.connect(state.master);
    o.start(t); o.stop(t + dur + 0.05);
  }

  function burst(dur, hp, vol) {
    const c = state.on && ctx();
    if (!c) return;
    const s = c.createBufferSource();
    const f = c.createBiquadFilter();
    const g = c.createGain();
    s.buffer = noise(c, dur);
    f.type = 'highpass'; f.frequency.value = hp;
    g.gain.setValueAtTime(vol, c.currentTime);
    g.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + dur);
    s.connect(f); f.connect(g); g.connect(state.master);
    s.start();
  }

  const SFX = {
    tap: () => tone(560, 0.05, 'sine', 0.08),
    boot: () => { tone(180, 0.9, 'sine', 0.12, 0, 1400); burst(0.4, 800, 0.15); },
    ok: () => { tone(660, 0.18, 'sine', 0.1); tone(990, 0.3, 'sine', 0.08, 0.08); },
    no: () => { tone(300, 0.25, 'triangle', 0.1); tone(220, 0.35, 'triangle', 0.08, 0.1); },
    kaccha: () => { burst(0.05, 3000, 0.5); tone(80, 0.25, 'sine', 0.3); },
    shutter: () => { burst(0.08, 1800, 0.4); tone(1200, 0.05, 'square', 0.04, 0.05); },
    dream: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, 1.6, 'sine', 0.04, i * 0.18)),
    page: () => burst(0.25, 5000, 0.06),
  };

  function stopBed() {
    if (!state.bed) return;
    const old = state.bed;
    state.bed = null;
    old.g.gain.setTargetAtTime(0, state.ctx.currentTime, 0.6);
    schedule(() => old.nodes.forEach((n) => { try { n.stop(); } catch (e) { /* 已停 */ } }), 2500);
  }

  function bed(name) {
    if (!state.on) { state.bedName = name; return; }
    const c = ctx();
    if (!c || name === state.bedName) return;
    state.bedName = name;
    stopBed();
    const g = c.createGain();
    g.gain.value = 0;
    g.connect(state.master);
    const nodes = [];
    const loopNoise = (type, freq, q, vol) => {
      const s = c.createBufferSource();
      s.buffer = noise(c, 3); s.loop = true;
      const f = c.createBiquadFilter();
      f.type = type; f.frequency.value = freq; f.Q.value = q || 1;
      const gg = c.createGain(); gg.gain.value = vol;
      s.connect(f); f.connect(gg); gg.connect(g); s.start(); nodes.push(s);
      return gg;
    };
    const osc = (freq, vol, type) => {
      const o = c.createOscillator();
      o.type = type || 'sine'; o.frequency.value = freq;
      const gg = c.createGain(); gg.gain.value = vol;
      o.connect(gg); gg.connect(g); o.start(); nodes.push(o);
      return gg;
    };
    const lfo = (target, rate, depth) => {
      const o = c.createOscillator(); o.frequency.value = rate;
      const d = c.createGain(); d.gain.value = depth;
      o.connect(d); d.connect(target.gain); o.start(); nodes.push(o);
    };
    if (name === 'city') loopNoise('lowpass', 380, 0.7, 0.5);
    if (name === 'afternoon') { const ci = loopNoise('bandpass', 5200, 9, 0.35); lfo(ci, 18, 0.3); loopNoise('lowpass', 300, 0.5, 0.15); }
    if (name === 'dream') { const a = osc(220, 0.03); const b = osc(330.8, 0.025); osc(440.5, 0.012); lfo(a, 0.15, 0.02); lfo(b, 0.11, 0.015); }
    if (name === 'hush') loopNoise('lowpass', 500, 0.5, 0.06);
    if (name === 'grown') { const w = loopNoise('bandpass', 900, 1.5, 0.4); lfo(w, 0.2, 0.25); }
    if (name === 'office') { osc(60, 0.05); osc(120, 0.03); loopNoise('highpass', 6000, 0.5, 0.03); }
    g.gain.setTargetAtTime(name === 'void' ? 0 : 0.9, c.currentTime, 0.8);
    state.bed = { g, nodes };
  }

  return {
    bed,
    sfx(name) { if (SFX[name]) SFX[name](); },
    setOn(on) {
      state.on = !!on;
      if (!state.on) { stopBed(); return; }
      const want = state.bedName; state.bedName = '';
      if (want) bed(want);
    },
    dispose() {
      if (state.ctx) { stopBed(); try { state.ctx.close && state.ctx.close(); } catch (e) { /* 已关 */ } }
      state.ctx = null; state.bedName = '';
    },
  };
}

module.exports = { create, bedForMood };
