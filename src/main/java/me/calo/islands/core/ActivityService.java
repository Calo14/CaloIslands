package me.calo.islands.core;

import me.calo.islands.content.*;
import me.calo.islands.data.ActivityRepository;
import me.calo.islands.domain.RegionService;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.time.Instant;

/** Real lifecycle and checkpoints; content providers own encounters and idempotent cleanup. */
public final class ActivityService {
    private final ActivityRepository store;
    private final RegionService regions;
    private final BooleanSupplier primaryThread;
    private final BiPredicate<Player, ActivityDefinition> entryAllowed;
    private final Consumer<ActivitySignal> signals;
    private final Map<String, Consumer<ActivityRun>> cleanup = new HashMap<>();
    private final Map<String, Consumer<ActivityRun>> resume = new HashMap<>();
    private final Set<UUID> pendingRecovery = new HashSet<>();
    private final Set<UUID> mutating = new HashSet<>();
    private boolean closed;

    public ActivityService(ActivityRepository store, RegionService regions, BooleanSupplier primaryThread,
                           BiPredicate<Player, ActivityDefinition> entryAllowed, Consumer<ActivitySignal> signals) throws SQLException {
        this.store = store; this.regions = regions; this.primaryThread = primaryThread;
        this.entryAllowed = entryAllowed; this.signals = signals;
        for (ActivityRun run : store.running()) pendingRecovery.add(run.runId());
    }
    private void requireThread() {
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Activity API requires the server thread");
        if (closed) throw new IllegalStateException("Activity service is closed");
    }
    /** Existing providers keep cancellation recovery unless they explicitly register a resume handler. */
    public void registerCleanup(String contentId, Consumer<ActivityRun> handler) throws SQLException {
        requireThread(); me.calo.islands.domain.Ids.require(contentId);
        Objects.requireNonNull(handler);
        Consumer<ActivityRun> existing = cleanup.putIfAbsent(contentId, handler);
        if (existing != null && existing != handler) throw new IllegalStateException("Cleanup already registered");
        recover(contentId);
    }
    /** Content that can reconstruct its runtime state from an immutable checkpoint opts in to resume. */
    public void registerResumable(String contentId, Consumer<ActivityRun> cleanupHandler,
                                  Consumer<ActivityRun> resumeHandler) throws SQLException {
        requireThread(); Objects.requireNonNull(resumeHandler);
        resume.put(contentId, resumeHandler);
        registerCleanup(contentId, cleanupHandler);
    }
    /** Explicit retry when cleanup or checkpoint persistence was temporarily unavailable. */
    public void recover(String contentId) throws SQLException {
        requireThread();
        if (!cleanup.containsKey(contentId)) throw new IllegalStateException("Content cleanup is unavailable");
        for (UUID id : List.copyOf(pendingRecovery)) {
            ActivityRun run = require(id);
            if (!run.definition().id().equals(contentId)) continue;
            if (run.state() == ActivityRun.State.RUNNING) {
                Consumer<ActivityRun> resumeHandler = resume.get(contentId);
                if (resumeHandler == null) terminal(run, ActivityRun.State.CANCELLED, ActivitySignal.Type.RECOVERED);
                else {
                    resumeHandler.accept(run);
                    ActivityRun next = run.next(ActivityRun.State.RUNNING, run.progress());
                    save(next, run.revision(), ActivitySignal.Type.RECOVERED);
                    emit(ActivitySignal.Type.RECOVERED, next);
                }
            }
            pendingRecovery.remove(id);
        }
    }
    public ActivityRun start(ActivityDefinition definition, Player participant) throws SQLException {
        requireThread(); Objects.requireNonNull(participant);
        if (!cleanup.containsKey(definition.id())) throw new IllegalStateException("Register cleanup before starting content");
        for (UUID pending : pendingRecovery)
            if (require(pending).definition().id().equals(definition.id()))
                throw new IllegalStateException("Recover interrupted runs before starting content");
        var region = regions.region(definition.regionId()).orElseThrow(() -> new IllegalArgumentException("Unknown activity region"));
        if (!definition.active() || !regions.operational(region) || !definition.entry().world().equals(region.world()))
            throw new IllegalArgumentException("Activity or region is unavailable");
        if (!participant.isOnline() || definition.requiredPermissions().stream().anyMatch(p -> !participant.hasPermission(p))
                || !entryAllowed.test(participant, definition)) throw new IllegalArgumentException("Activity access denied");
        for (ActivityRun running : store.running()) {
            if (running.participant().equals(participant.getUniqueId())
                    && running.definition().id().equals(definition.id()))
                throw new IllegalStateException("Participant already has a running activity");
        }
        ActivityRun run = new ActivityRun(UUID.randomUUID(), participant.getUniqueId(), definition, ActivityRun.State.RUNNING, 0, 1);
        store.insertWithSignal(run, ActivitySignal.Type.STARTED);
        emit(ActivitySignal.Type.STARTED, run); return run;
    }
    public ActivityRun progress(UUID runId, long absoluteProgress) throws SQLException {
        requireThread(); ActivityRun current = require(runId);
        if (absoluteProgress < 0) throw new IllegalArgumentException("Negative progress");
        if (current.state() != ActivityRun.State.RUNNING || absoluteProgress <= current.progress()) return current;
        requireRecovered(current);
        ActivityRun next = current.next(current.state(), absoluteProgress);
        save(next, current.revision(), ActivitySignal.Type.PROGRESS);
        emit(ActivitySignal.Type.PROGRESS, next); return next;
    }
    public ActivityRun complete(UUID runId) throws SQLException {
        requireThread(); ActivityRun run = require(runId);
        if (run.state() != ActivityRun.State.RUNNING) return run;
        requireRecovered(run); return terminal(run, ActivityRun.State.COMPLETED, ActivitySignal.Type.COMPLETED);
    }
    public ActivityRun cancel(UUID runId) throws SQLException {
        requireThread(); ActivityRun run = require(runId);
        if (run.state() != ActivityRun.State.RUNNING) return run;
        requireRecovered(run); return terminal(run, ActivityRun.State.CANCELLED, ActivitySignal.Type.CANCELLED);
    }
    public ActivityRun fail(UUID runId) throws SQLException {
        requireThread(); ActivityRun run = require(runId);
        if (run.state() != ActivityRun.State.RUNNING) return run;
        requireRecovered(run); return terminal(run, ActivityRun.State.FAILED, ActivitySignal.Type.FAILED);
    }
    public Optional<ActivityRun> checkpoint(UUID runId) throws SQLException { requireThread(); return store.find(runId); }
    public List<ActivityMember> members(UUID runId) throws SQLException {
        requireThread();
        ActivityRun run = require(runId);
        List<ActivityMember> members = store.members(runId);
        return members.isEmpty() ? List.of(new ActivityMember(run.participant(),
                run.state() == ActivityRun.State.RUNNING, run.progress())) : members;
    }
    public Instant startedAt(UUID runId) throws SQLException {
        requireThread(); return store.startedAt(runId);
    }
    public ActivityRun join(UUID runId, Player participant, int maxParticipants) throws SQLException {
        requireThread(); Objects.requireNonNull(participant);
        ActivityRun run = require(runId); requireRecovered(run);
        if (run.state() != ActivityRun.State.RUNNING) return run;
        if (!participant.isOnline() || run.definition().requiredPermissions().stream()
                .anyMatch(p -> !participant.hasPermission(p))
                || !entryAllowed.test(participant, run.definition()))
            throw new IllegalArgumentException("Activity access denied");
        ActivityRun next = store.join(runId, participant.getUniqueId(), maxParticipants);
        if (next.revision() != run.revision()) emit(ActivitySignal.Type.JOINED, next, participant.getUniqueId());
        return next;
    }
    public ActivityRun leave(UUID runId, UUID playerId) throws SQLException {
        requireThread(); Objects.requireNonNull(playerId);
        ActivityRun run = require(runId); requireRecovered(run);
        ActivityRun next = store.leave(runId, playerId);
        if (next.revision() != run.revision()) emit(ActivitySignal.Type.LEFT, next, playerId);
        return next;
    }
    public ActivityRun contribute(UUID runId, Player participant, UUID actionId,
                                  long amount, long perPlayerLimit) throws SQLException {
        requireThread(); Objects.requireNonNull(participant); Objects.requireNonNull(actionId);
        ActivityRun run = require(runId); requireRecovered(run);
        if (run.state() != ActivityRun.State.RUNNING) return run;
        if (!participant.isOnline() || run.definition().requiredPermissions().stream()
                .anyMatch(p -> !participant.hasPermission(p))
                || !entryAllowed.test(participant, run.definition()))
            throw new IllegalArgumentException("Activity access denied");
        ActivityRun next = store.contribute(runId, participant.getUniqueId(), actionId,
                amount, perPlayerLimit);
        if (next.revision() != run.revision()) emit(ActivitySignal.Type.CONTRIBUTED, next,
                participant.getUniqueId());
        return next;
    }
    /** Read-only pending intents for an approved GoldenRPG reward bridge. */
    public List<ActivityRewardIntent> rewardIntents(int limit) throws SQLException {
        requireThread();
        return store.rewardIntents(limit);
    }
    public List<ActivityRewardIntent> dispatchableRewardIntents(int limit, Set<String> contentIds)
            throws SQLException {
        requireThread();
        return store.dispatchableRewardIntents(limit, contentIds);
    }
    public boolean pinRewardPolicy(UUID requestId, String payload, boolean eligible) throws SQLException {
        requireThread(); return store.pinRewardPolicy(requestId, payload, eligible);
    }
    public boolean markRewardApplied(UUID requestId) throws SQLException {
        requireThread(); return store.markRewardApplied(requestId);
    }
    public boolean markRewardInvalid(UUID requestId) throws SQLException {
        requireThread(); return store.markRewardInvalid(requestId);
    }
    private void requireRecovered(ActivityRun run) {
        if (mutating.contains(run.runId())) throw new IllegalStateException("Activity transition is already in progress");
        if (pendingRecovery.contains(run.runId())) throw new IllegalStateException("Bind cleanup to recover this interrupted run first");
    }
    private ActivityRun require(UUID id) throws SQLException {
        return store.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown activity run"));
    }
    private ActivityRun terminal(ActivityRun current, ActivityRun.State state, ActivitySignal.Type type) throws SQLException {
        Consumer<ActivityRun> handler = cleanup.get(current.definition().id());
        if (handler == null) throw new IllegalStateException("Content cleanup is unavailable");
        if (!mutating.add(current.runId())) throw new IllegalStateException("Activity transition is already in progress");
        try {
            // Providers must tolerate retries if storage fails after cleanup or the server stops here.
            handler.accept(current);
            try {
                ActivityRun next = current.next(state, current.progress());
                save(next, current.revision(), type); emit(type, next); return next;
            } catch (SQLException | RuntimeException failure) {
                // A resumable provider must remain reachable for a same-process retry.
                Consumer<ActivityRun> resumeHandler = resume.get(current.definition().id());
                if (resumeHandler != null) {
                    try { resumeHandler.accept(current); }
                    catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
                }
                throw failure;
            }
        } finally { mutating.remove(current.runId()); }
    }
    private void save(ActivityRun run, long revision, ActivitySignal.Type type) throws SQLException {
        if (!store.updateWithSignal(run, revision, type)) throw new IllegalStateException("Activity changed concurrently");
    }
    private void emit(ActivitySignal.Type type, ActivityRun run) {
        emit(type, run, null);
    }
    private void emit(ActivitySignal.Type type, ActivityRun run, UUID actor) {
        try {
            if (store.hasSignalOutbox()) {
                dispatchPendingSignals(1000);
                return;
            }
            signals.accept(new ActivitySignal(actor == null ? ActivitySignal.VERSION
                    : ActivitySignal.GROUP_VERSION, type, run, actor));
            store.markSignalDelivered(run.runId(), run.revision());
        } catch (SQLException | RuntimeException ignored) {
            // The committed signal stays in the outbox for dispatchPendingSignals().
        }
    }
    public int dispatchPendingSignals(int limit) throws SQLException {
        requireThread();
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid signal batch size");
        int delivered = 0;
        for (ActivitySignal signal : store.pendingSignals(limit)) {
            try {
                signals.accept(signal);
                store.markSignalDelivered(signal.checkpoint().runId(), signal.checkpoint().revision());
                delivered++;
            } catch (SQLException | RuntimeException failure) { break; }
        }
        return delivered;
    }
    public void shutdown() throws SQLException {
        requireThread();
        closed = true; // Cleanup callbacks cannot start new runs during shutdown.
        SQLException failures = null;
        try {
            for (ActivityRun run : store.running()) {
                if (pendingRecovery.contains(run.runId())) continue;
                try {
                    if (resume.containsKey(run.definition().id())) cleanup.get(run.definition().id()).accept(run);
                    else terminal(run, ActivityRun.State.CANCELLED, ActivitySignal.Type.CANCELLED);
                }
                catch (SQLException | RuntimeException | LinkageError failure) {
                    if (failures == null) failures = new SQLException("Some activity runs require recovery after shutdown");
                    failures.addSuppressed(failure);
                }
            }
        } finally { cleanup.clear(); resume.clear(); }
        if (failures != null) throw failures;
    }
}
