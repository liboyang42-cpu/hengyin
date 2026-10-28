/**
 * D-11 · 门店 NPC「人设恒空,玩家对话必失败」修复契约。
 *
 * 修复前:ApiMerchantController.npcSave 与 CmsMerchantNpcController.add 都把 persona 写空串,
 * 商家页面也没有人设输入项。玩家点「和 TA 聊聊」时 NpcChatService 对空 persona 直接
 * 返回 NPC_PROFILE_UNAVAILABLE(「这家店的分身还没上线」),商家却以为配好了。
 * 修复后:商家在形象页可写「性格设定」(≤500 字,送内容安全),persona 落库并被对话链消费。
 *
 * 本契约钉三件事:
 *   ① 渲染层:形象页有性格设定入口(多行),并写明不填的后果;
 *   ② 行为层:保存/单独编辑都把 persona 发给 /api/merchant/npc/save,读回时回填;
 *   ③ 后端对齐:控制器校验 + 送检 + 落库,mapper update 真的带 persona。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

const JS_PATH = 'pages/merchant/decor/ai-npc/index.js';
const WXML_PATH = 'pages/merchant/decor/ai-npc/index.wxml';

function loadPage() {
  let definition;
  const requests = [];
  const app = {
    globalData: {},
    getUserID: () => '7',
    sendRequest(o) { requests.push(o); },
    chooseImage() {},
  };
  const noop = () => {};
  const file = path.join(ROOT, JS_PATH);
  vm.runInNewContext(read(JS_PATH), {
    Page: (p) => { definition = p; },
    getApp: () => app,
    wx: {},
    console,
    setTimeout: noop,
    clearTimeout: noop,
    require(id) {
      if (id.includes('/toast')) return Object.assign(noop, { success: noop });
      if (id.includes('/merchant-theme')) return { merchantPageShow: noop, merchantPageRestore: noop };
      return require(path.resolve(path.dirname(file), id));
    },
  });
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) {
      Object.keys(patch).forEach((key) => {
        if (!key.includes('.')) { this.data[key] = patch[key]; return; }
        const parts = key.split('.');
        let target = this.data;
        for (let i = 0; i < parts.length - 1; i += 1) target = target[parts[i]];
        target[parts[parts.length - 1]] = patch[key];
      });
    },
  });
  return { page, requests };
}

function savedPage(over) {
  const env = loadPage();
  env.page.data.hasProfile = true;
  env.page.data.savedAvatar = 'px1:p01';
  env.page.data.name = '小店长';
  env.page.data.greeting = '欢迎光临';
  Object.assign(env.page.data, over || {});
  env.page._saved = {
    avatar: 'px1:p01', name: env.page.data.name, greeting: env.page.data.greeting,
    persona: env.page.data.persona, knowledge: env.page.data.knowledge,
  };
  return env;
}

// 2026-10-27 业主 M-28(§6.3 已确认)推翻「性格设定走多行自由输入」:改成 温柔 / 可爱 / 毒舌 三选一 chip,
// persona 落中文标签、personality 落枚举(gentle/cute/savage,后端 L01)。下面两条随之改判。
test('渲染层:形象页保留性格设定与审核状态,性格是三选一 chip', () => {
  const wxml = read(WXML_PATH);
  const cell = wxml.match(/<cy-cell[^>]*data-key="persona"[^>]*\/>/);
  assert.ok(cell, '找不到性格设定入口');
  assert.match(cell[0], /title="性格设定"/);
  assert.match(cell[0], /value="\{\{persona \|\| '选择性格'\}\}"/);
  assert.doesNotMatch(cell[0], /description=/);
  // 2026-10-28 m698 业主:按内容高度的 Threads 式菜单,点一下就选上并保存,无标题、无保存键
  assert.match(wxml, /<cy-sheet inset show="\{\{personaSheet\}\}"/);
  assert.match(wxml, /<view class="tm" aria-role="radiogroup" aria-label="性格设定">/);
  assert.match(wxml, /class="tm-row[^"]*"[\s\S]*?wx:for="\{\{personalities\}\}"[\s\S]*?bindtap="pickPersona"[\s\S]*?aria-role="radio"/);
  assert.doesNotMatch(wxml, /bindtap="savePersona"/);
  assert.doesNotMatch(read(JS_PATH), /multiline: key === 'persona'/, '性格不再是文本框');
  assert.match(wxml, /auditStatus === 2 && auditReason/, '审核驳回后必须把原因显示给商家');
});

test('行为层:单独选性格保存 persona 标签 + personality 枚举,不动名字和招呼语', async () => {
  const env = savedPage({ persona: '' });
  env.page.editField({ currentTarget: { dataset: { key: 'persona' } } });
  assert.equal(env.page.data.field, null, '性格不再开文本框');
  assert.equal(env.page.data.personaSheet, true);
  assert.deepEqual(env.page.data.personalities.map((p) => p.label), ['温柔', '可爱', '毒舌']);
  assert.deepEqual(env.page.data.personalities.map((p) => p.key), ['gentle', 'cute', 'savage']);

  env.page.pickPersona({ currentTarget: { dataset: { key: 'bogus' } } });
  assert.equal(env.page.data.personaPick, '', '枚举外的值不收');
  env.page.pickPersona({ currentTarget: { dataset: { key: 'savage' } } });
  env.page.savePersona();

  const write = env.requests.find((r) => r.url === '/api/merchant/npc/save');
  assert.ok(write, '保存必须发 /api/merchant/npc/save');
  const payload = JSON.parse(write.data);
  assert.equal(payload.persona, '毒舌', 'persona 落中文标签(旧后端照样生效)');
  assert.equal(payload.personality, 'savage', 'personality 落枚举(L01 后端)');
  assert.equal(payload.name, '小店长', '改人设不得把名字带丢');
  assert.equal(payload.greeting, '欢迎光临', '改人设不得把招呼语带丢');
  assert.equal(payload.avatar, 'px1:p01', '改人设不得把形象带丢');

  write.success({ code: 200 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(env.page.data.persona, '毒舌', '保存成功必须回填本地态');
  assert.equal(env.page.data.personality, 'savage');
  assert.equal(env.page.data.personaSheet, false, '保存成功关弹层');
});

test('行为层:整页保存与脏态判定都包含 persona', async () => {
  const env = savedPage({ persona: '旧人设' });
  env.page.data.persona = '新人设';
  let left = false;
  env.page.leavePage = () => { left = true; };
  env.page.onNavBack();
  assert.equal(left, false, '人设改过但在保存前退出，必须先弹放弃确认');
  assert.equal(env.page.data.discardVisible, true);

  const env2 = savedPage({ persona: '旧人设' });
  env2.page.data.persona = '让店主说话像老朋友';
  env2.page.save();
  await new Promise((resolve) => setImmediate(resolve));
  const write = env2.requests.find((r) => r.url === '/api/merchant/npc/save');
  assert.ok(write, '整页保存必须发 /api/merchant/npc/save');
  assert.equal(JSON.parse(write.data).persona, '让店主说话像老朋友', '整页保存必须带上 persona');

  // 负控:把 persona 从整页保存 payload 里拿掉,同一断言必须红
  const mutated = read(JS_PATH).replace('persona: this.data.persona.trim(), ', '');
  assert.notEqual(mutated, read(JS_PATH), '负控锚点失效');
  assert.doesNotMatch(mutated, /persona: this\.data\.persona\.trim\(\)/, '拿掉后必须命中不到 persona');
});

test('后端对齐:商家可写 persona、更新链真的带 persona、空人设仍会被对话链拒绝', () => {
  const controller = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantController.java');
  assert.match(controller, /NPC_PERSONA_MAX = 500/, '后端必须有长度上限');
  assert.match(controller, /joinText\(name, greeting, persona, knowledge\)/, 'persona 必须一起送内容安全');
  assert.match(controller, /save\.setPersona\(persona\)/, '保存必须把商家写的 persona 落下去');
  assert.match(controller, /save\.getPersona\(\) == null\) save\.setPersona\(""\)/, '新建且没填时必须落空串(NOT NULL)');

  const mapper = read('../chengyinhub-system/src/main/resources/mapper/business/NpcProfileMapper.xml');
  const update = mapper.slice(mapper.indexOf('id="updateMerchantNpc"'), mapper.indexOf('id="updateMerchantNpcVoice"'));
  assert.match(update, /<if test="persona != null">persona = #\{persona\},<\/if>/, 'update 不写 persona 就永远存不进去');

  const chat = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java');
  assert.match(chat, /!nonBlank\(profile\.getPersona\(\)\)/, '对话链对空人设必须保持 fail-closed');
  assert.match(chat, /profile\.getPersona\(\) \+ "\\n"/, '对话链消费的正是 persona 字段');
});
