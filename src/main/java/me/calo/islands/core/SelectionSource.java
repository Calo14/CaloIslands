package me.calo.islands.core;

import me.calo.islands.domain.RegionSelectionService.Selection;
import org.bukkit.entity.Player;

/** A runtime selector. WorldEdit is preferred; the built-in wand is the fallback. */
public interface SelectionSource {
    final class UnsupportedShape extends RuntimeException { }
    Selection selection(Player player);
    void clear(Player player);
}
