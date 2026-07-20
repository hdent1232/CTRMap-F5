package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.propdata.PropDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Headless test for the prop palette database.
 *
 * Builds a PropDatabase straight from the pristine read-only BuildingModels
 * (a/0/2/3) and AreaData (a/0/1/4) GARC backups - both archives are only
 * opened for reading, nothing under the backup directory is written - and
 * asserts the measured facts of the ORAS dump: 380 BM entries, 150+ named
 * models, com_bm_pc01 with 100+ donor areas, exactly 217 trailing
 * com_bm_dummy free slots (indices 163-379) and a coherent texture
 * availability check (a model is satisfied in its own donor area; a
 * (model, area) pair whose textures are missing is reported as such).
 */
public class PropDatabaseTest {

	private static final String BM_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\2\\3";
	private static final String AD_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\4";

	public static void main(String[] args) {
		File bmFile = new File(args.length > 0 ? args[0] : BM_GARC_PATH);
		File adFile = new File(args.length > 1 ? args[1] : AD_GARC_PATH);
		if (!bmFile.exists()) {
			System.out.println("FAIL: pristine BuildingModels GARC not found: " + bmFile.getAbsolutePath());
			System.exit(1);
		}
		if (!adFile.exists()) {
			System.out.println("FAIL: pristine AreaData GARC not found: " + adFile.getAbsolutePath());
			System.exit(1);
		}

		GARC bm = new GARC(bmFile);
		GARC ad = new GARC(adFile);
		long t0 = System.currentTimeMillis();
		PropDatabase db = PropDatabase.build(bm, ad);
		long buildMs = System.currentTimeMillis() - t0;

		boolean pass = true;

		List<PropDatabase.PropModel> named = db.getNamedModels();
		int freeSlots = 0;
		int withDonors = 0;
		int withTemplate = 0;
		for (PropDatabase.PropModel m : db.models) {
			if (m.freeSlot) {
				freeSlots++;
			}
			if (!m.donorAreas.isEmpty()) {
				withDonors++;
			}
			if (m.template != null) {
				withTemplate++;
			}
		}
		System.out.println("BuildingModels entries:   " + db.models.size());
		System.out.println("named non-dummy models:   " + named.size());
		System.out.println("models with donor areas:  " + withDonors);
		System.out.println("models with templates:    " + withTemplate);
		System.out.println("free slots:               " + freeSlots);
		System.out.println("areas with texture packs: " + db.areaTextureNames.size());
		System.out.println("DB build time:            " + buildMs + " ms");

		if (db.models.size() != 380) {
			System.out.println("FAIL: expected 380 BuildingModels entries, got " + db.models.size());
			pass = false;
		}
		if (named.size() < 150) {
			System.out.println("FAIL: expected >= 150 named models, got " + named.size());
			pass = false;
		}
		if (freeSlots != 217) {
			System.out.println("FAIL: expected 217 free slots (indices 163-379), got " + freeSlots);
			pass = false;
		}
		if (db.areaTextureNames.size() != 228) {
			System.out.println("FAIL: expected 228 AD areas (entry 228 is not an AD container), got " + db.areaTextureNames.size());
			pass = false;
		}

		//com_bm_pc01 - the Pokemon Center, present in nearly every area
		PropDatabase.PropModel pc01 = null;
		for (PropDatabase.PropModel m : named) {
			if ("com_bm_pc01".equals(m.name)) {
				pc01 = m;
				break;
			}
		}
		if (pc01 == null) {
			System.out.println("FAIL: com_bm_pc01 not found among named models");
			pass = false;
		} else {
			System.out.println("com_bm_pc01: model " + pc01.modelIndex + ", " + pc01.donorAreas.size()
					+ " donor areas, template " + (pc01.template != null ? "present" : "MISSING"));
			if (pc01.donorAreas.size() < 100) {
				System.out.println("FAIL: com_bm_pc01 expected >= 100 donor areas, got " + pc01.donorAreas.size());
				pass = false;
			}
			if (pc01.template == null) {
				System.out.println("FAIL: com_bm_pc01 has no registry entry template");
				pass = false;
			}
		}

		//texture availability: com_bm_pc01 must be satisfied in its own donor area
		if (pc01 != null && !pc01.donorAreas.isEmpty()) {
			byte[] bch = PropDatabase.getSubfile(bm.getDecompressedEntry(pc01.modelIndex), 0);
			Set<String> required = PropDatabase.getMaterialTextureNames(bch);
			int donor = pc01.donorAreas.get(0);
			List<String> missing = PropDatabase.getMissingTextureNames(bch, db.areaTextureNames.get(donor));
			System.out.println("com_bm_pc01 requires " + required + " -> missing in donor area " + donor + ": " + missing);
			if (required.isEmpty()) {
				System.out.println("FAIL: com_bm_pc01 has no material texture names - material parse is broken");
				pass = false;
			}
			if (!missing.isEmpty()) {
				System.out.println("FAIL: com_bm_pc01 reported missing textures in its own donor area");
				pass = false;
			}
		}

		//aggregate own-donor check + search for a (model, area) pair with missing textures
		int selfChecked = 0;
		int selfSatisfied = 0;
		int missModel = -1;
		int missArea = -1;
		List<String> missNames = null;
		String missModelName = null;
		List<Integer> areas = new ArrayList<>(db.areaTextureNames.keySet());
		for (PropDatabase.PropModel m : named) {
			if (m.donorAreas.isEmpty()) {
				continue;
			}
			byte[] bch = PropDatabase.getSubfile(bm.getDecompressedEntry(m.modelIndex), 0);
			Set<String> required = PropDatabase.getMaterialTextureNames(bch);
			if (required.isEmpty()) {
				continue;
			}
			selfChecked++;
			if (PropDatabase.getMissingTextureNames(bch, db.areaTextureNames.get(m.donorAreas.get(0))).isEmpty()) {
				selfSatisfied++;
			}
			if (missModel == -1) {
				for (Integer area : areas) {
					if (m.donorAreas.contains(area)) {
						continue;
					}
					List<String> missing = PropDatabase.getMissingTextureNames(bch, db.areaTextureNames.get(area));
					if (!missing.isEmpty()) {
						missModel = m.modelIndex;
						missModelName = m.name;
						missArea = area;
						missNames = missing;
						break;
					}
				}
			}
		}
		System.out.println("own-donor texture check: " + selfSatisfied + "/" + selfChecked
				+ " models texture-satisfied in their first donor area");

		if (missModel != -1) {
			System.out.println("missing-texture pair: " + missModelName + " (model " + missModel + ") in area "
					+ missArea + " lacks " + missNames);
			Set<String> avail = db.areaTextureNames.get(missArea);
			for (String s : missNames) {
				if (avail != null && avail.contains(s)) {
					System.out.println("FAIL: getMissingTextureNames reported an available texture as missing: " + s);
					pass = false;
				}
			}
		} else {
			//every combination is texture-satisfied - assert that explicitly and say so
			System.out.println("no (named model, area) pair with missing textures exists - every combination is texture-satisfied");
			if (selfChecked == 0) {
				System.out.println("FAIL: no models with material texture requirements were checked at all");
				pass = false;
			}
		}

		if (!pass) {
			System.out.println("FAIL");
			System.exit(1);
		}
		System.out.println("PASS");
	}
}
