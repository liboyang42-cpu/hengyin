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
      '教程适用版本暂未标注；实际玩法以进入后的规则说明为准。'],
    ['subpackageA/pages/infomationdetail/infomationdetail.wxml',
      '适用玩法版本暂未标注；教程用于说明，实际任务、完成条件与奖励以游玩页为准。'],
  ],
  'group-code-copy': [
    ['pages/club/group-code/index.wxml',
      '活动核验团码供商家登记本场次接待，不能跨场次使用；玩家组队邀请码不能用于核销。'],
    ['pages/club/group-code/index.wxml', 'title="活动核验团码"'],
    ['pages/team/join/index.wxml',
      '你收到一份玩家组队邀请，邀请码不能用于活动签到或商家核销。组队与否不影响活动举行，入队不会代替报名或购票。'],
  ],
  'topic-comment-scope': [
    ['pages/topic/index/index.wxml',
      '未参与也可以评价。已支付或已核销报名后的首条评价可按当前规则获得积分，以实际积分记录为准。'],
    ['pages/topic/index/index.wxml', '评论积分须符合参与及首评条件，以实际积分记录为准'],
  ],
  'profile-signature-copy': [
    ['pages/gerenziliao/gerenziliao.wxml', '修改签名文字不会改变定位城市。'],
  ],
};

for (const [group, entries] of Object.entries(GROUPS)) {
  test(`已合文案组 ${group}:当前文案原样在位`, () => {
    for (const [rel, copy] of entries) {
      assert.ok(read(rel).includes(copy), `${rel} 缺少 ${group} 文案:${copy}`);
    }
  });
}
