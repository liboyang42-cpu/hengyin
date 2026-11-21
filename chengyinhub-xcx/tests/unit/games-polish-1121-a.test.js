// 2026-11-21 业主走查(编辑页「试玩」逐个玩法):各玩法组件自身的回归钉子。
// 内嵌(故事流 .chfull__kit / 编辑页 .cg-pv-inline-kit)时 --pk-col-pos:relative、--pk-card-h(-tall):auto、
// --pk-embed-bg 没有章节配色时是 transparent —— 下面每一条都是这个前提下真实出过的坏屏。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const kit = (name, ext) => read(`pages/playkit/components/playkit-${name}/index.${ext}`);
const rule = (css, sel) => {
  const m = css.match(new RegExp(sel.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&') + ' \\{([^}]*)\\}'));
  assert.ok(m, sel + ' 规则不存在');
  return m[1];
};
const minHeightRpx = (body) => {
  const m = body.match(/min-height:\s*(\d+)rpx/);
  return m ? Number(m[1]) : 0;
};
// 主按钮 = cy-play-stage 的直接子节点、带 .g-btn、标签非空
const mainButtons = (wxml) => [...wxml.matchAll(/<view\b[^>]*class="g-btn\b[^"]*"[^>]*>([\s\S]*?)<\/view>/g)];

test('倒计时:内嵌时油面那块有固定最小高(子元素全绝对定位,不撑高就是一片空白),主按钮是统一 .g-btn', () => {
  const css = kit('countdown', 'wxss');
  assert.ok(minHeightRpx(rule(css, '.oc')) >= 600, '.oc 内嵌时会塌成 0 高');
  assert.doesNotMatch(rule(css, '.oc'), /min-height:\s*var\(--pk-card-h/);
  assert.doesNotMatch(css, /\.oc__b \{/, '旧的白底描边胶囊应已撤掉');
  const wxml = kit('countdown', 'wxml');
  const btns = mainButtons(wxml);
  assert.equal(btns.length, 1);
  assert.match(btns[0][1], /开始/);
  assert.match(btns[0][0], /bindtap="onStart"/);
  // 按钮在 .oc 之外(stage 直接子节点):内嵌时跟在油面后面,不压进绝对定位的油里
  assert.ok(wxml.indexOf('class="g-btn') > wxml.lastIndexOf('</view>\n\n  <!--'), '按钮应排在 .oc 之后');
});

test('翻卡:内嵌时牌堆台面不塌(以前只剩「换一张 / 就这张」)', () => {
  const css = kit('random', 'wxss');
  const dk = rule(css, '.dk');
  assert.ok(minHeightRpx(dk) >= 900, '.dk 的牌堆是绝对定位,必须有最小高');
  assert.doesNotMatch(dk, /min-height:\s*var\(--pk-card-h-tall/);
  // 手势按钮字色读墨色,不读可能透明的底色
  assert.doesNotMatch(rule(css, '.dk__actbtn'), /color:\s*var\(--(pk-embed-bg|bg)\b/);
});

test('分支剧情:选项字不再用 var(--bg) 压在墨色块上(预览里底色透明 → 一排空白框)', () => {
  const css = kit('branch', 'wxss');
  for (const sel of ['.br__opts', '.br__opt', '.br__opt.is-on']) {
    assert.doesNotMatch(rule(css, sel), /color:\s*var\(--(bg|pk-embed-bg)\b/, sel);
  }
  assert.doesNotMatch(rule(css, '.br__opts'), /background:\s*var\(--ink\)/);
  assert.match(rule(css, '.br__opt'), /color:\s*var\(--sub\)/);
  assert.match(rule(css, '.br__opt.is-on'), /color:\s*var\(--ink\)/);
  assert.match(kit('branch', 'wxml'), /<view class="br__t">\{\{item\.t\}\}<\/view>/);
});

test('弹球 / 掷骰子 / 安静 / 喊一嗓子 / 罗盘:主按钮都是统一 .g-btn,标签非空且字色不读透明底', () => {
  const cases = {
    ballshake: /开始/, quiethold: /开始/, shout: /开始/, compass: /开始/,
  };
  for (const [name, label] of Object.entries(cases)) {
    const btns = mainButtons(kit(name, 'wxml'));
    assert.ok(btns.length >= 1, name + ' 没有 .g-btn 主按钮');
    assert.ok(btns.some(b => label.test(b[1])), name + ' 主按钮没有「开始」');
    for (const b of btns) assert.ok(b[1].replace(/\s+/g, '').length > 0, name + ' 有空标签按钮');
  }
  const dice = kit('diceroll', 'wxml');
  const diceBtns = mainButtons(dice);
  assert.ok(diceBtns.some(b => /bindtap="onRoll"/.test(b[0]) && /掷骰子/.test(b[1])), '内嵌骰子主按钮应写「掷骰子」');
  assert.ok(diceBtns.some(b => /掷 D20/.test(b[1])), 'D20 主按钮应写「掷 D20」');
  // 旧的各自按钮样式已撤
  for (const [name, sel] of [['ballshake', '.bl__b'], ['quiethold', '.qt__b'], ['shout', '.sh__b'], ['compass', '.cp__b'], ['diceroll', '.dzi__roll']]) {
    assert.ok(!kit(name, 'wxss').includes(sel + ' {'), name + ' 仍留着自己的主按钮样式 ' + sel);
  }
});

test('系统 / 开发说明行已撤(业主:没有说明文字,版面自己说明)', () => {
  const gone = {
    reaction: [/本机记录屏幕变绿到点击的时间差/],
    quiethold: [/只看手机听到的相对音量/, /不是分贝读数/, /thresholdText/],
    ballshake: [/撞击根据本机动作测量/, /不限时，撞满为止/],
    shout: [/使用舒适音量/, /相对音量/, /非分贝/],
    diceroll: [/沙盒预览/, /加值为作者在模板中设定的固定值/, /不计入正式判定/],
  };
  for (const [name, list] of Object.entries(gone)) {
    const wxml = kit(name, 'wxml').replace(/<!--[\s\S]*?-->/g, '');
    for (const re of list) assert.doesNotMatch(wxml, re, name + ' 仍显示 ' + re);
  }
  // 喊一嗓子保留且只保留一句短的安全话
  assert.equal((kit('shout', 'wxml').match(/别对着人喊/g) || []).length, 1);
  // 试玩 D20 只留一句玩家话
  assert.match(kit('diceroll', 'wxml'), /试玩，不计成绩/);
  // 判定屏 / 成绩单位里也不出「本机测量 / 本机相对」
  for (const name of ['reaction', 'shout', 'quiethold']) {
    assert.doesNotMatch(kit(name, 'js'), /'[^'\n]*本机(测量|相对)[^'\n]*'/, name);
  }
});

test('入场不拖慢首屏:这些玩法里没有 >0.1s 的 --pe-d,也没有 >0.2s 的 --pe-dur', () => {
  for (const name of ['countdown', 'random', 'branch', 'ballshake', 'diceroll', 'quiethold', 'shout', 'reaction', 'compass']) {
    const wxml = kit(name, 'wxml');
    for (const [, v] of wxml.matchAll(/--pe-d:\s*([\d.]+)s/g)) assert.ok(Number(v) <= 0.1, `${name} --pe-d:${v}s`);
    for (const [, v] of wxml.matchAll(/--pe-dur:\s*([\d.]+)s/g)) assert.ok(Number(v) <= 0.2, `${name} --pe-dur:${v}s`);
    // 每个挂了 .pe 的零件都显式压了时长(play-surface 缺省 .5s)
    for (const m of wxml.matchAll(/<view\b[^>]*class="[^"]*\bpe\b[^"]*"[^>]*>/g)) {
      assert.match(m[0], /--pe-dur:\s*\.[12]s/, name + ': ' + m[0].slice(0, 80));
    }
  }
});
