package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.nio.file.Files;

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
		public int firstNewZone;   // index of the first added zone (== old zone count; 536 on a stock game)
		public int realZones;      // how many the user asked for
		public int spareZones;     // padding to keep M a multiple of 4
		public int newZoneCount;   // M = firstNewZone + realZones + spareZones
		/**
		 * The N the code patch is built for: every zone past the stock base, this append's and the
		 * ones before it, spares included - NOT realZones, which is only this append's. On a second
		 * append a patch built from realZones names a master index the archive no longer has.
		 */
		public int patchZones;
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
			= java.util.EnumSet.of(ZoneResource.MAP, ZoneResource.AREA, ZoneResource.TEXT);

	/** The resources an append gives every zone it creates its own copy of. */
	public static java.util.Set<ZoneResource> madePrivate() {
		return java.util.Collections.unmodifiableSet(MADE_PRIVATE);
	}

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
	 * <p>Appends {@code newRealZones} new zones to the current ORAS workspace, plus
	 * enough spare slots to raise the total to M = the next multiple of 4, so it
	 * matches the {@link ctrmap.formats.codepatch.ZoneLimitPatch} code patch for
	 * the same N. All new zones (real + spare) are cloned from {@code srcIndex};
	 * the caller turns the real ones into their own maps afterwards. The GARC is
	 * NOT rewritten here - Pack Workspace must run immediately after, and only one
	 * append is allowed per pack cycle.
	 *
	 * <p>Generate the paired code patch with
	 * {@code ZoneLimitPatch.buildIPS(newRealZones, code)} against the user's own code.bin and deploy
	 * the resulting code.ips (Azahar: load/mods/&lt;titleid&gt;/exefs/; Luma: luma/titles/&lt;titleid&gt;/).
	 *
	 * @param acceptShared true when the user has SEEN {@link #sharedWarning} and
	 *        chosen to go on. False refuses, with that sentence.
	 */
	public static AppendResult appendZones(int newRealZones, int srcIndex, boolean acceptShared) throws IOException {
		//ONE UNIT FOR THE WHOLE APPEND (F040, F227): the map fork staged megabytes of regions and
		//matrices before the area fork checked its id budget, so running out of area ids left
		//them all behind with nothing pointing at them - and any later refusal did the same
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Add zones", Workspace.session())) {
			AppendResult r = appendZonesWrites(newRealZones, srcIndex, acceptShared);
			unit.commit();
			return r;
		}
	}

	private static AppendResult appendZonesWrites(int newRealZones, int srcIndex, boolean acceptShared) throws IOException {
		java.util.List<ZoneResource> stillShared = sharedAfterAppend();
		//asked BEFORE anything is written, like the area budget: a game folder with no
		//STORYTEXT archive is a real situation, and finding out at the fork would leave
		//the zones already appended and half independent
		if (MADE_PRIVATE.contains(ZoneResource.TEXT) && !TextForker.available(Workspace.session())) {
			throw new IOException("This game folder has no STORYTEXT archive, so a new zone cannot"
				+ " be given its own dialogue file - and it would silently share the donor's."
				+ "\n\nPoint CTRMap at a complete game folder, or remove TEXT from the resources"
				+ " the append makes private.");
		}
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
		//GROWS FROM WHAT IS THERE (F039): a game a previous append grew is grown again - master and
		//EN move up once more - rather than refused until every added zone was removed
		int m;
		try {
			m = ctrmap.formats.codepatch.ZoneLimitPatch.masterIndexFrom(oldCount, newRealZones);
		} catch (IllegalArgumentException cannot) {
			throw new IOException("Cannot add " + newRealZones + " zone(s) here: " + ctrmap.util.Bytes.reason(cannot) + ".", cannot);
		}
		int addCount = m - oldCount;
		if (srcIndex < 0 || srcIndex >= oldCount) {
			throw new IOException("Source zone " + srcIndex + " out of range (0.." + (oldCount - 1) + ").");
		}
		File dir = Workspace.getExtractionDirectory(ArchiveType.ZONE_DATA);
		File enOut = new File(dir, String.valueOf(m + 1));    // the new EN slot
		if (Workspace.isPendingArtifact(enOut)) {
			throw new IOException("An appended zone is already pending. Pack the workspace before adding more.");
		}
		File srcFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, srcIndex);
		if (srcFile == null) {
			throw new IOException("Could not extract the required ZoneData files from the workspace.");
		}
		// Through the one reader (F013): the staged table when it is right, the archive's when
		// the workspace holds a stale extraction (a reverted single-zone append can leave a
		// grown-master artifact behind) - and a refusal for a STAGED table of the wrong size,
		// which this used to replace with the archive's copy, dropping the edit
		byte[] masterBytes = MasterTable.read(Workspace.session()).bytes();
		// EN pack source: prefer a PENDING encounter edit (persisted workspace file
		// that validates - the encounter editor writes those), else the GARC. Never
		// trust a non-persisted extraction file: a stale one in the EN slot (from
		// the old reverted single-zone append) produced "EN pack has wrong magic".
		byte[] enBytes = null;
		File enWs = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, oldCount + 1);
		if (Workspace.isPendingArtifact(enWs)) {
			try {
				byte[] cand = Files.readAllBytes(enWs.toPath());
				ctrmap.formats.encounters.EnPack.parse(cand, oldCount);
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
		// is what PaddingZoneRepair.repairOnOpen exists to undo.
		//the grown table as the forkers repoint it: the same bytes, asked and set through the one class
		MasterTable grown = MasterTable.detached(p.master, p.newCount);
		GeometryForker.forkAppendedZones(p.newZos, grown, oldCount, newRealZones);

		// ...and its OWN AREA, which is the atmosphere, the water animations, the prop
		// registry and the NPC models. Sharing one meant a fog edit in a new zone
		// changed the zone it was cloned from and every other zone on that area - and
		// nothing said so until the user happened to open Fog & lighting, which is how
		// it was found. EVERY created zone, spares included: a spare is a zone somebody
		// can clone into later, and it is the same argument as the map.
		//
		// The budget is real and small - area ids are an 8-bit index and retail uses 229
		// of the 256 - so forkAppendedAreas checks it for the WHOLE batch before it
		// writes anything, rather than running out half way and leaving two of four new
		// zones private.
		int[] created = new int[addCount];
		for (int i = 0; i < addCount; i++) {
			created[i] = oldCount + i;
		}
		AreaForker.forkAppendedAreas(p.newZos, grown, created);

		// ...and its own STORY TEXT, so writing dialogue for a new zone stops rewriting
		// the donor's. Copied rather than emptied: the zone's SCRIPT cannot be forked
		// (CTRMap does not manage that archive), so every created zone runs the donor's
		// events, and those ask for line numbers. An empty text file under a script that
		// wants line 12 misbehaves in game; a copy keeps the pair consistent, and
		// independence is what this is for, not emptiness.
		TextForker.forkAppendedTexts(Workspace.session(), p.newZos, grown, created);

		WorkspaceSession session = Workspace.session();
		// new ZOs occupy entries oldCount..m-1 (decompressed on disk, LZ11 on pack)
		for (int i = 0; i < addCount; i++) {
			File f = new File(dir, String.valueOf(oldCount + i));
			WorkspaceUnit.write(f, p.newZos[i]);
			Workspace.addPersist(f);
			session.registerPendingCompression(ArchiveType.ZONE_DATA, oldCount + i, true);
		}
		// master table shifts to entry m, EN to entry m+1 (both uncompressed)
		File masterOut = new File(dir, String.valueOf(m));
		WorkspaceUnit.write(masterOut, p.master);
		WorkspaceUnit.write(enOut, p.en);
		Workspace.addPersist(masterOut);
		Workspace.addPersist(enOut);
		session.registerPendingCompression(ArchiveType.ZONE_DATA, m, false);
		session.registerPendingCompression(ArchiveType.ZONE_DATA, m + 1, false);

		AppendResult r = new AppendResult();
		r.shared = stillShared;
		r.sharedWarning = sharedWarning(srcIndex);
		r.firstNewZone = oldCount;
		r.realZones = newRealZones;
		r.spareZones = addCount - newRealZones;
		r.newZoneCount = m;
		r.patchZones = m - ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES;
		return r;
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
		ctrmap.formats.encounters.EnPack pack = ctrmap.formats.encounters.EnPack.parse(en, oldCount);
		if (!Arrays.equals(pack.bytes(), en)) {
			throw new IllegalArgumentException("EN pack rebuild round-trip self-check failed - refusing to touch the archive.");
		}
		MultiAppendPayloads p = new MultiAppendPayloads();
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
		p.en = pack.grownBy(addCount).bytes();
		return p;
	}


}
