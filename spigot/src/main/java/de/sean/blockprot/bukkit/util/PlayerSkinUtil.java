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

package de.sean.blockprot.bukkit.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.sean.blockprot.bukkit.nbt.BlockProtDatabase;
import de.sean.blockprot.bukkit.nbt.BlockProtDatabase.SkinData;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSkinUtil {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final Map<UUID, Long> FAILED_GEYSER_LOOKUPS = new ConcurrentHashMap<>();
    private static final long FAILED_LOOKUP_TTL_MS = 5 * 60 * 1000L;

    private static final boolean PAPER_PROFILE_SUPPORTED;
    private static Method bukkitCreateProfileMethod;
    private static Class<?> paperProfileClass;
    private static Constructor<?> profilePropertyConstructor;
    private static Method setPropertyMethod;
    private static Method getPropertiesMethod;
    private static Method propGetNameMethod;
    private static Method propGetValueMethod;
    private static Method propGetSignatureMethod;
    private static Method skullSetPlayerProfileMethod;

    static {
        boolean supported = false;
        try {
            paperProfileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            Class<?> profilePropertyClass = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            bukkitCreateProfileMethod = Bukkit.class.getMethod("createProfile", UUID.class, String.class);
            profilePropertyConstructor = profilePropertyClass.getConstructor(String.class, String.class, String.class);
            setPropertyMethod = paperProfileClass.getMethod("setProperty", profilePropertyClass);
            getPropertiesMethod = paperProfileClass.getMethod("getProperties");
            propGetNameMethod = profilePropertyClass.getMethod("getName");
            propGetValueMethod = profilePropertyClass.getMethod("getValue");
            propGetSignatureMethod = profilePropertyClass.getMethod("getSignature");
            skullSetPlayerProfileMethod = SkullMeta.class.getMethod("setPlayerProfile", paperProfileClass);
            supported = true;
        } catch (Throwable ignored) {
        }
        PAPER_PROFILE_SUPPORTED = supported;
    }

    private PlayerSkinUtil() {
    }

    public static boolean isBedrockUuid(@NotNull final UUID uuid) {
        return uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() != 0L;
    }

    public static boolean extractAndSaveSkin(@NotNull final UUID uuid, @Nullable final PlayerProfile profile) {
        if (profile == null) {
            return false;
        }

        final PlayerTextures textures = profile.getTextures();
        final URL skinUrl = textures.getSkin();
        final String skinUrlStr = skinUrl != null ? skinUrl.toString() : null;
        final String skinModel = skinUrl != null ? textures.getSkinModel().name() : null;

        String textureValue = null;
        String textureSignature = null;
        if (PAPER_PROFILE_SUPPORTED && paperProfileClass.isInstance(profile)) {
            try {
                Collection<?> props = (Collection<?>) getPropertiesMethod.invoke(profile);
                for (Object prop : props) {
                    if ("textures".equals(propGetNameMethod.invoke(prop))) {
                        textureValue = (String) propGetValueMethod.invoke(prop);
                        textureSignature = (String) propGetSignatureMethod.invoke(prop);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if ((skinUrlStr == null || skinUrlStr.isEmpty()) && (textureValue == null || textureValue.isEmpty())) {
            return false;
        }

        BlockProtDatabase.upsertPlayerSkin(uuid, skinUrlStr, skinModel, textureValue, textureSignature);
        return true;
    }

    public static boolean applySkinData(
        @NotNull final SkullMeta meta,
        @NotNull final UUID uuid,
        @Nullable final SkinData skinData
    ) {
        if (skinData == null) {
            return false;
        }

        if (PAPER_PROFILE_SUPPORTED
            && skinData.textureValue() != null && !skinData.textureValue().isEmpty()
            && skinData.textureSignature() != null && !skinData.textureSignature().isEmpty()) {
            try {
                Object paperProfile = bukkitCreateProfileMethod.invoke(null, uuid, null);
                Object prop = profilePropertyConstructor.newInstance("textures", skinData.textureValue(), skinData.textureSignature());
                setPropertyMethod.invoke(paperProfile, prop);
                skullSetPlayerProfileMethod.invoke(meta, paperProfile);
                return true;
            } catch (Throwable ignored) {
            }
        }

        if (skinData.skinUrl() != null && !skinData.skinUrl().isEmpty()) {
            try {
                PlayerProfile profile = Bukkit.getServer().createPlayerProfile(uuid, null);
                PlayerTextures textures = profile.getTextures();
                PlayerTextures.SkinModel model = "SLIM".equalsIgnoreCase(skinData.skinModel())
                    ? PlayerTextures.SkinModel.SLIM
                    : PlayerTextures.SkinModel.CLASSIC;
                textures.setSkin(URI.create(skinData.skinUrl()).toURL(), model);
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
                return true;
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    public static CompletableFuture<@Nullable SkinData> fetchGeyserSkinAsync(@NotNull final UUID uuid) {
        if (!isBedrockUuid(uuid)) {
            return CompletableFuture.completedFuture(null);
        }

        Long failedAt = FAILED_GEYSER_LOOKUPS.get(uuid);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_LOOKUP_TTL_MS) {
            return CompletableFuture.completedFuture(null);
        }

        final String xuid = Long.toUnsignedString(uuid.getLeastSignificantBits());
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.geysermc.org/v2/skin/" + xuid))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body() == null || response.body().isEmpty()) {
                    FAILED_GEYSER_LOOKUPS.put(uuid, System.currentTimeMillis());
                    return null;
                }

                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String value = json.has("value") && !json.get("value").isJsonNull() ? json.get("value").getAsString() : null;
                String signature = json.has("signature") && !json.get("signature").isJsonNull() ? json.get("signature").getAsString() : null;
                String textureId = json.has("texture_id") && !json.get("texture_id").isJsonNull() ? json.get("texture_id").getAsString() : null;
                boolean isSteve = !json.has("is_steve") || json.get("is_steve").isJsonNull() || json.get("is_steve").getAsBoolean();

                String skinUrl = textureId != null && !textureId.isEmpty()
                    ? "https://textures.minecraft.net/texture/" + textureId
                    : "";
                String skinModel = isSteve ? "CLASSIC" : "SLIM";

                if ((value == null || value.isEmpty()) && skinUrl.isEmpty()) {
                    FAILED_GEYSER_LOOKUPS.put(uuid, System.currentTimeMillis());
                    return null;
                }

                FAILED_GEYSER_LOOKUPS.remove(uuid);
                BlockProtDatabase.upsertPlayerSkin(uuid, skinUrl, skinModel, value, signature);
                return new SkinData(skinUrl, skinModel, value, signature);
            } catch (Exception e) {
                FAILED_GEYSER_LOOKUPS.put(uuid, System.currentTimeMillis());
                return null;
            }
        });
    }
}
