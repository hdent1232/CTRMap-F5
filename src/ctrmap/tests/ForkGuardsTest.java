package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.GeometryForker;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.WorkspaceIntegrity;
import ctrmap.humaninterface.AreaForkPrompt;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Forking a zone away from the data it shares must leave the archives able to
 * answer for it.
 *
 * <p>An AREA fork appends a private AreaData entry and the NPC registry the
 * engine reads with the SAME id. AreaData carries one entry more than the
 * registry archive - index 228 is the engine's per-area table, not an area - so
 * the new id (229) was one past the end of NPCRegistries, and
 * {@link ctrmap.formats.garc.GARC#packDirectory} silently renumbered the file
 * called "229" to the first free slot, 228. Both dialogs reported success and
 * the editor rendered the zone from the staged workspace file, but in the game
 * the zone's area had no registry behind it and it could not load. A second
 * fork accidentally back-filled 229 and left the counts one apart the other
 * way; the third was refused forever with "AreaData and the NPC registry are
 * out of step", and area forking - the gate on every atmosphere, prop and
 * NPC-model edit - was dead after two uses.
 *
 * <p>Measured on a scratch copy of the dump: one fork gave AreaData=230
 * NPCReg=229 with the clone sitting at 228 and index 229 missing.
 *
 * <p>Runs three consecutive forks, each followed by a real pack, because the
 * damage only showed on the second and third.
 *
 * <p>A GEOMETRY fork had the opposite problem: no guard against being run on a
 * zone that already owns its map, so every run appended another copy of every
 * region and orphaned the last one, silently, while reporting success.
 *
 * Usage: java ctrmap.tests.ForkGuardsTest &lt;romfs-root&gt;
 */
public class ForkGuardsTest {

	/** Zones with three different retail areas; any three would do. */
	private static final int[] ZONES = {15, 20, 30};
	/** Mauville: its map matrix is already its own in the retail game. */
	private static final int PRIVATE_ZONE = 15;
	/** Fallarbor Town: shares matrix 8 with Routes 111, 112, 113 and 114. */
	private static final int SHARED_ZONE = 10;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);
		areaForkKeepsTheRegistryAligned();
		geometryForkOnlyRunsWhenItIsNeeded();
		theForkPromptHandsBackWhatItDid();
		gappedAppendIsRefused();
		aRegistryAlreadyPastTheNewAreaIdIsRefusedOutLoud();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Three forks in a row, each packed, each leaving a game that can load. */
	static void areaForkKeepsTheRegistryAligned() throws Exception {
		for (int pass = 0; pass < ZONES.length; pass++) {
			int zone = ZONES[pass];
			int oldArea = AreaForker.currentArea(zone);
			byte[] srcRegistry = registry(oldArea);
			AreaForker.ForkResult r;
			try {
				r = AreaForker.forkArea(zone);
			} catch (Exception ex) {
				System.out.println("  FAIL: fork " + (pass + 1) + " of zone " + zone
						+ " was refused: " + ex.getMessage());
				fails++;
				return;
			}
			pack();
			int areas = Workspace.getArchive(Workspace.ArchiveType.AREA_DATA).length, regs = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length;
			check(regs == areas, "fork " + (pass + 1) + ": the registry archive covers every area"
					+ " (AreaData " + areas + ", NPCRegistries " + regs + ")");
			check(r.newArea < regs, "fork " + (pass + 1) + ": the registry the engine reads for area "
					+ r.newArea + " exists");
			check(Arrays.equals(registry(r.newArea), srcRegistry),
					"fork " + (pass + 1) + ": and it is the clone of area " + oldArea);
			List<String> bad = WorkspaceIntegrity.check(Workspace.session(), true);
			check(bad.isEmpty(), "fork " + (pass + 1) + ": the packed game passes every"
					+ " cross-archive invariant " + bad);
		}
	}

	/**
	 * A geometry fork on a zone that already owns its map must do nothing.
	 *
	 * <p>Every run appended a fresh copy of every region in the zone's matrix
	 * plus a new matrix and reported "Zone N now has private map geometry" as
	 * if it had been needed, orphaning the previous copies - nothing ever
	 * reclaims them. Measured: four forks of zone 15, whose map is ALREADY
	 * private in the retail game, grew FieldData from 857 to 861 regions and by
	 * 1.17 MB, and three of the four appended matrices were referenced by no
	 * zone at all. Iterating on a test zone does this every cycle.
	 */
	static void geometryForkOnlyRunsWhenItIsNeeded() throws Exception {
		check(GeometryForker.matrixSharers(PRIVATE_ZONE) == 0,
				"zone " + PRIVATE_ZONE + "'s map is already its own in the retail game");
		int regions = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length, matrices = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length;
		GeometryForker.ForkResult r = GeometryForker.ensurePrivate(PRIVATE_ZONE);
		pack();
		check(!r.forked, "so it reports that it forked nothing");
		check(Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length == regions && Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length == matrices,
				"giving an already-private zone its own map appends nothing (FieldData "
				+ regions + " -> " + Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length + ", MapMatrix " + matrices + " -> "
				+ Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length + ")");

		check(GeometryForker.matrixSharers(SHARED_ZONE) > 0,
				"zone " + SHARED_ZONE + " shares its map with other zones");
		r = GeometryForker.ensurePrivate(SHARED_ZONE);
		pack();
		check(r.forked && Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length > regions,
				"a zone that shares its map does get a private copy");
		check(GeometryForker.matrixSharers(SHARED_ZONE) == 0, "and stops sharing");

		regions = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length;
		matrices = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length;
		r = GeometryForker.ensurePrivate(SHARED_ZONE);
		pack();
		check(!r.forked && Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length == regions && Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length == matrices,
				"forking the same zone a second time appends nothing (FieldData " + regions
				+ " -> " + Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA).length + ", MapMatrix " + matrices + " -> "
				+ Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX).length + ")");
		check(Arrays.equals(r.srcRegions, r.newRegions),
				"and hands back the regions the zone is already using");
	}

	/**
	 * The shared-area prompt hands its outcome back to the caller, instead of
	 * leaving it in a static for whoever packs next.
	 *
	 * <p>{@code AreaForkPrompt.ensurePrivate} used to return a bare area id and
	 * remember "I forked" in {@code lastForked}, which {@code packIfForked}
	 * read later. Three dialogs call the first; two of them call the second.
	 * The third - the tile painter's Apply - packs on its own, so its fork left
	 * the flag standing, and the next atmosphere or ripple edit packed the
	 * workspace for a fork it never made. The result now travels with the
	 * caller, exactly as {@link GeometryForker#ensurePrivate}'s does, and a
	 * caller that forked nothing cannot be told otherwise by anyone.
	 *
	 * <p>Drives the prompt headless through {@link Ui#record}: every answer the
	 * dialog can give, then the pack-if-forked decision on a result that
	 * forked nothing while a fork somebody else made and packed is fresh.
	 */
	static void theForkPromptHandsBackWhatItDid() throws Exception {
		int zone = -1;
		for (int z = 0; z < zoneData().length - 2 && zone < 0; z++) {
			if (Arrays.binarySearch(ZONES, z) < 0 && AreaForker.areaSharers(z) > 0) {
				zone = z;
			}
		}
		check(zone >= 0, "a zone that still shares its area: " + zone);
		final int area = AreaForker.currentArea(zone);
		final int areas = areaData().length;
		final String edit = "a test edit";

		//nobody there to answer: that is a cancel, not consent
		List<String> said = Ui.record();
		AreaForker.ForkResult r = AreaForkPrompt.ensurePrivate(null, zone, area, edit);
		Ui.stopRecording();
		check(said.size() == 1 && said.get(0).contains("SHARES its area"),
				"a zone that shares its area is asked: " + said);
		check(r == null, "and an unanswered question hands back nothing to edit with: " + r);

		Ui.record("Cancel");
		r = AreaForkPrompt.ensurePrivate(null, zone, area, edit);
		Ui.stopRecording();
		check(r == null, "Cancel hands back nothing to edit with: " + r);

		Ui.record("Edit the shared area anyway");
		r = AreaForkPrompt.ensurePrivate(null, zone, area, edit);
		Ui.stopRecording();
		check(r != null && !r.forked && r.newArea == area && r.oldArea == area && r.zoneIndex == zone,
				"declining the fork hands back the shared area " + area + " as it is, forked=false");
		check(AreaForker.currentArea(zone) == area && areaData().length == areas,
				"and none of the three answers so far wrote anything (zone " + zone + " still on area "
				+ AreaForker.currentArea(zone) + ", AreaData " + areaData().length + ")");

		Ui.record("Give this zone its own area");
		r = AreaForkPrompt.ensurePrivate(null, zone, area, edit);
		Ui.stopRecording();
		check(r != null && r.forked && r.oldArea == area && r.newArea != area && r.newArea == areas,
				"accepting forks, and the result says so: area " + area + " -> "
				+ (r == null ? "null" : r.newArea + ", forked=" + r.forked));
		check(r != null && AreaForker.currentArea(zone) == r.newArea, "and the zone now points at the copy");

		//this caller packs on its own, as the tile painter's Apply does, and
		//never calls packIfForked - the shape that left the static standing
		pack();
		check(areaData().length == areas + 1 && AreaForker.areaSharers(zone) == 0,
				"packed by the caller itself: AreaData " + areas + " -> " + areaData().length
				+ ", zone " + zone + " shares with nobody");

		//an unrelated caller now asks about a zone that forks nothing. Its
		//pack-if-forked must run onDone at once and pack nothing: the fork above
		//is not its news, and there is no longer anywhere for it to be told
		final boolean[] ran = {false};
		AreaForker.ForkResult none = AreaForker.forkIfShared(zone);
		check(!none.forked, "a zone that owns its area forks nothing when asked again");
		//a pack reloads every archive handle, so the handle's identity is the
		//cheap, exact witness of "nothing was packed" - a headless pack waits
		//for its worker, so timing would not tell the two apart
		ctrmap.formats.garc.GARC handle = areaData();
		AreaForkPrompt.packIfForked(none, () -> ran[0] = true);
		check(ran[0], "packIfForked on a result that forked nothing runs onDone");
		check(areaData() == handle, "and packs nothing, whatever somebody else forked a moment ago"
				+ " (the AreaData handle was " + (areaData() == handle ? "kept" : "reloaded by a pack") + ")");

		//and the prompt itself does not ask about a private area
		said = Ui.record();
		r = AreaForkPrompt.ensurePrivate(null, zone, r.newArea, edit);
		Ui.stopRecording();
		check(said.isEmpty() && r != null && !r.forked && r.newArea == r.oldArea
				&& r.newArea == AreaForker.currentArea(zone),
				"a zone that owns its area is not asked, and gets that area back unforked: " + said);
	}

	/**
	 * The mechanism underneath, in isolation: a file named past the end of an
	 * archive must be refused, not quietly written somewhere else. This is what
	 * turned the fork's one-slot mistake into a game that would not load, and
	 * it would do the same to any other appender.
	 */
	static void gappedAppendIsRefused() throws Exception {
		File dir = Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES);
		int before = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length;
		int past = before + 1;
		File gapped = new File(dir, String.valueOf(past));
		Files.write(gapped.toPath(), new byte[]{1, 2, 3, 4});
		Workspace.addPersist(gapped);
		try {
			pack();
			check(false, "packing a file named past the end of the archive is refused");
		} catch (IOException ex) {
			check(ex.getMessage().contains(String.valueOf(past)),
					"packing a file named past the end of the archive is refused: " + ex.getMessage());
		}
		check(Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length == before,
				"and the archive did not grow into the slot it would have been renumbered to");
		Workspace.persistPaths().remove(gapped.getAbsolutePath());
		gapped.delete();
	}

	/**
	 * A fork onto an area id the NPC registry archive ALREADY reaches past must
	 * be refused, out loud, having staged nothing.
	 *
	 * <p>The engine indexes the registry by area id, so the two archives have
	 * to stay in step: AreaData ends one past the last area (index 228 is the
	 * per-area table), and the registry archive ends at the last area. When the
	 * registry runs further than that, the two are already out of step and a
	 * fork cannot repair it - the clone would land at an id something else
	 * already occupies. The guard at AreaForker:247 says exactly that.
	 *
	 * <p>Nothing asserted the sentence. Deleting the throw, or swallowing it
	 * into a benign return, both left the battery green - and the first of them
	 * is worse than no guard at all: the fork carries on, writes a private area
	 * over an id the registry archive has already used, repoints the zone at
	 * it, and reports success. The user gets a zone quietly wearing somebody
	 * else's NPCs.
	 *
	 * <p>Pushes the registry archive two entries past AreaData with ordinary
	 * legal tail appends - the archive itself refuses a gap, so this is the
	 * only way to build the state - and then asks for a fork.
	 */
	static void aRegistryAlreadyPastTheNewAreaIdIsRefusedOutLoud() throws Exception {
		final int ZONE = 40; //not one of ZONES above, so it has never been forked here
		File npDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES);
		while (Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length <= Workspace.getArchive(Workspace.ArchiveType.AREA_DATA).length + 1) {
			File tail = new File(npDir, String.valueOf(Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length));
			Files.write(tail.toPath(), new byte[]{0, 0, 0, 0}); //a registry naming no models
			Workspace.addPersist(tail);
			pack();
		}
		int areas = Workspace.getArchive(Workspace.ArchiveType.AREA_DATA).length, regs = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).length;
		check(regs > areas + 1, "the archives are out of step: AreaData " + areas
				+ ", NPCRegistries " + regs + " (the registry reaches past the id a fork would use)");

		int areaBefore = AreaForker.currentArea(ZONE);
		File adOut = new File(Workspace.getExtractionDirectory(Workspace.ArchiveType.AREA_DATA),
				String.valueOf(areas));
		AreaForker.ForkResult r = null;
		Throwable thrown = null;
		try {
			r = AreaForker.forkArea(ZONE);
		} catch (Throwable t) {
			thrown = t;
		}
		check(thrown instanceof IOException,
				"forking onto an id the registry already reaches past is refused, as an"
				+ " IOException: " + thrown);
		String said = thrown == null ? "" : String.valueOf(thrown.getMessage());
		check(said.contains("already reaches past the new area id " + areas),
				"the refusal names the id it would have used: " + said);
		check(said.contains("out of step"), "and says the two archives disagree: " + said);
		check(r == null, "and hands back no fork: " + r);

		//and the damage did not happen: nothing staged, nothing repointed
		check(!adOut.exists(), "no private area copy was written for the refused fork ("
				+ adOut.getName() + ")");
		check(!Workspace.persistPaths().contains(adOut.getAbsolutePath()),
				"and nothing was marked pending for it");
		check(AreaForker.currentArea(ZONE) == areaBefore,
				"and zone " + ZONE + " still points at area " + areaBefore
				+ " (now " + AreaForker.currentArea(ZONE) + ")");
		check(Workspace.getArchive(Workspace.ArchiveType.AREA_DATA).length == areas, "and AreaData did not grow (" + areas + " -> "
				+ Workspace.getArchive(Workspace.ArchiveType.AREA_DATA).length + ")");
	}

	/** The bytes the engine would read as the registry for an area id. */
	static byte[] registry(int area) {
		byte[] b = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES).getDecompressedEntry(area);
		return b == null ? null : b;
	}

	static void pack() throws Exception {
		Workspace.packArchives((percent, what) -> {
		});
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
	/**
	 * The AreaData handle as it is NOW. A pack reloads every handle, and the
	 * checks above read this again after each step on purpose: the handle's
	 * identity is their witness that nothing was packed.
	 */
	static ctrmap.formats.garc.GARC areaData() {
		return Workspace.getArchive(Workspace.ArchiveType.AREA_DATA);
	}

	static ctrmap.formats.garc.GARC zoneData() {
		return Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
	}
}
