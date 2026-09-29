@echo off
rem Closes every window started by demo.bat.
taskkill /FI "WINDOWTITLE eq DistriLab*" /T /F >nul 2>nul
echo Stopped the DistriLab windows.
