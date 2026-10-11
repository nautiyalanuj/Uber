package com.example.uber.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    // Redis GEO supports a smaller latitude range than GPS's +/-90 degrees.
    public record Location(
            @NotNull @DecimalMin("-85.05112878") @DecimalMax("85.05112878") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    public enum DriverState { ACTIVE, INACTIVE, DRIVING }

    public record StateRequest(@NotNull DriverState state) {}

    public record BookTripRequest(
            @NotNull UUID requestId,
            @NotBlank @Size(max = 80) String riderId,
            @NotNull @Valid Location start,
            @NotNull @Valid Location end,
            @Positive @DecimalMax("50") Double radiusKm) {}

    public record DriverView(long id, String name, String phone, String vehicleNumber, DriverState state) {}
    public record NearbyDriver(DriverView driver, double distanceKm) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trip(UUID requestId, String riderId, long driverId, Location start, Location end,
                       double radiusKm, String status, double pickupDistanceKm,
                       long createdAt, Long completedAt) {}
    public record Booking(Trip trip, DriverView driver) {}
}
