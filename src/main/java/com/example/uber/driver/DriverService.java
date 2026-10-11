package com.example.uber.driver;

import com.example.uber.api.ApiModels.*;
import com.example.uber.dispatch.RedisDispatchStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DriverService implements ApplicationRunner {
    private final DriverRepository repository;
    private final RedisDispatchStore dispatch;
    public DriverService(DriverRepository repository, RedisDispatchStore dispatch) {
        this.repository = repository;
        this.dispatch = dispatch;
    }

    @Override
    public void run(ApplicationArguments args) {
        // HSETNX preserves assigned drivers when another app instance starts.
        repository.findAll().forEach(driver -> dispatch.initialize(driver.id()));
    }

    public List<DriverView> list() { return repository.findAll().stream().map(this::view).toList(); }
    public DriverView get(long id) { return view(repository.find(id)); }

    public void updateLocation(long id, Location point) {
        repository.find(id);
        dispatch.updateLocation(id, point);
    }

    public DriverView setState(long id, DriverState state) {
        DriverRepository.Driver driver = repository.find(id);
        DriverState applied = dispatch.setState(id, state);
        return new DriverView(driver.id(), driver.name(), driver.phone(), driver.vehicleNumber(), applied);
    }

    public List<NearbyDriver> nearby(Location point, double radiusKm) {
        return dispatch.nearby(point, radiusKm).stream()
                .map(match -> new NearbyDriver(get(match.driverId()), match.distanceKm())).toList();
    }

    private DriverView view(DriverRepository.Driver driver) {
        return new DriverView(driver.id(), driver.name(), driver.phone(), driver.vehicleNumber(), dispatch.getState(driver.id()));
    }
}
