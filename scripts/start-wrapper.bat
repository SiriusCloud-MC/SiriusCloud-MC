@echo off
rem Starts the SiriusCloud wrapper. Its state lives in .\wrapper\.
cd /d "%~dp0wrapper"
java -Xms128M -Xmx256M -jar cloud-wrapper.jar
