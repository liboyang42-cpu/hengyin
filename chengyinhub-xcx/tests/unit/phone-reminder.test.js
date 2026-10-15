const test=require('node:test');
const assert=require('node:assert/strict');
const reminder=require('../../utils/phone-reminder');
test('稍后按账号延后24小时，精确到期恢复且不影响另一账号',()=>{
 const storage={};const wx={getStorageSync:k=>storage[k],setStorageSync:(k,v)=>storage[k]=v};
 assert.equal(reminder.shouldPrompt(wx,7,100),true);assert.equal(reminder.snooze(wx,7,100),true);
 assert.equal(reminder.shouldPrompt(wx,7,100+24*3600000-1),false);
 assert.equal(reminder.shouldPrompt(wx,7,100+24*3600000),true);
 assert.equal(reminder.shouldPrompt(wx,8,101),true);
 assert.equal(reminder.shouldPrompt(wx,'',101),false);
 assert.equal(reminder.snooze(wx,0,101),false);
});
test('损坏或不可用存储不会崩溃或阻塞绑定',()=>{
 assert.equal(reminder.shouldPrompt({getStorageSync:()=> 'fake'},7,0),true);
 assert.equal(reminder.shouldPrompt({getStorageSync:()=>{throw Error();}},7,0),true);
 assert.equal(reminder.snooze({setStorageSync:()=>{throw Error();}},7,0),false);
});
