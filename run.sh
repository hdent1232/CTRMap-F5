#!/bin/sh
# CTRMap-F5 launcher (macOS / Linux). See run.bat for the Windows equivalent.
cd "$(dirname "$0")" || exit 1

# A staged update is applied here, before the JVM opens anything: a running Java
# process holds its own jar open and could not replace it. The applying JVM runs
# out of the staged copy inside the hidden .ctrmap-update folder, so the
# installed jar is free to be overwritten - and there is never a second copy of
# CTRMap sitting next to the first one.
if [ -f ".ctrmap-update/READY" ] && [ -f ".ctrmap-update/staged/CTRMap-F5.jar" ]; then
	java -cp ".ctrmap-update/staged/CTRMap-F5.jar" ctrmap.update.Updater --apply "$PWD"
fi

# An installed release runs from the jar; a source checkout runs from build/classes.
if [ -f "CTRMap-F5.jar" ]; then
	exec java -Xmx1024m -cp "CTRMap-F5.jar:lib/jogl-all.jar:lib/gluegen-rt.jar" ctrmap.CtrmapMainframe "$@"
else
	exec java -Xmx1024m -cp "build/classes:lib/jogl-all.jar:lib/gluegen-rt.jar" ctrmap.CtrmapMainframe "$@"
fi
