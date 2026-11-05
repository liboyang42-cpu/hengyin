/**
 * 答对那一刻(P-11):老问答答对后先在题卡里停一拍,带上本站碎片,再回故事流。
 * 第三轮(业主):答对写作者在模板里配的完成反馈(文字反馈 feedbackText → node.passText);
 * 作者什么都没写时才用兜底「对了」+ 答对回执里的揭示。
 *
 * 纯函数,可单测。返回 null = 不停这一拍,照旧直接走完成回执(凭证流 / 本局运行时回执 / 重复提交)。
 * - reveal:服务端答对回执里的 answerReveal(答对之后才下发,不预置在节点列表里)。
 * - fragment:本站碎片。探店日(mode 2)完成后另有碎片翻卡,这里不重复。
 * - hold:有揭示或碎片要读 = 等玩家点「继续」;只有「对了」= 短停后自动继续。
 */
function rightMoment(input) {
  const o = input || {};
  const data = o.data || {};
  if (o.genericProof) return null;
  if (data.themeRuntime || data.sharedRuntime || data.done === false) return null;
  if (data.firstTime === false) return null;
  const node = o.node || {};
  const text = String(node.passText || '').trim();
  const reveal = typeof data.answerReveal === 'string' ? data.answerReveal.trim() : '';
  const fragment = Number(o.mode) === 2 ? '' : String(node.fragmentText || '').trim();
  return { text, reveal, fragment, hold: !!(text || reveal || fragment) };
}

/** 只有「对了」时自动继续前停多久(毫秒)。够看清一行字,不拖节奏。 */
const RIGHT_AUTO_MS = 1100;

module.exports = { rightMoment, RIGHT_AUTO_MS };
