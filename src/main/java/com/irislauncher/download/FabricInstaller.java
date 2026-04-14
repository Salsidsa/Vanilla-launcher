package com.irislauncher.download;

import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Installs Fabric mod loader for a given Minecraft version.
 * Iris requires Fabric to run.
 */
public class FabricInstaller {

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private Consumer<String> onStatus = s -> {};
    private final MinecraftDownloader downloader;

    public FabricInstaller(MinecraftDownloader downloader) {
        this.downloader = downloader;
    }

    public void setStatusListener(Consumer<String> l) { onStatus = l; }

    /**
     * Installs Fabric for {@code mcVersion} into {@code gameDir}.
     * @return the Fabric profile JSON (merged version JSON with Fabric loader on top)
     */
    public JsonObject install(String mcVersion, Path gameDir) throws Exception {
        onStatus.accept("Finding latest Fabric loader for " + mcVersion + "...");

        // Get loader versions
        JsonArray loaders = fetchJsonArray(FABRIC_META + "/versions/loader/" + mcVersion);
        if (loaders.isEmpty()) {
            throw new RuntimeException("Fabric does not support Minecraft " + mcVersion + " yet.");
        }

        String loaderVersion = loaders.get(0).getAsJsonObject()
                .getAsJsonObject("loader")
                .get("version").getAsString();
        onStatus.accept("Installing Fabric " + loaderVersion + "...");

        // Fetch Fabric profile JSON (replaces mainClass and adds Fabric libraries)
        String profileUrl = FABRIC_META + "/versions/loader/" + mcVersion
                + "/" + loaderVersion + "/profile/json";
        JsonObject fabricProfile = fetchJsonObject(profileUrl);

        // Save Fabric profile JSON in its own versioned folder (e.g. versions/26.1.1-fabric-loader-0.16.10/)
        String fabricId = fabricProfile.get("id").getAsString();
        Path fabricDir = gameDir.resolve("versions").resolve(fabricId);
        // Refresh: delete and recreate so stale files don't accumulate
        if (Files.isDirectory(fabricDir)) {
            try (var s = Files.walk(fabricDir)) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .map(java.nio.file.Path::toFile)
                 .forEach(java.io.File::delete);
            } catch (Exception ignored) {}
        }
        Files.createDirectories(fabricDir);
        Files.writeString(fabricDir.resolve(fabricId + ".json"), new Gson().toJson(fabricProfile));

        // Clean up old-style embedded fabric JSON if present
        Path legacyJson = gameDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + "-fabric.json");
        try { Files.deleteIfExists(legacyJson); } catch (Exception ignored) {}

        // Download Fabric libraries
        onStatus.accept("Downloading Fabric libraries...");
        if (fabricProfile.has("libraries")) {
            downloader.downloadLibraries(fabricProfile.getAsJsonArray("libraries"), gameDir);
        }

        onStatus.accept("Fabric installed: " + fabricId);
        return fabricProfile;
    }

    private JsonObject fetchJsonObject(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " for " + url);
            return JsonParser.parseString(resp.body().string()).getAsJsonObject();
        }
    }

    private JsonArray fetchJsonArray(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " for " + url);
            return JsonParser.parseString(resp.body().string()).getAsJsonArray();
        }
    }
}
