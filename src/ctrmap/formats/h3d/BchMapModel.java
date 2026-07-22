package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lossless structural reader for ORAS FieldData map-model BCH files (the visual
 * map geometry: FieldData GARC a/0/3/9, GR container subfile 1). Unlike
 * {@link BCHFile} (an Ohana copy-paste that relocates the buffer in place and
 * builds render-only, de-indexed geometry), this reader is NON-DESTRUCTIVE: it
 * keeps the original bytes untouched and resolves every section-relative pointer
 * to an absolute file offset via a map built from the relocation table.
 *
 * <p>It walks the full structure — the 15 content dictionaries, the single
 * H3DModel header, the N materials (0x2C header + 0x110 params), the meshes
 * (0x38 header) and their submeshes (0x34) — and locates every mesh's vertex and
 * index buffer from the command-sourced relocation entries. This is the
 * foundation for a byte-exact writer (native map-geometry editing) and for a
 * faithful renderer.
 *
 * <p>Scope: ORAS/XY map models only — backwardCompatibility 0x21, exactly one
 * model, N materials, no embedded textures/shaders/animations (all measured
 * across every real FieldData region). {@link #validate()} returns the list of
 * structural inconsistencies found (empty == a clean, fully-understood parse);
 * it is the reader gate the test suite asserts on for a broad spread of regions.
 *
 * <p>Layout facts are from the SPICA (CtrH3D) serializer, cross-checked against
 * real dumps. See {@code oras-format-research} notes and BchTexturePack for the
 * sibling texture-pack writer that uses the same deterministic-rebuild strategy.
 */
public class BchMapModel {

	// H3DSection enum ids (used in relocation flags: flag = target | (source << 4))
	public static final int SEC_CONTENTS = 0, SEC_STRINGS = 1, SEC_COMMANDS = 2, SEC_RAWDATA = 4,
			SEC_RAWDATA_TEX = 5, SEC_RAWDATA_VTX = 6, SEC_RAWDATA_IDX16 = 7, SEC_RAWDATA_IDX8 = 8,
			SEC_RAWEXT = 9, SEC_BASEADDR = 14;

	public final byte[] raw;

	// header
	public int bc, fc, converterVersion;
	public int contentsAddr, stringsAddr, commandsAddr, rawDataAddr, rawExtAddr, relocAddr;
	public int contentsLen, stringsLen, commandsLen, rawDataLen, rawExtLen, relocLen;
	public int uninitData, uninitCmd, flagsByte, addressCount;

	// relocation
	public int[] reloc;                                    // raw u32 entries, in file order
	private final Map<Integer, Integer> ptrLocToTargetBase = new HashMap<>();
	private final List<int[]> cmdRelocs = new ArrayList<>(); // {flag, ptrWordLoc}

	// structure
	public int modelPtr;
	public int matValuesPtr, matCount;
	public int meshesPtr, meshCount;
	public int modelNamePtr, modelMetaPtr;
	/** Content dict indices (2..14) that are populated in this model. Empty for
	 *  most regions; 18 of 857 carry a materialsLUT (dict 4) for fragment
	 *  lighting. The writer must preserve these auxiliary dicts + their data. */
	public final List<Integer> auxDicts = new ArrayList<>();
	public final List<Integer> materialHeaderOffsets = new ArrayList<>();
	public final List<Integer> materialParamOffsets = new ArrayList<>();
	/** per mesh: {hdrOff, enableCmdPtr, enableCmdCnt, subPtr, subCnt, matIndex} */
	public final List<int[]> meshes = new ArrayList<>();
	/** per mesh vertex buffer: {absOffset, cmdWordLoc} */
	public final List<int[]> vtxBuffers = new ArrayList<>();
	/** per mesh index buffer: {absOffset, cmdWordLoc, elemSize} */
	public final List<int[]> idxBuffers = new ArrayList<>();
	public int baseAddrCount;

	private final List<String> problems = new ArrayList<>();

	public BchMapModel(byte[] bytes) {
		this.raw = bytes;
		parseHeader();
		parseReloc();
		parseContent();
	}

	/** True when the bytes look like a map-model BCH this reader handles. */
	public static boolean isMapModel(byte[] b) {
		if (b == null || b.length < 0x44 || b[0] != 'B' || b[1] != 'C' || b[2] != 'H' || b[3] != 0) {
			return false;
		}
		if ((b[4] & 0xFF) != 0x21) {
			return false;
		}
		int contentsAddr = le32(b, 8);
		if (contentsAddr != 0x44 || contentsAddr + 15 * 12 > b.length) {
			return false;
		}
		int models = le32(b, contentsAddr + 4);       // dict0 (models) count
		return models == 1;
	}

	/** Structural inconsistencies found during parse; empty == clean parse. */
	public List<String> validate() {
		return problems;
	}

	private void parseHeader() {
		if (raw[0] != 'B' || raw[1] != 'C' || raw[2] != 'H' || raw[3] != 0) {
			throw new IllegalArgumentException("not a BCH");
		}
		bc = u8(4);
		fc = u8(5);
		converterVersion = u16(6);
		if (bc != 0x21) {
			throw new IllegalArgumentException("unsupported BCH backwardCompatibility 0x" + Integer.toHexString(bc));
		}
		contentsAddr = i32(8);
		stringsAddr = i32(12);
		commandsAddr = i32(16);
		rawDataAddr = i32(20);
		int c = 24;
		rawExtAddr = i32(c);
		c += 4;
		relocAddr = i32(c);
		c += 4;
		contentsLen = i32(c);
		c += 4;
		stringsLen = i32(c);
		c += 4;
		commandsLen = i32(c);
		c += 4;
		rawDataLen = i32(c);
		c += 4;
		rawExtLen = i32(c);
		c += 4;
		relocLen = i32(c);
		c += 4;
		uninitData = i32(c);
		c += 4;
		uninitCmd = i32(c);
		c += 4;
		flagsByte = u8(c);
		addressCount = u16(c + 2);
		check(contentsAddr == 0x44, "contentsAddr==0x44");
	}

	private void parseReloc() {
		int n = relocLen / 4;
		reloc = new int[n];
		for (int i = 0; i < n; i++) {
			int v = i32(relocAddr + i * 4);
			reloc[i] = v;
			int off25 = v & 0x1FFFFFF;
			int flag = (v >>> 25) & 0x7F;
			int target = flag & 0xF;
			int source = flag >>> 4;
			int sourceBase = sectionBase(source);
			if (sourceBase < 0) {
				continue;
			}
			int ptrLoc = sourceBase + (target == SEC_STRINGS ? off25 : off25 * 4);
			if (source == SEC_CONTENTS) {
				ptrLocToTargetBase.put(ptrLoc, targetSectionBase(target));
			} else if (source == SEC_COMMANDS) {
				cmdRelocs.add(new int[]{flag, ptrLoc});
			}
		}
		for (int[] cr : cmdRelocs) {
			int flag = cr[0], loc = cr[1];
			switch (flag) {
				case 0x26:
					vtxBuffers.add(new int[]{rawDataAddr + i32(loc), loc});
					break;
				case 0x27:
					idxBuffers.add(new int[]{rawDataAddr + (i32(loc) & 0x7FFFFFFF), loc, 2});
					break;
				case 0x28:
					idxBuffers.add(new int[]{rawDataAddr + (i32(loc) & 0x7FFFFFFF), loc, 1});
					break;
				case 0x2E:
					baseAddrCount++;
					break;
				default:
					break;
			}
		}
	}

	private int sectionBase(int sec) {
		switch (sec) {
			case SEC_CONTENTS: return contentsAddr;
			case SEC_STRINGS: return stringsAddr;
			case SEC_COMMANDS: return commandsAddr;
			case SEC_RAWDATA: return rawDataAddr;
			case SEC_RAWEXT: return rawExtAddr;
			default: return -1;
		}
	}

	private int targetSectionBase(int target) {
		switch (target) {
			case SEC_CONTENTS: return contentsAddr;
			case SEC_STRINGS: return stringsAddr;
			case SEC_COMMANDS: return commandsAddr;
			case SEC_RAWDATA:
			case SEC_RAWDATA_TEX:
			case SEC_RAWDATA_VTX:
			case SEC_RAWDATA_IDX16:
			case SEC_RAWDATA_IDX8:
				return rawDataAddr;
			case SEC_BASEADDR:
				return 0;
			default:
				return rawExtAddr;
		}
	}

	/**
	 * Reads a pointer word at loc and absolutizes it via the reloc-derived target
	 * base. Returns 0 for a null pointer (a slot with no relocation entry).
	 */
	public int ptr(int loc) {
		Integer base = ptrLocToTargetBase.get(loc);
		int word = i32(loc);
		if (base == null) {
			if (word != 0) {
				problems.add("ptr@" + loc + " has no reloc but nonzero word 0x" + Integer.toHexString(word));
			}
			return 0;
		}
		return word + base;
	}

	private void parseContent() {
		int dict0Values = ptr(contentsAddr);
		int dict0Count = i32(contentsAddr + 4);
		matValuesPtr = -1; // resolved from the model header below (dict1 Values is the same array)
		matCount = i32(contentsAddr + 1 * 12 + 4);
		check(dict0Count == 1, "models dict count==1 (got " + dict0Count + ")");
		// dicts 2..14 are usually empty, but a minority of regions carry a
		// materialsLUT (dict 4). Record any populated auxiliary dict rather than
		// rejecting it; the geometry structure is unaffected either way.
		for (int d = 2; d < 15; d++) {
			if (i32(contentsAddr + d * 12 + 4) != 0) {
				auxDicts.add(d);
			}
		}
		modelPtr = ptr(dict0Values);
		check(modelPtr != 0, "model pointer nonzero");

		// H3DModel header (0x98)
		int m = modelPtr;
		matValuesPtr = ptr(m + 0x34);
		int matDictCount = i32(m + 0x34 + 4);
		check(matDictCount == matCount, "model.Materials count==content dict1 count");
		meshesPtr = ptr(m + 0x40);
		meshCount = i32(m + 0x40 + 4);
		modelNamePtr = ptr(m + 0x84);
		modelMetaPtr = ptr(m + 0x94);
		check(i32(m + 0x68 + 4) == 0, "SubMeshCullings empty");
		int meshNodesCount = i32(m + 0x88);
		int meshNodesTree = i32(m + 0x8C);
		check((meshNodesCount == 0) == (meshNodesTree == 0), "MeshNodes count/tree consistent");

		for (int i = 0; i < matCount; i++) {
			int h = matValuesPtr + i * 0x2C;
			materialHeaderOffsets.add(h);
			int paramsPtr = ptr(h + 0x00);
			int namePtr = ptr(h + 0x28);
			check(paramsPtr != 0, "material " + i + " params nonzero");
			check(namePtr != 0, "material " + i + " name nonzero");
			materialParamOffsets.add(paramsPtr);
		}

		for (int i = 0; i < meshCount; i++) {
			int h = meshesPtr + i * 0x38;
			int matIndex = u16(h + 0x00);
			int enableCmdPtr = ptr(h + 0x08);
			int enableCmdCnt = i32(h + 0x0C);
			int subPtr = ptr(h + 0x10);
			int subCnt = i32(h + 0x14);
			meshes.add(new int[]{h, enableCmdPtr, enableCmdCnt, subPtr, subCnt, matIndex});
			check(enableCmdPtr != 0, "mesh " + i + " enableCmd nonzero");
			check(subCnt == 1, "mesh " + i + " has exactly 1 submesh (got " + subCnt + ")");
			check(matIndex < matCount, "mesh " + i + " matIndex in range");
			for (int s = 0; s < subCnt; s++) {
				int sh = subPtr + s * 0x34;
				check(ptr(sh + 0x2C) != 0, "mesh " + i + " submesh " + s + " cmd nonzero");
			}
		}

		// mesh-based cross-invariants (matCount may differ from meshCount:
		// materials can be shared, e.g. region50 has 19 materials / 20 meshes)
		check(vtxBuffers.size() == meshCount, "vertex buffers==meshCount (" + vtxBuffers.size() + " vs " + meshCount + ")");
		check(idxBuffers.size() == meshCount, "index buffers==meshCount (" + idxBuffers.size() + " vs " + meshCount + ")");
		check(baseAddrCount == meshCount, "base addresses==meshCount");
		check(addressCount == 2 * meshCount, "addressCount==2*meshCount");
		check(uninitData == addressCount * 4, "uninitData==addressCount*4");
	}

	// ---- vertex geometry (Tier-1 editing) --------------------------------

	/** PICA attribute element byte sizes: byte, ubyte, short, float. */
	private static final int[] TYPE_BYTES = {1, 1, 2, 4};

	/** Located vertex geometry for one mesh: where its position attribute lives
	 *  in the raw vertex buffer, and how many vertices it has. Position is
	 *  Float(3) in every real ORAS map model (verified: 163,499 verts, 0 garbage). */
	public static final class MeshGeom {
		public int meshIndex;
		public int vtxAbs;       // absolute file offset of the vertex buffer
		public int stride;       // bytes per vertex
		public int posOffset;    // byte offset of the position attribute within a vertex (-1 if none)
		public int posType;      // PICA format: 3 == float
		public int posElems;     // components (3 for XYZ)
		public int vertexCount;  // vertex count, bounded by the buffer's actual extent
		/** True when the position attribute decoded confidently (Float(3), fits the
		 *  stride, finite map-scale coords). A minority of meshes use a vertex-format
		 *  variant this reader doesn't decode; those are left untouched by edits. */
		public boolean posOk;
	}

	/**
	 * Decodes each mesh's vertex layout: walks the PICA attribute config in the
	 * EnableCommands stream (buffer-attribute -> permutation index -> name/format
	 * double indirection) to find the position attribute, and reads the submesh
	 * index buffer to get the vertex count. This is what makes offset-preserving
	 * geometry edits possible without touching any pointer/scaffolding.
	 */
	public List<MeshGeom> geometry() {
		Map<Integer, Integer> idxElem = new HashMap<>();
		for (int[] ib : idxBuffers) {
			idxElem.put(ib[1], ib[2]);
		}
		// sorted unique raw-buffer starts, so a buffer's extent = next start (or
		// section end) - this start. Vertex writes are bounded by this to prevent
		// spilling past a buffer when an index-derived count over-estimates.
		java.util.TreeSet<Integer> starts = new java.util.TreeSet<>();
		for (int[] vb : vtxBuffers) {
			starts.add(vb[0]);
		}
		for (int[] ib : idxBuffers) {
			starts.add(ib[0]);
		}
		int rawEnd = rawDataAddr + rawDataLen;

		List<MeshGeom> out = new ArrayList<>();
		for (int mi = 0; mi < meshes.size(); mi++) {
			int[] mesh = meshes.get(mi);
			int p = mesh[1];       // enableCmdPtr
			int subPtr = mesh[3];
			long fmt64 = u32(p + 0x28) | (u32(p + 0x2C) << 32);
			long perm64 = u32(p + 0x10) | (u32(p + 0x18) << 32);
			long attrs64 = u32(p + 0x34) | (u32(p + 0x38) << 32);
			MeshGeom g = new MeshGeom();
			g.meshIndex = mi;
			g.vtxAbs = rawDataAddr + i32(p + 0x30);
			g.stride = (int) ((attrs64 >>> 48) & 0xFF);
			g.posOffset = -1;
			// attrs64 bits 60-63 hold the buffer's component COUNT directly (not
			// count-1). Walk exactly that many; take the FIRST Position (name 0).
			// Both guards matter: trailing nibbles are 0, which would otherwise
			// alias to permIndex 0 -> name 0 and falsely re-match Position past
			// the real components.
			int bufAttrCount = (int) ((attrs64 >>> 60) & 0xF);
			int running = 0;
			for (int j = 0; j < bufAttrCount; j++) {
				int permIndex = (int) ((attrs64 >>> (j * 4)) & 0xF);
				int name = (int) ((perm64 >>> (permIndex * 4)) & 0xF);
				int fmtNibble = (int) ((fmt64 >>> (permIndex * 4)) & 0xF);
				int type = fmtNibble & 3;
				int elems = ((fmtNibble >>> 2) & 3) + 1;
				if (name == 0 && g.posOffset < 0) { // Position (first match)
					g.posOffset = running;
					g.posType = type;
					g.posElems = elems;
				}
				running += elems * TYPE_BYTES[type];
			}

			// vertex count = index-derived (maxIndex+1), clamped to the buffer's
			// actual byte extent so it can never over-read/over-write.
			int idxCount = 0;
			int subCmdPtr = ptr(subPtr + 0x2C);
			Integer es = idxElem.get(subCmdPtr + 0x10);
			if (es != null) {
				int numIdx = i32(subCmdPtr + 0x10 + 8);       // NUMVERTICES param
				int idxAbs = rawDataAddr + (i32(subCmdPtr + 0x10) & 0x7FFFFFFF);
				int maxIndex = 0;
				for (int k = 0; k < numIdx; k++) {
					int v = es == 1 ? (raw[idxAbs + k] & 0xFF) : u16(idxAbs + k * 2);
					if (v > maxIndex) {
						maxIndex = v;
					}
				}
				idxCount = maxIndex + 1;
			}
			Integer next = starts.higher(g.vtxAbs);
			int extent = (next != null ? next : rawEnd) - g.vtxAbs;
			int byExtent = g.stride > 0 ? extent / g.stride : 0;
			g.vertexCount = idxCount > 0 ? Math.min(idxCount, byExtent) : byExtent;

			// decode confidence: a Float(3) position that fits the stride and whose
			// first/last vertex read as finite, map-scale coordinates.
			g.posOk = g.posOffset >= 0 && g.posType == 3 && g.posElems >= 3
					&& g.stride >= g.posOffset + 12 && g.vertexCount > 0
					&& coordSane(g, 0) && coordSane(g, g.vertexCount - 1);
			out.add(g);
		}
		return out;
	}

	private boolean coordSane(MeshGeom g, int v) {
		int base = g.vtxAbs + v * g.stride + g.posOffset;
		if (base < 0 || base + 12 > raw.length) {
			return false;
		}
		for (int c = 0; c < 3; c++) {
			float f = Float.intBitsToFloat(le32(raw, base + c * 4));
			if (Float.isNaN(f) || Float.isInfinite(f) || Math.abs(f) > 1e5f) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns a copy of this model with every vertex position translated by
	 * (dx,dy,dz). This is an offset-PRESERVING edit: only vertex-position bytes
	 * in the raw-data section change, so every section length, pointer, patricia
	 * tree and relocation entry stays byte-identical - the result is game-valid
	 * by construction (the sibling of BchTexturePack's deterministic rebuild).
	 * Meshes whose position is not Float(3) are left untouched.
	 */
	/**
	 * Reads a mesh's vertex positions as an array of {x,y,z}, or null if the mesh
	 * has no decodable Float(3) position. This is the read side for in-app
	 * reshaping (drag/sculpt vertices).
	 */
	public float[][] getVertexPositions(int meshIndex) {
		List<MeshGeom> geom = geometry();
		if (meshIndex < 0 || meshIndex >= geom.size()) {
			return null;
		}
		MeshGeom g = geom.get(meshIndex);
		if (!g.posOk) {
			return null;
		}
		float[][] out = new float[g.vertexCount][3];
		for (int v = 0; v < g.vertexCount; v++) {
			int base = g.vtxAbs + v * g.stride + g.posOffset;
			out[v][0] = Float.intBitsToFloat(le32(raw, base));
			out[v][1] = Float.intBitsToFloat(le32(raw, base + 4));
			out[v][2] = Float.intBitsToFloat(le32(raw, base + 8));
		}
		return out;
	}

	/**
	 * Returns a copy of the model with one mesh's vertex positions replaced. This
	 * is offset-PRESERVING (vertex count/format unchanged - only position bytes
	 * change), so every offset, tree and relocation entry stays identical. Extra
	 * positions beyond the mesh's vertex count are ignored; the mesh must have a
	 * decodable Float(3) position.
	 */
	public byte[] setVertexPositions(int meshIndex, float[][] positions) {
		List<MeshGeom> geom = geometry();
		if (meshIndex < 0 || meshIndex >= geom.size()) {
			return raw.clone();
		}
		MeshGeom g = geom.get(meshIndex);
		byte[] out = raw.clone();
		if (!g.posOk || positions == null) {
			return out;
		}
		int n = Math.min(positions.length, g.vertexCount);
		for (int v = 0; v < n; v++) {
			int base = g.vtxAbs + v * g.stride + g.posOffset;
			putFloat(out, base, positions[v][0]);
			putFloat(out, base + 4, positions[v][1]);
			putFloat(out, base + 8, positions[v][2]);
		}
		return out;
	}

	public byte[] translate(float dx, float dy, float dz) {
		byte[] out = raw.clone();
		for (MeshGeom g : geometry()) {
			if (!g.posOk) {
				continue; // unrecognized vertex format - leave untouched, never corrupt
			}
			for (int v = 0; v < g.vertexCount; v++) {
				int base = g.vtxAbs + v * g.stride + g.posOffset;
				putFloat(out, base, Float.intBitsToFloat(le32(out, base)) + dx);
				putFloat(out, base + 4, Float.intBitsToFloat(le32(out, base + 4)) + dy);
				putFloat(out, base + 8, Float.intBitsToFloat(le32(out, base + 8)) + dz);
			}
		}
		return out;
	}

	private long u32(int o) {
		return i32(o) & 0xFFFFFFFFL;
	}

	// ---- raw-data relayout writer (Tier-2 geometry foundation) ------------

	/**
	 * Rebuilds the BCH with the raw-data section re-laid-out. Each vertex/index
	 * buffer is keyed by its command-word location (see {@link #vtxBuffers} /
	 * {@link #idxBuffers}, element [1]); any buffer present in {@code replacements}
	 * is swapped for the given bytes (padded to the 0x10 buffer alignment), and
	 * every buffer after a size change slides down. The command words that hold
	 * each buffer's offset are rewritten, and the header's raw-data length and the
	 * rawExt/relocation addresses are patched. Relocation ENTRIES are untouched
	 * (they reference command-word locations in the unchanged commands section,
	 * not raw-data offsets). With an empty map the output is byte-identical.
	 *
	 * <p>This is the structural-write plumbing: it keeps offsets/sizes internally
	 * consistent. Producing a semantically-valid <em>edit</em> (matching vertex
	 * counts, attribute config and index buffer) is the layer built on top.
	 */
	public byte[] rebuildRawData(Map<Integer, byte[]> replacements) {
		// all raw-data buffers, in on-disk order
		List<int[]> bufs = new ArrayList<>(); // {absOffset, cmdWordLoc}
		for (int[] vb : vtxBuffers) {
			bufs.add(new int[]{vb[0], vb[1]});
		}
		for (int[] ib : idxBuffers) {
			bufs.add(new int[]{ib[0], ib[1]});
		}
		bufs.sort((a, b) -> Integer.compare(a[0], b[0]));
		int rawEnd = rawDataAddr + rawDataLen;

		java.io.ByteArrayOutputStream newRaw = new java.io.ByteArrayOutputStream();
		Map<Integer, Integer> newRelByCmd = new HashMap<>();
		// preserve any bytes before the first buffer (data not referenced by a
		// vertex/index relocation - kept verbatim so the layout stays exact)
		int firstBufAbs = bufs.isEmpty() ? rawEnd : bufs.get(0)[0];
		newRaw.write(raw, rawDataAddr, firstBufAbs - rawDataAddr);
		for (int bi = 0; bi < bufs.size(); bi++) {
			int absOff = bufs.get(bi)[0];
			int cmdLoc = bufs.get(bi)[1];
			int spanEnd = (bi + 1 < bufs.size()) ? bufs.get(bi + 1)[0] : rawEnd;
			byte[] data;
			byte[] repl = replacements == null ? null : replacements.get(cmdLoc);
			if (repl != null) {
				int padded = (repl.length + 0xF) & ~0xF;
				data = new byte[padded];
				System.arraycopy(repl, 0, data, 0, repl.length);
			} else {
				data = java.util.Arrays.copyOfRange(raw, absOff, spanEnd); // original span (already 0x10-padded)
			}
			newRelByCmd.put(cmdLoc, newRaw.size()); // 0x10-aligned by construction
			newRaw.write(data, 0, data.length);
		}
		byte[] newRawData = newRaw.toByteArray();
		int newRelocAddr = (rawDataAddr + newRawData.length + 0x7F) & ~0x7F;
		// the whole file is padded to 0x80 after the relocation section
		int fileLen = (newRelocAddr + relocLen + 0x7F) & ~0x7F;
		byte[] out = new byte[fileLen];

		// header + contents + strings + commands, verbatim
		System.arraycopy(raw, 0, out, 0, rawDataAddr);
		// rewrite each buffer's offset command word (preserving any high bit)
		for (Map.Entry<Integer, Integer> e : newRelByCmd.entrySet()) {
			int cmdLoc = e.getKey();
			int high = i32(cmdLoc) & 0x80000000;
			pokeInt(out, cmdLoc, e.getValue() | high);
		}
		// new raw-data, then (zero) section padding, then relocation verbatim
		System.arraycopy(newRawData, 0, out, rawDataAddr, newRawData.length);
		System.arraycopy(raw, relocAddr, out, newRelocAddr, relocLen);
		// patch header addresses/length
		pokeInt(out, 24, newRelocAddr); // rawExtAddr (rawExt is empty, shares reloc addr)
		pokeInt(out, 28, newRelocAddr); // relocationAddr
		pokeInt(out, 44, newRawData.length); // rawDataLength
		return out;
	}

	private static void pokeInt(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	/**
	 * Grows an existing mesh by appending vertices and faces to its buffers,
	 * reusing the mesh's material and vertex format - the first operation that
	 * actually ADDS geometry to a map (extend terrain, merge in a reused asset,
	 * or land imported geometry that matches this mesh's material).
	 *
	 * @param meshIndex        the mesh to grow
	 * @param extraVertexBytes raw vertex data for the new vertices, a whole
	 *                         number of the mesh's vertex stride, in the mesh's
	 *                         exact attribute layout (copy an existing vertex to
	 *                         see the format)
	 * @param extraIndices     new triangle indices into the COMBINED vertex set
	 *                         (existing vertices keep their indices; the first new
	 *                         vertex is index {@code oldVertexCount})
	 * @return a new BCH with the grown mesh (call again on the result to chain)
	 * @throws IllegalStateException if the mesh has no decodable geometry, or the
	 *         combined mesh would exceed 16-bit indices (a u8 index buffer is
	 *         upgraded to u16 automatically when needed)
	 */
	public byte[] appendGeometry(int meshIndex, byte[] extraVertexBytes, int[] extraIndices) {
		List<MeshGeom> geom = geometry();
		if (meshIndex < 0 || meshIndex >= geom.size()) {
			throw new IllegalArgumentException("mesh index out of range");
		}
		MeshGeom g = geom.get(meshIndex);
		if (!g.posOk || g.stride <= 0) {
			throw new IllegalStateException("mesh " + meshIndex + " has no decodable geometry");
		}
		if (extraVertexBytes.length % g.stride != 0) {
			throw new IllegalArgumentException("extra vertex bytes (" + extraVertexBytes.length
					+ ") must be a multiple of the mesh stride (" + g.stride + ")");
		}
		int vtxCmdLoc = meshes.get(meshIndex)[1] + 0x30;     // ATTRIBBUFFER0_OFFSET word
		int vtxDataLen = g.vertexCount * g.stride;
		int subCmdPtr = ptr(meshes.get(meshIndex)[3] + 0x2C);
		int idxCmdLoc = subCmdPtr + 0x10;                    // INDEXBUFFER_CONFIG word
		int numVerticesLoc = idxCmdLoc + 8;                  // NUMVERTICES param
		int elemSize = -1;
		for (int[] ib : idxBuffers) {
			if (ib[1] == idxCmdLoc) {
				elemSize = ib[2];
				break;
			}
		}
		if (elemSize < 0) {
			throw new IllegalStateException("mesh " + meshIndex + " index buffer not located");
		}
		int idxAbs = rawDataAddr + (i32(idxCmdLoc) & 0x7FFFFFFF);
		int curIndexCount = i32(numVerticesLoc);
		int newVertexCount = g.vertexCount + extraVertexBytes.length / g.stride;

		// combine indices, then range-check against the current index width
		int[] newIndices = new int[curIndexCount + extraIndices.length];
		int maxIdx = 0;
		for (int i = 0; i < curIndexCount; i++) {
			newIndices[i] = elemSize == 1 ? (raw[idxAbs + i] & 0xFF) : u16(idxAbs + i * 2);
			maxIdx = Math.max(maxIdx, newIndices[i]);
		}
		for (int i = 0; i < extraIndices.length; i++) {
			newIndices[curIndexCount + i] = extraIndices[i];
			maxIdx = Math.max(maxIdx, extraIndices[i]);
		}
		if (maxIdx >= newVertexCount) {
			throw new IllegalArgumentException("an index references a vertex that does not exist (max index "
					+ maxIdx + " >= vertex count " + newVertexCount + ")");
		}
		// upgrade the index width to u16 if the new range needs it
		int newElemSize = (elemSize == 1 && maxIdx > 0xFF) ? 2 : elemSize;
		if (maxIdx > 0xFFFF) {
			throw new IllegalStateException("mesh " + meshIndex + " would exceed 16-bit indices (max index " + maxIdx + ")");
		}

		byte[] newVtx = new byte[vtxDataLen + extraVertexBytes.length];
		System.arraycopy(raw, g.vtxAbs, newVtx, 0, vtxDataLen);
		System.arraycopy(extraVertexBytes, 0, newVtx, vtxDataLen, extraVertexBytes.length);

		byte[] newIdx = new byte[newIndices.length * newElemSize];
		for (int i = 0; i < newIndices.length; i++) {
			if (newElemSize == 1) {
				newIdx[i] = (byte) newIndices[i];
			} else {
				newIdx[i * 2] = (byte) newIndices[i];
				newIdx[i * 2 + 1] = (byte) (newIndices[i] >> 8);
			}
		}

		Map<Integer, byte[]> repl = new HashMap<>();
		repl.put(vtxCmdLoc, newVtx);
		repl.put(idxCmdLoc, newIdx);
		byte[] out = rebuildRawData(repl);
		pokeInt(out, numVerticesLoc, newIndices.length); // NUMVERTICES = new draw count

		// if the index width was upgraded u8 -> u16, flip its relocation flag
		// (0x28 RawDataIndex8 -> 0x27 RawDataIndex16) so the loader reads 2-byte
		// indices; the on-disk offset word stays plain (the high bit is injected
		// at load by the relocator per the flag)
		if (newElemSize != elemSize) {
			int ei = relocEntryIndexFor(idxCmdLoc, 0x28);
			if (ei >= 0) {
				int outRelocAddr = le32(out, 28);
				int off = outRelocAddr + ei * 4;
				pokeInt(out, off, (le32(out, off) & 0x1FFFFFF) | (0x27 << 25));
			}
		}
		return out;
	}

	/** Index of the relocation entry for a given command-word location + flag, or -1. */
	private int relocEntryIndexFor(int cmdWordLoc, int wantFlag) {
		for (int i = 0; i < reloc.length; i++) {
			int v = reloc[i];
			int flag = (v >>> 25) & 0x7F;
			if (flag != wantFlag || (flag >>> 4) != SEC_COMMANDS) {
				continue;
			}
			if (commandsAddr + (v & 0x1FFFFFF) * 4 == cmdWordLoc) {
				return i;
			}
		}
		return -1;
	}

	private static void putFloat(byte[] b, int o, float f) {
		int v = Float.floatToIntBits(f);
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	private void check(boolean cond, String what) {
		if (!cond) {
			problems.add(what);
		}
	}

	private int u8(int o) {
		return raw[o] & 0xFF;
	}

	private int u16(int o) {
		return (raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8);
	}

	private int i32(int o) {
		return le32(raw, o);
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
