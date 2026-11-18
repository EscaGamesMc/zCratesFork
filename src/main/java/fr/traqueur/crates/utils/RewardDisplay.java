package fr.traqueur.crates.utils;

import fr.traqueur.crates.Messages;
import fr.traqueur.crates.api.models.crates.Crate;
import fr.traqueur.crates.api.models.crates.Reward;
import fr.traqueur.crates.api.providers.PlaceholderProvider;
import fr.traqueur.crates.api.services.ItemsService;
import fr.traqueur.crates.api.services.MessagesService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;

public final class RewardDisplay {

    private static final DecimalFormat CHANCE_FORMAT = new DecimalFormat("#.##");

    private RewardDisplay() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static List<Reward> sortedByRarity(Crate crate) {
        return crate.rewards().stream()
                .sorted(Comparator.comparingDouble(Reward::weight))
                .toList();
    }

    public static double totalWeight(Crate crate) {
        return crate.rewards().stream().mapToDouble(Reward::weight).sum();
    }

    public static ItemStack buildDisplayItem(Player player, Reward reward, double totalWeight) {
        ItemStack item = reward.displayItem().build(player);
        if (totalWeight <= 0) {
            return item;
        }
        String papiString = PlaceholderProvider.parsePlaceholders(player, Messages.CHANCE_REWARD_LORE_LINE.get());
        Component chanceComponent = MessagesService.parseMessage(papiString,
                Placeholder.parsed("chance", CHANCE_FORMAT.format((reward.weight() / totalWeight) * 100)));
        ItemsService.addLoreLine(item, chanceComponent);
        return item;
    }
}
