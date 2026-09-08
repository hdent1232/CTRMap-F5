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
 * what it wrote or throws with the reason, and {@link #discardEntities()}
 * forgets; the window that owns the question (the zone panel) asks it
 * through the dialog seam and passes the decision down.
 * {@code ctrmap.tests.HandedGameTest} proves nothing here asks anything.
 */
public class Zone {

	/** The parts of a zone that carry a change of their own; the script is always written. */
	public enum Part {
		HEADER, ENTITIES
	}

	public ZO file;
	public ZoneHeader header;
	public ZoneEntities entities;
	public GFLPawnScript s;

	public Zone(ZO data, GameType game) {
		file = data;
		header = new ZoneHeader(data.getFile(0), game);
		entities = new ZoneEntities(data.getFile(1));
		s = new GFLPawnScript(data.getFile(2));
	}

	/**
	 * The parts whose in-memory state differs from the file: the header when
	 * its assembled bytes differ from subfile 0, the entities when they are
	 * marked modified.
	 */
	public EnumSet<Part> changed() {
		EnumSet<Part> out = EnumSet.noneOf(Part.class);
		if (!Arrays.equals(header.assembleData(), file.getFile(0))) {
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
	 * Writes the parts named in {@code keep} and then the script, which is
	 * always written, and returns the parts written.
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
	 * @param keep the parts to write; a part that is named but unchanged is
	 * written anyway, which the container turns into a no-op
	 * @return the parts written; never null
	 * @throws IllegalStateException when the entities were asked for and will
	 * not serialise, with the record's own reason; nothing is written then
	 */
	public EnumSet<Part> store(Set<Part> keep) {
		EnumSet<Part> written = EnumSet.noneOf(Part.class);
		byte[] entityData = null;
		if (keep.contains(Part.ENTITIES)) {
			//throws IllegalStateException naming the record that refused;
			//nothing below runs, so the file stays as it was
			entityData = entities.assembleData();
		}
		if (keep.contains(Part.HEADER)) {
			file.storeFile(0, header.assembleData());
			written.add(Part.HEADER);
		}
		if (entityData != null) {
			file.storeFile(1, entityData);
			entities.modified = false;
			written.add(Part.ENTITIES);
		}
		file.storeFile(2, s.getScriptBytes());
		return written;
	}

	/**
	 * Forgets that the entities were modified without writing anything; the
	 * in-memory records are left as they are, for a caller that reloads them.
	 *
	 * @return true when there was a change to forget, false when the entities
	 * were not marked modified
	 */
	public boolean discardEntities() {
		if (!entities.modified) {
			return false;
		}
		entities.modified = false;
		return true;
	}
}
