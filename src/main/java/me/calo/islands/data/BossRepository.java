package me.calo.islands.data;

import me.calo.islands.content.BossAction;
import me.calo.islands.content.BossFight;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface BossRepository {
    record Result(BossFight fight, boolean replayed, boolean phaseChanged, boolean completed) { }
    record Leader(UUID playerId, double amount) { }
    void create(BossFight fight) throws SQLException;
    void join(UUID fightId, UUID playerId) throws SQLException;
    Result record(UUID fightId, BossAction action) throws SQLException;
    BossFight terminate(UUID fightId, BossFight.State state) throws SQLException;
    Optional<BossFight> find(UUID fightId) throws SQLException;
    List<BossFight> active() throws SQLException;
    Map<BossAction.Kind, Double> contribution(UUID fightId, UUID playerId) throws SQLException;
    List<Leader> leaders(UUID fightId, BossAction.Kind kind, int limit) throws SQLException;
}
