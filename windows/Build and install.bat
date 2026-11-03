@echo off
cd /d "%~dp0"
call gradlew.bat createDistributable --console=plain || pause
python "_dev_tools\install.py"
