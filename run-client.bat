@echo off
setlocal
set MAIN=com.example.demo.client.ClientApp

rem Use Maven if available to run the client in a new window
where mvn >nul 2>&1
if %ERRORLEVEL%==0 (
  start "Client" cmd /k "mvn -Dexec.mainClass=\"%MAIN%\" exec:java -DskipTests"
  goto :eof
)

rem If a packaged JAR exists, run it in a new window
if exist target\*.jar (
  for %%f in (target\*.jar) do set JAR=%%~f
  start "Client" cmd /k "java -cp \"%%JAR%%;target\\dependency\\*\" %MAIN%"
  goto :eof
)

echo Neither Maven found nor packaged JAR present.
echo Please install Maven or run 'mvn package' to build the project, then run this script again.
pause
