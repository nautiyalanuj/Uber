-- A heartbeat or a state update must never release an assigned driver.
local state = redis.call('HGET', KEYS[2], ARGV[1])
if state == 'DRIVING' or redis.call('HEXISTS', KEYS[4], ARGV[1]) == 1 then
    return 'BUSY'
end
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
return ARGV[2]
