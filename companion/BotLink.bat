@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>&1 && (set "PY=py -3") || (set "PY=python")
%PY% -m pip install -q -r "%~dp0requirements.txt"
if errorlevel 1 exit /b 1
%PY% "%~dp0botlink.py" %*
