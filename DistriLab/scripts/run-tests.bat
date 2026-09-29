@echo off
rem Runs the unit tests, then the end-to-end cluster test (which starts its
rem own bootstrap node and 5 workers on port 1299). Rebuilds first if a JDK
rem is available; otherwise tests the included distrilab.jar.
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || (pause & exit /b 1)
where javac >nul 2>nul && (call "%~dp0build.bat" || (pause & exit /b 1))
java -jar distrilab.jar test
if errorlevel 1 (pause & exit /b 1)
java -jar distrilab.jar cluster-test
pause
