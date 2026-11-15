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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BlockProtDatabase {
    private static @Nullable Connection connection;

    private BlockProtDatabase() {}

    public static synchronized void enable(@NotNull final File dbFile) {
        if (connection != null) {
            return;
        }
        try {
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            applyWalPragmas(connection);
            createSchema(connection);
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
        }
    }

    public static void enableWalOnDatabaseFile(@NotNull final File dbFile) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            applyWalPragmas(conn);
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to enable WAL mode on " + dbFile.getName() + ": " + e.getMessage());
        }
    }

    private static void applyWalPragmas(@NotNull final Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
            stmt.execute("PRAGMA temp_store=MEMORY;");
            stmt.execute("PRAGMA foreign_keys=ON;");
        }
    }

    private static void createSchema(@NotNull final Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS locked_blocks ("
                    + "world TEXT NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "owner TEXT NOT NULL,"
                    + "nbt_data TEXT NOT NULL DEFAULT '',"
                    + "updated_at INTEGER NOT NULL,"
                    + "PRIMARY KEY (world, x, y, z)"
                    + ");"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_locked_blocks_owner ON locked_blocks(owner);");
        }
    }

    public static synchronized void disable() {
        if (connection == null) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
        } catch (SQLException ignored) {
        }
        try {
            connection.close();
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to close SQLite database: " + e.getMessage());
        } finally {
            connection = null;
        }
    }

    public static synchronized void upsertBlock(@NotNull final Location location, @NotNull final String owner, @NotNull final String nbtData) {
        if (connection == null || location.getWorld() == null) {
            return;
        }
        final String sql = "INSERT INTO locked_blocks (world, x, y, z, owner, nbt_data, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(world, x, y, z) DO UPDATE SET "
            + "owner = excluded.owner, nbt_data = excluded.nbt_data, updated_at = excluded.updated_at;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld().getName());
            ps.setInt(2, location.getBlockX());
            ps.setInt(3, location.getBlockY());
            ps.setInt(4, location.getBlockZ());
            ps.setString(5, owner);
            ps.setString(6, nbtData);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to upsert locked block in SQLite: " + e.getMessage());
        }
    }

    public static synchronized void deleteBlock(@NotNull final Location location) {
        if (connection == null || location.getWorld() == null) {
            return;
        }
        final String sql = "DELETE FROM locked_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld().getName());
            ps.setInt(2, location.getBlockX());
            ps.setInt(3, location.getBlockY());
            ps.setInt(4, location.getBlockZ());
            ps.executeUpdate();
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to delete locked block from SQLite: " + e.getMessage());
        }
    }

    public static synchronized void deleteBlocksByOwner(@NotNull final String owner) {
        if (connection == null) {
            return;
        }
        final String sql = "DELETE FROM locked_blocks WHERE owner = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.executeUpdate();
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to delete owner blocks from SQLite: " + e.getMessage());
        }
    }

    public static synchronized @NotNull List<Location> getBlocksByOwner(@NotNull final String owner) {
        if (connection == null) {
            return Collections.emptyList();
        }
        final List<Location> locations = new ArrayList<>();
        final String sql = "SELECT world, x, y, z FROM locked_blocks WHERE owner = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world != null) {
                        locations.add(new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                    }
                }
            }
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to query blocks by owner from SQLite: " + e.getMessage());
        }
        return locations;
    }

    public static synchronized int getTotalLockedBlockCount() {
        if (connection == null) {
            return 0;
        }
        final String sql = "SELECT COUNT(*) FROM locked_blocks;";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            BlockProt.getInstance().getLogger().warning("Failed to query total locked block count from SQLite: " + e.getMessage());
        }
        return 0;
    }
}
