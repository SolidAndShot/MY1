package com.solidandshot.lightningcrowbar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Input;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LightningCrowbarPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Component CROWBAR_NAME = Component.text("撬棍", NamedTextColor.GOLD);
    private static final Component NAIL_BRICK_NAME = Component.text("钉砖", NamedTextColor.AQUA);
    private static final double FINE_STEP = 0.05;
    private static final double LARGE_STEP = 0.5;
    private static final double POSITION_EPSILON = 0.0001;

    private NamespacedKey crowbarKey;
    private NamespacedKey nailBrickKey;
    private final Map<UUID, AnchorState> anchors = new ConcurrentHashMap<>();
    private final Map<UUID, InputState> inputStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRightClicks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        crowbarKey = new NamespacedKey(this, "lightning_crowbar");
        nailBrickKey = new NamespacedKey(this, "nail_brick");
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("lightningcrowbar") != null) {
            getCommand("lightningcrowbar").setExecutor(this);
            getCommand("lightningcrowbar").setTabCompleter(this);
        }

        registerRecipes();
        getLogger().info("LightningCrowbar enabled on Luminol/Folia.");
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(new NamespacedKey(this, "lightning_crowbar"));
        Bukkit.removeRecipe(new NamespacedKey(this, "nail_brick"));
        anchors.clear();
        inputStates.clear();
        lastRightClicks.clear();
    }

    private void registerRecipes() {
        NamespacedKey crowbarRecipeKey = new NamespacedKey(this, "lightning_crowbar");
        ShapedRecipe crowbarRecipe = new ShapedRecipe(crowbarRecipeKey, createCrowbar());
        crowbarRecipe.shape(" I ", "ISI", " S ");
        crowbarRecipe.setIngredient('I', Material.IRON_INGOT);
        crowbarRecipe.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(crowbarRecipe);

        NamespacedKey nailBrickRecipeKey = new NamespacedKey(this, "nail_brick");
        ShapedRecipe nailBrickRecipe = new ShapedRecipe(nailBrickRecipeKey, createNailBrick());
        nailBrickRecipe.shape(" I ", "IBI", " I ");
        nailBrickRecipe.setIngredient('I', Material.IRON_INGOT);
        nailBrickRecipe.setIngredient('B', Material.BRICK);
        Bukkit.addRecipe(nailBrickRecipe);
    }

    private ItemStack createCrowbar() {
        ItemStack item = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(CROWBAR_NAME);
        meta.lore(List.of(Component.text("攻击生物时召唤闪电", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(crowbarKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNailBrick() {
        ItemStack item = new ItemStack(Material.BRICK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(NAIL_BRICK_NAME);
        meta.lore(List.of(Component.text("右键固定位置，再次右键进入调整模式", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(nailBrickKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCrowbar(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_PICKAXE || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(crowbarKey, PersistentDataType.BYTE);
    }

    private boolean isNailBrick(ItemStack item) {
        if (item == null || item.getType() != Material.BRICK || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(nailBrickKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity target = event.getEntity();
        if (!(damager instanceof Player player)
                || !(target instanceof LivingEntity livingTarget)
                || !isCrowbar(player.getInventory().getItemInMainHand())) {
            return;
        }

        // Entity damage events run on the target's region thread in Folia.
        livingTarget.getWorld().strikeLightning(livingTarget.getLocation());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onNailBrickInteract(PlayerInteractEvent event) {
        if (!isNailBrick(event.getItem())) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            handleRightClick(event.getPlayer());
        } else if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)
                && anchors.get(event.getPlayer().getUniqueId()) instanceof AnchorState state
                && state.mode == AnchorMode.ADJUSTING) {
            event.setCancelled(true);
            state.mode = AnchorMode.FIXED;
            event.getPlayer().sendMessage(Component.text("调整完成，已重新固定。", NamedTextColor.GREEN));
        }
    }

    private void handleRightClick(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastRightClicks.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 150) {
            return;
        }

        AnchorState state = anchors.get(player.getUniqueId());
        if (state == null) {
            anchors.put(player.getUniqueId(), new AnchorState(player.getLocation()));
            inputStates.put(player.getUniqueId(), InputState.from(player.getCurrentInput()));
            player.sendMessage(Component.text("已固定当前位置。再次右键钉砖进入调整模式。", NamedTextColor.GREEN));
        } else if (state.mode == AnchorMode.FIXED) {
            state.mode = AnchorMode.ADJUSTING;
            player.sendMessage(Component.text(
                    "已进入调整模式：普通移动微调 0.05 格，冲刺移动大调 0.5 格；W/S 前后、A/D 左右，跳跃上移，Shift 下移；左键完成。",
                    NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        InputState previous = inputStates.getOrDefault(playerId, InputState.EMPTY);
        InputState current = InputState.from(event.getInput());
        inputStates.put(playerId, current);

        AnchorState state = anchors.get(playerId);
        if (state == null) {
            return;
        }

        if (state.mode == AnchorMode.FIXED) {
            if (current.movementActive()) {
                anchors.remove(playerId, state);
                player.sendMessage(Component.text("你已使用移动键，固定状态已解除。", NamedTextColor.YELLOW));
            }
            return;
        }

        if (state.mode == AnchorMode.ADJUSTING) {
            adjustFromInput(state, previous, current, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        AnchorState state = anchors.get(playerId);
        if (state == null) {
            return;
        }

        InputState input = inputStates.getOrDefault(playerId, InputState.EMPTY);
        if (state.mode == AnchorMode.FIXED) {
            if (input.movementActive()) {
                anchors.remove(playerId, state);
                event.getPlayer().sendMessage(Component.text("你已使用移动键，固定状态已解除。", NamedTextColor.YELLOW));
                return;
            }

            if (event instanceof PlayerTeleportEvent) {
                event.setCancelled(true);
                return;
            }

            event.setTo(anchorView(state.location, to));
            event.getPlayer().setVelocity(new Vector());
            return;
        }

        if (state.mode == AnchorMode.ADJUSTING && !samePosition(to, state.location)) {
            event.setTo(anchorView(state.location, to));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        AnchorState state = anchors.get(event.getPlayer().getUniqueId());
        InputState input = inputStates.getOrDefault(event.getPlayer().getUniqueId(), InputState.EMPTY);
        if (state != null && state.mode == AnchorMode.FIXED && !input.movementActive()) {
            event.setVelocity(new Vector());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        anchors.remove(playerId);
        inputStates.remove(playerId);
        lastRightClicks.remove(playerId);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        anchors.remove(event.getEntity().getUniqueId());
    }

    private void adjustFromInput(AnchorState state, InputState previous, InputState current, Player player) {
        double step = current.sprint ? LARGE_STEP : FINE_STEP;
        float yaw = player.getLocation().getYaw();
        double yawRadians = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double rightX = Math.cos(yawRadians);
        double rightZ = Math.sin(yawRadians);
        double moveX = 0;
        double moveY = 0;
        double moveZ = 0;

        if (current.forward && !previous.forward) {
            moveX += forwardX * step;
            moveZ += forwardZ * step;
        }
        if (current.backward && !previous.backward) {
            moveX -= forwardX * step;
            moveZ -= forwardZ * step;
        }
        if (current.right && !previous.right) {
            moveX += rightX * step;
            moveZ += rightZ * step;
        }
        if (current.left && !previous.left) {
            moveX -= rightX * step;
            moveZ -= rightZ * step;
        }
        if (current.jump && !previous.jump) {
            moveY += step;
        }
        if (current.sneak && !previous.sneak) {
            moveY -= step;
        }
        if (moveX == 0 && moveY == 0 && moveZ == 0) {
            return;
        }

        state.location = offset(state.location, moveX, moveY, moveZ, player.getLocation());
        player.teleportAsync(state.location.clone());
    }

    private Location offset(Location source, double x, double y, double z, Location view) {
        Location result = source.clone().add(x, y, z);
        result.setYaw(view.getYaw());
        result.setPitch(view.getPitch());
        return result;
    }

    private Location anchorView(Location anchor, Location view) {
        Location result = anchor.clone();
        result.setYaw(view.getYaw());
        result.setPitch(view.getPitch());
        return result;
    }

    private boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && Math.abs(first.getX() - second.getX()) <= POSITION_EPSILON
                && Math.abs(first.getY() - second.getY()) <= POSITION_EPSILON
                && Math.abs(first.getZ() - second.getZ()) <= POSITION_EPSILON;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0
                || (!args[0].equalsIgnoreCase("give") && !args[0].equalsIgnoreCase("givebrick"))) {
            sender.sendMessage(Component.text("用法: /" + label + " give [玩家] 或 /" + label + " givebrick [玩家]",
                    NamedTextColor.YELLOW));
            return true;
        }

        boolean giveNailBrick = args[0].equalsIgnoreCase("givebrick");
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("找不到在线玩家: " + args[1], NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("控制台使用此命令时必须指定玩家。", NamedTextColor.RED));
            return true;
        }

        Player recipient = target;
        recipient.getScheduler().run(this, task -> {
            Component itemName = giveNailBrick ? NAIL_BRICK_NAME : CROWBAR_NAME;
            recipient.getInventory().addItem(giveNailBrick ? createNailBrick() : createCrowbar());
            recipient.sendMessage(Component.text("你获得了一个 ", NamedTextColor.GREEN)
                    .append(itemName)
                    .append(Component.text("。", NamedTextColor.GREEN)));
        }, () -> sender.sendMessage(Component.text("目标玩家当前不可用。", NamedTextColor.RED)));
        String itemName = giveNailBrick ? "钉砖" : "撬棍";
        sender.sendMessage(Component.text("已将" + itemName + "发给 " + recipient.getName() + "。", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterCompletions(List.of("give", "givebrick"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givebrick"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filterCompletions(names, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterCompletions(List<String> values, String input) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowerInput))
                .sorted()
                .toList();
    }

    private enum AnchorMode {
        FIXED,
        ADJUSTING
    }

    private static final class AnchorState {
        private Location location;
        private AnchorMode mode = AnchorMode.FIXED;

        private AnchorState(Location location) {
            this.location = location.clone();
        }
    }

    private static final class InputState {
        private static final InputState EMPTY = new InputState(false, false, false, false, false, false, false);

        private final boolean forward;
        private final boolean backward;
        private final boolean left;
        private final boolean right;
        private final boolean jump;
        private final boolean sneak;
        private final boolean sprint;

        private InputState(boolean forward, boolean backward, boolean left, boolean right,
                           boolean jump, boolean sneak, boolean sprint) {
            this.forward = forward;
            this.backward = backward;
            this.left = left;
            this.right = right;
            this.jump = jump;
            this.sneak = sneak;
            this.sprint = sprint;
        }

        private static InputState from(Input input) {
            return new InputState(
                    input.isForward(),
                    input.isBackward(),
                    input.isLeft(),
                    input.isRight(),
                    input.isJump(),
                    input.isSneak(),
                    input.isSprint());
        }

        private boolean movementActive() {
            return forward || backward || left || right || jump || sneak;
        }
    }
}
