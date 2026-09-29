@echo off
rem ===================================================================
rem  Runs some jobs from the command line against a running cluster
rem  (start it first with demo.bat) and keeps the window open so you
rem  can read the results.
rem ===================================================================
setlocal
cd /d "%~dp0.."
call "%~dp0find-java.bat" || (pause & exit /b 1)
echo ==== Cluster status ==========================================
java -jar distrilab.jar cli status
echo.
echo ==== PRIMESUM(1,1000) - the example from the brief ===========
java -jar distrilab.jar cli submit PRIMESUM 1 1000
echo.
echo ==== 12 jobs from data\jobs-batch.csv, all at the same time ===
java -jar distrilab.jar cli batch data\jobs-batch.csv
echo.
echo ==== Cluster status after the batch ===========================
java -jar distrilab.jar cli status
echo.
pause
