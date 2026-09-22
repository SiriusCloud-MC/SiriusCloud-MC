@rem
@rem Gradle wrapper launcher (Windows).
@rem
@echo off
setlocal

set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo gradle-wrapper.jar is missing from gradle\wrapper\.
    echo.
    echo Fetch it once with either:
    echo   gradle wrapper --gradle-version 8.12
    echo   curl -Lo gradle\wrapper\gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v8.12.0/gradle/wrapper/gradle-wrapper.jar
    exit /b 1
)

if defined JAVA_HOME (
    set JAVACMD="%JAVA_HOME%\bin\java.exe"
) else (
    set JAVACMD=java
)

%JAVACMD% %JAVA_OPTS% -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
