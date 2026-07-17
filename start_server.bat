@echo off
echo Starting Parental Control Cloud Server...
cd /d "%~dp0cloud-server"
python app.py
pause
