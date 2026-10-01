package me.calo.islands;

import me.calo.islands.content.ActivityRewardRules;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActivityRewardRulesTest {
    @Test void missingPolicyDoesNotCreateRewardsAndApprovedPayloadIsStable() throws Exception {
        assertTrue(ActivityRewardRules.read(null, Set.of("defense")).isEmpty());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                policy:
                  defense:
                    minimum-contribution: 5
                    rewards:
                      - kind: EXPERIENCE
                        amount: 10
                        resource-id: ''
                """);
        var rules = ActivityRewardRules.read(yaml.getConfigurationSection("policy"), Set.of("defense"));
        assertEquals(5, rules.get("defense").minimumContribution());
        assertEquals(rules.get("defense").rewards(),
                ActivityRewardRules.decode(rules.get("defense").payload()));
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRewardRules.read(yaml.getConfigurationSection("policy"), Set.of()));
    }
    @Test void incompleteEligibilityAndInvalidRewardFail() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                policy:
                  defense:
                    rewards:
                      - kind: MATERIAL
                        amount: 1
                        resource-id: invalid resource
                """);
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRewardRules.read(yaml.getConfigurationSection("policy"), Set.of("defense")));
        yaml.set("policy.defense.minimum-contribution", 1);
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRewardRules.read(yaml.getConfigurationSection("policy"), Set.of("defense")));
    }
    @Test void overflowingPolicyIntegersAreRejected() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                policy:
                  defense:
                    minimum-contribution: 1
                    rewards:
                      - kind: EXPERIENCE
                        amount: 1
                """);
        yaml.set("policy.defense.minimum-contribution", 1.0e30);
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRewardRules.read(yaml.getConfigurationSection("policy"), Set.of("defense")));
        yaml.set("policy.defense.minimum-contribution", 1);
        yaml.set("policy.defense.rewards", java.util.List.of(java.util.Map.of(
                "kind", "EXPERIENCE", "amount", 1.0e30)));
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRewardRules.read(yaml.getConfigurationSection("policy"), Set.of("defense")));
    }
}
