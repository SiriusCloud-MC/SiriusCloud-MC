@echo off
rem Starts the SiriusCloud node. Its state lives in .\node\.
cd /d "%~dp0node"

rem Tells the node it was started from here: this script installs downloaded
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
java -Xms256M -Xmx512M -jar cloud-node.jar
if %ERRORLEVEL% EQU 75 (
    echo Restarting...
    timeout /t 1 /nobreak >nul
    goto start
)
exit /b %ERRORLEVEL%
