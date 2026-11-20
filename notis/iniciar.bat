@echo off
rem Abre Notis: deja esta ventana abierta mientras quieras recibir avisos.
cd /d "%~dp0"
start "" http://127.0.0.1:5600
python -I server.py 5600
pause
