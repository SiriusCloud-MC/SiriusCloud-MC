@echo off
rem Starts the SiriusCloud node. Its state lives in .\node\.
cd /d "%~dp0node"
java -Xms256M -Xmx512M -jar cloud-node.jar
