package fr.traqueur.crates.hooks.escagamesitems;

import fr.traqueur.crates.api.annotations.AutoHook;
import fr.traqueur.crates.api.hooks.Hook;
import fr.traqueur.crates.api.providers.ItemsProvider;
import fr.traqueur.crates.api.registries.ItemsProvidersRegistry;
import fr.traqueur.crates.api.registries.Registry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

@AutoHook("EscagamesItems")
public class EscaGamesItemsHook implements Hook {
    @Override
    public void onEnable() {
        // Resolve the optional service from the installed plugin, without bundling its private API.
        var plugin = Bukkit.getPluginManager().getPlugin("EscagamesItems");
        try {
            Class<?> api = plugin.getClass().getClassLoader()
                    .loadClass("fr.escagames.escagamesitems.manager.IItemConfigManager");
            Object manager = Bukkit.getServicesManager().load(api);
            if (manager == null) throw new IllegalStateException("EscagamesItems item service is unavailable");
            var provider = new Provider(manager, api.getMethod("buildItemStack", String.class, int.class, Player.class),
                    api.getMethod("getInternalId", ItemStack.class));
            Registry.get(ItemsProvidersRegistry.class).register("EscagamesItems", provider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Incompatible EscagamesItems item service", e);
        }
    }

    private record Provider(Object manager, Method buildItem, Method internalId) implements ItemsProvider {
        @Override
        public ItemStack item(Player player, String id) {
            return ItemStack.class.cast(invoke(buildItem, id, 1, player).orElse(null));
        }

        @Override
        public boolean matches(ItemStack item, String id) {
            return invoke(internalId, item).filter(id::equals).isPresent();
        }

        private Optional<?> invoke(Method method, Object... arguments) {
            try {
                return (Optional<?>) method.invoke(manager, arguments);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("EscagamesItems failed to " + method.getName(), e);
            }
        }
    }
}
