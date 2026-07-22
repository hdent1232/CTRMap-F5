package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * EXPERIMENTAL: appends a brand-new zone slot to the end of the ZoneData GARC
 * (ORAS only in v1).
 *
 * ORAS ZoneData layout (a/0/1/3, 538 entries pristine): entries 0..N-1 are
 * LZ11-compressed ZO containers (N == 536), entry N is the UNCOMPRESSED master
 * zone-header table (N rows x 0x38, row count implied purely by entry size)
 * and entry N+1 is the UNCOMPRESSED "EN" wild-encounter pack (u16 magic "EN" +
 * u16 count + u32 absolute offsets[count+1] + blob data; consecutive equal
 * offsets == "no wild data", which 386 retail zones have).
 *
 * Appending is therefore an INSERT-SHIFT: the new ZO takes over the master's
 * old GARC index, the master (grown by one row) shifts to old index + 1 and
 * the EN pack (grown by one empty blob) to old index + 2. All three land in
 * the zonedata workspace directory under their NUMERIC target names and go
 * through the normal GARC.packDirectory flow.
 *
 * Compression is content-sniffed (first byte 0x11), not stored in the GARC,
 * and packDirectory keeps the ORIGINAL entry's flag for indices below the
 * original entry count. The shifted slots are below it, so the new ZO is
 * stored PRE-LZ11-COMPRESSED (the old entry at its index - the master - was
 * uncompressed and packDirectory writes the file verbatim; on reopen the 0x11
 * byte sniffs compressed, matching zones 0..N-1) while the master file stays
 * raw for the same reason. Only the appended EN slot (index >= original
 * count) consults the compressionOverrides map, so appendZone() registers a
 * pending {newEnIndex: false} override that Workspace.packWorkspace() hands
 * to packDirectory.
 *
 * Whether the GAME accepts a grown ZoneData (hardcoded zone counts or entry
 * indices in code.bin are unknown) has never been proven by any fan hack -
 * callers MUST surface the experimental warning before invoking this.
 */
public class ZoneAppender {

	private static Map<Integer, Boolean> pendingZoneDataOverrides = null;

	/**
	 * The three rebuilt ZoneData payloads of an append, keyed to workspace
	 * file names: compressedZo -> "newIndex", master -> "newIndex + 1",
	 * en -> "newIndex + 2".
	 */
	public static class AppendPayloads {

		/** The new ZO, LZ11-compressed for verbatim storage in the shifted slot. */
		public byte[] compressedZo;
		/** The new ZO decompressed (OAZoneNumber patched) - for verification. */
		public byte[] newZo;
		/** Master table grown by one row (row newIndex = copy of row srcIndex, OAZoneNumber patched). */
		public byte[] master;
		/** EN pack grown by one empty blob (count + 1, all offsets shifted by 4). */
		public byte[] en;
	}

	/**
	 * Appends a new zone cloned from srcIndex to the current ORAS workspace.
	 * Reads the source ZO, master table and EN pack through the
	 * workspace-aware path (so prior saved edits are respected), writes the
	 * three shifted/grown files into the zonedata pack directory, persists
	 * them and registers the compression override for the appended EN slot.
	 *
	 * The GARC itself is NOT rewritten here - the caller must Pack Workspace
	 * (which reloads the archives) immediately afterwards; GARC.length is a
	 * constructor-only field and stays stale until then, which is also why
	 * only ONE append is allowed per pack cycle.
	 *
	 * @return the index of the new zone
	 */
	public static int appendZone(int srcIndex) throws IOException {
		if (!Workspace.isOA()) {
			throw new IOException("Adding new zones is ORAS-only in v1.");
		}
		GARC garc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (garc == null) {
			throw new IOException("No workspace is loaded (ZoneData archive unavailable).");
		}
		int newIndex = garc.length - 2; //current zone count; the master's current GARC index
		if (srcIndex < 0 || srcIndex >= newIndex) {
			throw new IOException("Source zone " + srcIndex + " out of range (0.." + (newIndex - 1) + ").");
		}
		File dir = Workspace.getExtractionDirectory(Workspace.ArchiveType.ZONE_DATA);
		File enOut = new File(dir, String.valueOf(newIndex + 2));
		if (Workspace.persist_paths.contains(enOut.getAbsolutePath())) {
			throw new IOException("An appended zone is already pending. Pack the workspace before adding another one.");
		}
		//resolve the CURRENT bytes through the workspace-aware path
		File srcFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, srcIndex);
		File masterFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, newIndex);
		File enFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, newIndex + 1);
		if (srcFile == null || masterFile == null || enFile == null) {
			throw new IOException("Could not extract the required ZoneData files from the workspace.");
		}
		AppendPayloads p = buildAppendPayloads(readAll(srcFile), readAll(masterFile), readAll(enFile), srcIndex, newIndex);
		//insert-shift: overwrite the old master slot with the new ZO, master and EN
		//move up by one. Write DECOMPRESSED bytes so the files stay loadable in the
		//editor (a compressed ZO would fail to parse); the pack applies the
		//compression per the overrides below.
		writeAll(masterFile, p.newZo); //file "newIndex" = the new zone (decompressed)
		writeAll(enFile, p.master); //file "newIndex + 1" = grown master
		writeAll(enOut, p.en); //file "newIndex + 2" = grown EN
		Workspace.addPersist(masterFile);
		Workspace.addPersist(enFile);
		Workspace.addPersist(enOut);
		if (pendingZoneDataOverrides == null) {
			pendingZoneDataOverrides = new HashMap<>();
		}
		pendingZoneDataOverrides.put(newIndex, Boolean.TRUE);      //new ZO: LZ11 like every zone
		pendingZoneDataOverrides.put(newIndex + 1, Boolean.FALSE); //master table: uncompressed
		pendingZoneDataOverrides.put(newIndex + 2, Boolean.FALSE); //EN pack: uncompressed
		return newIndex;
	}

	/**
	 * Hands the compression overrides of a pending append to the pack flow
	 * and clears them (they apply to exactly one packDirectory call).
	 * Returns null when no append is pending - packDirectory accepts that.
	 */
	public static Map<Integer, Boolean> consumePendingZoneDataOverrides() {
		Map<Integer, Boolean> m = pendingZoneDataOverrides;
		pendingZoneDataOverrides = null;
		return m;
	}

	/**
	 * Core byte transform (headless-testable, no filesystem access). Builds
	 * the three payloads of an append from the CURRENT source ZO, master
	 * table and EN pack. Validates everything - and self-checks the EN
	 * rebuild round-trip - BEFORE constructing anything, so a failure here
	 * leaves no partial state for the caller to clean up.
	 */
	public static AppendPayloads buildAppendPayloads(byte[] srcZo, byte[] master, byte[] en, int srcIndex, int newIndex) {
		if (srcIndex < 0 || srcIndex >= newIndex) {
			throw new IllegalArgumentException("Source zone " + srcIndex + " out of range (0.." + (newIndex - 1) + ").");
		}
		if (master.length != newIndex * ZoneCloner.ZONE_HEADER_SIZE) {
			throw new IllegalArgumentException("Master table is " + master.length + " bytes, expected " + newIndex + " x 0x38.");
		}
		validateEN(en, newIndex);
		//sanity self-check: rebuilding with the count unchanged must reproduce the input bit-for-bit
		if (!Arrays.equals(rebuildEN(en, newIndex, false), en)) {
			throw new IllegalArgumentException("EN pack rebuild round-trip self-check failed - refusing to touch the archive.");
		}
		AppendPayloads p = new AppendPayloads();
		p.newZo = ZoneCloner.cloneZoneBytes(srcZo, newIndex, true);
		p.compressedZo = LZ11.compress(p.newZo);
		p.master = new byte[master.length + ZoneCloner.ZONE_HEADER_SIZE];
		System.arraycopy(master, 0, p.master, 0, master.length);
		ZoneCloner.patchMasterRow(p.master, srcIndex, newIndex, true);
		p.en = rebuildEN(en, newIndex, true);
		return p;
	}

	/**
	 * Structural validation of an EN pack against the expected zone count.
	 * Layout: u16 magic "EN", u16 count, u32 absolute offsets[count + 1]
	 * (monotonic non-decreasing, first == end of the offset table, last ==
	 * file length), then the blob data.
	 */
	public static void validateEN(byte[] en, int expectedCount) {
		if (en == null || en.length < 8) {
			throw new IllegalArgumentException("EN pack too short (" + (en == null ? 0 : en.length) + " bytes).");
		}
		if (en[0] != 'E' || en[1] != 'N') {
			throw new IllegalArgumentException("EN pack has wrong magic (0x" + Integer.toHexString(en[0] & 0xFF) + Integer.toHexString(en[1] & 0xFF) + ").");
		}
		int count = (en[2] & 0xFF) | ((en[3] & 0xFF) << 8);
		if (count != expectedCount) {
			throw new IllegalArgumentException("EN pack count " + count + " != zone count " + expectedCount + ".");
		}
		int tableEnd = 4 + (count + 1) * 4;
		if (en.length < tableEnd) {
			throw new IllegalArgumentException("EN pack too short for its offset table (" + en.length + " < " + tableEnd + ").");
		}
		int prev = -1;
		for (int i = 0; i <= count; i++) {
			int off = readIntLE(en, 4 + i * 4);
			if (i == 0 && off != tableEnd) {
				throw new IllegalArgumentException("EN pack first offset 0x" + Integer.toHexString(off) + " != table end 0x" + Integer.toHexString(tableEnd) + ".");
			}
			if (off < prev) {
				throw new IllegalArgumentException("EN pack offsets not monotonic at index " + i + ".");
			}
			prev = off;
		}
		if (prev != en.length) {
			throw new IllegalArgumentException("EN pack end sentinel 0x" + Integer.toHexString(prev) + " != file length 0x" + Integer.toHexString(en.length) + ".");
		}
	}

	/**
	 * Rebuilds an EN pack from its own offset table and blobs; optionally
	 * appends one EMPTY blob for a new zone ("no wild data" - game-legal, 386
	 * retail zones have it). With appendEmpty the result is exactly 4 bytes
	 * longer (one more offset), every original offset is shifted by +4 and
	 * the two last offsets both equal the new file length; without it the
	 * result is byte-identical to a well-formed input.
	 */
	public static byte[] rebuildEN(byte[] en, int expectedCount, boolean appendEmpty) {
		return rebuildENMulti(en, expectedCount, appendEmpty ? 1 : 0);
	}

	/**
	 * Like {@link #rebuildEN} but appends {@code appendCount} empty ("no wild
	 * data") blobs at once - used when adding several zones in one shot. Every
	 * appended offset equals the (unchanged) data end, so the new blobs are
	 * zero-length. appendCount 0 reproduces the input byte-for-byte.
	 */
	public static byte[] rebuildENMulti(byte[] en, int expectedCount, int appendCount) {
		if (appendCount < 0) {
			throw new IllegalArgumentException("appendCount must be >= 0");
		}
		validateEN(en, expectedCount);
		int count = (en[2] & 0xFF) | ((en[3] & 0xFF) << 8);
		int[] offs = new int[count + 1];
		for (int i = 0; i <= count; i++) {
			offs[i] = readIntLE(en, 4 + i * 4);
		}
		int newCount = count + appendCount;
		int tableEnd = 4 + (newCount + 1) * 4;
		int dataLen = offs[count] - offs[0];
		byte[] out = new byte[tableEnd + dataLen]; //appended blobs are empty - contribute 0 bytes
		out[0] = 'E';
		out[1] = 'N';
		out[2] = (byte) newCount;
		out[3] = (byte) (newCount >> 8);
		int shift = tableEnd - offs[0];
		for (int i = 0; i <= count; i++) {
			writeIntLE(out, 4 + i * 4, offs[i] + shift);
		}
		for (int j = 1; j <= appendCount; j++) {
			writeIntLE(out, 4 + (count + j) * 4, offs[count] + shift); //empty blob -> points at data end
		}
		System.arraycopy(en, offs[0], out, tableEnd, dataLen);
		return out;
	}

	/**
	 * Payloads for a MULTI-zone append (the block-of-N layout that
	 * {@link ctrmap.formats.codepatch.ZoneLimitPatch} expects): {@code addCount}
	 * new ZO containers, the master table grown to {@code oldCount + addCount}
	 * rows, and the EN pack grown by {@code addCount} empty blobs.
	 */
	public static class MultiAppendPayloads {

		/** addCount new ZO containers (decompressed; OAZoneNumber patched to their own new index). */
		public byte[][] newZos;
		/** Master table grown to newCount rows. */
		public byte[] master;
		/** EN pack grown by addCount empty blobs. */
		public byte[] en;
		public int oldCount;
		public int addCount;
		public int newCount;
	}

	/**
	 * Core byte transform (headless, no filesystem) for appending several zones
	 * at once. All new zones are cloned from {@code srcIndex}; callers turn the
	 * "real" ones into their own maps afterwards and leave the spares as-is
	 * (they exist only to keep the master index a multiple of 4 - see
	 * ZoneLimitPatch). Validates everything and self-checks the EN round-trip
	 * before building, so a failure leaves no partial state.
	 */
	public static MultiAppendPayloads buildMultiAppendPayloads(byte[] srcZo, byte[] master, byte[] en, int srcIndex, int oldCount, int addCount) {
		if (addCount < 1) {
			throw new IllegalArgumentException("addCount must be >= 1");
		}
		if (srcIndex < 0 || srcIndex >= oldCount) {
			throw new IllegalArgumentException("Source zone " + srcIndex + " out of range (0.." + (oldCount - 1) + ").");
		}
		if (master.length != oldCount * ZoneCloner.ZONE_HEADER_SIZE) {
			throw new IllegalArgumentException("Master table is " + master.length + " bytes, expected " + oldCount + " x 0x38.");
		}
		validateEN(en, oldCount);
		if (!Arrays.equals(rebuildENMulti(en, oldCount, 0), en)) {
			throw new IllegalArgumentException("EN pack rebuild round-trip self-check failed - refusing to touch the archive.");
		}
		MultiAppendPayloads p = new MultiAppendPayloads();
		p.oldCount = oldCount;
		p.addCount = addCount;
		p.newCount = oldCount + addCount;
		p.newZos = new byte[addCount][];
		for (int i = 0; i < addCount; i++) {
			p.newZos[i] = ZoneCloner.cloneZoneBytes(srcZo, oldCount + i, true);
		}
		p.master = new byte[p.newCount * ZoneCloner.ZONE_HEADER_SIZE];
		System.arraycopy(master, 0, p.master, 0, master.length);
		for (int i = 0; i < addCount; i++) {
			ZoneCloner.patchMasterRow(p.master, srcIndex, oldCount + i, true);
		}
		p.en = rebuildENMulti(en, oldCount, addCount);
		return p;
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static void writeIntLE(byte[] b, int off, int value) {
		b[off] = (byte) (value & 0xFF);
		b[off + 1] = (byte) ((value >> 8) & 0xFF);
		b[off + 2] = (byte) ((value >> 16) & 0xFF);
		b[off + 3] = (byte) ((value >> 24) & 0xFF);
	}

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
