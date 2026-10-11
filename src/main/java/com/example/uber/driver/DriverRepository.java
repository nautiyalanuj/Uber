package com.example.uber.driver;

import com.example.uber.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class DriverRepository {
    public record Driver(long id, String name, String phone, String vehicleNumber) {}
    private final JdbcTemplate jdbc;
    public DriverRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Driver> findAll() {
        return jdbc.query("SELECT id, name, phone, vehicle_number FROM drivers ORDER BY id",
                (rs, row) -> new Driver(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("vehicle_number")));
    }

    public Driver find(long id) {
        return jdbc.query("SELECT id, name, phone, vehicle_number FROM drivers WHERE id = ?",
                (rs, row) -> new Driver(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("vehicle_number")), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Driver not found"));
    }
}
