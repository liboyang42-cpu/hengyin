const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 前端按后端原话分类的几组文案：改措辞或换术语都会让真实回包落进「未知」。这里锁成与后端源码逐字一致。
const ROOT = path.join(__dirname, '..', '..');
const REPO = path.join(ROOT, '..');
const javaText = (() => {
  const out = [];
  const walk = (dir) => {
    for (const name of fs.readdirSync(dir)) {
      const p = path.join(dir, name);
      const st = fs.statSync(p);
      if (st.isDirectory()) walk(p);
      else if (name.endsWith('.java')) out.push(fs.readFileSync(p, 'utf8'));
    }
  };
  for (const mod of ['chengyinhub-system', 'chengyinhub-admin', 'chengyinhub-framework', 'chengyinhub-common']) {
    const dir = path.join(REPO, mod, 'src', 'main', 'java');
    if (fs.existsSync(dir)) walk(dir);
  }
  return out.join('\n');
})();

function literalsOf(rel, anchor) {
  const src = fs.readFileSync(path.join(ROOT, rel), 'utf8');
  const line = src.split('\n').find((l) => l.includes(anchor));
  assert.ok(line, `${rel} 缺少 ${anchor}`);
  return [...line.matchAll(/'([^']+)'/g)].map((m) => m[1]);
}

test('漫游据点预写拒绝原话与后端逐字一致', () => {
  for (const msg of literalsOf('subpackageMember/components/cy/scene-roam-poi-detail/index.js', 'PREWRITE_REJECTIONS = new Set')) {
    assert.ok(javaText.includes('"' + msg + '"'), `后端找不到原话：${msg}`);
  }
});

test('据点核销未执行原话与后端逐字一致', () => {
  for (const msg of literalsOf('utils/citynode-redeem-outcome.js', 'const NOT_REDEEMED')) {
    assert.ok(javaText.includes('"' + msg + '"'), `后端找不到原话：${msg}`);
  }
});
