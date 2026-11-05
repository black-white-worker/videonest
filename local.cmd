@echo off
REM Local one-command entry point for videonest.
REM   local.cmd                 -> local deploy: build jar/dist, build images, run containers (port 8082)
REM   local.cmd dev             -> start middleware + backend(8080) + vite(5173) for coding
REM   local.cmd infra           -> start middleware only
REM   local.cmd status|logs|down|clean
REM   local.cmd -SkipBuild      -> local deploy without rebuilding jar/dist
setlocal

set "FIRST=%~1"

if "%FIRST%"=="" goto run_app
if "%FIRST:~0,1%"=="-" goto run_app

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local.ps1" -Action %*
exit /b %ERRORLEVEL%

:run_app
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local.ps1" -Action app %*
exit /b %ERRORLEVEL%
