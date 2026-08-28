package ctrmap;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
	}

	/**
	 * Clears a zone's placed content - furniture, NPCs, warps and triggers - back
	 * to empty, keeping the header and script. Safe for any zone (no index change).
	 *
	 * @return how many placed objects were removed
	 */
	public static int clearZone(int zoneIndex) throws IOException {
		if (!Workspace.valid) {
			throw new IOException("No workspace is loaded.");
		}
		File zf = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
		if (zf == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		ZO zo = new ZO(zf);
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
		if (!zo.storeFile(1, assembled)) { // rewrites the ZO on disk + persists
			throw new IOException("Could not write the emptied zone.");
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
	public static RenameResult renameZone(int zoneIndex, String newName) throws IOException {
		if (!Workspace.valid) {
			throw new IOException("No workspace is loaded.");
		}
		GARC zoGarc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoGarc == null) {
			throw new IOException("ZoneData archive unavailable.");
		}
		int masterIndex = ZoneCloner.getMasterIndex();
		if (zoneIndex < 0 || zoneIndex >= masterIndex) {
			throw new IOException("Zone " + zoneIndex + " out of range (0.." + (masterIndex - 1) + ").");
		}
		// validate the new name encodes (fail before touching anything)
		try {
			GFMessageFile.write(java.util.Arrays.asList(newName));
		} catch (RuntimeException ex) {
			throw new IOException("That name can't be encoded: " + ex.getMessage());
		}

		File masterFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, masterIndex);
		byte[] master = readAll(masterFile);
		int rowOff = zoneIndex * ZoneCloner.ZONE_HEADER_SIZE;
		int curParent = u16(master, rowOff + PARENTMAP_OFFSET) & PARENTMAP_MASK;

		// count zones sharing this name line (scan the master table - all headers)
		int rows = master.length / ZoneCloner.ZONE_HEADER_SIZE;
		int sharers = 0;
		for (int i = 0; i < rows; i++) {
			if ((u16(master, i * ZoneCloner.ZONE_HEADER_SIZE + PARENTMAP_OFFSET) & PARENTMAP_MASK) == curParent) {
				sharers++;
			}
		}

		// load the location-name text file (entry index from the game profile)
		int gtIndex = ctrmap.formats.text.LocationNames.gametextIndex();
		File gtFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.GAMETEXT, gtIndex);
		if (gtFile == null) {
			throw new IOException("Could not read the location-name text file (GAMETEXT " + gtIndex + ").");
		}
		GFMessageFile names = new GFMessageFile(readAll(gtFile));

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
			int slot = findFreeBlankSlot(master, names);
			if (slot >= 0) {
				names.setLine(slot, newName);
				newParent = slot;
				r.gaveOwnName = true;
			} else {
				// no free slot: fall back to renaming the shared name in place (affects all sharers)
				names.setLine(curParent, newName);
				newParent = curParent;
				r.renamedSharers = true;
			}
		}
		writeAll(gtFile, names.write());
		Workspace.addPersist(gtFile);

		if (newParent != curParent) {
			repointParentMap(zoneIndex, masterFile, master, rowOff, newParent);
		}
		r.parentMap = newParent;
		return r;
	}

	/**
	 * Finds a location-name slot that is IN BOUNDS (below the game's hardcoded 356
	 * place-id limit) and used by NO zone and currently blank - safe to repurpose
	 * for a zone's private name. Returns -1 if none is free.
	 */
	private static int findFreeBlankSlot(byte[] master, GFMessageFile names) {
		int rows = master.length / ZoneCloner.ZONE_HEADER_SIZE;
		int count = Math.min(names.getLineCount(), PARENTMAP_MASK + 1); // parentMap is 10-bit
		boolean[] used = new boolean[count];
		for (int i = 0; i < rows; i++) {
			int p = u16(master, i * ZoneCloner.ZONE_HEADER_SIZE + PARENTMAP_OFFSET) & PARENTMAP_MASK;
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
	private static void repointParentMap(int zoneIndex, File masterFile, byte[] master, int rowOff, int newParent) throws IOException {
		// ZO header (subfile 0)
		File zf = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
		ZO zo = new ZO(zf);
		byte[] hdr = zo.getFile(0);
		setParentMap(hdr, 0, newParent);
		if (!zo.storeFile(0, hdr)) {
			throw new IOException("Could not update zone " + zoneIndex + "'s header.");
		}
		// master row
		setParentMap(master, rowOff, newParent);
		writeAll(masterFile, master);
		Workspace.addPersist(masterFile);
	}

	/** Overwrites only the low-10-bit parentMap of the packed u16 at base+0x1C. */
	private static void setParentMap(byte[] b, int base, int newParent) {
		int packed = u16(b, base + PARENTMAP_OFFSET);
		packed = (packed & ~PARENTMAP_MASK) | (newParent & PARENTMAP_MASK);
		b[base + PARENTMAP_OFFSET] = (byte) (packed & 0xFF);
		b[base + PARENTMAP_OFFSET + 1] = (byte) ((packed >> 8) & 0xFF);
	}

	private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
		in.read(b);
		in.close();
		return b;
	}

	private static void writeAll(File f, byte[] b) throws IOException {
		OutputStream os = new FileOutputStream(f);
		os.write(b);
		os.flush();
		os.close();
	}
}
