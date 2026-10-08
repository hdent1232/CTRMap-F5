package ctrmap.util;

/**
 * Reading a number out of text a person sees (F113): a table cell, a dropdown item. The NPC and
 * trigger forms each kept a reader of their own beside the item picker's, two of the three
 * answering a different sentinel for the same string. It lives here, below every package that
 * uses it: placed in the form helpers, it gave the picker package an edge up into the
 * application's, and the application's package cycle grew from 7 to 8.
 *
 * <p>Not: {@link Bytes} - that reads and writes bytes; and not
 * {@code ctrmap.formats.h3d.StringUtils}, which reads strings out of a binary file. This reads
 * what a string says.
 */
public final class Text {

	private Text() {
	}

	/**
	 * The first integer in a string ("25  Pikachu" -> 25, "12 (talker: line 3)" -> 12), or -1 when it
	 * holds none.
	 */
	public static int parseLeadingInt(String s) {
		if (s == null) {
			return -1;
		}
		int i = 0, n = s.length();
		while (i < n && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-') {
			i++;
		}
		int j = i;
		if (j < n && s.charAt(j) == '-') {
			j++;
		}
		while (j < n && Character.isDigit(s.charAt(j))) {
			j++;
		}
		try {
			return Integer.parseInt(s.substring(i, j));
		} catch (RuntimeException ex) {
			return -1;
		}
	}
}
