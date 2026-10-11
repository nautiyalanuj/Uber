-- KEYS: locations, states, updated-at, driver-trips, trips
-- ARGV: driverId, longitude, latitude. Redis TIME avoids app clock skew.
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
redis.call('GEOADD', KEYS[1], ARGV[2], ARGV[3], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[1], now)
redis.call('HSETNX', KEYS[2], ARGV[1], 'INACTIVE')
return 1
