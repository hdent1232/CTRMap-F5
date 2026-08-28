package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.GarcRebuilder;
import ctrmap.formats.garc.LZ11;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
 * are simply never loaded).
 */
public class ZoneRemover {

	public static final int BASE_ZONES = ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES;
	public static final int MASTER_ROW = ctrmap.formats.codepatch.ZoneLimitPatch.MASTER_ROW;

	/**
	 * Restores the GARC at {@code garcFile} to the stock layout. Headless,
	 * file-level. Returns the number of zones removed (0 = nothing added).
	 */
	public static int removeFromFile(File garcFile) throws IOException {
		GARC zo = new GARC(garcFile);
		int count = zo.length;
		if (count <= BASE_ZONES + 2) {
			return 0;
		}
		int removed = count - (BASE_ZONES + 2);
		int masterIdx = count - 2, enIdx = count - 1;

		byte[] master = zo.getDecompressedEntry(masterIdx);
		if (master == null || master.length < BASE_ZONES * MASTER_ROW) {
			throw new IOException("Master zone-header table is smaller than the stock 536 rows - not an appended ZoneData?");
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
		byte[] en = zo.getStoredEntry(enIdx);
		if (en == null) {
			throw new IOException("Could not read the EN encounter pack");
		}
		stored.add(en);
		GarcRebuilder.write(garcFile, garcFile, stored);
		return removed;
	}

	/**
	 * Warp references from BASE zones into the added zones about to be removed
	 * - each would become a warp into nothing. Human-readable, for the confirm
	 * dialog; empty = safe.
	 */
	public static List<String> referencesToAdded(GARC zo) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Math.min(BASE_ZONES, zo.length); i++) {
			try {
				byte[] z = zo.getDecompressedEntry(i);
				if (z == null || z.length < 12 || (z[0] & 0xFF) != 0x5A || (z[1] & 0xFF) != 0x4F) {
					continue;
				}
				int cnt = (z[2] & 0xFF) | ((z[3] & 0xFF) << 8);
				if (cnt < 2) {
					continue;
				}
				int o0 = le32(z, 4 + 4), o1 = le32(z, 4 + 2 * 4);
				if (o0 < 0 || o1 > z.length || o1 <= o0) {
					continue;
				}
				ZoneEntities ent = new ZoneEntities(java.util.Arrays.copyOfRange(z, o0, o1));
				for (int w = 0; w < ent.warps.size(); w++) {
					int tz = ent.warps.get(w).targetZone;
					if (tz >= BASE_ZONES && tz < 0x8000) {
						out.add("zone " + i + " warp " + w + " -> added zone " + tz);
					}
				}
			} catch (RuntimeException ex) {
				// unreadable zone - skip (the scan is advisory)
			}
		}
		return out;
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
