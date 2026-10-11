package com.example.uber.dispatch;

import com.example.uber.api.ApiException;
import com.example.uber.api.ApiModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
public class RedisDispatchStore {
    // All keys share one Redis Cluster hash slot, allowing multi-key Lua scripts.
    private static final List<String> KEYS = List.of("uber:{dispatch}:locations", "uber:{dispatch}:states",
            "uber:{dispatch}:updated-at", "uber:{dispatch}:driver-trips", "uber:{dispatch}:trips");
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final long maxAgeMillis;
    private final DefaultRedisScript<Long> location = script("location", Long.class);
    private final DefaultRedisScript<String> state = script("state", String.class);
    private final DefaultRedisScript<String> nearby = script("nearby", String.class);
    private final DefaultRedisScript<String> book = script("book", String.class);
    private final DefaultRedisScript<String> complete = script("complete", String.class);

    public record NearbyMatch(long driverId, double distanceKm) {}

    public RedisDispatchStore(StringRedisTemplate redis, ObjectMapper json,
                              @Value("${uber.location-max-age-seconds}") long maxAgeSeconds) {
        if (maxAgeSeconds <= 0) throw new IllegalArgumentException("Location max age must be positive");
        this.redis = redis;
        this.json = json;
        this.maxAgeMillis = Math.multiplyExact(maxAgeSeconds, 1000);
    }

    public void initialize(long driverId) {
        redis.opsForHash().putIfAbsent(KEYS.get(1), Long.toString(driverId), DriverState.INACTIVE.name());
    }

    public DriverState getState(long driverId) {
        Object value = redis.opsForHash().get(KEYS.get(1), Long.toString(driverId));
        return value == null ? DriverState.INACTIVE : DriverState.valueOf(value.toString());
    }

    public void updateLocation(long driverId, Location point) {
        redis.execute(location, KEYS, Long.toString(driverId), point.longitude().toString(), point.latitude().toString());
    }

    public DriverState setState(long driverId, DriverState desired) {
        if (desired == DriverState.DRIVING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DRIVING is set only when a trip is assigned");
        }
        String result = redis.execute(state, KEYS, Long.toString(driverId), desired.name());
        if ("BUSY".equals(result)) throw new ApiException(HttpStatus.CONFLICT, "Complete the current trip before changing driver state");
        return DriverState.valueOf(Objects.requireNonNull(result));
    }

    public List<NearbyMatch> nearby(Location point, double radiusKm) {
        String result = redis.execute(nearby, KEYS, point.longitude().toString(), point.latitude().toString(),
                Double.toString(radiusKm), Long.toString(maxAgeMillis));
        return Arrays.asList(read(result, NearbyMatch[].class));
    }

    public Trip book(BookTripRequest request) {
        double radiusKm = request.radiusKm() == null ? 5.0 : request.radiusKm();
        // Normalize omitted/default radius so an equivalent retry has the same fingerprint.
        String payload = write(new BookTripRequest(request.requestId(), request.riderId(), request.start(), request.end(), radiusKm));
        String result = redis.execute(book, KEYS, request.requestId().toString(), fingerprint(payload), payload,
                request.start().longitude().toString(), request.start().latitude().toString(),
                Double.toString(radiusKm), Long.toString(maxAgeMillis));
        if ("NO_DRIVER".equals(result)) throw new ApiException(HttpStatus.NOT_FOUND, "No active driver with a fresh location within the search radius");
        if ("CONFLICT".equals(result)) throw new ApiException(HttpStatus.CONFLICT, "requestId was already used for a different trip request");
        return read(result, Trip.class);
    }

    public Trip getTrip(UUID id) {
        Object stored = redis.opsForHash().get(KEYS.get(4), id.toString());
        if (stored == null) throw new ApiException(HttpStatus.NOT_FOUND, "Trip not found");
        return read(stored.toString(), Trip.class);
    }

    public Trip complete(UUID id) {
        String result = redis.execute(complete, KEYS, id.toString());
        if ("NOT_FOUND".equals(result)) throw new ApiException(HttpStatus.NOT_FOUND, "Trip not found");
        if ("CONFLICT".equals(result)) throw new ApiException(HttpStatus.CONFLICT, "Driver assignment does not match this trip");
        return read(result, Trip.class);
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(Objects.requireNonNull(value), type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid Redis dispatch data", exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot encode trip", exception); }
    }

    private String fingerprint(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        script.setResultType(resultType);
        return script;
    }
}
