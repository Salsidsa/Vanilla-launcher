package com.irislauncher.download;

import com.google.gson.*;
import okhttp3.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads vanilla Minecraft client, libraries, and assets.
 */
public class MinecraftDownloader {

    private static final String VERSION_MANIFEST =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private Consumer<String> onStatus   = s -> {};
    /** progress: (current, total) */
    private BiConsumer<Long, Long> onProgress = (c, t) -> {};

    public void setStatusListener(Consumer<String> l)      { onStatus = l; }
    public void setProgressListener(BiConsumer<Long, Long> l) { onProgress = l; }

    /**
     * Downloads the full game for {@code version} into {@code gameDir}.
     * @return parsed version JSON (used by FabricInstaller and GameLauncher)
     */
    public JsonObject download(String version, Path gameDir) throws Exception {
        onStatus.accept("Fetching Minecraft version manifest...");
        JsonObject manifest = fetchJson(VERSION_MANIFEST);

        String versionUrl = null;
        for (JsonElement el : manifest.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            if (version.equals(v.get("id").getAsString())) {
                versionUrl = v.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new RuntimeException("Minecraft version " + version + " not found in manifest.");
        }

        onStatus.accept("Loading version metadata for " + version + "...");
        JsonObject versionJson = fetchJson(versionUrl);

        // Save version JSON
        Path versionsDir = gameDir.resolve("versions").resolve(version);
        Files.createDirectories(versionsDir);
        Path versionJsonFile = versionsDir.resolve(version + ".json");
        Files.writeString(versionJsonFile, new Gson().toJson(versionJson));

        // Download client jar
        onStatus.accept("Downloading Minecraft client...");
        JsonObject clientDownload = versionJson
                .getAsJsonObject("downloads")
                .getAsJsonObject("client");
        Path clientJar = versionsDir.resolve(version + ".jar");
        downloadFile(clientDownload.get("url").getAsString(), clientJar,
                clientDownload.get("sha1").getAsString());

        // Download libraries
        onStatus.accept("Downloading libraries...");
        downloadLibraries(versionJson.getAsJsonArray("libraries"), gameDir);

        // Download assets
        onStatus.accept("Downloading assets...");
        downloadAssets(versionJson, gameDir);

        return versionJson;
    }

    void downloadLibraries(JsonArray libraries, Path gameDir) throws Exception {
        Path libDir = gameDir.resolve("libraries");
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement el : libraries) {
            list.add(el.getAsJsonObject());
        }

        for (int i = 0; i < list.size(); i++) {
            JsonObject lib = list.get(i);
            onProgress.accept((long) i, (long) list.size());

            if (!isLibraryAllowed(lib)) continue;

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) {
                // Fabric / Maven-style library: { "name": "group:artifact:version", "url": "https://..." }
                if (lib.has("name") && lib.has("url")) {
                    String mavenPath = mavenCoordToPath(lib.get("name").getAsString());
                    if (mavenPath != null) {
                        String baseUrl = lib.get("url").getAsString();
                        if (!baseUrl.endsWith("/")) baseUrl += "/";
                        Path dest = libDir.resolve(mavenPath.replace("/", File.separator));
                        downloadFile(baseUrl + mavenPath, dest, null);
                    }
                }
                continue;
            }
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            if (artifact == null) continue;

            String url  = artifact.get("url").getAsString();
            String path = artifact.get("path").getAsString();
            String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;

            Path dest = libDir.resolve(path.replace("/", File.separator));
            downloadFile(url, dest, sha1);

            // Extract natives if needed
            if (downloads.has("classifiers")) {
                String nativeKey = getNativeKey(lib);
                if (nativeKey != null) {
                    JsonObject native_ = downloads.getAsJsonObject("classifiers")
                            .getAsJsonObject(nativeKey);
                    if (native_ != null) {
                        Path nativeDest = libDir.resolve(
                                native_.get("path").getAsString().replace("/", File.separator));
                        downloadFile(native_.get("url").getAsString(), nativeDest, null);
                        extractNatives(nativeDest, gameDir.resolve("natives"));
                    }
                }
            }
        }
        onProgress.accept((long) list.size(), (long) list.size());
    }

    private void downloadAssets(JsonObject versionJson, Path gameDir) throws Exception {
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        String assetId  = assetIndex.get("id").getAsString();
        String indexUrl = assetIndex.get("url").getAsString();

        Path assetsDir  = gameDir.resolve("assets");
        Path indexesDir = assetsDir.resolve("indexes");
        Files.createDirectories(indexesDir);

        Path indexFile = indexesDir.resolve(assetId + ".json");
        downloadFile(indexUrl, indexFile, null);

        JsonObject index = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");
        boolean isVirtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        boolean mapToResources = index.has("map_to_resources") && index.get("map_to_resources").getAsBoolean();

        List<String> keys = new ArrayList<>(objects.keySet());
        Path objectsDir = assetsDir.resolve("objects");

        for (int i = 0; i < keys.size(); i++) {
            if (i % 50 == 0) onProgress.accept((long) i, (long) keys.size());
            String key  = keys.get(i);
            JsonObject obj  = objects.getAsJsonObject(key);
            String hash = obj.get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            Path dest = objectsDir.resolve(prefix).resolve(hash);

            if (!Files.exists(dest)) {
                String url = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
                downloadFile(url, dest, null);
            }

            if (isVirtual || mapToResources) {
                Path vDest = (mapToResources
                        ? gameDir.resolve("resources").resolve(key)
                        : assetsDir.resolve("virtual").resolve(assetId).resolve(key));
                if (!Files.exists(vDest)) {
                    Files.createDirectories(vDest.getParent());
                    Files.copy(dest, vDest);
                }
            }
        }
        onProgress.accept((long) keys.size(), (long) keys.size());
    }

    // ─── File download ────────────────────────────────────────────────────────

    void downloadFile(String url, Path dest, String expectedSha1) throws IOException {
        if (Files.exists(dest) && isValidFile(dest, expectedSha1)) return;
        Files.createDirectories(dest.getParent());

        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " for " + url);
            try (InputStream in  = resp.body().byteStream();
                 OutputStream out = Files.newOutputStream(dest)) {
                in.transferTo(out);
            }
        }
    }

    private boolean isValidFile(Path path, String expectedSha1) {
        if (expectedSha1 == null) return true;
        try {
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equals(expectedSha1);
        } catch (Exception e) {
            return false;
        }
    }

    private void extractNatives(Path jar, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")) {
                    Path dest = nativesDir.resolve(Paths.get(name).getFileName());
                    if (!Files.exists(dest)) {
                        try (OutputStream out = Files.newOutputStream(dest)) {
                            zis.transferTo(out);
                        }
                    }
                }
            }
        }
    }

    private boolean isLibraryAllowed(JsonObject lib) {
        if (!lib.has("rules")) return true;
        for (JsonElement ruleEl : lib.getAsJsonArray("rules")) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (rule.has("os")) {
                String os = rule.getAsJsonObject("os").get("name").getAsString();
                String current = getCurrentOs();
                boolean matches = os.equals(current);
                if ("allow".equals(action) && !matches) return false;
                if ("disallow".equals(action) && matches) return false;
            }
        }
        return true;
    }

    private String getNativeKey(JsonObject lib) {
        if (!lib.has("natives")) return null;
        JsonObject natives = lib.getAsJsonObject("natives");
        String os = getCurrentOs();
        if (natives.has(os)) return natives.get(os).getAsString()
                .replace("${arch}", System.getProperty("os.arch").contains("64") ? "64" : "32");
        return null;
    }

    private String getCurrentOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    /** "net.fabricmc:fabric-loader:0.19.1" → "net/fabricmc/fabric-loader/0.19.1/fabric-loader-0.19.1.jar" */
    private String mavenCoordToPath(String coord) {
        String[] parts = coord.split(":");
        if (parts.length < 3) return null;
        String group    = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version  = parts[2];
        // optional classifier (parts[3])
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private JsonObject fetchJson(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return JsonParser.parseString(resp.body().string()).getAsJsonObject();
        }
    }
}
