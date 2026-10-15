function call(api, input) { return new Promise((resolve, reject) => api(Object.assign({}, input, { success: resolve, fail: reject }))); }
function current(page, who, generation) { if (!page.current(generation, who)) throw new Error('登录或页面已变化，文件操作已失效'); }
async function writeFile(page, filename, data, expiresAt) {
  const who = page._identity, generation = page._generation;
  current(page, who, generation);
  if (expiresAt && Date.now() >= expiresAt) throw new Error('文件内容已过期，请重新准备');
  if (!/^[A-Za-z0-9_.-]+\.(jsonl|ics)$/.test(filename) || typeof data !== 'string') throw new Error('文件内容或名称无效');
  const fs = wx.getFileSystemManager();
  const filePath = wx.env.USER_DATA_PATH + '/' + filename;
  await call(fs.writeFile.bind(fs), { filePath, data, encoding: 'utf8' });
  page._files.push(filePath);
  try { current(page, who, generation); } catch (e) { page.deleteFiles(); throw e; }
  return filePath;
}
async function transfer(page, file, share) {
  const who = page._identity, generation = page._generation;
  current(page, who, generation);
  if (file.expiresAt && Date.now() >= file.expiresAt) { page.deleteFiles(); throw new Error('文件内容已过期，请重新准备'); }
  const path = await writeFile(page, file.filename, file.content, file.expiresAt);
  try {
    if (share) {
      if (typeof wx.shareFileMessage !== 'function') throw new Error('当前微信版本不支持文件分享');
      await call(wx.shareFileMessage.bind(wx), { filePath: path, fileName: file.filename });
    } else {
      if (typeof wx.saveFileToDisk !== 'function') throw new Error('当前设备不支持保存到文件，请使用分享文件');
      await call(wx.saveFileToDisk.bind(wx), { filePath: path });
    }
    current(page, who, generation);
    return share ? '文件分享已完成' : '文件保存已完成';
  } catch (e) {
    current(page, who, generation);
    const message = String(e.errMsg || e.message || '文件操作失败');
    if (/cancel/i.test(message)) return '已取消，未完成文件操作';
    if (/auth|permission|denied/i.test(message)) throw new Error('文件权限不足，未完成文件操作');
    throw new Error(message);
  } finally { page.deleteFiles(); }
}
module.exports = { transfer, writeFile };
