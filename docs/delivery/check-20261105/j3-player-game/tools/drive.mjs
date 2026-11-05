// generic driver: node drive.mjs '<opts json>' '<steps json>'
// steps: "tap:text" "tapx:text"(exact) "tapn:text|n" "sel:css" "shot:name" "say:label" "wait:ms" "loc:lat,lng"
//        "eval:js" "data:expr" "type:text" "pay:success" "modal:confirm" "scroll:px" "heading:deg" "back" "open:url" "route"
import { run, S } from './lib.mjs';
const opts = JSON.parse(process.argv[2]); const steps = JSON.parse(process.argv[3]);
await run(Object.assign({ agree: true }, opts), async (w) => {
  await S(opts.boot || 5000);
  for (const st of steps) {
    const i = st.indexOf(':'); const k = i < 0 ? st : st.slice(0, i); const v = i < 0 ? '' : st.slice(i + 1);
    try {
      if (k === 'tap') await w.tap(v);
      else if (k === 'tapx') await w.tap(v, { exact: true });
      else if (k === 'tapn') { const [t, n] = v.split('|'); await w.tap(t, { nth: +n }); }
      else if (k === 'sel') { const h = await w.page.$('pierce/' + v); if (h) { await h.evaluate((el) => el.scrollIntoView({ block: 'center' })); await S(400); } await w.tapSel(v); }
      else if (k === 'shot') await w.shot(v);
      else if (k === 'say') await w.say(v);
      else if (k === 'wait') await S(+v);
      else if (k === 'loc') { const [a, b] = v.split(','); await w.loc(+a, +b); }
      else if (k === 'eval') console.log('EVAL', JSON.stringify(await w.eval(v)).slice(0, 2000));
      else if (k === 'data') console.log('DATA', JSON.stringify(await w.data(v)).slice(0, 2500));
      else if (k === 'type') {
        const hs = (await w.page.$$('pierce/input')).concat(await w.page.$$('pierce/textarea')); let h = null;
        for (const x of hs) { if (await x.boundingBox()) h = x; }
        if (!h) throw new Error('no input');
        await h.click({ clickCount: 3 }); await h.type(v, { delay: 20 }); await S(400);
      }
      else if (k === 'pay') await w.pay(v);
      else if (k === 'modal') await w.modal(v);
      else if (k === 'scroll') await w.scroll(+v);
      else if (k === 'heading') await w.heading(+v);
      else if (k === 'back') await w.back();
      else if (k === 'open') await w.open(v);
      else if (k === 'sortto') {
        const want = v.split(',');
        for (let pos = 0; pos < want.length; pos++) {
          const ys = {};
          for (const lab of want) { const pt = await w.page.evaluate((t) => { let r = null; (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); let own = ''; for (const n of el.childNodes) if (n.nodeType === 3) own += n.data; if (own.trim() === t) { const b = el.getBoundingClientRect(); if (b.height > 0) r = b.top; } } })(document); return r; }, lab); ys[lab] = pt; }
          const cur = want.slice().sort((a, b) => ys[a] - ys[b]);
          if (cur[pos] !== want[pos]) { await w.tap(cur[pos], { exact: true }); await S(500); await w.tap(want[pos], { exact: true }); await S(800); }
        }
      }
      else if (k === 'react') {
        const log = [];
        for (let i = 0; i < 80; i++) {
          const st = await w.page.evaluate(() => { let r = null; (function wk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) wk(el.shadowRoot); if (el.classList && el.classList.contains('rx') && el.getBoundingClientRect().height > 0) r = el; } })(document); if (!r) return null; const b = r.getBoundingClientRect(); const m = r.className.match(/rx--(\w+)/); return { ph: m ? m[1] : '?', x: b.left + b.width / 2, y: b.top + b.height / 2 }; });
          if (!st) break; log.push(st.ph);
          if (st.ph === 'done') break;
          if (st.ph === 'idle' || st.ph === 'early') { await w.page.mouse.click(st.x, st.y); await S(300); }
          else if (st.ph === 'go') { await S(260); await w.page.mouse.click(st.x, st.y); await S(300); }
          else await S(120);
        }
        console.log('REACT', log.join(',').slice(0, 400));
      }
      else if (k === 'hold') {
        const [t, ms] = v.split('|');
        const pt = await w.page.evaluate((tt) => { let r = null; (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); let own = ''; for (const n of el.childNodes) if (n.nodeType === 3) own += n.data; if (own.trim() === tt) { const b = el.getBoundingClientRect(); if (b.height > 0) r = { x: b.left + b.width / 2, y: b.top + b.height / 2 }; } } })(document); return r; }, t);
        if (!pt) throw new Error('hold: no ' + t);
        await w.page.mouse.move(pt.x, pt.y); await w.page.mouse.down(); await S(+ms || 3000); await w.page.mouse.up(); await S(800);
      }
      else if (k === 'drag') {
        const [x1, y1, x2, y2, n] = v.split(',').map(Number);
        for (let r = 0; r < (n || 1); r++) {
          const yy = y1 + (y2 - y1) * r / Math.max(1, (n || 1) - 1);
          await w.page.mouse.move(x1, yy); await w.page.mouse.down();
          for (let s = 1; s <= 12; s++) await w.page.mouse.move(x1 + (x2 - x1) * s / 12, yy, { steps: 2 });
          await w.page.mouse.up(); await S(80);
        }
      }
      else if (k === 'click') { const [x, y] = v.split(',').map(Number); await w.page.mouse.click(x, y); await S(900); }
      else if (k === 'log') { const l = w.log ? await w.log() : []; console.log('RLOG', JSON.stringify(l.slice(-(+v || 20))).slice(0, 3000)); }
      else if (k === 'pickview') { // rig: picker-view has no gesture; fire its own bindchange with the value a scroll would emit (as j2 did for picker wheels)
        const [cls, idx] = v.split('|');
        console.log('PICKVIEW', JSON.stringify(await w.eval(`(function(){var hit=null;(function walk(r){r.querySelectorAll('*').forEach(function(el){if(el.shadowRoot)walk(el.shadowRoot);if(!hit&&el.__tag==='picker-view'&&String(el.className||'').indexOf('${cls}')>=0)hit=el;});})(document);if(!hit)return 'no picker-view';MPWEB.fire(hit,'change',{value:[${+idx}]});return 'fired';})()`))); await S(800); }
      else if (k === 'route') console.log('ROUTENOW', JSON.stringify(await w.route()));
      else console.log('UNKNOWN', st);
      if (['tap', 'tapx', 'tapn', 'sel'].includes(k)) await S(1200);
    } catch (e) { console.log('MISS', st, e.message.slice(0, 120)); }
  }
});
