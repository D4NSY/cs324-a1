@echo off
rem Command-line client, e.g.
rem   cli.bat status
rem   cli.bat submit PRIMESUM 1 1000
rem   cli.bat batch data\jobs-batch.csv
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || exit /b 1
if not exist distrilab.jar call "%~dp0build.bat" || exit /b 1
java -jar distrilab.jar cli %*
