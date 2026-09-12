package ctrmap;

import ctrmap.gamedef.ArchiveType;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.LittleEndian.putU16;

/**
 * Everything a zone header POINTS AT, and therefore everything a zone can share
 * with another zone.
 *
 * <p>WHY THIS IS A TABLE AND NOT FOUR PIECES OF CODE. A zone created by this
 * editor is supposed to be independent from birth - editing it must never change
 * another zone. The map matrix was made independent and the other three were
 * not, and the reason is exactly that they were never written down in one place:
 * the appender forked the field it knew about, and nothing anywhere said what
 * the full set was. The owner met that as a shared-map dialog opening on a zone
 * the editor had created seconds earlier, and then again as a Fog &amp; lighting
 * dialog saying five zones shared an area.
 *
 * <p>Measured on the owner's game after the map was fixed: zones 536-539, all
 * created by one append from donor 534, still shared story text 491 and script
 * 134 with 534 and with each other, and 537/538/539 still shared area 24 with
 * 534 and with zone 20. Editing the story text of 537 rewrote Sootopolis's, and
 * blank spare 539 was wired to run Sootopolis's script.
 *
 * <p>So the set is a table and the work is a loop over it. A fifth shareable
 * field is then a row here, which {@code WorkflowGuardsTest} checks against the
 * header parser - not a fifth thing for someone to forget.
 *
 * <p>NOT EVERYTHING HERE CAN BE FORKED, and that is recorded rather than hidden.
 * {@link #SCRIPT} has no {@link ArchiveType}: CTRMap does not manage the script
 * archive at all, so a created zone cannot be given its own script and the only
 * honest close is to SAY so where the zone is created. A row that cannot be
 * forked is still a row - it is the difference between a known limitation and a
 * silence.
 */
public enum ZoneResource {

	/** Atmosphere, water animations, prop registry and NPC models. Forked by {@link AreaForker}. */
	AREA("area", 0x02, ArchiveType.AREA_DATA),
	/** The map: the matrix and every FieldData region under it. Forked by {@link GeometryForker}. */
	MAP("map", 0x04, ArchiveType.MAP_MATRIX),
	/** The zone's story text file. */
	TEXT("story text", 0x06, ArchiveType.STORYTEXT),
	/**
	 * The zone's script. NOT FORKABLE: there is no {@code ArchiveType} for the
	 * script archive, so CTRMap neither extracts nor packs it and cannot give a
	 * new zone one of its own. A zone created here runs the donor's events.
	 */
	SCRIPT("script", 0x18, null);

	/** What to call it in a sentence a user reads. */
	public final String label;
	/** Byte offset of its u16 id inside the zone header (ZO subfile 0). */
	public final int headerOffset;
	/** The archive its id indexes, or null when CTRMap does not manage that archive. */
	public final ArchiveType archive;

	ZoneResource(String label, int headerOffset, ArchiveType archive) {
		this.label = label;
		this.headerOffset = headerOffset;
		this.archive = archive;
	}

	/** Whether a zone can be given its own copy of this at all. */
	public boolean forkable() {
		return archive != null;
	}

	/** This resource's id, read out of a whole ZO container's bytes. */
	public int idIn(byte[] zoBytes) {
		return u16(zoBytes, i32(zoBytes, 4) + headerOffset);
	}

	/** Points a ZO container's header at a different id of this resource, in place. */
	public void setIn(byte[] zoBytes, int id) {
		putU16(zoBytes, i32(zoBytes, 4) + headerOffset, id);
	}

	/** This resource's id in a row of the master zone-header table. */
	public int idInMasterRow(byte[] master, int zoneIndex) {
		return u16(master, zoneIndex * GeometryForker.MASTER_ROW + headerOffset);
	}
}
