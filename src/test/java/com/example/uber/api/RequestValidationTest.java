package com.example.uber.api;

import com.example.uber.api.ApiModels.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }
    @AfterAll static void close() { factory.close(); }

    @Test void acceptsValidCoordinatesAndOptionalRadius() {
        assertThat(validator.validate(new BookTripRequest(UUID.randomUUID(), "rider-1",
                new Location(28.6139, 77.2090), new Location(28.5355, 77.3910), null))).isEmpty();
    }

    @Test void rejectsMissingLatitudeAndRedisUnsupportedLatitude() {
        assertThat(validator.validate(new Location(null, 77.0))).isNotEmpty();
        assertThat(validator.validate(new Location(89.0, 77.0))).isNotEmpty();
    }

    @Test void validatesNestedLocationsAndSearchRadius() {
        var request = new BookTripRequest(UUID.randomUUID(), "rider-1",
                new Location(28.0, 181.0), new Location(28.0, 77.0), 51.0);
        assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("start.longitude", "radiusKm");
    }

    @Test void rejectsAbsentRequestIdAndBlankRider() {
        var request = new BookTripRequest(null, " ", new Location(28.0, 77.0), new Location(28.0, 77.0), 0.0);
        assertThat(validator.validate(request)).hasSize(3);
    }
}
