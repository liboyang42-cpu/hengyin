local receipt=redis.call('GET',KEYS[3]); if receipt=='0' then return 0 end;
if not receipt then redis.call('SET',KEYS[3],'0','EX',172800); return 0 end;
local sep=string.find(receipt,'|',1,true); if not sep then return redis.error_reply('invalid quota receipt') end;
local gen=string.sub(receipt,1,sep-1); local raw=redis.call('GET',KEYS[1]); local refunded=0;
if raw and redis.call('GET',KEYS[2])==gen then
if not (raw=='0' or string.match(raw,'^[1-9]%d*$')) then return redis.error_reply('invalid quota counter') end;
local used=tonumber(raw); if not used or used>9007199254740991 then return redis.error_reply('quota counter out of range') end;
if used>0 then redis.call('DECR',KEYS[1]); refunded=1 end; end;
redis.call('SET',KEYS[3],'0','EX',172800); return refunded;
