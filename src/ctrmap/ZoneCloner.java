package ctrmap;

import java.io.File;
import java.io.IOException;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.putI32;
import java.nio.file.Files;

/**
 * Clones a zone over another EXISTING ZoneData slot ("safe" variant - no GARC
 * appending, both slots must already exist).
 *
 * What is copied: the whole ZO container of the source zone (header, entities,
 * zone script, in-container encounter subfile, misc) plus the source row of
 * the master zone-header table. The "EN" encounter pack (ORAS-only, GARC entry
 * length-1) is deliberately left untouched, so the destination keeps whatever
 * wild encounters its slot had.
 *
 * The OAZoneNumber (unknownFlags bits 21..31 of the 0x38-byte zone header,
 * ORAS only) must equal the zone's own GARC index, so it is re-patched to the
 * destination index in both the cloned ZO subfile 0 and the master table row.
 * This is done as a RAW byte patch at headerOffset + 0x28 (LE u32) because
 * ZoneHeader.calculateFlags() intentionally never writes those bits (the line
 * is commented out there).
 */
public class ZoneCloner {

	public static final int ZONE_HEADER_SIZE = 0x38;
	public static final int UNKNOWN_FLAGS_OFFSET = 0x28;
	public static final int OA_ZONE_NUMBER_SHIFT = 21;

	/**
	 * Clones zone srcIndex over zone dstIndex in the current workspace. Reads
	 * the source through Workspace.getWorkspaceFile so prior (saved) edits are
	 * respected, overwrites the destination's workspace zonedata file and the
	 * master table row, and persists both touched files.
	 */
	public static void cloneIntoSlot(int srcIndex, int dstIndex) throws IOException {
		File src = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, srcIndex);
		File dst = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, dstIndex);
		File master = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, getMasterIndex());
		if (src == null || dst == null || master == null) {
			throw new IOException("Could not extract the required ZoneData files from the workspace.");
		}
		cloneIntoFiles(src, dst, master, srcIndex, dstIndex, Workspace.isOA());
		Workspace.addPersist(dst);
		Workspace.addPersist(master);
	}

	/**
	 * GARC index of the master zone-header table - same branching as
	 * ZoneLoadingPanel.loadEverything()/store(): the last entry on XY, the
	 * second-to-last on ORAS (whose last entry is the "EN" encounter pack).
	 */
	public static int getMasterIndex() {
		ctrmap.formats.garc.GARC zoArc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoArc == null) {
			throw new IllegalStateException("No workspace is loaded (ZoneData archive unavailable).");
		}
		return zoArc.length - (Workspace.isXY() ? 1 : 2);
	}

	/**
	 * Core byte transform with the file locations injected (headless-testable).
	 * Overwrites dstZo with a patched copy of srcZo and patches the master
	 * table in place. patchOAZoneNumber should be true on ORAS only - on XY
	 * bits 21..31 of unknownFlags are not the zone index and are copied
	 * verbatim.
	 */
	public static void cloneIntoFiles(File srcZo, File dstZo, File masterFile, int srcIndex, int dstIndex, boolean patchOAZoneNumber) throws IOException {
		if (srcIndex == dstIndex) {
			throw new IllegalArgumentException("Source and destination are the same zone.");
		}
		byte[] src = Files.readAllBytes(srcZo.toPath());
		checkZOMagic(src, "Source zone " + srcIndex);
		byte[] dstOld = Files.readAllBytes(dstZo.toPath());
		checkZOMagic(dstOld, "Destination zone " + dstIndex);
		byte[] cloned = cloneZoneBytes(src, dstIndex, patchOAZoneNumber);
		byte[] master = Files.readAllBytes(masterFile.toPath());
		patchMasterRow(master, srcIndex, dstIndex, patchOAZoneNumber);
		Files.write(dstZo.toPath(), cloned);
		Files.write(masterFile.toPath(), master);
	}

	/**
	 * Returns a copy of the ZO container bytes with, if requested, the header's
	 * OAZoneNumber (unknownFlags bits 21..31) patched to dstIndex. The header
	 * is ZO subfile 0, so the flags live at containerOffset[0] + 0x28.
	 */
	public static byte[] cloneZoneBytes(byte[] zo, int dstIndex, boolean patchOAZoneNumber) {
		checkZOMagic(zo, "Zone data");
		byte[] out = new byte[zo.length];
		System.arraycopy(zo, 0, out, 0, zo.length);
		if (patchOAZoneNumber) {
			int headerOffset = i32(out, 4); //containerOffset[0] = start of subfile 0 (the 0x38-byte zone header)
			if (headerOffset < 0 || headerOffset + ZONE_HEADER_SIZE > out.length) {
				throw new IllegalArgumentException("ZO subfile 0 out of bounds (offset " + headerOffset + ").");
			}
			patchOAZoneNumberBits(out, headerOffset, dstIndex);
		}
		return out;
	}

	/**
	 * Copies the MASTER's own row srcIndex over row dstIndex (preserving the
	 * master's byte 0x2A bit16-clear convention - the zone's own subfile-0
	 * header is deliberately NOT used here) and, if requested, patches the
	 * row's OAZoneNumber bits to dstIndex. In-place.
	 */
	public static void patchMasterRow(byte[] master, int srcIndex, int dstIndex, boolean patchOAZoneNumber) {
		int srcOff = srcIndex * ZONE_HEADER_SIZE;
		int dstOff = dstIndex * ZONE_HEADER_SIZE;
		if (srcOff < 0 || srcOff + ZONE_HEADER_SIZE > master.length || dstOff < 0 || dstOff + ZONE_HEADER_SIZE > master.length) {
			throw new IllegalArgumentException("Master table row out of bounds (src " + srcIndex + ", dst " + dstIndex + ", " + master.length + " bytes).");
		}
		System.arraycopy(master, srcOff, master, dstOff, ZONE_HEADER_SIZE);
		if (patchOAZoneNumber) {
			patchOAZoneNumberBits(master, dstOff, dstIndex);
		}
	}

	private static void patchOAZoneNumberBits(byte[] b, int headerOffset, int zoneNumber) {
		if (zoneNumber < 0 || zoneNumber > 0x7FF) {
			throw new IllegalArgumentException("Zone number " + zoneNumber + " does not fit in 11 bits.");
		}
		int flags = i32(b, headerOffset + UNKNOWN_FLAGS_OFFSET);
		flags = (flags & 0x001FFFFF) | (zoneNumber << OA_ZONE_NUMBER_SHIFT);
		putI32(b, headerOffset + UNKNOWN_FLAGS_OFFSET, flags);
	}

	private static void checkZOMagic(byte[] zo, String what) {
		if (zo == null || zo.length < 4 + 2 * 4) {
			throw new IllegalArgumentException(what + " is too short to be a ZO container.");
		}
		if ((zo[0] & 0xFF) != 0x5A || (zo[1] & 0xFF) != 0x4F) {
			throw new IllegalArgumentException(what + " is not a ZO container (dummy ZoneData entry?).");
		}
	}


}
