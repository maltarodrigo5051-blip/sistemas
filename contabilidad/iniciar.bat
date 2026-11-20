@echo off
rem Doble clic para abrir el sistema contable (un .ps1 con doble clic solo se abre en el Bloc de notas).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
pause
