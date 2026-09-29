@echo off
rem ===================================================================
rem  One-click local demo: bootstrap node + N workers (default 4) + one
rem  client GUI, each in its own window, all on this computer.
rem    demo.bat        -> 4 workers
rem    demo.bat 6      -> 6 workers
rem  Stop everything with stop-demo.bat (or close the windows).
rem ===================================================================
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || (pause & exit /b 1)
if not exist distrilab.jar call "%~dp0build.bat" || (pause & exit /b 1)
set WORKERS=%~1
if "%WORKERS%"=="" set WORKERS=4
start "DistriLab Bootstrap" cmd /k java -jar distrilab.jar bootstrap
timeout /t 3 /nobreak >nul
for /l %%i in (1,1,%WORKERS%) do (
  start "DistriLab Worker %%i" cmd /k java -jar distrilab.jar worker --id=%%i
  timeout /t 2 /nobreak >nul
)
start "DistriLab Client" cmd /k java -jar distrilab.jar client
echo Started the bootstrap node, %WORKERS% workers and a client GUI.
echo Type "status" in any worker window to see its state. Run stop-demo.bat to stop.
endlocal
