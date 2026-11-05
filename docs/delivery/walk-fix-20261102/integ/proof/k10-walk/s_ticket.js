await h.tap("票务设置"); await sleep(1200);
await h.tap("选择",{exact:true}); await sleep(800); await h.tap("完成",{exact:true}); await sleep(700);
await h.tap("选择",{exact:true,nth:1}); await sleep(800); await h.tap("完成",{exact:true}); await sleep(700);
await h.tap("编辑",{exact:true}); await sleep(1000); await h.sc(900); await sleep(300);
await h.tap("选择节点"); await sleep(1200); await h.sc(700);
await h.tap("保存票种"); await sleep(1200);
const t = await h.say(); await w.page.mouse.click(39,774); await sleep(1200);
return t.slice(-400);
