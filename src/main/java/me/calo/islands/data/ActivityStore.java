package me.calo.islands.data;

import me.calo.islands.content.*;
import me.calo.islands.domain.Destination;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.time.Instant;

/** Checkpoints in the existing CaloIslands pool/schema. Never writes to external plugin tables. */
public final class ActivityStore implements ActivityRepository {
    private final DataSource source;
    public ActivityStore(DataSource source) { this.source = source; }
    @Override public boolean hasSignalOutbox() { return true; }
    @Override public List<ActivityRun> running() throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT * FROM calo_activity_runs WHERE state='RUNNING' ORDER BY run_id"); ResultSet rows = q.executeQuery()) {
            List<ActivityRun> result = new ArrayList<>();
            while (rows.next()) result.add(read(rows));
            return List.copyOf(result);
        }
    }
    @Override public Optional<ActivityRun> find(UUID id) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT * FROM calo_activity_runs WHERE run_id=?")) {
            q.setString(1, id.toString());
            try (ResultSet rows = q.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        }
    }
    @Override public void insert(ActivityRun run) throws SQLException {
        try (Connection c = source.getConnection()) { insertRow(c, run); }
    }
    private static void insertRow(Connection c, ActivityRun run) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                INSERT INTO calo_activity_runs (run_id,participant,content_id,kind,region_id,entry_world,
                  entry_x,entry_y,entry_z,entry_yaw,entry_pitch,active,permissions,state,progress,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ActivityDefinition d = run.definition(); Destination p = d.entry();
            q.setString(1, run.runId().toString()); q.setString(2, run.participant().toString());
            q.setString(3, d.id()); q.setString(4, d.kind().name()); q.setString(5, d.regionId());
            q.setString(6, p.world()); q.setDouble(7, p.x()); q.setDouble(8, p.y()); q.setDouble(9, p.z());
            q.setFloat(10, p.yaw()); q.setFloat(11, p.pitch()); q.setBoolean(12, d.active());
            q.setString(13, String.join("\n", new TreeSet<>(d.requiredPermissions())));
            q.setString(14, run.state().name()); q.setLong(15, run.progress()); q.setLong(16, run.revision());
            q.executeUpdate();
        }
    }
    @Override public boolean update(ActivityRun run, long expectedRevision) throws SQLException {
        try (Connection c = source.getConnection()) { return updateRow(c, run, expectedRevision); }
    }
    private static boolean updateRow(Connection c, ActivityRun run, long expectedRevision) throws SQLException {
        if (expectedRevision < 1 || run.revision() != Math.addExact(expectedRevision, 1))
            throw new IllegalArgumentException("Invalid activity revision");
        try (PreparedStatement q = c.prepareStatement("""
                UPDATE calo_activity_runs SET state=?,progress=?,revision=? WHERE run_id=? AND revision=? AND state='RUNNING'
                """)) {
            q.setString(1, run.state().name()); q.setLong(2, run.progress()); q.setLong(3, run.revision());
            q.setString(4, run.runId().toString()); q.setLong(5, expectedRevision); return q.executeUpdate() == 1;
        }
    }
    @Override public void insertWithSignal(ActivityRun run, ActivitySignal.Type type) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertRow(c, run); insertSignal(c, run, type); c.commit();
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    @Override public boolean updateWithSignal(ActivityRun run, long expectedRevision,
                                               ActivitySignal.Type type) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!updateRow(c, run, expectedRevision)) { c.rollback(); return false; }
                insertSignal(c, run, type);
                if (type == ActivitySignal.Type.COMPLETED) reserveRewardIntent(c, run);
                c.commit(); return true;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    private static void insertSignal(Connection c, ActivityRun run, ActivitySignal.Type type) throws SQLException {
        insertSignal(c, run, type, null);
    }
    private static void insertSignal(Connection c, ActivityRun run, ActivitySignal.Type type,
                                     UUID actor) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                INSERT INTO calo_activity_signals
                  (run_id,revision,protocol_version,type,state,progress,actor_id) VALUES (?,?,?,?,?,?,?)
                """)) {
            q.setString(1, run.runId().toString()); q.setLong(2, run.revision());
            q.setInt(3, actor == null ? ActivitySignal.VERSION : ActivitySignal.GROUP_VERSION);
            q.setString(4, type.name());
            q.setString(5, run.state().name()); q.setLong(6, run.progress());
            q.setString(7, actor == null ? null : actor.toString()); q.executeUpdate();
        }
    }
    private static void reserveRewardIntent(Connection c, ActivityRun run) throws SQLException {
        if (run.state() != ActivityRun.State.COMPLETED)
            throw new IllegalArgumentException("Reward intent requires completed activity");
        Set<UUID> participants = new HashSet<>();
        participants.add(run.participant());
        try (PreparedStatement members = c.prepareStatement(
                "SELECT player_id FROM calo_activity_members WHERE run_id=?")) {
            members.setString(1, run.runId().toString());
            try (ResultSet rows = members.executeQuery()) {
                while (rows.next()) participants.add(UUID.fromString(rows.getString(1)));
            }
        }
        try (PreparedStatement q = c.prepareStatement("""
                INSERT INTO calo_activity_reward_intents (request_id,run_id,player_id)
                VALUES (?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id
                """)) {
            for (UUID participant : participants) {
                UUID requestId = UUID.nameUUIDFromBytes(("CALO_ACTIVITY:" + run.runId() + ":" + participant)
                        .getBytes(StandardCharsets.UTF_8));
                q.setString(1, requestId.toString()); q.setString(2, run.runId().toString());
                q.setString(3, participant.toString()); q.executeUpdate();
            }
        }
    }
    @Override public List<ActivityRewardIntent> rewardIntents(int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid reward intent batch size");
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                SELECT request_id,run_id,player_id,status,payload FROM calo_activity_reward_intents
                WHERE status IN ('WAITING_POLICY','PENDING')
                ORDER BY CASE WHEN status='PENDING' THEN 0 ELSE 1 END,run_id LIMIT ?
                """)) {
            q.setInt(1, limit);
            List<ActivityRewardIntent> result = new ArrayList<>();
            try (ResultSet rows = q.executeQuery()) {
                while (rows.next()) result.add(new ActivityRewardIntent(
                        UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                        UUID.fromString(rows.getString(3)), rows.getString(4), rows.getString(5)));
            }
            return List.copyOf(result);
        }
    }
    @Override public List<ActivityRewardIntent> dispatchableRewardIntents(int limit, Set<String> contentIds)
            throws SQLException {
        if (limit < 1 || limit > 1000 || contentIds == null || contentIds.size() > 100)
            throw new IllegalArgumentException("Invalid reward dispatch query");
        String eligible = contentIds.isEmpty() ? "FALSE" : "r.content_id IN ("
                + String.join(",", Collections.nCopies(contentIds.size(), "?")) + ")";
        String sql = """
                SELECT i.request_id,i.run_id,i.player_id,i.status,i.payload
                FROM calo_activity_reward_intents i JOIN calo_activity_runs r ON r.run_id=i.run_id
                WHERE i.status='PENDING' OR (i.status='WAITING_POLICY' AND
                """ + eligible + ") ORDER BY CASE WHEN i.status='PENDING' THEN 0 ELSE 1 END,i.run_id LIMIT ?";
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(sql)) {
            int parameter = 1;
            for (String id : new TreeSet<>(contentIds)) {
                me.calo.islands.domain.Ids.require(id);
                q.setString(parameter++, id);
            }
            q.setInt(parameter, limit);
            List<ActivityRewardIntent> result = new ArrayList<>();
            try (ResultSet rows = q.executeQuery()) {
                while (rows.next()) result.add(new ActivityRewardIntent(UUID.fromString(rows.getString(1)),
                        UUID.fromString(rows.getString(2)), UUID.fromString(rows.getString(3)),
                        rows.getString(4), rows.getString(5)));
            }
            return List.copyOf(result);
        }
    }
    @Override public boolean pinRewardPolicy(UUID requestId, String payload, boolean eligible) throws SQLException {
        Objects.requireNonNull(requestId);
        if (eligible && (payload == null || payload.isBlank() || payload.length() > 4096))
            throw new IllegalArgumentException("Invalid reward payload");
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                UPDATE calo_activity_reward_intents SET status=?,payload=?
                WHERE request_id=? AND status='WAITING_POLICY'
                """)) {
            q.setString(1, eligible ? "PENDING" : "INELIGIBLE");
            q.setString(2, eligible ? payload : null);
            q.setString(3, requestId.toString());
            return q.executeUpdate() == 1;
        }
    }
    @Override public boolean markRewardApplied(UUID requestId) throws SQLException {
        Objects.requireNonNull(requestId);
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                UPDATE calo_activity_reward_intents SET status='APPLIED'
                WHERE request_id=? AND status='PENDING'
                """)) {
            q.setString(1, requestId.toString());
            return q.executeUpdate() == 1;
        }
    }
    @Override public boolean markRewardInvalid(UUID requestId) throws SQLException {
        Objects.requireNonNull(requestId);
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                UPDATE calo_activity_reward_intents SET status='INVALID_POLICY'
                WHERE request_id=? AND status='PENDING'
                """)) {
            q.setString(1, requestId.toString());
            return q.executeUpdate() == 1;
        }
    }
    @Override public List<ActivitySignal> pendingSignals(int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid signal batch size");
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                SELECT r.*,s.revision AS signal_revision,s.protocol_version,s.type AS signal_type,
                       s.state AS signal_state,s.progress AS signal_progress,s.actor_id
                FROM calo_activity_signals s JOIN calo_activity_runs r ON r.run_id=s.run_id
                WHERE s.delivered=FALSE ORDER BY s.created_at,s.run_id,s.revision LIMIT ?
                """)) {
            q.setInt(1, limit);
            List<ActivitySignal> signals = new ArrayList<>();
            try (ResultSet rows = q.executeQuery()) {
                while (rows.next()) {
                    ActivityRun latest = read(rows);
                    ActivityRun checkpoint = new ActivityRun(latest.runId(), latest.participant(),
                            latest.definition(), ActivityRun.State.valueOf(rows.getString("signal_state")),
                            rows.getLong("signal_progress"), rows.getLong("signal_revision"));
                    String actor = rows.getString("actor_id");
                    signals.add(new ActivitySignal(rows.getInt("protocol_version"),
                            ActivitySignal.Type.valueOf(rows.getString("signal_type")), checkpoint,
                            actor == null ? null : UUID.fromString(actor)));
                }
            }
            return List.copyOf(signals);
        }
    }
    @Override public void markSignalDelivered(UUID runId, long revision) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                UPDATE calo_activity_signals SET delivered=TRUE WHERE run_id=? AND revision=?
                """)) {
            q.setString(1, runId.toString()); q.setLong(2, revision); q.executeUpdate();
        }
    }

    @Override public List<ActivityMember> members(UUID runId) throws SQLException {
        Objects.requireNonNull(runId);
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement("""
                SELECT player_id,active,contribution FROM calo_activity_members
                WHERE run_id=? ORDER BY contribution DESC,player_id
                """)) {
            q.setString(1, runId.toString());
            List<ActivityMember> result = new ArrayList<>();
            try (ResultSet rows = q.executeQuery()) {
                while (rows.next()) result.add(new ActivityMember(UUID.fromString(rows.getString(1)),
                        rows.getBoolean(2), rows.getLong(3)));
            }
            return List.copyOf(result);
        }
    }

    @Override public Instant startedAt(UUID runId) throws SQLException {
        Objects.requireNonNull(runId);
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT started_at FROM calo_activity_runs WHERE run_id=?")) {
            q.setString(1, runId.toString());
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown activity run");
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    @Override public ActivityRun join(UUID runId, UUID playerId, int maxParticipants) throws SQLException {
        Objects.requireNonNull(runId); Objects.requireNonNull(playerId);
        if (maxParticipants < 1 || maxParticipants > 100) throw new IllegalArgumentException("Invalid participant limit");
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                ActivityRun current = lockRun(c, runId);
                if (current.state() != ActivityRun.State.RUNNING) { c.commit(); return current; }
                Boolean existing = null;
                try (PreparedStatement q = c.prepareStatement("""
                        SELECT active FROM calo_activity_members WHERE run_id=? AND player_id=? FOR UPDATE
                        """)) {
                    q.setString(1, runId.toString()); q.setString(2, playerId.toString());
                    try (ResultSet rows = q.executeQuery()) { if (rows.next()) existing = rows.getBoolean(1); }
                }
                if (Boolean.TRUE.equals(existing)) { c.commit(); return current; }
                try (PreparedStatement q = c.prepareStatement(
                        "SELECT COUNT(*) FROM calo_activity_members WHERE run_id=? AND active=TRUE")) {
                    q.setString(1, runId.toString());
                    try (ResultSet rows = q.executeQuery()) {
                        rows.next();
                        if (rows.getInt(1) >= maxParticipants)
                            throw new IllegalStateException("Activity participant limit reached");
                    }
                }
                if (existing == null) {
                    try (PreparedStatement q = c.prepareStatement("""
                            INSERT INTO calo_activity_members (run_id,player_id,active,contribution)
                            VALUES (?,?,TRUE,0)
                            """)) {
                        q.setString(1, runId.toString()); q.setString(2, playerId.toString()); q.executeUpdate();
                    }
                } else {
                    try (PreparedStatement q = c.prepareStatement("""
                            UPDATE calo_activity_members SET active=TRUE WHERE run_id=? AND player_id=?
                            """)) {
                        q.setString(1, runId.toString()); q.setString(2, playerId.toString()); q.executeUpdate();
                    }
                }
                ActivityRun next = current.next(ActivityRun.State.RUNNING, current.progress());
                if (!updateRow(c, next, current.revision())) throw new IllegalStateException("Activity changed concurrently");
                insertSignal(c, next, ActivitySignal.Type.JOINED, playerId);
                c.commit(); return next;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @Override public ActivityRun leave(UUID runId, UUID playerId) throws SQLException {
        Objects.requireNonNull(runId); Objects.requireNonNull(playerId);
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                ActivityRun current = lockRun(c, runId);
                if (current.state() != ActivityRun.State.RUNNING) { c.commit(); return current; }
                int changed;
                try (PreparedStatement q = c.prepareStatement("""
                        UPDATE calo_activity_members SET active=FALSE
                        WHERE run_id=? AND player_id=? AND active=TRUE
                        """)) {
                    q.setString(1, runId.toString()); q.setString(2, playerId.toString());
                    changed = q.executeUpdate();
                }
                if (changed == 0) { c.commit(); return current; }
                ActivityRun next = current.next(ActivityRun.State.RUNNING, current.progress());
                if (!updateRow(c, next, current.revision())) throw new IllegalStateException("Activity changed concurrently");
                insertSignal(c, next, ActivitySignal.Type.LEFT, playerId);
                c.commit(); return next;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @Override public ActivityRun contribute(UUID runId, UUID playerId, UUID actionId,
                                             long amount, long perPlayerLimit) throws SQLException {
        Objects.requireNonNull(runId); Objects.requireNonNull(playerId); Objects.requireNonNull(actionId);
        if (amount < 1 || amount > 1_000_000 || perPlayerLimit < amount)
            throw new IllegalArgumentException("Invalid activity contribution");
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                ActivityRun current = lockRun(c, runId);
                try (PreparedStatement q = c.prepareStatement("""
                        SELECT run_id,player_id,amount FROM calo_activity_actions WHERE action_id=? FOR UPDATE
                        """)) {
                    q.setString(1, actionId.toString());
                    try (ResultSet rows = q.executeQuery()) {
                        if (rows.next()) {
                            if (!runId.toString().equals(rows.getString(1)) || amount != rows.getLong(3))
                                throw new IllegalArgumentException("Activity action UUID collision");
                            c.commit(); return current;
                        }
                    }
                }
                if (current.state() != ActivityRun.State.RUNNING) { c.commit(); return current; }
                long previous;
                try (PreparedStatement q = c.prepareStatement("""
                        SELECT contribution FROM calo_activity_members
                        WHERE run_id=? AND player_id=? AND active=TRUE FOR UPDATE
                        """)) {
                    q.setString(1, runId.toString()); q.setString(2, playerId.toString());
                    try (ResultSet rows = q.executeQuery()) {
                        if (!rows.next()) throw new IllegalStateException("Player is not an active participant");
                        previous = rows.getLong(1);
                    }
                }
                if (Math.addExact(previous, amount) > perPlayerLimit)
                    throw new IllegalStateException("Activity contribution limit reached");
                try (PreparedStatement q = c.prepareStatement("""
                        INSERT INTO calo_activity_actions (action_id,run_id,player_id,amount) VALUES (?,?,?,?)
                        """)) {
                    q.setString(1, actionId.toString()); q.setString(2, runId.toString());
                    q.setString(3, playerId.toString()); q.setLong(4, amount); q.executeUpdate();
                }
                try (PreparedStatement q = c.prepareStatement("""
                        UPDATE calo_activity_members SET contribution=contribution+?
                        WHERE run_id=? AND player_id=?
                        """)) {
                    q.setLong(1, amount); q.setString(2, runId.toString());
                    q.setString(3, playerId.toString()); q.executeUpdate();
                }
                ActivityRun next = current.next(ActivityRun.State.RUNNING, Math.addExact(current.progress(), amount));
                if (!updateRow(c, next, current.revision())) throw new IllegalStateException("Activity changed concurrently");
                insertSignal(c, next, ActivitySignal.Type.CONTRIBUTED, playerId);
                c.commit(); return next;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static ActivityRun lockRun(Connection c, UUID runId) throws SQLException {
        try (PreparedStatement q = c.prepareStatement(
                "SELECT * FROM calo_activity_runs WHERE run_id=? FOR UPDATE")) {
            q.setString(1, runId.toString());
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown activity run");
                return read(rows);
            }
        }
    }
    private static ActivityRun read(ResultSet r) throws SQLException {
        String permissions = r.getString("permissions");
        Set<String> required = permissions.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(permissions.split("\n")));
        var definition = new ActivityDefinition(r.getString("content_id"), ActivityDefinition.Kind.valueOf(r.getString("kind")),
                r.getString("region_id"), new Destination(r.getString("entry_world"), r.getDouble("entry_x"), r.getDouble("entry_y"),
                r.getDouble("entry_z"), r.getFloat("entry_yaw"), r.getFloat("entry_pitch")), r.getBoolean("active"), required);
        return new ActivityRun(UUID.fromString(r.getString("run_id")), UUID.fromString(r.getString("participant")), definition,
                ActivityRun.State.valueOf(r.getString("state")), r.getLong("progress"), r.getLong("revision"));
    }
}
