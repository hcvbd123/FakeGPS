@if "%DEBUG%" == "" @echo off
@rem Gradle wrapper for Windows
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
set APP_BASE_NAME=%~n0
set CLASSPATH=%DIRNAME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_HOME%/bin/java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
