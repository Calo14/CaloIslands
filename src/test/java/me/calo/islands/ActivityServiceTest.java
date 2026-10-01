package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.core.ActivityService;
import me.calo.islands.data.ActivityRepository;
import me.calo.islands.domain.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class ActivityServiceTest {
    private final Memory store = new Memory();
    private final RegionService regions = mock(RegionService.class);
    private final Player player = mock(Player.class);
    private final List<ActivitySignal> signals = new ArrayList<>();
    private final Region region = new Region("test_region", "test_world", new Bounds(0,-64,0,20,319,20),true,1);
    private final ActivityDefinition definition = new ActivityDefinition("test_event", ActivityDefinition.Kind.EVENT,
            "test_region",new Destination("test_world",5.5,64,5.5,90,0),true,Set.of("content.test.enter"));
    private ActivityService service() throws Exception {
        when(regions.region("test_region")).thenReturn(Optional.of(region)); when(regions.operational(region)).thenReturn(true);
        when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("content.test.enter")).thenReturn(true);
        return new ActivityService(store,regions,()->true,(p,d)->true,signals::add);
    }
    @Test void startProgressCompletionAndRepeatedRequestsHaveDurableVersionedSignals() throws Exception {
        ActivityService service=service(); AtomicInteger cleaned=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->service.start(definition,player));
        service.registerCleanup(definition.id(),run->cleaned.incrementAndGet());
        ActivityRun run=service.start(definition,player);
        assertEquals(1,run.revision()); assertEquals(definition,store.find(run.runId()).orElseThrow().definition());
        ActivityRun progressed=service.progress(run.runId(),3); assertEquals(2,progressed.revision());
        assertEquals(progressed,service.progress(run.runId(),3)); assertEquals(progressed,service.progress(run.runId(),2));
        ActivityRun completed=service.complete(run.runId()); assertEquals(ActivityRun.State.COMPLETED,completed.state());
        assertEquals(3,completed.revision()); assertEquals(completed,service.complete(run.runId()));
        assertEquals(completed,service.cancel(run.runId())); assertEquals(1,cleaned.get());
        assertEquals(List.of(ActivitySignal.Type.STARTED,ActivitySignal.Type.PROGRESS,ActivitySignal.Type.COMPLETED),
                signals.stream().map(ActivitySignal::type).toList());
        assertTrue(signals.stream().allMatch(s->s.protocolVersion()==1 && s.checkpoint().runId().equals(run.runId())));
    }
    @Test void restartRequiresCleanupBindingAndRecoversByCancellingWithoutReplayingCompletion() throws Exception {
        ActivityService first=service(); first.registerCleanup(definition.id(),run->{});
        ActivityRun run=first.start(definition,player); first.progress(run.runId(),7);
        signals.clear(); ActivityService restarted=service(); AtomicInteger cleaned=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->restarted.progress(run.runId(),8));
        assertThrows(IllegalStateException.class,()->restarted.complete(run.runId()));
        Consumer<ActivityRun> handler=checkpoint->cleaned.incrementAndGet();
        restarted.registerCleanup(definition.id(),handler); restarted.registerCleanup(definition.id(),handler);
        assertEquals(1,cleaned.get()); assertEquals(1,signals.size()); assertEquals(ActivitySignal.Type.RECOVERED,signals.getFirst().type());
        ActivityRun recovered=restarted.checkpoint(run.runId()).orElseThrow();
        assertEquals(ActivityRun.State.CANCELLED,recovered.state()); assertEquals(7,recovered.progress()); assertEquals(3,recovered.revision());
        assertEquals(recovered,restarted.cancel(run.runId())); assertEquals(recovered,restarted.complete(run.runId()));
    }
    @Test void inactiveMissingRegionPermissionsExternalEntryAndAsyncCallsDenyBeforeInsert() throws Exception {
        ActivityService service=service(); service.registerCleanup(definition.id(),run->{});
        when(player.hasPermission("content.test.enter")).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->service.start(definition,player));
        when(player.hasPermission("content.test.enter")).thenReturn(true); when(regions.operational(region)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->service.start(definition,player));
        when(regions.operational(region)).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->service.start(new ActivityDefinition(definition.id(),definition.kind(),definition.regionId(),definition.entry(),false,Set.of()),player));
        when(regions.region("test_region")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,()->service.start(definition,player));
        when(regions.region("test_region")).thenReturn(Optional.of(region));
        ActivityService denied=new ActivityService(store,regions,()->true,(p,d)->false,signals::add);
        denied.registerCleanup(definition.id(),run->{}); assertThrows(IllegalArgumentException.class,()->denied.start(definition,player));
        ActivityService async=new ActivityService(store,regions,()->false,(p,d)->true,signals::add);
        assertThrows(IllegalStateException.class,()->async.registerCleanup(definition.id(),run->{}));
        assertThrows(IllegalStateException.class,()->async.start(definition,player));
        assertTrue(store.values.isEmpty()); assertTrue(signals.isEmpty());
    }
    @Test void storageFailureAfterCleanupLeavesCheckpointRetryableAndEmitsNoCompletion() throws Exception {
        ActivityService service=service(); AtomicInteger attempts=new AtomicInteger(); service.registerCleanup(definition.id(),run->attempts.incrementAndGet());
        ActivityRun run=service.start(definition,player); store.failUpdates=true;
        assertThrows(SQLException.class,()->service.complete(run.runId()));
        assertEquals(ActivityRun.State.RUNNING,store.find(run.runId()).orElseThrow().state()); assertEquals(1,signals.size());
        store.failUpdates=false; service.complete(run.runId()); assertEquals(2,attempts.get()); assertEquals(2,signals.size());
    }
    @Test void resumableDefenseRestoresRuntimeAfterUnconfirmedCompletion() throws Exception {
        ActivityService service=service(); AtomicBoolean active=new AtomicBoolean();
        service.registerResumable(definition.id(),run->active.set(false),run->active.set(true));
        ActivityRun run=service.start(definition,player); active.set(true);
        store.failUpdates=true;
        assertThrows(SQLException.class,()->service.complete(run.runId()));
        assertTrue(active.get());
        assertEquals(ActivityRun.State.RUNNING,service.checkpoint(run.runId()).orElseThrow().state());
        store.failUpdates=false;
        assertEquals(ActivityRun.State.COMPLETED,service.complete(run.runId()).state());
        assertFalse(active.get());
    }
    @Test void cleanupCannotReenterProgressAndRecoveryCanBeRetriedAfterFailure() throws Exception {
        ActivityService first=service(); first.registerCleanup(definition.id(),run->{}); ActivityRun run=first.start(definition,player);
        ActivityService recovered=service(); AtomicInteger attempts=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->recovered.registerCleanup(definition.id(),checkpoint->{
            if(attempts.incrementAndGet()==1) throw new IllegalStateException("cleanup temporarily unavailable");
            assertThrows(IllegalStateException.class,()->recovered.progress(checkpoint.runId(),99));
        }));
        assertEquals(ActivityRun.State.RUNNING,store.find(run.runId()).orElseThrow().state());
        assertThrows(IllegalStateException.class,()->recovered.start(definition,player));
        recovered.recover(definition.id());
        assertEquals(ActivityRun.State.CANCELLED,store.find(run.runId()).orElseThrow().state()); assertEquals(2,attempts.get());
    }
    @Test void shutdownCancelsAllRunsEvenWhenOneCleanupFailsAndLeavesThatRunRecoverable() throws Exception {
        ActivityService service=service(); service.registerCleanup(definition.id(),run->{ throw new IllegalStateException("retry cleanup"); });
        ActivityRun failed=service.start(definition,player);
        var dungeon=new ActivityDefinition("test_dungeon",ActivityDefinition.Kind.DUNGEON,"test_region",definition.entry(),true,Set.of());
        service.registerCleanup(dungeon.id(),run->{}); ActivityRun successful=service.start(dungeon,player);
        assertThrows(SQLException.class,service::shutdown);
        assertEquals(ActivityRun.State.RUNNING,store.find(failed.runId()).orElseThrow().state());
        assertEquals(ActivityRun.State.CANCELLED,store.find(successful.runId()).orElseThrow().state());
        assertThrows(IllegalStateException.class,()->service.start(dungeon,player));
    }
    @Test void resumableContentPreservesCheckpointAcrossGracefulShutdownAndRestart() throws Exception {
        ActivityService first=service(); AtomicInteger cleaned=new AtomicInteger();
        first.registerResumable(definition.id(),run->cleaned.incrementAndGet(),run->{});
        ActivityRun run=first.start(definition,player);
        first.progress(run.runId(),4);
        first.shutdown();
        assertEquals(1,cleaned.get());
        assertEquals(ActivityRun.State.RUNNING,store.find(run.runId()).orElseThrow().state());
        signals.clear();
        ActivityService restarted=service(); List<ActivityRun> resumed=new ArrayList<>();
        restarted.registerResumable(definition.id(),ignored->{},resumed::add);
        assertEquals(1,resumed.size());
        assertEquals(4,resumed.getFirst().progress());
        assertEquals(3,store.find(run.runId()).orElseThrow().revision());
        assertEquals(ActivitySignal.Type.RECOVERED,signals.getFirst().type());
        assertEquals(4,restarted.progress(run.runId(),5).revision());
    }
    @Test void failedDefenseIsDistinctFromCancellationAndCannotCompleteOrRewardAgain() throws Exception {
        ActivityService service=service(); service.registerCleanup(definition.id(),run->{});
        ActivityRun run=service.start(definition,player);
        ActivityRun failed=service.fail(run.runId());
        assertEquals(ActivityRun.State.FAILED,failed.state());
        assertEquals(ActivitySignal.Type.FAILED,signals.getLast().type());
        assertEquals(failed,service.complete(run.runId()));
        assertEquals(failed,service.fail(run.runId()));
        assertEquals(0,failed.progress());
    }
    @Test void duplicateActivationCannotOrphanTheFirstParticipantsCheckpoint() throws Exception {
        ActivityService service=service(); service.registerCleanup(definition.id(),run->{});
        ActivityRun first=service.start(definition,player);
        assertThrows(IllegalStateException.class,()->service.start(definition,player));
        assertEquals(List.of(first),store.running());
        assertEquals(1,signals.size());
        service.cancel(first.runId());
        assertEquals(ActivityRun.State.RUNNING,service.start(definition,player).state());
    }
    private static final class Memory implements ActivityRepository {
        final Map<UUID,ActivityRun> values=new LinkedHashMap<>(); boolean failUpdates;
        public List<ActivityRun> running() { return values.values().stream().filter(r->r.state()==ActivityRun.State.RUNNING).toList(); }
        public Optional<ActivityRun> find(UUID id) { return Optional.ofNullable(values.get(id)); }
        public void insert(ActivityRun run) { if(values.putIfAbsent(run.runId(),run)!=null) throw new IllegalStateException("duplicate run"); }
        public boolean update(ActivityRun run,long expectedRevision) throws SQLException {
            if(failUpdates) throw new SQLException("test storage unavailable");
            ActivityRun old=values.get(run.runId()); if(old==null || old.revision()!=expectedRevision || old.state()!=ActivityRun.State.RUNNING) return false;
            values.put(run.runId(),run); return true;
        }
    }
}
