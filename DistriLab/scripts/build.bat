@echo off
rem ===================================================================
rem  Builds distrilab.jar from the sources. Needs a JDK 9 or newer
rem  (javac and jar on PATH). The jar itself runs on Java 8 or newer.
rem ===================================================================
setlocal
cd /d "%~dp0.."
where javac >nul 2>nul
if errorlevel 1 (
  echo ERROR: javac was not found. Install a JDK ^(e.g. Temurin 21^) and add its bin folder to PATH.
  echo You do not need to build: distrilab.jar is already included and runs on Java 8+.
  exit /b 1
)
if exist out rmdir /s /q out
mkdir out\classes
rem Compiling the launcher with -sourcepath compiles every class it uses.
javac --release 8 -encoding UTF-8 -Xlint:all,-serial,-options -d out\classes -sourcepath src\main\java src\main\java\distrilab\Launcher.java
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
xcopy /e /i /q /y src\main\resources out\classes >nul
jar --create --file distrilab.jar --main-class distrilab.Launcher -C out\classes .
if errorlevel 1 (
  echo BUILD FAILED while creating the jar
  exit /b 1
)
echo Built distrilab.jar
endlocal
