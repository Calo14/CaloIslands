package me.calo.islands.data;

import me.calo.islands.content.BossAction;
import me.calo.islands.content.BossDefinition;
import me.calo.islands.content.BossFight;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** CaloIlands authority for boss lifecycle and contribution; never computes RPG damage. */
public final class BossStore implements BossRepository {
    private final DataSource source;
    public BossStore(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public void create(BossFight fight) throws SQLException {
        if (fight.state() != BossFight.State.ACTIVE || fight.phaseIndex() != 0
                || fight.healthFraction() != 1 || fight.revision() != 1)
            throw new IllegalArgumentException("Invalid initial boss fight");
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                INSERT INTO calo_boss_fights
                (fight_id,entity_id,world_id,boss_id,mythic_mob_id,phase_rules,state,phase_index,health_fraction,revision)
                VALUES (?,?,?,?,?,?,'ACTIVE',0,1,1)
                """)) {
            q.setString(1, fight.fightId().toString()); q.setString(2, fight.entityId().toString());
            q.setString(3, fight.worldId().toString()); q.setString(4, fight.definition().id());
            q.setString(5, fight.definition().mythicMobId()); q.setString(6, encode(fight.definition()));
            q.executeUpdate();
        }
    }

    @Override public void join(UUID fightId, UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId);
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                BossFight fight = locked(c, fightId);
                if (fight.state() != BossFight.State.ACTIVE) throw new IllegalStateException("Boss is not active");
                try (PreparedStatement q = c.prepareStatement("""
                        INSERT INTO calo_boss_participants (fight_id,player_id) VALUES (?,?)
                        ON DUPLICATE KEY UPDATE player_id=player_id
                        """)) {
                    q.setString(1, fightId.toString()); q.setString(2, playerId.toString()); q.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) { rollback(c, failure); throw failure; }
        }
    }

    @Override public Result record(UUID fightId, BossAction action) throws SQLException {
        Objects.requireNonNull(action);
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                BossFight current = locked(c, fightId);
                if (previous(c, fightId, action)) {
                    c.commit();
                    return new Result(current, true, false, current.state() == BossFight.State.COMPLETED);
                }
                if (current.state() != BossFight.State.ACTIVE)
                    throw new IllegalStateException("Boss is not active");
                if (!participant(c, fightId, action.playerId()))
                    throw new IllegalArgumentException("Player is not a boss participant");
                BossFight next = action.kind() == BossAction.Kind.DAMAGE
                        ? current.advance(action.remainingHealthFraction())
                        : new BossFight(current.fightId(), current.entityId(), current.worldId(),
                                current.definition(), current.state(), current.phaseIndex(),
                                current.healthFraction(), Math.addExact(current.revision(), 1));
                try (PreparedStatement q = c.prepareStatement("""
                        UPDATE calo_boss_fights SET state=?,phase_index=?,health_fraction=?,revision=?
                        WHERE fight_id=? AND revision=? AND state='ACTIVE'
                        """)) {
                    q.setString(1, next.state().name()); q.setInt(2, next.phaseIndex());
                    q.setDouble(3, next.healthFraction()); q.setLong(4, next.revision());
                    q.setString(5, fightId.toString()); q.setLong(6, current.revision());
                    if (q.executeUpdate() != 1) throw new SQLException("Stale boss fight revision");
                }
                try (PreparedStatement q = c.prepareStatement("""
                        INSERT INTO calo_boss_actions
                        (action_id,fight_id,player_id,kind,amount,health_fraction) VALUES (?,?,?,?,?,?)
                        """)) {
                    q.setString(1, action.actionId().toString()); q.setString(2, fightId.toString());
                    q.setString(3, action.playerId().toString()); q.setString(4, action.kind().name());
                    q.setDouble(5, action.amount());
                    if (action.remainingHealthFraction() == null) q.setNull(6, java.sql.Types.DOUBLE);
                    else q.setDouble(6, action.remainingHealthFraction());
                    q.executeUpdate();
                }
                try (PreparedStatement q = c.prepareStatement("""
                        INSERT INTO calo_boss_contributions (fight_id,player_id,kind,amount)
                        VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE amount=amount+VALUES(amount)
                        """)) {
                    q.setString(1, fightId.toString()); q.setString(2, action.playerId().toString());
                    q.setString(3, action.kind().name()); q.setDouble(4, action.amount()); q.executeUpdate();
                }
                if (next.state() == BossFight.State.COMPLETED) reserveRewardIntents(c, fightId);
                c.commit();
                return new Result(next, false, next.phaseIndex() != current.phaseIndex(),
                        next.state() == BossFight.State.COMPLETED);
            } catch (SQLException | RuntimeException failure) { rollback(c, failure); throw failure; }
        }
    }

    @Override public BossFight terminate(UUID fightId, BossFight.State state) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                BossFight current = locked(c, fightId);
                if (current.state() != BossFight.State.ACTIVE) { c.commit(); return current; }
                BossFight next = current.terminate(state);
                try (PreparedStatement q = c.prepareStatement("""
                        UPDATE calo_boss_fights SET state=?,revision=? WHERE fight_id=? AND revision=?
                        """)) {
                    q.setString(1, next.state().name()); q.setLong(2, next.revision());
                    q.setString(3, fightId.toString()); q.setLong(4, current.revision());
                    if (q.executeUpdate() != 1) throw new SQLException("Stale boss fight revision");
                }
                c.commit(); return next;
            } catch (SQLException | RuntimeException failure) { rollback(c, failure); throw failure; }
        }
    }

    @Override public Optional<BossFight> find(UUID fightId) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT * FROM calo_boss_fights WHERE fight_id=?")) {
            q.setString(1, fightId.toString());
            try (ResultSet rows = q.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        }
    }

    @Override public List<BossFight> active() throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT * FROM calo_boss_fights WHERE state='ACTIVE' ORDER BY fight_id");
             ResultSet rows = q.executeQuery()) {
            List<BossFight> result = new ArrayList<>();
            while (rows.next()) result.add(read(rows));
            return List.copyOf(result);
        }
    }

    @Override public Map<BossAction.Kind, Double> contribution(UUID fightId, UUID playerId) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                SELECT kind,amount FROM calo_boss_contributions WHERE fight_id=? AND player_id=?
                """)) {
            q.setString(1, fightId.toString()); q.setString(2, playerId.toString());
            try (ResultSet rows = q.executeQuery()) {
                Map<BossAction.Kind, Double> result = new EnumMap<>(BossAction.Kind.class);
                while (rows.next()) result.put(BossAction.Kind.valueOf(rows.getString(1)), rows.getDouble(2));
                return Map.copyOf(result);
            }
        }
    }

    /** Read-only ranking over already committed contributions. No reward or economy changes. */
    @Override public List<Leader> leaders(UUID fightId, BossAction.Kind kind, int limit) throws SQLException {
        Objects.requireNonNull(fightId); Objects.requireNonNull(kind);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid leaderboard limit");
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                SELECT player_id,amount FROM calo_boss_contributions
                WHERE fight_id=? AND kind=? ORDER BY amount DESC,player_id ASC LIMIT ?
                """)) {
            q.setString(1, fightId.toString()); q.setString(2, kind.name()); q.setInt(3, limit);
            try (ResultSet rows = q.executeQuery()) {
                List<Leader> result = new ArrayList<>();
                while (rows.next()) result.add(new Leader(UUID.fromString(rows.getString(1)), rows.getDouble(2)));
                return List.copyOf(result);
            }
        }
    }

    private static BossFight locked(Connection c, UUID id) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT * FROM calo_boss_fights WHERE fight_id=? FOR UPDATE")) {
            q.setString(1, id.toString());
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown boss fight");
                return read(rows);
            }
        }
    }

    private static boolean participant(Connection c, UUID fightId, UUID playerId) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                SELECT 1 FROM calo_boss_participants WHERE fight_id=? AND player_id=?
                """)) {
            q.setString(1, fightId.toString()); q.setString(2, playerId.toString());
            try (ResultSet rows = q.executeQuery()) { return rows.next(); }
        }
    }

    private static boolean previous(Connection c, UUID fightId, BossAction action) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                SELECT fight_id,player_id,kind,amount,health_fraction
                FROM calo_boss_actions WHERE action_id=? FOR UPDATE
                """)) {
            q.setString(1, action.actionId().toString());
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) return false;
                Double fraction = rows.getObject(5) == null ? null : rows.getDouble(5);
                if (!fightId.toString().equals(rows.getString(1))
                        || !action.playerId().toString().equals(rows.getString(2))
                        || !action.kind().name().equals(rows.getString(3))
                        || Double.compare(action.amount(), rows.getDouble(4)) != 0
                        || !Objects.equals(action.remainingHealthFraction(), fraction))
                    throw new IllegalArgumentException("Boss action UUID collision");
                return true;
            }
        }
    }

    private static void reserveRewardIntents(Connection c, UUID fightId) throws SQLException {
        List<String> players = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement("""
                SELECT DISTINCT player_id FROM calo_boss_contributions
                WHERE fight_id=? AND amount>0 ORDER BY player_id
                """)) {
            q.setString(1, fightId.toString());
            try (ResultSet rows = q.executeQuery()) { while (rows.next()) players.add(rows.getString(1)); }
        }
        try (PreparedStatement q = c.prepareStatement("""
                INSERT INTO calo_boss_reward_intents (request_id,fight_id,player_id)
                VALUES (?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id
                """)) {
            for (String player : players) {
                UUID requestId = UUID.nameUUIDFromBytes(("CALO_BOSS:" + fightId + ":" + player)
                        .getBytes(StandardCharsets.UTF_8));
                q.setString(1, requestId.toString()); q.setString(2, fightId.toString());
                q.setString(3, player); q.addBatch();
            }
            q.executeBatch();
        }
    }

    private static String encode(BossDefinition definition) {
        return definition.phases().stream().map(p -> p.id() + "=" + p.enterAtFraction())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static BossFight read(ResultSet rows) throws SQLException {
        List<BossDefinition.Phase> phases = new ArrayList<>();
        for (String part : rows.getString("phase_rules").split(";")) {
            String[] pair = part.split("=", 2);
            phases.add(new BossDefinition.Phase(pair[0], Double.parseDouble(pair[1])));
        }
        return new BossFight(UUID.fromString(rows.getString("fight_id")),
                UUID.fromString(rows.getString("entity_id")), UUID.fromString(rows.getString("world_id")),
                new BossDefinition(rows.getString("boss_id"), rows.getString("mythic_mob_id"), phases),
                BossFight.State.valueOf(rows.getString("state")), rows.getInt("phase_index"),
                rows.getDouble("health_fraction"), rows.getLong("revision"));
    }

    private static void rollback(Connection c, Exception failure) {
        try { c.rollback(); } catch (SQLException problem) { failure.addSuppressed(problem); }
    }
}
