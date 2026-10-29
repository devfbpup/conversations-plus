@echo off
title Conversations+ updater
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" %*
pause
