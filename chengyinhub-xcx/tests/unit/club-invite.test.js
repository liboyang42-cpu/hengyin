const assert = require('node:assert/strict');
const test = require('node:test');

const invite = require('../../utils/club-invite.js');

test('W7 SOC-15: only a canonical 10-character code is read from the link', () => {
  assert.equal(invite.parseInviteCode({ id: '42', invite: 'ABCDEFGH23' }), 'ABCDEFGH23');
  assert.equal(invite.parseInviteCode({ id: '42', invite: 'abcdefgh23' }), '');
  assert.equal(invite.parseInviteCode({ id: '42', invite: 'ABCDEFGH2O' }), '', 'O and 0 are not in the alphabet');
  assert.equal(invite.parseInviteCode({ id: '42' }), '');
  assert.equal(invite.parseInviteCode(null), '');
});

test('W7 SOC-15: routes match the server', () => {
  assert.equal(invite.landingUrl('ABCDEFGH23'), '/api/club/invites/ABCDEFGH23');
  assert.equal(invite.acceptUrl('ABCDEFGH23'), '/api/club/invites/ABCDEFGH23/accept');
});

test('W7 SOC-15: a valid invite names the club and the target channel', () => {
  const banner = invite.inviteBanner({ state: 'VALID', club_name: '夜跑团', channel_name: '活动', channel_id: '50' });
  assert.equal(banner.valid, true);
  assert.equal(banner.title, '你被邀请加入「夜跑团」');
  assert.equal(banner.sub, '加入后带你去 #活动');
  assert.equal(invite.inviteBanner({ state: 'VALID', club_name: '夜跑团', already_member: true }), null);
});

test('W7 SOC-15: an expired, used-up or revoked invite says why and keeps the normal join', () => {
  assert.equal(invite.inviteBanner({ state: 'EXPIRED' }).title, '邀请已失效');
  assert.equal(invite.inviteBanner({ state: 'FULL' }).title, '邀请次数已用完');
  assert.equal(invite.inviteBanner({ state: 'REVOKED' }).title, '邀请已撤销');
  assert.equal(invite.inviteBanner({ state: 'FULL' }).valid, false);
  assert.equal(invite.inviteBanner({ state: 'FULL' }).sub, '你仍可按俱乐部的规则申请加入');
  assert.equal(invite.inviteBanner(null), null);
});

test('W7 SOC-15: after joining, a channel invite opens the channel list; pending stays on the club', () => {
  assert.equal(invite.afterAcceptUrl('42', { state: 'joined', channel_id: '50' }), '/pages/club/channels/index?clubId=42');
  assert.equal(invite.afterAcceptUrl('42', { state: 'MEMBER', channel_id: '50' }), '/pages/club/channels/index?clubId=42');
  assert.equal(invite.afterAcceptUrl('42', { state: 'pending', channel_id: '50' }), '');
  assert.equal(invite.afterAcceptUrl('42', { state: 'joined', channel_id: null }), '');
});
