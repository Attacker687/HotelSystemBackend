-- Validate every fallible precondition before the single complete RPUSH.
local now = redis.call('TIME')
local deadline = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])
if not deadline or tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) >= deadline then return -1 end
if not ttl or ttl <= 0 or ttl ~= math.floor(ttl) or #ARGV < 3 then return -2 end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'list' then return -2 end
if not redis.acl_check_cmd('RPUSH', KEYS[1], unpack(ARGV, 3))
    or not redis.acl_check_cmd('PEXPIRE', KEYS[1], ARGV[2]) then return -2 end
local length = redis.call('RPUSH', KEYS[1], unpack(ARGV, 3))
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return length
