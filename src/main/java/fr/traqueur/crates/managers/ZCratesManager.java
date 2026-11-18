package fr.traqueur.crates.managers;

import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import fr.traqueur.crates.animations.AnimationExecutor;
import fr.traqueur.crates.api.Logger;
import fr.traqueur.crates.api.events.*;
import fr.traqueur.crates.api.managers.CratesManager;
import fr.traqueur.crates.api.models.crates.Condition;
import fr.traqueur.crates.api.models.crates.OpenResult;
import fr.traqueur.crates.api.managers.UsersManager;
import fr.traqueur.crates.api.models.CrateOpening;
import fr.traqueur.crates.api.models.User;
import fr.traqueur.crates.api.models.crates.Crate;
import fr.traqueur.crates.api.models.animations.Animation;
import fr.traqueur.crates.api.models.animations.AnimationContext;
import fr.traqueur.crates.api.models.animations.HookContext;
import fr.traqueur.crates.api.models.crates.Reward;
import fr.traqueur.crates.api.models.placedcrates.CrateDisplay;
import fr.traqueur.crates.api.models.placedcrates.CrateDisplayFactory;
import fr.traqueur.crates.api.models.placedcrates.DisplayType;
import fr.traqueur.crates.api.models.placedcrates.PlacedCrate;
import fr.traqueur.crates.api.registries.CrateDisplayFactoriesRegistry;
import fr.traqueur.crates.api.registries.CratesRegistry;
import fr.traqueur.crates.api.registries.Registry;
import fr.traqueur.crates.listeners.CratesListener;
import fr.traqueur.crates.listeners.RollingPreviewListener;
import fr.traqueur.crates.models.placedcrates.EntityCrateDisplay;
import fr.traqueur.crates.models.wrappers.CrateWrapper;
import fr.traqueur.crates.models.wrappers.InventoryWrapper;
import fr.traqueur.crates.models.wrappers.PlayerWrapper;
import fr.traqueur.crates.api.serialization.Keys;
import fr.traqueur.crates.api.settings.Settings;
import fr.traqueur.crates.settings.PluginSettings;
import fr.traqueur.crates.utils.RewardDisplay;
import fr.traqueur.crates.views.CrateMenu;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ZCratesManager implements CratesManager {

    private final InventoryManager inventoryManager;
    private final AnimationExecutor animationExecutor;
    private final Map<UUID, OpenedCrate> openingCrates;
    private final Map<UUID, Crate> previewingCrates;
    private final Map<UUID, PendingReward> pendingRewards;

    // Placed crates management - single cache combining data and display
    private final Map<Location, ActivePlacedCrate> placedCratesCache;

    private static final List<Integer> ROLLING_PREVIEW_SLOTS = IntStream.concat(
            IntStream.rangeClosed(9, 35),
            IntStream.rangeClosed(0, 8)
    ).boxed().toList();

    private static final long REWARD_DELIVERY_DELAY = 2L;

    @Override
    public void init() {
        this.getPlugin().registerListener(new CratesListener(this));
        this.getPlugin().registerListener(new RollingPreviewListener(this));
        this.ensureInventoriesExist();

        // Delay placed crates loading to ensure MythicMobs/ModelEngine are fully initialized
        // This prevents display issues with custom models on server restart
        Bukkit.getScheduler().runTaskLater(this.getPlugin(), () -> {
            this.loadAllPlacedCrates();
            Logger.debug("Placed crates loaded after initialization delay");
        }, 40L); // 2 seconds delay (40 ticks)
    }

    private static class OpenedCrate {
        protected final Crate crate;
        protected final Animation animation;
        protected UUID animationId;
        protected Reward currentReward;
        protected int rerollsRemaining;
        protected boolean animationCompleted;
        // Stored for reroll to restart animation
        protected Inventory inventory;
        protected List<Integer> slots;
        protected ItemStack[] savedContents;
        protected final List<ItemStack> previewItems = new ArrayList<>();

        public OpenedCrate(Crate crate, Animation animation) {
            this.crate = crate;
            this.animation = animation;
            this.rerollsRemaining = crate.maxRerolls();
            this.animationCompleted = false;
        }
    }

    private record ActivePlacedCrate(PlacedCrate data, CrateDisplay<?> display) {}

    private record PendingReward(Crate crate, Reward reward) {}

    public ZCratesManager(InventoryManager inventoryManager) {
        this.animationExecutor = new AnimationExecutor(this.getPlugin());
        this.inventoryManager = inventoryManager;
        this.openingCrates = new ConcurrentHashMap<>();
        this.previewingCrates = new ConcurrentHashMap<>();
        this.pendingRewards = new ConcurrentHashMap<>();
        this.placedCratesCache = new ConcurrentHashMap<>();
    }

    @Override
    public OpenResult tryOpenCrate(Player player, Crate crate) {
        if(this.openingCrates.containsKey(player.getUniqueId())) {
            return OpenResult.alreadyOpening();
        }

        if (!crate.key().has(player)) {
            return OpenResult.noKey();
        }

        // Check all conditions
        for (Condition condition : crate.conditions()) {
            if (!condition.check(player, crate)) {
                return OpenResult.conditionFailed(condition);
            }
        }

        // Fire pre-open event (cancellable)
        CratePreOpenEvent preOpenEvent = new CratePreOpenEvent(player, crate);
        Bukkit.getPluginManager().callEvent(preOpenEvent);
        if (preOpenEvent.isCancelled()) {
            return OpenResult.eventCancelled();
        }

        crate.key().remove(player);

        // Call onOpen for all conditions (e.g., set cooldown)
        for (Condition condition : crate.conditions()) {
            condition.onOpen(player, crate);
        }

        this.openCrate(player, crate, crate.animation());
        return OpenResult.success();
    }

    @Override
    public void openCrate(Player player, Crate crate, Animation animation) {
        if (crate.instantReward()) {
            this.giveInstantReward(player, crate, animation);
            return;
        }

        if(this.openingCrates.containsKey(player.getUniqueId())) {
            return;
        }
        this.deliverPendingReward(player);
        OpenedCrate openedCrate = new OpenedCrate(crate, animation);
        this.openingCrates.put(player.getUniqueId(), openedCrate);
        if (animation.usesInventory()) {
            this.inventoryManager.openInventory(player, this.getPlugin(), crate.relatedMenu());
        }

        // Fire open event (after menu is opened)
        CrateOpenEvent openEvent = new CrateOpenEvent(player, crate, animation);
        Bukkit.getPluginManager().callEvent(openEvent);

        // No menu to start it: an animation without inventory starts right away
        if (!animation.usesInventory()) {
            this.runAnimation(player, openedCrate, null, List.of());
        }
    }

    /**
     * Rolls and grants the reward straight away, without opening any menu or
     * playing an animation. Fires the same events as a regular opening so that
     * listeners behave identically, minus the reroll-related ones.
     */
    private void giveInstantReward(Player player, Crate crate, Animation animation) {
        CrateOpenEvent openEvent = new CrateOpenEvent(player, crate, animation);
        Bukkit.getPluginManager().callEvent(openEvent);

        UsersManager usersManager = this.getPlugin().getManager(UsersManager.class);
        User user = usersManager.getUser(player.getUniqueId());

        Reward reward = crate.generateReward(user);

        RewardGeneratedEvent rewardEvent = new RewardGeneratedEvent(player, crate, reward, false);
        Bukkit.getPluginManager().callEvent(rewardEvent);

        CrateOpening crateOpening = user.addCrateOpening(crate.id(), reward.id());
        usersManager.persistCrateOpening(crateOpening);

        reward.give(player);
        Logger.debug("Player {} received instant reward {} from crate {}", player.getName(), reward.id(), crate.id());

        RewardGivenEvent givenEvent = new RewardGivenEvent(player, crate, reward);
        Bukkit.getPluginManager().callEvent(givenEvent);
    }

    @Override
    public void openPreview(Player player, Crate crate) {
        CratePreviewEvent previewEvent = new CratePreviewEvent(player, crate);
        Bukkit.getPluginManager().callEvent(previewEvent);
        if (previewEvent.isCancelled()) {
            return;
        }

        var preview = Optional.ofNullable(crate.relatedMenu())
                .flatMap(menu -> this.inventoryManager.getInventory(this.getPlugin(), menu + "-preview"))
                .or(() -> this.inventoryManager.getInventory(this.getPlugin(), "crate-preview"));
        if (preview.isEmpty()) {
            Logger.warning("No preview inventory loaded for crate '{}'", crate.id());
            return;
        }
        this.previewingCrates.put(player.getUniqueId(), crate);
        this.inventoryManager.openInventory(player, preview.get());
    }

    @Override
    public Optional<Crate> getPreviewingCrate(Player player) {
        return Optional.ofNullable(this.previewingCrates.get(player.getUniqueId()));
    }

    @Override
    public void closePreview(Player player) {
        this.previewingCrates.remove(player.getUniqueId());
    }

    @Override
    public void startAnimation(Player player, Inventory inventory, List<Integer> slots) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());

        // Store inventory and slots for potential reroll
        openedCrate.inventory = inventory;
        openedCrate.slots = slots;

        this.runAnimation(player, openedCrate, inventory, slots);
    }

    private void runAnimation(Player player, OpenedCrate openedCrate, Inventory inventory, List<Integer> slots) {
        // Determine if this is a reroll (animation already started once before)
        boolean isReroll = openedCrate.animationId != null;

        openedCrate.animationCompleted = false;

        PlayerWrapper playerWrapper = new PlayerWrapper(player);
        Crate crate = openedCrate.crate;

        // Get user before generating reward (needed for algorithm context)
        UsersManager usersManager = this.getPlugin().getManager(UsersManager.class);
        User user = usersManager.getUser(player.getUniqueId());

        // Generate reward using the algorithm with user context
        Reward reward = crate.generateReward(user);
        openedCrate.currentReward = reward;

        // Fire reward generated event
        RewardGeneratedEvent rewardEvent = new RewardGeneratedEvent(player, crate, reward, isReroll);
        Bukkit.getPluginManager().callEvent(rewardEvent);

        // Log the crate opening (dual-write: memory + immediate async DB persist)
        CrateOpening crateOpening = user.addCrateOpening(crate.id(), reward.id());
        usersManager.persistCrateOpening(crateOpening);

        if (inventory != null) {
            this.showRollingPreview(player, openedCrate);
        }

        InventoryWrapper inventoryWrapper = inventory == null ? null : new InventoryWrapper(this.getPlugin(), player, crate, inventory, slots);
        // Pass supplier to get live rerolls remaining count
        CrateWrapper crateWrapper = new CrateWrapper(crate, player, reward, () -> openedCrate.rerollsRemaining);

        // Holds this run's id: a hook completing a stale run (after a reroll) must not end the new one
        UUID[] runId = new UUID[1];
        HookContext hookContext = new HookContext(player, crate, reward, () -> {
            if (runId[0] != null) {
                this.animationExecutor.completeAnimation(runId[0]);
            }
        });

        // startAnimation runs the first phase's onStart before returning: set the ids first,
        // so a hook may complete the animation from there
        UUID animationId = UUID.randomUUID();
        runId[0] = animationId;
        openedCrate.animationId = animationId;
        this.animationExecutor.startAnimation(animationId, openedCrate.animation, new AnimationContext(playerWrapper, inventoryWrapper, crateWrapper, hookContext), () -> {
            openedCrate.animationCompleted = true;
            this.hideRollingPreview(player, openedCrate);
            // Without a menu to close, the reward is given as soon as the animation ends
            if (!openedCrate.animation.usesInventory()) {
                this.closeCrate(player);
            }
        });
    }

    private void showRollingPreview(Player player, OpenedCrate openedCrate) {
        Crate crate = openedCrate.crate;
        if (!crate.rollingPreview() || crate.rewards().isEmpty()) {
            return;
        }

        PlayerInventory playerInventory = player.getInventory();
        ItemStack[] contents = playerInventory.getStorageContents();
        if (openedCrate.savedContents == null) {
            openedCrate.savedContents = Arrays.stream(contents)
                    .map(item -> item == null ? null : item.clone())
                    .toArray(ItemStack[]::new);
        }

        try {
            double totalWeight = RewardDisplay.totalWeight(crate);
            List<Reward> rewards = RewardDisplay.sortedByRarity(crate);

            playerInventory.setStorageContents(new ItemStack[contents.length]);
            openedCrate.previewItems.clear();
            for (int index = 0; index < Math.min(rewards.size(), ROLLING_PREVIEW_SLOTS.size()); index++) {
                ItemStack item = RewardDisplay.buildDisplayItem(player, rewards.get(index), totalWeight);
                openedCrate.previewItems.add(item);
                playerInventory.setItem(ROLLING_PREVIEW_SLOTS.get(index), item);
            }
            player.updateInventory();

            if (rewards.size() > ROLLING_PREVIEW_SLOTS.size()) {
                Logger.debug("Crate {} has {} rewards, only the {} rarest fit the rolling preview",
                        crate.id(), rewards.size(), ROLLING_PREVIEW_SLOTS.size());
            }
        } catch (Exception exception) {
            Logger.severe("Failed to display the rolling preview of crate '" + crate.id() + "', giving the inventory back", exception);
            this.hideRollingPreview(player, openedCrate);
        }
    }

    private void hideRollingPreview(Player player, OpenedCrate openedCrate) {
        if (openedCrate.savedContents == null) {
            return;
        }
        player.getInventory().setStorageContents(openedCrate.savedContents);
        openedCrate.savedContents = null;
        openedCrate.previewItems.clear();
        player.updateInventory();
    }

    public void handleDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        if (openedCrate == null || openedCrate.savedContents == null) {
            return;
        }

        ItemStack[] saved = openedCrate.savedContents;
        if (event.getKeepInventory()) {
            player.getInventory().setStorageContents(saved);
            player.updateInventory();
        } else {
            List<ItemStack> drops = event.getDrops();
            openedCrate.previewItems.forEach(drops::remove);
            Arrays.stream(saved).filter(Objects::nonNull).forEach(drops::add);
        }

        openedCrate.savedContents = null;
        openedCrate.previewItems.clear();
    }

    public boolean isRollingPreviewShown(Player player) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        return openedCrate != null && openedCrate.savedContents != null;
    }

    @Override
    public int batchOpenCrate(Player player, Crate crate) {
        int effectiveMax = crate.maxBatchSize() > 0
                ? crate.maxBatchSize()
                : Settings.get(PluginSettings.class).maxBatchSize();
        return this.batchOpenCrate(player, crate, effectiveMax);
    }

    @Override
    public int batchOpenCrate(Player player, Crate crate, int amount) {
        int effectiveMax = crate.maxBatchSize() > 0
                ? crate.maxBatchSize()
                : Settings.get(PluginSettings.class).maxBatchSize();
        if (effectiveMax <= 0) return 0;
        int toOpen = Math.min(amount, effectiveMax);
        UsersManager usersManager = this.getPlugin().getManager(UsersManager.class);
        User user = usersManager.getUser(player.getUniqueId());
        int opened = 0;

        for (int i = 0; i < toOpen; i++) {
            if (!crate.key().has(player)) break;

            for (Condition condition : crate.conditions()) {
                if (!condition.check(player, crate)) return opened;
            }

            CratePreOpenEvent preOpenEvent = new CratePreOpenEvent(player, crate);
            Bukkit.getPluginManager().callEvent(preOpenEvent);
            if (preOpenEvent.isCancelled()) break;

            crate.key().remove(player);
            for (Condition condition : crate.conditions()) {
                condition.onOpen(player, crate);
            }

            Reward reward = crate.generateReward(user);

            RewardGeneratedEvent rewardEvent = new RewardGeneratedEvent(player, crate, reward, false);
            Bukkit.getPluginManager().callEvent(rewardEvent);

            CrateOpening crateOpening = user.addCrateOpening(crate.id(), reward.id());
            usersManager.persistCrateOpening(crateOpening);

            reward.give(player);
            Bukkit.getPluginManager().callEvent(new RewardGivenEvent(player, crate, reward));

            opened++;
        }

        Logger.debug("Player {} batch opened {} x crate '{}'", player.getName(), opened, crate.id());
        return opened;
    }

    @Override
    public boolean canReroll(Player player) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        if (openedCrate == null) {
            return false;
        }
        return openedCrate.animationCompleted && openedCrate.rerollsRemaining > 0;
    }

    @Override
    public int getRerollsRemaining(Player player) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        if (openedCrate == null) {
            return 0;
        }
        return openedCrate.rerollsRemaining;
    }

    @Override
    public Optional<Reward> getCurrentReward(Player player) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        if (openedCrate == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(openedCrate.currentReward);
    }

    @Override
    public boolean reroll(Player player) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        if (openedCrate == null || !openedCrate.animationCompleted || openedCrate.rerollsRemaining <= 0) {
            return false;
        }

        // Fire reroll event (cancellable)
        CrateRerollEvent rerollEvent = new CrateRerollEvent(player, openedCrate.crate, openedCrate.currentReward, openedCrate.rerollsRemaining - 1);
        Bukkit.getPluginManager().callEvent(rerollEvent);
        if (rerollEvent.isCancelled()) {
            return false;
        }

        // Decrement rerolls
        openedCrate.rerollsRemaining--;

        // Cancel current animation if still running
        if (openedCrate.animationId != null && this.animationExecutor.isRunning(openedCrate.animationId)) {
            this.animationExecutor.cancelAnimation(openedCrate.animationId);
        }

        Logger.debug("Player {} rerolling ({} rerolls remaining)", player.getName(), openedCrate.rerollsRemaining);

        // Restart the full animation with a new reward
        this.runAnimation(player, openedCrate, openedCrate.inventory, openedCrate.slots);

        return true;
    }


    @Override
    public boolean isAnimationCompleted(Player player) {
        OpenedCrate openedCrate = this.openingCrates.get(player.getUniqueId());
        return openedCrate != null && openedCrate.animationCompleted;
    }

    @Override
    public void stopAllOpening() {
        // Cancelling an animation without inventory closes its crate, which gives the reward;
        // the ones left below are given when their menu closes
        this.animationExecutor.cancelAll();
        this.openingCrates.forEach((uuid, openedCrate) -> {
            Player player = this.getPlugin().getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        });
        Bukkit.getOnlinePlayers().forEach(this::deliverPendingReward);
    }

    @Override
    public void closeCrate(Player player) {
        this.deliverPendingReward(player);

        OpenedCrate instance = this.openingCrates.remove(player.getUniqueId());
        if (instance == null) {
            return;
        }

        UUID animationId = instance.animationId;
        if (animationId != null && this.animationExecutor.isRunning(animationId)) {
            this.animationExecutor.cancelAnimation(animationId, true);
        }
        this.hideRollingPreview(player, instance);
        if (!instance.animation.usesInventory()) {
            if (instance.currentReward != null) deliverReward(player, new PendingReward(instance.crate, instance.currentReward));
            return;
        }
        this.resyncInventory(player);

        if (instance.currentReward == null) {
            Logger.warning("Player {} closed crate {} with no reward to give, the roll never produced one",
                    player.getName(), instance.crate.id());
            return;
        }

        this.pendingRewards.put(player.getUniqueId(), new PendingReward(instance.crate, instance.currentReward));
        if (this.getPlugin().isEnabled() && player.isOnline()) {
            Bukkit.getScheduler().runTaskLater(this.getPlugin(), () -> this.deliverPendingReward(player), REWARD_DELIVERY_DELAY);
        } else {
            this.deliverPendingReward(player);
        }
    }

    public void deliverPendingReward(Player player) {
        PendingReward pending = this.pendingRewards.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }

        deliverReward(player, pending);
    }

    private void deliverReward(Player player, PendingReward pending) {
        pending.reward().give(player);
        Logger.debug("Player {} received reward {} from crate {}", player.getName(), pending.reward().id(), pending.crate().id());
        player.updateInventory();

        RewardGivenEvent givenEvent = new RewardGivenEvent(player, pending.crate(), pending.reward());
        Bukkit.getPluginManager().callEvent(givenEvent);
    }

    private void resyncInventory(Player player) {
        if (!this.getPlugin().isEnabled()) {
            player.updateInventory();
            return;
        }
        Bukkit.getScheduler().runTask(this.getPlugin(), () -> {
            if (player.isOnline()) {
                player.updateInventory();
            }
        });
    }

    @Override
    public void ensureInventoriesExist() {
        this.inventoryManager.deleteInventories(this.getPlugin());
        CratesRegistry registry = Registry.get(CratesRegistry.class);

        loadPreview("crate-preview");
        for (Crate crate : registry.getAll()) {
            String menu = crate.relatedMenu();
            if (menu == null) continue;
            loadPreview(menu + "-preview");
            if (crate.instantReward() || !crate.animation().usesInventory()) continue;
            try {
                if (this.inventoryManager.getInventory(this.getPlugin(), menu).isEmpty()) {
                    this.inventoryManager.loadInventoryOrSaveResource(this.getPlugin(), "inventories/" + menu + ".yml", CrateMenu.class);
                }
            } catch (InventoryException | IllegalArgumentException e) {
                Logger.warning("Failed to load opening menu for crate '{}': {}", crate.id(), e.getMessage());
            }
        }
    }

    private void loadPreview(String name) {
        if (this.inventoryManager.getInventory(this.getPlugin(), name).isPresent()) return;
        try {
            this.inventoryManager.loadInventoryOrSaveResource(this.getPlugin(), "inventories/preview/" + name + ".yml");
        } catch (InventoryException | IllegalArgumentException e) {
            Logger.warning("Failed to load preview '{}': {}", name, e.getMessage());
        }
    }

    @Override
    public PlacedCrate placeCrate(String crateId, Location location, DisplayType displayType, String displayValue, float yaw) {
        PlacedCrate placedCrate = new PlacedCrate(
                UUID.randomUUID(),
                crateId,
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                displayType,
                displayValue,
                yaw
        );

        // Spawn the display
        CrateDisplayFactory<?> factory = Registry.get(CrateDisplayFactoriesRegistry.class).getById(displayType);
        if (factory == null) {
            throw new IllegalArgumentException("No display factory registered for type: " + displayType);
        }

        // Use the same location (block + yaw) as when respawning from the chunk PDC
        CrateDisplay<?> display = factory.create(placedCrate.getLocation(), displayValue, yaw);
        display.spawn();

        // Cache
        Location blockLocation = location.getBlock().getLocation();
        this.placedCratesCache.put(blockLocation, new ActivePlacedCrate(placedCrate, display));

        // Save to chunk PDC
        this.saveToChunk(location.getChunk());

        Logger.info("Placed crate '{}' at {} with display type {}", crateId, blockLocation, displayType);
        return placedCrate;
    }

    @Override
    public void removePlacedCrate(PlacedCrate placedCrate) {
        Location location = placedCrate.getLocation();
        if (location == null) {
            Logger.warning("Cannot remove placed crate: world '{}' not loaded", placedCrate.worldName());
            return;
        }

        Location blockLocation = location.getBlock().getLocation();

        // Remove from cache and display
        ActivePlacedCrate active = this.placedCratesCache.remove(blockLocation);
        if (active != null && active.display() != null) {
            active.display().remove();
        }

        // Update chunk PDC
        this.saveToChunk(location.getChunk());

        Logger.info("Removed placed crate '{}' at {}", placedCrate.crateId(), blockLocation);
    }

    @Override
    public Optional<PlacedCrate> findPlacedCrateByBlock(Block block) {
        Location blockLocation = block.getLocation();
        ActivePlacedCrate cached = this.placedCratesCache.get(blockLocation);
        if (cached != null) {
            return Optional.of(cached.data());
        }

        // Check if any block display matches
        return this.placedCratesCache.values().stream()
                .filter(activePlacedCrate -> activePlacedCrate.display() != null
                        && activePlacedCrate.display().getLocation().equals(blockLocation))
                .map(ActivePlacedCrate::data)
                .findFirst();
    }

    @Override
    public Optional<PlacedCrate> findPlacedCrateByEntity(Entity entity) {
        return this.placedCratesCache.values().stream()
                .filter(active -> {
                    CrateDisplay<?> display = active.display();
                    if (display instanceof EntityCrateDisplay entityDisplay) {
                        return entityDisplay.matches(entity);
                    }
                    return false;
                })
                .map(ActivePlacedCrate::data)
                .findFirst();
    }

    @Override
    public void loadPlacedCratesFromChunk(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        List<PlacedCrate> placedCrates = Keys.PLACED_CRATES.get(pdc, new ArrayList<>());

        for (PlacedCrate placedCrate : placedCrates) {
            Location location = placedCrate.getLocation();
            if (location == null) {
                continue;
            }

            Location blockLocation = location.getBlock().getLocation();

            // Skip if already loaded
            if (this.placedCratesCache.containsKey(blockLocation)) {
                continue;
            }

            // Spawn display
            CrateDisplayFactory<?> factory = Registry.get(CrateDisplayFactoriesRegistry.class).getById(placedCrate.displayType());
            CrateDisplay<?> display = null;
            if (factory != null) {
                display = factory.create(location, placedCrate.displayValue(), placedCrate.yaw());
                display.spawn();
            }

            this.placedCratesCache.put(blockLocation, new ActivePlacedCrate(placedCrate, display));
        }

        if (!placedCrates.isEmpty()) {
            Logger.debug("Loaded {} placed crates from chunk ({}, {})", placedCrates.size(), chunk.getX(), chunk.getZ());
        }
    }

    @Override
    public void unloadPlacedCratesFromChunk(Chunk chunk) {
        List<Location> toRemove = this.placedCratesCache.keySet().stream()
                .filter(loc -> loc.getWorld().equals(chunk.getWorld())
                        && loc.getBlockX() >> 4 == chunk.getX()
                        && loc.getBlockZ() >> 4 == chunk.getZ())
                .toList();

        for (Location location : toRemove) {
            ActivePlacedCrate active = this.placedCratesCache.remove(location);
            if (active != null && active.display() != null) {
                active.display().remove();
            }
        }

        if (!toRemove.isEmpty()) {
            Logger.debug("Unloaded {} placed crates from chunk ({}, {})", toRemove.size(), chunk.getX(), chunk.getZ());
        }
    }

    @Override
    public void loadAllPlacedCrates() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                this.loadPlacedCratesFromChunk(chunk);
            }
        }
        Logger.info("Loaded all placed crates from {} worlds", Bukkit.getWorlds().size());
    }

    @Override
    public void unloadAllPlacedCrates() {
        for (ActivePlacedCrate active : this.placedCratesCache.values()) {
            if (active.display() != null) {
                active.display().remove();
            }
        }
        this.placedCratesCache.clear();
        Logger.info("Unloaded all placed crates");
    }

    @Override
    public List<PlacedCrate> getPlacedCratesInWorld(World world) {
        return this.placedCratesCache.values().stream()
                .map(ActivePlacedCrate::data)
                .filter(crate -> crate.worldName().equals(world.getName()))
                .collect(Collectors.toList());
    }

    private void saveToChunk(Chunk chunk) {
        List<PlacedCrate> cratesInChunk = this.placedCratesCache.entrySet().stream()
                .filter(entry -> {
                    Location loc = entry.getKey();
                    return loc.getWorld().equals(chunk.getWorld())
                            && loc.getBlockX() >> 4 == chunk.getX()
                            && loc.getBlockZ() >> 4 == chunk.getZ();
                })
                .map(entry -> entry.getValue().data())
                .toList();

        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        Keys.PLACED_CRATES.set(pdc, cratesInChunk);
    }
}

