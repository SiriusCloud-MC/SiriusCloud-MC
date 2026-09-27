@echo off
rem Starts the SiriusCloud node. Its state lives in .\node\.
cd /d "%~dp0node"

rem Exit code 75: a cluster leader stepped down and asks to be started again
rem as a follower. Anything else - including a normal shutdown - ends here.
:start
java -Xms256M -Xmx512M -jar cloud-node.jar
if %ERRORLEVEL% EQU 75 (
    echo Restarting as a cluster follower...
    timeout /t 1 /nobreak >nul
    goto start
)
exit /b %ERRORLEVEL%
