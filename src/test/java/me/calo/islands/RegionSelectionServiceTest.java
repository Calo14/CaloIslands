package me.calo.islands;

import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.RegionSelectionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class RegionSelectionServiceTest {
    @Test
    void positionsAreIndependentPerPlayerAndNormalizeAllAxes() {
        RegionSelectionService selections = new RegionSelectionService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        selections.setFirst(first, "test_world", 9, 80, 7);
        assertFalse(selections.get(first).orElseThrow().complete());
        selections.setSecond(first, "test_world", -2, 60, -4);
        assertEquals(new Bounds(-2, 60, -4, 9, 80, 7), selections.get(first).orElseThrow().bounds());
        assertEquals("12x21x12", selections.get(first).orElseThrow().dimensions());
        assertTrue(selections.get(second).isEmpty());
        selections.setSecond(second, "test_world", 1, 2, 3);
        assertFalse(selections.get(second).orElseThrow().complete());
        selections.clear(first);
        assertTrue(selections.get(first).isEmpty());
        assertTrue(selections.get(second).isPresent());
    }

    @Test
    void rejectsIncompleteAndCrossWorldSelections() {
        RegionSelectionService selections = new RegionSelectionService();
        UUID player = UUID.randomUUID();
        selections.setSecond(player, "world_b", 1, 2, 3);
        assertThrows(IllegalStateException.class, () -> selections.get(player).orElseThrow().bounds());
        selections.setFirst(player, "world_a", 5, 6, 7);
        assertFalse(selections.get(player).orElseThrow().sameWorld());
        assertThrows(IllegalStateException.class, () -> selections.get(player).orElseThrow().bounds());
    }

    @Test
    void previewStartsEnabledAndClearResetsManualToggle() {
        RegionSelectionService selections = new RegionSelectionService();
        UUID player = UUID.randomUUID();
        assertTrue(selections.previewEnabled(player));
        assertFalse(selections.togglePreview(player));
        assertFalse(selections.previewEnabled(player));
        assertTrue(selections.togglePreview(player));
        selections.togglePreview(player);
        selections.clear(player);
        assertTrue(selections.previewEnabled(player));
    }

    @Test
    void fullHeightUsesWorldLimitsAndExactKeepsSelectedY() {
        RegionSelectionService selections = new RegionSelectionService();
        UUID player = UUID.randomUUID();
        selections.setFirst(player, "test_world", 9, 70, 7);
        selections.setSecond(player, "test_world", -2, 70, -4);
        var selection = selections.get(player).orElseThrow();
        assertEquals(RegionSelectionService.Mode.FULLHEIGHT, selections.mode(player));
        assertEquals(new Bounds(-2, -40, -4, 9, 199, 7),
                selection.effectiveBounds(selections.mode(player), -40, 200));
        assertEquals("12x240x12", selection.dimensions(selection.effectiveBounds(
                selections.mode(player), -40, 200)));
        selections.setMode(player, RegionSelectionService.Mode.EXACT);
        assertEquals(new Bounds(-2, 70, -4, 9, 70, 7),
                selection.effectiveBounds(selections.mode(player), -40, 200));
        selections.clear(player);
        assertEquals(RegionSelectionService.Mode.FULLHEIGHT, selections.mode(player));
    }
}
