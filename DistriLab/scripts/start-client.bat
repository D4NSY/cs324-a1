@echo off
rem Starts the client GUI. Start it several times for several clients.
rem   start-client.bat --bootstrap=192.168.1.20:1099
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || (pause & exit /b 1)
if not exist distrilab.jar call "%~dp0build.bat" || (pause & exit /b 1)
java -jar distrilab.jar client %*
