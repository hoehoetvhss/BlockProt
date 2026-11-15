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

package de.sean.blockprot.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class ConfigMigrator {
    public static final int CURRENT_CONFIG_VERSION = 2;

    private static final List<String> LOCKABLE_LIST_KEYS = List.of(
        "lockable_tile_entities",
        "lockable_shulker_boxes",
        "lockable_blocks",
        "lockable_doors"
    );

    private ConfigMigrator() {
    }

    public static void migrateMainConfig(@NotNull final JavaPlugin plugin) {
        final File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return;
        }

        final YamlConfiguration defaultConfig;
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return;
            }
            defaultConfig = YamlConfiguration.loadConfiguration(
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
            );
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read default config.yml for migration", e);
            return;
        }

        final YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);
        final int oldVersion = userConfig.getInt("config_version", 1);
        boolean changed = false;

        for (String key : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(key)) {
                continue;
            }
            if (!userConfig.contains(key, true)) {
                userConfig.set(key, defaultConfig.get(key));
                copyComments(defaultConfig, userConfig, key);
                changed = true;
            }
        }

        if (oldVersion < CURRENT_CONFIG_VERSION) {
            for (String listKey : LOCKABLE_LIST_KEYS) {
                if (mergeMissingListEntries(defaultConfig, userConfig, listKey)) {
                    changed = true;
                }
            }
            userConfig.set("config_version", CURRENT_CONFIG_VERSION);
            copyComments(defaultConfig, userConfig, "config_version");
            changed = true;
        }

        if (!changed) {
            return;
        }

        try {
            final File backupFile = new File(plugin.getDataFolder(), "config.yml.bak");
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            userConfig.save(configFile);
            plugin.getLogger().info(
                "Migrated config.yml (version " + oldVersion + " -> " + CURRENT_CONFIG_VERSION + ", backup saved to config.yml.bak)."
            );
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save migrated config.yml", e);
        }
    }

    public static void migrateTranslationFile(
        @NotNull final JavaPlugin plugin,
        @NotNull final File targetFile,
        @NotNull final String resourcePath,
        @NotNull final YamlConfiguration fallbackDefaults
    ) {
        if (!targetFile.exists()) {
            return;
        }

        final YamlConfiguration userLang = YamlConfiguration.loadConfiguration(targetFile);
        YamlConfiguration resourceLang = null;
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream != null) {
                resourceLang = YamlConfiguration.loadConfiguration(
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                );
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read default translation resource: " + resourcePath, e);
        }

        boolean changed = false;
        if (resourceLang != null && mergeMissingKeys(resourceLang, userLang)) {
            changed = true;
        }
        if (mergeMissingKeys(fallbackDefaults, userLang)) {
            changed = true;
        }

        if (changed) {
            try {
                userLang.save(targetFile);
                plugin.getLogger().info("Migrated missing translation keys into " + targetFile.getName() + ".");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save migrated translation file: " + targetFile.getName(), e);
            }
        }
    }

    private static boolean mergeMissingKeys(
        @NotNull final YamlConfiguration source,
        @NotNull final YamlConfiguration target
    ) {
        boolean changed = false;
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
                continue;
            }
            if (!target.contains(key, true)) {
                target.set(key, source.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean mergeMissingListEntries(
        @NotNull final YamlConfiguration defaultConfig,
        @NotNull final YamlConfiguration userConfig,
        @NotNull final String listKey
    ) {
        final List<String> defaultList = defaultConfig.getStringList(listKey);
        if (defaultList.isEmpty()) {
            return false;
        }
        final List<String> currentList = userConfig.getStringList(listKey);
        final Set<String> merged = new LinkedHashSet<>(currentList);
        final int beforeSize = merged.size();
        merged.addAll(defaultList);
        if (merged.size() == beforeSize) {
            return false;
        }
        userConfig.set(listKey, new ArrayList<>(merged));
        return true;
    }

    private static void copyComments(
        @NotNull final YamlConfiguration source,
        @NotNull final YamlConfiguration target,
        @NotNull final String key
    ) {
        final List<String> comments = source.getComments(key);
        if (!comments.isEmpty()) {
            target.setComments(key, comments);
        }
        final List<String> inlineComments = source.getInlineComments(key);
        if (!inlineComments.isEmpty()) {
            target.setInlineComments(key, inlineComments);
        }
    }
}
