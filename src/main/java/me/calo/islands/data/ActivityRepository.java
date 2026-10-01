package me.calo.islands.data;

import me.calo.islands.content.ActivityRun;
import me.calo.islands.content.ActivitySignal;
import me.calo.islands.content.ActivityRewardIntent;
import me.calo.islands.content.ActivityMember;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface ActivityRepository {
    List<ActivityRun> running() throws SQLException;
    Optional<ActivityRun> find(UUID runId) throws SQLException;
    void insert(ActivityRun run) throws SQLException;
    boolean update(ActivityRun run, long expectedRevision) throws SQLException;
    default void insertWithSignal(ActivityRun run, ActivitySignal.Type type) throws SQLException { insert(run); }
    default boolean updateWithSignal(ActivityRun run, long expectedRevision,
                                     ActivitySignal.Type type) throws SQLException { return update(run, expectedRevision); }
    default List<ActivitySignal> pendingSignals(int limit) throws SQLException { return List.of(); }
    default void markSignalDelivered(UUID runId, long revision) throws SQLException { }
    default boolean hasSignalOutbox() { return false; }
    default List<ActivityRewardIntent> rewardIntents(int limit) throws SQLException { return List.of(); }
    default List<ActivityRewardIntent> dispatchableRewardIntents(int limit, java.util.Set<String> contentIds)
            throws SQLException { return rewardIntents(limit); }
    default boolean pinRewardPolicy(UUID requestId, String payload, boolean eligible) throws SQLException {
        throw new UnsupportedOperationException("Reward policy ledger unavailable");
    }
    default boolean markRewardApplied(UUID requestId) throws SQLException {
        throw new UnsupportedOperationException("Reward policy ledger unavailable");
    }
    default boolean markRewardInvalid(UUID requestId) throws SQLException {
        throw new UnsupportedOperationException("Reward policy ledger unavailable");
    }
    default List<ActivityMember> members(UUID runId) throws SQLException { return List.of(); }
    default Instant startedAt(UUID runId) throws SQLException { return Instant.EPOCH; }
    default ActivityRun join(UUID runId, UUID playerId, int maxParticipants) throws SQLException {
        throw new UnsupportedOperationException("Group activities unavailable");
    }
    default ActivityRun leave(UUID runId, UUID playerId) throws SQLException {
        throw new UnsupportedOperationException("Group activities unavailable");
    }
    default ActivityRun contribute(UUID runId, UUID playerId, UUID actionId,
                                   long amount, long perPlayerLimit) throws SQLException {
        throw new UnsupportedOperationException("Group activities unavailable");
    }
}
