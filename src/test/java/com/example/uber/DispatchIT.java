package com.example.uber;

import com.example.uber.api.ApiModels.*;
import com.example.uber.driver.DriverService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DispatchIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired DriverService drivers;
    private static final Location PICKUP = new Location(28.6139, 77.2090);
    private static final Location DESTINATION = new Location(28.5355, 77.3910);

    @BeforeEach void resetDispatchOnly() {
        redis.delete(List.of("uber:{dispatch}:locations", "uber:{dispatch}:states", "uber:{dispatch}:updated-at",
                "uber:{dispatch}:driver-trips", "uber:{dispatch}:trips"));
    }

    @Test void postgresContainsTenDriversAndUnknownDriverCannotSendLocation() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drivers", Integer.class)).isEqualTo(10);
        assertThat(http.getForObject("/api/drivers", DriverView[].class)).hasSize(10)
                .allMatch(driver -> driver.state() == DriverState.INACTIVE);
        assertThat(http.exchange("/api/drivers/999/location", HttpMethod.PUT,
                new HttpEntity<>(PICKUP), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void choosesNearestFreshActiveDriverAndRetainsBothTripLocations() {
        activate(1, PICKUP);
        activate(2, new Location(28.62, 77.21));
        activate(3, PICKUP);
        setState(3, DriverState.INACTIVE);
        activate(4, PICKUP);
        redis.opsForHash().put("uber:{dispatch}:updated-at", "4", "1");
        activate(5, new Location(19.0760, 72.8777));
        var request = request(UUID.randomUUID());
        var booking = book(request);
        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(booking.getBody().trip().driverId()).isEqualTo(1);
        assertThat(booking.getBody().trip().start()).isEqualTo(PICKUP);
        assertThat(booking.getBody().trip().end()).isEqualTo(DESTINATION);
        assertThat(booking.getBody().driver().state()).isEqualTo(DriverState.DRIVING);
        var nearby = http.getForObject("/api/drivers/nearby?latitude=28.6139&longitude=77.209&radiusKm=5", NearbyDriver[].class);
        assertThat(nearby).hasSize(1);
        assertThat(nearby[0].driver().id()).isEqualTo(2);
    }

    @Test void twoConcurrentRidersCannotClaimOneDriver() throws Exception {
        activate(1, PICKUP);
        var results = concurrent(List.of(request(UUID.randomUUID()), request(UUID.randomUUID())));
        assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.NOT_FOUND).hasSize(1);
    }

    @Test void twentyConcurrentRidersReceiveExactlyTenDistinctDrivers() throws Exception {
        for (long id = 1; id <= 10; id++) activate(id, PICKUP);
        var requests = new ArrayList<BookTripRequest>();
        for (int i = 0; i < 20; i++) requests.add(request(UUID.randomUUID()));
        var results = concurrent(requests);
        var successes = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).toList();
        assertThat(successes).hasSize(10);
        assertThat(successes.stream().map(r -> r.getBody().trip().driverId()).toList()).doesNotHaveDuplicates();
        assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.NOT_FOUND).hasSize(10);
    }

    @Test void concurrentRetriesReturnOneAssignmentAndConflictingReuseIsRejected() throws Exception {
        activate(1, PICKUP);
        activate(2, PICKUP);
        var request = request(UUID.randomUUID());
        var results = concurrent(Collections.nCopies(8, request));
        assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
        assertThat(results.stream().map(r -> r.getBody().trip()).distinct().toList()).hasSize(1);
        var otherPayload = new BookTripRequest(request.requestId(), "other-rider", PICKUP, DESTINATION, 5.0);
        assertThat(http.postForEntity("/api/trips", otherPayload, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(redis.opsForHash().size("uber:{dispatch}:driver-trips")).isEqualTo(1);
    }

    @Test void completionAndItsRetriesCannotReleaseANewerAssignment() {
        activate(1, PICKUP);
        UUID first = UUID.randomUUID();
        assertThat(book(request(first)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var completed = http.postForEntity("/api/trips/" + first + "/complete", null, Trip.class);
        assertThat(completed.getBody().status()).isEqualTo("COMPLETED");
        assertThat(completed.getBody().completedAt()).isNotNull();
        assertThat(drivers.get(1).state()).isEqualTo(DriverState.ACTIVE);
        UUID second = UUID.randomUUID();
        assertThat(book(request(second)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(http.postForEntity("/api/trips/" + first + "/complete", null, Trip.class).getBody()).isEqualTo(completed.getBody());
        assertThat(drivers.get(1).state()).isEqualTo(DriverState.DRIVING);
        assertThat(redis.opsForHash().get("uber:{dispatch}:driver-trips", "1")).isEqualTo(second.toString());
        assertThat(http.getForObject("/api/trips/" + second, Trip.class).status()).isEqualTo("DRIVING");
    }

    @Test void heartbeatStateChangeAndAppInitializationDoNotReleaseBusyDriver() {
        activate(1, PICKUP);
        assertThat(book(request(UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(http.exchange("/api/drivers/1/location", HttpMethod.PUT,
                new HttpEntity<>(DESTINATION), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setState(1, DriverState.ACTIVE).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(setState(1, DriverState.INACTIVE).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        drivers.run(null);
        assertThat(drivers.get(1).state()).isEqualTo(DriverState.DRIVING);
        assertThat(book(request(UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void emptyStaleAndUnlocatedFleetCannotBeBooked() {
        assertThat(book(request(UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(setState(1, DriverState.ACTIVE).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(book(request(UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        activate(1, PICKUP);
        redis.opsForHash().put("uber:{dispatch}:updated-at", "1", "1");
        assertThat(book(request(UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForObject("/api/drivers/nearby?latitude=28.6139&longitude=77.209", NearbyDriver[].class)).isEmpty();
    }

    @Test void invalidApiInputsReturnBadRequestAndUnknownTripReturnsNotFound() {
        var invalid = new BookTripRequest(UUID.randomUUID(), "rider", new Location(89.0, 77.0), DESTINATION, 5.0);
        assertThat(http.postForEntity("/api/trips", invalid, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/drivers/1/location", HttpMethod.PUT,
                new HttpEntity<>(new Location(null, 77.0)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setState(1, DriverState.DRIVING).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/drivers/nearby?latitude=90&longitude=77", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/drivers/nearby?latitude=28&longitude=77&radiusKm=0", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/trips/invalid-uuid", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/trips/" + UUID.randomUUID() + "/complete", null, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void activate(long id, Location point) {
        assertThat(http.exchange("/api/drivers/" + id + "/location", HttpMethod.PUT,
                new HttpEntity<>(point), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setState(id, DriverState.ACTIVE).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> setState(long id, DriverState state) {
        return http.exchange("/api/drivers/" + id + "/state", HttpMethod.PUT, new HttpEntity<>(new StateRequest(state)), String.class);
    }

    private BookTripRequest request(UUID id) { return new BookTripRequest(id, "rider-" + id, PICKUP, DESTINATION, 5.0); }
    private ResponseEntity<Booking> book(BookTripRequest request) { return http.postForEntity("/api/trips", request, Booking.class); }

    private List<ResponseEntity<Booking>> concurrent(List<BookTripRequest> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = requests.stream().map(request -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                return book(request);
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var responses = new ArrayList<ResponseEntity<Booking>>();
            for (var future : futures) responses.add(future.get(30, TimeUnit.SECONDS));
            return responses;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
