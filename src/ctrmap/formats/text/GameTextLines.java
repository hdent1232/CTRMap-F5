package ctrmap.formats.text;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * A game text file's lines, or why they cannot be read - the one reader (census M.55).
 *
 * <p>WHY THIS EXISTS. The Maison, class-list, trainer, encounter and lighting editors each read a text file of
 * names through a private copy that turned any failure into no lines at all, so species, item, move, class and
 * place names became bare numbers and nobody was told - and making that loud had to be done in every copy. Here
 * a file either answers its lines or says which file could not be read and why; an editor that can go on showing
 * numbers is handed the reason to show.
 *
 * <p>Not: {@link GFMessageFile} - the codec of one file's bytes, which knows no file number and nothing of where a
 * file is read from.
 */
public final class GameTextLines {

	private GameTextLines() {
	}

	/**
	 * The lines of text file {@code entry}, whose bytes are {@code raw}.
	 *
	 * @throws IllegalStateException naming the file and why, when it is missing or does not decode
	 */
	public static String[] of(byte[] raw, int entry) {
		if (raw == null) {
			throw new IllegalStateException("text file " + entry + " could not be read: the game does not hold it");
		}
		try {
			return GFMessageFile.getStrings(raw).toArray(new String[0]);
		} catch (RuntimeException undecoded) {
			throw new IllegalStateException("text file " + entry + " could not be read: "
					+ ctrmap.util.Bytes.reason(undecoded), undecoded);
		}
	}

	/**
	 * The same, from whatever {@code raw} reads - or no lines, with why said into {@code unread}, for an editor that
	 * shows numbers where names are missing and tells the user so.
	 */
	public static String[] orSaid(Callable<byte[]> raw, int entry, List<String> unread) {
		try {
			return of(raw.call(), entry);
		} catch (IllegalStateException said) {
			unread.add(ctrmap.util.Bytes.reason(said));
		} catch (Exception unreadable) {
			unread.add("text file " + entry + " could not be read: " + ctrmap.util.Bytes.reason(unreadable));
		}
		return new String[0];
	}
}
