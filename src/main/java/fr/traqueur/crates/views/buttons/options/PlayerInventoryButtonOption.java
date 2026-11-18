package fr.traqueur.crates.views.buttons.options;

import fr.maxlego08.menu.api.ButtonManager;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.MenuItemStack;
import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.ButtonOption;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.utils.Loader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;

public class PlayerInventoryButtonOption implements ButtonOption {

    private final Plugin plugin;

    public PlayerInventoryButtonOption(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "player-inventory";
    }

    @Override
    public Plugin getPlugin() {
        return this.plugin;
    }

    @Override
    public void loadButton(Button button, YamlConfiguration configuration, String path, InventoryManager inventoryManager,
                           ButtonManager buttonManager, Loader<MenuItemStack> itemStackLoader, File file) {
        String prefix = path.endsWith(".") ? path : path + ".";

        boolean enabled = configuration.getBoolean(prefix + "player-inventory",
                configuration.getBoolean(prefix + "playerInventory",
                        configuration.getBoolean(prefix + "player_inventory",
                                configuration.getBoolean(path + "player-inventory",
                                        configuration.getBoolean(path + "playerInventory",
                                                configuration.getBoolean(path + "player_inventory", false))))));
        if (enabled) {
            button.setPlayerInventory(true);
        }
    }

    @Override
    public void onClick(Button button, Player player, InventoryClickEvent event, InventoryEngine inventory, int slot, boolean isLeftClick) {
    }
}
