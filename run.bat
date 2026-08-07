@echo off
REM MyTix — compile and run (Windows cmd / PowerShell)
REM Usage:  .\run.bat
setlocal
cd /d "%~dp0"

set JAR=mysql-connector-java-8.0.29.jar
if not exist "%JAR%" (
  echo error: %JAR% not found
  exit /b 1
)

where javac >nul 2>&1
if errorlevel 1 (
  echo error: javac not found on PATH. Install a JDK and retry.
  exit /b 1
)

if not exist out mkdir out
echo Compiling...
javac -cp "%JAR%" -d out src\Main.java src\db\*.java src\util\*.java src\service\*.java
if errorlevel 1 exit /b 1

echo Starting MyTix...
java -cp "out;%JAR%" Main
endlocal
