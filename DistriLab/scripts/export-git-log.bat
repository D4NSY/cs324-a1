@echo off
rem Writes the Git history (all commits and commits per author) to git-log.txt.
setlocal
cd /d "%~dp0.."
git log --reverse --date=format:"%%Y-%%m-%%d %%H:%%M" --pretty=format:"%%h  %%ad  %%<(18,trunc)%%an  %%s" > git-log.txt
echo.>> git-log.txt
git shortlog -sne HEAD >> git-log.txt
echo Wrote git-log.txt
