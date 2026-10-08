package ctrmap.formats.zone;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.containers.ZO;
import ctrmap.gamedef.GameType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Zone data container class, incomplete.
 *
 * <p>Saving is a decision this class no longer makes. {@code store(boolean)}
 * used to ask the user, from inside the format layer, whether to keep the
 * header and whether to keep the entities, and to open an error dialog when
 * the entities refused to serialise - where no suite could answer and no
 * caller could decide for it. Now {@link #changed()} says what differs from
 * the file, {@link #store(Set)} writes the parts it is told to and reports
 * what it wrote or throws with the reason, and {@link #discardHeader()} and
 * {@link #discardEntities()} put back what the file holds; the window that
 * owns the question (the zone panel) asks it
 * through the dialog seam and passes the decision down.
 * {@code ctrmap.tests.HandedGameTest} proves nothing here asks anything.
 */
public class Zone {

	/** The parts of a zone that carry a change of their own; the script is written whenever it differs from what was read. */
	public enum Part {
		HEADER, ENTITIES
	}

	public ZO file;
	public ZoneHeader header;
	public ZoneEntities entities;
	public GFLPawnScript s;

	/** The game this zone was read for - {@link #reread} needs it. */
	private final GameType game;

	/** Subfile names, for what a report says changed. */
	private static final String[] SUBFILES = {"header", "entities", "script"};

	/**
	 * What the FILE held, subfile by subfile, when this zone read it or last wrote it - as a
	 * digest, so 536 table zones do not each keep a copy of their script (F234).
	 *
	 * <p>WHY. Empty zone, Rename, Connect zones, the painter's door wiring and an interior clone
	 * each write a zone file through a container of their own, and this object - the zone table's,
	 * which the editor opens and saves - went on holding what it read before. Opening it later
	 * showed the old warps, and saving it wrote the old header, entities and script back over what
	 * was done: a rename undone by the next save, a link cut off. Comparing against what was READ,
	 * not against the file as it is now, is how this object can tell its own edits from another
	 * writer's: {@link #changed} is the first, {@link #changedOnDisk} the second, and
	 * {@link #store} refuses a part that is both.
	 */
	private final byte[][] asRead = new byte[3][];

	public Zone(ZO data, GameType game) {
		file = data;
		this.game = game;
		byte[] h = data.getFile(0);
		byte[] e = data.getFile(1);
		byte[] sc = data.getFile(2);
		asRead[0] = digest(h);
		asRead[1] = digest(e);
		asRead[2] = digest(sc);
		header = new ZoneHeader(h, game);
		entities = new ZoneEntities(e);
		s = new GFLPawnScript(sc);
	}

	static byte[] digest(byte[] b) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(b == null ? new byte[0] : b);
		} catch (java.security.NoSuchAlgorithmException every) {
			throw new IllegalStateException("SHA-256 is missing from this Java, which every Java has", every);
		}
	}

	/**
	 * The subfiles the file no longer holds as this zone read them: another writer's work since
	 * (F234). Empty when the file is as read.
	 */
	public java.util.List<String> changedOnDisk() {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (int i = 0; i < SUBFILES.length; i++) {
			if (onDisk(i)) {
				out.add(SUBFILES[i]);
			}
		}
		return out;
	}

	/** Whether subfile {@code i} was written by someone else since this zone read it. */
	private boolean onDisk(int i) {
		return !Arrays.equals(digest(file.getFile(i)), asRead[i]);
	}

	/** The game this zone was read for - what a template asks before it writes into it (F085). */
	public GameType game() {
		return game;
	}

	/**
	 * This zone read again from its file: what opening a zone another writer changed shows
	 * (F234), rather than what the zone table read before that writer ran.
	 */
	public Zone reread() {
		return new Zone(file, game);
	}

	/**
	 * The parts whose in-memory state differs from what was READ: the header
	 * when its assembled bytes differ from subfile 0 as read, the entities when
	 * they are marked modified. Against what was read, not the file as it is now
	 * (F234): a header another writer changed is that writer's change, not this
	 * zone's, and calling it this zone's offered to write the old one back.
	 */
	public EnumSet<Part> changed() {
		EnumSet<Part> out = EnumSet.noneOf(Part.class);
		if (!Arrays.equals(digest(header.assembleData()), asRead[0])) {
			out.add(Part.HEADER);
		}
		if (entities.modified) {
			out.add(Part.ENTITIES);
		}
		return out;
	}

	/**
	 * Writes everything that {@link #changed()}, and the script, asking
	 * nothing: what a headless caller means by "save".
	 *
	 * @see #store(Set)
	 */
	public EnumSet<Part> store() {
		return store(changed());
	}

	/**
	 * Writes the parts named in {@code keep} and then the script, each only when
	 * it differs from what was read, and returns the parts named and now on disk
	 * as in memory. A part about to be written that ANOTHER writer changed on disk
	 * since this zone read it is refused before anything is written (F234).
	 *
	 * <p>The entities are serialised BEFORE anything is written, so a record
	 * that refuses - a warp with no destination, an NPC with no altitude, a
	 * 256th entity - leaves the file exactly as it was and the entities still
	 * marked modified. It used to be possible to write the header and then
	 * refuse the entities, which the caller reported as "not saved" over a
	 * half-written zone.
	 *
	 * <p>A part not named is left unwritten and, for the entities, still marked
	 * modified: a caller that was told "discard" says so through
	 * {@link #discardEntities()}, so that forgetting is a decision and not a
	 * side effect of saving something else.
	 *
	 * @param keep the parts to write; a part that is named but unchanged from
	 * what was read is not written and is still answered as written
	 * @return the parts written; never null
	 * @throws IllegalStateException when the entities were asked for and will
	 * not serialise, with the record's own reason, or when a part to be written
	 * was changed on disk by another writer; nothing is written then
	 */
	public EnumSet<Part> store(Set<Part> keep) {
		EnumSet<Part> written = EnumSet.noneOf(Part.class);
		byte[] entityData = null;
		if (keep.contains(Part.ENTITIES)) {
			//throws IllegalStateException naming the record that refused;
			//nothing below runs, so the file stays as it was
			entityData = entities.assembleData();
		}
		byte[] headerData = keep.contains(Part.HEADER) ? header.assembleData() : null;
		byte[] script = s.getScriptBytes();
		//A PART IS WRITTEN ONLY WHEN IT DIFFERS FROM WHAT WAS READ (F234) - the script included:
		//it was written on every save whatever had happened to it, so a script another writer
		//changed was put back as it was read
		boolean headerOut = headerData != null && !Arrays.equals(digest(headerData), asRead[0]);
		boolean entitiesOut = entityData != null && !Arrays.equals(digest(entityData), asRead[1]);
		boolean scriptOut = !Arrays.equals(digest(script), asRead[2]);
		//AND IS REFUSED, BEFORE ANYTHING IS WRITTEN, when the file's copy of that same part was
		//changed by another writer since: writing it would put the old one back over that work.
		//A part only one side changed is no conflict, so a zone with no edits of its own never
		//refuses - answering Discard is always a way out.
		java.util.List<String> both = new java.util.ArrayList<>();
		if (headerOut && onDisk(0)) {
			both.add(SUBFILES[0]);
		}
		if (entitiesOut && onDisk(1)) {
			both.add(SUBFILES[1]);
		}
		if (scriptOut && onDisk(2)) {
			both.add(SUBFILES[2]);
		}
		if (!both.isEmpty()) {
			throw new IllegalStateException("The zone's " + String.join(" and ", both) + " changed on disk since"
					+ " it was opened - another action wrote the zone (Empty zone, Rename, Connect zones, a door"
					+ " wired from another map) - and this copy has its own edits to the same part. Writing it"
					+ " would put the old one back over that work, so NOTHING was written. Discard these edits"
					+ " to reopen the zone as it is on disk, then make them again (F234).");
		}
		if (headerData != null) {
			if (headerOut) {
				file.storeFile(0, headerData);
				asRead[0] = digest(headerData);
			}
			written.add(Part.HEADER);
		}
		if (entityData != null) {
			if (entitiesOut) {
				file.storeFile(1, entityData);
				asRead[1] = digest(entityData);
			}
			entities.modified = false;
			written.add(Part.ENTITIES);
		}
		if (scriptOut) {
			file.storeFile(2, script);
			asRead[2] = digest(script);
		}
		return written;
	}

	/**
	 * Puts the entities back to what the file holds, writing nothing: a new read of them, which the
	 * caller shows (F106). It used to forget that they were modified and leave the edited records in
	 * memory and on screen, where the next save of the zone wrote them.
	 *
	 * @return true when there was a change to put back, false when the entities
	 * were not marked modified
	 */
	public boolean discardEntities() {
		if (!entities.modified) {
			return false;
		}
		byte[] now = file.getFile(1);
		entities = new ZoneEntities(now);
		asRead[1] = digest(now); //what is shown now is what the file holds now (F234)
		return true;
	}

	/**
	 * Puts the header's fields back to what the file holds, writing nothing and keeping the area,
	 * registry and matrix it opened (F106): No left the edited fields in the header, where the next
	 * save of the zone wrote them.
	 *
	 * @return true when there was a change to put back
	 */
	public boolean discardHeader() {
		if (!changed().contains(Part.HEADER)) {
			return false;
		}
		byte[] now = file.getFile(0);
		header.parse(now);
		asRead[0] = digest(now); //what is shown now is what the file holds now (F234)
		return true;
	}
}
