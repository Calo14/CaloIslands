package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.core.ActivityService;
import me.calo.islands.domain.Destination;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityRewardBridgeTest {
    @Test void approvedIntentPinsPayloadAndOneInFlightRequestUsesStableUuid() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        ActivityService activities = mock(ActivityService.class);
        UUID playerId = UUID.randomUUID();
        ActivityRun run = new ActivityRun(UUID.randomUUID(), playerId,
                new ActivityDefinition("defense", ActivityDefinition.Kind.EVENT, "coast",
                        new Destination("world", 5, 64, 5, 0, 0), true, Set.of()),
                ActivityRun.State.COMPLETED, 5, 2);
        ActivityRewardIntent waiting = new ActivityRewardIntent(UUID.randomUUID(), run.runId(),
                playerId, "WAITING_POLICY", null);
        ActivityRewardRules.Rule rule = new ActivityRewardRules.Rule(3,
                List.of(new ActivityRewardRules.Line("EXPERIENCE", "", 10)));
        when(activities.dispatchableRewardIntents(1000, Set.of("defense")))
                .thenReturn(List.of(waiting));
        when(activities.checkpoint(run.runId())).thenReturn(java.util.Optional.of(run));
        when(activities.members(run.runId())).thenReturn(List.of(new ActivityMember(playerId, false, 5)));
        when(activities.pinRewardPolicy(waiting.requestId(), rule.payload(), true)).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ActivityRewardIntent> sent = new AtomicReference<>();
        CompletableFuture<String> result = new CompletableFuture<>();
        ActivityRewardBridge bridge = new ActivityRewardBridge(plugin, activities,
                Map.of("defense", rule), (intent, lines) -> {
                    calls.incrementAndGet(); sent.set(intent);
                    assertEquals(rule.rewards(), lines);
                    return result;
                });
        bridge.tick();
        bridge.tick();
        assertEquals(1, calls.get());
        assertEquals(waiting.requestId(), sent.get().requestId());
        assertEquals("PENDING", sent.get().status());
        assertEquals(rule.payload(), sent.get().payload());
        bridge.stop();
    }
}
