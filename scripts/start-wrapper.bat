@echo off
rem Starts the SiriusCloud wrapper. Its state lives in .\wrapper\.
cd /d "%~dp0wrapper"

rem Tells the wrapper it was started from here: this script installs downloaded
rem updates, and starts it again when it exits with code 75 - to install one,
rem or, for a node, to rejoin its cluster as a follower. Any other exit,
rem including a normal shutdown, ends here.
set SIRIUSCLOUD_LAUNCHER=1

:start
if exist "local\updates\ready\" (
    echo Installing the downloaded update...
    xcopy /E /Y /Q "local\updates\ready\*" "." >nul
    rmdir /S /Q "local\updates\ready"
    del /Q "local\updates\ready.version" 2>nul
)
java -Xms128M -Xmx256M -jar cloud-wrapper.jar
if %ERRORLEVEL% EQU 75 (
    echo Restarting...
    timeout /t 1 /nobreak >nul
    goto start
)
exit /b %ERRORLEVEL%
