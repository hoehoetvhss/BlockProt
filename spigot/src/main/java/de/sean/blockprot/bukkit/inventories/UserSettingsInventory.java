/*
 * Copyright (C) 2021 - 2025 spnda
 * This file is part of BlockProt <https://github.com/spnda/BlockProt>.
 *
 * BlockProt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BlockProt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BlockProt.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.sean.blockprot.bukkit.inventories;

import de.sean.blockprot.bukkit.BlockProt;
import de.sean.blockprot.bukkit.TranslationKey;
import de.sean.blockprot.bukkit.Translator;
import de.sean.blockprot.bukkit.nbt.PlayerSettingsHandler;
import de.sean.blockprot.bukkit.nbt.StatHandler;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class UserSettingsInventory extends BlockProtInventory {
    @Override
    int getSize() {
        return InventoryConstants.singleLine;
    }

    @NotNull
    @Override
    String getTranslatedInventoryName() {
        return Translator.get(TranslationKey.INVENTORIES__USER_SETTINGS);
    }

    @Override
    public void onClick(@NotNull InventoryClickEvent event, @NotNull InventoryState state) {
        Player player = (Player) event.getWhoClicked();
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        switch (item.getType()) {
            case BARRIER -> {
                // Lock on place button, default value is true
                PlayerSettingsHandler settingsHandler = new PlayerSettingsHandler(player);
                settingsHandler.setLockOnPlace(!settingsHandler.getLockOnPlace());
                inventory.setItem(0, toggleOption(item, null));
            }
            case PLAYER_HEAD -> {
                state.friendSearchState = InventoryState.FriendSearchState.DEFAULT_FRIEND_SEARCH;
                closeAndOpen(player, new FriendManageInventory().fill(player));
            }
            case ENDER_CHEST -> {
                final int updatedCount = StatHandler.applyConfigurationToAll(player, null);
                final String msg = updatedCount == 0
                    ? Translator.get(TranslationKey.MESSAGES__NO_PROTECTED_BLOCKS)
                    : String.format(Translator.get(TranslationKey.MESSAGES__CONFIGURATION_APPLIED_TO_ALL), updatedCount);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
                closeAndOpen(player, null);
            }
            default -> closeAndOpen(player, null); // This also includes Material.BLACK_STAINED_GLASS_PANE
        }
        event.setCancelled(true);
    }

    @Override
    public void onClose(@NotNull InventoryCloseEvent event, @NotNull InventoryState state) {
    }

    public Inventory fill(Player player) {
        PlayerSettingsHandler settingsHandler = new PlayerSettingsHandler(player);
        boolean lockOnPlace = settingsHandler.getLockOnPlace();
        setEnchantedOptionItemStack(
            0,
            Material.BARRIER,
            TranslationKey.INVENTORIES__LOCK_ON_PLACE,
            lockOnPlace
        );
        if (!BlockProt.getDefaultConfig().isFriendFunctionalityDisabled()) {
            setItemStack(
                1,
                Material.PLAYER_HEAD,
                TranslationKey.INVENTORIES__FRIENDS__MANAGE
            );
        }
        setItemStack(
            2,
            Material.ENDER_CHEST,
            TranslationKey.INVENTORIES__UPDATE_ALL_CONFIGURATIONS
        );
        setBackButton();
        return inventory;
    }
}
