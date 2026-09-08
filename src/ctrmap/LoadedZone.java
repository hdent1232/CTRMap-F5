package ctrmap;

import ctrmap.formats.zone.Zone;

/**
 * The ONE owner of the loaded-zone state: the zone table the Zone tab built
 * from the archive, the zone that is open, and the index it is open at.
 *
 * <p>These three used to be public fields of {@code ZoneLoadingPanel}, written
 * from inside the panel, from the map view and from three suites, and read
 * through the main window's static by fourteen classes - none of which could
 * be handed a different zone in a test, because there was only ever the one
 * panel to read. Now the window makes ONE of these and hands it to the panel
 * and to every reader; a suite hands each reader its own and reads two
 * answers. Every write is a method that says what happened; the table is
 * never handed out, so {@code zones[2] = x} from outside cannot happen.
 *
 * <p>What the writes mean, pinned by ZoneLoadingStateTest as the panel
 * behaves: {@link #table} is a rebuild, which lets go of the open zone (it
 * belonged to the old table) and does NOT touch the index; {@link #release}
 * lets go of the open zone alone (the map view opening a bare GR file, the
 * start of a rebuild); {@link #close} is the panel's unload, nothing open and
 * the index -1; {@link #open(int, Zone)} plants a zone at an index, or an
 * index with no zone yet, which is what the panel is between loading a zone
 * and recording where it came from. So {@link #index()} can be a table
 * position while {@link #open()} is null - that is the state after a rebuild,
 * and readers that need both check both, as they always did.
 *
 * <p>Plain fields, as the panel's were: the panel writes from its workers and
 * the window reads on the event thread, and this class changes nothing about
 * that - it gives the state an owner, not a lock.
 */
public final class LoadedZone {

	private Zone[] table;
	private Zone open;
	private int index = -1;

	/** How many slots the zone table holds; 0 before any rebuild. */
	public int count() {
		return table == null ? 0 : table.length;
	}

	/**
	 * The zone in slot {@code i}, which may be null for a slot the rebuild
	 * could not fill. Refuses, naming the index and the count, for a slot the
	 * table does not have.
	 */
	public Zone at(int i) {
		if (i < 0 || i >= count()) {
			throw new IndexOutOfBoundsException("zone " + i + " is outside the loaded zone table, which holds "
					+ count() + " zone(s)");
		}
		return table[i];
	}

	/** The open zone, or null when none is. */
	public Zone open() {
		return open;
	}

	/** The index the open zone was opened at; -1 when none was, and see the class comment for a rebuild. */
	public int index() {
		return index;
	}

	/** True when a zone is open. */
	public boolean isOpen() {
		return open != null;
	}

	// ------------------------------------------------------------------ writes

	/**
	 * A rebuild: this table replaces the old one (copied, so the caller's
	 * array is theirs to drop), whatever zone was open is let go, and the
	 * index is left as it was - PINNED by ZoneLoadingStateTest, which is how
	 * the Add-zones picker still defaults to the zone that was open before.
	 */
	public void table(Zone[] zones) {
		if (zones == null) {
			throw new IllegalArgumentException("a zone table cannot be null - hand an empty table to say the archive holds no zones");
		}
		table = zones.clone();
		open = null;
	}

	/** One slot of the table, filled or refilled; the rebuild fills them one by one. */
	public void replace(int i, Zone z) {
		if (i < 0 || i >= count()) {
			throw new IndexOutOfBoundsException("zone " + i + " is outside the loaded zone table, which holds "
					+ count() + " zone(s)");
		}
		table[i] = z;
	}

	/** Opens slot {@code i} of the table; refuses a slot the table does not have or could not fill. */
	public void open(int i) {
		Zone z = at(i);
		if (z == null) {
			throw new IllegalStateException("slot " + i + " of the loaded zone table holds no zone");
		}
		open = z;
		index = i;
	}

	/**
	 * {@code z} is open at index {@code i}: a zone the list worker just
	 * loaded, a zone a suite plants, a zone opened from a file ({@code i} of
	 * -1, no slot), or - {@code z} null - an index with no zone open.
	 */
	public void open(int i, Zone z) {
		if (i < -1) {
			throw new IllegalArgumentException("a zone index is -1 for none or a table position, not " + i);
		}
		open = z;
		index = i;
	}

	/** The open zone is let go; the index is not touched. See the class comment. */
	public void release() {
		open = null;
	}

	/** Nothing is open and the index says so. */
	public void close() {
		open = null;
		index = -1;
	}
}
