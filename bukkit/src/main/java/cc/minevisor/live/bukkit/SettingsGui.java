package cc.minevisor.live.bukkit;

import cc.minevisor.live.DiscordAdmin;
import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.SettingsMenu;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

final class SettingsGui implements Listener {

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final MinevisorLivePlugin plugin;

    SettingsGui(MinevisorLivePlugin plugin) {
        this.plugin = plugin;
    }

    static void open(MinevisorLivePlugin plugin, Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, SettingsMenu.ROWS * 9, SettingsMenu.TITLE);
        holder.inventory = inv;
        fill(inv, plugin.discordConfig());
        player.openInventory(inv);
    }

    private static void fill(Inventory inv, DiscordConfig config) {
        inv.clear();
        for (SettingsMenu.Slot slot : SettingsMenu.build(config)) inv.setItem(slot.index(), item(slot));
    }

    private static String color(SettingsMenu.Line line) {
        ChatColor c = switch (line.color()) {
            case "gray" -> ChatColor.GRAY;
            case "dark_gray" -> ChatColor.DARK_GRAY;
            case "green" -> ChatColor.GREEN;
            case "red" -> ChatColor.RED;
            case "aqua" -> ChatColor.AQUA;
            case "gold" -> ChatColor.GOLD;
            case "yellow" -> ChatColor.YELLOW;
            default -> ChatColor.WHITE;
        };
        return c + (line.bold() ? ChatColor.BOLD.toString() : "") + line.text();
    }

    private static ItemStack item(SettingsMenu.Slot slot) {
        Material material = Material.matchMaterial(slot.item());
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(color(slot.name()));
        List<String> lore = new ArrayList<>();
        for (SettingsMenu.Line line : slot.lore()) lore.add(color(line));
        meta.setLore(lore);
        if (slot.glow()) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != top) return;
        if (!player.hasPermission("minevisor.admin")) {
            player.closeInventory();
            return;
        }
        DiscordConfig config = plugin.discordConfig();
        SettingsMenu.Slot slot = SettingsMenu.at(SettingsMenu.build(config), e.getRawSlot());
        if (slot == null) return;
        switch (slot.action()) {
            case TOGGLE -> {
                MinevisorCommand.change(plugin, player, slot.setting(), DiscordAdmin.isOn(config, slot.setting()) ? "off" : "on");
                click(player);
                fill(top, plugin.discordConfig());
            }
            case CYCLE_BANNER -> {
                String banner = config.bannerUrl() == null || config.bannerUrl().isBlank() ? "auto" : config.bannerUrl();
                MinevisorCommand.change(plugin, player, "banner", banner.equalsIgnoreCase("auto") ? "off" : "auto");
                click(player);
                fill(top, plugin.discordConfig());
            }
            case SUGGEST -> {
                player.closeInventory();
                TextComponent prompt = new TextComponent("» Click here, then paste or type the value");
                prompt.setColor(net.md_5.bungee.api.ChatColor.AQUA);
                prompt.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, slot.setting()));
                player.spigot().sendMessage(prompt);
            }
            case LINK -> {
                player.closeInventory();
                player.performCommand("minevisor link");
            }
            case CLOSE -> player.closeInventory();
            case NONE -> {
            }
        }
    }

    private static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }
}
