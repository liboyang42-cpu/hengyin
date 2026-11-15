// G2 / GAM-14:节点编辑页渲染已注册的 cy-ai-assist,调用既有 /api/ai/node/generate(与后台「按地点生成题目草稿」同一服务)。
// 只填空着的字段,不覆盖作者已写的内容;生成结果标注「AI 起草」,作者核对后照常保存与提交审核。
// 角色限制沿用服务端(玩家身份会得到服务端的如实提示),这里不另做判断。

const FILLABLE = ['description', 'questionName', 'questionAnswer', 'hint1', 'hint2', 'answerReveal', 'feedbackText', 'storyText'];
const SOURCE_NOTE = 'AI 起草:内容由 AI 生成,请逐条核对后再提交审核';

function buildRequest(formData, prompt) {
  const form = formData || {};
  return {
    nodeName: String(form.title || '').slice(0, 64),
    address: String(form.usageLocation || '').slice(0, 120),
    themeContext: String(form.description || '').slice(0, 300),
    purpose: String(prompt || '').slice(0, 120)
  };
}

/** 返回 {patch, steps, filled}:patch 只含原来为空的字段;规则只在作者还没写任何一步时填入。 */
function fillPatch(formData, ruleSteps, node) {
  const form = formData || {};
  const source = node || {};
  const patch = {};
  let filled = 0;
  FILLABLE.forEach((field) => {
    const value = String(source[field] == null ? '' : source[field]).trim();
    if (value && !String(form[field] || '').trim()) { patch['formData.' + field] = value; filled++; }
  });
  let steps = null;
  const empty = !(ruleSteps || []).some(s => String(s || '').trim());
  const rules = String(source.ruleInstructions || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean).slice(0, 12);
  if (empty && rules.length) { steps = rules.map(s => s.slice(0, 60)); filled++; }
  return { patch, steps, filled };
}

function generate(page, app, e) {
  const prompt = e && e.detail && e.detail.prompt;
  const token = (page._aiAssistToken || 0) + 1;
  page._aiAssistToken = token;
  page.setData({ aiAssistState: 'generating', aiAssistError: '' });
  (function () {
    const that = page;
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/ai/node/generate',
      method: 'POST',
      data: buildRequest(that.data.formData, prompt),
      success: (res) => {
        if (token !== that._aiAssistToken) return;
        const node = res && res.code == '200' && res.data && res.data.node;
        if (!node) {
          that.setData({ aiAssistState: 'error', aiAssistError: (res && res.msg) || '这次没生成出可用的内容,请换个说法再试' });
          return;
        }
        const { patch, steps, filled } = fillPatch(that.data.formData, that.data.ruleSteps, node);
        if (Object.keys(patch).length) that._setFormState(patch);
        if (steps) that._syncRuleSteps(steps);
        that.setData({
          aiAssistState: 'done',
          aiAssistNote: filled ? SOURCE_NOTE : 'AI 已生成,但你已写好的内容都保留了,没有替换'
        });
      },
      fail: (res) => {
        if (token !== that._aiAssistToken) return;
        that.setData({ aiAssistState: 'error', aiAssistError: (res && res.msg) || '生成失败,请重试' });
      }
    });
  })();
}

function cancel(page) {
  page._aiAssistToken = (page._aiAssistToken || 0) + 1;
  page.setData({ aiAssistState: 'idle' });
}

module.exports = { buildRequest, fillPatch, generate, cancel, SOURCE_NOTE, FILLABLE };
