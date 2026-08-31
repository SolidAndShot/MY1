package com.solidandshot.lightningcrowbar;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Pose;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The 10-minute hide-and-seek game. It is intentionally isolated from the
 * nail-brick movement implementation in LightningCrowbarPlugin.
 */
public final class HideAndSeekManager implements Listener, org.bukkit.command.CommandExecutor,
        org.bukkit.command.TabCompleter {
    private static final long ROUND_SECONDS = 10 * 60L;
    private static final long HIDER_FAST_SECONDS = 30L;
    private static final int[] SCALE_THRESHOLDS = {0, 180, 300, 480};
    // A normal player is about 1.8 blocks tall. Keep every result below the
    // requested visual height while using the server's scale attribute.
    private static final double[] HIDER_SCALES = {0.54, 0.82, 1.10, 1.65};
    private static final int MAX_HITS = 2;
    private static final long BOOST_MILLIS = 3_000L;
    private static final long FATIGUE_MILLIS = 10_000L;
    private static final long RADAR_COOLDOWN_MILLIS = 30_000L;
    private static final double TRACKING_BULLET_SPEED = 0.16;
    private static final long WHISTLE_COOLDOWN_MILLIS = 20_000L;
    private static final long REVEAL_MILLIS = 5_000L;
    private static final long FIRECRACKER_EFFECT_MILLIS = 5_000L;
    private static final int MIN_EVENT_INTERVAL_SECONDS = 15;
    private static final int MAX_SLOW_AMPLIFIER = 10;
    private static final double[] MAX_HIDER_SCALES = {0.55, 0.83, 1.11, 1.66};

    private final LightningCrowbarPlugin plugin;
    private final Map<UUID, Role> roles = new ConcurrentHashMap<>();
    private final Map<UUID, Role> configuredRoles = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRoundState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> radarCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Long>> whistleCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> whistleCycles = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> swordHits = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bowHits = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> firecrackerHits = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastHiderAttackers = new ConcurrentHashMap<>();
    private final Set<UUID> pendingConversion = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> revealTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> firecrackerParticleTasks = new ConcurrentHashMap<>();
    private final Set<UUID> gameEntities = ConcurrentHashMap.newKeySet();
    private final double[] hiderScales = HIDER_SCALES.clone();
    private final int[] scaleThresholds = SCALE_THRESHOLDS.clone();
    private final Map<RandomEventType, Boolean> randomEventEnabled = new ConcurrentHashMap<>();
    private final Map<String, Boolean> itemGiveEnabled = new ConcurrentHashMap<>();

    private NamespacedKey itemKey;
    private NamespacedKey levelKey;
    private NamespacedKey projectileKey;
    private NamespacedKey targetKey;
    private NamespacedKey nailBrickKey;
    private NamespacedKey firecrackerKey;
    private ScheduledTask gameTask;
    private volatile GamePhase phase = GamePhase.WAITING;
    private volatile long startedAtMillis;
    private volatile String lastResult = "尚未开始";
    private volatile boolean testMode;
    private volatile int announcedScaleStage;
    private volatile int selectedScaleStage;
    private volatile int hiderSlowTriggerSeconds = (int) HIDER_FAST_SECONDS;
    private volatile int hiderSlowAmplifier = 5;
    private volatile boolean randomEventsEnabled;
    private volatile RandomEventMode randomEventMode = RandomEventMode.RANDOM;
    private volatile int randomEventIntervalSeconds = 120;
    private volatile long nextRandomEventAtMillis;
    private volatile long nextTimedEventSecond;
    private volatile RadarMode radarMode = RadarMode.CHUNK;

    public HideAndSeekManager(LightningCrowbarPlugin plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "hns_item");
        levelKey = new NamespacedKey(plugin, "hns_whistle_level");
        projectileKey = new NamespacedKey(plugin, "hns_projectile");
        targetKey = new NamespacedKey(plugin, "hns_target");
        nailBrickKey = new NamespacedKey(plugin, "nail_brick");
        firecrackerKey = new NamespacedKey(plugin, "hns_firecracker");
        for (ItemGrant grant : ItemGrant.values()) {
            itemGiveEnabled.put(grant.configKey, true);
        }
        for (RandomEventType type : RandomEventType.values()) {
            randomEventEnabled.put(type, true);
        }
        loadGameConfig();
        saveGameConfig();
        gameTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tickGame(), 1L, 20L);
    }

    public void shutdown() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        for (ScheduledTask task : revealTasks.values()) {
            task.cancel();
        }
        revealTasks.clear();
        for (ScheduledTask task : firecrackerParticleTasks.values()) {
            task.cancel();
        }
        firecrackerParticleTasks.clear();
        stopGame(null, false);
        saveGameConfig();
    }

    private void tickGame() {
        if (phase != GamePhase.RUNNING) {
            return;
        }

        long elapsed = elapsedSeconds();
        if (elapsed >= ROUND_SECONDS) {
            finishGame("躲藏者坚持到时间，躲藏者胜利！");
            return;
        }

        int currentScaleStage = scaleStage(elapsed);
        if (currentScaleStage > announcedScaleStage) {
            announcedScaleStage = currentScaleStage;
            broadcastScaleChange(currentScaleStage);
        }

        processRandomEvents(elapsed);
        enforceTrackingBulletSpeed();

        for (UUID id : new ArrayList<>(roles.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.getScheduler().run(plugin, task -> updatePlayer(player, elapsed), () -> { });
        }

        if (!testMode && hiderCount() == 0) {
            finishGame("所有躲藏者已被击杀，抓捕者胜利！");
        }
    }

    private void updatePlayer(Player player, long elapsed) {
        UUID id = player.getUniqueId();
        Role role = roles.get(id);
        if (role == null || phase != GamePhase.RUNNING) {
            return;
        }
        PlayerRoundState state = playerStates.get(id);
        if (state == null) {
            return;
        }
        lastLocations.put(id, player.getLocation().clone());

        if (role == Role.HIDER) {
            int stage = scaleStage(elapsed);
            if (stage != state.scaleStage) {
                state.scaleStage = stage;
                applyHiderScaleAndPose(player, state, stage);
                player.sendMessage(Component.text("你的体型已变化为约 " + displayHiderHeight(stage) + " 格。",
                        NamedTextColor.YELLOW));
            }
            applyHiderEffects(player, state, elapsed);
        } else {
            applyHunterEffects(player, state, role == Role.ELIMINATED_HUNTER, elapsed);
        }

        if (state.revealUntil > System.currentTimeMillis()) {
            player.setGlowing(true);
        }
        String roleText = role == Role.HIDER ? "躲藏者" : role == Role.HUNTER ? "抓捕者" : "普通抓捕者";
        player.sendActionBar(Component.text("[" + roleText + "] 剩余 " + formatRemaining(elapsed)
                + " | " + formatNextEvent(elapsed) + " | 躲藏者 " + hiderCount(), NamedTextColor.AQUA));
    }

    private void broadcastScaleChange(int stage) {
        String text = "躲藏者体型已变为约 " + displayHiderHeight(stage) + " 格";
        Title title = Title.title(
                Component.text("体型变化", NamedTextColor.GOLD),
                Component.text(text, NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(1_000), Duration.ofMillis(2_600), Duration.ofMillis(1_400)));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.getScheduler().run(plugin, task -> {
                viewer.showTitle(title);
                viewer.playSound(viewer.getLocation(), "minecraft:apply_effect.trial_omen",
                        SoundCategory.PLAYERS, 0.45f, 0.2f);
            }, () -> { });
        }
        broadcast(Component.text(text + "。", NamedTextColor.YELLOW));
    }

    private void applyHiderScaleAndPose(Player player, PlayerRoundState state, int stage) {
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(hiderScales[stage]);
        }
        Pose pose = switch (stage) {
            case 1 -> Pose.SITTING;
            case 2 -> Pose.SNEAKING;
            default -> Pose.STANDING;
        };
        player.setPose(pose, false);
    }

    private void applyHiderEffects(Player player, PlayerRoundState state, long elapsed) {
        long now = System.currentTimeMillis();
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        if (state.boostUntil > now) {
            addEffect(player, PotionEffectType.SPEED, 8);
        } else if (state.fatigueUntil > now) {
            addEffect(player, PotionEffectType.SLOWNESS, 5);
        } else if (elapsed < hiderSlowTriggerSeconds) {
            addEffect(player, PotionEffectType.SPEED, 4);
        } else {
            addEffect(player, PotionEffectType.SLOWNESS, hiderSlowAmplifier);
        }
        if (state.eventSlowUntil > now) {
            addEffect(player, PotionEffectType.SLOWNESS,
                    Math.max(hiderSlowAmplifier, state.eventSlowAmplifier));
        }
        if (state.firecrackerSlowUntil > now) {
            addEffect(player, PotionEffectType.SLOWNESS, Math.max(hiderSlowAmplifier, 4));
        }
        if (state.eventSpeedUntil > now) {
            addEffect(player, PotionEffectType.SPEED, Math.max(4, state.eventSpeedAmplifier));
        }
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    private void applyHunterEffects(Player player, PlayerRoundState state, boolean eliminated, long elapsed) {
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.GLOWING);
        int speed = eliminated ? 0 : 1;
        if (state.eventSpeedUntil > System.currentTimeMillis()) {
            speed = Math.max(speed, state.eventSpeedAmplifier);
        }
        addEffect(player, PotionEffectType.SPEED, speed);
        addEffect(player, PotionEffectType.JUMP_BOOST, eliminated ? 0 : 1);
        if (!eliminated) {
            int strength = Math.min(4, (int) (elapsed / 120L));
            addEffect(player, PotionEffectType.STRENGTH, strength);
        }
        addEffect(player, PotionEffectType.GLOWING, 0);
    }

    private void addEffect(Player player, PotionEffectType type, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, 45, amplifier, false, false, true));
    }

    private long elapsedSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1_000L);
    }

    private int scaleStage(long elapsed) {
        int stage = 0;
        for (int i = 1; i < scaleThresholds.length; i++) {
            if (elapsed >= scaleThresholds[i]) {
                stage = i;
            }
        }
        return stage;
    }

    private String formatNextEvent(long elapsed) {
        long next = ROUND_SECONDS;
        for (int threshold : scaleThresholds) {
            if (threshold > elapsed) {
                next = threshold;
                break;
            }
        }
        String label = next == ROUND_SECONDS ? "结算" : "下次体型";
        return label + " " + formatDuration(Math.max(0L, next - elapsed));
    }

    private double displayHiderHeight(int stage) {
        return Math.round(hiderScales[stage] * 1.8 * 100.0) / 100.0;
    }

    private String formatDuration(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void loadGameConfig() {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("hns");
        if (root == null) {
            return;
        }
        for (int i = 0; i < hiderScales.length; i++) {
            hiderScales[i] = clampScale(i, root.getDouble("scales." + i, hiderScales[i]));
        }
        for (int i = 1; i < scaleThresholds.length; i++) {
            int fallback = scaleThresholds[i];
            scaleThresholds[i] = Math.max(scaleThresholds[i - 1] + 1,
                    Math.min((int) ROUND_SECONDS - 1,
                            root.getInt("scale-thresholds." + i, fallback)));
        }
        hiderSlowTriggerSeconds = Math.max(1,
                Math.min((int) ROUND_SECONDS - 1,
                        root.getInt("hider-slow.trigger-seconds", hiderSlowTriggerSeconds)));
        hiderSlowAmplifier = Math.max(0,
                Math.min(MAX_SLOW_AMPLIFIER,
                        root.getInt("hider-slow.amplifier", hiderSlowAmplifier)));
        randomEventsEnabled = root.getBoolean("random-events.enabled", randomEventsEnabled);
        randomEventMode = parseRandomEventMode(root.getString("random-events.mode"), randomEventMode);
        randomEventIntervalSeconds = Math.max(MIN_EVENT_INTERVAL_SECONDS,
                Math.min((int) ROUND_SECONDS, root.getInt("random-events.interval-seconds", randomEventIntervalSeconds)));
        radarMode = parseRadarMode(root.getString("radar.mode"), radarMode);
        ConfigurationSection items = root.getConfigurationSection("items");
        if (items != null) {
            for (ItemGrant grant : ItemGrant.values()) {
                itemGiveEnabled.put(grant.configKey,
                        items.getBoolean(grant.configKey, itemGiveEnabled.getOrDefault(grant.configKey, true)));
            }
        }
        ConfigurationSection events = root.getConfigurationSection("random-events.events");
        if (events != null) {
            for (RandomEventType type : RandomEventType.values()) {
                randomEventEnabled.put(type, events.getBoolean(type.configKey, true));
            }
        }
    }

    private void saveGameConfig() {
        plugin.getConfig().set("hns.scales.0", hiderScales[0]);
        plugin.getConfig().set("hns.scales.1", hiderScales[1]);
        plugin.getConfig().set("hns.scales.2", hiderScales[2]);
        plugin.getConfig().set("hns.scales.3", hiderScales[3]);
        plugin.getConfig().set("hns.scale-thresholds.1", scaleThresholds[1]);
        plugin.getConfig().set("hns.scale-thresholds.2", scaleThresholds[2]);
        plugin.getConfig().set("hns.scale-thresholds.3", scaleThresholds[3]);
        plugin.getConfig().set("hns.hider-slow.trigger-seconds", hiderSlowTriggerSeconds);
        plugin.getConfig().set("hns.hider-slow.amplifier", hiderSlowAmplifier);
        plugin.getConfig().set("hns.random-events.enabled", randomEventsEnabled);
        plugin.getConfig().set("hns.random-events.mode", randomEventMode.name());
        plugin.getConfig().set("hns.random-events.interval-seconds", randomEventIntervalSeconds);
        for (RandomEventType type : RandomEventType.values()) {
            plugin.getConfig().set("hns.random-events.events." + type.configKey,
                    randomEventEnabled.getOrDefault(type, true));
        }
        plugin.getConfig().set("hns.radar.mode", radarMode.name());
        for (ItemGrant grant : ItemGrant.values()) {
            plugin.getConfig().set("hns.items." + grant.configKey,
                    itemGiveEnabled.getOrDefault(grant.configKey, true));
        }
        plugin.saveConfig();
    }

    private double clampScale(int stage, double value) {
        return Math.max(0.10, Math.min(MAX_HIDER_SCALES[stage], value));
    }

    private void adjustScaleThreshold(int stage, int deltaSeconds) {
        if (stage < 1 || stage >= scaleThresholds.length) {
            return;
        }
        int minimum = stage == 1 ? 1 : scaleThresholds[stage - 1] + 1;
        int maximum = stage == scaleThresholds.length - 1
                ? (int) ROUND_SECONDS - 1
                : scaleThresholds[stage + 1] - 1;
        if (maximum < minimum) {
            return;
        }
        int candidate = scaleThresholds[stage] + deltaSeconds;
        scaleThresholds[stage] = Math.max(minimum, Math.min(maximum, candidate));
    }

    private void markHiderScalesDirty() {
        for (PlayerRoundState state : playerStates.values()) {
            state.scaleStage = -1;
        }
    }

    private void processRandomEvents(long elapsed) {
        if (!randomEventsEnabled || phase != GamePhase.RUNNING) {
            return;
        }
        List<RandomEventType> enabled = Arrays.stream(RandomEventType.values())
                .filter(type -> randomEventEnabled.getOrDefault(type, true))
                .toList();
        if (enabled.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (randomEventMode == RandomEventMode.TIMED) {
            if (elapsed >= nextTimedEventSecond) {
                triggerRandomEvent(enabled);
                do {
                    nextTimedEventSecond += randomEventIntervalSeconds;
                } while (nextTimedEventSecond <= elapsed);
            }
        } else if (now >= nextRandomEventAtMillis) {
            triggerRandomEvent(enabled);
            nextRandomEventAtMillis = now + randomEventDelayMillis();
        }
    }

    private void resetRandomEventSchedule() {
        nextTimedEventSecond = Math.max(MIN_EVENT_INTERVAL_SECONDS, randomEventIntervalSeconds);
        nextRandomEventAtMillis = System.currentTimeMillis() + randomEventDelayMillis();
    }

    private long randomEventDelayMillis() {
        long base = randomEventIntervalSeconds * 1_000L;
        if (randomEventMode == RandomEventMode.TIMED) {
            return base;
        }
        long minimum = Math.max(MIN_EVENT_INTERVAL_SECONDS * 1_000L, base / 2L);
        long maximum = Math.max(minimum, base + base / 2L);
        return ThreadLocalRandom.current().nextLong(minimum, maximum + 1L);
    }

    private void triggerRandomEvent(List<RandomEventType> enabled) {
        RandomEventType type = enabled.get(ThreadLocalRandom.current().nextInt(enabled.size()));
        long now = System.currentTimeMillis();
        switch (type) {
            case HIDER_REVEAL -> {
                for (UUID id : new ArrayList<>(roles.keySet())) {
                    if (roles.get(id) == Role.HIDER) {
                        Player hider = Bukkit.getPlayer(id);
                        if (hider != null && hider.isOnline()) {
                            reveal(hider, REVEAL_MILLIS);
                        }
                    }
                }
                broadcast(Component.text("随机事件：所有躲藏者暴露 5 秒。", NamedTextColor.RED));
            }
            case HIDER_SLOW -> {
                for (Map.Entry<UUID, Role> entry : roles.entrySet()) {
                    if (entry.getValue() == Role.HIDER) {
                        PlayerRoundState state = playerStates.get(entry.getKey());
                        if (state != null) {
                            state.eventSlowUntil = now + 10_000L;
                            state.eventSlowAmplifier = Math.min(MAX_SLOW_AMPLIFIER, hiderSlowAmplifier + 2);
                        }
                    }
                }
                broadcast(Component.text("随机事件：躲藏者获得额外缓慢效果 10 秒。", NamedTextColor.RED));
            }
            case HUNTER_HASTE -> {
                for (Map.Entry<UUID, Role> entry : roles.entrySet()) {
                    if (isHunter(entry.getValue())) {
                        PlayerRoundState state = playerStates.get(entry.getKey());
                        if (state != null) {
                            state.eventSpeedUntil = now + 10_000L;
                            state.eventSpeedAmplifier = 3;
                        }
                    }
                }
                broadcast(Component.text("随机事件：抓捕者获得额外速度 10 秒。", NamedTextColor.GOLD));
            }
            case HIDER_BOOST -> {
                for (Map.Entry<UUID, Role> entry : roles.entrySet()) {
                    if (entry.getValue() == Role.HIDER) {
                        PlayerRoundState state = playerStates.get(entry.getKey());
                        if (state != null) {
                            state.eventSpeedUntil = now + 5_000L;
                            state.eventSpeedAmplifier = 6;
                        }
                    }
                }
                broadcast(Component.text("随机事件：躲藏者获得短暂加速 5 秒。", NamedTextColor.GREEN));
            }
        }
    }

    private RandomEventMode parseRandomEventMode(String value, RandomEventMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return RandomEventMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private RadarMode parseRadarMode(String value, RadarMode fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("CUBE_10")) {
            return RadarMode.SQUARE_10;
        }
        try {
            return RadarMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private int hiderCount() {
        int count = 0;
        for (Role role : roles.values()) {
            if (role == Role.HIDER) {
                count++;
            }
        }
        return count;
    }

    public void openAdminMenu(Player admin) {
        if (!admin.hasPermission("lightningcrowbar.hns")) {
            admin.sendMessage(Component.text("你没有躲猫猫管理权限。", NamedTextColor.RED));
            return;
        }
        admin.openInventory(createAdminInventory(AdminMenuPage.MAIN));
    }

    private Inventory createAdminInventory() {
        return createAdminInventory(AdminMenuPage.MAIN);
    }

    private Inventory createAdminInventory(AdminMenuPage page) {
        AdminMenuHolder holder = new AdminMenuHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(page.title));
        holder.inventory = inventory;
        switch (page) {
            case MAIN -> createMainMenu(inventory);
            case GAME -> createGameMenu(inventory);
            case PLAYERS -> createPlayersMenu(inventory);
            case RULES -> createRulesMenu(inventory);
            case HIDER_ITEMS -> createItemMenu(inventory, false);
            case HUNTER_ITEMS -> createItemMenu(inventory, true);
            case RADAR_EVENTS -> createRadarEventsMenu(inventory);
        }
        return inventory;
    }

    private void createMainMenu(Inventory inventory) {
        inventory.setItem(10, menuItem(Material.LIME_WOOL, "游戏控制", "menu_game"));
        inventory.setItem(12, menuItem(Material.PLAYER_HEAD, "玩家与角色", "menu_players"));
        inventory.setItem(14, menuItem(Material.SPYGLASS, "规则与体型", "menu_rules"));
        inventory.setItem(16, menuItem(Material.BRICK, "躲藏者道具发放", "menu_hider_items"));
        inventory.setItem(28, menuItem(Material.NETHERITE_SWORD, "抓捕者道具发放", "menu_hunter_items"));
        inventory.setItem(30, menuItem(Material.COMPASS, "雷达与随机事件", "menu_radar_events"));
        inventory.setItem(22, ComponentItem(Material.PAPER, "状态：" + phaseText(),
                "剩余时间：" + formatRemaining(), "躲藏者：" + hiderCount(), "上局：" + lastResult));
        inventory.setItem(49, menuItem(Material.BARRIER, "关闭", "close_menu"));
    }

    private void createGameMenu(Inventory inventory) {
        inventory.setItem(10, menuItem(Material.LIME_WOOL, "开始 10 分钟游戏", "start"));
        inventory.setItem(11, menuItem(Material.RED_WOOL, "停止游戏", "stop"));
        inventory.setItem(12, menuItem(Material.CLOCK, "刷新状态", "status"));
        inventory.setItem(13, menuItem(Material.COMPASS, "随机分配角色", "randomize"));
        inventory.setItem(14, menuItem(Material.CHEST, "发放当前角色道具", "kit"));
        inventory.setItem(15, menuItem(Material.REPEATER, "单人测试：躲藏者", "test_hider"));
        inventory.setItem(16, menuItem(Material.TARGET, "单人测试：抓捕者", "test_hunter"));
        inventory.setItem(19, menuItem(Material.REDSTONE_TORCH, "时间 -30 秒", "time_minus_30"));
        inventory.setItem(20, menuItem(Material.CLOCK, "时间 +30 秒", "time_plus_30"));
        inventory.setItem(21, menuItem(Material.COPPER_BLOCK, "跳到 3 分钟", "time_180"));
        inventory.setItem(22, menuItem(Material.IRON_BLOCK, "跳到 5 分钟", "time_300"));
        inventory.setItem(23, menuItem(Material.NETHERITE_BLOCK, "跳到 8 分钟", "time_480"));
        inventory.setItem(25, menuItem(Material.GOLDEN_SWORD, "强制抓捕者胜利", "win_hunter"));
        inventory.setItem(26, menuItem(Material.GOLDEN_APPLE, "强制躲藏者胜利", "win_hider"));
        inventory.setItem(31, ComponentItem(Material.PAPER, "状态：" + phaseText(),
                "剩余时间：" + formatRemaining(), "躲藏者：" + hiderCount(), "上局：" + lastResult));
        addBackButton(inventory);
    }

    private void createPlayersMenu(Inventory inventory) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < players.size() && i < 12; i++) {
            inventory.setItem(10 + i, playerHead(players.get(i)));
        }
        inventory.setItem(31, ComponentItem(Material.PLAYER_HEAD, "角色预设",
                "点击玩家头像在躲藏者/抓捕者之间切换"));
        addBackButton(inventory);
    }

    private void createRulesMenu(Inventory inventory) {
        inventory.setItem(10, menuItem(Material.CLOCK,
                "体型节点 1：" + formatDuration(scaleThresholds[1]), "scale_time_1"));
        inventory.setItem(11, menuItem(Material.CLOCK,
                "体型节点 2：" + formatDuration(scaleThresholds[2]), "scale_time_2"));
        inventory.setItem(12, menuItem(Material.CLOCK,
                "体型节点 3：" + formatDuration(scaleThresholds[3]), "scale_time_3"));
        inventory.setItem(19, ComponentItem(Material.SPYGLASS,
                "体型阶段 " + (selectedScaleStage + 1) + " / 4",
                "当前倍率：" + formatScale(hiderScales[selectedScaleStage]),
                "约高：" + displayHiderHeight(selectedScaleStage) + " 格"));
        inventory.setItem(20, menuItem(Material.RED_CONCRETE, "体型倍率 -0.01", "scale_minus"));
        inventory.setItem(21, menuItem(Material.GREEN_CONCRETE, "体型倍率 +0.01", "scale_plus"));
        inventory.setItem(22, menuItem(Material.CLOCK, "切换体型阶段", "scale_stage_next"));
        inventory.setItem(28, ComponentItem(Material.POTION, "躲藏者缓慢配置",
                "触发：" + formatDuration(hiderSlowTriggerSeconds),
                "等级：缓慢 " + (hiderSlowAmplifier + 1)));
        inventory.setItem(29, menuItem(Material.REPEATER, "缓慢触发时间 +30秒", "slow_time_next"));
        inventory.setItem(30, menuItem(Material.REDSTONE, "缓慢等级 +1", "slow_amp_next"));
        addBackButton(inventory);
    }

    private void createItemMenu(Inventory inventory, boolean hunter) {
        ItemGrant[] grants = Arrays.stream(ItemGrant.values())
                .filter(grant -> grant.hunter == hunter).toArray(ItemGrant[]::new);
        for (int i = 0; i < grants.length; i++) {
            ItemGrant grant = grants[i];
            boolean enabled = itemGiveEnabled.getOrDefault(grant.configKey, true);
            inventory.setItem(10 + i, menuItem(enabled ? Material.LIME_WOOL : Material.GRAY_WOOL,
                    grant.displayName + "：" + onOff(enabled), "item_toggle_" + grant.configKey));
        }
        inventory.setItem(31, ComponentItem(hunter ? Material.NETHERITE_SWORD : Material.BRICK,
                hunter ? "抓捕者开局道具" : "躲藏者开局道具",
                "绿色表示游戏开始时自动发放，灰色表示关闭。"));
        addBackButton(inventory);
    }

    private void createRadarEventsMenu(Inventory inventory) {
        inventory.setItem(10, menuItem(Material.COMPASS, "雷达范围：" + radarModeText(), "radar_mode"));
        inventory.setItem(12, menuItem(randomEventsEnabled ? Material.LIME_WOOL : Material.GRAY_WOOL,
                "随机事件总开关：" + onOff(randomEventsEnabled), "events_toggle"));
        int slot = 19;
        for (RandomEventType type : RandomEventType.values()) {
            boolean enabled = randomEventEnabled.getOrDefault(type, true);
            inventory.setItem(slot++, menuItem(enabled ? Material.REDSTONE_TORCH : Material.GRAY_DYE,
                    "事件：" + type.displayName + " " + onOff(enabled),
                    "event_toggle_" + type.configKey));
        }
        inventory.setItem(28, menuItem(Material.DAYLIGHT_DETECTOR,
                "事件触发：" + randomEventModeText(), "events_mode"));
        inventory.setItem(29, menuItem(Material.CLOCK,
                "事件间隔 +15秒（当前 " + randomEventIntervalSeconds + "）", "events_interval_next"));
        inventory.setItem(31, ComponentItem(Material.PAPER, "雷达说明",
                "区块模式按当前区块查询；正方形模式按玩家中心的 X/Z 范围查询。"));
        addBackButton(inventory);
    }

    private void addBackButton(Inventory inventory) {
        inventory.setItem(49, menuItem(Material.ARROW, "返回主菜单", "menu_back"));
    }

    private ItemStack playerHead(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setOwningPlayer(player);
        Role role = configuredRoles.get(player.getUniqueId());
        String roleText = role == Role.HUNTER ? "抓捕者" : role == Role.HIDER ? "躲藏者" : "未设置";
        meta.displayName(Component.text(player.getName() + "：" + roleText, NamedTextColor.WHITE));
        meta.lore(List.of(Component.text("点击切换预设角色", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack menuItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.WHITE));
            meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack ComponentItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.AQUA));
            meta.lore(Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatScale(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String onOff(boolean enabled) {
        return enabled ? "开启" : "关闭";
    }

    private String radarModeText() {
        return radarMode == RadarMode.CHUNK ? "区块" : "边长 " + radarMode.sideLength + " 格正方形";
    }

    private String randomEventModeText() {
        return randomEventMode == RandomEventMode.RANDOM ? "随机" : "定时";
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (phase != GamePhase.RUNNING || !isUseAction(event.getAction())) {
            return;
        }
        Player player = event.getPlayer();
        Role role = roles.get(player.getUniqueId());
        if (role == null) {
            return;
        }
        ItemStack item = event.getItem();
        String kind = itemKind(item);
        if (kind == null) {
            return;
        }

        if (kind.equals("abatukan")) {
            if (role != Role.HIDER) {
                return;
            }
            event.setCancelled(true);
            consumeOne(player);
            PlayerRoundState state = playerStates.get(player.getUniqueId());
            if (state != null) {
                state.boostUntil = System.currentTimeMillis() + BOOST_MILLIS;
                state.fatigueUntil = state.boostUntil + FATIGUE_MILLIS;
            }
            player.playSound(player.getLocation(), "minecraft:particle.soul_escape",
                    SoundCategory.PLAYERS, 0.35f, 1.5f);
            player.sendMessage(Component.text("阿巴土坎片生效：巨额加速 3 秒，随后疲劳 10 秒。", NamedTextColor.GREEN));
        } else if (kind.equals("whistle")) {
            if (role != Role.HIDER) {
                return;
            }
            event.setCancelled(true);
            int level = whistleLevel(item);
            useWhistle(player, level);
        } else if (kind.equals("grenade")) {
            if (!isHunter(role)) {
                return;
            }
            event.setCancelled(true);
            consumeOne(player);
            Snowball grenade = player.launchProjectile(Snowball.class);
            grenade.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "grenade");
            grenade.setVelocity(player.getLocation().getDirection().normalize().multiply(1.35));
            gameEntities.add(grenade.getUniqueId());
        } else if (kind.equals("small_firecracker")) {
            if (!isHunter(role)) {
                return;
            }
            event.setCancelled(true);
            consumeOne(player);
            Snowball firecracker = player.launchProjectile(Snowball.class);
            firecracker.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "small_firecracker");
            firecracker.getPersistentDataContainer().set(firecrackerKey, PersistentDataType.BYTE, (byte) 1);
            firecracker.setVelocity(player.getLocation().getDirection().normalize().multiply(1.05));
            gameEntities.add(firecracker.getUniqueId());
        } else if (kind.equals("chunk_radar")) {
            if (role != Role.HUNTER) {
                return;
            }
            event.setCancelled(true);
            showChunkRadar(player);
        } else if (kind.equals("tracking_radar")) {
            if (role != Role.HUNTER) {
                return;
            }
            event.setCancelled(true);
            launchTrackingRadar(player);
        }
    }

    private boolean isUseAction(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private void playWhistle(Player source, int level) {
        Sound sound = switch (Math.max(1, Math.min(3, level))) {
            case 1 -> Sound.ENTITY_GHAST_AMBIENT;
            case 2 -> Sound.ENTITY_ENDERMAN_SCREAM;
            default -> Sound.ENTITY_ENDER_DRAGON_GROWL;
        };
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Location soundLocation = source.getLocation().clone();
            viewer.getScheduler().run(plugin,
                    task -> viewer.playSound(soundLocation, sound, SoundCategory.PLAYERS, 3.0f, 1.0f), () -> { });
        }
        source.sendMessage(Component.text("嘲讽哨等级 " + level + " 已吹响。", NamedTextColor.YELLOW));
    }

    private void useWhistle(Player player, int level) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<Integer, Long> cooldowns = whistleCooldowns.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>());
        long readyAt = cooldowns.getOrDefault(level, 0L);
        if (readyAt > now) {
            player.sendMessage(Component.text("嘲讽哨等级 " + level + " 冷却中，还需 "
                    + ((readyAt - now + 999) / 1_000) + " 秒。", NamedTextColor.RED));
            return;
        }

        cooldowns.put(level, now + WHISTLE_COOLDOWN_MILLIS);
        playWhistle(player, level);
        Set<Integer> cycle = whistleCycles.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet());
        cycle.add(level);
        if (cycle.containsAll(Set.of(1, 2, 3))) {
            cycle.clear();
            player.getInventory().addItem(customItem(Material.MUSIC_DISC_CAT, "阿巴土坎片", "abatukan", 1,
                    "使用后加速 3 秒，随后疲劳 10 秒。"));
            player.sendMessage(Component.text("三个等级的嘲讽哨都已使用，获得一张阿巴土坎片。", NamedTextColor.GREEN));
        }
    }

    private void showChunkRadar(Player hunter) {
        Location hunterLocation = hunter.getLocation();
        int count = 0;
        for (Map.Entry<UUID, Role> entry : roles.entrySet()) {
            if (entry.getValue() != Role.HIDER) {
                continue;
            }
            Player hider = Bukkit.getPlayer(entry.getKey());
            Location location = lastLocations.get(entry.getKey());
            if (hider != null && location != null && location.getWorld().equals(hunterLocation.getWorld())
                    && isWithinRadarRange(hunterLocation, location)) {
                count++;
            }
        }
        String modeText = radarModeText();
        hunter.sendMessage(Component.text("雷达（" + modeText + "）：范围内有 " + count + " 名躲藏者。",
                NamedTextColor.AQUA));
        hunter.playSound(hunter.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.4f);
    }

    private void launchTrackingRadar(Player hunter) {
        long now = System.currentTimeMillis();
        long cooldown = radarCooldowns.getOrDefault(hunter.getUniqueId(), 0L);
        if (cooldown > now) {
            hunter.sendMessage(Component.text("追踪雷达冷却中，还需 " + ((cooldown - now + 999) / 1000) + " 秒。",
                    NamedTextColor.RED));
            return;
        }
        Player target = nearestHider(hunter);
        if (target == null) {
            hunter.sendMessage(Component.text("当前没有可追踪的躲藏者。", NamedTextColor.YELLOW));
            return;
        }
        radarCooldowns.put(hunter.getUniqueId(), now + RADAR_COOLDOWN_MILLIS);
        Location launch = hunter.getEyeLocation().clone();
        Location targetLocation = lastLocations.get(target.getUniqueId());
        if (targetLocation == null) {
            hunter.sendMessage(Component.text("目标位置尚未同步，请稍后重试。", NamedTextColor.YELLOW));
            radarCooldowns.put(hunter.getUniqueId(), 0L);
            return;
        }
        Vector direction = targetLocation.clone().add(0, 0.8, 0).toVector().subtract(launch.toVector()).normalize();
        ShulkerBullet bullet = hunter.getWorld().spawn(launch, ShulkerBullet.class, spawned -> {
            spawned.setShooter(hunter);
            spawned.setVelocity(direction.multiply(TRACKING_BULLET_SPEED));
            spawned.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "tracking");
            spawned.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        });
        gameEntities.add(bullet.getUniqueId());
        hunter.sendMessage(Component.text("追踪雷达已发射。", NamedTextColor.AQUA));
    }

    private Player nearestHider(Player source) {
        Location sourceLocation = source.getLocation();
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> roles.get(player.getUniqueId()) == Role.HIDER)
                .filter(player -> lastLocations.get(player.getUniqueId()) != null)
                .filter(player -> lastLocations.get(player.getUniqueId()).getWorld().equals(source.getWorld()))
                .filter(player -> isWithinRadarRange(sourceLocation, lastLocations.get(player.getUniqueId())))
                .min(Comparator.comparingDouble(player -> lastLocations.get(player.getUniqueId())
                        .distanceSquared(sourceLocation)))
                .orElse(null);
    }

    private boolean isWithinRadarRange(Location center, Location target) {
        if (target == null || center.getWorld() != target.getWorld()) {
            return false;
        }
        if (radarMode == RadarMode.CHUNK) {
            return (target.getBlockX() >> 4) == (center.getBlockX() >> 4)
                    && (target.getBlockZ() >> 4) == (center.getBlockZ() >> 4);
        }
        int side = radarMode.sideLength;
        int lowerOffset = (side - 1) / 2;
        int upperOffset = side - 1 - lowerOffset;
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        return target.getBlockX() >= centerX - lowerOffset
                && target.getBlockX() <= centerX + upperOffset
                && target.getBlockZ() >= centerZ - lowerOffset
                && target.getBlockZ() <= centerZ + upperOffset;
    }

    private void enforceTrackingBulletSpeed() {
        for (UUID id : new ArrayList<>(gameEntities)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof ShulkerBullet bullet)
                    || !"tracking".equals(bullet.getPersistentDataContainer()
                    .get(projectileKey, PersistentDataType.STRING))) {
                continue;
            }
            bullet.getScheduler().run(plugin, task -> {
                if (!bullet.isValid()) {
                    return;
                }
                Vector velocity = bullet.getVelocity();
                if (velocity.lengthSquared() > 0.000001) {
                    bullet.setVelocity(velocity.normalize().multiply(TRACKING_BULLET_SPEED));
                }
            }, () -> { });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player hunter) || phase != GamePhase.RUNNING
                || roles.get(hunter.getUniqueId()) == null || !isHunter(roles.get(hunter.getUniqueId()))
                || !isItem(event.getBow(), "hunter_bow") || !(event.getProjectile() instanceof AbstractArrow arrow)) {
            return;
        }
        arrow.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "hunter_arrow");
        gameEntities.add(arrow.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (phase != GamePhase.RUNNING || !(event.getEntity() instanceof Player target)) {
            return;
        }
        Role targetRole = roles.get(target.getUniqueId());
        if (targetRole != Role.HIDER) {
            return;
        }

        Player attacker = directAttacker(event.getDamager());
        if (attacker == null || !isHunter(roles.get(attacker.getUniqueId()))) {
            event.setCancelled(true);
            return;
        }

        String weapon = damageWeapon(event.getDamager(), attacker);
        if (weapon == null) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        Map<UUID, Integer> hitMap = weapon.equals("sword") ? swordHits : bowHits;
        int hits = hitMap.merge(target.getUniqueId(), 1, Integer::sum);
        if (hits >= MAX_HITS) {
            hitMap.remove(target.getUniqueId());
            lastHiderAttackers.put(target.getUniqueId(), attacker.getUniqueId());
            target.setHealth(0.0);
        } else {
            target.setHealth(Math.max(1.0, target.getMaxHealth() / 2.0));
            attacker.sendMessage(Component.text("命中躲藏者（" + hits + "/2），下一次同类攻击将击杀。", NamedTextColor.YELLOW));
        }
    }

    private Player directAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private String damageWeapon(Entity damager, Player attacker) {
        if (damager instanceof Player && isItem(attacker.getInventory().getItemInMainHand(), "hunter_sword")) {
            return "sword";
        }
        if (damager instanceof AbstractArrow arrow && "hunter_arrow".equals(
                arrow.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING))) {
            return "bow";
        }
        return null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String kind = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
        if (kind == null) {
            return;
        }
        gameEntities.remove(projectile.getUniqueId());
        if (kind.equals("grenade")) {
            Location location = projectile.getLocation();
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                Location playerLocation = lastLocations.get(player.getUniqueId());
                if (roles.get(player.getUniqueId()) == Role.HIDER
                        && playerLocation != null
                        && playerLocation.getWorld().equals(location.getWorld())
                        && playerLocation.distanceSquared(location) <= 9.0) {
                    reveal(player, REVEAL_MILLIS);
                }
            }
        } else if (kind.equals("small_firecracker")) {
            Player thrower = projectile.getShooter() instanceof Player player ? player : null;
            processFirecrackerImpact(projectile.getLocation(), thrower);
        } else if (kind.equals("tracking")) {
            Entity hit = event.getHitEntity();
            if (hit instanceof Player player && roles.get(player.getUniqueId()) == Role.HIDER) {
                reveal(player, REVEAL_MILLIS, Sound.ENTITY_ENDERMAN_SCREAM);
            }
        }
        projectile.remove();
    }

    private void processFirecrackerImpact(Location impact, Player thrower) {
        for (Player target : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            Location targetLocation = lastLocations.get(target.getUniqueId());
            if (roles.get(target.getUniqueId()) != Role.HIDER || targetLocation == null
                    || !targetLocation.getWorld().equals(impact.getWorld())
                    || targetLocation.distanceSquared(impact) > 4.0) {
                continue;
            }
            UUID targetId = target.getUniqueId();
            int hit = firecrackerHits.merge(targetId, 1, Integer::sum);
            UUID throwerId = thrower == null ? null : thrower.getUniqueId();
            target.getScheduler().run(plugin, task -> applyFirecrackerHit(target, hit, throwerId), () -> { });
        }
    }

    private void applyFirecrackerHit(Player target, int hit, UUID throwerId) {
        if (phase != GamePhase.RUNNING || roles.get(target.getUniqueId()) != Role.HIDER) {
            return;
        }
        PlayerRoundState state = playerStates.get(target.getUniqueId());
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        state.firecrackerSlowUntil = now + FIRECRACKER_EFFECT_MILLIS;
        if (hit == 1) {
            target.sendMessage(Component.text("小摔炮命中：缓慢 V 持续 5 秒。", NamedTextColor.YELLOW));
        } else if (hit == 2) {
            startFirecrackerParticles(target);
            target.sendMessage(Component.text("小摔炮再次命中：水滴效果持续 5 秒。", NamedTextColor.AQUA));
        } else {
            firecrackerHits.remove(target.getUniqueId());
            boolean lethal = target.getHealth() <= 10.0;
            if (lethal && throwerId != null) {
                lastHiderAttackers.put(target.getUniqueId(), throwerId);
            } else {
                lastHiderAttackers.remove(target.getUniqueId());
            }
            target.setHealth(Math.max(0.0, target.getHealth() - 10.0));
            target.playSound(target.getLocation(), Sound.ENTITY_SILVERFISH_HURT,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
            target.sendMessage(Component.text("小摔炮第三次命中：受到 5 颗心伤害。", NamedTextColor.RED));
        }
    }

    private void startFirecrackerParticles(Player target) {
        UUID id = target.getUniqueId();
        ScheduledTask previous = firecrackerParticleTasks.remove(id);
        if (previous != null) {
            previous.cancel();
        }
        long until = System.currentTimeMillis() + FIRECRACKER_EFFECT_MILLIS;
        ScheduledTask task = target.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (phase != GamePhase.RUNNING || !target.isOnline() || System.currentTimeMillis() >= until) {
                scheduled.cancel();
                firecrackerParticleTasks.remove(id, scheduled);
                return;
            }
            Location location = target.getLocation().add(0, 0.8, 0);
            target.getWorld().spawnParticle(Particle.SPLASH, location, 35, 0.45, 0.7, 0.45, 0.08);
        }, () -> firecrackerParticleTasks.remove(id), 1L, 5L);
        firecrackerParticleTasks.put(id, task);
    }

    private void reveal(Player player, long duration) {
        reveal(player, duration, Sound.ENTITY_SHULKER_SHOOT);
    }

    private void reveal(Player player, long duration, Sound sound) {
        player.getScheduler().run(plugin, task -> beginReveal(player, duration, sound), () -> { });
    }

    private void beginReveal(Player player, long duration, Sound sound) {
        PlayerRoundState state = playerStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.revealUntil = Math.max(state.revealUntil, System.currentTimeMillis() + duration);
        player.setGlowing(true);
        ScheduledTask old = revealTasks.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            PlayerRoundState active = playerStates.get(player.getUniqueId());
            if (phase != GamePhase.RUNNING || active != state || active.revealUntil <= System.currentTimeMillis()) {
                player.setGlowing(false);
                scheduled.cancel();
                revealTasks.remove(player.getUniqueId(), scheduled);
                return;
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                Location soundLocation = player.getLocation().clone();
                viewer.getScheduler().run(plugin,
                        viewerTask -> viewer.playSound(soundLocation, sound,
                                SoundCategory.PLAYERS, 1.2f, 0.8f), () -> { });
            }
        }, () -> revealTasks.remove(player.getUniqueId()), 1L, 20L);
        revealTasks.put(player.getUniqueId(), task);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (phase != GamePhase.RUNNING) {
            return;
        }
        UUID id = event.getEntity().getUniqueId();
        if (roles.get(id) == Role.HIDER) {
            Player killer = event.getEntity().getKiller();
            UUID trackedKiller = lastHiderAttackers.remove(id);
            if (killer == null && trackedKiller != null) {
                killer = Bukkit.getPlayer(trackedKiller);
            }
            if (killer != null && roles.get(killer.getUniqueId()) == Role.HUNTER) {
                radarCooldowns.put(killer.getUniqueId(), 0L);
                killer.sendMessage(Component.text("成功击杀躲藏者，追踪雷达冷却已重置。", NamedTextColor.GREEN));
            }
            pendingConversion.add(id);
            ScheduledTask particles = firecrackerParticleTasks.remove(id);
            if (particles != null) {
                particles.cancel();
            }
            firecrackerHits.remove(id);
            event.getEntity().sendMessage(Component.text("你已被抓捕，重生后将成为普通抓捕者。", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!pendingConversion.remove(id)) {
            return;
        }
        event.getPlayer().getScheduler().runDelayed(plugin, task -> {
            if (phase != GamePhase.RUNNING) {
                return;
            }
            roles.put(id, Role.ELIMINATED_HUNTER);
            PlayerRoundState state = playerStates.get(id);
            if (state != null) {
                state.scaleStage = -1;
            }
            applyEliminatedHunter(event.getPlayer());
            giveHunterKit(event.getPlayer(), true);
            broadcast(Component.text(event.getPlayer().getName() + " 已变身为普通抓捕者。", NamedTextColor.RED));
        }, () -> { }, 1L);
    }

    private void applyEliminatedHunter(Player player) {
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(1.0);
        }
        player.setPose(Pose.STANDING, false);
        updatePlayer(player, elapsedSeconds());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (phase == GamePhase.RUNNING && roles.get(id) == Role.HIDER) {
            roles.put(id, Role.ELIMINATED_HUNTER);
            broadcast(Component.text(event.getPlayer().getName() + " 离开游戏，视为被抓捕。", NamedTextColor.RED));
        }
        ScheduledTask reveal = revealTasks.remove(id);
        if (reveal != null) {
            reveal.cancel();
        }
        ScheduledTask particles = firecrackerParticleTasks.remove(id);
        if (particles != null) {
            particles.cancel();
        }
        firecrackerHits.remove(id);
        lastLocations.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder holder)
                || !(event.getWhoClicked() instanceof Player admin)) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !admin.hasPermission("lightningcrowbar.hns")) {
            return;
        }
        PersistentDataContainer data = clicked.getItemMeta() == null
                ? null : clicked.getItemMeta().getPersistentDataContainer();
        if (data == null) {
            return;
        }
        String value = data.get(targetKey, PersistentDataType.STRING);
        if (value == null) {
            return;
        }
        if (value.equals("close_menu")) {
            admin.closeInventory();
            return;
        } else if (value.equals("menu_back")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.MAIN));
            return;
        } else if (value.equals("menu_game")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.GAME));
            return;
        } else if (value.equals("menu_players")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.PLAYERS));
            return;
        } else if (value.equals("menu_rules")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.RULES));
            return;
        } else if (value.equals("menu_hider_items")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.HIDER_ITEMS));
            return;
        } else if (value.equals("menu_hunter_items")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.HUNTER_ITEMS));
            return;
        } else if (value.equals("menu_radar_events")) {
            admin.openInventory(createAdminInventory(AdminMenuPage.RADAR_EVENTS));
            return;
        } else if (value.startsWith("item_toggle_")) {
            String key = value.substring("item_toggle_".length());
            for (ItemGrant grant : ItemGrant.values()) {
                if (grant.configKey.equals(key)) {
                    boolean enabled = !itemGiveEnabled.getOrDefault(key, true);
                    itemGiveEnabled.put(key, enabled);
                    saveGameConfig();
                    admin.sendMessage(Component.text(grant.displayName + "开局发放已" + (enabled ? "开启" : "关闭") + "。",
                            NamedTextColor.GREEN));
                    break;
                }
            }
        } else if (value.equals("start")) {
            startGame(admin);
        } else if (value.equals("stop")) {
            stopGame(admin, true);
        } else if (value.equals("randomize")) {
            randomizeConfiguredRoles();
            admin.sendMessage(Component.text("已随机预设角色。", NamedTextColor.GREEN));
        } else if (value.equals("kit")) {
            giveCurrentKit(admin);
        } else if (value.equals("status")) {
            sendStatus(admin);
        } else if (value.equals("test_hider")) {
            startTestGame(admin, Role.HIDER);
        } else if (value.equals("test_hunter")) {
            startTestGame(admin, Role.HUNTER);
        } else if (value.equals("time_minus_30")) {
            adjustElapsed(-30);
        } else if (value.equals("time_plus_30")) {
            adjustElapsed(30);
        } else if (value.equals("time_180")) {
            setElapsed(180);
        } else if (value.equals("time_300")) {
            setElapsed(300);
        } else if (value.equals("time_480")) {
            setElapsed(480);
        } else if (value.equals("win_hunter")) {
            finishGame("管理员判定：抓捕者胜利！");
        } else if (value.equals("win_hider")) {
            finishGame("管理员判定：躲藏者胜利！");
        } else if (value.equals("scale_time_1") || value.equals("scale_time_2") || value.equals("scale_time_3")) {
            int stage = Integer.parseInt(value.substring("scale_time_".length()));
            adjustScaleThreshold(stage, event.getClick() == ClickType.RIGHT ? -30 : 30);
            saveGameConfig();
            markHiderScalesDirty();
            admin.sendMessage(Component.text("体型节点 " + stage + " 已调整为 "
                    + formatDuration(scaleThresholds[stage]) + "（左键 +30 秒，右键 -30 秒）。", NamedTextColor.GREEN));
        } else if (value.equals("scale_minus")) {
            hiderScales[selectedScaleStage] = clampScale(selectedScaleStage,
                    hiderScales[selectedScaleStage] - 0.01);
            markHiderScalesDirty();
            saveGameConfig();
            admin.sendMessage(Component.text("体型阶段 " + (selectedScaleStage + 1)
                    + " 倍率已调整为 " + formatScale(hiderScales[selectedScaleStage]) + "。", NamedTextColor.GREEN));
        } else if (value.equals("scale_plus")) {
            hiderScales[selectedScaleStage] = clampScale(selectedScaleStage,
                    hiderScales[selectedScaleStage] + 0.01);
            markHiderScalesDirty();
            saveGameConfig();
            admin.sendMessage(Component.text("体型阶段 " + (selectedScaleStage + 1)
                    + " 倍率已调整为 " + formatScale(hiderScales[selectedScaleStage]) + "。", NamedTextColor.GREEN));
        } else if (value.equals("scale_stage_next")) {
            selectedScaleStage = (selectedScaleStage + 1) % hiderScales.length;
        } else if (value.equals("slow_time_next")) {
            hiderSlowTriggerSeconds += 30;
            if (hiderSlowTriggerSeconds >= ROUND_SECONDS) {
                hiderSlowTriggerSeconds = 30;
            }
            saveGameConfig();
        } else if (value.equals("slow_amp_next")) {
            hiderSlowAmplifier = (hiderSlowAmplifier + 1) % (MAX_SLOW_AMPLIFIER + 1);
            saveGameConfig();
        } else if (value.equals("radar_mode")) {
            radarMode = radarMode.next();
            saveGameConfig();
            admin.sendMessage(Component.text("雷达模式已切换为：" + radarModeText() + "。", NamedTextColor.GREEN));
        } else if (value.equals("events_toggle")) {
            randomEventsEnabled = !randomEventsEnabled;
            resetRandomEventSchedule();
            saveGameConfig();
        } else if (value.equals("events_mode")) {
            randomEventMode = randomEventMode == RandomEventMode.RANDOM
                    ? RandomEventMode.TIMED : RandomEventMode.RANDOM;
            resetRandomEventSchedule();
            saveGameConfig();
        } else if (value.equals("events_interval_next")) {
            randomEventIntervalSeconds += 15;
            if (randomEventIntervalSeconds > ROUND_SECONDS) {
                randomEventIntervalSeconds = MIN_EVENT_INTERVAL_SECONDS;
            }
            resetRandomEventSchedule();
            saveGameConfig();
        } else if (value.startsWith("event_toggle_")) {
            String key = value.substring("event_toggle_".length());
            for (RandomEventType type : RandomEventType.values()) {
                if (type.configKey.equals(key)) {
                    randomEventEnabled.put(type, !randomEventEnabled.getOrDefault(type, true));
                    saveGameConfig();
                    break;
                }
            }
        } else {
            try {
                UUID target = UUID.fromString(value);
                toggleConfiguredRole(target);
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
        admin.openInventory(createAdminInventory(holder.page));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // The holder is intentionally stateless; reopening is handled by the click listener.
    }

    private void toggleConfiguredRole(UUID id) {
        Role current = configuredRoles.get(id);
        configuredRoles.put(id, current == Role.HUNTER ? Role.HIDER : Role.HUNTER);
    }

    private void randomizeConfiguredRoles() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(players, ThreadLocalRandom.current());
        configuredRoles.clear();
        int hunters = Math.max(1, players.size() / 4);
        for (int i = 0; i < players.size(); i++) {
            configuredRoles.put(players.get(i).getUniqueId(), i < hunters ? Role.HUNTER : Role.HIDER);
        }
    }

    public boolean startGame(org.bukkit.command.CommandSender sender) {
        if (phase == GamePhase.RUNNING) {
            sender.sendMessage(Component.text("游戏已经在进行中。", NamedTextColor.YELLOW));
            return false;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.size() < 2) {
            sender.sendMessage(Component.text("至少需要 2 名在线玩家才能开始。", NamedTextColor.RED));
            return false;
        }
        assignRoles(players);
        if (hiderCount() == 0 || roles.values().stream().noneMatch(this::isHunter)) {
            sender.sendMessage(Component.text("必须至少有 1 名躲藏者和 1 名抓捕者。", NamedTextColor.RED));
            roles.clear();
            return false;
        }

        phase = GamePhase.RUNNING;
        testMode = false;
        startedAtMillis = System.currentTimeMillis();
        announcedScaleStage = 0;
        resetRandomEventSchedule();
        lastResult = "进行中";
        playerStates.clear();
        lastLocations.clear();
        swordHits.clear();
        bowHits.clear();
        firecrackerHits.clear();
        lastHiderAttackers.clear();
        radarCooldowns.clear();
        whistleCooldowns.clear();
        whistleCycles.clear();
        pendingConversion.clear();
        for (Player player : players) {
            player.getScheduler().run(plugin, task -> {
                PlayerRoundState state = PlayerRoundState.capture(player);
                playerStates.put(player.getUniqueId(), state);
                lastLocations.put(player.getUniqueId(), player.getLocation().clone());
                applyRoleStart(player);
                giveRoleKit(player);
            }, () -> { });
        }
        broadcast(Component.text("躲猫猫开始！本局时长 10 分钟。", NamedTextColor.GOLD));
        sendStatus(sender);
        return true;
    }

    private void startTestGame(Player player, Role role) {
        if (phase == GamePhase.RUNNING) {
            stopGame(null, false);
        }
        phase = GamePhase.RUNNING;
        testMode = true;
        startedAtMillis = System.currentTimeMillis();
        announcedScaleStage = 0;
        resetRandomEventSchedule();
        lastResult = "单人测试中";
        roles.clear();
        playerStates.clear();
        whistleCooldowns.clear();
        whistleCycles.clear();
        firecrackerHits.clear();
        roles.put(player.getUniqueId(), role);
        player.getScheduler().run(plugin, task -> {
            playerStates.put(player.getUniqueId(), PlayerRoundState.capture(player));
            lastLocations.put(player.getUniqueId(), player.getLocation().clone());
            applyRoleStart(player);
            giveRoleKit(player);
            player.sendMessage(Component.text("已开始单人" + (role == Role.HIDER ? "躲藏者" : "抓捕者")
                    + "测试。", NamedTextColor.GREEN));
        }, () -> { });
    }

    private void adjustElapsed(long seconds) {
        if (phase == GamePhase.RUNNING) {
            startedAtMillis -= seconds * 1_000L;
            long elapsed = Math.max(0L, Math.min(ROUND_SECONDS - 1, elapsedSeconds()));
            startedAtMillis = System.currentTimeMillis() - elapsed * 1_000L;
            announcedScaleStage = scaleStage(elapsed);
        }
    }

    private void setElapsed(long seconds) {
        if (phase == GamePhase.RUNNING) {
            long elapsed = Math.max(0L, Math.min(ROUND_SECONDS - 1, seconds));
            startedAtMillis = System.currentTimeMillis() - elapsed * 1_000L;
            announcedScaleStage = scaleStage(elapsed);
            for (PlayerRoundState state : playerStates.values()) {
                state.scaleStage = -1;
            }
        }
    }

    private void assignRoles(List<Player> players) {
        roles.clear();
        List<Player> unassigned = new ArrayList<>();
        for (Player player : players) {
            Role role = configuredRoles.get(player.getUniqueId());
            if (role == null) {
                unassigned.add(player);
            } else {
                roles.put(player.getUniqueId(), role);
            }
        }
        int hunterCount = Math.max(1, players.size() / 4);
        long configuredHunters = roles.values().stream().filter(role -> role == Role.HUNTER).count();
        for (Player player : unassigned) {
            Role role = configuredHunters < hunterCount ? Role.HUNTER : Role.HIDER;
            roles.put(player.getUniqueId(), role);
            if (role == Role.HUNTER) {
                configuredHunters++;
            }
        }
        if (roles.values().stream().noneMatch(role -> role == Role.HIDER)) {
            UUID id = roles.keySet().iterator().next();
            roles.put(id, Role.HIDER);
        }
    }

    private void applyRoleStart(Player player) {
        Role role = roles.get(player.getUniqueId());
        if (role == Role.HIDER) {
            AttributeInstance scale = player.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(hiderScales[0]);
            }
            player.setPose(Pose.STANDING, false);
            updatePlayer(player, 0L);
        } else {
            updatePlayer(player, 0L);
        }
    }

    private void giveRoleKit(Player player) {
        if (roles.get(player.getUniqueId()) == Role.HIDER) {
            giveHiderKit(player);
        } else {
            giveHunterKit(player, roles.get(player.getUniqueId()) == Role.ELIMINATED_HUNTER);
        }
    }

    private void giveCurrentKit(Player player) {
        if (phase != GamePhase.RUNNING) {
            player.sendMessage(Component.text("当前没有进行中的游戏。", NamedTextColor.YELLOW));
            return;
        }
        giveRoleKit(player);
        player.sendMessage(Component.text("已发放你的躲猫猫道具。", NamedTextColor.GREEN));
    }

    private void giveHiderKit(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (grantEnabled(ItemGrant.HIDER_ABATUKAN)) {
            inventory.addItem(customItem(Material.MUSIC_DISC_CAT, "阿巴土坎片", "abatukan", 1,
                    "使用后加速 3 秒，随后疲劳 10 秒。"));
        }
        if (grantEnabled(ItemGrant.HIDER_NAIL_BRICK)) {
            inventory.addItem(nailBrick());
        }
        ItemGrant[] whistles = {ItemGrant.HIDER_WHISTLE_1, ItemGrant.HIDER_WHISTLE_2, ItemGrant.HIDER_WHISTLE_3};
        for (int level = 1; level <= 3; level++) {
            if (grantEnabled(whistles[level - 1])) {
                inventory.addItem(whistle(level));
            }
        }
    }

    private void giveHunterKit(Player player, boolean eliminated) {
        PlayerInventory inventory = player.getInventory();
        if (grantEnabled(ItemGrant.HUNTER_SWORD)) {
            inventory.addItem(customItem(Material.NETHERITE_SWORD, "下界合金剑", "hunter_sword", 1,
                    "两次命中可击杀躲藏者。"));
        }
        if (grantEnabled(ItemGrant.HUNTER_BOW)) {
            inventory.addItem(customItem(Material.BOW, "猎人弓", "hunter_bow", 1,
                    "两箭可击杀躲藏者。"));
        }
        if (grantEnabled(ItemGrant.HUNTER_ARROW)) {
            inventory.addItem(customItem(Material.ARROW, "猎人箭", "hunter_arrow_item", 16,
                    "猎人弓专用箭。"));
        }
        if (grantEnabled(ItemGrant.HUNTER_PEARL)) {
            inventory.addItem(customItem(Material.ENDER_PEARL, "追击末影珍珠", "hunter_pearl", 32,
                    "高速追击与位移。"));
        }
        if (grantEnabled(ItemGrant.HUNTER_GRENADE)) {
            inventory.addItem(customItem(Material.SNOWBALL, "手雷", "grenade", 3,
                    "命中后让躲藏者暴露 5 秒。"));
        }
        if (grantEnabled(ItemGrant.HUNTER_FIRECRACKER)) {
            inventory.addItem(customItem(Material.FIREWORK_STAR, "小摔炮", "small_firecracker", 3,
                    "同一躲藏者三次命中会造成递增效果。"));
        }
        if (!eliminated && grantEnabled(ItemGrant.HUNTER_RADAR)) {
            inventory.addItem(customItem(Material.COMPASS, "范围雷达", "chunk_radar", 1,
                    "显示当前雷达范围中的躲藏者数量。"));
        }
        if (!eliminated && grantEnabled(ItemGrant.HUNTER_TRACKING_RADAR)) {
            inventory.addItem(customItem(Material.ENDER_EYE, "追踪雷达", "tracking_radar", 1,
                    "发射追踪潜影贝子弹。"));
        }
    }

    private boolean grantEnabled(ItemGrant grant) {
        return itemGiveEnabled.getOrDefault(grant.configKey, true);
    }

    private ItemStack whistle(int level) {
        ItemStack item = customItem(Material.GOAT_HORN, "嘲讽哨 " + level, "whistle", 1,
                "播放等级 " + level + " 的全局音效。" );
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack nailBrick() {
        ItemStack item = new ItemStack(Material.BRICK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("固定棒", NamedTextColor.AQUA));
            meta.lore(List.of(Component.text("使用现有钉砖系统固定并调整位置。", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(nailBrickKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack customItem(Material material, String name, String kind, int amount, String lore) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.AQUA));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, kind);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String itemKind(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta() == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private boolean isItem(ItemStack item, String kind) {
        return kind.equals(itemKind(item));
    }

    private int whistleLevel(ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return 1;
        }
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1);
    }

    private void consumeOne(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void finishGame(String message) {
        if (phase != GamePhase.RUNNING) {
            return;
        }
        lastResult = message;
        List<UUID> participants = new ArrayList<>(playerStates.keySet());
        broadcast(Component.text(message, NamedTextColor.GOLD));
        celebrateVictory(participants, message);
        stopGame(null, false);
    }

    private void celebrateVictory(Collection<UUID> participants, String message) {
        boolean hidersWon = message.contains("躲藏者胜利");
        Component titleText = Component.text(hidersWon ? "躲藏者胜利！" : "抓捕者胜利！", NamedTextColor.GOLD);
        Component subtitleText = Component.text("本局游戏结束", NamedTextColor.YELLOW);
        Title title = Title.title(titleText, subtitleText,
                Title.Times.times(Duration.ofMillis(700), Duration.ofMillis(3_000), Duration.ofMillis(1_500)));
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.getScheduler().run(plugin, task -> {
                player.showTitle(title);
                Firework firework = player.getWorld().spawn(player.getLocation().clone().add(0, 1, 0), Firework.class);
                FireworkMeta meta = firework.getFireworkMeta();
                meta.setPower(1);
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(hidersWon ? Color.AQUA : Color.RED, Color.YELLOW)
                        .withFade(Color.WHITE)
                        .trail(true)
                        .flicker(true)
                        .build());
                firework.setFireworkMeta(meta);
            }, () -> { });
        }
    }

    public void stopGame(org.bukkit.command.CommandSender sender, boolean notify) {
        if (phase == GamePhase.WAITING) {
            if (sender != null) {
                sender.sendMessage(Component.text("当前没有进行中的游戏。", NamedTextColor.YELLOW));
            }
            return;
        }
        phase = GamePhase.WAITING;
        testMode = false;
        for (ScheduledTask task : revealTasks.values()) {
            task.cancel();
        }
        revealTasks.clear();
        for (ScheduledTask task : firecrackerParticleTasks.values()) {
            task.cancel();
        }
        firecrackerParticleTasks.clear();
        for (UUID id : new ArrayList<>(playerStates.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerRoundState state = playerStates.get(id);
            if (player != null && state != null) {
                player.getScheduler().run(plugin, task -> {
                    state.restore(player);
                    plugin.releaseAnchorForGame(player);
                    clearGameItems(player);
                }, () -> { });
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!playerStates.containsKey(player.getUniqueId())) {
                player.getScheduler().run(plugin, task -> clearGameItems(player), () -> { });
            }
        }
        playerStates.clear();
        roles.clear();
        lastLocations.clear();
        swordHits.clear();
        bowHits.clear();
        firecrackerHits.clear();
        lastHiderAttackers.clear();
        radarCooldowns.clear();
        whistleCooldowns.clear();
        whistleCycles.clear();
        pendingConversion.clear();
        for (UUID id : new ArrayList<>(gameEntities)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                entity.getScheduler().run(plugin, task -> entity.remove(), () -> { });
            }
        }
        gameEntities.clear();
        if (sender != null && notify) {
            sender.sendMessage(Component.text("躲猫猫游戏已停止。", NamedTextColor.YELLOW));
        }
    }

    private void clearGameItems(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isGameItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
        ItemStack cursor = player.getOpenInventory().getCursor();
        if (isGameItem(cursor)) {
            player.getOpenInventory().setCursor(null);
        }
    }

    private boolean isGameItem(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta() == null) {
            return false;
        }
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(itemKey, PersistentDataType.STRING)
                || data.has(nailBrickKey, PersistentDataType.BYTE);
    }

    private void broadcast(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    private boolean isHunter(Role role) {
        return role == Role.HUNTER || role == Role.ELIMINATED_HUNTER;
    }

    private String phaseText() {
        return phase == GamePhase.RUNNING ? "进行中" : "等待中";
    }

    private String formatRemaining() {
        if (phase != GamePhase.RUNNING) {
            return "10:00";
        }
        return formatRemaining(elapsedSeconds());
    }

    private String formatRemaining(long elapsed) {
        long remaining = Math.max(0L, ROUND_SECONDS - elapsed);
        return formatDuration(remaining);
    }

    private void sendStatus(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(Component.text("躲猫猫状态：" + phaseText()
                + "，剩余 " + formatRemaining() + "，躲藏者 " + hiderCount() + " 人。", NamedTextColor.AQUA));
    }

    private void broadcastRole(Player player) {
        Role role = roles.get(player.getUniqueId());
        player.sendMessage(Component.text("你的角色：" + (role == Role.HIDER ? "躲藏者" : "抓捕者"), NamedTextColor.GOLD));
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                             String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(Component.text("/hns admin | start | stop | randomize | setrole <玩家> <hider|hunter> | test <hider|hunter> | kit | status",
                    NamedTextColor.YELLOW));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("start") || sub.equals("stop") || sub.equals("randomize") || sub.equals("setrole")
                || sub.equals("admin")) && !sender.hasPermission("lightningcrowbar.hns")) {
            sender.sendMessage(Component.text("你没有躲猫猫管理权限。", NamedTextColor.RED));
            return true;
        }
        switch (sub) {
            case "admin" -> {
                if (sender instanceof Player player) {
                    openAdminMenu(player);
                } else {
                    sender.sendMessage(Component.text("控制台不能打开背包 GUI，请使用命令。", NamedTextColor.YELLOW));
                }
            }
            case "start" -> startGame(sender);
            case "stop" -> stopGame(sender, true);
            case "randomize" -> {
                randomizeConfiguredRoles();
                sender.sendMessage(Component.text("已随机预设角色。", NamedTextColor.GREEN));
            }
            case "setrole" -> setRole(sender, args);
            case "test" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    sender.sendMessage(Component.text("用法：/hns test <hider|hunter>", NamedTextColor.YELLOW));
                } else {
                    Role role = args[1].equalsIgnoreCase("hider") ? Role.HIDER
                            : args[1].equalsIgnoreCase("hunter") ? Role.HUNTER : null;
                    if (role == null) {
                        sender.sendMessage(Component.text("角色必须是 hider 或 hunter。", NamedTextColor.YELLOW));
                    } else {
                        startTestGame(player, role);
                    }
                }
            }
            case "kit" -> {
                if (sender instanceof Player player) {
                    giveCurrentKit(player);
                }
            }
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage(Component.text("未知子命令，使用 /hns help 查看。", NamedTextColor.YELLOW));
        }
        return true;
    }

    private void setRole(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("用法：/hns setrole <玩家> <hider|hunter>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("找不到在线玩家。", NamedTextColor.RED));
            return;
        }
        Role role = args[2].equalsIgnoreCase("hider") ? Role.HIDER
                : args[2].equalsIgnoreCase("hunter") ? Role.HUNTER : null;
        if (role == null) {
            sender.sendMessage(Component.text("角色必须是 hider 或 hunter。", NamedTextColor.YELLOW));
            return;
        }
        configuredRoles.put(target.getUniqueId(), role);
        sender.sendMessage(Component.text("已将 " + target.getName() + " 预设为 "
                + (role == Role.HIDER ? "躲藏者" : "抓捕者") + "。", NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                       String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("admin", "start", "stop", "randomize", "setrole", "test", "kit", "status"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setrole")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setrole")) {
            return filter(List.of("hider", "hunter"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return filter(List.of("hider", "hunter"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(Collection<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private enum GamePhase {
        WAITING,
        RUNNING
    }

    private enum Role {
        HIDER,
        HUNTER,
        ELIMINATED_HUNTER
    }

    private enum RadarMode {
        CHUNK(0),
        SQUARE_1(1),
        SQUARE_2(2),
        SQUARE_3(3),
        SQUARE_4(4),
        SQUARE_5(5),
        SQUARE_6(6),
        SQUARE_7(7),
        SQUARE_8(8),
        SQUARE_9(9),
        SQUARE_10(10);

        private final int sideLength;

        RadarMode(int sideLength) {
            this.sideLength = sideLength;
        }

        private RadarMode next() {
            RadarMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private enum AdminMenuPage {
        MAIN("躲猫猫控制台"),
        GAME("控制台 / 游戏控制"),
        PLAYERS("控制台 / 玩家与角色"),
        RULES("控制台 / 规则与体型"),
        HIDER_ITEMS("控制台 / 躲藏者道具"),
        HUNTER_ITEMS("控制台 / 抓捕者道具"),
        RADAR_EVENTS("控制台 / 雷达与随机事件");

        private final String title;

        AdminMenuPage(String title) {
            this.title = title;
        }
    }

    private enum ItemGrant {
        HIDER_ABATUKAN(false, "hider.abatukan", "阿巴土坎片"),
        HIDER_NAIL_BRICK(false, "hider.nail-brick", "固定棒"),
        HIDER_WHISTLE_1(false, "hider.whistle-1", "嘲讽哨 1"),
        HIDER_WHISTLE_2(false, "hider.whistle-2", "嘲讽哨 2"),
        HIDER_WHISTLE_3(false, "hider.whistle-3", "嘲讽哨 3"),
        HUNTER_SWORD(true, "hunter.sword", "下界合金剑"),
        HUNTER_BOW(true, "hunter.bow", "猎人弓"),
        HUNTER_ARROW(true, "hunter.arrow", "猎人箭"),
        HUNTER_PEARL(true, "hunter.pearl", "末影珍珠"),
        HUNTER_GRENADE(true, "hunter.grenade", "手雷"),
        HUNTER_FIRECRACKER(true, "hunter.small-firecracker", "小摔炮"),
        HUNTER_RADAR(true, "hunter.radar", "范围雷达"),
        HUNTER_TRACKING_RADAR(true, "hunter.tracking-radar", "追踪雷达");

        private final boolean hunter;
        private final String configKey;
        private final String displayName;

        ItemGrant(boolean hunter, String configKey, String displayName) {
            this.hunter = hunter;
            this.configKey = configKey;
            this.displayName = displayName;
        }
    }

    private enum RandomEventMode {
        RANDOM,
        TIMED
    }

    private enum RandomEventType {
        HIDER_REVEAL("hider-reveal", "全体暴露"),
        HIDER_SLOW("hider-slow", "躲藏者减速"),
        HUNTER_HASTE("hunter-haste", "抓捕者加速"),
        HIDER_BOOST("hider-boost", "躲藏者加速");

        private final String configKey;
        private final String displayName;

        RandomEventType(String configKey, String displayName) {
            this.configKey = configKey;
            this.displayName = displayName;
        }
    }

    private static final class AdminMenuHolder implements InventoryHolder {
        private final AdminMenuPage page;
        private Inventory inventory;

        private AdminMenuHolder(AdminMenuPage page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class PlayerRoundState {
        private final Map<PotionEffectType, PotionEffect> effects = new HashMap<>();
        private final Double originalScale;
        private final Pose originalPose;
        private int scaleStage = -1;
        private long boostUntil;
        private long fatigueUntil;
        private long revealUntil;
        private long eventSlowUntil;
        private int eventSlowAmplifier;
        private long firecrackerSlowUntil;
        private long eventSpeedUntil;
        private int eventSpeedAmplifier;

        private PlayerRoundState(Double originalScale, Pose originalPose) {
            this.originalScale = originalScale;
            this.originalPose = originalPose;
        }

        private static PlayerRoundState capture(Player player) {
            AttributeInstance scale = player.getAttribute(Attribute.SCALE);
            PlayerRoundState state = new PlayerRoundState(scale == null ? null : scale.getBaseValue(), player.getPose());
            for (PotionEffect effect : player.getActivePotionEffects()) {
                state.effects.put(effect.getType(), effect);
            }
            return state;
        }

        private void restore(Player player) {
            for (PotionEffectType type : List.of(PotionEffectType.SPEED, PotionEffectType.SLOWNESS,
                    PotionEffectType.STRENGTH, PotionEffectType.JUMP_BOOST, PotionEffectType.GLOWING)) {
                player.removePotionEffect(type);
            }
            for (PotionEffect effect : effects.values()) {
                player.addPotionEffect(effect);
            }
            AttributeInstance scale = player.getAttribute(Attribute.SCALE);
            if (scale != null && originalScale != null) {
                scale.setBaseValue(originalScale);
            }
            player.setPose(originalPose, false);
            player.setGlowing(false);
        }
    }
}
