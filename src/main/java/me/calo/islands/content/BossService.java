package me.calo.islands.content;

import me.calo.islands.data.BossRepository;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Main-thread lifecycle controller. Damage arrives only after GoldenRPG confirms it. */
public final class BossService {
    public enum EntityState { ALIVE, DEAD, UNLOADED }
    public interface EntityAdapter {
        EntityState state(UUID worldId, UUID entityId);
        String templateId(UUID worldId, UUID entityId);
        void phase(BossFight fight, BossDefinition.Phase phase);
        void cleanup(BossFight fight);
    }

    private final BossRepository repository;
    private final EntityAdapter entities;
    private final Consumer<BossSignal> signals;
    private final Map<UUID, BossFight> active = new HashMap<>();
    private final java.util.function.BooleanSupplier primaryThread;

    public BossService(BossRepository repository, EntityAdapter entities,
                       Consumer<BossSignal> signals,
                       java.util.function.BooleanSupplier primaryThread) {
        this.repository = Objects.requireNonNull(repository);
        this.entities = Objects.requireNonNull(entities);
        this.signals = Objects.requireNonNull(signals);
        this.primaryThread = Objects.requireNonNull(primaryThread);
    }

    private void requireThread() {
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Boss API requires server thread");
    }

    public void recover() throws SQLException {
        requireThread();
        for (BossFight fight : repository.active()) {
            active.put(fight.fightId(), fight);
            EntityState state = entities.state(fight.worldId(), fight.entityId());
            if (state == EntityState.DEAD) {
                BossFight ended = repository.terminate(fight.fightId(), BossFight.State.DESPAWNED);
                active.remove(fight.fightId());
                entities.cleanup(ended);
                signal(BossSignal.Type.DESPAWNED, ended);
            } else if (state == EntityState.ALIVE) {
                if (!fight.definition().mythicMobId().equals(
                        entities.templateId(fight.worldId(), fight.entityId()))) {
                    BossFight ended = repository.terminate(fight.fightId(), BossFight.State.DESPAWNED);
                    active.remove(fight.fightId());
                    entities.cleanup(ended);
                    signal(BossSignal.Type.DESPAWNED, ended);
                    continue;
                }
                entities.phase(fight, fight.definition().phases().get(fight.phaseIndex()));
                signal(BossSignal.Type.RECOVERED, fight);
            }
        }
    }

    /** Bind a MythicMobs entity after its adapter verifies the configured template. */
    public BossFight bind(UUID fightId, UUID entityId, UUID worldId,
                          BossDefinition definition) throws SQLException {
        requireThread();
        if (entities.state(worldId, entityId) != EntityState.ALIVE)
            throw new IllegalArgumentException("Boss entity is not loaded and alive");
        if (!definition.mythicMobId().equals(entities.templateId(worldId, entityId)))
            throw new IllegalArgumentException("Boss entity template mismatch");
        BossFight fight = new BossFight(fightId, entityId, worldId, definition,
                BossFight.State.ACTIVE, 0, 1, 1);
        repository.create(fight);
        active.put(fightId, fight);
        entities.phase(fight, definition.phases().getFirst());
        signal(BossSignal.Type.STARTED, fight);
        return fight;
    }

    public void join(UUID fightId, UUID playerId) throws SQLException {
        requireThread();
        if (!active.containsKey(fightId)) throw new IllegalArgumentException("Boss fight is not active");
        repository.join(fightId, playerId);
    }

    public BossRepository.Result confirmed(UUID fightId, BossAction action) throws SQLException {
        requireThread();
        BossRepository.Result result = repository.record(fightId, action);
        BossFight fight = result.fight();
        if (!result.replayed()) {
            if (result.phaseChanged() && !result.completed()) {
                entities.phase(fight, fight.definition().phases().get(fight.phaseIndex()));
                signal(BossSignal.Type.PHASE, fight);
            }
            if (result.completed()) {
                active.remove(fightId);
                entities.cleanup(fight);
                signal(BossSignal.Type.COMPLETED, fight);
            } else active.put(fightId, fight);
        }
        return result;
    }

    public BossFight cancel(UUID fightId) throws SQLException {
        requireThread();
        BossFight result = repository.terminate(fightId, BossFight.State.CANCELLED);
        BossFight previous = active.remove(fightId);
        if (previous != null && result.state() == BossFight.State.CANCELLED) {
            entities.cleanup(result);
            signal(BossSignal.Type.CANCELLED, result);
        }
        return result;
    }

    /** Unloaded chunks are deferred; truly missing entities terminate without reward. */
    public void reconcile() throws SQLException {
        requireThread();
        for (BossFight fight : List.copyOf(active.values())) {
            EntityState state = entities.state(fight.worldId(), fight.entityId());
            if (state == EntityState.UNLOADED) continue;
            if (state == EntityState.DEAD || (state == EntityState.ALIVE
                    && !fight.definition().mythicMobId().equals(
                    entities.templateId(fight.worldId(), fight.entityId())))) {
                BossFight ended = repository.terminate(fight.fightId(), BossFight.State.DESPAWNED);
                active.remove(fight.fightId());
                entities.cleanup(ended);
                signal(BossSignal.Type.DESPAWNED, ended);
            }
        }
    }

    public void shutdown() {
        requireThread();
        // Keep the persisted Mythic entity for reconnection/restart recovery.
        active.clear();
    }

    private void signal(BossSignal.Type type, BossFight fight) {
        signals.accept(new BossSignal(BossSignal.VERSION, type, fight));
    }
}
