package com.example.uber.api;

import com.example.uber.api.ApiModels.*;
import com.example.uber.dispatch.RedisDispatchStore;
import com.example.uber.driver.DriverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/trips")
public class TripController {
    private final RedisDispatchStore dispatch;
    private final DriverService drivers;
    public TripController(RedisDispatchStore dispatch, DriverService drivers) {
        this.dispatch = dispatch;
        this.drivers = drivers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Booking book(@Valid @RequestBody BookTripRequest request) {
        Trip trip = dispatch.book(request);
        return new Booking(trip, drivers.get(trip.driverId()));
    }

    @GetMapping("/{id}")
    public Trip get(@PathVariable UUID id) { return dispatch.getTrip(id); }

    @PostMapping("/{id}/complete")
    public Trip complete(@PathVariable UUID id) { return dispatch.complete(id); }
}
