package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.data.BossStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BossStoreTest {
    private static BossDefinition definition() {
        return new BossDefinition("technical_boss", "ConfiguredMythicMob", List.of(
                new BossDefinition.Phase("opening", 1),
                new BossDefinition.Phase("middle", 0.6),
                new BossDefinition.Phase("final", 0.25)));
    }

    @Test void phasesAndContributionsCommitOnceAndSurviveReconnection() throws Exception {
        var source = source(); BossStore store = new BossStore(source);
        UUID player = UUID.randomUUID(), ally = UUID.randomUUID();
        BossFight fight = new BossFight(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                definition(), BossFight.State.ACTIVE, 0, 1, 1);
        store.create(fight); store.join(fight.fightId(), player); store.join(fight.fightId(), ally);
        var action = new BossAction(UUID.randomUUID(), player, BossAction.Kind.DAMAGE, 15, 0.55);
        var first = store.record(fight.fightId(), action);
        assertEquals(1, first.fight().phaseIndex());
        assertTrue(first.phaseChanged());
        assertTrue(store.record(fight.fightId(), action).replayed());
        assertEquals(15, store.contribution(fight.fightId(), player).get(BossAction.Kind.DAMAGE));
        store.record(fight.fightId(), new BossAction(UUID.randomUUID(), ally, BossAction.Kind.HEAL, 8, null));
        assertEquals(8, new BossStore(source).contribution(fight.fightId(), ally).get(BossAction.Kind.HEAL));
        assertEquals(List.of(player), store.leaders(fight.fightId(), BossAction.Kind.DAMAGE, 10)
                .stream().map(me.calo.islands.data.BossRepository.Leader::playerId).toList());
        assertEquals(List.of(ally), store.leaders(fight.fightId(), BossAction.Kind.HEAL, 1)
                .stream().map(me.calo.islands.data.BossRepository.Leader::playerId).toList());
        assertThrows(IllegalArgumentException.class,
                () -> store.leaders(fight.fightId(), BossAction.Kind.DAMAGE, 101));
        var lethal = store.record(fight.fightId(), new BossAction(UUID.randomUUID(), player,
                BossAction.Kind.DAMAGE, 55, 0.0));
        assertTrue(lethal.completed());
        assertEquals(2, lethal.fight().phaseIndex());
        assertEquals(BossFight.State.COMPLETED, new BossStore(source).find(fight.fightId()).orElseThrow().state());
        try (Connection c = source.getConnection(); var q = c.prepareStatement(
                "SELECT COUNT(*) FROM calo_boss_reward_intents WHERE fight_id=?")) {
            q.setString(1, fight.fightId().toString());
            try (var rows = q.executeQuery()) { rows.next(); assertEquals(2, rows.getInt(1)); }
        }
        assertThrows(IllegalStateException.class, () -> store.record(fight.fightId(),
                new BossAction(UUID.randomUUID(), player, BossAction.Kind.DAMAGE, 1, 0.0)));
    }

    @Test void invalidParticipationAndFractionRollBackWithoutContributions() throws Exception {
        BossStore store = new BossStore(source());
        BossFight fight = new BossFight(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                definition(), BossFight.State.ACTIVE, 0, 1, 1);
        store.create(fight);
        UUID outsider = UUID.randomUUID();
        var action = new BossAction(UUID.randomUUID(), outsider, BossAction.Kind.DAMAGE, 4, 0.8);
        assertThrows(IllegalArgumentException.class, () -> store.record(fight.fightId(), action));
        assertEquals(1, store.find(fight.fightId()).orElseThrow().healthFraction());
        assertTrue(store.contribution(fight.fightId(), outsider).isEmpty());
        store.join(fight.fightId(), outsider);
        store.record(fight.fightId(), action);
        assertThrows(IllegalArgumentException.class, () -> store.record(fight.fightId(),
                new BossAction(UUID.randomUUID(), outsider, BossAction.Kind.DAMAGE, 2, 0.9)));
        assertEquals(0.8, store.find(fight.fightId()).orElseThrow().healthFraction());
        assertThrows(IllegalArgumentException.class, () -> store.record(fight.fightId(),
                new BossAction(action.actionId(), outsider, BossAction.Kind.DAMAGE, 5, 0.8)));
    }

    @Test void despawnIsTerminalAndCannotProduceRewardIntent() throws Exception {
        var source = source(); BossStore store = new BossStore(source);
        BossFight fight = new BossFight(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                definition(), BossFight.State.ACTIVE, 0, 1, 1);
        store.create(fight);
        assertEquals(BossFight.State.DESPAWNED,
                store.terminate(fight.fightId(), BossFight.State.DESPAWNED).state());
        assertEquals(BossFight.State.DESPAWNED,
                new BossStore(source).terminate(fight.fightId(), BossFight.State.DESPAWNED).state());
        assertTrue(store.active().isEmpty());
    }

    private static JdbcDataSource source() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:boss_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = source.getConnection(); var ddl = c.createStatement()) {
            ddl.execute("CREATE TABLE calo_boss_fights (fight_id CHAR(36) PRIMARY KEY, entity_id CHAR(36) UNIQUE, world_id CHAR(36), boss_id VARCHAR(64), mythic_mob_id VARCHAR(128), phase_rules TEXT, state VARCHAR(16), phase_index INT, health_fraction DOUBLE, revision BIGINT)");
            ddl.execute("CREATE TABLE calo_boss_participants (fight_id CHAR(36), player_id CHAR(36), PRIMARY KEY(fight_id,player_id))");
            ddl.execute("CREATE TABLE calo_boss_actions (sequence BIGINT AUTO_INCREMENT PRIMARY KEY, action_id CHAR(36) UNIQUE, fight_id CHAR(36), player_id CHAR(36), kind VARCHAR(32), amount DOUBLE, health_fraction DOUBLE)");
            ddl.execute("CREATE TABLE calo_boss_contributions (fight_id CHAR(36), player_id CHAR(36), kind VARCHAR(32), amount DOUBLE, PRIMARY KEY(fight_id,player_id,kind))");
            ddl.execute("CREATE TABLE calo_boss_reward_intents (request_id CHAR(36) PRIMARY KEY, fight_id CHAR(36), player_id CHAR(36), status VARCHAR(24) DEFAULT 'WAITING_POLICY', UNIQUE(fight_id,player_id))");
        }
        return source;
    }
}
