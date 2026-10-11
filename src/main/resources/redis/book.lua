-- ARGV: requestId, fingerprint, request JSON, longitude, latitude, radiusKm, maxAgeMillis
local existing = redis.call('HGET', KEYS[5], ARGV[1])
if existing then
    local trip = cjson.decode(existing)
    if trip.fingerprint ~= ARGV[2] then return 'CONFLICT' end
    return existing
end

local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local candidates = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[4], ARGV[5],
    'BYRADIUS', ARGV[6], 'km', 'ASC', 'WITHDIST')
for _, candidate in ipairs(candidates) do
    local id = candidate[1]
    local updated = tonumber(redis.call('HGET', KEYS[3], id) or '0')
    if redis.call('HGET', KEYS[2], id) == 'ACTIVE'
        and redis.call('HEXISTS', KEYS[4], id) == 0
        and updated > 0 and now - updated <= tonumber(ARGV[7]) then
        local trip = cjson.decode(ARGV[3])
        trip.driverId = tonumber(id)
        trip.fingerprint = ARGV[2]
        trip.status = 'DRIVING'
        trip.pickupDistanceKm = tonumber(candidate[2])
        trip.createdAt = now
        local encoded = cjson.encode(trip)
        -- Search, claim, state, and idempotency record execute as one atomic script.
        redis.call('HSET', KEYS[2], id, 'DRIVING')
        redis.call('HSET', KEYS[4], id, ARGV[1])
        redis.call('HSET', KEYS[5], ARGV[1], encoded)
        return encoded
    end
end
return 'NO_DRIVER'
