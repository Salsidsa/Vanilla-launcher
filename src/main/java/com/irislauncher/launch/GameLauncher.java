package com.irislauncher.launch;

import com.google.gson.*;
import com.irislauncher.auth.AuthResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GameLauncher {

    private Consumer<String> onOutput = s -> {};
    private Consumer<String> onStatus = s -> {};

    public void setOutputListener(Consumer<String> l) { onOutput = l; }
    public void setStatusListener(Consumer<String> l)  { onStatus = l; }

    public Process launch(String mcVersion,
                          JsonObject fabricProfile,
                          JsonObject vanillaJson,
                          Path gameDir,
                          AuthResult auth) throws IOException {

        JsonObject activeProfile = fabricProfile != null ? fabricProfile : vanillaJson;
        String versionId   = str(activeProfile, "id", mcVersion);
        String mainClass   = str(activeProfile, "mainClass", "net.minecraft.client.main.Main");
        String classpath   = buildClasspath(mcVersion, fabricProfile, vanillaJson, gameDir);
        String nativesPath = gameDir.resolve("natives").toString();

        String assetIndexId = mcVersion;
        if (vanillaJson.has("assetIndex")) {
            assetIndexId = str(vanillaJson.getAsJsonObject("assetIndex"), "id", mcVersion);
        }

        onStatus.accept("Preparing Minecraft " + versionId + "...");

        String javaPath    = findJava();
        int    javaVersion = getJavaMajorVersion(javaPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);

        // Vanilla supplies -Djava.library.path, -cp ${classpath} etc.
        addJvmArgs(cmd, vanillaJson, nativesPath, classpath, versionId, mcVersion, javaVersion);

        // Fabric adds -DFabricMcEmu
        if (fabricProfile != null) {
            addJvmArgs(cmd, fabricProfile, nativesPath, classpath, versionId, mcVersion, javaVersion);
        }

        cmd.add("-XX:+UseG1GC");
        if (javaVersion < 26) cmd.add("-XX:+ParallelRefProcEnabled"); // deprecated in 26
        cmd.add("-Xmx2G");
        cmd.add("-Xms512M");

        cmd.add(mainClass);

        // Game args always from vanilla (Fabric game args are empty)
        addGameArgs(cmd, vanillaJson, auth, gameDir, versionId, mcVersion, assetIndexId);

        onStatus.accept("Launching Minecraft...");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.environment().put("APPDATA", gameDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        Thread t = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) onOutput.accept(line);
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();

        return process;
    }

    // ─── JVM / Game args ─────────────────────────────────────────────────────

    private void addJvmArgs(List<String> cmd, JsonObject profile,
                             String nativesPath, String classpath,
                             String versionId, String mcVersion, int javaVersion) {
        if (!profile.has("arguments")) {
            // Legacy launcher format
            cmd.add("-Djava.library.path=" + nativesPath);
            cmd.add("-cp");
            cmd.add(classpath);
            return;
        }
        JsonObject arguments = profile.getAsJsonObject("arguments");
        if (!arguments.has("jvm")) return;

        for (JsonElement el : arguments.getAsJsonArray("jvm")) {
            if (el.isJsonPrimitive()) {
                String arg = resolveJvmArg(el.getAsString(), nativesPath, classpath, versionId, mcVersion);
                if (isArgCompatible(arg, javaVersion)) cmd.add(arg);
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (!isAllowed(obj)) continue;
                JsonElement val = obj.get("value");
                if (val == null) continue;
                if (val.isJsonPrimitive()) {
                    String arg = resolveJvmArg(val.getAsString(), nativesPath, classpath, versionId, mcVersion);
                    if (isArgCompatible(arg, javaVersion)) cmd.add(arg);
                } else if (val.isJsonArray()) {
                    for (JsonElement v : val.getAsJsonArray()) {
                        if (v.isJsonPrimitive()) {
                            String arg = resolveJvmArg(v.getAsString(), nativesPath, classpath, versionId, mcVersion);
                            if (isArgCompatible(arg, javaVersion)) cmd.add(arg);
                        }
                    }
                }
            }
        }
    }

    private void addGameArgs(List<String> cmd, JsonObject profile, AuthResult auth,
                              Path gameDir, String versionId, String mcVersion,
                              String assetIndexId) {
        if (profile.has("arguments") && profile.getAsJsonObject("arguments").has("game")) {
            for (JsonElement el : profile.getAsJsonObject("arguments").getAsJsonArray("game")) {
                if (el.isJsonPrimitive())
                    cmd.add(substituteGameArg(el.getAsString(), auth, gameDir,
                            versionId, mcVersion, assetIndexId));
            }
        } else if (profile.has("minecraftArguments")) {
            for (String part : profile.get("minecraftArguments").getAsString().split(" "))
                cmd.add(substituteGameArg(part, auth, gameDir, versionId, mcVersion, assetIndexId));
        }
    }

    private String resolveJvmArg(String arg, String nativesPath, String classpath,
                                   String versionId, String mcVersion) {
        arg = arg.replace("${natives_directory}",   nativesPath);
        arg = arg.replace("${launcher_name}",        "VanillaLauncher");
        arg = arg.replace("${launcher_version}",     "1.0");
        arg = arg.replace("${classpath}",             classpath);
        arg = arg.replace("${version_name}",          versionId);
        arg = arg.replace("${version_type}",          "release");
        arg = arg.replace("${library_directory}",     "");
        arg = arg.replace("${classpath_separator}",   File.pathSeparator);
        return arg;
    }

    private String substituteGameArg(String arg, AuthResult auth, Path gameDir,
                                      String versionId, String mcVersion, String assetIndexId) {
        arg = arg.replace("${auth_player_name}",  auth.username);
        arg = arg.replace("${version_name}",       versionId);
        arg = arg.replace("${game_directory}",     gameDir.toAbsolutePath().toString());
        arg = arg.replace("${assets_root}",        gameDir.resolve("assets").toAbsolutePath().toString());
        arg = arg.replace("${assets_index_name}",  assetIndexId);
        arg = arg.replace("${auth_uuid}",          auth.uuid);
        arg = arg.replace("${auth_access_token}",  auth.accessToken);
        arg = arg.replace("${user_type}",          "msa");
        arg = arg.replace("${version_type}",       "release");
        arg = arg.replace("${clientid}",           "0");
        arg = arg.replace("${auth_xuid}",          "0");
        arg = arg.replace("${resolution_width}",   "854");
        arg = arg.replace("${resolution_height}",  "480");
        return arg;
    }

    /** Evaluates Mojang rule objects. Returns true if the arg should be included. */
    private boolean isAllowed(JsonObject obj) {
        if (!obj.has("rules")) return true;
        for (JsonElement ruleEl : obj.getAsJsonArray("rules")) {
            if (!ruleEl.isJsonObject()) continue;
            JsonObject rule = ruleEl.getAsJsonObject();
            JsonElement actionEl = rule.get("action");
            if (actionEl == null) continue;
            String action = actionEl.getAsString();

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                JsonElement nameEl = os.get("name");
                if (nameEl == null) continue; // arch-only rule, skip
                String osName  = nameEl.getAsString();
                boolean matches = osName.equals(getCurrentOs());
                if ("allow".equals(action)    && !matches) return false;
                if ("disallow".equals(action) &&  matches) return false;
            }
            if (rule.has("features")) {
                if ("allow".equals(action)) return false;
            }
        }
        return true;
    }

    // ─── Classpath ────────────────────────────────────────────────────────────

    private String buildClasspath(String mcVersion, JsonObject fabricProfile,
                                   JsonObject vanillaJson, Path gameDir) {
        List<Path> entries = new ArrayList<>();
        Path libDir = gameDir.resolve("libraries");

        addLibraries(vanillaJson.getAsJsonArray("libraries"), libDir, entries);
        if (fabricProfile != null && fabricProfile.has("libraries"))
            addLibraries(fabricProfile.getAsJsonArray("libraries"), libDir, entries);

        entries.add(gameDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar"));

        StringBuilder cp = new StringBuilder();
        for (Path p : entries) {
            if (Files.exists(p)) {
                if (cp.length() > 0) cp.append(File.pathSeparator);
                cp.append(p.toAbsolutePath());
            }
        }
        return cp.toString();
    }

    private void addLibraries(JsonArray libraries, Path libDir, List<Path> entries) {
        if (libraries == null) return;
        for (JsonElement el : libraries) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!isLibraryAllowed(lib)) continue;

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) {
                if (lib.has("name")) {
                    Path p = mavenToPath(lib.get("name").getAsString(), libDir);
                    if (p != null) entries.add(p);
                }
                continue;
            }
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            if (artifact == null) continue;
            JsonElement pathEl = artifact.get("path");
            if (pathEl == null) continue;
            entries.add(libDir.resolve(pathEl.getAsString().replace("/", File.separator)));
        }
    }

    private Path mavenToPath(String coord, Path libDir) {
        String[] parts = coord.split(":");
        if (parts.length < 3) return null;
        String group      = parts[0].replace('.', '/');
        String artifact   = parts[1];
        String version    = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        String filename   = artifact + "-" + version + classifier + ".jar";
        return libDir.resolve(group).resolve(artifact).resolve(version).resolve(filename);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Returns true if the JVM arg is supported on the given Java version. */
    private boolean isArgCompatible(String arg, int javaVersion) {
        // --sun-misc-unsafe-memory-access was introduced in Java 22
        if (arg.contains("sun-misc-unsafe-memory-access") && javaVersion < 22) return false;
        return true;
    }

    /** Detects the major version number of the given javaw executable (e.g. 21, 26). */
    private int getJavaMajorVersion(String javaPath) {
        try {
            String exe = javaPath.replace("javaw.exe", "java.exe").replace("javaw", "java");
            ProcessBuilder pb = new ProcessBuilder(exe, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("version \"(\\d+)").matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 21;
    }

    private String findJava() {
        String exe    = isWindows() ? "javaw.exe" : "java";
        String best   = null;
        int bestMajor = 0;

        // 1. Standard JDK install dirs — version from folder name (e.g. "jdk-26")
        String[] jdkDirs = {
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\Microsoft",
            "C:\\Program Files\\BellSoft",
            "C:\\Program Files\\Amazon Corretto",
        };
        for (String base : jdkDirs) {
            Path dir = Paths.get(base);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                for (Path entry : (Iterable<Path>) stream::iterator) {
                    Path candidate = entry.resolve("bin").resolve(exe);
                    if (!Files.exists(candidate)) continue;
                    int major = parseMajorFromDir(entry.getFileName().toString());
                    if (major >= 25 && major > bestMajor) { bestMajor = major; best = candidate.toString(); }
                }
            } catch (Exception ignored) {}
        }

        // 2. Minecraft launcher bundled JREs (official, MS Store, CurseForge)
        //    Layout: <runtimeBase>/<runtimeName>/<platform>/<runtimeName>/bin/javaw.exe
        String localAppData = System.getenv("LOCALAPPDATA");
        String appData      = System.getenv("APPDATA");
        String userHome     = System.getProperty("user.home");
        List<Path> mcRuntimeBases = new ArrayList<>();
        // Official launcher
        if (appData != null)      mcRuntimeBases.add(Paths.get(appData,   ".minecraft", "runtime"));
        // CurseForge
        if (userHome != null)     mcRuntimeBases.add(Paths.get(userHome,  "curseforge", "minecraft", "Install", "runtime"));
        // Microsoft Store — scan Packages folder for any Minecraft package
        if (localAppData != null) {
            Path packages = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packages)) {
                try (var stream = Files.list(packages)) {
                    for (Path pkg : (Iterable<Path>) stream::iterator) {
                        if (pkg.getFileName().toString().startsWith("Microsoft.")) {
                            Path rt = pkg.resolve("LocalCache").resolve("Local").resolve("runtime");
                            if (Files.isDirectory(rt)) mcRuntimeBases.add(rt);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        for (Path base : mcRuntimeBases) {
            if (!Files.isDirectory(base)) continue;
            try (var s1 = Files.list(base)) {
                for (Path rtName : (Iterable<Path>) s1::iterator) {        // e.g. java-runtime-epsilon
                    try (var s2 = Files.list(rtName)) {
                        for (Path platform : (Iterable<Path>) s2::iterator) { // e.g. windows-x64
                            Path jreRoot  = platform.resolve(rtName.getFileName().toString());
                            Path candidate = jreRoot.resolve("bin").resolve(exe);
                            if (!Files.exists(candidate)) continue;
                            int major = readJavaVersionFromRelease(jreRoot);
                            if (major >= 25 && major > bestMajor) { bestMajor = major; best = candidate.toString(); }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        if (best != null) return best;

        // 3. JAVA_HOME only if >= 25
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null) {
            Path p = Paths.get(envJavaHome, "bin", exe);
            if (Files.exists(p) && getJavaMajorVersion(p.toString()) >= 25) return p.toString();
        }

        // 4. Last resort
        String sysJavaHome = System.getProperty("java.home");
        if (sysJavaHome != null) {
            Path p = Paths.get(sysJavaHome, "bin", exe);
            if (Files.exists(p)) return p.toString();
        }
        return exe;
    }

    /** Reads JAVA_VERSION from <jreRoot>/release and returns the major version number. */
    private int readJavaVersionFromRelease(Path jreRoot) {
        try {
            Path release = jreRoot.resolve("release");
            if (!Files.exists(release)) return 0;
            for (String line : Files.readAllLines(release)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                    return Integer.parseInt(v.split("[._]")[0]);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** Parses major version from directory name like "jdk-21", "jdk-21.0.5", "jdk-26.0.1" */
    private int parseMajorFromDir(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)").matcher(name);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String getCurrentOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private boolean isLibraryAllowed(JsonObject lib) {
        if (!lib.has("rules")) return true;
        for (JsonElement ruleEl : lib.getAsJsonArray("rules")) {
            if (!ruleEl.isJsonObject()) continue;
            JsonObject rule = ruleEl.getAsJsonObject();
            JsonElement actionEl = rule.get("action");
            if (actionEl == null) continue;
            String action = actionEl.getAsString();
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                JsonElement nameEl = os.get("name");
                if (nameEl == null) continue;
                String osName  = nameEl.getAsString();
                boolean matches = osName.equals(getCurrentOs());
                if ("allow".equals(action)    && !matches) return false;
                if ("disallow".equals(action) &&  matches) return false;
            }
        }
        return true;
    }

    /** Safe string getter with fallback. */
    private static String str(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        return el != null ? el.getAsString() : fallback;
    }
}
