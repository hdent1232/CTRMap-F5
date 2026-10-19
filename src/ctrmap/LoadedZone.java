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
 * <p>NO HOLES (F208). The table used to be installed first and filled slot by slot, so one
 * unreadable ZoneData entry left every later slot null - and five of the nine readers of
 * {@link #at} dereferenced what it answered, so after that no zone could be opened at all, the
 * warp form throwing on a zone nobody had asked about. A table is refused unless every slot
 * holds a zone and a slot cannot be emptied, so at() never answers null and no reader has to
 * remember that it might.
 *
 * <p>ONE STATE, REPLACED WHOLE (F016). The table, the open zone and the index were three plain
 * fields with the comment "it gives the state an owner, not a lock" - and the zone-open worker
 * read the table off the event thread while the event thread swapped it, so under the memory
 * model it could see a new table with an old index, or a slot half written. They are one
 * immutable {@link State} behind a volatile reference now: every write builds the next state and
 * publishes it at once, and a reader on any thread sees one whole state, never a mixture.
 * Writes are serialised so two of them cannot each build from the same old state.
 */
public final class LoadedZone {

	/** The three answers together, never changed once made. */
	private static final class State {

		final Zone[] table;
		final Zone open;
		final int index;

		State(Zone[] table, Zone open, int index) {
			this.table = table;
			this.open = open;
			this.index = index;
		}

		int count() {
			return table == null ? 0 : table.length;
		}

		Zone at(int i) {
			if (i < 0 || i >= count()) {
				throw new IndexOutOfBoundsException("zone " + i + " is outside the loaded zone table, which holds "
						+ count() + " zone(s)");
			}
			return table[i];
		}
	}

	private volatile State state = new State(null, null, -1);

	/** How many slots the zone table holds; 0 before any rebuild. */
	public int count() {
		return state.count();
	}

	/**
	 * The zone in slot {@code i} - never null, since a table with a hole is refused (F208).
	 * Refuses, naming the index and the count, for a slot the table does not have.
	 */
	public Zone at(int i) {
		return state.at(i);
	}

	/** The open zone, or null when none is. */
	public Zone open() {
		return state.open;
	}

	/** The index the open zone was opened at; -1 when none was, and see the class comment for a rebuild. */
	public int index() {
		return state.index;
	}

	/** True when a zone is open. */
	public boolean isOpen() {
		return state.open != null;
	}

	// ------------------------------------------------------------------ writes

	/**
	 * A rebuild: this table replaces the old one (copied, so the caller's
	 * array is theirs to drop), whatever zone was open is let go, and the
	 * index is left as it was - PINNED by ZoneLoadingStateTest, which is how
	 * the Add-zones picker still defaults to the zone that was open before.
	 */
	public synchronized void table(Zone[] zones) {
		if (zones == null) {
			throw new IllegalArgumentException("a zone table cannot be null - hand an empty table to say the archive holds no zones");
		}
		for (int i = 0; i < zones.length; i++) {
			if (zones[i] == null) {
				throw new IllegalArgumentException("slot " + i + " of the zone table holds no zone - a table is installed"
						+ " whole, once every zone in it has been read (F208)");
			}
		}
		state = new State(zones.clone(), null, state.index);
	}

	/** One slot of the table, refilled with another zone; a slot is never emptied (F208). */
	public synchronized void replace(int i, Zone z) {
		if (z == null) {
			throw new IllegalArgumentException("slot " + i + " of the zone table cannot be emptied - a zone table"
					+ " has no holes (F208)");
		}
		State s = state;
		s.at(i);   //refuses a slot the table does not have
		Zone[] next = s.table.clone();   //copied: a reader holding the old state keeps the old table whole
		next[i] = z;
		state = new State(next, s.open, s.index);
	}

	/** Opens slot {@code i} of the table; refuses a slot the table does not have. */
	@ctrmap.util.ForSuites("SEAM: a suite opens a slot the table already holds; the application opens a zone"
			+ " as it loads it, through open(int, Zone)")
	public synchronized void open(int i) {
		State s = state;
		state = new State(s.table, s.at(i), i);
	}

	/**
	 * {@code z} is open at index {@code i}: a zone the list worker just
	 * loaded, a zone a suite plants, a zone opened from a file ({@code i} of
	 * -1, no slot), or - {@code z} null - an index with no zone open.
	 */
	public synchronized void open(int i, Zone z) {
		if (i < -1) {
			throw new IllegalArgumentException("a zone index is -1 for none or a table position, not " + i);
		}
		state = new State(state.table, z, i);
	}

	/** The open zone is let go; the index is not touched. See the class comment. */
	public synchronized void release() {
		State s = state;
		state = new State(s.table, null, s.index);
	}

	/** Nothing is open and the index says so. */
	public synchronized void close() {
		state = new State(state.table, null, -1);
	}
}
