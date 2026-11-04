@echo off
setlocal
set ROOT=%~dp0..

set MODE=%1
if "%MODE%"=="" set MODE=800

if /i "%MODE%"=="480" (
    echo Starting 480x480 simulator...
    cd /d "%ROOT%\Super_VESC_Display\lvgl-simulator\buildsim480\bin"
    start "" "%ROOT%\Super_VESC_Display\lvgl-simulator\buildsim480\bin\simulator.exe"
) else (
    echo Starting 800x480 simulator...
    cd /d "%ROOT%\Super_VESC_Display\lvgl-simulator\build\bin"
    start "" "%ROOT%\Super_VESC_Display\lvgl-simulator\build\bin\simulator.exe"
)
