package me.calo.islands.content;

import me.calo.islands.core.ActivityService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Sends only policy-pinned intents through GoldenRPG's registered public RewardService. */
public final class ActivityRewardBridge {
    @FunctionalInterface public interface Client {
        /** Null means GoldenRPG is unavailable; a completed status mirrors RewardService.Status. */
        CompletableFuture<String> reward(ActivityRewardIntent intent, List<ActivityRewardRules.Line> lines);
    }
    private final JavaPlugin plugin;
    private final ActivityService activities;
    private final Map<String, ActivityRewardRules.Rule> rules;
    private final Client client;
    private final Set<UUID> inFlight = new HashSet<>();
    private boolean stopped;

    public ActivityRewardBridge(JavaPlugin plugin, ActivityService activities,
                                Map<String, ActivityRewardRules.Rule> rules, Client client) {
        this.plugin = Objects.requireNonNull(plugin);
        this.activities = Objects.requireNonNull(activities);
        this.rules = Map.copyOf(rules);
        this.client = Objects.requireNonNull(client);
    }
    public void stop() { stopped = true; inFlight.clear(); }
    public void tick() throws SQLException {
        if (stopped) return;
        for (ActivityRewardIntent intent : activities.dispatchableRewardIntents(1000, rules.keySet())) {
            if (intent.status().equals("WAITING_POLICY")) {
                ActivityRun run = activities.checkpoint(intent.runId()).orElse(null);
                if (run == null || run.state() != ActivityRun.State.COMPLETED) continue;
                ActivityRewardRules.Rule rule = rules.get(run.definition().id());
                if (rule == null) continue;
                UUID playerId = intent.playerId();
                long contribution = activities.members(run.runId()).stream()
                        .filter(member -> member.playerId().equals(playerId))
                        .mapToLong(ActivityMember::contribution).findFirst().orElse(0);
                boolean eligible = contribution >= rule.minimumContribution();
                if (!activities.pinRewardPolicy(intent.requestId(), rule.payload(), eligible)) continue;
                if (!eligible) continue;
                intent = new ActivityRewardIntent(intent.requestId(), intent.runId(), intent.playerId(),
                        "PENDING", rule.payload());
            }
            if (!intent.status().equals("PENDING") || !inFlight.add(intent.requestId())) continue;
            CompletableFuture<String> result;
            try { result = client.reward(intent, ActivityRewardRules.decode(intent.payload())); }
            catch (RuntimeException failure) {
                inFlight.remove(intent.requestId());
                plugin.getLogger().log(Level.WARNING, "GoldenRPG reward dispatch unavailable", failure);
                continue;
            }
            if (result == null) { inFlight.remove(intent.requestId()); continue; }
            UUID requestId = intent.requestId();
            result.whenComplete((status, failure) -> {
                if (stopped) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    inFlight.remove(requestId);
                    if (stopped) return;
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING, "GoldenRPG reward unconfirmed", failure);
                        return;
                    }
                    try {
                        if ("APPLIED".equals(status) || "ALREADY_APPLIED".equals(status))
                            activities.markRewardApplied(requestId);
                        else if ("INVALID".equals(status)) {
                            activities.markRewardInvalid(requestId);
                            plugin.getLogger().warning("GoldenRPG rejected activity reward policy for " + requestId);
                        }
                    } catch (SQLException issue) {
                        plugin.getLogger().log(Level.WARNING, "Activity reward acknowledgement unconfirmed", issue);
                    }
                });
            });
        }
    }

    /** Loads the public API through GoldenRPG's plugin classloader; CaloIslands embeds no Golden classes. */
    public static Client goldenClient(JavaPlugin plugin) {
        return (intent, lines) -> {
            var golden = Bukkit.getPluginManager().getPlugin("GoldenRPG");
            if (golden == null || !golden.isEnabled()) return null;
            try {
                ClassLoader loader = golden.getClass().getClassLoader();
                Class<?> api = Class.forName("com.goldenlyons.goldenrpg.api.RewardService", true, loader);
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) api);
                if (registration == null) return null;
                Class<?> rewardType = Class.forName("com.goldenlyons.goldenrpg.api.RewardService$Reward", true, loader);
                Class<?> requestType = Class.forName("com.goldenlyons.goldenrpg.api.RewardService$Request", true, loader);
                Constructor<?> rewardConstructor = rewardType.getConstructor(String.class, String.class, long.class);
                List<Object> rewards = new ArrayList<>();
                for (ActivityRewardRules.Line line : lines)
                    rewards.add(rewardConstructor.newInstance(line.kind(), line.resourceId(), line.amount()));
                Object request = requestType.getConstructor(UUID.class, String.class, String.class,
                        String.class, List.class).newInstance(intent.playerId(), "CALOISLANDS",
                        intent.runId().toString(), intent.requestId().toString(), rewards);
                Method reward = api.getMethod("reward", requestType);
                CompletableFuture<?> future = (CompletableFuture<?>) reward.invoke(registration.getProvider(), request);
                return future.thenApply(result -> {
                    try { return result.getClass().getMethod("status").invoke(result).toString(); }
                    catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                });
            } catch (ReflectiveOperationException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING, "GoldenRPG reward API unavailable", failure);
                return null;
            }
        };
    }
}
