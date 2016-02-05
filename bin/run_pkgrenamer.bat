@echo off
setlocal

cd /d %~dp0

set java_exe=
call libs\find_java.bat
if not defined java_exe goto :EOF

%java_exe% -cp libs\pkgrenamer_apktool.jar pkgrenamer.Main
