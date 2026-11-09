const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

// 发布收尾 Lane G2:4 个已合文案组源码在 master 上但没有锁定文案的专项测试(回归缺口)。
// 这里按当前文案原样锁住;改文案须同步改本测试,并确认语义(不承诺/不混用)仍成立。
const GROUPS = {
  'tutorial-version-copy': [
    ['subpackageA/components/scene-how-to-play-detail/index.wxml',
      '适用玩法版本暂未标注；教程用于说明，实际任务、完成条件与奖励以游玩页为准。'],
    ['subpackageA/pages/infomation/infomation.wxml',
      '实际玩法以游玩页规则为准'],
    ['subpackageA/pages/infomationdetail/infomationdetail.wxml',
      '适用玩法版本暂未标注；教程用于说明，实际任务、完成条件与奖励以游玩页为准。'],
  ],
  'group-code-copy': [
    ['pages/club/group-code/index.wxml', 'title="活动核验团码"'],
    // 第十轮 f3 第 5 项(j2 F-24):入队页这句说明按 owner「删说明小字」规则撤掉(旧锁定是被取代的旧决定);
    // 第十二轮 k3(p3 N11):团码页「团码仅限本场次；组队邀请码不能核销」同按此规则撤掉 —— 码只在选定场次后
    // 才出现,组队邀请码本身也不会被服务端当团码核销(ApiVerifyGroupCode),语义由页面结构与服务端承担。
  ],
  'topic-comment-scope': [
    // 第十轮 f3 第 4 项(j5 L9):评价空卡里那句与下方常驻积分口径行重复,按 owner「删说明小字」撤掉;
    // 合规口径仍由下面这行常驻并链到积分规则。
    ['pages/topic/index/index.wxml', '评论积分须符合参与及首评条件，以实际积分记录为准'],
  ],
};

for (const [group, entries] of Object.entries(GROUPS)) {
  test(`已合文案组 ${group}:当前文案原样在位`, () => {
    for (const [rel, copy] of entries) {
      assert.ok(read(rel).includes(copy), `${rel} 缺少 ${group} 文案:${copy}`);
    }
  });
}
