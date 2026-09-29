#!/usr/bin/env sh
# Writes the Git history (all commits and commits per author) to git-log.txt.
cd "$(dirname "$0")/.." || exit 1
{
  git log --reverse --date=format:'%Y-%m-%d %H:%M' --pretty=format:'%h  %ad  %<(18,trunc)%an  %s'
  echo
  echo
  git shortlog -sne HEAD   # (HEAD is required when output is redirected)
} > git-log.txt
echo "Wrote git-log.txt"
