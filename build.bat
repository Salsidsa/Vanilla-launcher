@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-21
echo Building Shpak Launcher...
call "%~dp0gradlew.bat" jar
if errorlevel 1 (
    echo Build failed!
    pause
) else (
    echo Done! Run launch.vbs to start.
    pause
)
