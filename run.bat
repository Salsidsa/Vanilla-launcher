@echo off
set ROOT=%~dp0
set JAVA_HOME=C:\Program Files\Java\jdk-21

if exist "C:\Program Files\Java\jdk-21\bin\javaw.exe" (
    set JAVA_EXE=C:\Program Files\Java\jdk-21\bin\javaw.exe
) else (
    set JAVA_EXE=javaw.exe
)

start "" "%JAVA_EXE%" --add-opens javafx.fxml/javafx.fxml=ALL-UNNAMED ^
     --enable-native-access=javafx.graphics ^
     -jar "%ROOT%out\iris-launcher.jar"
