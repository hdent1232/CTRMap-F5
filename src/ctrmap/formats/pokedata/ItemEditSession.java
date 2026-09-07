package ctrmap.formats.pokedata;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * What the item editor edits, with no window attached: the record table, the
 * two text lists, and which slots are free. The dialog is a view over this,
 * and every write the item editor makes to a game goes through {@link #save}.
 *
 * <p>Why it is separate from the dialog: the one thing the item editor does
 * that can hurt a game is a save, and inside a Swing listener it could only
 * be exercised with a screen. Here a suite hands it a record and two strings
 * and reads back what it chose to write - which is also the contract worth
 * stating out loud: <b>a save touches only the parts that differ</b>. The
 * record is a 36-byte in-place poke into the archive; a text line is a
 * rewrite of that whole text file into the workspace, which the next pack
 * ships. Writing a line that did not change would stage a 776-line file for
 * nothing and make every pack after it carry the text archive.
 */
public final class ItemEditSession {

	/** The parts of an item a save may write. */
	public enum Changed {
		RECORD, NAME, DESCRIPTION
	}

	public final ItemTable table;
	/** The name list, kept in step with what has been saved. Read it; the session updates it. */
	public final List<String> names;
	/** The description list, likewise. */
	public final List<String> descs;
	/** The ids that can take a new item; see {@link ItemTable#freeSlots}. */
	public final List<Integer> free;

	public ItemEditSession(ItemTable table, List<String> names, List<String> descs) {
		this.table = table;
		this.names = names;
		this.descs = descs;
		this.free = table.freeSlots(names);
	}

	/**
	 * The live workspace's items, or null when this game has no VERIFIED item
	 * table - the caller has to say that to the user; see
	 * {@link ItemTable#openWorkspace}.
	 */
	public static ItemEditSession openWorkspace() throws IOException {
		ItemTable t = ItemTable.openWorkspace();
		if (t == null) {
			return null;
		}
		return new ItemEditSession(t, ItemText.read(ItemText.Which.NAMES),
				ItemText.read(ItemText.Which.DESCRIPTIONS));
	}

	public int count() {
		return table.count();
	}

	/** The record as the archive holds it now. */
	public byte[] record(int id) {
		return table.raw(id);
	}

	/** The name, or "" past the end of the list. */
	public String name(int id) {
		return id >= 0 && id < names.size() ? names.get(id) : "";
	}

	/** The description, or "" past the end of the list. */
	public String description(int id) {
		return id >= 0 && id < descs.size() ? descs.get(id) : "";
	}

	public boolean isFree(int id) {
		return free.contains(id);
	}

	/** The record as it was before the first edit in this workspace, or null when no copy was taken. */
	public byte[] baselineRecord(int id) {
		return ItemTable.baselineRecord(id);
	}

	/**
	 * Writes what differs from what is stored, and nothing else, and says
	 * which parts those were. The record goes first; if it is refused, no
	 * text is written.
	 *
	 * @return the parts written; empty when nothing differed
	 */
	public EnumSet<Changed> save(int id, byte[] rec, String name, String desc) throws IOException {
		EnumSet<Changed> changed = EnumSet.noneOf(Changed.class);
		if (!Arrays.equals(rec, table.raw(id))) {
			table.writeRecord(id, rec);
			changed.add(Changed.RECORD);
		}
		if (id < names.size() && !name.equals(names.get(id))) {
			ItemText.setLine(ItemText.Which.NAMES, id, name);
			names.set(id, name);
			changed.add(Changed.NAME);
		}
		if (id < descs.size() && !desc.equals(descs.get(id))) {
			ItemText.setLine(ItemText.Which.DESCRIPTIONS, id, desc);
			descs.set(id, desc);
			changed.add(Changed.DESCRIPTION);
		}
		return changed;
	}
}
