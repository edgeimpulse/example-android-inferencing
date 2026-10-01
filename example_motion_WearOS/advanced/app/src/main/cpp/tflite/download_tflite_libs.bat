@echo off
setlocal enabledelayedexpansion

:: Set script path
set SCRIPTPATH=%~dp0

:: Create directory for Android 64-bit TFLite libraries
if not exist "%SCRIPTPATH%\android64" mkdir "%SCRIPTPATH%\android64"

:: Change to the target directory
cd /d "%SCRIPTPATH%\android64"

:: Check if TensorFlow Lite static library exists
if not exist "libtensorflow-lite.a" (
    echo Downloading TensorFlow Lite libraries...
    powershell -Command "& {Invoke-WebRequest -Uri 'https://cdn.edgeimpulse.com/build-system/tflite/android64/tflite-android64.zip' -OutFile 'tflite-android64.zip'}"
    
    echo Extracting files...
    powershell -Command "& {Expand-Archive -Path 'tflite-android64.zip' -DestinationPath '%SCRIPTPATH%\android64' -Force}"
    
    del tflite-android64.zip
)

echo TensorFlow Lite libraries downloaded successfully.
endlocal
pause

