package ctrmap.formats.propdata;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.H3DModelNameGet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searchable database of all BuildingModels props: model names, which areas
 * reference each model in their AD prop registry (donor areas) and one
 * representative raw registry entry per model (for auto-importing a prop into
 * an area that does not have it yet).
 *
 * Everything here is built with lightweight static parses of the raw GARC
 * entries - no BCH model/texture decoding, no GL buffering and no Workspace
 * file extraction, so building the whole database only costs the LZ11
 * decompression of the archives. The database is built lazily on first use
 * and cached in memory for the session (no flat-file cache - registry or
 * archive edits would silently invalidate it; call invalidate() after
 * repacking instead).
 */
public class PropDatabase {

	public static final String DUMMY_MODEL_NAME = "com_bm_dummy";

	private static PropDatabase instance;

	/**
	 * One entry per BuildingModels GARC index.
	 */
	public final List<PropModel> models = new ArrayList<>();

	/**
	 * Area index -> names of the textures in that area's texture pack (AD file 1).
	 */
	public final Map<Integer, Set<String>> areaTextureNames = new LinkedHashMap<>();

	private PropDatabase() {
	}

	/**
	 * Gets the session-cached database, building it from the Workspace GARCs
	 * on first use. Returns null if the workspace is not validated yet.
	 */
	public static synchronized PropDatabase get() {
		if (instance == null && Workspace.valid && Workspace.bm != null && Workspace.ad != null) {
			instance = build(Workspace.bm, Workspace.ad);
		}
		return instance;
	}

	public static synchronized boolean isBuilt() {
		return instance != null;
	}

	/**
	 * Drops the cached database, e.g. after repacking archives with changed
	 * registries.
	 */
	public static synchronized void invalidate() {
		instance = null;
	}

	/**
	 * Builds the database from a BuildingModels GARC (a/0/2/3 on ORAS) and an
	 * AreaData GARC (a/0/1/4 on ORAS). Both archives are only read.
	 */
	public static PropDatabase build(GARC bmGarc, GARC adGarc) {
		PropDatabase db = new PropDatabase();
		for (int i = 0; i < bmGarc.length; i++) {
			PropModel m = new PropModel(i);
			byte[] container = bmGarc.getDecompressedEntry(i);
			if (container != null && container.length > 4 && container[0] == 'B' && container[1] == 'M') {
				byte[] bch = getSubfile(container, 0);
				if (isBCH(bch) && peek(bch, 8) + 8 <= bch.length && peek(bch, peek(bch, 8) + 4) > 0) { //content header model count > 0
					try {
						m.name = H3DModelNameGet.H3DModelNameGet(bch);
					} catch (Exception ex) {
						m.name = null;
					}
				}
			}
			db.models.add(m);
		}
		for (int area = 0; area < adGarc.length; area++) {
			byte[] ad = adGarc.getDecompressedEntry(area);
			if (ad == null || ad.length < 8 || ad[0] != 'A' || ad[1] != 'D') {
				continue; //not an AD container (e.g. ORAS AreaData entry 228)
			}
			byte[] pack = getSubfile(ad, 1);
			db.areaTextureNames.put(area, isBCH(pack) ? getTexturePackTextureNames(pack) : new HashSet<String>());
			byte[] reg = getSubfile(ad, 0);
			if (reg == null || reg.length < 4) {
				continue;
			}
			int count = peek(reg, 0);
			for (int e = 0; e < count && 4 + e * 0x50 + 0x50 <= reg.length; e++) {
				int base = 4 + e * 0x50;
				int model = peekU16(reg, base + 2);
				if (model < 0 || model >= db.models.size()) {
					continue;
				}
				PropModel m = db.models.get(model);
				if (m.donorAreas.isEmpty() || m.donorAreas.get(m.donorAreas.size() - 1) != area) {
					m.donorAreas.add(area);
				}
				if (m.template == null) {
					m.template = new byte[0x50];
					System.arraycopy(reg, base, m.template, 0, 0x50);
				}
			}
		}
		//free slots are the trailing contiguous run of unused com_bm_dummy entries
		//(GARC index 0 is also com_bm_dummy but it is the canonical dummy, not a free slot)
		for (int i = db.models.size() - 1; i >= 0; i--) {
			PropModel m = db.models.get(i);
			if (DUMMY_MODEL_NAME.equals(m.name) && m.donorAreas.isEmpty()) {
				m.freeSlot = true;
			} else {
				break;
			}
		}
		return db;
	}

	public PropModel getModel(int index) {
		return (index >= 0 && index < models.size()) ? models.get(index) : null;
	}

	/**
	 * All models with a readable name that are not dummies/free slots, sorted
	 * by name (index as tie-breaker).
	 */
	public List<PropModel> getNamedModels() {
		List<PropModel> out = new ArrayList<>();
		for (PropModel m : models) {
			if (m.name != null && !m.name.isEmpty() && !m.freeSlot && !DUMMY_MODEL_NAME.equals(m.name)) {
				out.add(m);
			}
		}
		Collections.sort(out, new Comparator<PropModel>() {
			@Override
			public int compare(PropModel o1, PropModel o2) {
				int c = o1.name.compareTo(o2.name);
				return (c != 0) ? c : o1.modelIndex - o2.modelIndex;
			}
		});
		return out;
	}

	/**
	 * First area whose texture pack contains ALL the given texture names -
	 * the automatic donor pick for a cross-area texture import. The model's
	 * own donor areas are preferred (an area that references the model must
	 * have its textures, or vanilla itself would hardlock); every other area
	 * is scanned as a fallback. Returns -1 when no single area has them all.
	 */
	public int findDonorAreaWithTextures(PropModel model, List<String> textureNames) {
		if (model != null) {
			for (Integer area : model.donorAreas) {
				Set<String> names = areaTextureNames.get(area);
				if (names != null && names.containsAll(textureNames)) {
					return area;
				}
			}
		}
		for (Map.Entry<Integer, Set<String>> e : areaTextureNames.entrySet()) {
			if (e.getValue().containsAll(textureNames)) {
				return e.getKey();
			}
		}
		return -1;
	}

	/**
	 * Keeps the in-memory name-set of an area consistent after textures were
	 * imported into its pack (call after a successful BchTexturePack import
	 * has been stored) - the database is built from the packed GARC, which
	 * does not see workspace-file edits until the next Pack Workspace.
	 */
	public void registerImportedTextures(int area, Collection<String> textureNames) {
		Set<String> names = areaTextureNames.get(area);
		if (names == null) {
			names = new LinkedHashSet<>();
			areaTextureNames.put(area, names);
		}
		names.addAll(textureNames);
	}

	public static class PropModel {

		public final int modelIndex;
		public String name;
		public boolean freeSlot = false;
		public final List<Integer> donorAreas = new ArrayList<>();
		/**
		 * Raw 0x50-byte AD prop registry entry from the first donor area,
		 * usable as a template when importing the prop into another area.
		 */
		public byte[] template;

		public PropModel(int modelIndex) {
			this.modelIndex = modelIndex;
		}
	}

	//--- lightweight static parsers ------------------------------------------

	/**
	 * Extracts a subfile from a GF mini container (BM/AD/GR/ZO...) byte array
	 * without any File IO. Returns null when out of bounds/corrupt.
	 */
	public static byte[] getSubfile(byte[] container, int index) {
		if (container == null || container.length < 8) {
			return null;
		}
		int count = peekU16(container, 2);
		if (index < 0 || index >= count || 4 + (count + 1) * 4 > container.length) {
			return null;
		}
		int start = peek(container, 4 + index * 4);
		int end = peek(container, 4 + (index + 1) * 4);
		if (start < 0 || end > container.length || end < start) {
			return null;
		}
		byte[] out = new byte[end - start];
		System.arraycopy(container, start, out, 0, out.length);
		return out;
	}

	/**
	 * Names of the textures embedded in a BCH texture pack (or any BCH), read
	 * without decoding any texture data.
	 */
	public static Set<String> getTexturePackTextureNames(byte[] bch) {
		Set<String> out = new LinkedHashSet<>();
		if (!isBCH(bch)) {
			return out;
		}
		int main = peek(bch, 8);
		int strTable = peek(bch, 12);
		int ptrTable = peek(bch, main + 36) + main;
		int count = peek(bch, main + 40);
		for (int i = 0; i < count; i++) {
			if (ptrTable + i * 4 + 4 > bch.length) {
				break;
			}
			int texHeader = peek(bch, ptrTable + i * 4) + main;
			if (texHeader < 0 || texHeader + 32 > bch.length) {
				continue;
			}
			String name = readCString(bch, peek(bch, texHeader + 28) + strTable);
			if (name != null && !name.isEmpty()) {
				out.add(name);
			}
		}
		return out;
	}

	/**
	 * Texture names referenced by the materials of the first model in a BCH
	 * (material name0/1/2). Only name slots that the BCH relocation table
	 * flags as string pointers are read - unused slots contain junk that the
	 * naive read would misinterpret as garbage names. Obvious non-references
	 * ("projection_dummy", 1-char strings) are filtered on top of that.
	 */
	public static Set<String> getMaterialTextureNames(byte[] bch) {
		Set<String> out = new LinkedHashSet<>();
		if (!isBCH(bch)) {
			return out;
		}
		int bc = bch[4] & 0xFF;
		int main = peek(bch, 8);
		int strTable = peek(bch, 12);
		Set<Integer> stringPositions = getStringRelocPositions(bch);
		int modelPtrTable = peek(bch, main) + main;
		int modelCount = peek(bch, main + 4);
		if (modelCount < 1 || modelPtrTable + 4 > bch.length) {
			return out;
		}
		int model0 = peek(bch, modelPtrTable) + main;
		if (model0 < 0 || model0 + 0x3C > bch.length) {
			return out;
		}
		int matTable = peek(bch, model0 + 0x34) + main;
		int matCount = peek(bch, model0 + 0x38);
		int stride = (bc < 0x21) ? 0x58 : 0x2C;
		int nameBase = (bc < 0x21) ? 0x48 : 0x1C;
		for (int i = 0; i < matCount; i++) {
			for (int slot = 0; slot < 3; slot++) {
				int fieldPos = matTable + i * stride + nameBase + slot * 4;
				if (fieldPos < 0 || fieldPos + 4 > bch.length || !stringPositions.contains(fieldPos)) {
					continue;
				}
				String name = readCString(bch, peek(bch, fieldPos) + strTable);
				if (name == null || name.length() <= 1 || name.equals("projection_dummy")) {
					continue;
				}
				out.add(name);
			}
		}
		return out;
	}

	/**
	 * Which of the model's required textures are NOT in the given available
	 * set. Textures embedded in the model's own BCH satisfy themselves.
	 * An empty result means the prop is safe to place texture-wise (missing
	 * prop textures hardlock the game when the area loads).
	 */
	public static List<String> getMissingTextureNames(byte[] modelBch, Set<String> availableTextureNames) {
		List<String> missing = new ArrayList<>();
		Set<String> required = getMaterialTextureNames(modelBch);
		required.removeAll(getTexturePackTextureNames(modelBch)); //self-contained textures, none in vanilla ORAS but cheap to honor
		for (String name : required) {
			if (availableTextureNames == null || !availableTextureNames.contains(name)) {
				missing.add(name);
			}
		}
		return missing;
	}

	/**
	 * Positions (absolute file offsets) of all fields that the BCH relocation
	 * table marks as string table pointers (flag 1).
	 */
	private static Set<Integer> getStringRelocPositions(byte[] bch) {
		Set<Integer> out = new HashSet<>();
		int bc = bch[4] & 0xFF;
		int main = peek(bch, 8);
		int relocOffset = (bc > 0x20) ? peek(bch, 28) : peek(bch, 24);
		int relocLength = (bc > 0x20) ? peek(bch, 52) : peek(bch, 44);
		for (int o = relocOffset; o + 4 <= relocOffset + relocLength && o + 4 <= bch.length; o += 4) {
			int value = peek(bch, o);
			int offset = value & 0x1FFFFFF;
			int flags = value >>> 25;
			if (flags == 1) {
				out.add(offset + main);
			}
		}
		return out;
	}

	public static boolean isBCH(byte[] b) {
		return b != null && b.length > 0x40 && b[0] == 'B' && b[1] == 'C' && b[2] == 'H' && b[3] == 0;
	}

	private static String readCString(byte[] b, int offset) {
		if (offset < 0 || offset >= b.length) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = offset; i < b.length && b[i] != 0; i++) {
			sb.append((char) (b[i] & 0xFF));
		}
		return sb.toString();
	}

	private static int peek(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static int peekU16(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}
}
