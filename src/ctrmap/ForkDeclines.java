package ctrmap;

import ctrmap.util.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The zones whose private-map offer the user declined, kept IN THE WORKSPACE FOLDER and keyed by
 * the game folder they were declined against (F253).
 *
 * <p>They were kept machine-wide in {@code java.util.prefs}, under the hashCode of the workspace
 * path. Pointing the same workspace at another game kept the key, so game A's declined zone indices
 * silenced the offer on game B's unrelated zones at those indices - and an edit there changed every
 * zone sharing the map without the question asked. A moved or re-cased path lost its declines, and
 * two paths with one hash shared theirs. Here they live with the workspace they belong to, one set
 * per game folder, and a clean that forgets the workspace's edits forgets these with them.
 *
 * <p>Not: {@code WorkspaceSession}'s persist list - that names edited FILES and is the edit's own
 * record; a decline is an answer about a zone, and must not make a file look edited.
 */
public final class ForkDeclines {

	/** The file, beside the persist list in the workspace folder. */
	public static final String FILE = "ctrmap_fork_declined.properties";

	private ForkDeclines() {
	}

	public static File file(File workspaceDir) {
		return new File(workspaceDir, FILE);
	}

	/** The key a game folder's declines are kept under: its absolute, normalised path. */
	static String key(String gameDir) {
		return new File(gameDir).getAbsoluteFile().toPath().normalize().toString();
	}

	/**
	 * The zones declined in {@code workspaceDir} against {@code gameDir}. Empty when there are none
	 * or when either folder is unknown. A token that is not a zone number was written by hand and
	 * is skipped alone.
	 *
	 * @throws IOException when the file is there and cannot be read: "could not read" is not "none
	 * declined" - the caller asks again, which is the safe direction, and says why (F016's sweep:
	 * this answered an empty set and nobody learnt the file was broken)
	 */
	public static Set<Integer> read(File workspaceDir, String gameDir) throws IOException {
		Set<Integer> out = new TreeSet<>();
		if (workspaceDir == null || gameDir == null || gameDir.trim().isEmpty()) {
			return out;
		}
		Properties held = load(file(workspaceDir));
		for (String tok : held.getProperty(key(gameDir), "").split(",")) {
			if (tok.trim().matches("\\d{1,9}")) {
				out.add(Integer.parseInt(tok.trim()));
			}
		}
		return out;
	}

	/**
	 * Keeps {@code zones} as the declines against {@code gameDir}, leaving every other game's
	 * alone. Written whole to a temporary file and moved over the old, so a failed write leaves
	 * the last good set. Throws when it cannot be kept - the caller says so.
	 */
	@ctrmap.util.NotAnEdit("it records which zones' private-map offers the user declined, so the question is not"
		+ " asked again: the user's answers to a question, kept beside the workspace, not the game's data")
	public static void write(File workspaceDir, String gameDir, Set<Integer> zones) throws IOException {
		if (workspaceDir == null || gameDir == null || gameDir.trim().isEmpty()) {
			throw new IOException("no workspace or no game folder to keep the declines against");
		}
		File target = file(workspaceDir);
		Properties held = load(target);   //throws when it cannot be read: it is not written over then
		StringBuilder csv = new StringBuilder();
		for (int z : new TreeSet<>(zones)) {
			if (csv.length() > 0) {
				csv.append(',');
			}
			csv.append(z);
		}
		if (csv.length() == 0) {
			held.remove(key(gameDir));
		} else {
			held.setProperty(key(gameDir), csv.toString());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		held.store(out, "Zones whose private-map offer was declined, per game folder - written by CTRMap");
		//through the one staged writer the program has, not a second temp-and-move of its own;
		//staged at one fixed name, since the panel is its one writer
		Bytes.replaceFile(target, new File(workspaceDir, FILE + ".tmp"), out.toByteArray());
	}

	/** Forgets every decline in the workspace - a clean that forgets its edits forgets these. */
	public static void forget(File workspaceDir) throws IOException {
		if (workspaceDir != null) {
			Bytes.removeFile(file(workspaceDir));
		}
	}

	/** The file's entries; empty when there is no file. Throws when it is there and cannot be read. */
	private static Properties load(File f) throws IOException {
		Properties p = new Properties();
		if (!f.exists()) {
			return p;
		}
		try (InputStream in = Files.newInputStream(f.toPath())) {
			p.load(in);
			return p;
		} catch (IllegalArgumentException malformed) {
			throw new IOException(f.getAbsolutePath() + " is not a declines file: " + Bytes.reason(malformed), malformed);
		}
	}
}
