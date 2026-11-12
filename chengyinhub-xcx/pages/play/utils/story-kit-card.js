// 故事流里两件「不该挤在正文里」的东西(2026-11-06 业主走查 V3-10/53/11、V3-15):
//   ① 要整屏的玩法(摇一摇 / 相册 / 变色就点 / 倒计时盖屏 / 抽卡 / 预言 / 秒表 / 找物):画面本身就是整块屏,
//      塞进故事流只会是「把全屏缩小了」。内嵌时流里只放一张入口卡,点开整屏;关掉回到卡,这一站的玩法不退出。
//      其余玩法照旧真内嵌(块高跟内容走、没有台面底色,见 index.wxss .chfull__kit)。
//   ② 延伸任务:本章还没做的延伸站(编号 ≥ 100)或无块流旧章节里的多站小任务,不再靠正文里的提示句,
//      收进故事流末尾一行「延伸任务」,点开半屏列出,点一行打开那一站的地点卡。
// index.js 的 UI 门禁基线按行号登记,新方法都放这里,index.js 只留单行挂钩。

const KIT_FULLSCREEN_FROM_CARD = ['album', 'ballshake', 'reaction', 'countdown', 'predict', 'random', 'stopwatch', 'hidden'];
/* l-play(proto pop-branch):分支剧情在流里也是一张入口卡,点开不是整屏而是居中弹窗(index.wxml 的 playKit.pop) */
const KIT_POP_FROM_CARD = ['branch'];
/* a1-v3-10-53(2026-11-10 业主「不需要打开两个字 没有图片或其他信息吗」):入口卡 = 封面 + 名字 + 类型。
   类型名与作者在玩法目录里看到的同名(pages/publish/utils/publish/node-game-catalog.js 的 label,单测核对),
   图标同目录的 icon;没有站点配图时用图标块当封面。 */
const KIT_CARD_META = {   // illus = 没有站点配图时入口卡上的插图(cy-illus,l-play)
  album: { label: '相册', icon: 'image', illus: 'gem' },
  ballshake: { label: '弹球', icon: 'play', illus: 'compass' },
  reaction: { label: '变色就点', icon: 'star', illus: 'star' },
  countdown: { label: '倒计时', icon: 'clock', illus: 'hourglass' },
  predict: { label: '预测', icon: 'flag', illus: 'bookmark' },
  random: { label: '翻卡', icon: 'gift', illus: 'gem' },
  stopwatch: { label: '精准停表', icon: 'clock', illus: 'hourglass' },
  hidden: { label: '找东西', icon: 'search', illus: 'clue' },
  branch: { label: '分支剧情', icon: 'switch-chapter', illus: 'key' },
};

/** 会话视图刷新后 playKit 的新状态:整屏 kit 照旧 show;内嵌里要整屏的那批 → 入口卡(已点开的保持开着)。
 *  l-play:玩法开始弹窗选了「全屏」(full)的内嵌玩法,同一个 kit 盖整屏开(show),退出回到流里(见 _collapseKitCard)。 */
function playKitState(prev, kit, inline, ready, full) {
  const pop = !!inline && !!kit && KIT_POP_FROM_CARD.indexOf(kit.type) >= 0;
  const card = pop || (!!inline && !!kit && KIT_FULLSCREEN_FROM_CARD.indexOf(kit.type) >= 0);
  const keepOpen = (card && !!(prev && prev.card && prev.show)) || (!!inline && !!full && !!(prev && prev.show && prev.kit && prev.kit.type === kit.type));
  const meta = card ? KIT_CARD_META[kit.type] : null;
  return { show: !inline || keepOpen || (!!inline && !!full && !(prev && prev.kit && prev.kit.type === kit.type)), kit, readyForBase: ready, card, pop, cardLabel: meta ? meta.label : '', cardIcon: meta ? meta.icon : '', cardIllus: meta ? meta.illus : '' };
}

/** 本章的延伸任务:未完成、属于本章、是延伸站(编号 ≥ 100)或在无块流章节里;正在玩的那站不算 */
function storyTasks(nodes, chapterId, legacy, playingNodeId) {
  return (nodes || []).filter((n) => n && !n.done
    && (chapterId == null || String(n.chapterId) === String(chapterId))
    && (Number(n.num) >= 100 || legacy)
    && String(n.nodeId) !== String(playingNodeId));
}

const data = { storyTasks: { show: false, rows: [] } };

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    /** 入口卡 → 整屏打开同一个玩法(同一局、同一个 kit,不另开会话) */
    expandPlayKit() {
      const pk = this.data.playKit || {};
      if (pk.card && pk.kit) this.setData({ 'playKit.show': true });
    },
    /** closePlayKit 的前置:入口卡点开的整屏只收回到卡;已经可以继续就接着走故事。返回 true = 已处理 */
    _collapseKitCard() {
      const pk = this.data.playKit || {};
      if (!pk.show || !this.data.chapterFull) return false;
      const full = !pk.card && !!this._kitStartFull && !!pk.kit && pk.kit.present === 'inline';   // l-play:玩法开始弹窗选的「全屏」,退出只收回到流里
      if (!pk.card && !full) return false;
      if (full) this._kitStartFull = '';
      this.setData({ 'playKit.show': false });
      if (pk.readyForBase && this.data.advancedPlay && this.data.advancedPlay.show) this.continueStoryGame();
      return true;
    },
    /** openChapterFull 的收尾:有延伸任务就在流末尾放一行入口(一个 .chfull__para,measureChapter 按下标对齐) */
    _pushStoryTasks(items, nodes, blocks, readOnly, mood) {
      const legacy = !(blocks && blocks.length);
      const tasks = readOnly ? [] : storyTasks(nodes, (this.data.chapter || {}).id, legacy, this._playKitNodeId);
      const show = tasks.some((n) => Number(n.num) >= 100) || tasks.length > 1;
      // a1-v3-15:半屏每行带站点配图 / 地址(服务端节点字段原样),没有就不显示
      const rows = show ? tasks.map((n, i) => ({ nodeId: n.nodeId, name: n.name || '', no: i + 1, img: n.imgUrl || '', address: n.address || '', desc: String(n.description || n.story || '').slice(0, 80) })) : [];   // l-play:翻页卡上的一句说明(节点字段原样,没有不出)
      if (rows.length) items.push({ kind: 'tasks', count: rows.length, mood });
      this.setData({ storyTasks: { show: false, rows } });
    },
    openStoryTasks() {
      if ((this.data.storyTasks.rows || []).length) this.setData({ 'storyTasks.show': true, extDeck: { idx: 0, flipping: false } });   // l-play:翻页卡从第一张开始
    },
    closeStoryTasks() { this.setData({ 'storyTasks.show': false }); },
    openStoryTask(e) {
      const id = e && e.currentTarget && e.currentTarget.dataset.nodeid;
      const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(id));
      this.setData({ 'storyTasks.show': false });
      if (node && !node.done) this.openSheet(node);
    },
  };
  return api;
}

module.exports = { KIT_FULLSCREEN_FROM_CARD, KIT_POP_FROM_CARD, KIT_CARD_META, playKitState, storyTasks, data, methods };
