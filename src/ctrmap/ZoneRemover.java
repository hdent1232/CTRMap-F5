package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.GarcRebuilder;
import ctrmap.formats.garc.LZ11;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import ctrmap.formats.containers.ContainerBytes;

/**
 * Removes ADDED zones (index &gt;= 536), restoring the stock ZoneData layout:
 * 536 zones + master table at 536 + "EN" encounter pack at 537. The inverse
 * of ZoneAppender - built on {@link GarcRebuilder}, the shrink-capable GARC
 * writer. Base-zone entries and the EN pack are preserved byte-for-byte
 * (stored form); the master table is truncated to its stock 536 rows.
 *
 * <p>After removal the zone-limit code patch is no longer needed - the stock
 * executable is correct again - so the user should delete their deployed
 * code.ips. Appended FieldData regions / MapMatrix entries from geometry
 * forks of the removed zones stay in their archives as unreferenced data
 * (harmless; the engine indexes those archives directly, extra tail entries
 * are simply never loaded), and so do their story text files. Map and text ids
 * are 16-bit, so their tails cost space, not a budget.
 *
 * <p>AREA IDS ARE GIVEN BACK (F043). They are an 8-bit index with 27 to spare
 * past retail, and every append forks one per zone, so leaving them behind ran
 * the budget out after about six add-and-remove cycles. {@link #remove} empties
 * the NPC registry of every area the removed zones held and no other zone names,
 * and {@link AreaForker#takeAreaIds} hands those ids out before growing the
 * archive.
 */
public class ZoneRemover {

	public static final int BASE_ZONES = ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES;
	public static final int MASTER_ROW = ctrmap.formats.codepatch.ZoneLimitPatch.MASTER_ROW;

	/**
	 * Restores the GARC at {@code garcFile} to the stock layout. Headless, file-level. Returns
	 * the number of zones removed (0 = nothing added).
	 *
	 * <p>PRIVATE (F043): shrinking ZoneData alone left the removed zones' area ids spent, so the
	 * one way to do it is {@link #remove}, which gives them back. {@link #stockLayout} is the
	 * same layout for a caller that only needs to see it.
	 */
	private static int removeFromFile(File garcFile) throws IOException {
		GARC zo = new GARC(garcFile);
		List<byte[]> stored = stockLayout(zo);
		if (stored == null) {
			return 0;
		}
		GarcRebuilder.write(garcFile, garcFile, stored);
		return zo.length - stored.size();
	}

	/**
	 * The stored entries of {@code zo} in the stock layout - its base zones verbatim, the master
	 * table cut to their rows, the EN pack cut to their count - or null when it has no added
	 * zones. Writes nothing.
	 */
	public static List<byte[]> stockLayout(GARC zo) throws IOException {
		//THE ONE READER, AT THE MEASURED ZONE COUNT (F013). This took the table at entry
		//"count - 2" and accepted any table of at least 536 rows, so a table that did not
		//match the zone count was cut to 536 rows whatever it had held. Appending zones is
		//the one game that supports it alone (BASE_ZONES is its count), so its profile answers (F207).
		int zones = ctrmap.ZoneTables.zoneCount(zo, ctrmap.gamedef.GameProfile.onlyGameWith(
				ctrmap.gamedef.GameProfile.Feature.ZONE_APPEND));
		if (zones <= BASE_ZONES) {
			return null;
		}
		int masterIdx = zones, enIdx = zones + 1;

		byte[] master;
		try {
			master = ctrmap.MasterTable.detached(zo.getDecompressedEntry(masterIdx), zones).bytes();
		} catch (IOException wrong) {
			throw new IOException("The added zones were not removed: " + ctrmap.util.Bytes.reason(wrong), wrong);
		}
		byte[] truncated = java.util.Arrays.copyOf(master, BASE_ZONES * MASTER_ROW);
		byte[] masterStored = zo.isEntryCompressed(masterIdx) ? LZ11.compress(truncated) : truncated;

		List<byte[]> stored = new ArrayList<>();
		for (int i = 0; i < BASE_ZONES; i++) {
			byte[] b = zo.getStoredEntry(i);
			if (b == null) {
				throw new IOException("Could not read zone entry " + i);
			}
			stored.add(b);
		}
		stored.add(masterStored);
		//THE EN PACK IS TRUNCATED WITH THE TABLE, not preserved beside it. It
		//carries its own entry count and the append grew it, so keeping it verbatim
		//left a 536-row master table beside a 540-entry pack - and the NEXT append
		//refused with "EN pack count 540 != zone count 536". A revert that leaves
		//the user unable to append again has not reverted; it has half-reverted and
		//said nothing. Found by WorkflowGuardsTest driving remove -> append.
		byte[] enBytes = zo.getDecompressedEntry(enIdx);
		if (enBytes == null) {
			throw new IOException("Could not read the EN encounter pack");
		}
		int enCount = ctrmap.formats.containers.ContainerBytes.count(enBytes);
		byte[] enOut = enCount == BASE_ZONES ? enBytes
			: ctrmap.formats.encounters.EnPack.parse(enBytes, enCount).truncatedTo(BASE_ZONES).bytes();
		stored.add(zo.isEntryCompressed(enIdx) ? LZ11.compress(enOut) : enOut);
		return stored;
	}

	/**
	 * What a removal did: how many zones went, and which area ids came back with them.
	 *
	 * <p>Not: AreaForker.ForkResult - that is one fork's outcome; this is a removal's, which
	 * gives ids back rather than taking one.
	 */
	public static final class Removed {

		/** How many added zones were removed; 0 when there were none. */
		public final int zones;
		/** The area ids the removed zones held and no other zone names, lowest first. */
		public final List<Integer> areasBack;

		Removed(int zones, List<Integer> areasBack) {
			this.zones = zones;
			this.areasBack = areasBack;
		}
	}

	/**
	 * The whole removal, on a workspace the caller has just packed: the added zones out of
	 * ZoneData, the workspace's stale copies of their entries dropped, the archive re-read, and
	 * their area ids given back - one operation, so nothing can do the first without the last.
	 *
	 * <p>GIVING AN AREA ID BACK (F043) is emptying its NPC registry in the archive, which is the
	 * mark {@link AreaForker#givenBack} reads: the next fork takes the id instead of the
	 * archive's end. Its AreaData entry and per-area table row stay until that fork overwrites
	 * them; nothing reads an area no zone names. Only the ids the REMOVED zones held are given
	 * back - an id no zone named before the removal, with NPCs in its registry, is what an older
	 * build's misplaced fork left, and {@link WorkspaceIntegrity} asks the user to move those by
	 * hand; this never touches it.
	 *
	 * @return what was removed and given back
	 * @throws IOException when an archive is not open or an entry cannot be read
	 */
	public static Removed remove(WorkspaceSession s) throws IOException {
		GARC zo = s.getArchive(ctrmap.gamedef.ArchiveType.ZONE_DATA);
		if (zo == null) {
			throw new IOException("ZoneData is not open, so there are no added zones to remove.");
		}
		//which areas the zones about to go hold, read while their rows still exist
		MasterTable before = MasterTable.read(s);
		java.util.Set<Integer> held = new java.util.TreeSet<>();
		for (int z = BASE_ZONES; z < before.zones(); z++) {
			held.add(before.id(ZoneResource.AREA, z));
		}
		int removed = removeFromFile(zo.file);
		s.reloadGARC(ctrmap.gamedef.ArchiveType.ZONE_DATA);
		//the stale extracted copies of everything at or past the stock zone range (the added
		//zones and the old master table and EN pack)
		s.dropExtracted(ctrmap.gamedef.ArchiveType.ZONE_DATA, n -> n >= BASE_ZONES);
		GARC ad = s.getArchive(ctrmap.gamedef.ArchiveType.AREA_DATA);
		List<Integer> back = new ArrayList<>();
		if (removed > 0 && ad != null) {
			for (int id : AreaForker.unnamedForkedAreas(MasterTable.read(s), ad.length)) {
				if (held.contains(id)) {
					back.add(id);
				}
			}
			emptyRegistries(s, back);
		}
		return new Removed(removed, back);
	}

	/**
	 * Empties the NPC registries of {@code freed} in the archive - file level, like
	 * removeFromFile - then re-reads the session's handle and drops the stale extracted copies.
	 *
	 * @throws IOException when the archive is not open or an entry cannot be read; nothing was
	 * changed then
	 */
	static void emptyRegistries(WorkspaceSession s, List<Integer> freed) throws IOException {
		GARC np = s.getArchive(ctrmap.gamedef.ArchiveType.NPC_REGISTRIES);
		if (np == null) {
			throw new IOException("The NPC registries are not open, so the area ids the removed zones held could"
					+ " not be given back.");
		}
		List<byte[]> stored = new ArrayList<>();
		List<Integer> emptied = new ArrayList<>();
		for (int i = 0; i < np.length; i++) {
			byte[] b = np.getStoredEntry(i);
			if (b == null) {
				throw new IOException("NPC registry " + i + " could not be read, so no area id was given back"
						+ " and nothing was changed.");
			}
			if (freed.contains(i) && b.length > 0) {
				stored.add(new byte[0]);
				emptied.add(i);
			} else {
				stored.add(b);
			}
		}
		if (!emptied.isEmpty()) {
			GarcRebuilder.write(np.file, np.file, stored);
			s.reloadGARC(ctrmap.gamedef.ArchiveType.NPC_REGISTRIES);
			s.dropExtracted(ctrmap.gamedef.ArchiveType.NPC_REGISTRIES, emptied::contains);
		}
	}

	/**
	 * Warp references from the workspace's BASE zones as they are now - unpacked edits included -
	 * into the added zones about to be removed (F225); see {@link #referencesToAdded(int, ZoneTables.ZoneBytes)}.
	 */
	public static List<String> referencesToAdded(WorkspaceSession s) {
		GARC zo = s.getArchive(ctrmap.gamedef.ArchiveType.ZONE_DATA);
		return referencesToAdded(zo == null ? 0 : zo.length, i -> ZoneTables.zoneNow(s, i));
	}

	/**
	 * Warp references from BASE zones into the added zones about to be removed - each would become a
	 * warp into nothing - and every base zone that could not be read, whose warps are therefore not
	 * checked. Human-readable, for the confirm dialog; empty = safe. An unreadable zone was skipped
	 * ("the scan is advisory"), so a warp in it into an added zone was missing from the warning, and
	 * the scan read the archive as of the last pack, without the user's unpacked edits (F225).
	 */
	public static List<String> referencesToAdded(int zones, ZoneTables.ZoneBytes source) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Math.min(BASE_ZONES, zones); i++) {
			byte[] z;
			try {
				z = source.zone(i);
			} catch (IOException | RuntimeException unread) {
				out.add("zone " + i + " could not be read (" + Ui.reason(unread) + "), so its warps are not checked");
				continue;
			}
			if (z == null) {
				out.add("zone " + i + " could not be read, so its warps are not checked");
				continue;
			}
			if (z.length < 12 || (z[0] & 0xFF) != 0x5A || (z[1] & 0xFF) != 0x4F) {
				continue;
			}
			ZoneEntities ent;
			try {
				byte[] block = ContainerBytes.subfile(z, 1);
				if (block == null || block.length == 0) {
					throw new IllegalStateException("its container has no whole entity block");
				}
				ent = new ZoneEntities(block);
			} catch (RuntimeException unread) {
				out.add("zone " + i + "'s warps could not be read (" + Ui.reason(unread) + "), so they are not checked");
				continue;
			}
			for (int w = 0; w < ent.warps.size(); w++) {
				int tz = ent.warps.get(w).targetZone;
				if (tz >= BASE_ZONES && tz < 0x8000) {
					out.add("zone " + i + " warp " + w + " -> added zone " + tz);
				}
			}
		}
		return out;
	}
}
