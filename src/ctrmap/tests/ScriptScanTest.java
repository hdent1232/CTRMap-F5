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

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
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
