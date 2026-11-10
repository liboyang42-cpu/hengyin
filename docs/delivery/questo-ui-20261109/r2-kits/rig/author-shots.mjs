// r2-kits 作者端截图驱动:真实 pages/publish/temp/index(编辑玩法模板),创作者 e2e-openid-9002,真实后端。
// 每个状态重开一次页面,步骤一致,改前/改后两个包各跑一次。
// 用法: RIG_SHOTS=<dir> SUFFIX=before|after node author-shots.mjs [state...]
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';

const suffix = process.env.SUFFIX || 'after';
const only = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/* 把含某段文字的元素滚到屏幕上方(穿透组件 shadow root) */
const scrollTo = (text, offset = 120) => ({ eval: `(() => {
  const hits = []; const walk = (root) => { const tw = document.createTreeWalker(root, NodeFilter.SHOW_TEXT); let n; while ((n = tw.nextNode())) if (n.nodeValue.trim() === ${JSON.stringify(text)}) hits.push(n.parentElement);
    for (const el of root.querySelectorAll('*')) if (el.shadowRoot) walk(el.shadowRoot); };
  walk(document); const el = hits.find((h) => h.getBoundingClientRect().height > 0); if (!el) return 'missing';
  let sv = el.parentElement; const up = (x) => x.parentElement || (x.getRootNode() && x.getRootNode().host) || null;
  while (sv && !(sv.scrollHeight > sv.clientHeight + 4 && /auto|scroll/.test(getComputedStyle(sv).overflowY))) sv = up(sv);
  if (!sv) { el.scrollIntoView(); return 'win'; }
  sv.scrollTop += el.getBoundingClientRect().top - sv.getBoundingClientRect().top - ${offset}; return 'ok'; })()` });
const pick = (label) => [{ wait: 1200 }, { tap: label }, { tap: '完成', wait: 1500 }];
const page = (js) => ({ eval: `(() => { const p = getCurrentPages().slice(-1)[0]; ${js}; return 'ok'; })()`, wait: 1000 });
/* 三个选项的文案与配图直接写进页面数据(rig 上传走内容安全桩会报「安全检查服务异常」,与本项无关) */
const OPTS = `const o = (p.data.advanced.qa.options || []).map((x, i) => Object.assign({}, x, { label: ['古董店老板', '守夜人', '报童'][i] || x.label }));
  while (o.length < 3) o.push({ id: 'o' + o.length, label: ['古董店老板', '守夜人', '报童'][o.length], fb: '' });
  p.setData({ 'advanced.qa.options': o, 'advanced.qa.title': '谁在钟楼留下了钥匙？' })`;
const IMGS = `p.setData({ 'advanced.qa.options[0].img': '/subpackageP3/images/memoji-1.png', 'advanced.qa.options[1].img': '/subpackageP3/images/memoji-2.png' }); p.qzOptionAlt && p.qzOptionAlt({ currentTarget: { dataset: { index: 0 } }, detail: { value: '' } })`;
const STATES = [
  { id: 'b2-x-cat', steps: [] },
  /* 改后:逐字猜词不再是单独标签,走「文字问答 → 表单里打开逐字猜词」 */
  { id: 'b2-x-cat-form', after: true, steps: [...pick('文字问答'), scrollTo('题目', 40)] },
  { id: 'b2-ug-01-author', afterSteps: [...pick('文字问答'), page(`p.qzHangmanForm({ detail: { value: true } })`), { type: ['input[placeholder="例如 ENDLESS"]', 'NICOLAS AND PERNELLE'] }, scrollTo('格子', 260)], steps: [...pick('逐字猜词'), { type: ['input[placeholder="例如 ENDLESS"]', 'NICOLAS AND PERNELLE'] }, scrollTo('格子', 260)] },
  { id: 'b2-ug-01-author-short', afterSteps: [...pick('文字问答'), page(`p.qzHangmanForm({ detail: { value: true } })`), { type: ['input[placeholder="例如 ENDLESS"]', 'ENDLESS'] }, scrollTo('逐字猜词', 120)], steps: [...pick('逐字猜词'), { type: ['input[placeholder="例如 ENDLESS"]', 'ENDLESS'] }, scrollTo('格子', 260)] },
  { id: 'b2-ug-02-author', steps: [...pick('选项问答'), page(OPTS), page(IMGS), scrollTo('选项 1', 140)] },
  { id: 'b2-ug-03-author', steps: [...pick('选项问答'), page(OPTS), page(`p.qzAccusationToggle({ detail: { value: true } })`), scrollTo('指认结案', 40)] },
  { id: 'b2-ug-03-author-2', steps: [...pick('选项问答'), page(OPTS), page(`p.qzAccusationToggle({ detail: { value: true } })`), page(`p.qzTestimonyAdd && p.qzTestimonyAdd({ currentTarget: { dataset: { index: 0 } } })`), scrollTo('古董店老板', 60)] },
  { id: 'b2-ug-10-author', steps: [...pick('选项问答'), page(OPTS), scrollTo('题目', 40)] },
  { id: 'b2-ug-10-author-text', steps: [...pick('文字问答'), scrollTo('题目', 40)] },
];
for (const s of STATES) {
  if (only.length && !only.includes(s.id)) continue;
  if (s.after && suffix !== 'after') continue;
  const steps = suffix === 'after' && s.afterSteps ? s.afterSteps : s.steps;
  const w = await launch({ path: 'pages/publish/temp/index', identity: 'e2e-openid-9002', agree: true, settle: 4000 });
  try {
    for (const step of steps) {
      if (step.tap) await w.tap(step.tap);
      if (step.tapSel) await w.tapSel(step.tapSel);
      if (step.type) await w.type(step.type[0], step.type[1]);
      if (step.eval) console.log(s.id, 'eval', await w.eval(step.eval));
      await sleep(step.wait || 800);
    }
    console.log('shot', s.id, await w.shot(`${s.id}-${suffix}`));
  } catch (e) { console.log('FAIL', s.id, String(e).slice(0, 300)); }
  const errs = w.errors(); if (errs.length) console.log('PAGE ERRORS', s.id, errs.slice(0, 3));
  await w.browser.close();
}
