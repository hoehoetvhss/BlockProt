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

package de.sean.blockprot.bukkit.commands;

import de.sean.blockprot.bukkit.Permissions;
import de.sean.blockprot.bukkit.TranslationKey;
import de.sean.blockprot.bukkit.Translator;
import de.sean.blockprot.bukkit.nbt.StatHandler;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UnlockAllCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!canUseCommand(sender)) {
            sender.spigot().sendMessage(new TextComponent(Translator.get(TranslationKey.MESSAGES__NO_PERMISSION)));
            return true;
        }
        if (args.length < 2) {
            sender.spigot().sendMessage(new TextComponent("§cUsage: /blockprot unlockall <player>"));
            return true;
        }

        final OfflinePlayer target = findTargetPlayer(args[1]);
        if (target == null) {
            sender.spigot().sendMessage(new TextComponent(Translator.get(TranslationKey.MESSAGES__NO_PROTECTED_BLOCKS)));
            return true;
        }

        final int unlockedCount = StatHandler.unlockAllForPlayer(target.getUniqueId().toString());
        if (unlockedCount == 0) {
            sender.spigot().sendMessage(new TextComponent(Translator.get(TranslationKey.MESSAGES__NO_PROTECTED_BLOCKS)));
            return true;
        }

        final String targetName = target.getName() != null ? target.getName() : args[1];
        final String message = String.format(Translator.get(TranslationKey.MESSAGES__ADMIN_UNLOCKED_ALL), unlockedCount, targetName);
        sender.spigot().sendMessage(new TextComponent(message));
        return true;
    }

    @Nullable
    private OfflinePlayer findTargetPlayer(@NotNull final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(name)) {
                return offlinePlayer;
            }
        }
        return null;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!canUseCommand(sender) || args.length != 2) {
            return Collections.emptyList();
        }
        final String prefix = args[1].toLowerCase();
        final List<String> names = new ArrayList<>();
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            final String name = offlinePlayer.getName();
            if (name != null && name.toLowerCase().startsWith(prefix)) {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public boolean canUseCommand(@NotNull CommandSender sender) {
        return sender.isOp() || sender.hasPermission(Permissions.ADMIN.key());
    }
}
