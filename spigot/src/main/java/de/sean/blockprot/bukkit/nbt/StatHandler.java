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

package de.sean.blockprot.bukkit.nbt;

import de.sean.blockprot.bukkit.BlockProt;
import de.sean.blockprot.bukkit.integrations.PluginIntegration;
import de.sean.blockprot.bukkit.nbt.stats.BlockCountStatistic;
import de.sean.blockprot.bukkit.nbt.stats.BukkitStatistic;
import de.sean.blockprot.bukkit.nbt.stats.LocationListEntry;
import de.sean.blockprot.bukkit.nbt.stats.PlayerBlocksStatistic;
import de.sean.blockprot.bukkit.tasks.StatisticFileSaveTask;
import de.sean.blockprot.bukkit.util.BlockUtil;
import de.sean.blockprot.nbt.stats.StatisticType;
import de.tr7zw.changeme.nbtapi.NBTCompound;
import de.tr7zw.changeme.nbtapi.NBTContainer;
import de.tr7zw.changeme.nbtapi.NBTFile;
import de.tr7zw.changeme.nbtapi.NBTType;
import de.tr7zw.changeme.nbtapi.NbtApiException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * StatHandler for statistic related NBT data. Keeps track
 * of every statistic and periodically saves it to disk.
 * 
 * @since 1.0.0
 */
public final class StatHandler extends NBTHandler<NBTCompound> {
    static final String STAT_FILE_NAME = "blockprot_stats.nbt";
    static final String STAT_BACKUP_FILE_NAME = "blockprot_stats.nbt_old";

    static final String PLAYER_SUB_KEY = "player_stats";
    static final String SERVER_SUB_KEY = "server_stats";

    private static @Nullable BukkitTask fileSaveTask;

    private static @Nullable File backupFile;

    private static @Nullable File temporarySwapFile;

    private static @Nullable NBTFile nbtFile;

    /** Internal constructor to copy the NBT compound. */
    private StatHandler(@NotNull final NBTCompound compound) {
        super();
        this.container = compound;
    }

    /** Update the NBT compound of given statistic. */
    public void updateStatistic(final @NotNull BukkitStatistic<?> statistic) {
        statistic.updateContainer(this.container);
    }

    public static void enable() {
        File dataDir = BlockProt.getInstance().getDataDirectory();
        BlockProtDatabase.enable(new File(dataDir, "blockprot_locks.sqlite"));
    }

    public static void saveFile() throws IOException {
    }

    public static void disable() {
        if (fileSaveTask != null && !fileSaveTask.isCancelled())
            fileSaveTask.cancel();

        BlockProtDatabase.disable();
    }

    /**
     * Adds given block to given player's block statistic, while also incrementing the
     * global block count.
     */
    public static void addBlock(@NotNull final Player player, @NotNull final Location block) {
        String nbtData = "";
        if (block.getWorld() != null) {
            nbtData = new BlockNBTHandler(block.getBlock()).getNbtCopy().toString();
        }
        BlockProtDatabase.upsertBlock(block, player.getUniqueId().toString(), nbtData);
    }

    /**
     * Removes given block from the player's statistic, while also decrementing
     * the global block count.
     */
    @Deprecated
    public static void removeContainer(@NotNull final Player player, @NotNull final Location block) {
        BlockProtDatabase.deleteBlock(block);
    }

    /**
     * Removes given block from the player's statistic, while also decrementing
     * the global block count. Also takes care of special blocks, like doors.
     * @since 1.0.3
     */
    public static void removeContainer(@NotNull final Player player, @NotNull final Block block) {
        removeContainer(player.getUniqueId().toString(), block);
    }

    public static void removeContainer(@NotNull final String playerUuid, @NotNull final Block block) {
        /* Remove the other half of the door from the statistics as well */
        if (BlockProt.getDefaultConfig().isLockableDoor(block.getType())) {
            final Block otherDoor = BlockUtil.getOtherDoorHalf(block.getState());
            if (otherDoor != null)
                removeContainer(playerUuid, otherDoor.getLocation());
        }

        removeContainer(playerUuid, block.getLocation());
    }

    private static void removeContainer(@NotNull final String playerUuid, @NotNull final Location location) {
        BlockProtDatabase.deleteBlock(location);
    }

    public static int unlockAllForPlayer(@NotNull final String playerUuid) {
        final var locations = BlockProtDatabase.getBlocksByOwner(playerUuid);
        int unlockedCount = 0;
        for (Location loc : locations) {
            if (loc.getWorld() == null) {
                continue;
            }
            Block block = loc.getBlock();
            if (!BlockProt.getDefaultConfig().isLockable(block.getType())) {
                BlockProtDatabase.deleteBlock(loc);
                continue;
            }
            BlockNBTHandler handler = new BlockNBTHandler(block);
            if (handler.isOwner(playerUuid)) {
                handler.clear();
                handler.applyToOtherContainer();
                unlockedCount++;
            }
        }
        BlockProtDatabase.deleteBlocksByOwner(playerUuid);
        return unlockedCount;
    }

    public static int applyConfigurationToAll(@NotNull final Player player, @Nullable final NBTContainer clipboard) {
        final String playerUuid = player.getUniqueId().toString();
        final var locations = BlockProtDatabase.getBlocksByOwner(playerUuid);
        final PlayerSettingsHandler settingsHandler = clipboard == null ? new PlayerSettingsHandler(player) : null;
        int updatedCount = 0;

        for (Location loc : locations) {
            if (loc.getWorld() == null) {
                continue;
            }
            Block block = loc.getBlock();
            if (!BlockProt.getDefaultConfig().isLockable(block.getType())) {
                BlockProtDatabase.deleteBlock(loc);
                continue;
            }
            BlockNBTHandler handler = new BlockNBTHandler(block);
            if (!handler.isOwner(playerUuid)) {
                if (handler.isNotProtected()) {
                    BlockProtDatabase.deleteBlock(loc);
                }
                continue;
            }
            if (clipboard != null) {
                NBTContainer copy = new NBTContainer();
                copy.mergeCompound(clipboard);
                handler.pasteNbt(copy);
            } else if (settingsHandler != null) {
                handler.setFriends(java.util.Collections.emptyList());
                settingsHandler.getFriendsStream()
                    .filter(fh -> PluginIntegration.filterFriendByUuidForAll(UUID.fromString(fh.getName()), player, block))
                    .forEach(handler::addFriend);
                if (BlockProt.getDefaultConfig().disallowRedstoneOnPlace()) {
                    handler.getRedstoneHandler().setAll(false);
                } else {
                    handler.getRedstoneHandler().reset();
                }
                handler.applyToOtherContainer();
            }
            BlockProtDatabase.upsertBlock(loc, playerUuid, handler.getNbtCopy().toString());
            updatedCount++;
        }
        return updatedCount;
    }

    /**
     * Get a statistic. To query player specific statistics please
     * use {@link #getStatistic(BukkitStatistic, Player)}.
     */
    public static void getStatistic(@NotNull BukkitStatistic<?> statistic) {
        if (statistic.getType() == StatisticType.PLAYER) {
            throw new RuntimeException("StatHandler#getStatistic with player statistic called without player");
        }
        getStatistic(statistic, null);
    }

    public static void getStatistic(@NotNull BukkitStatistic<?> statistic, @Nullable Player player) {
        getStatisticForPlayer(statistic, player == null ? null : player.getUniqueId().toString());
    }

    public static void getStatisticForPlayer(@NotNull BukkitStatistic<?> statistic, @Nullable String playerUuid) {
        if (statistic.getType() == StatisticType.ALL || statistic.getType() == StatisticType.PLAYER) {
            // Only get the statistic if player is not null.
            if (playerUuid != null) {
                NBTContainer container = new NBTContainer();
                statistic.updateContainer(container);
                if (statistic instanceof PlayerBlocksStatistic playerBlocksStatistic) {
                    for (Location loc : BlockProtDatabase.getBlocksByOwner(playerUuid)) {
                        playerBlocksStatistic.add(loc);
                    }
                }
            }
        }
        // Let "ALL" fallthrough.
        if (statistic.getType() == StatisticType.ALL || statistic.getType() == StatisticType.GLOBAL) {
            NBTContainer container = new NBTContainer();
            container.setInteger("container_count", BlockProtDatabase.getTotalLockedBlockCount());
            statistic.updateContainer(container);
        }
    }

    private static @NotNull Stream<StatHandler> getPlayerStats() {
        if (nbtFile == null) throw new RuntimeException("nbtFile was null.");
        if (!nbtFile.hasKey(PLAYER_SUB_KEY)) return Stream.empty();

        final NBTCompound list = nbtFile.getOrCreateCompound(PLAYER_SUB_KEY);
        return list
            .getKeys()
            .stream()
            .map((comp) -> new StatHandler(list.getCompound(comp)));
    }

    private static @NotNull Optional<StatHandler> getStatsForPlayer(@NotNull final String id) {
        Optional<StatHandler> ret = getPlayerStats()
            .filter((p) -> p.getName().equals(id))
            .findFirst();

        // Add the stats NBT to be sure that we always return a present optional value.
        if (ret.isEmpty()) {
            return Optional.of(StatHandler.addStatsForPlayer(id));
        }
        return ret;
    }

    private static @NotNull StatHandler addStatsForPlayer(@NotNull final String id) {
        if (nbtFile == null) throw new RuntimeException("nbtFile was null.");
        NBTCompound compound = nbtFile.getOrCreateCompound(PLAYER_SUB_KEY);
        compound.addCompound(id).setString("id", id);
        return new StatHandler(compound.getCompound(id));
    }

    private static @NotNull StatHandler getServerStats() {
        if (nbtFile == null) throw new RuntimeException("nbtFile was null.");
        if (nbtFile.hasKey(SERVER_SUB_KEY) &&
            nbtFile.getType(SERVER_SUB_KEY) == NBTType.NBTTagCompound) {
            return new StatHandler(nbtFile.getCompound(SERVER_SUB_KEY));
        } else {
            return new StatHandler(nbtFile.addCompound(SERVER_SUB_KEY));
        }
    }
}
