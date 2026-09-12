package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.LZ11;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.putI32;
import java.nio.file.Files;
import ctrmap.formats.containers.ContainerBytes;

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
 * count) consults the compressionOverrides map, so the append registers a
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

	// NOTE: the legacy single-zone appendZone(int) was REMOVED (2026-08-30): it
	// predates auto-forking and produced zones that silently SHARED their donor's
	// map geometry (the user's zones 536-539 came from it). All appends go
	// through appendZones(int, int) below, which forks every real zone at
	// creation - do not resurrect a creation path that skips GeometryForker.

	/**
	 * Result of a multi-zone append: the index of the first new (real) zone, how
	 * many real zones were added, and the total zone count M the matching code
	 * patch must target (== {@link ctrmap.formats.codepatch.ZoneLimitPatch#masterIndex}).
	 */
	public static class AppendResult {
		public int firstNewZone;   // index of the first added zone (== old zone count, 536)
		public int realZones;      // how many the user asked for
		public int spareZones;     // padding to keep M a multiple of 4
		public int newZoneCount;   // M = firstNewZone + realZones + spareZones
		/** What the created zones still SHARE with the zone they were cloned from. */
		public java.util.List<ZoneResource> shared = java.util.Collections.emptyList();
		/** The same, as the sentence the user was shown before any of it was written. */
		public String sharedWarning = "";
	}

	/**
	 * The resources {@link #appendZones} gives every zone it creates a PRIVATE copy
	 * of. Everything else in {@link ZoneResource} is still shared with the donor.
	 *
	 * <p>THIS SET IS THE POINT. A zone header points at four things and this used to
	 * fork one of them, with nothing anywhere recording that the other three were
	 * left behind - so the map was fixed, twice, while story text and script stayed
	 * shared and silent. Measured on the owner's game: zones 536-539, one append,
	 * all four still on the donor's story text and script. Naming the set here makes
	 * the gap a value the append can REFUSE on, and makes closing it later one edit
	 * rather than four.
	 */
	private static final java.util.EnumSet<ZoneResource> MADE_PRIVATE
			= java.util.EnumSet.of(ZoneResource.MAP);

	/**
	 * What a zone created by an append would still share with its donor: every
	 * {@link ZoneResource} this appender does not make private.
	 */
	public static java.util.List<ZoneResource> sharedAfterAppend() {
		java.util.List<ZoneResource> out = new java.util.ArrayList<>();
		for (ZoneResource res : ZoneResource.values()) {
			if (!MADE_PRIVATE.contains(res)) {
				out.add(res);
			}
		}
		return out;
	}

	/** The sentence a user must be shown before an append writes anything. */
	public static String sharedWarning(int srcIndex) {
		StringBuilder sb = new StringBuilder();
		for (ZoneResource res : sharedAfterAppend()) {
			sb.append(sb.length() == 0 ? "" : ", ").append(res.label);
		}
		if (sb.length() == 0) {
			return "";
		}
		return "Each new zone gets its own map, but will SHARE its " + sb + " with zone "
			+ srcIndex + ".\nEditing any of those in a new zone changes zone " + srcIndex
			+ " as well, and every other\nzone already sharing them.";
	}

	/**
	 * Appends {@code newRealZones} new zones to the current ORAS workspace, plus
	 * enough spare slots to raise the total to M = the next multiple of 4, so it
	 * matches the {@link ctrmap.formats.codepatch.ZoneLimitPatch} code patch for
	 * the same N. All new zones (real + spare) are cloned from {@code srcIndex};
	 * the caller turns the real ones into their own maps afterwards. As with the
	 * single-zone path, the GARC is NOT rewritten here - Pack Workspace must run
	 * immediately after, and only one append is allowed per pack cycle.
	 *
	 * <p>Generate the paired code patch with
	 * {@code ZoneLimitPatch.buildIPS(newRealZones)} and deploy the resulting
	 * code.ips (Azahar: load/mods/&lt;titleid&gt;/exefs/; Luma: luma/titles/&lt;titleid&gt;/).
	 */
	/**
	 * REFUSES, and says what a created zone would share. This is the two-argument
	 * form and it exists to be refused: an append that cannot make its zones
	 * independent must not happen because a caller did not think to ask.
	 *
	 * <p>WHY A REFUSAL AND NOT A WARNING. A warning is a detector - it reports
	 * what already went wrong and relies on somebody reading it. The zones this
	 * editor created shared their donor's story text and script for two releases
	 * with nothing said, and the owner found it by opening a settings field by
	 * chance. The only thing that closes that class is refusing at the point the
	 * zone is MADE, so the answer has to be a decision somebody took rather than
	 * a message somebody missed.
	 *
	 * <p>When {@link #sharedAfterAppend()} is empty - when every resource is made
	 * private - this stops refusing on its own and becomes an ordinary call. The
	 * refusal shrinks as the capability grows, which is the right way round.
	 */
	public static AppendResult appendZones(int newRealZones, int srcIndex) throws IOException {
		java.util.List<ZoneResource> shared = sharedAfterAppend();
		if (!shared.isEmpty()) {
			throw new IOException(sharedWarning(srcIndex)
				+ "\n\nCTRMap cannot give a new zone its own " + shared + " yet, so it will not"
				+ " create one\nquietly. Use the form that takes an explicit acknowledgement, after"
				+ " showing the\nuser this sentence.");
		}
		return appendZones(newRealZones, srcIndex, true);
	}

	/**
	 * Appends the zones, having been told the caller has shown the user what they
	 * will share.
	 *
	 * @param acceptShared true when the user has SEEN {@link #sharedWarning} and
	 *        chosen to go on. False refuses, with that sentence.
	 */
	public static AppendResult appendZones(int newRealZones, int srcIndex, boolean acceptShared) throws IOException {
		java.util.List<ZoneResource> stillShared = sharedAfterAppend();
		if (!acceptShared && !stillShared.isEmpty()) {
			throw new IOException(sharedWarning(srcIndex));
		}
		if (!Workspace.isValid()) {
			throw new IOException("No workspace is loaded, so there is no game to ask.");
		}
		ctrmap.gamedef.GameProfile prof = Workspace.profile();
		if (!prof.supports(ctrmap.gamedef.GameProfile.Feature.ZONE_APPEND)) {
			//names the game and the capability. "ORAS-only in v1" told a user
			//who had opened X/Y what the editor could do, not what THEY could
			//do, and said exactly the same thing to Sun/Moon
			throw new IOException("Adding zones is not available for " + prof.displayName() + "."
					+ "\n\nZones past the ones a game ships need that game's zone-table limit"
					+ " found in its executable and raised by a code patch. Both were measured"
					+ " for Omega Ruby / Alpha Sapphire only; neither has been established for "
					+ prof.displayName() + ", so CTRMap cannot write a zone this game would"
					+ " never load.");
		}
		if (newRealZones < 1) {
			throw new IOException("Must add at least one zone.");
		}
		GARC garc = Workspace.getArchive(ArchiveType.ZONE_DATA);
		if (garc == null) {
			throw new IOException("No workspace is loaded (ZoneData archive unavailable).");
		}
		int oldCount = ZoneTables.zoneCount(garc);            // current zone count (master's GARC index)
		int m = ctrmap.formats.codepatch.ZoneLimitPatch.masterIndex(newRealZones);
		int addCount = m - oldCount;
		if (oldCount != ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES) {
			throw new IOException("ZoneData already has " + oldCount + " zones; the code patch assumes a stock 536-zone base. Revert first.");
		}
		if (srcIndex < 0 || srcIndex >= oldCount) {
			throw new IOException("Source zone " + srcIndex + " out of range (0.." + (oldCount - 1) + ").");
		}
		File dir = Workspace.getExtractionDirectory(ArchiveType.ZONE_DATA);
		File enOut = new File(dir, String.valueOf(m + 1));    // the new EN slot
		if (Workspace.isPendingArtifact(enOut)) {
			throw new IOException("An appended zone is already pending. Pack the workspace before adding more.");
		}
		File srcFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, srcIndex);
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, oldCount);
		if (srcFile == null || masterFile == null) {
			throw new IOException("Could not extract the required ZoneData files from the workspace.");
		}
		// Master respects saved zone-header edits via the workspace file, but fall
		// back to the authoritative GARC bytes if it is the wrong structure (a
		// reverted single-zone append can leave a grown-master artifact behind).
		byte[] masterBytes = Files.readAllBytes(masterFile.toPath());
		if (masterBytes.length != oldCount * ZoneCloner.ZONE_HEADER_SIZE) {
			masterBytes = garc.getDecompressedEntry(oldCount);
		}
		// EN pack source: prefer a PENDING encounter edit (persisted workspace file
		// that validates - the encounter editor writes those), else the GARC. Never
		// trust a non-persisted extraction file: a stale one in the EN slot (from
		// the old reverted single-zone append) produced "EN pack has wrong magic".
		byte[] enBytes = null;
		File enWs = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, oldCount + 1);
		if (Workspace.isPendingArtifact(enWs)) {
			try {
				byte[] cand = Files.readAllBytes(enWs.toPath());
				validateEN(cand, oldCount);
				enBytes = cand;
			} catch (RuntimeException stale) {
				//fall through to the GARC
			}
		}
		if (enBytes == null) {
			enBytes = garc.getDecompressedEntry(oldCount + 1);
		}
		MultiAppendPayloads p = buildMultiAppendPayloads(Files.readAllBytes(srcFile.toPath()), masterBytes, enBytes, srcIndex, oldCount, addCount);

		// Auto-fork geometry: give EVERY new zone its OWN private map so editing one
		// does not change the zone it was cloned from (what users expect - a new zone
		// is independent by default). Mutates p.newZos[i] (repointed header) and
		// p.master (repointed row) in place and adds the region/matrix copies to the
		// workspace. The spares that pad the count out to a multiple of 4 are forked
		// too and then BLANKED: a slot nobody asked for opens empty rather than as a
		// second copy of the donor. Up to 1.0.1 they kept the donor map instead, which
		// is what CtrmapMainframe.repairSharedPaddingZones exists to undo.
		GeometryForker.forkAppendedZones(p.newZos, p.master, oldCount, newRealZones);

		if (pendingZoneDataOverrides == null) {
			pendingZoneDataOverrides = new HashMap<>();
		}
		// new ZOs occupy entries oldCount..m-1 (decompressed on disk, LZ11 on pack)
		for (int i = 0; i < addCount; i++) {
			File f = new File(dir, String.valueOf(oldCount + i));
			Files.write(f.toPath(), p.newZos[i]);
			Workspace.addPersist(f);
			pendingZoneDataOverrides.put(oldCount + i, Boolean.TRUE);
		}
		// master table shifts to entry m, EN to entry m+1 (both uncompressed)
		File masterOut = new File(dir, String.valueOf(m));
		Files.write(masterOut.toPath(), p.master);
		Files.write(enOut.toPath(), p.en);
		Workspace.addPersist(masterOut);
		Workspace.addPersist(enOut);
		pendingZoneDataOverrides.put(m, Boolean.FALSE);
		pendingZoneDataOverrides.put(m + 1, Boolean.FALSE);

		AppendResult r = new AppendResult();
		r.shared = stillShared;
		r.sharedWarning = sharedWarning(srcIndex);
		r.firstNewZone = oldCount;
		r.realZones = newRealZones;
		r.spareZones = addCount - newRealZones;
		r.newZoneCount = m;
		return r;
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
		int count = ContainerBytes.count(en);
		if (count != expectedCount) {
			throw new IllegalArgumentException("EN pack count " + count + " != zone count " + expectedCount + ".");
		}
		int tableEnd = 4 + (count + 1) * 4;
		if (en.length < tableEnd) {
			throw new IllegalArgumentException("EN pack too short for its offset table (" + en.length + " < " + tableEnd + ").");
		}
		int prev = -1;
		for (int i = 0; i <= count; i++) {
			int off = i32(en, 4 + i * 4);
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
	/**
	 * The EN pack with only its first {@code keepCount} blobs - the mirror of
	 * {@link #rebuildENMulti}, and the half that was missing.
	 *
	 * <p>WHY IT HAD TO EXIST. Removing the added zones truncates the master
	 * zone-header table back to its stock rows and preserved the EN pack
	 * BYTE FOR BYTE - which its own comment said out loud, as if that were the
	 * safe choice. It is not: the EN pack carries its own count, and the append
	 * grew it. So a revert left a 536-row table beside a 540-entry EN pack, and
	 * the next append refused with "EN pack count 540 != zone count 536" -
	 * leaving the user unable to add zones again, and unable to see why, since
	 * nothing in the revert had said it was leaving the tail behind.
	 *
	 * <p>The dropped blobs are the removed zones' wild-encounter data, which is
	 * what removing those zones means. The blobs that stay are copied verbatim,
	 * so a zone the user kept comes back byte-identical.
	 */
	public static byte[] truncateEN(byte[] en, int expectedCount, int keepCount) {
		if (keepCount < 0 || keepCount > expectedCount) {
			throw new IllegalArgumentException("keepCount " + keepCount + " out of range (0.."
				+ expectedCount + ")");
		}
		validateEN(en, expectedCount);
		int count = ContainerBytes.count(en);
		int[] offs = new int[count + 1];
		for (int i = 0; i <= count; i++) {
			offs[i] = i32(en, 4 + i * 4);
		}
		int tableEnd = 4 + (keepCount + 1) * 4;
		int dataLen = offs[keepCount] - offs[0];
		byte[] out = new byte[tableEnd + dataLen];
		out[0] = 'E';
		out[1] = 'N';
		out[2] = (byte) keepCount;
		out[3] = (byte) (keepCount >> 8);
		int shift = tableEnd - offs[0];
		for (int i = 0; i <= keepCount; i++) {
			putI32(out, 4 + i * 4, offs[i] + shift);
		}
		System.arraycopy(en, offs[0], out, tableEnd, dataLen);
		//the same self-check the append makes before it touches an archive: what
		//came out must be a structurally valid pack of exactly the count claimed
		validateEN(out, keepCount);
		return out;
	}

	public static byte[] rebuildENMulti(byte[] en, int expectedCount, int appendCount) {
		if (appendCount < 0) {
			throw new IllegalArgumentException("appendCount must be >= 0");
		}
		validateEN(en, expectedCount);
		int count = ContainerBytes.count(en);
		int[] offs = new int[count + 1];
		for (int i = 0; i <= count; i++) {
			offs[i] = i32(en, 4 + i * 4);
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
			putI32(out, 4 + i * 4, offs[i] + shift);
		}
		for (int j = 1; j <= appendCount; j++) {
			putI32(out, 4 + (count + j) * 4, offs[count] + shift); //empty blob -> points at data end
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


}
