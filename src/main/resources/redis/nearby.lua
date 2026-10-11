-- ARGV: longitude, latitude, radiusKm, maxAgeMillis
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local candidates = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2],
    'BYRADIUS', ARGV[3], 'km', 'ASC', 'WITHDIST')
local result = {}
for _, candidate in ipairs(candidates) do
    local id = candidate[1]
    local updated = tonumber(redis.call('HGET', KEYS[3], id) or '0')
    if redis.call('HGET', KEYS[2], id) == 'ACTIVE'
        and redis.call('HEXISTS', KEYS[4], id) == 0
        and updated > 0 and now - updated <= tonumber(ARGV[4]) then
        table.insert(result, {driverId = tonumber(id), distanceKm = tonumber(candidate[2])})
    end
end
-- Lua cjson encodes an empty table as {}, so return [] explicitly.
if #result == 0 then return '[]' end
return cjson.encode(result)
