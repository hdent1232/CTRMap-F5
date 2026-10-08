package ctrmap.formats.propdata;

import ctrmap.formats.GameCache;
import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.H3DModelNameGet;
import ctrmap.gamedef.ArchiveType;
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
import static ctrmap.util.LittleEndian.u16;
import static ctrmap.util.LittleEndian.i32;

/**
 * Searchable database of all BuildingModels props: model names, which areas
 * reference each model in their AD prop registry (donor areas) and one
 * representative raw registry entry per model (for auto-importing a prop into
 * an area that does not have it yet).
 *
 * Everything here is built with lightweight static parses of the raw GARC
 * entries - no BCH model/texture decoding, no GL buffering and no workspace
 * file extraction, so building the whole database only costs the LZ11
 * decompression of the archives. The database is built lazily on first use
 * from the {@link GameFiles} it is handed and kept in that game's own store
 * (no flat-file cache - registry or archive edits would silently invalidate
 * it). A pack empties the store, so the next use rebuilds from the fresh
 * archives (F218).
 */
public class PropDatabase {

	public static final String DUMMY_MODEL_NAME = "com_bm_dummy";

	/**
	 * The database, kept by the game it was built from (F218). It was one static
	 * instance keyed to nothing, handed back for whatever game was asked about
	 * next; then one keyed to the identity of the last game handed, so two games
	 * took turns rebuilding it; and a pack dropped it by a call of its own. The
	 * game's store answers all three: each game keeps its own, and a pack
	 * empties it with everything else read from the old archives.
	 */
	private static final GameCache.Key<PropDatabase> DATABASE = new GameCache.Key<>("prop database");

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
	 * The database of the handed game, built from its BuildingModels and
	 * AreaData archives on first use and kept until a pack rewrites them. Null
	 * (kept too) when the handed game has not opened both archives: absence,
	 * for the caller to say in words, never a database built from another game.
	 *
	 * <p>Handed the game rather than fetching the application's: this used to
	 * gate on {@code Workspace.isValid()} and read the global's archives, so
	 * the palette could only ever describe the application's game and no suite
	 * could hand it a pool of its own.
	 *
	 * @param files the game to build from; null is refused in words
	 */
	public static PropDatabase get(GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("the prop database must be handed the game it is built from;"
					+ " handed null, there is no archive to read and no game to answer for");
		}
		return files.cache().get(DATABASE, () -> {
			GARC bm = files.archive(ArchiveType.BUILDING_MODELS);
			GARC ad = files.archive(ArchiveType.AREA_DATA);
			return bm == null || ad == null ? null : build(bm, ad);
		});
	}

	/** Whether {@link #get} would answer from what the handed game keeps, without building anything. */
	public static boolean isBuilt(GameFiles files) {
		return files != null && files.cache().holds(DATABASE);
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
				byte[] bch = ContainerBytes.subfile(container, 0);
				if (isBCH(bch) && i32(bch, 8) + 8 <= bch.length && i32(bch, i32(bch, 8) + 4) > 0) { //content header model count > 0
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
			byte[] pack = ContainerBytes.subfile(ad, 1);
			db.areaTextureNames.put(area, isBCH(pack) ? getTexturePackTextureNames(pack) : new HashSet<String>());
			byte[] reg = ContainerBytes.subfile(ad, 0);
			if (reg == null || reg.length < 4) {
				continue;
			}
			int count = i32(reg, 0);
			for (int e = 0; e < count && 4 + e * 0x50 + 0x50 <= reg.length; e++) {
				int base = 4 + e * 0x50;
				int model = u16(reg, base + 2);
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
	 * Names of the textures embedded in a BCH texture pack (or any BCH), read
	 * without decoding any texture data.
	 */
	public static Set<String> getTexturePackTextureNames(byte[] bch) {
		Set<String> out = new LinkedHashSet<>();
		if (!isBCH(bch)) {
			return out;
		}
		int main = i32(bch, 8);
		int strTable = i32(bch, 12);
		int ptrTable = i32(bch, main + 36) + main;
		int count = i32(bch, main + 40);
		for (int i = 0; i < count; i++) {
			if (ptrTable + i * 4 + 4 > bch.length) {
				break;
			}
			int texHeader = i32(bch, ptrTable + i * 4) + main;
			if (texHeader < 0 || texHeader + 32 > bch.length) {
				continue;
			}
			String name = readCString(bch, i32(bch, texHeader + 28) + strTable);
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
		int main = i32(bch, 8);
		int strTable = i32(bch, 12);
		Set<Integer> stringPositions = getStringRelocPositions(bch);
		int modelPtrTable = i32(bch, main) + main;
		int modelCount = i32(bch, main + 4);
		if (modelCount < 1 || modelPtrTable + 4 > bch.length) {
			return out;
		}
		int model0 = i32(bch, modelPtrTable) + main;
		if (model0 < 0 || model0 + 0x3C > bch.length) {
			return out;
		}
		int matTable = i32(bch, model0 + 0x34) + main;
		int matCount = i32(bch, model0 + 0x38);
		int stride = (bc < 0x21) ? 0x58 : 0x2C;
		int nameBase = (bc < 0x21) ? 0x48 : 0x1C;
		for (int i = 0; i < matCount; i++) {
			for (int slot = 0; slot < 3; slot++) {
				int fieldPos = matTable + i * stride + nameBase + slot * 4;
				if (fieldPos < 0 || fieldPos + 4 > bch.length || !stringPositions.contains(fieldPos)) {
					continue;
				}
				String name = readCString(bch, i32(bch, fieldPos) + strTable);
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
		int main = i32(bch, 8);
		int relocOffset = (bc > 0x20) ? i32(bch, 28) : i32(bch, 24);
		int relocLength = (bc > 0x20) ? i32(bch, 52) : i32(bch, 44);
		for (int o = relocOffset; o + 4 <= relocOffset + relocLength && o + 4 <= bch.length; o += 4) {
			int value = i32(bch, o);
			int offset = value & 0x1FFFFFF;
			int flags = value >>> 25;
			if (flags == 1) {
				out.add(offset + main);
			}
		}
		return out;
	}

	public static boolean isBCH(byte[] b) {
		return ctrmap.formats.h3d.BchSections.isBch(b); //the one reading of it (F066)
	}

	/** The string at {@code offset}, or null when the offset is outside the file (its callers' "no name"). */
	private static String readCString(byte[] b, int offset) {
		if (offset < 0 || offset >= b.length) {
			return null;
		}
		return ctrmap.formats.h3d.StringUtils.cString(b, offset);
	}
}
