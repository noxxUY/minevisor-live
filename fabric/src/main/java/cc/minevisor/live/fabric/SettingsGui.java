package cc.minevisor.live.fabric;

import cc.minevisor.live.DiscordAdmin;
import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.SettingsMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

final class SettingsGui extends ChestMenu {

    private final MinevisorLiveMod mod;
    private final SimpleContainer items;

    private SettingsGui(int id, Inventory playerInventory, SimpleContainer items, MinevisorLiveMod mod) {
        super(MenuType.GENERIC_9x4, id, playerInventory, items, SettingsMenu.ROWS);
        this.items = items;
        this.mod = mod;
        fill();
    }

    static void open(ServerPlayer player, MinevisorLiveMod mod) {
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new SettingsGui(id, inv, new SimpleContainer(SettingsMenu.ROWS * 9), mod),
                Component.literal(SettingsMenu.TITLE)));
    }

    private void fill() {
        for (int i = 0; i < items.getContainerSize(); i++) items.setItem(i, ItemStack.EMPTY);
        for (SettingsMenu.Slot slot : SettingsMenu.build(mod.discordConfig())) items.setItem(slot.index(), stack(slot));
    }

    private static MutableComponent line(SettingsMenu.Line line) {
        ChatFormatting color = switch (line.color()) {
            case "gray" -> ChatFormatting.GRAY;
            case "dark_gray" -> ChatFormatting.DARK_GRAY;
            case "green" -> ChatFormatting.GREEN;
            case "red" -> ChatFormatting.RED;
            case "aqua" -> ChatFormatting.AQUA;
            case "gold" -> ChatFormatting.GOLD;
            case "yellow" -> ChatFormatting.YELLOW;
            default -> ChatFormatting.WHITE;
        };
        return Component.literal(line.text()).withStyle(s -> s
                .withColor(color)
                .withBold(line.bold())
                .withItalic(false));
    }

    private static ItemStack stack(SettingsMenu.Slot slot) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(slot.item())).orElse(Items.PAPER);
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, line(slot.name()));
        List<Component> lore = new ArrayList<>();
        for (SettingsMenu.Line l : slot.lore()) lore.add(line(l));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        if (slot.glow()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (player instanceof ServerPlayer sp && slotId >= 0 && slotId < SettingsMenu.ROWS * 9) handle(sp, slotId);
        sendAllDataToRemote();
    }

    private void handle(ServerPlayer player, int slotId) {
        if (!player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            player.closeContainer();
            return;
        }
        DiscordConfig config = mod.discordConfig();
        SettingsMenu.Slot slot = SettingsMenu.at(SettingsMenu.build(config), slotId);
        if (slot == null) return;
        switch (slot.action()) {
            case TOGGLE -> {
                MinevisorCommand.change(player.createCommandSourceStack(), mod, slot.setting(),
                        DiscordAdmin.isOn(config, slot.setting()) ? "off" : "on");
                click(player);
                fill();
            }
            case CYCLE_BANNER -> {
                String banner = config.bannerUrl() == null || config.bannerUrl().isBlank() ? "auto" : config.bannerUrl();
                MinevisorCommand.change(player.createCommandSourceStack(), mod, "banner", banner.equalsIgnoreCase("auto") ? "off" : "auto");
                click(player);
                fill();
            }
            case SUGGEST -> {
                player.closeContainer();
                player.sendSystemMessage(Component.literal("» Click here, then paste or type the value")
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.SuggestCommand(slot.setting()))));
            }
            case LINK -> {
                player.closeContainer();
                MinevisorCommand.link(player.createCommandSourceStack(), mod);
            }
            case CLOSE -> player.closeContainer();
            case NONE -> {
            }
        }
    }

    private static void click(ServerPlayer player) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.6f, 1.3f);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
