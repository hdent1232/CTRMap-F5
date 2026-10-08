package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * The list of which extracted files are edits survives a crash, and is never missing (F034).
 *
 * <p>WHY THIS SUITE EXISTS. The list is the only record of which files in the workspace are the
 * user's unpacked edits: every window close deletes the extracted files it does not name. It was
 * written only at that close, so a crash forgot every edit made since the workspace opened and the
 * next close deleted them; and it was rewritten delete-then-write with the failure logged, so a
 * write that failed half way left no list at all, which reads as "no edits".
 *
 * <p>"The next open" here is {@link WorkspaceSession#persistedIn}, the read a restart does.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.EditListTest
 */
public class EditListTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			anEditIsKnownToTheNextOpenAtOnce();
			aWriteThatFailsLeavesTheListWholeAndSaysSo();
			aReadOnlyGameWritesNoList();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static WorkspaceSession session(String name) throws Exception {
		File root = Scratch.dir("ctrmap_editlist_" + name);
		new File(root, "workspace").mkdirs();
		WorkspaceSession s = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS);
		s.prepareDirectories();
		return s;
	}

	static File edit(WorkspaceSession s, String name) throws Exception {
		File f = new File(new File(s.workspaceDir(), "zone"), name);
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), new byte[]{1, 2, 3});
		return f;
	}

	static void anEditIsKnownToTheNextOpenAtOnce() throws Exception {
		System.out.println("--- an edit is on the list the next open reads as soon as it is made, not at close");
		WorkspaceSession s = session("through");
		File f = edit(s, "5");
		s.addPersist(f);
		List<String> next = WorkspaceSession.persistedIn(s.workspaceDir());
		check(next.contains(f.getAbsolutePath()),
				"with no close and no save, a restart would know " + f.getName() + " is an edit " + next);
		File g = edit(s, "6");
		s.addPersist(g);
		next = WorkspaceSession.persistedIn(s.workspaceDir());
		check(next.contains(f.getAbsolutePath()) && next.contains(g.getAbsolutePath()),
				"and the next edit joins it rather than replacing it " + next);
	}

	static void aWriteThatFailsLeavesTheListWholeAndSaysSo() throws Exception {
		System.out.println("--- a list that cannot be written is said, and the list on disk stays whole");
		WorkspaceSession s = session("fails");
		File f = edit(s, "5");
		s.addPersist(f);
		File list = WorkspaceSession.persistFile(s.workspaceDir());
		byte[] before = Files.readAllBytes(list.toPath());
		//the next write is made to fail: something stands where it writes the new list first
		File blocker = new File(list.getPath() + ".writing");
		check(blocker.mkdirs(), "the next write is made to fail");
		String said = null;
		try {
			s.addPersist(edit(s, "6"));
		} catch (IllegalStateException loud) {
			said = loud.getMessage();
		}
		check(said != null && said.contains("could not be written") && said.contains("6"),
				"the failure is thrown, naming the edit it could not record: " + said);
		check(list.isFile() && java.util.Arrays.equals(Files.readAllBytes(list.toPath()), before),
				"and the list on disk is the last whole one, never missing");
		check(WorkspaceSession.persistedIn(s.workspaceDir()).contains(f.getAbsolutePath()),
				"so the earlier edit is still known");
		blocker.delete();
	}

	static void aReadOnlyGameWritesNoList() throws Exception {
		System.out.println("--- a read-only game writes no list");
		WorkspaceSession s = session("readonly").readOnly();
		File f = edit(s, "5");
		s.addPersist(f);
		check(!WorkspaceSession.persistFile(s.workspaceDir()).exists(),
				"a read-only session records the edit in memory and writes nothing to the workspace");
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
