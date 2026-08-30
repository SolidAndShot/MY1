package com.solidandshot.lightningcrowbar;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private static final double[] HIDER_SCALES = {1.0, 1.5, 2.0, 3.0};
    private static final int MAX_HITS = 2;
    private static final long BOOST_MILLIS = 3_000L;
    private static final long FATIGUE_MILLIS = 10_000L;
    private static final long RADAR_COOLDOWN_MILLIS = 30_000L;
    private static final long REVEAL_MILLIS = 5_000L;

    private final LightningCrowbarPlugin plugin;
    private final Map<UUID, Role> roles = new ConcurrentHashMap<>();
    private final Map<UUID, Role> configuredRoles = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRoundState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> radarCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> swordHits = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bowHits = new ConcurrentHashMap<>();
    private final Set<UUID> pendingConversion = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> revealTasks = new ConcurrentHashMap<>();
    private final Set<UUID> gameEntities = ConcurrentHashMap.newKeySet();

    private NamespacedKey itemKey;
    private NamespacedKey levelKey;
    private NamespacedKey projectileKey;
    private NamespacedKey targetKey;
    private NamespacedKey nailBrickKey;
    private ScheduledTask gameTask;
    private volatile GamePhase phase = GamePhase.WAITING;
    private volatile long startedAtMillis;
    private volatile String lastResult = "尚未开始";
    private volatile boolean testMode;

    public HideAndSeekManager(LightningCrowbarPlugin plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "hns_item");
        levelKey = new NamespacedKey(plugin, "hns_whistle_level");
        projectileKey = new NamespacedKey(plugin, "hns_projectile");
        targetKey = new NamespacedKey(plugin, "hns_target");
        nailBrickKey = new NamespacedKey(plugin, "nail_brick");
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
        stopGame(null, false);
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
                player.sendMessage(Component.text("你的体型已变化为 " + HIDER_SCALES[stage] + " 格。",
                        NamedTextColor.YELLOW));
            }
            applyHiderEffects(player, state, elapsed);
        } else {
            applyHunterEffects(player, role == Role.ELIMINATED_HUNTER, elapsed);
        }

        if (state.revealUntil > System.currentTimeMillis()) {
            player.setGlowing(true);
        }
        String roleText = role == Role.HIDER ? "躲藏者" : role == Role.HUNTER ? "抓捕者" : "普通抓捕者";
        player.sendActionBar(Component.text("[" + roleText + "] 剩余 " + formatRemaining()
                + " | 躲藏者 " + hiderCount(), NamedTextColor.AQUA));
    }

    private void applyHiderScaleAndPose(Player player, PlayerRoundState state, int stage) {
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(HIDER_SCALES[stage]);
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
        } else if (elapsed < HIDER_FAST_SECONDS) {
            addEffect(player, PotionEffectType.SPEED, 4);
        } else {
            addEffect(player, PotionEffectType.SLOWNESS, 5);
        }
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    private void applyHunterEffects(Player player, boolean eliminated, long elapsed) {
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.GLOWING);
        addEffect(player, PotionEffectType.SPEED, eliminated ? 0 : 1);
        addEffect(player, PotionEffectType.JUMP_BOOST, eliminated ? 0 : 1);
        int strength = eliminated ? 0 : Math.min(4, (int) (elapsed / 120L));
        addEffect(player, PotionEffectType.STRENGTH, strength);
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
        for (int i = 1; i < SCALE_THRESHOLDS.length; i++) {
            if (elapsed >= SCALE_THRESHOLDS[i]) {
                stage = i;
            }
        }
        return stage;
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
        admin.openInventory(createAdminInventory());
    }

    private Inventory createAdminInventory() {
        AdminMenuHolder holder = new AdminMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("躲猫猫控制台"));
        holder.inventory = inventory;
        inventory.setItem(10, menuItem(Material.LIME_WOOL, "开始 10 分钟游戏", "start"));
        inventory.setItem(11, menuItem(Material.RED_WOOL, "停止游戏", "stop"));
        inventory.setItem(12, menuItem(Material.CLOCK, "刷新状态", "status"));
        inventory.setItem(13, menuItem(Material.COMPASS, "随机分配角色", "randomize"));
        inventory.setItem(14, menuItem(Material.CHEST, "发放当前角色道具", "kit"));
        inventory.setItem(15, menuItem(Material.REPEATER, "单人测试：躲藏者", "test_hider"));
        inventory.setItem(16, menuItem(Material.TARGET, "单人测试：抓捕者", "test_hunter"));
        inventory.setItem(22, ComponentItem(Material.PAPER, "状态：" + phaseText(),
                "剩余时间：" + formatRemaining(), "躲藏者：" + hiderCount(), "上局：" + lastResult));
        inventory.setItem(19, menuItem(Material.REDSTONE_TORCH, "时间 -30 秒", "time_minus_30"));
        inventory.setItem(20, menuItem(Material.CLOCK, "时间 +30 秒", "time_plus_30"));
        inventory.setItem(21, menuItem(Material.COPPER_BLOCK, "跳到 3 分钟", "time_180"));
        inventory.setItem(23, menuItem(Material.IRON_BLOCK, "跳到 5 分钟", "time_300"));
        inventory.setItem(24, menuItem(Material.NETHERITE_BLOCK, "跳到 8 分钟", "time_480"));
        inventory.setItem(25, menuItem(Material.GOLDEN_SWORD, "强制抓捕者胜利", "win_hunter"));
        inventory.setItem(26, menuItem(Material.GOLDEN_APPLE, "强制躲藏者胜利", "win_hider"));

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < players.size() && i < 27; i++) {
            Player player = players.get(i);
            inventory.setItem(27 + i, playerHead(player));
        }
        return inventory;
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
            player.playSound(player.getLocation(), Sound.MUSIC_DISC_CAT, SoundCategory.PLAYERS, 1.0f, 1.4f);
            player.sendMessage(Component.text("阿巴图坎片生效：巨额加速 3 秒，随后疲劳 10 秒。", NamedTextColor.GREEN));
        } else if (kind.equals("whistle")) {
            if (role != Role.HIDER) {
                return;
            }
            event.setCancelled(true);
            int level = whistleLevel(item);
            consumeOne(player);
            playWhistle(player, level);
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

    private void showChunkRadar(Player hunter) {
        Location hunterLocation = hunter.getLocation();
        int chunkX = hunterLocation.getBlockX() >> 4;
        int chunkZ = hunterLocation.getBlockZ() >> 4;
        int count = 0;
        for (Map.Entry<UUID, Role> entry : roles.entrySet()) {
            if (entry.getValue() != Role.HIDER) {
                continue;
            }
            Player hider = Bukkit.getPlayer(entry.getKey());
            Location location = lastLocations.get(entry.getKey());
            if (hider != null && location != null && location.getWorld().equals(hunterLocation.getWorld())
                    && (location.getBlockX() >> 4) == chunkX
                    && (location.getBlockZ() >> 4) == chunkZ) {
                count++;
            }
        }
        hunter.sendMessage(Component.text("区块雷达：当前区块有 " + count + " 名躲藏者。", NamedTextColor.AQUA));
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
            spawned.setVelocity(direction.multiply(1.2));
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
                .min(Comparator.comparingDouble(player -> lastLocations.get(player.getUniqueId())
                        .distanceSquared(sourceLocation)))
                .orElse(null);
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
            return;
        }
        event.setCancelled(true);
        Map<UUID, Integer> hitMap = weapon.equals("sword") ? swordHits : bowHits;
        int hits = hitMap.merge(target.getUniqueId(), 1, Integer::sum);
        if (hits >= MAX_HITS) {
            hitMap.remove(target.getUniqueId());
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
        } else if (kind.equals("tracking")) {
            Entity hit = event.getHitEntity();
            if (hit instanceof Player player && roles.get(player.getUniqueId()) == Role.HIDER) {
                reveal(player, REVEAL_MILLIS);
            }
        }
        projectile.remove();
    }

    private void reveal(Player player, long duration) {
        player.getScheduler().run(plugin, task -> beginReveal(player, duration), () -> { });
    }

    private void beginReveal(Player player, long duration) {
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
                        viewerTask -> viewer.playSound(soundLocation, Sound.ENTITY_SHULKER_SHOOT,
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
            if (killer != null && roles.get(killer.getUniqueId()) == Role.HUNTER) {
                radarCooldowns.put(killer.getUniqueId(), 0L);
                killer.sendMessage(Component.text("成功击杀躲藏者，追踪雷达冷却已重置。", NamedTextColor.GREEN));
            }
            pendingConversion.add(id);
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
        if (value.equals("start")) {
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
        } else {
            try {
                UUID target = UUID.fromString(value);
                toggleConfiguredRole(target);
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
        admin.openInventory(createAdminInventory());
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
        lastResult = "进行中";
        playerStates.clear();
        lastLocations.clear();
        swordHits.clear();
        bowHits.clear();
        radarCooldowns.clear();
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
        lastResult = "单人测试中";
        roles.clear();
        playerStates.clear();
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
        }
    }

    private void setElapsed(long seconds) {
        if (phase == GamePhase.RUNNING) {
            long elapsed = Math.max(0L, Math.min(ROUND_SECONDS - 1, seconds));
            startedAtMillis = System.currentTimeMillis() - elapsed * 1_000L;
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
                scale.setBaseValue(1.0);
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
        inventory.addItem(customItem(Material.MUSIC_DISC_CAT, "阿巴图坎片", "abatukan", 1,
                "使用后加速 3 秒，随后疲劳 10 秒。"));
        inventory.addItem(nailBrick());
        for (int level = 1; level <= 3; level++) {
            inventory.addItem(whistle(level));
        }
    }

    private void giveHunterKit(Player player, boolean eliminated) {
        PlayerInventory inventory = player.getInventory();
        inventory.addItem(customItem(Material.NETHERITE_SWORD, "下界合金剑", "hunter_sword", 1,
                "两次命中可击杀躲藏者。"));
        inventory.addItem(customItem(Material.BOW, "猎人弓", "hunter_bow", 1,
                "两箭可击杀躲藏者。"));
        inventory.addItem(customItem(Material.ARROW, "猎人箭", "hunter_arrow_item", 16,
                "猎人弓专用箭。"));
        inventory.addItem(customItem(Material.ENDER_PEARL, "追击末影珍珠", "hunter_pearl", 32,
                "高速追击与位移。"));
        inventory.addItem(customItem(Material.SNOWBALL, "手雷", "grenade", 3,
                "命中后让躲藏者暴露 5 秒。"));
        if (!eliminated) {
            inventory.addItem(customItem(Material.COMPASS, "区块雷达", "chunk_radar", 1,
                    "显示当前区块中的躲藏者数量。"));
            inventory.addItem(customItem(Material.ENDER_EYE, "追踪雷达", "tracking_radar", 1,
                    "发射追踪潜影贝子弹。"));
        }
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
        broadcast(Component.text(message, NamedTextColor.GOLD));
        stopGame(null, false);
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
        for (UUID id : new ArrayList<>(playerStates.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerRoundState state = playerStates.get(id);
            if (player != null && state != null) {
                player.getScheduler().run(plugin, task -> state.restore(player), () -> { });
            }
        }
        playerStates.clear();
        roles.clear();
        lastLocations.clear();
        swordHits.clear();
        bowHits.clear();
        radarCooldowns.clear();
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
        long remaining = Math.max(0, ROUND_SECONDS - elapsedSeconds());
        return String.format(Locale.ROOT, "%02d:%02d", remaining / 60, remaining % 60);
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

    private static final class AdminMenuHolder implements InventoryHolder {
        private Inventory inventory;

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
