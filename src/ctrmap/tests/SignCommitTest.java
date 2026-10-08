package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.NpcTemplates;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The map painter's sign wiring writes its text, its furniture and its script together or not
 * at all (F088).
 *
 * <p>WHY THIS SUITE EXISTS. Wiring a placed sign adds a line to the zone's story text, a furniture
 * record pointing at a new script case, and the case. They were three writes in a row, the text's
 * a raw one added straight to the pack list, so a script that failed to write left furniture
 * pointing at a case that does not exist and a line nothing shows - already queued for the next
 * pack, with the zone freezing in game when the sign is read.
 *
 * <p>ORDER: needs no dump and no display. The asking is a dialog per sign; the commit is not, and
 * that is what this drives, in a made game.
 *
 * Usage: java ctrmap.tests.SignCommitTest
 */
public class SignCommitTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			run();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void run() throws Exception {
		System.out.println("--- a sign wiring that fails at its script leaves everything as it was (F088)");
		WorkspaceSession ws = MapResizeTest.game("signs");
		byte[] amx = HandedGameTest.amx();
		byte[] entities = new byte[12 + amx.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(amx, 0, entities, 12, amx.length);
		File zoFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		Files.write(zoFile.toPath(), MapResizeTest.container(0x5a4f, new byte[ctrmap.MasterTable.ROW], entities, amx));
		File story = new File(ws.getExtractionDirectory(ArchiveType.STORYTEXT), "7");
		story.getParentFile().mkdirs();
		Files.write(story.toPath(), GFMessageFile.write(Arrays.asList("already here")));

		Method commit = ctrmap.humaninterface.TilePainterForm.class.getDeclaredMethod("commitSigns", ZO.class, File.class,
				GFMessageFile.class, ZoneEntities.class, GFLPawnScript.class);
		commit.setAccessible(true);

		byte[] zoBefore = Files.readAllBytes(zoFile.toPath());
		byte[] storyBefore = Files.readAllBytes(story.toPath());
		List<String> editsBefore = new ArrayList<>(ws.persistPaths());
		ZO zo = new ZO(zoFile, ws);
		GFMessageFile msg = new GFMessageFile(Files.readAllBytes(story.toPath()));
		ZoneEntities ent = new ZoneEntities(zo.getFile(1));
		msg.addLine("a sign");
		ent.furniture.add(NpcTemplates.makeSignFurniture(NpcScriptGateTest.ORAS, 1, 3, 4));
		GFLPawnScript failing = new GFLPawnScript(zo.getFile(2)) {
			@Override
			public byte[] getScriptBytes() {
				throw new IllegalStateException("the disk filled");
			}
		};
		Throwable thrown = null;
		try {
			commit.invoke(null, zo, story, msg, ent, failing);
		} catch (InvocationTargetException ex) {
			thrown = ex.getCause();
		}
		check(thrown != null && String.valueOf(thrown.getMessage()).contains("could not write the sign script"),
				"control: the commit fails at the script: " + thrown);
		check(Arrays.equals(storyBefore, Files.readAllBytes(story.toPath())), "the story text is as it was - no line nothing shows");
		check(Arrays.equals(zoBefore, Files.readAllBytes(zoFile.toPath())), "the zone is as it was - no furniture pointing at a missing case");
		check(editsBefore.equals(ws.persistPaths()), "and nothing was added to the pack list: " + ws.persistPaths());

		System.out.println("--- and one that does not fail writes all three");
		ZO zo2 = new ZO(zoFile, ws);
		GFMessageFile msg2 = new GFMessageFile(Files.readAllBytes(story.toPath()));
		ZoneEntities ent2 = new ZoneEntities(zo2.getFile(1));
		GFLPawnScript s2 = new GFLPawnScript(zo2.getFile(2));
		s2.decompressThis();
		msg2.addLine("a sign");
		ent2.furniture.add(NpcTemplates.makeSignFurniture(NpcScriptGateTest.ORAS, 1, 3, 4));
		commit.invoke(null, zo2, story, msg2, ent2, s2);
		ZO back = new ZO(zoFile, ws);
		check(new GFMessageFile(Files.readAllBytes(story.toPath())).getLines().equals(Arrays.asList("already here", "a sign"))
				&& new ZoneEntities(back.getFile(1)).furniture.size() == 1,
				"the line and the furniture are written");
		check(ws.persistPaths().size() == editsBefore.size() + 2, "and the story file and the zone are on the pack list: "
				+ ws.persistPaths());
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
