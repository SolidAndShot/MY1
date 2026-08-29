package com.solidandshot.lightningcrowbar;

import org.bukkit.Bukkit;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LightningCrowbarPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Component CROWBAR_NAME = Component.text("撬棍", NamedTextColor.GOLD);
    private NamespacedKey crowbarKey;

    @Override
    public void onEnable() {
        crowbarKey = new NamespacedKey(this, "lightning_crowbar");
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("lightningcrowbar") != null) {
            getCommand("lightningcrowbar").setExecutor(this);
            getCommand("lightningcrowbar").setTabCompleter(this);
        }

        registerRecipe();
        getLogger().info("LightningCrowbar enabled on Luminol/Folia.");
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(new NamespacedKey(this, "lightning_crowbar"));
    }

    private void registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "lightning_crowbar");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createCrowbar());
        recipe.shape(" I ", "ISI", " S ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(recipe);
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

    private boolean isCrowbar(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_PICKAXE || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(crowbarKey, PersistentDataType.BYTE);
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("用法: /" + label + " give [玩家]", NamedTextColor.YELLOW));
            return true;
        }

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
            recipient.getInventory().addItem(createCrowbar());
            recipient.sendMessage(Component.text("你获得了一个 ", NamedTextColor.GREEN)
                    .append(CROWBAR_NAME)
                    .append(Component.text("。", NamedTextColor.GREEN)));
        }, () -> sender.sendMessage(Component.text("目标玩家当前不可用。", NamedTextColor.RED)));
        sender.sendMessage(Component.text("已将撬棍发给 " + recipient.getName() + "。", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterCompletions(List.of("give"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filterCompletions(names, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterCompletions(List<String> values, String input) {
        String lowerInput = input.toLowerCase();
        return values.stream()
                .filter(value -> value.toLowerCase().startsWith(lowerInput))
                .sorted()
                .toList();
    }
}
