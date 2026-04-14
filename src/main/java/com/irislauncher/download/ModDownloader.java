package com.irislauncher.download;

import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Downloads mods and shader packs from Modrinth.
 */
public class ModDownloader {

    private static final String API = "https://api.modrinth.com/v2";
    private static final String UA  = "ShpakLauncher/1.0";

    // Modrinth project IDs
    private static final String IRIS         = "YL57xq9U";
    private static final String SODIUM       = "AANobbMI";
    private static final String FABRIC_API   = "P7dR8mSH";
    private static final String VOICE_CHAT    = "9eGKb6K1";
    private static final String VOICE_MSGS    = "WWLeFuHa";
    private static final String COMP_UNBOUND = "R6NEzAwj";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private Consumer<String> onStatus = s -> {};
    public void setStatusListener(Consumer<String> l) { onStatus = l; }

    public void installAll(Path gameDir, String mcVersion) throws Exception {
        Path modsDir    = gameDir.resolve("mods");
        Path shadersDir = gameDir.resolve("shaderpacks");
        Files.createDirectories(modsDir);
        Files.createDirectories(shadersDir);

        downloadMod(IRIS,       "Iris",             mcVersion, modsDir);
        downloadMod(SODIUM,     "Sodium",           mcVersion, modsDir);
        downloadMod(FABRIC_API, "Fabric API",       mcVersion, modsDir);
        downloadMod(VOICE_CHAT, "Simple Voice Chat", mcVersion, modsDir);
        downloadMod(VOICE_MSGS, "Voice Messages",    mcVersion, modsDir);
        downloadShader(COMP_UNBOUND, "Complementary Unbound", mcVersion, shadersDir);
    }

    // ─── Mod (requires fabric loader) ────────────────────────────────────────

    private void downloadMod(String projectId, String name,
                              String mcVersion, Path modsDir) throws Exception {
        onStatus.accept("Looking for " + name + " for Minecraft " + mcVersion + "...");

        JsonArray versions = queryVersions(projectId, mcVersion, "fabric");

        // Fallback: any version for this loader if exact MC version not found
        if (versions.isEmpty()) {
            onStatus.accept("Exact version of " + name + " not found, using latest compatible...");
            versions = queryVersions(projectId, null, "fabric");
        }

        if (versions.isEmpty())
            throw new RuntimeException(name + " not found on Modrinth for Fabric.");

        JsonObject latest = versions.get(0).getAsJsonObject();
        String ver = latest.get("version_number").getAsString();
        onStatus.accept("Downloading " + name + " " + ver + "...");

        JsonObject file = primaryFile(latest);
        downloadFile(file.get("url").getAsString(), modsDir.resolve(file.get("filename").getAsString()));
        onStatus.accept(name + " " + ver + " installed.");
    }

    // ─── Shader pack ─────────────────────────────────────────────────────────

    private void downloadShader(String projectId, String name,
                                 String mcVersion, Path shadersDir) throws Exception {
        onStatus.accept("Looking for shader " + name + "...");

        // Shader packs are loader-agnostic
        JsonArray versions = queryVersions(projectId, null, null);
        if (versions.isEmpty())
            throw new RuntimeException(name + " not found on Modrinth.");

        // Prefer a version that lists our MC version; fall back to latest
        JsonObject chosen = null;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            for (JsonElement gv : v.getAsJsonArray("game_versions")) {
                if (mcVersion.equals(gv.getAsString())) { chosen = v; break; }
            }
            if (chosen != null) break;
        }
        if (chosen == null) chosen = versions.get(0).getAsJsonObject();

        String ver = chosen.get("version_number").getAsString();
        onStatus.accept("Downloading " + name + " " + ver + "...");

        JsonObject file = primaryFile(chosen);
        downloadFile(file.get("url").getAsString(), shadersDir.resolve(file.get("filename").getAsString()));
        onStatus.accept(name + " " + ver + " installed.");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private JsonArray queryVersions(String projectId, String mcVersion,
                                     String loader) throws IOException {
        StringBuilder url = new StringBuilder(API + "/project/" + projectId + "/version?");
        if (mcVersion != null)
            url.append("game_versions=%5B%22").append(mcVersion).append("%22%5D&");
        if (loader != null)
            url.append("loaders=%5B%22").append(loader).append("%22%5D");

        Request req = new Request.Builder().url(url.toString())
                .addHeader("User-Agent", UA).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return new JsonArray();
            JsonElement el = JsonParser.parseString(resp.body().string());
            return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
        }
    }

    private JsonObject primaryFile(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        for (JsonElement f : files) {
            JsonObject file = f.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
        }
        return files.get(0).getAsJsonObject();
    }

    private void downloadFile(String url, Path dest) throws IOException {
        if (Files.exists(dest)) {
            onStatus.accept("Already exists: " + dest.getFileName());
            return;
        }
        Request req = new Request.Builder().url(url)
                .addHeader("User-Agent", UA).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " for " + url);
            try (InputStream in  = resp.body().byteStream();
                 OutputStream out = Files.newOutputStream(dest)) {
                in.transferTo(out);
            }
        }
    }

}
