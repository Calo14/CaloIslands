package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.core.ActivityService;
import me.calo.islands.data.ActivityStore;
import me.calo.islands.domain.*;
import org.bukkit.entity.Player;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivitySignalOutboxTest {
    @Test void committedSignalSurvivesCallbackFailureAndReplaysSameRevision() throws Exception {
        JdbcDataSource source = source(); ActivityStore store = new ActivityStore(source);
        RegionService regions = mock(RegionService.class);
        Region region = new Region("test_region", "world",
                new Bounds(0, -64, 0, 20, 319, 20), true, 1);
        when(regions.region("test_region")).thenReturn(java.util.Optional.of(region));
        when(regions.operational(region)).thenReturn(true);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        ActivityDefinition definition = new ActivityDefinition("test_event", ActivityDefinition.Kind.EVENT,
                "test_region", new Destination("world", 5, 64, 5, 0, 0), true, Set.of());
        ActivityService first = new ActivityService(store, regions, () -> true, (p, d) -> true,
                ignored -> { throw new IllegalStateException("consumer offline"); });
        first.registerCleanup(definition.id(), ignored -> {});
        ActivityRun started = first.start(definition, player);
        ActivityRun progressed = first.progress(started.runId(), 4);
        assertEquals(2, store.pendingSignals(10).size());
        assertEquals(progressed, store.find(started.runId()).orElseThrow());
        List<ActivitySignal> delivered = new ArrayList<>();
        ActivityService restarted = new ActivityService(new ActivityStore(source), regions,
                () -> true, (p, d) -> true, delivered::add);
        assertEquals(2, restarted.dispatchPendingSignals(10));
        assertEquals(List.of(ActivitySignal.Type.STARTED, ActivitySignal.Type.PROGRESS),
                delivered.stream().map(ActivitySignal::type).toList());
        assertEquals(started.revision(), delivered.getFirst().checkpoint().revision());
        assertEquals(progressed.revision(), delivered.getLast().checkpoint().revision());
        assertEquals(0, restarted.dispatchPendingSignals(10));
        assertTrue(store.pendingSignals(10).isEmpty());
    }

    @Test void missingOutboxTableRollsBackActivityStart() throws Exception {
        JdbcDataSource source = source();
        try (Connection c = source.getConnection()) { c.createStatement().execute("DROP TABLE calo_activity_signals"); }
        ActivityStore store = new ActivityStore(source);
        ActivityRun run = new ActivityRun(UUID.randomUUID(), UUID.randomUUID(),
                new ActivityDefinition("test_event", ActivityDefinition.Kind.EVENT, "test_region",
                        new Destination("world", 5, 64, 5, 0, 0), true, Set.of()),
                ActivityRun.State.RUNNING, 0, 1);
        assertThrows(java.sql.SQLException.class,
                () -> store.insertWithSignal(run, ActivitySignal.Type.STARTED));
        assertTrue(store.find(run.runId()).isEmpty());
    }

    @Test void onlyCommittedCompletionReservesOneStableRewardIntent() throws Exception {
        JdbcDataSource source = source(); ActivityStore store = new ActivityStore(source);
        ActivityDefinition definition = new ActivityDefinition("test_event", ActivityDefinition.Kind.EVENT,
                "test_region", new Destination("world", 5, 64, 5, 0, 0), true, Set.of());
        ActivityRun completeRun = new ActivityRun(UUID.randomUUID(), UUID.randomUUID(),
                definition, ActivityRun.State.RUNNING, 0, 1);
        ActivityRun failedRun = new ActivityRun(UUID.randomUUID(), UUID.randomUUID(),
                definition, ActivityRun.State.RUNNING, 0, 1);
        store.insertWithSignal(completeRun, ActivitySignal.Type.STARTED);
        store.insertWithSignal(failedRun, ActivitySignal.Type.STARTED);
        ActivityRun completed = completeRun.next(ActivityRun.State.COMPLETED, 5);
        ActivityRun failed = failedRun.next(ActivityRun.State.FAILED, 0);
        assertTrue(store.updateWithSignal(completed, 1, ActivitySignal.Type.COMPLETED));
        assertTrue(store.updateWithSignal(failed, 1, ActivitySignal.Type.FAILED));
        assertFalse(store.updateWithSignal(completed, 1, ActivitySignal.Type.COMPLETED));
        var intents = new ActivityStore(source).rewardIntents(10);
        assertEquals(1, intents.size());
        assertEquals(completeRun.runId(), intents.getFirst().runId());
        assertEquals(completeRun.participant(), intents.getFirst().playerId());
        assertEquals("WAITING_POLICY", intents.getFirst().status());
        assertEquals(UUID.nameUUIDFromBytes(("CALO_ACTIVITY:" + completeRun.runId() + ":"
                        + completeRun.participant()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                intents.getFirst().requestId());
    }

    @Test void missingRewardLedgerRollsBackCompletionAndSignal() throws Exception {
        JdbcDataSource source = source(); ActivityStore store = new ActivityStore(source);
        ActivityRun run = new ActivityRun(UUID.randomUUID(), UUID.randomUUID(),
                new ActivityDefinition("test_event", ActivityDefinition.Kind.EVENT, "test_region",
                        new Destination("world", 5, 64, 5, 0, 0), true, Set.of()),
                ActivityRun.State.RUNNING, 3, 1);
        store.insertWithSignal(run, ActivitySignal.Type.STARTED);
        try (Connection c = source.getConnection()) {
            c.createStatement().execute("DROP TABLE calo_activity_reward_intents");
        }
        assertThrows(java.sql.SQLException.class, () -> store.updateWithSignal(
                run.next(ActivityRun.State.COMPLETED, 3), 1, ActivitySignal.Type.COMPLETED));
        assertEquals(run, store.find(run.runId()).orElseThrow());
        assertEquals(List.of(ActivitySignal.Type.STARTED),
                store.pendingSignals(10).stream().map(ActivitySignal::type).toList());
    }

    @Test void groupContributionSurvivesRestartDeduplicatesActionsAndReservesEachIntent() throws Exception {
        JdbcDataSource source = source(); ActivityStore first = new ActivityStore(source);
        UUID leader = UUID.randomUUID(), helper = UUID.randomUUID();
        ActivityRun run = new ActivityRun(UUID.randomUUID(), leader,
                new ActivityDefinition("capture", ActivityDefinition.Kind.EVENT, "test_region",
                        new Destination("world", 5, 64, 5, 0, 0), true, Set.of()),
                ActivityRun.State.RUNNING, 0, 1);
        first.insertWithSignal(run, ActivitySignal.Type.STARTED);
        first.join(run.runId(), leader, 2);
        first.join(run.runId(), helper, 2);
        assertEquals(2, first.members(run.runId()).size());
        assertThrows(IllegalStateException.class, () -> first.join(run.runId(), UUID.randomUUID(), 2));
        UUID action = UUID.randomUUID();
        assertEquals(3, first.contribute(run.runId(), helper, action, 3, 5).progress());
        ActivityStore restarted = new ActivityStore(source);
        assertEquals(3, restarted.contribute(run.runId(), helper, action, 3, 5).progress());
        assertEquals(3, restarted.contribute(run.runId(), leader, action, 3, 5).progress());
        assertThrows(IllegalArgumentException.class,
                () -> restarted.contribute(run.runId(), leader, action, 2, 5));
        assertThrows(IllegalStateException.class,
                () -> restarted.contribute(run.runId(), helper, UUID.randomUUID(), 3, 5));
        restarted.leave(run.runId(), helper);
        assertFalse(restarted.members(run.runId()).stream().filter(m -> m.playerId().equals(helper))
                .findFirst().orElseThrow().active());
        restarted.join(run.runId(), helper, 2);
        ActivityRun latest = restarted.find(run.runId()).orElseThrow();
        assertTrue(restarted.updateWithSignal(latest.next(ActivityRun.State.COMPLETED, 3),
                latest.revision(), ActivitySignal.Type.COMPLETED));
        assertEquals(2, restarted.rewardIntents(10).size());
        assertFalse(restarted.updateWithSignal(latest.next(ActivityRun.State.COMPLETED, 3),
                latest.revision(), ActivitySignal.Type.COMPLETED));
        assertEquals(2, restarted.rewardIntents(10).size());
        assertTrue(restarted.pendingSignals(20).stream().anyMatch(s ->
                s.protocolVersion() == 2 && s.type() == ActivitySignal.Type.CONTRIBUTED
                        && helper.equals(s.actor())));
    }
    @Test void rewardPolicyIsPinnedBeforeDispatchAndAcknowledgedOnce() throws Exception {
        JdbcDataSource source = source(); ActivityStore store = new ActivityStore(source);
        UUID player = UUID.randomUUID();
        ActivityRun run = new ActivityRun(UUID.randomUUID(), player,
                new ActivityDefinition("defense", ActivityDefinition.Kind.EVENT, "test_region",
                        new Destination("world", 5, 64, 5, 0, 0), true, Set.of()),
                ActivityRun.State.RUNNING, 2, 1);
        store.insertWithSignal(run, ActivitySignal.Type.STARTED);
        assertTrue(store.updateWithSignal(run.next(ActivityRun.State.COMPLETED, 2), 1,
                ActivitySignal.Type.COMPLETED));
        UUID requestId = store.rewardIntents(10).getFirst().requestId();
        assertTrue(store.pinRewardPolicy(requestId, "v1\nEXPERIENCE\t\t10\n", true));
        assertFalse(store.pinRewardPolicy(requestId, "v1\nEXPERIENCE\t\t20\n", true));
        assertEquals("v1\nEXPERIENCE\t\t10\n", new ActivityStore(source)
                .rewardIntents(10).getFirst().payload());
        assertTrue(store.markRewardApplied(requestId));
        assertFalse(store.markRewardApplied(requestId));
        assertTrue(store.rewardIntents(10).isEmpty());
    }

    @Test void dispatchSkipsUnconfiguredPolicyWithoutStarvingConfiguredIntent() throws Exception {
        ActivityStore store = new ActivityStore(source());
        ActivityDefinition definition = new ActivityDefinition("unconfigured", ActivityDefinition.Kind.EVENT,
                "test_region", new Destination("world", 5, 64, 5, 0, 0), true, Set.of());
        ActivityRun unconfigured = new ActivityRun(UUID.randomUUID(), UUID.randomUUID(), definition,
                ActivityRun.State.RUNNING, 0, 1);
        store.insertWithSignal(unconfigured, ActivitySignal.Type.STARTED);
        assertTrue(store.updateWithSignal(unconfigured.next(ActivityRun.State.COMPLETED, 1), 1,
                ActivitySignal.Type.COMPLETED));
        ActivityRun configured = new ActivityRun(UUID.randomUUID(), UUID.randomUUID(),
                new ActivityDefinition("configured", ActivityDefinition.Kind.EVENT,
                        "test_region", new Destination("world", 5, 64, 5, 0, 0), true, Set.of()),
                ActivityRun.State.RUNNING, 0, 1);
        store.insertWithSignal(configured, ActivitySignal.Type.STARTED);
        assertTrue(store.updateWithSignal(configured.next(ActivityRun.State.COMPLETED, 1), 1,
                ActivitySignal.Type.COMPLETED));
        assertEquals(List.of(configured.runId()), store.dispatchableRewardIntents(1, Set.of("configured"))
                .stream().map(ActivityRewardIntent::runId).toList());
        assertTrue(store.dispatchableRewardIntents(1, Set.of()).isEmpty());
        ActivityRewardIntent intent = store.dispatchableRewardIntents(1, Set.of("configured")).getFirst();
        assertTrue(store.pinRewardPolicy(intent.requestId(), "v1\nEXPERIENCE\t\t10\n", true));
        assertEquals(List.of(intent.requestId()), store.dispatchableRewardIntents(1, Set.of())
                .stream().map(ActivityRewardIntent::requestId).toList());
    }

    private static JdbcDataSource source() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:activity_outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = source.getConnection(); var ddl = c.createStatement()) {
            ddl.execute("""
                    CREATE TABLE calo_activity_runs (run_id CHAR(36) PRIMARY KEY, participant CHAR(36),
                      content_id VARCHAR(64), kind VARCHAR(16), region_id VARCHAR(64), entry_world VARCHAR(128),
                      entry_x DOUBLE, entry_y DOUBLE, entry_z DOUBLE, entry_yaw FLOAT, entry_pitch FLOAT,
                      active BOOLEAN, permissions TEXT, state VARCHAR(16), progress BIGINT, revision BIGINT,
                      started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                    """);
            ddl.execute("""
                    CREATE TABLE calo_activity_signals (run_id CHAR(36), revision BIGINT,
                      protocol_version INT, type VARCHAR(24), state VARCHAR(16), progress BIGINT,
                      actor_id CHAR(36),
                      delivered BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY(run_id,revision))
                    """);
            ddl.execute("""
                    CREATE TABLE calo_activity_reward_intents (request_id CHAR(36) PRIMARY KEY,
                      run_id CHAR(36), player_id CHAR(36), status VARCHAR(24) DEFAULT 'WAITING_POLICY',
                      payload TEXT,
                      UNIQUE(run_id,player_id))
                    """);
            ddl.execute("""
                    CREATE TABLE calo_activity_members (run_id CHAR(36), player_id CHAR(36),
                      active BOOLEAN, contribution BIGINT, PRIMARY KEY(run_id,player_id))
                    """);
            ddl.execute("""
                    CREATE TABLE calo_activity_actions (action_id CHAR(36) PRIMARY KEY,
                      run_id CHAR(36), player_id CHAR(36), amount BIGINT)
                    """);
        }
        return source;
    }
}
