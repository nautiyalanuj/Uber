package com.example.uber.api;

import com.example.uber.api.ApiModels.*;
import com.example.uber.driver.DriverService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {
    private final DriverService drivers;
    public DriverController(DriverService drivers) { this.drivers = drivers; }

    @GetMapping
    public List<DriverView> list() { return drivers.list(); }

    @GetMapping("/{id}")
    public DriverView get(@PathVariable long id) { return drivers.get(id); }

    @PutMapping("/{id}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void location(@PathVariable long id, @Valid @RequestBody Location point) {
        drivers.updateLocation(id, point);
    }

    @PutMapping("/{id}/state")
    public DriverView state(@PathVariable long id, @Valid @RequestBody StateRequest request) {
        return drivers.setState(id, request.state());
    }

    @GetMapping("/nearby")
    public List<NearbyDriver> nearby(
            @RequestParam @DecimalMin("-85.05112878") @DecimalMax("85.05112878") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam(defaultValue = "5") @Positive @DecimalMax("50") double radiusKm) {
        return drivers.nearby(new Location(latitude, longitude), radiusKm);
    }
}
