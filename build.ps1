# IrisLauncher build script
# Usage: .\build.ps1

$root       = Split-Path -Parent $MyInvocation.MyCommand.Path
$libDir     = "$root\libs"
$appClasses = "$root\out\app_classes"   # our compiled .class files
$fatDir     = "$root\out\fat"           # fat JAR staging area
$jarOut     = "$root\out\iris-launcher.jar"
$srcRoot    = "$root\src\main\java"
$resRoot    = "$root\src\main\resources"

function Download-Jar([string]$groupId, [string]$artifactId, [string]$version, [string]$classifier = "") {
    $groupPath = $groupId.Replace(".", "/")
    $file = if ($classifier) { "$artifactId-$version-$classifier.jar" } else { "$artifactId-$version.jar" }
    $url  = "https://repo1.maven.org/maven2/$groupPath/$artifactId/$version/$file"
    $dest = "$libDir\$file"
    if (Test-Path $dest) { Write-Host "  [skip] $file"; return $dest }
    Write-Host "  [download] $file ..."
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    return $dest
}

function Get-JavaHome {
    # Prefer Java 21 for compatibility with Gradle and JavaFX
    $candidates = @(
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Eclipse Adoptium\jdk-21",
        "C:\Program Files\Microsoft\jdk-21"
    )
    foreach ($c in $candidates) {
        if (Test-Path "$c\bin\javac.exe") { return $c }
    }
    # Fallback: use current java.home
    $output = cmd /c "java -XshowSettings:property -version 2>&1"
    foreach ($line in $output) {
        if ($line -match 'java\.home\s*=\s*(.+)') { return $Matches[1].Trim() }
    }
    throw "Cannot locate JDK 21. Install it from https://adoptium.net/"
}

Write-Host "=== IrisLauncher Build ===" -ForegroundColor Cyan
$jdkHome = Get-JavaHome
$javac   = "$jdkHome\bin\javac.exe"
$jarExe  = "$jdkHome\bin\jar.exe"
Write-Host "JDK: $jdkHome" -ForegroundColor DarkGray

# Clean & create dirs
Remove-Item -Recurse -Force "$root\out" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $libDir     | Out-Null
New-Item -ItemType Directory -Force -Path $appClasses | Out-Null
New-Item -ItemType Directory -Force -Path $fatDir     | Out-Null

# ── 1. Download dependencies ──────────────────────────────────────────────────
Write-Host "`n[1/4] Downloading dependencies..." -ForegroundColor Yellow
$jars = @()
$jars += Download-Jar "com.squareup.okhttp3" "okhttp"          "4.12.0"
$jars += Download-Jar "com.squareup.okio"    "okio-jvm"        "3.6.0"
$jars += Download-Jar "org.jetbrains.kotlin" "kotlin-stdlib"   "1.9.22"
$jars += Download-Jar "com.google.code.gson" "gson"            "2.10.1"
$jars += Download-Jar "org.openjfx"          "javafx-base"     "23.0.1" "win"
$jars += Download-Jar "org.openjfx"          "javafx-graphics" "23.0.1" "win"
$jars += Download-Jar "org.openjfx"          "javafx-controls" "23.0.1" "win"
$jars += Download-Jar "org.openjfx"          "javafx-fxml"     "23.0.1" "win"
$classpath = ($jars -join ";")

# ── 2. Compile ────────────────────────────────────────────────────────────────
Write-Host "`n[2/4] Compiling..." -ForegroundColor Yellow
$sourceListFile = "$root\out\sources.txt"
New-Item -ItemType Directory -Force -Path "$root\out" | Out-Null
$sourceFiles = Get-ChildItem -Path $srcRoot -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
[System.IO.File]::WriteAllLines($sourceListFile, $sourceFiles, [System.Text.UTF8Encoding]::new($false))

$result = & $javac --release 21 -cp $classpath -d $appClasses "@$sourceListFile" 2>&1
if ($LASTEXITCODE -ne 0) { $result | Write-Host; Write-Host "FAILED" -ForegroundColor Red; exit 1 }
Write-Host "  OK" -ForegroundColor Green

# ── 3. Copy resources ─────────────────────────────────────────────────────────
Write-Host "`n[3/4] Packaging fat JAR..." -ForegroundColor Yellow

# a) Extract all dependency JARs into fatDir
foreach ($jar in $jars) {
    Push-Location $fatDir
    & $jarExe xf $jar 2>&1 | Out-Null
    Pop-Location
}

# b) Copy our compiled classes into fatDir (overwrite deps if conflict)
Copy-Item -Recurse -Force "$appClasses\*" $fatDir

# c) Copy resources
Copy-Item -Recurse -Force "$resRoot\*" $fatDir

# ── 4. Create JAR ─────────────────────────────────────────────────────────────
$manifest = "$root\out\MANIFEST.MF"
[System.IO.File]::WriteAllText($manifest,
    "Manifest-Version: 1.0`r`nMain-Class: com.irislauncher.Main`r`n`r`n")

& $jarExe cfm $jarOut $manifest -C $fatDir .

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nBuild successful!" -ForegroundColor Green
    Write-Host "Output: $jarOut" -ForegroundColor Cyan
    Write-Host "Run:    run.bat" -ForegroundColor White
} else {
    Write-Host "JAR creation failed!" -ForegroundColor Red; exit 1
}
