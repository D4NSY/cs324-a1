@echo off
rem Starts the bootstrap node. Extra options are passed through, e.g.
rem   start-bootstrap.bat --port=2000 --host=192.168.1.20
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || (pause & exit /b 1)
if not exist distrilab.jar call "%~dp0build.bat" || (pause & exit /b 1)
java -jar distrilab.jar bootstrap %*
