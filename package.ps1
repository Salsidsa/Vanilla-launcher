$ErrorActionPreference = "Stop"
$root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk21   = "C:\Program Files\Java\jdk-21"
$output  = "$root\VanillaLauncherSetup.exe"
$tmp     = "$root\_pkg_tmp"
$icon    = "$root\minecraft_macos_bigsur_icon_189943.ico"

# -- 1. Build JAR --------------------------------------------------------------
Write-Host "[1/4] Building JAR..." -ForegroundColor Cyan
$env:JAVA_HOME = $jdk21
& "$root\gradlew.bat" jar
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed!" -ForegroundColor Red; exit 1 }

# -- 2. jpackage app-image (bundles JRE - no Java needed on friend's PC) ------
Write-Host "[2/4] Creating app-image with bundled JRE..." -ForegroundColor Cyan
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
New-Item -ItemType Directory $tmp | Out-Null

$appImageDir = "$tmp\app_image"
New-Item -ItemType Directory $appImageDir | Out-Null

& "$jdk21\bin\jpackage.exe" `
    --type app-image `
    --name "Vanilla Launcher" `
    --input "$root\out" `
    --main-jar "iris-launcher.jar" `
    --icon "$icon" `
    --java-options "--add-opens javafx.fxml/javafx.fxml=ALL-UNNAMED" `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --dest $appImageDir

if ($LASTEXITCODE -ne 0) { Write-Host "jpackage failed!" -ForegroundColor Red; exit 1 }

# -- 3. Zip the app-image contents ---------------------------------------------
Write-Host "[3/4] Zipping app-image..." -ForegroundColor Cyan
$appFolder = "$appImageDir\Vanilla Launcher"
$zipPath   = "$tmp\app.zip"
Compress-Archive -Path "$appFolder\*" -DestinationPath $zipPath

# -- 4. Compile + assemble self-extracting installer EXE ----------------------
Write-Host "[4/4] Building installer EXE..." -ForegroundColor Cyan

$cs = @'
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Windows.Forms;

class Setup : Form {
    Label  titleLabel, subtitleLabel, pathLabel, statusLabel;
    Button installBtn, cancelBtn;
    ProgressBar bar;
    Panel topPanel;

    static string dest = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "VanillaLauncher");

    [STAThread]
    static void Main() {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new Setup());
    }

    Setup() {
        // -- Form --
        Text            = "Vanilla Launcher Setup";
        Size            = new Size(460, 300);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox     = false;
        StartPosition   = FormStartPosition.CenterScreen;
        BackColor       = Color.FromArgb(18, 18, 30);
        Font            = new Font("Segoe UI", 9f);

        // -- Top accent bar --
        topPanel = new Panel {
            Dock      = DockStyle.Top,
            Height    = 4,
            BackColor = Color.FromArgb(93, 204, 255)
        };

        // -- Title --
        titleLabel = new Label {
            Text      = "VANILLA LAUNCHER",
            ForeColor = Color.White,
            BackColor = Color.Transparent,
            Font      = new Font("Segoe UI", 15f, FontStyle.Bold),
            AutoSize  = true,
            Location  = new Point(30, 28)
        };

        subtitleLabel = new Label {
            Text      = "Minecraft 26.1.1",
            ForeColor = Color.FromArgb(140, 140, 160),
            BackColor = Color.Transparent,
            Font      = new Font("Segoe UI", 9f),
            AutoSize  = true,
            Location  = new Point(32, 62)
        };

        // -- Path --
        pathLabel = new Label {
            Text      = "Install to:  " + dest,
            ForeColor = Color.FromArgb(120, 120, 140),
            BackColor = Color.Transparent,
            Font      = new Font("Segoe UI", 8f),
            AutoSize  = true,
            Location  = new Point(30, 100)
        };

        // -- Progress bar (hidden initially) --
        bar = new ProgressBar {
            Location = new Point(30, 185),
            Size     = new Size(400, 6),
            Style    = ProgressBarStyle.Marquee,
            Visible  = false
        };

        // -- Status --
        statusLabel = new Label {
            Text      = "",
            ForeColor = Color.FromArgb(93, 204, 255),
            BackColor = Color.Transparent,
            Font      = new Font("Segoe UI", 8f),
            AutoSize  = true,
            Location  = new Point(30, 200)
        };

        // -- Buttons --
        installBtn = new Button {
            Text      = "Install",
            Location  = new Point(248, 230),
            Size      = new Size(90, 30),
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(93, 204, 255),
            ForeColor = Color.FromArgb(10, 10, 20),
            Font      = new Font("Segoe UI", 9f, FontStyle.Bold),
            Cursor    = Cursors.Hand
        };
        installBtn.FlatAppearance.BorderSize = 0;
        installBtn.Click += DoInstall;

        cancelBtn = new Button {
            Text      = "Cancel",
            Location  = new Point(348, 230),
            Size      = new Size(80, 30),
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(40, 40, 58),
            ForeColor = Color.FromArgb(160, 160, 180),
            Font      = new Font("Segoe UI", 9f),
            Cursor    = Cursors.Hand
        };
        cancelBtn.FlatAppearance.BorderSize = 0;
        cancelBtn.Click += (s, e) => Application.Exit();

        Controls.AddRange(new Control[] {
            topPanel, titleLabel, subtitleLabel, pathLabel,
            bar, statusLabel, installBtn, cancelBtn
        });
    }

    void DoInstall(object sender, EventArgs e) {
        installBtn.Enabled = false;
        cancelBtn.Enabled  = false;
        bar.Visible        = true;
        statusLabel.Text   = "Installing...";

        var worker = new System.ComponentModel.BackgroundWorker();
        worker.DoWork += (s, ev) => {
            string exePath = Assembly.GetExecutingAssembly().Location;
            byte[] all = File.ReadAllBytes(exePath);
            long zipOffset = BitConverter.ToInt64(all, all.Length - 8);
            int  zipLen    = all.Length - 8 - (int)zipOffset;

            if (Directory.Exists(dest)) Directory.Delete(dest, true);
            Directory.CreateDirectory(dest);

            using (var ms = new MemoryStream(all, (int)zipOffset, zipLen))
            using (var zip = new ZipArchive(ms, ZipArchiveMode.Read)) {
                foreach (var entry in zip.Entries) {
                    string name = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                    string full = Path.Combine(dest, name);
                    if (name.EndsWith(Path.DirectorySeparatorChar.ToString())) {
                        Directory.CreateDirectory(full);
                    } else {
                        Directory.CreateDirectory(Path.GetDirectoryName(full));
                        using (var src = entry.Open())
                        using (var dst = File.Create(full))
                            src.CopyTo(dst);
                    }
                }
            }

            string launcherExe = Path.Combine(dest, "Vanilla Launcher.exe");
            string ps =
                "$ws=New-Object -ComObject WScript.Shell;" +
                "$s=$ws.CreateShortcut([Environment]::GetFolderPath('Desktop')+'\\Vanilla Launcher.lnk');" +
                "$s.TargetPath='" + launcherExe.Replace("'", "''") + "';" +
                "$s.WorkingDirectory='" + dest.Replace("'", "''") + "';" +
                "$s.IconLocation='" + launcherExe.Replace("'", "''") + ",0';" +
                "$s.Description='Vanilla Minecraft Launcher';" +
                "$s.Save()";
            var psi = new ProcessStartInfo("powershell.exe",
                "-NoProfile -ExecutionPolicy Bypass -Command \"" + ps + "\"") {
                WindowStyle = ProcessWindowStyle.Hidden, CreateNoWindow = true
            };
            Process.Start(psi).WaitForExit();
        };
        worker.RunWorkerCompleted += (s, ev) => {
            if (ev.Error != null) {
                bar.Visible      = false;
                statusLabel.Text = "Error: " + ev.Error.Message;
                installBtn.Enabled = true;
                cancelBtn.Enabled  = true;
            } else {
                bar.Visible      = false;
                statusLabel.Text = "Done! Shortcut created on your Desktop.";
                cancelBtn.Text   = "Close";
                cancelBtn.Enabled = true;
            }
        };
        worker.RunWorkerAsync();
    }
}
'@

$stubPath = "$tmp\stub.exe"

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$winFormsAsm    = [System.Windows.Forms.Form].Assembly.Location
$compressionAsm = [System.IO.Compression.ZipArchive].Assembly.Location
$compressionFS  = Join-Path (Split-Path $compressionAsm) "System.IO.Compression.FileSystem.dll"
$systemAsm      = [System.Diagnostics.Process].Assembly.Location
$drawingAsm     = [System.Drawing.Color].Assembly.Location

$cp = New-Object System.CodeDom.Compiler.CompilerParameters
$cp.OutputAssembly     = $stubPath
$cp.GenerateExecutable = $true
$cp.CompilerOptions    = "/target:winexe /win32icon:`"$icon`""
$cp.ReferencedAssemblies.Add($winFormsAsm)    | Out-Null
$cp.ReferencedAssemblies.Add($compressionAsm) | Out-Null
$cp.ReferencedAssemblies.Add($systemAsm)      | Out-Null
$cp.ReferencedAssemblies.Add($drawingAsm)     | Out-Null
if (Test-Path $compressionFS) {
    $cp.ReferencedAssemblies.Add($compressionFS) | Out-Null
}

Add-Type -TypeDefinition $cs -CompilerParameters $cp

# Append ZIP to stub, then write offset as 8 bytes
$stubBytes = [System.IO.File]::ReadAllBytes($stubPath)
$zipBytes  = [System.IO.File]::ReadAllBytes($zipPath)
$offset    = [long]$stubBytes.Length

$stream = [System.IO.File]::Open($output, [System.IO.FileMode]::Create)
$stream.Write($stubBytes, 0, $stubBytes.Length)
$stream.Write($zipBytes,  0, $zipBytes.Length)
$stream.Write([BitConverter]::GetBytes($offset), 0, 8)
$stream.Close()

# Cleanup (stub.exe may be locked by the PS session - ignore)
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue

if (Test-Path $output) {
    $sizeMB = [math]::Round((Get-Item $output).Length / 1MB, 1)
    Write-Host ""
    Write-Host "  Done: $output ($sizeMB MB)" -ForegroundColor Green
    Write-Host "  Send this file to your friend - no Java required!" -ForegroundColor White
} else {
    Write-Host "  Failed to create installer." -ForegroundColor Red
}
