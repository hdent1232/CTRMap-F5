package ctrmap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Files written OUT of the program, to a place the user named for them: an export (F209d).
 *
 * <p>An export reads what the user made and writes a copy somewhere else; nothing it was made from changes,
 * so there is nothing for an undo to put back. Each export wrote its file in the window's own code - a
 * FileWriter in the OBJ export, two Files.copy calls in the container builder - where the undo rule could not
 * tell one from a save (3 entries, 2026-10-08). They write here, the one place that says so.
 *
 * <p>Not: {@link ctrmap.util.Bytes#replaceFile} - the write it uses, which says nothing about what is
 * written; nor {@link WorkspaceUnit#write}, which writes a workspace file its unit can put back.
 */
public final class Exports {

	private Exports() {
	}

	/** {@code data} as the file {@code to}, whole or not at all. */
	@ctrmap.util.NotAnEdit("it writes what the user exported to the file they named for it, outside what is"
			+ " edited: what it was made from is read, not changed")
	public static void write(File to, byte[] data) throws IOException {
		ctrmap.util.Bytes.replaceFile(to, data);
	}

	/**
	 * The zone-limit patch {@code ips} saved into the code.ips the user named, the patches already in it kept (F152).
	 * Census H.2's Make zone-limit patch is an export like the others here: made from the zone count and the
	 * executable, made again from the same zones whenever it is asked for.
	 */
	@ctrmap.util.NotAnEdit("it writes the zone-limit patch, made from the zone count and the executable, into the code.ips"
			+ " the user named for it; the patches already there are kept, and the patch is made again from the same zones")
	public static void zoneLimitPatch(File codeIps, byte[] ips) throws IOException {
		ctrmap.formats.codepatch.CodeIps.save(codeIps, ips, ctrmap.formats.codepatch.ZoneLimitPatch.owned(),
				WorkspaceUnit::touching);
	}

	/** {@code from} copied over {@code to}. */
	@ctrmap.util.NotAnEdit("it copies a file the user exported to the place they named for it: the file it copies"
			+ " is read, not changed")
	public static void copy(File from, File to) throws IOException {
		Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
}
