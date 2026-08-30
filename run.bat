@echo off
setlocal
cd /d "%~dp0"

rem A staged update is applied HERE, before the JVM opens anything, because a
rem running Java process holds its own jar open and could not replace it. The
rem applying JVM runs out of the staged copy inside the hidden .ctrmap-update
rem folder, so the installed jar is free to be overwritten. This is why there is
rem never a second copy of CTRMap sitting next to the first one.
if exist ".ctrmap-update\READY" (
  if exist ".ctrmap-update\staged\CTRMap-F5.jar" (
    java -cp ".ctrmap-update\staged\CTRMap-F5.jar" ctrmap.update.Updater --apply "%CD%"
  )
)

rem An installed release runs from the jar; a source checkout runs from build\classes.
if exist "CTRMap-F5.jar" (
  java -Xmx1024m -cp "CTRMap-F5.jar;lib\jogl-all.jar;lib\gluegen-rt.jar" ctrmap.CtrmapMainframe %*
) else (
  java -Xmx1024m -cp "build\classes;lib\jogl-all.jar;lib\gluegen-rt.jar" ctrmap.CtrmapMainframe %*
)
endlocal
