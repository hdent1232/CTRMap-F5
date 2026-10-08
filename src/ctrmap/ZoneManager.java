package ctrmap;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import static ctrmap.util.LittleEndian.u16;
import static ctrmap.util.LittleEndian.putU16;
import java.nio.file.Files;

/**
 * Safe, index-preserving zone edits: EMPTY a zone (clear its NPCs/warps/etc.) and
 * RENAME a zone (change its in-game location banner). Neither changes any zone's
 * GARC index, so no warp/script reference can break (unlike removing a slot - see
 * {@link ZoneAppender} for the tail-removal path).
 *
 * <p>A zone's in-game name is GAMETEXT file 90 (ORAS), line = the header's 10-bit
 * {@code parentMap} field (offset 0x1C). Renaming is share-aware, mirroring
 * {@link GeometryForker}: if the zone shares its name with others (a clone shares
 * the town it was copied from), a NEW name line is appended and the zone repointed
 * to it, so only this zone is renamed; if the name is the zone's alone, the line is
 * edited in place. The parentMap is repointed in BOTH the ZO header and the master
 * zone-header table (the game reads the RAM master table - see GeometryForker).
 */
public class ZoneManager {

	/** Header byte offset of the packed parentMap/OLvalue u16 (parentMap = low 10 bits). */
	public static final int PARENTMAP_OFFSET = 0x1C;
	public static final int PARENTMAP_MASK = 0x3FF;

	/** Result of a rename, for user-facing reporting. */
	public static class RenameResult {
		public boolean gaveOwnName;   // true if the zone was moved to its own private name slot (was shared)
		public boolean renamedSharers; // true if the shared name was edited in place (affected other zones)
		public int sharers;           // how many zones shared the old name
		public int parentMap;         // the zone's (possibly new) name line index
		public String oldName;

		/**
		 * What the user is told after a rename, and specifically WHO ELSE it moved.
		 *
		 * <p>A location name is shared: in the retail game one line names Fallarbor
		 * Town and Routes 111 to 114 together. {@link ZoneManager#renameZone} tries
		 * to give the renamed zone a private name line so the others keep theirs,
		 * but the game's place-id bound is hard, and when no free line is left it
		 * falls back to editing the shared line - which renames every zone on it.
		 *
		 * <p>Those two outcomes look identical from the map: the zone the user asked
		 * about now has the name they typed. The only thing that distinguishes "your
		 * town is renamed" from "your town and four routes are renamed" is this
		 * sentence. Lose it and the user ships a game where four routes are called
		 * whatever they named their new town, having been told the rename worked.
		 */
		public String report(int zoneIndex, String newName) {
			StringBuilder sb = new StringBuilder();
			sb.append("Zone ").append(zoneIndex).append(" renamed to \"").append(newName).append("\".\n\n");
			if (gaveOwnName) {
				sb.append("It was sharing the name \"").append(oldName).append("\" with ").append(sharers)
				  .append(" zones; it now has its own name and the others are unchanged.\n\n");
			} else if (renamedSharers) {
				sb.append("NOTE: \"").append(oldName).append("\" was shared by ").append(sharers)
				  .append(" zones and no free name slot was available, so ALL of them were renamed.\n\n");
			} else {
				sb.append("The name belonged to this zone alone.\n\n");
			}
			sb.append("Run File > Deploy to emulator (it packs first), then fully restart the emulator.\n");
			sb.append("Reselect the zone in the dropdown to see the new name in the editor.");
			return sb.toString();
		}
	}

	/**
	 * Clears a zone's placed content - furniture, NPCs, warps and triggers - back
	 * to empty, keeping the header and script. Safe for any zone (no index change).
	 *
	 * @return how many placed objects were removed
	 */
	public static int clearZone(WorkspaceSession ws, int zoneIndex) throws IOException {
		if (ws == null) {
			throw new IOException("No workspace is loaded.");
		}
		File zf = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (zf == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		ZO zo = new ZO(zf, ws);
		ZoneEntities ent = new ZoneEntities(zo.getFile(1));
		int removed = ent.furniture.size() + ent.npcs.size() + ent.warps.size()
				+ ent.triggers1.size() + ent.triggers2.size();
		ent.furniture.clear();
		ent.npcs.clear();
		ent.warps.clear();
		ent.triggers1.clear();
		ent.triggers2.clear();
		ent.modified = true;
		byte[] assembled = ent.assembleData();
		if (assembled == null) {
			throw new IOException("Could not rebuild the emptied entity data.");
		}
		try {
			zo.storeFile(1, assembled); // rewrites the ZO on disk + persists
		} catch (RuntimeException notWritten) {
			throw new IOException("Could not write the emptied zone: "
				+ ctrmap.util.Bytes.reason(notWritten), notWritten);
		}
		return removed;
	}

	/**
	 * Renames a zone's in-game location banner. Share-aware (see class doc): a
	 * shared name is forked to a new private line; a zone-unique name is edited in
	 * place. Pack Workspace afterwards.
	 *
	 * @param zoneIndex the zone to rename
	 * @param newName   the new location name (plain text)
	 */
	public static RenameResult renameZone(WorkspaceSession ws, int zoneIndex, String newName) throws IOException {
		return renameZone(ws, zoneIndex, newName, false);
	}

	/**
	 * A rename refused because the zone's name is shared and no free name line is left (F233,
	 * census L.8): this zone cannot get a name of its own, and editing the shared line renames every
	 * zone on it - which happened, after the dialog had promised the others were left unchanged.
	 * Nothing was written.
	 */
	public static final class SharedNameOnly extends IOException {

		public final int sharers;
		public final String oldName;

		SharedNameOnly(int sharers, String oldName) {
			super("No free name line is left in the game's location names, so this zone cannot get a name"
					+ " of its own - renaming \"" + oldName + "\" would rename all " + sharers + " zones that share"
					+ " it. Nothing was renamed.");
			this.sharers = sharers;
			this.oldName = oldName;
		}
	}

	/**
	 * @param renameAllSharers what to do when the name is shared and no free line is left: true
	 * renames the shared line for every zone on it, false refuses with {@link SharedNameOnly}
	 */
	public static RenameResult renameZone(WorkspaceSession ws, int zoneIndex, String newName, boolean renameAllSharers)
			throws IOException {
		//ONE UNIT (F227): the name, the zone header and the master row are written in turn, and a
		//refusal at the master row left the header and the row naming different places
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Rename zone", ws)) {
			RenameResult r = renameZoneWrites(ws, zoneIndex, newName, renameAllSharers);
			unit.commit();
			return r;
		}
	}

	private static RenameResult renameZoneWrites(WorkspaceSession ws, int zoneIndex, String newName, boolean renameAllSharers)
			throws IOException {
		if (ws == null) {
			throw new IOException("No workspace is loaded.");
		}
		GARC zoGarc = ws.getArchive(ArchiveType.ZONE_DATA);
		if (zoGarc == null) {
			throw new IOException("ZoneData archive unavailable.");
		}
		//THE HANDED GAME'S OWN TABLE bounds the index (F019): this asked the GLOBAL game's master
		//index, so a caller handing another session got its range check - and its idea of which
		//entry is the master table - from a different game
		MasterTable table = MasterTable.read(ws);
		if (zoneIndex < 0 || zoneIndex >= table.zones()) {
			throw new IOException("Zone " + zoneIndex + " out of range (0.." + (table.zones() - 1) + ").");
		}
		// validate the new name encodes (fail before touching anything)
		try {
			GFMessageFile.write(java.util.Arrays.asList(newName));
		} catch (RuntimeException ex) {
			throw new IOException("That name can't be encoded: " + ctrmap.util.Bytes.reason(ex));
		}

		//through the one reader (F013): this read it with no check at all
		int curParent = placeOf(table, zoneIndex);

		// count zones sharing this name line (scan the master table - all headers)
		int sharers = 0;
		for (int i = 0; i < table.zones(); i++) {
			if (placeOf(table, i) == curParent) {
				sharers++;
			}
		}

		// load the location-name text file (entry index from the game profile)
		int gtIndex = ctrmap.formats.text.LocationNames.gametextIndex(ws);
		File gtFile = ws.getWorkspaceFile(ArchiveType.GAMETEXT, gtIndex);
		if (gtFile == null) {
			throw new IOException("Could not read the location-name text file (GAMETEXT " + gtIndex + ").");
		}
		GFMessageFile names = new GFMessageFile(Files.readAllBytes(gtFile.toPath()));

		RenameResult r = new RenameResult();
		r.sharers = sharers;
		r.oldName = (curParent >= 0 && curParent < names.getLineCount()) ? names.getLine(curParent) : "";

		int newParent;
		if (sharers <= 1) {
			// this name belongs to this zone alone -> rename in place (safe, only this zone)
			if (curParent < names.getLineCount()) {
				names.setLine(curParent, newName);
			} else {
				throw new IOException("Zone " + zoneIndex + "'s name line " + curParent + " is out of range.");
			}
			newParent = curParent;
		} else {
			// shared name -> move this zone to a FREE, IN-BOUNDS name slot so the other
			// zones keep their name. A new appended slot would exceed the game's hardcoded
			// location-id bound (356), so we reuse an unused blank slot (index < count).
			int slot = findFreeBlankSlot(table, names);
			if (slot >= 0) {
				names.setLine(slot, newName);
				newParent = slot;
				r.gaveOwnName = true;
			} else if (!renameAllSharers) {
				throw new SharedNameOnly(sharers, r.oldName);   //asked first, never done (F233, census L.8)
			} else {
				// no free slot, and the caller said so: rename the shared name in place (affects all sharers)
				names.setLine(curParent, newName);
				newParent = curParent;
				r.renamedSharers = true;
			}
		}
		WorkspaceUnit.write(gtFile, names.write());
		ws.addPersist(gtFile);

		if (newParent != curParent) {
			repointParentMap(ws, zoneIndex, table, newParent);
		}
		r.parentMap = newParent;
		return r;
	}

	/** The zone's place-name line: the low 10 bits of its master row's packed field at 0x1C. */
	private static int placeOf(MasterTable table, int zone) {
		return u16(table.row(zone), PARENTMAP_OFFSET) & PARENTMAP_MASK;
	}

	/**
	 * Finds a location-name slot that is IN BOUNDS (below the game's hardcoded 356
	 * place-id limit) and used by NO zone and currently blank - safe to repurpose
	 * for a zone's private name. Returns -1 if none is free.
	 */
	private static int findFreeBlankSlot(MasterTable table, GFMessageFile names) {
		int count = Math.min(names.getLineCount(), PARENTMAP_MASK + 1); // parentMap is 10-bit
		boolean[] used = new boolean[count];
		for (int i = 0; i < table.zones(); i++) {
			int p = placeOf(table, i);
			if (p >= 0 && p < count) {
				used[p] = true;
			}
		}
		for (int i = 1; i < count; i++) { // skip 0 (the "no location" sentinel)
			if (!used[i]) {
				String nm = names.getLine(i);
				if (nm == null || nm.trim().isEmpty()) {
					return i;
				}
			}
		}
		return -1;
	}

	/** Sets the zone's parentMap in BOTH the ZO header (subfile 0) and the master row. */
	private static void repointParentMap(WorkspaceSession ws, int zoneIndex, MasterTable table, int newParent) throws IOException {
		// ZO header (subfile 0)
		File zf = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		ZO zo = new ZO(zf, ws);
		byte[] hdr = zo.getFile(0);
		setParentMap(hdr, 0, newParent);
		try {
			zo.storeFile(0, hdr);
		} catch (RuntimeException notWritten) {
			throw new IOException("Could not update zone " + zoneIndex + "'s header: "
				+ ctrmap.util.Bytes.reason(notWritten), notWritten);
		}
		// master row
		byte[] row = table.row(zoneIndex);
		setParentMap(row, 0, newParent);
		table.setRow(zoneIndex, row);
		table.write();
	}

	/** Overwrites only the low-10-bit parentMap of the packed u16 at base+0x1C. */
	private static void setParentMap(byte[] b, int base, int newParent) {
		int packed = u16(b, base + PARENTMAP_OFFSET);
		packed = (packed & ~PARENTMAP_MASK) | (newParent & PARENTMAP_MASK);
		putU16(b, base + PARENTMAP_OFFSET, packed);
	}


}
