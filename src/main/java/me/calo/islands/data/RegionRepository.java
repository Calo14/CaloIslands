package me.calo.islands.data;

import me.calo.islands.domain.City;
import me.calo.islands.domain.Region;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Local registry persistence contract; the MariaDB store is authoritative. */
public interface RegionRepository {
    List<Region> regions() throws SQLException;
    Optional<Region> region(String id) throws SQLException;
    void insert(Region region) throws SQLException;
    boolean update(Region region, int expectedVersion) throws SQLException;
    boolean deleteRegion(String id, int expectedVersion) throws SQLException;
    List<City> cities() throws SQLException;
    Optional<City> city(String id) throws SQLException;
    void insert(City city) throws SQLException;
    boolean update(City city, int expectedVersion) throws SQLException;
    boolean deleteCity(String id, int expectedVersion) throws SQLException;
}
