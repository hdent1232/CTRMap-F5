package ctrmap.tests;

import ctrmap.humaninterface.ScriptEditor;
import java.lang.reflect.Method;

/**
 * The script editor tells a subroutine's brace from a CASETBL's (F109).
 *
 * <p>WHY THIS SUITE EXISTS. Typing '{' asks which template to insert by scanning back from the
 * brace for "sub_", stopping at a tab. The case-sensitive scan tested its stop marker with
 * {@code &&} where {@code ||} belonged, so it never stopped: a CASETBL brace in any subroutine but
 * the first found the previous subroutine's name and got the SUBROUTINE template - "RETN(0)" and a
 * closing brace in the middle of a case table. Its case-insensitive twin took a substring past
 * the end of the text, so a brace typed near the end threw out of the document listener; and
 * neither scan ever looked at index 0.
 *
 * <p>ORDER: needs no dump and no display. The text is laid out as getDisassemblyTextForArea lays
 * it out.
 *
 * Usage: java ctrmap.tests.ScriptScanTest
 */
public class ScriptScanTest {

	static {
		Headless.require();   //the editor is built to type into its listener
	}

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String two = "sub_0000\n{\n\tNOP()\n}\n\nsub_0010\n{\n\tCASETBL(0x1)\n{";
		int brace = two.length() - 1;
		Object found = scan(two, brace - 1, "sub_", "\t", true);
		check(Integer.valueOf(-1).equals(found), "a CASETBL brace in the second subroutine stops at the tab, not at the first sub's name: " + found);
		int afterNewline = brace - 2;
		found = scan(two, afterNewline, "CASETBL", "\t", false);
		check(Integer.valueOf(two.indexOf("CASETBL")).equals(found), "control: the case-insensitive scan finds the CASETBL it is under: " + found);

		String own = "sub_0000\n{\n\tNOP()\n}\n\nsub_0010\n{";
		found = scan(own, own.length() - 2, "sub_", "\t", true);
		check(Integer.valueOf(own.indexOf("sub_0010")).equals(found), "a subroutine's own brace finds its name: " + found);
		String first = "sub_0000\n{";
		found = scan(first, first.length() - 2, "sub_", "\t", true);
		check(Integer.valueOf(0).equals(found), "and the first subroutine's, at index 0: " + found);

		String end = "sub_0000\n{\n\tCASE";
		found = scan(end, end.length() - 1, "CASETBL", "\t", false);
		check(Integer.valueOf(-1).equals(found), "a scan near the end of the text answers rather than throwing: " + found);

		//the brace typed, into the editor's own listener (the 2026-10-15 shared sweep, ScriptEditor 338, negate-if,
		//SURVIVED: the scan was asked, the listener that uses it never was)
		System.out.println("--- a brace typed after a subroutine's name opens the subroutine");
		String afterName = typeBrace("sub_0000");
		check(afterName.equals("sub_0000\n{\n\tRETN(0)\n}"),
				"typed on the name's line, the brace goes to a line of its own, with the subroutine's template: "
				+ afterName.replace("\n", "\\n").replace("\t", "\\t"));
		String ownLine = typeBrace("sub_0000\n");
		//the common way to type one, under its name - and the one the template was lost on: it went in at the brace + 2,
		//which counted a newline put before the brace, and there is none here (found by this check, 2026-10-15)
		check(ownLine.equals("sub_0000\n{\n\tRETN(0)\n}"),
				"typed at the start of a line, it stays where it is and gets the template too: "
				+ ownLine.replace("\n", "\\n").replace("\t", "\\t"));
		String table = typeBrace("sub_0000\n{\n\tCASETBL(0x1)\n");
		check(table.equals("sub_0000\n{\n\tCASETBL(0x1)\n{\n\t\t* => 0x0\n\t}"),
				"and a CASETBL's brace typed at the start of a line gets the case table's: "
				+ table.replace("\n", "\\n").replace("\t", "\\t"));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * {@code before} with a '{' typed at its end through the editor's own document listener, on the event thread as
	 * typing is, read back once the event thread has run what the brace queued and what that queued in turn. What the
	 * listener says about the made script's instructions is recorded, not printed: these checks read the text.
	 */
	static String typeBrace(String before) throws Exception {
		int at = before.length();
		ScriptEditor se = new ScriptEditor(new RecordingZoneSaver());
		se.loaded = true;
		se.editData = false;
		ScriptEditor.ScriptDocumentListener listener = new ScriptEditor.ScriptDocumentListener(se);
		ctrmap.formats.scripts.GFLPawnScript script = new ctrmap.formats.scripts.GFLPawnScript(HandedGameTest.amx());
		script.decompressThis();
		listener.setScript(script);
		javax.swing.text.PlainDocument doc = new javax.swing.text.PlainDocument();
		doc.insertString(0, before, null);
		doc.addDocumentListener(listener);
		ctrmap.Ui.record();
		try {
			javax.swing.SwingUtilities.invokeAndWait(() -> {
				try {
					doc.insertString(at, "{", null);
				} catch (javax.swing.text.BadLocationException ex) {
					throw new IllegalStateException(ex);
				}
			});
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
		} finally {
			ctrmap.Ui.stopRecording();
		}
		return doc.getText(0, doc.getLength());
	}

	/** The scan's answer, or what it threw. */
	static Object scan(String text, int pos, String toFind, String stopAt, boolean caseSensitive) throws Exception {
		Method m = ScriptEditor.ScriptDocumentListener.class.getDeclaredMethod("scanBackwardsFindUntil",
				String.class, int.class, String.class, String.class, boolean.class);
		m.setAccessible(true);
		try {
			return m.invoke(null, text, pos, toFind, stopAt, caseSensitive);
		} catch (java.lang.reflect.InvocationTargetException threw) {
			return threw.getCause();
		}
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
