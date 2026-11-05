// expects globalThis.K = { plusY, name, lat, lng, tpl }
const K = globalThis.K;
await w.page.mouse.click(195, K.plusY); await sleep(700); await h.tap("地点", { exact: true }); await sleep(1200);
await h.setAt((await h.inputs()).findIndex(x => x.ph === "给这个节点起个名字"), K.name);
await w.page.evaluate((a, b) => window.MPWEB.setLocation(a, b), K.lat, K.lng);
await h.tap("选择节点"); await sleep(1200);
await w.page.mouse.click(336, 400); await sleep(1800);
await h.sc(4000); await sleep(400);
const items = await w.page.evaluate(() => { const out = []; (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if (el.classList && el.classList.contains('temp-rail__li')) { const b = el.getBoundingClientRect(); out.push([el.textContent.trim(), b.x + b.width / 2, b.y + b.height / 2]); } } })(document); return out; });
const it = items.find(x => x[0] === K.tpl); if (!it) throw new Error('no tpl ' + K.tpl);
await w.page.mouse.click(it[1], it[2]); await sleep(700);
const sel = await w.data("selectedTempId");
await h.sc(-5000); await sleep(300); await w.page.mouse.click(340, 212); await sleep(1500);
await w.page.mouse.click(339, 212); await sleep(1500);
return [sel, (await h.say()).slice(-200)];
