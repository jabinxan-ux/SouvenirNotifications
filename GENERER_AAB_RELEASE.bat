@echo off
setlocal
cd /d "%~dp0"

set "JAVA_HOME=%USERPROFILE%\.jdks\jbr-21.0.11"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK 21 introuvable. Installe JDK 21 ou corrige JAVA_HOME dans ce fichier.
    pause
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JDK utilise : %JAVA_HOME%
echo Generation release standard avec Lint...
call gradlew.bat --no-daemon :app:bundleRelease
set "RESULT=%ERRORLEVEL%"

if "%RESULT%"=="0" (
    echo.
    echo Build reussi. L'AAB est dans :
    echo app\build-souvenir-0.4.17\outputs\bundle\release\app-release.aab
) else (
    echo.
    echo Build echoue avec le code %RESULT%. Lis les dernieres lignes ci-dessus.
)
echo.
pause
exit /b %RESULT%
