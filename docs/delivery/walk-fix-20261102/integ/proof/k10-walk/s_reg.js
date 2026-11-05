await sleep(2500);
await h.tap("查看详情与报名"); await sleep(2000);
await h.tap("立即报名"); await sleep(1800);
await h.tap("城市定向票"); await sleep(500); await h.tap("下一步 · 去结算"); await sleep(2500);
await h.tap("参与人信息"); await sleep(2000);
await h.setAt(0, "周夜"); await h.setAt(1, "13900000009"); await h.tap("保存参与人信息"); await sleep(2500);
await h.tap("同意将姓名、手机号提供给主办方，用于报名核验"); await sleep(500);
await h.tap("确认报名"); await sleep(3500);
return [await w.route(), (await h.say()).slice(-200)];
