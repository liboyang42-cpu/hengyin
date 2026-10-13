/**
 * 自由探索商家权益页(pages/play/merchant)的看图回归 driver。
 *
 * ⚠️ 与其它 setData 直驱 driver 同样的边界:这里手写 node,页面的 onLoad 取数逻辑
 * (从上一页 getCurrentPages()[-2].data.nodes 按 nodeId 找)**不执行**。
 * 所以本 driver 只回答「渲染出来长什么样」,证明不了取数正确 —— 别把它当契约测。
 * 校验只留渲染层能证的:帧不陈旧(md5 互异)、真渲染了(view 节点数)、无 console error。
 *
 * 用法:node scripts/_verify_play_merchant_shots.js
 */
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const OUT_DIR = path.join(os.homedir(), 'Desktop', '自由探索商家页截图');
process.env.UI_AUDIT_OUT = process.env.UI_AUDIT_OUT || OUT_DIR;
const { launchFixed } = require('./_infra');
const { resolveOpenState } = require('../pages/play/utils/play-open-state.js');

const NODE = {
  nodeId: 7001,
  name: '丸碧护肤中心(金辉广场店)',
  gameTitle: '城南夜巡 · 第3章',
  cardHook: '到店出示核销码,领取本章专属权益',
  address: '上海市黄浦区金辉广场 B1-07',
  // ★ 用真实字段:normNode() 产的是 businessTime/openStatus。
  //   之前这里手喂 openText,结果截图里营业行好端端显示着,把「真实数据下永远不显示」
  //   这个缺陷整个掩盖了 —— fixture 编了一个后端根本不下发的字段,就是在给自己看假象。
  businessTime: '10:00-22:00',
  openStatus: '营业中',
  lat: '31.2304', lng: '121.4737',
  imgUrl: '/pages/play/images/d_topic5.png',
};

const SHOTS = [
  {
    name: '01-商家权益页-完整态',
    data: {
      node: Object.assign({}, NODE, { openText: resolveOpenState(NODE).openText }), notFound: false, regId: 88,
      heroImg: NODE.imgUrl,
      coupon: { amount: '20', minPoint: '100' },
    },
  },
  {
    name: '02-商家权益页-无优惠券',
    data: { node: Object.assign({}, NODE, { openText: resolveOpenState(NODE).openText }), notFound: false, regId: 88, heroImg: NODE.imgUrl, coupon: null },
  },
  {
    // 深链/分享直接打开、上一页已销毁 ⇒ 取不到 node。必须明说拿不到,不许渲染空白或 undefined。
    // ⚠️ minNodes 单独给 3:空态本来就该只有三行(标题/副文案/返回钮),
    //    用统一阈值会把「正确的空态」误判成「没渲染出来」——2026-08-14 实测踩过一次。
    name: '03-商家权益页-取不到数据', minNodes: 3,
    data: { node: null, notFound: true, regId: 0, heroImg: '', coupon: null },
  },
];

(async () => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const mp = await launchFixed();
  const errors = [];
  mp.on('console', (m) => { if (m.type === 'error') errors.push(String(m.args && m.args[0]).slice(0, 160)); });

  const seen = new Map();
  let bad = 0;
  for (const shot of SHOTS) {
    const page = await mp.reLaunch('/pages/play/merchant/index?nodeId=7001');
    await page.waitFor(1500);
    // ★ 必须回读「现在到底停在哪一页」。2026-08-14 实测:复用已开实例时 reLaunch 会
    //   偶发没落到目标页(拍到了玩家首页),而 md5 互异 / 节点数 / 零 console error
    //   三条**全部放行** —— driver 证明不了自己拍对了页,就是假绿。
    const actual = await mp.currentPage();
    const actualPath = actual && actual.path;
    if (String(actualPath).indexOf('pages/play/merchant/index') < 0) {
      console.log(`FAIL ${shot.name}: 当前页是 ${actualPath},不是目标页,本张作废`);
      bad++;
      continue;
    }
    await page.setData(shot.data);
    await page.waitFor(700);

    const file = path.join(OUT_DIR, `${shot.name}.png`);
    await mp.screenshot({ path: file });
    const md5 = crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
    const nodes = (await page.$$('view')).length;

    if (seen.has(md5)) { console.log(`FAIL ${shot.name}: 与 ${seen.get(md5)} 同图(陈旧帧)`); bad++; }
    seen.set(md5, shot.name);
    const minNodes = shot.minNodes || 20;
    if (nodes < minNodes) { console.log(`FAIL ${shot.name}: view 节点仅 ${nodes} < ${minNodes},没渲染出来`); bad++; }
    console.log(`${shot.name} md5=${md5.slice(0, 8)} view=${nodes}`);
  }

  console.log('CONSOLE_ERRORS:', errors.length, errors.slice(0, 3).join(' | '));
  console.log(bad === 0 && errors.length === 0 ? 'SHOTS_OK(仅证明渲染)' : `SHOTS_BAD(${bad})`);
  console.log('截图目录:', OUT_DIR);
  await mp.disconnect();
  process.exit(bad === 0 && errors.length === 0 ? 0 : 1);
})().catch((e) => { console.error('DRIVER_ERROR', e && e.message); process.exit(1); });
