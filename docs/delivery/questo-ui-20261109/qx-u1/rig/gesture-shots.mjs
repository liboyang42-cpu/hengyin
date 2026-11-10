// qx-u1 gesture frames on the walkthrough rig.
// node gesture-shots.mjs <label before|after> <outDir>
// Opens 编辑资料 → 个人介绍 sheet (a titled cy-sheet, player dark) and drives real pointer drags
// on the sheet header, shooting mid-gesture frames. Needs the rig WXS shim (rig-wxs-event-shim.diff).
const RIG = process.env.RIG_WALK || `${process.env.HOME}/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs`;
const { launch } = await import(RIG);
const [label = 'after', out = '.'] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const w = await launch({ path: 'pages/gerenziliao/gerenziliao', identity: 'e2e-openid-9004', agree: true, settle: 3000 });
const page = w.page;
const shot = async (name) => { await page.screenshot({ path: `${out}/${name}-${label}.jpg`, type: 'jpeg', quality: 70 }); console.log('shot', name); };

// deep query across shadow roots, returns viewport rect of the first match
const rect = (sel) => page.evaluate((sel) => {
  const walk = (root) => {
    const hit = root.querySelector(sel); if (hit) return hit;
    for (const el of root.querySelectorAll('*')) if (el.shadowRoot) { const r = walk(el.shadowRoot); if (r) return r; }
    return null;
  };
  const el = walk(document); if (!el) return null;
  const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height };
}, sel);

async function openSheet() {
  await page.evaluate(() => getCurrentPages().slice(-1)[0].setData({ popDavid: true }));
  await sleep(900);
}

async function drag(from, path, { hold = false, stepMs = 16 } = {}) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  let { x, y } = from;
  for (const [dx, dy] of path) { x += dx; y += dy; await page.mouse.move(x, y); await sleep(stepMs); }
  if (!hold) { await page.mouse.up(); }
}

await openSheet();
await shot('S1-sheet-open');
const head = await rect('.sh__head');
console.log('head', JSON.stringify(head));
if (head) {
  const at = { x: head.x + 60, y: head.y + head.h / 2 };
  // frame 2: finger 140px down, still holding (slow drag)
  await drag(at, Array(14).fill([0, 10]), { hold: true, stepMs: 40 });
  await sleep(120); await shot('S2-drag-down-140');
  // back up past the top detent: finger 140px above start, holding ⇒ rubber band (×0.25 ≈ 35px)
  for (let i = 0; i < 28; i++) { await page.mouse.move(at.x, at.y + 140 - i * 10); await sleep(40); }
  await sleep(120); await shot('S3-pull-up-rubber');
  await page.mouse.up(); await sleep(450); await shot('S4-spring-back');
  // slow drag past 30% then release ⇒ dismiss
  const panel = await rect('.sh__panel');
  const dist = Math.round((panel ? panel.h : 400) * 0.45);
  await drag(at, Array(Math.ceil(dist / 10)).fill([0, 10]), { hold: true, stepMs: 60 });
  await sleep(80); await shot('S5-drag-past-30pct');
  await page.mouse.up(); await sleep(140); await shot('S6-release-falling');
  await sleep(600); await shot('S7-dismissed');
}
console.log('route', JSON.stringify(await w.route()));
console.log('ERR', JSON.stringify(w.errors().slice(0, 5)));
await w.browser.close();
