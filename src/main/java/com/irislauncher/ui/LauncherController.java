package com.irislauncher.ui;

import com.google.gson.*;
import com.irislauncher.auth.AuthResult;
import com.irislauncher.download.FabricInstaller;
import com.irislauncher.download.MinecraftDownloader;
import com.irislauncher.download.ModDownloader;
import com.irislauncher.launch.GameLauncher;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.awt.Desktop;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;

public class LauncherController {

    private static final String APP_VERSION      = "1.0.0";
    private static final String MODDED_VERSION   = "26.1.1";
    private static final String GITHUB_RELEASES_API =
            "https://api.github.com/repos/Salsidsa/Vanilla-launcher/releases/latest";

    // ── FXML fields ──────────────────────────────────────────────────────────
    @FXML private ComboBox<VersionEntry> versionBox;
    @FXML private TextField   nicknameField;
    @FXML private Button      launchButton;
    @FXML private VBox        progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label       statusLabel;
    @FXML private TextArea    consoleArea;
    @FXML private ImageView   gifView;
    @FXML private StackPane   rightPane;
    @FXML private Region      nickUnderline;

    private final Path gameDir = getDefaultGameDir();

    // ── Init ─────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        // Create launcher_profiles.json stub so external tools (e.g. Fabric Installer) can find it
        try {
            Files.createDirectories(gameDir);
            Path profiles = gameDir.resolve("launcher_profiles.json");
            if (!Files.exists(profiles))
                Files.writeString(profiles,
                    "{\"profiles\":{},\"settings\":{},\"version\":3}");
        } catch (Exception ignored) {}

        // Saved nickname
        try {
            Path nickFile = gameDir.resolve("nickname.txt");
            if (Files.exists(nickFile))
                nicknameField.setText(Files.readString(nickFile).trim());
        } catch (Exception ignored) {}

        // Underline on focus
        if (nickUnderline != null) {
            nicknameField.focusedProperty().addListener((obs, was, is) ->
                nickUnderline.setStyle(is
                    ? "-fx-background-color: white;"
                    : "-fx-background-color: rgba(255,255,255,0.25);"));
        }

        // GIF
        if (gifView != null && rightPane != null) {
            try {
                URL res = getClass().getResource("/com/irislauncher/bg.gif");
                if (res != null) {
                    gifView.setImage(new Image(res.toExternalForm()));
                    gifView.fitWidthProperty().bind(rightPane.widthProperty());
                    gifView.fitHeightProperty().bind(rightPane.heightProperty());
                }
            } catch (Exception ignored) {}
        }

        // Version combo styling
        versionBox.setCellFactory(lv -> new VersionCell());
        versionBox.setButtonCell(new VersionCell());

        // Always add modded first, then load the rest in background
        versionBox.getItems().add(new VersionEntry(MODDED_VERSION, readFabricDisplayId(), "modded", isVersionDownloaded(MODDED_VERSION)));
        versionBox.getSelectionModel().selectFirst();

        Thread t = new Thread(this::loadVersions);
        t.setDaemon(true);
        t.start();

        Thread w = new Thread(this::watchVersionsDir);
        w.setDaemon(true);
        w.start();

        Thread u = new Thread(this::checkForUpdates);
        u.setDaemon(true);
        u.start();
    }

    // ── Version loading ───────────────────────────────────────────────────────
    private void loadVersions() {
        List<VersionEntry> local = new ArrayList<>();
        try (var stream = Files.list(gameDir.resolve("versions"))) {
            stream.filter(Files::isDirectory)
                  .map(p -> p.getFileName().toString())
                  .filter(n -> !n.equals(MODDED_VERSION)
                           && !n.startsWith(MODDED_VERSION + "-fabric"))
                  .sorted()
                  .forEach(name -> local.add(new VersionEntry(name, detectVersionType(name), true)));
        } catch (Exception ignored) {}

        Platform.runLater(() -> {
            versionBox.getItems().addAll(local);
            versionBox.setButtonCell(new VersionCell());
        });
    }

    /** Watches versions/ for new or deleted folders and syncs the combo box live. */
    private void watchVersionsDir() {
        try {
            Path versionsDir = gameDir.resolve("versions");
            Files.createDirectories(versionsDir);
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                versionsDir.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                while (true) {
                    WatchKey key = watcher.take();
                    boolean changed = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path name = (Path) event.context();
                        if (Files.isDirectory(versionsDir.resolve(name)) ||
                                event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            changed = true;
                        }
                    }
                    key.reset();
                    if (changed) {
                        Thread.sleep(300); // debounce — wait for folder to finish writing
                        Platform.runLater(this::syncLocalVersions);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** Re-scans versions/ and adds/removes entries in the combo box without touching MODDED. */
    private void syncLocalVersions() {
        Set<String> shown = new HashSet<>();
        for (VersionEntry e : versionBox.getItems()) shown.add(e.id);

        // Add newly appeared folders
        try (var stream = Files.list(gameDir.resolve("versions"))) {
            stream.filter(Files::isDirectory)
                  .map(p -> p.getFileName().toString())
                  .filter(n -> !n.equals(MODDED_VERSION) && !n.startsWith(MODDED_VERSION + "-fabric"))
                  .filter(n -> !shown.contains(n))
                  .forEach(n -> versionBox.getItems()
                          .add(new VersionEntry(n, detectVersionType(n), true)));
        } catch (Exception ignored) {}

        // Remove entries whose folders no longer exist (keep MODDED and Mojang manifest entries)
        versionBox.getItems().removeIf(e ->
                !e.isModded()
                && e.downloaded
                && !Files.isDirectory(gameDir.resolve("versions").resolve(e.id)));
    }

    /** Infer type from version folder name (e.g. "25w10a" → snapshot, else release). */
    private static String detectVersionType(String id) {
        return id.matches("\\d+w\\d+[a-z].*") ? "snapshot" : "release";
    }

    // ── Launch ────────────────────────────────────────────────────────────────
    @FXML
    public void onLaunch() {
        String name = nicknameField.getText().trim();
        if (name.isEmpty())                        { setStatus("Enter a nickname!"); return; }
        if (name.length() < 3 || name.length() > 16) { setStatus("Nickname must be 3-16 characters."); return; }

        VersionEntry selected = versionBox.getValue();
        if (selected == null) { setStatus("Select a version!"); return; }

        try {
            Files.createDirectories(gameDir);
            Files.writeString(gameDir.resolve("nickname.txt"), name);
        } catch (Exception ignored) {}

        UUID uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        AuthResult auth = new AuthResult("offline", "", name,
                uuid.toString().replace("-", ""));

        launchButton.setDisable(true);
        launchButton.setText("LOADING...");
        launchButton.setStyle("-fx-background-color: #cccccc; -fx-text-fill: #333333; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 5; -fx-cursor: default;");
        showProgress(true);
        consoleArea.clear();

        final String mcVersion = selected.id;
        final boolean isModded = selected.isModded();

        Thread t = new Thread(() -> {
            try {
                MinecraftDownloader mc = new MinecraftDownloader();
                mc.setStatusListener(this::setStatus);
                mc.setProgressListener((cur, total) -> Platform.runLater(() ->
                        progressBar.setProgress(total > 0 ? (double) cur / total : -1)));

                JsonObject vanillaJson;
                JsonObject fabricProfile = null;
                String baseVersion = mcVersion; // version used for JAR path in GameLauncher

                if (isModded) {
                    // Our built-in modded: download vanilla, then install Fabric + mods
                    vanillaJson = mc.download(mcVersion, gameDir);

                    FabricInstaller fabric = new FabricInstaller(mc);
                    fabric.setStatusListener(this::setStatus);
                    fabricProfile = fabric.install(mcVersion, gameDir);

                    ModDownloader mods = new ModDownloader();
                    mods.setStatusListener(this::setStatus);
                    mods.installAll(gameDir, mcVersion);
                } else {
                    // Check if this is an externally installed Fabric/modded profile (has inheritsFrom)
                    Path localJsonFile = gameDir.resolve("versions").resolve(mcVersion)
                                                .resolve(mcVersion + ".json");
                    if (Files.exists(localJsonFile)) {
                        JsonObject localJson = JsonParser.parseString(
                                Files.readString(localJsonFile)).getAsJsonObject();
                        if (localJson.has("inheritsFrom")) {
                            baseVersion = localJson.get("inheritsFrom").getAsString();
                            setStatus("Downloading base version " + baseVersion + "...");
                            vanillaJson = mc.download(baseVersion, gameDir);
                            fabricProfile = localJson;
                        } else {
                            vanillaJson = mc.download(mcVersion, gameDir);
                        }
                    } else {
                        vanillaJson = mc.download(mcVersion, gameDir);
                    }
                }

                GameLauncher launcher = new GameLauncher();
                launcher.setStatusListener(this::setStatus);
                launcher.setOutputListener(this::appendConsole);

                setStatus("Launching Minecraft " + mcVersion + "...");
                Process process = launcher.launch(baseVersion, fabricProfile, vanillaJson, gameDir, auth);

                Platform.runLater(() -> {
                    showProgress(false);
                    setStatusDirect("Running! PID: " + process.pid());
                    resetLaunchButton();
                    // Refresh downloaded badge — re-read Fabric ID for modded, update list cell
                    VersionEntry v = versionBox.getValue();
                    if (v != null) {
                        String display = v.isModded() ? readFabricDisplayId() : v.id;
                        VersionEntry updated = new VersionEntry(v.id, display, v.type, true);
                        int idx = versionBox.getItems().indexOf(v);
                        if (idx >= 0) versionBox.getItems().set(idx, updated);
                        versionBox.setValue(updated);
                        versionBox.setButtonCell(new VersionCell());
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatusDirect("Error: " + e.getMessage());
                    appendConsole("ERROR: " + e.getMessage());
                    showProgress(false);
                    resetLaunchButton();
                });
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── Discord ───────────────────────────────────────────────────────────────
    @FXML
    public void onOpenMods() {
        try {
            Path modsDir = gameDir.resolve("mods");
            Files.createDirectories(modsDir);
            Desktop.getDesktop().open(modsDir.toFile());
        } catch (Exception ignored) {}
    }

    @FXML
    public void onDiscord() {
        try { Desktop.getDesktop().browse(new URI("https://discord.gg/YFhgyuhRgG")); }
        catch (Exception ignored) {}
    }

    // ── Auto-update ───────────────────────────────────────────────────────────
    private void checkForUpdates() {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new URL(GITHUB_RELEASES_API).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) return;
            String body = new String(conn.getInputStream().readAllBytes());
            conn.disconnect();

            JsonObject release = JsonParser.parseString(body).getAsJsonObject();
            String tag = release.get("tag_name").getAsString().replaceFirst("^v", "");

            if (!isNewerVersion(tag, APP_VERSION)) return;

            String assetUrl = null;
            for (JsonElement el : release.getAsJsonArray("assets")) {
                JsonObject a = el.getAsJsonObject();
                if (a.get("name").getAsString().endsWith(".jar")) {
                    assetUrl = a.get("browser_download_url").getAsString();
                    break;
                }
            }
            final String downloadUrl = assetUrl;

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Update available");
                alert.setHeaderText("Version " + tag + " is available");
                alert.setContentText("Install update now?");
                alert.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.OK && downloadUrl != null)
                        installUpdate(downloadUrl);
                });
            });
        } catch (Exception ignored) {}
    }

    private void installUpdate(String url) {
        launchButton.setDisable(true);
        setStatus("Downloading update...");
        showProgress(true);

        Thread t = new Thread(() -> {
            try {
                Path currentJar = Path.of(
                        LauncherController.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
                Path tmpJar = currentJar.resolveSibling(currentJar.getFileName() + ".update");

                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                long total = conn.getContentLengthLong();

                try (var in  = conn.getInputStream();
                     var out = Files.newOutputStream(tmpJar)) {
                    byte[] buf = new byte[8192];
                    long done = 0; int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        final long d = done, tot = total;
                        Platform.runLater(() ->
                                progressBar.setProgress(tot > 0 ? (double) d / tot : -1));
                    }
                }

                Files.move(tmpJar, currentJar, StandardCopyOption.REPLACE_EXISTING);

                Platform.runLater(() -> {
                    showProgress(false);
                    launchButton.setDisable(false);
                    Alert done = new Alert(Alert.AlertType.INFORMATION);
                    done.setTitle("Update installed");
                    done.setHeaderText("Update downloaded successfully");
                    done.setContentText("Restart the launcher to apply the update.");
                    done.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showProgress(false);
                    launchButton.setDisable(false);
                    setStatusDirect("Update failed: " + e.getMessage());
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static boolean isNewerVersion(String remote, String local) {
        try {
            int[] r = Arrays.stream(remote.split("\\.")).mapToInt(Integer::parseInt).toArray();
            int[] l = Arrays.stream(local.split("\\.")).mapToInt(Integer::parseInt).toArray();
            for (int i = 0; i < Math.max(r.length, l.length); i++) {
                int rv = i < r.length ? r[i] : 0;
                int lv = i < l.length ? l[i] : 0;
                if (rv > lv) return true;
                if (rv < lv) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void resetLaunchButton() {
        launchButton.setDisable(false);
        launchButton.setText("PLAY");
        launchButton.setStyle("");
    }
    private void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
        appendConsole(msg);
    }
    private void setStatusDirect(String msg) {
        statusLabel.setText(msg);
        appendConsole(msg);
    }
    private void appendConsole(String line) {
        Platform.runLater(() -> consoleArea.appendText(line + "\n"));
    }
    private void showProgress(boolean show) {
        Platform.runLater(() -> {
            progressBox.setVisible(show);
            progressBox.setManaged(show);
        });
    }
    private static Path getDefaultGameDir() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null) return Paths.get(appdata, ".vanillalauncher");
        return Paths.get(System.getProperty("user.home"), ".vanillalauncher");
    }

    // ── Version helpers ───────────────────────────────────────────────────────
    private boolean isVersionDownloaded(String id) {
        return Files.isDirectory(gameDir.resolve("versions").resolve(id));
    }

    private String readFabricDisplayId() {
        try {
            Path versionsDir = gameDir.resolve("versions");
            if (Files.isDirectory(versionsDir)) {
                try (var stream = Files.list(versionsDir)) {
                    return stream
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith(MODDED_VERSION + "-fabric"))
                        .findFirst()
                        .orElse(MODDED_VERSION);
                }
            }
        } catch (Exception ignored) {}
        return MODDED_VERSION;
    }

    // ── Version entry ─────────────────────────────────────────────────────────
    public static class VersionEntry {
        final String  id;
        final String  displayId;  // shown in UI; equals id for normal versions
        final String  type;       // "modded", "release", "snapshot"
        boolean       downloaded; // true if versions/<id> folder exists

        VersionEntry(String id, String type, boolean downloaded) {
            this.id = id; this.displayId = id; this.type = type; this.downloaded = downloaded;
        }
        VersionEntry(String id, String displayId, String type, boolean downloaded) {
            this.id = id; this.displayId = displayId; this.type = type; this.downloaded = downloaded;
        }

        boolean isModded()   { return "modded".equals(type); }
        boolean isSnapshot() { return "snapshot".equals(type); }
    }

    // ── Custom combo cell ─────────────────────────────────────────────────────
    private static class VersionCell extends ListCell<VersionEntry> {
        @Override
        protected void updateItem(VersionEntry item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                setStyle("-fx-background-color: #16162a;");
                return;
            }

            HBox row = new HBox(8);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            row.setStyle("-fx-background-color: transparent;");

            Label id = new Label(item.displayId);
            id.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-background-color: transparent;");

            // Type badge (MODDED / RELEASE / SNAPSHOT) — unchanged styling
            Label typeBadge = new Label(item.type.toUpperCase());
            typeBadge.setStyle(
                "-fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 6; " +
                "-fx-background-radius: 3; " +
                (item.isModded()
                    ? "-fx-background-color: #5865F2; -fx-text-fill: white;"
                    : item.isSnapshot()
                        ? "-fx-background-color: #332800; -fx-text-fill: #ffcc00;"
                        : "-fx-background-color: #1e2a1e; -fx-text-fill: #88cc88;")
            );

            // Download status badge — blue if downloaded, red if not
            Label dlBadge = new Label(item.downloaded ? "DOWNLOADED" : "NOT DOWNLOADED");
            dlBadge.setStyle(
                "-fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 6; " +
                "-fx-background-radius: 3; " +
                (item.downloaded
                    ? "-fx-background-color: #1a3a5c; -fx-text-fill: #4a9eff;"
                    : "-fx-background-color: #3a1a1a; -fx-text-fill: #e05252;")
            );

            row.getChildren().addAll(id, typeBadge, dlBadge);
            setGraphic(row);
            setStyle("-fx-background-color: #16162a; -fx-padding: 5 8;");
        }
    }
}
