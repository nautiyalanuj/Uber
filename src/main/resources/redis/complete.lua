local stored = redis.call('HGET', KEYS[5], ARGV[1])
if not stored then return 'NOT_FOUND' end
local trip = cjson.decode(stored)
-- Retrying an old completion must not release a driver's newer trip.
if trip.status == 'COMPLETED' then return stored end
local id = tostring(trip.driverId)
if redis.call('HGET', KEYS[4], id) ~= ARGV[1] then return 'CONFLICT' end
local time = redis.call('TIME')
trip.status = 'COMPLETED'
trip.completedAt = time[1] * 1000 + math.floor(time[2] / 1000)
local encoded = cjson.encode(trip)
redis.call('HSET', KEYS[5], ARGV[1], encoded)
redis.call('HDEL', KEYS[4], id)
redis.call('HSET', KEYS[2], id, 'ACTIVE')
-- Keep the latest actual heartbeat location; do not assume arrival at destination.
return encoded
