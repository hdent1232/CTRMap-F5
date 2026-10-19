package ctrmap;

import ctrmap.formats.GameFiles;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The records of one archive of fixed-shape records - the battle facility's set pools and class
 * lists - as the workspace holds them, for an editor that shows them as rows (F102).
 *
 * <p>A RECORD THAT CANNOT BE READ IS SAID SO, NEVER STOOD IN FOR. Both facility editors caught any
 * failure to read or parse a record and put a blank one in its place - species 0, which the pool
 * guard then offered as free authoring space, or class 0 with no sets - and their Save rewrote
 * every record, so the next Save replaced a retail record nobody could read with an empty one, and
 * every Save marked the whole archive edited for the next pack. Here an unreadable record keeps a
 * placeholder row, is {@link #unreadable named with its reason}, and is never written; a save
 * writes only the records whose bytes changed.
 *
 * <p>Not: {@link ctrmap.formats.recordschema.RecordSchema} - it describes N fixed-stride records
 * laid back to back in ONE blob and where their fields are; these are one record per archive
 * entry, each its own workspace file, and what was missing is how they are read, refused and
 * written, not how they are laid out. Nor {@link ctrmap.formats.maison.MaisonPoolGuard} - it
 * decides which slots are free from the pristine snapshot and reads nothing of the workspace.
 */
public final class RecordPool {

	public final ArchiveType type;
	private final GameFiles files;
	/** The bytes each record held when it was read, or last saved; null for one that could not be read. */
	private final byte[][] held;
	/** Why each record could not be read; null for one that was. */
	private final String[] unreadable;

	private RecordPool(GameFiles files, ArchiveType type, int count) {
		this.files = files;
		this.type = type;
		this.held = new byte[count][];
		this.unreadable = new String[count];
	}

	/**
	 * Reads the {@code rows.length} records of {@code type} into {@code rows}: each through
	 * {@code parse}, or - when its file cannot be read or does not parse - {@code placeholder},
	 * marked unreadable.
	 */
	public static <T> RecordPool load(GameFiles files, ArchiveType type, T[] rows, Function<byte[], T> parse,
			Supplier<T> placeholder) {
		RecordPool pool = new RecordPool(files, type, rows.length);
		for (int i = 0; i < rows.length; i++) {
			try {
				File f = files.staged(type, i);
				if (f == null) {
					throw new IOException("the game holds no record " + i + " to stage");
				}
				byte[] rec = Files.readAllBytes(f.toPath());
				rows[i] = parse.apply(rec);
				pool.held[i] = rec;
			} catch (IOException | RuntimeException cannot) {
				rows[i] = placeholder.get();
				pool.unreadable[i] = ctrmap.util.Bytes.reason(cannot);
			}
		}
		return pool;
	}

	/** Whether record {@code i} could not be read. */
	public boolean unreadable(int i) {
		return unreadable[i] != null;
	}

	/** Why record {@code i} could not be read; null when it was. */
	public String why(int i) {
		return unreadable[i];
	}

	/** How many records could not be read. */
	@ctrmap.util.ForSuites("SEAM: a suite counts the records the pool could not read, to see each is said rather than skipped")
	public int unreadableCount() {
		int n = 0;
		for (String u : unreadable) {
			n += u != null ? 1 : 0;
		}
		return n;
	}

	/**
	 * Writes the records whose bytes changed - never one that could not be read - and answers which.
	 * Every write is announced to the game first, so a {@link WorkspaceUnit} the caller has open puts
	 * them all back if one fails; what this pool takes as written is updated only once all are.
	 */
	public <T> List<Integer> save(T[] rows, Function<T, byte[]> write) throws IOException {
		List<Integer> changed = new ArrayList<>();
		List<byte[]> bytes = new ArrayList<>();
		for (int i = 0; i < rows.length; i++) {
			if (unreadable[i] != null) {
				continue;   //it holds bytes nobody could read: a placeholder written over them would replace them
			}
			byte[] now = write.apply(rows[i]);
			if (!Arrays.equals(now, held[i])) {
				changed.add(i);
				bytes.add(now);
			}
		}
		for (int k = 0; k < changed.size(); k++) {
			File f = files.staged(type, changed.get(k));
			if (f == null) {
				throw new IOException("record " + changed.get(k) + " of " + type + " has no file to write to");
			}
			files.aboutToWrite(f);
			ctrmap.util.Bytes.replaceFile(f, bytes.get(k));
			files.edited(f);
		}
		for (int k = 0; k < changed.size(); k++) {
			held[changed.get(k)] = bytes.get(k);
		}
		return changed;
	}
}
