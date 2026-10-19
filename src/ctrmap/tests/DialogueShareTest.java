package ctrmap.tests;

import ctrmap.MasterTable;
import ctrmap.TextForker;
import ctrmap.humaninterface.NPCEditForm;
import java.io.File;

/**
 * A talker's line is never rewritten in a story-text file another zone also shows (F233).
 *
 * <p>WHY THIS SUITE EXISTS. A cloned zone keeps its donor's story text. The dialogue editor
 * rewrote a talker's line in place whenever no OTHER talker in this zone's script used it, so
 * editing what an NPC says in a cloned town changed what the same NPC says in the retail town.
 * The area and the map each have a sharing guard over the master zone-header table; the story text
 * had none.
 *
 * <p>ORDER: needs no dump. The master table is three rows built here.
 *
 * Usage: java ctrmap.tests.DialogueShareTest [src root]
 */
public class DialogueShareTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		textSharersCountTheOtherZones();
		aSharedLineIsNotRewrittenInPlace();
		theEditorAsks(root);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Three zones' rows: zone 0 and its clone zone 1 show text 5, zone 2 shows text 7. */
	static MasterTable master() throws Exception {
		byte[] rows = new byte[3 * MasterTable.ROW];
		int[] text = {5, 5, 7};
		for (int z = 0; z < 3; z++) {
			MapResizeTest.putU16(rows, z * MasterTable.ROW + 0x06, text[z]);
		}
		return MasterTable.detached(rows, 3);
	}

	static void textSharersCountTheOtherZones() throws Exception {
		System.out.println("--- story text has a sharing count, like the area and the map (F233)");
		MasterTable m = master();
		check(TextForker.textSharers(m, 1) == 1, "a clone and its donor share their text: 1 other zone");
		check(TextForker.textSharers(m, 2) == 0, "control: a zone with its own text shares it with none");
		String said = "";
		try {
			TextForker.textSharers(m, 9);
		} catch (java.io.IOException refused) {
			said = refused.getMessage();
		}
		check(said.contains("zone 9"), "a zone the table has no row for is refused, naming it: " + said);
	}

	static void aSharedLineIsNotRewrittenInPlace() {
		System.out.println("--- a line is rewritten in place only when nothing else shows it (F233)");
		check(NPCEditForm.rewriteInPlace(1, 0), "one talker, text no other zone shows: rewritten in place");
		check(!NPCEditForm.rewriteInPlace(1, 1),
				"one talker in this zone, but the donor town shows the same file: a new line - it was rewritten, the retail NPC's too");
		check(!NPCEditForm.rewriteInPlace(2, 0), "two talkers on the line: a new line, as before");
		check(!NPCEditForm.rewriteInPlace(1, -1), "and when nobody can say who shares the text: a new line, never a guess");
	}

	static void theEditorAsks(File root) throws Exception {
		System.out.println("--- the dialogue editor asks both before it rewrites (F233)");
		String src = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/NPCEditForm.java")));
		String edit = PropEditFormGuardsTest.body(src, "public DialogueEdit editDialogue(");
		int asks = edit.indexOf("ctrmap.TextForker.textSharers(");
		int decides = edit.indexOf("rewriteInPlace(TalkerScriptWizard.countTalkersUsingLine(zone.s, msgLine), textSharers)");
		int rewrites = edit.indexOf("msg.setLine(msgLine, text)");
		check(asks >= 0 && decides > asks && rewrites > decides && edit.contains("textSharers = -1;"),
				"editDialogue counts the text's sharers, decides with both, and only then may rewrite; unknown is -1");

		System.out.println("--- a shared zone name is renamed for every sharer only when the user says so (F233, census L.8)");
		String zm = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/ZoneManager.java")));
		int refuse = zm.indexOf("} else if (!renameAllSharers) {");
		int thrown = zm.indexOf("throw new SharedNameOnly(sharers, r.oldName);");
		int inPlace = zm.indexOf("names.setLine(curParent, newName);\n\t\t\t\tnewParent = curParent;\n\t\t\t\tr.renamedSharers = true;");
		check(refuse >= 0 && thrown > refuse && inPlace > thrown,
				"with no free line the rename refuses unless told to rename every sharer");
		String mf = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/CtrmapMainframe.java")));
		int caught = mf.indexOf("catch (ZoneManager.SharedNameOnly shared)");
		int asked = mf.indexOf("Ui.confirm(", caught);
		//THROUGH THE KEPT SEAM (F209c): the window says "every sharer" to WorkspaceOps.rename, which says it on
		int told = mf.indexOf("WorkspaceOps.rename(game, idx, name, true,");
		String ops = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/WorkspaceOps.java")));
		check(caught >= 0 && asked > caught && told > asked && mf.indexOf("rename(game, idx, name, true", told + "WorkspaceOps.rename(".length()) < 0
				&& ops.contains("everyZone ? ZoneManager.renameZone(ws, idx, name, true)"),
				"and the Rename dialog asks, by name and count, before it says so");
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
