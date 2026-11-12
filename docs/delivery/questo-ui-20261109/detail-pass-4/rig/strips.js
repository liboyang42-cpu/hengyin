/* 动效六帧条(0/80/160/240/360/480 ms):before = master 数值,after = 本分支真实模块给出的关键帧计划。
 * 云端容器没有 Docker 后端,走查 rig 起不来(AGENTS 也禁止本地 Docker),所以用 Chromium 的 Web Animations
 * 把产品代码的关键帧计划在同一时间点逐帧渲染;不是真机 animate,曲线语义按微信文档映射。
 * halo 一条是慢呼吸,时间轴 0/240/480/720/960/1200 ms(半个周期);其余 0/80/160/240/360/480 ms。
 * 用法: node strips.js <repo>/chengyinhub-xcx <out-dir> */
const path = require('path'), fs = require('fs');
const XCX = path.resolve(process.argv[2]), OUT = path.resolve(process.argv[3]);
const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const req = (p) => require(path.join(XCX, p));
const T0 = [0, 80, 160, 240, 360, 480], TH = [0, 240, 480, 720, 960, 1200];
const wx = (frames) => frames.map((f) => {
  const tr = [];
  if (f.translateX != null || f.translateY != null) tr.push(`translate(${f.translateX || 0}px,${(f.translateY || 0)}px)`);
  if (f.scale) tr.push(`scale(${f.scale[0]},${f.scale[1]})`);
  if (f.skew) tr.push(`skewX(${f.skew[0]}deg)`);
  const o = { offset: f.offset }; if (tr.length) o.transform = tr.join(' ');
  if (f.opacity != null) o.opacity = f.opacity; if (f.ease) o.easing = f.ease; return o;
});
const EO = 'cubic-bezier(.22,.61,.36,1)', REL = 'cubic-bezier(.2,1.4,.5,1)';
const blk = (w, c) => `<div class="blk b" style="width:${w}px${c ? ';background:' + c : ''}"></div>`;
// 每项:html + anims(side,t) → [{sel, all?, kf, opt}] + text(side,t)
const items = {
  press: { html: '<div class="card el">按钮</div>', anims: (s) => {
    const a = s === 'after' ? { d: 80, u: 220, sc: .97, o: .92, rel: REL } : { d: 80, u: 80, sc: .98, o: .88, rel: EO }; const H = 200, D = H + a.u;
    return [{ sel: '.el', kf: [{ transform: 'scale(1)', opacity: 1, offset: 0, easing: EO }, { transform: `scale(${a.sc})`, opacity: a.o, offset: a.d / D }, { transform: `scale(${a.sc})`, opacity: a.o, offset: H / D, easing: a.rel }, { transform: 'scale(1)', opacity: 1, offset: 1 }], opt: { duration: D } }]; } },
  entry: { html: `<div style="position:absolute;inset:0;padding-top:18px">${blk(90)}${blk(110)}${blk(70)}${blk(100, '#4f8cff')}</div>`, anims: (s) => {
    if (s === 'before') return []; const got = []; const st = global.setTimeout; global.setTimeout = (fn, d) => { got.push(d); fn(); };
    const frames = []; req('utils/entry-rhythm.js').play({ data: {}, animate: (sel, f, dur) => frames.push([f, dur]) }, ['.b', '.b', '.b', '.b']); global.setTimeout = st;
    return frames.map(([f, dur], i) => ({ sel: '.b', nth: i, kf: wx(f), opt: { duration: dur, delay: got[i] } })); } },
  count: { html: '<div class="card"><span class="el" style="font-size:22px;font-variant-numeric:tabular-nums">120 米</span></div>', anims: () => [], text: (s, t) => s === 'after' ? { '.el': req('pages/play/utils/count-roll.js') && req('utils/count-up.js').frame('120 米', Math.max(0, t - 260 + 260)) } : {} },
  sheen: { html: '<div class="card" style="overflow:hidden;background:#2b2740">卡包<div class="el" style="position:absolute;left:0;top:0;width:36px;height:60px;background:linear-gradient(90deg,transparent,rgba(255,255,255,.35),transparent);opacity:0"></div></div>', anims: (s) => s === 'after' ? [{ sel: '.el', kf: wx(req('pages/play/utils/pack-detail.js').sheenFrames()), opt: { duration: 3000 } }] : [] },
  halo: { ts: TH, html: '<div class="card" style="background:transparent"><div class="el" style="position:absolute;width:110px;height:110px;border-radius:50%;background:radial-gradient(closest-side,rgba(233,196,124,.22),rgba(233,196,124,0) 68%);opacity:.5;left:-5px;top:-25px"></div><span style="position:relative;font-size:26px">01</span></div>', anims: (s) => s === 'after' ? [{ sel: '.el', kf: wx(req('pages/play/components/chapter-flip/ambient.js').haloFrames(true)), opt: { duration: 1500 } }] : [{ sel: '.el', kf: [{ opacity: .5, offset: 0 }, { opacity: .5, offset: 1 }], opt: { duration: 1500 } }] },
  sheet: { html: '', htmlFn: (s) => `<div class="el" style="position:absolute;left:12px;right:12px;bottom:0;height:80px;border-radius:16px 16px 0 0;background:#1d1f27;box-shadow:inset 0 1px 0 rgba(255,255,255,${s === 'after' ? '.18' : '.18'}),0 -12px 40px rgba(0,0,0,.55)"></div><div class="card" style="top:14px;box-shadow:${s === 'after' ? 'inset 0 1px 0 rgba(255,255,255,.10),0 1px 2px rgba(0,0,0,.18)' : 'none'}">卡面</div>`, anims: () => [{ sel: '.el', kf: [{ transform: 'translateY(80px)', offset: 0 }, { transform: 'translateY(0)', offset: 1 }], opt: { duration: 420, easing: 'cubic-bezier(.32,.72,0,1)' } }] },
  menu: { html: `<div class="el" style="position:absolute;right:8px;top:8px;width:110px;height:92px;border-radius:12px;background:#262833;transform-origin:100% 0;padding:8px;box-sizing:border-box">${blk(70)}${blk(90)}${blk(60)}</div>`, anims: (s) => s === 'after' ? [{ sel: '.el', kf: wx(req('pages/play/utils/menu-open.js').frames()), opt: { duration: 420 } }] : [] },
};
const CSS = `body{margin:0;background:#0b0c10;font:14px sans-serif;color:#e8e8ec}.row{display:flex;gap:6px;padding:8px}.tile{width:150px;height:130px;position:relative;overflow:hidden;background:#14151b;border-radius:10px}.t{position:absolute;left:4px;top:3px;font-size:10px;color:#889;z-index:5}.card{position:absolute;left:25px;top:34px;width:100px;height:60px;border-radius:12px;background:#1d1f27;display:flex;align-items:center;justify-content:center}.blk{height:14px;margin:5px 14px;border-radius:4px;background:#2a2d38}.tag{position:absolute;right:6px;bottom:3px;font-size:10px;color:#889;z-index:5}`;
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch(); const pg = await b.newPage({ viewport: { width: 960, height: 300 } });
  for (const [name, it] of Object.entries(items)) {
    const T = it.ts || T0;
    const rows = ['before', 'after'].map((side) => {
      const body = it.htmlFn ? it.htmlFn(side) : it.html;
      return `<div class="row">${T.map((t) => `<div class="tile"><div class="t">${t}ms</div><div class="stage" data-s="${side}" data-t="${t}" style="position:absolute;inset:0">${body}</div><div class="tag">${side}</div></div>`).join('')}</div>`;
    }).join('');
    await pg.setContent(`<style>${CSS}</style>${rows}`);
    const spec = [];
    ['before', 'after'].forEach((side) => T.forEach((t) => spec.push({ side, t, anims: it.anims(side, t), text: it.text ? it.text(side, t) : {} })));
    await pg.evaluate((spec) => spec.forEach(({ side, t, anims, text }) => {
      const st = document.querySelector(`.stage[data-s="${side}"][data-t="${t}"]`);
      anims.forEach((a) => { const els = [...st.querySelectorAll(a.sel)]; const el = a.nth != null ? els[a.nth] : els[0]; const an = el.animate(a.kf, Object.assign({ fill: 'both', easing: 'linear' }, a.opt)); an.pause(); an.currentTime = t; });
      Object.keys(text).forEach((k) => { st.querySelector(k).textContent = text[k]; });
    }), spec);
    await pg.screenshot({ path: path.join(OUT, `${name}.png`) });
  }
  await b.close();
})();
