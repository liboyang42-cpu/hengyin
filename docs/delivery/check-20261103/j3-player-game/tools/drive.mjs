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
      else if (k === 'sel') await w.tapSel(v);
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
      else if (k === 'route')console.log('ROUTENOW', JSON.stringify(await w.route()));
      else console.log('UNKNOWN', st);
      if (['tap', 'tapx', 'tapn', 'sel'].includes(k)) await S(1200);
    } catch (e) { console.log('MISS', st, e.message.slice(0, 120)); }
  }
});
