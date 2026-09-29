@echo off
rem Starts one worker. The first argument is the worker ID; any further
rem options are passed through. Examples:
rem   start-worker.bat 3
rem   start-worker.bat 3 --threads=8 --host=192.168.1.21 --bootstrap=192.168.1.20:1099
rem With no arguments the bootstrap node assigns the next free ID.
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || (pause & exit /b 1)
if not exist distrilab.jar call "%~dp0build.bat" || (pause & exit /b 1)
if "%~1"=="" (
  java -jar distrilab.jar worker
) else (
  java -jar distrilab.jar worker --id=%*
)
