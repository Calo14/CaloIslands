package me.calo.islands.core;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/** Identifies the admin wand by PDC marker, independent of its material or display name. */
public final class SelectionTool {
    private final Material material;
    private final Messages messages;
    private final NamespacedKey marker;

    public SelectionTool(JavaPlugin plugin, String materialName, Messages messages) {
        this.messages = Objects.requireNonNull(messages);
        this.marker = new NamespacedKey(plugin, "region_wand");
        try {
            this.material = Material.valueOf(Objects.requireNonNull(materialName).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid wand.material: " + materialName);
        }
        if (!material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("wand.material must be a usable item");
        }
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalStateException("Wand material has no item metadata");
        meta.setDisplayName(messages.text("wand-name"));
        meta.setLore(messages.lines("wand-lore"));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && Byte.valueOf((byte) 1).equals(
                meta.getPersistentDataContainer().get(marker, PersistentDataType.BYTE));
    }
}
