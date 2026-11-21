// 首页问候:登录且起过自己的昵称才带名字;游客与系统默认昵称(「城瘾用户」「微信用户」「用户123」)只说「你好」。
const PLACEHOLDER_NICKNAMES = ['城瘾用户', '微信用户', '旅人'];
const GENERATED_NICKNAME = /^用户\d+$/;

function isPlaceholderNickname(nickname) {
  const nick = String(nickname == null ? '' : nickname).trim();
  return !nick || PLACEHOLDER_NICKNAMES.indexOf(nick) >= 0 || GENERATED_NICKNAME.test(nick);
}

function homeGreeting(userInfo) {
  const nick = String((userInfo && userInfo.nickname) || '').trim();
  return isPlaceholderNickname(nick) ? '你好' : '你好，' + nick;
}

module.exports = { homeGreeting, isPlaceholderNickname };
