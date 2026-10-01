package me.calo.islands.data;

import me.calo.islands.content.ManagedObjective;
import java.sql.SQLException;
import java.util.List;

/** Durable overrides for staff-managed activities. Runs remain in ActivityStore. */
public interface ObjectiveDefinitionRepository {
    List<ManagedObjective> list() throws SQLException;
    void save(ManagedObjective value, long expectedRevision) throws SQLException;
}
