@echo off
chcp 65001 >nul
rem Installe et lance Mini-Jeux Bluetooth sur le téléphone branché en USB (débogage USB activé).
cd /d "%~dp0"
set ADB=adb
where adb >nul 2>nul || set ADB=C:\Users\admin\BuildTools-Temp\sdk\platform-tools\adb.exe
if not exist "%ADB%" if not "%ADB%"=="adb" goto noadb

"%ADB%" get-state >nul 2>nul || goto nophone
echo Installation sur le téléphone...
"%ADB%" install -r "MiniJeuxBluetooth.apk" || goto fin
"%ADB%" shell monkey -p fr.poiscail.minijeux -c android.intent.category.LAUNCHER 1 >nul
echo C'est lancé sur le téléphone !
goto fin

:nophone
echo Aucun téléphone détecté.
echo Branche-le en USB, active le débogage USB, accepte l'autorisation, puis relance ce fichier.
goto fin

:noadb
echo Outil adb introuvable. Copie MiniJeuxBluetooth.apk sur le téléphone et ouvre-le.

:fin
pause
