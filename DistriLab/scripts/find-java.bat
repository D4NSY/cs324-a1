@echo off
rem ===================================================================
rem  Makes sure "java" (version 8 or newer) can be run by the other
rem  scripts, even when Java is installed but not on PATH.
rem  Order: PATH, JAVA_HOME, then the usual install folders (a newer
rem  JDK is preferred over an old JRE). Called by the other scripts.
rem ===================================================================
where java >nul 2>nul
if not errorlevel 1 exit /b 0
set "DISTRILAB_JAVA_DIR="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "DISTRILAB_JAVA_DIR=%JAVA_HOME%\bin"
if not defined DISTRILAB_JAVA_DIR for /d %%d in ("%ProgramFiles%\Java\*" "%ProgramFiles(x86)%\Java\*" "%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Microsoft\jdk*" "%ProgramFiles%\Zulu\*" "%ProgramFiles%\Amazon Corretto\*") do if exist "%%~d\bin\java.exe" set "DISTRILAB_JAVA_DIR=%%~d\bin"
if not defined DISTRILAB_JAVA_DIR goto notfound
set "PATH=%DISTRILAB_JAVA_DIR%;%PATH%"
echo Using Java from "%DISTRILAB_JAVA_DIR%"
exit /b 0
:notfound
echo ERROR: Java was not found. Install Java 8 or newer, e.g. Temurin 21 from https://adoptium.net
exit /b 1
