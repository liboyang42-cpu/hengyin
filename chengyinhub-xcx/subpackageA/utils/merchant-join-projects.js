// check-1104 p2 F-3(第十轮 f1 第 10 项):商家工作台「全部 N」里的承接项目,在「我的项目」(scope=MERCHANT)同样列出。
// 两个来源与工作台完全一致(pages/merchant/index loadJoinList / loadChapterProjectList / refreshJoinTotal):
//   ① /api/registration/merchant/list(status=0 取全部):已审核(status=1)且未结束的报名承接,一行一条;
//   ② /api/merchant/chapter-application/mine:已通过的章节承接与本人待接受邀请,按主题合并,①里已有的主题不再重复。
// 只读卡:承接项目的编辑/上下架/删除都不归商家,点卡进同一个项目页(merchantinfo?topicId=…&scope=MERCHANT)。
const { isEndedProject, statusLabelOf, localToday, isPendingChapterInvite } = require('../../utils/merchant-workbench.js');

const STATE_OF_LABEL = { 未开始: 'notStarted', 进行中: 'running', 已结束: 'completed' };

function isRecord(value) { return !!value && typeof value === 'object' && !Array.isArray(value); }

function card(row, key) {
  const topicId = Number(row.topicId);
  const pendingInvite = isPendingChapterInvite(row);
  const dateLabel = statusLabelOf(row.startDate, row.endDate);
  const label = pendingInvite && dateLabel !== '已结束' ? '待接受' : dateLabel;
  return {
    id: topicId,
    key,
    bizType: 'topic',
    ownerType: 'merchant',
    title: row.topicName || row.addressName || '未命名项目',
    cover: row.topicImgUrl || row.imgUrl || '',
    startTime: row.startDate || null,
    endTime: row.endDate || null,
    addressName: row.addressName || '',
    state: label === '待接受' ? 'pending' : (STATE_OF_LABEL[label] || 'running'),
    // 状态签与工作台承接卡同源(statusLabelOf);档期未知写关系本身「承接中」
    stateText: label || '承接中',
    projectTypeText: pendingInvite ? '受邀' : '承接',
    _pendingChapterInvite: pendingInvite,
    _join: true,
  };
}

/**
 * @returns {{ items: Array, total: number }} total 与工作台 joinTotal 同一算法(未结束的已审核报名条数 + 章节独有主题数)。
 */
function joinProjects(legacyRows, chapterRows, today) {
  const day = today || localToday();
  const legacy = (Array.isArray(legacyRows) ? legacyRows : []).filter(isRecord)
    .filter((row) => Number(row.status) === 1 && !isEndedProject(row.startDate, row.endDate, day));
  const legacyTopics = new Set(legacy.map((row) => String(row.topicId))
    .filter((id) => id && id !== 'undefined' && id !== 'null'));
  const items = legacy.map((row) => card(row, 'join-' + row.id));
  const chapterTopics = {};
  (Array.isArray(chapterRows) ? chapterRows : []).filter(isRecord).forEach((row) => {
    const topicId = Number(row.topicId);
    if (!Number.isSafeInteger(topicId) || topicId <= 0 || (Number(row.status) !== 1 && !isPendingChapterInvite(row))) return;
    const key = String(topicId);
    if (!chapterTopics[key] || isPendingChapterInvite(row)) chapterTopics[key] = row;
  });
  Object.keys(chapterTopics).forEach((key) => {
    if (!legacyTopics.has(key)) items.push(card(chapterTopics[key], 'chapter-' + key));
    else if (isPendingChapterInvite(chapterTopics[key])) {
      const index = items.findIndex(item => item.id === Number(key));
      items[index] = card(chapterTopics[key], items[index].key);
    }
  });
  return { items, total: items.length };
}

module.exports = { joinProjects };
