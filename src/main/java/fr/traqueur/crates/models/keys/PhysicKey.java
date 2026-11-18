package fr.traqueur.crates.models.keys;

import fr.traqueur.crates.api.models.crates.Key;
import fr.traqueur.crates.api.settings.models.ItemStackWrapper;
import fr.traqueur.crates.api.serialization.Keys;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import fr.traqueur.crates.api.registries.ItemsProvidersRegistry;
import fr.traqueur.crates.api.registries.Registry;

import java.util.Optional;

public record PhysicKey(String name, ItemStackWrapper item) implements Key {

    private boolean matches(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        Optional<String> taggedKey = Keys.KEY_NAME.get(stack.getItemMeta().getPersistentDataContainer());
        if (taggedKey.isPresent()) return name.equals(taggedKey.get());
        var source = item.copyFrom();
        if (source == null) return false;
        var provider = Registry.get(ItemsProvidersRegistry.class).getById(source.pluginName());
        return provider != null && provider.matches(stack, source.itemId());
    }

    @Override
    public boolean has(Player player) {
        for (ItemStack itemStack : player.getInventory().getContents()) {
            if (matches(itemStack)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void remove(Player player) {
        for (ItemStack itemStack : player.getInventory().getContents()) {
            if (matches(itemStack)) {
                itemStack.setAmount(itemStack.getAmount() - 1);
                return;
            }
        }
    }

    @Override
    public void give(Player player) {
        ItemStack item = this.item.build(player);
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta != null) {
            PersistentDataContainer container = itemMeta.getPersistentDataContainer();
            Keys.KEY_NAME.set(container, this.name);
            item.setItemMeta(itemMeta);
        }
        player.getInventory().addItem(item).forEach((__, leftover) -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    @Override
    public int count(Player player) {
        int count = 0;
        for (ItemStack itemStack : player.getInventory().getContents()) {
            if (matches(itemStack)) {
                count += itemStack.getAmount();
            }
        }
        return count;
    }


}
