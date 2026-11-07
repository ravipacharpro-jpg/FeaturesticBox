@echo off
echo Building FeaturesticBox Release AAR...
call gradlew.bat :FeaturesticBox:assembleRelease --no-daemon --stacktrace
echo Done! AAR location: FeaturesticBox\build\outputs\aar\FeaturesticBox-release.aar
pause
