package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ctrmap.util.LittleEndian;

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
		if (!BchSections.isBch(b) || (b[4] & 0xFF) != 0x21) {
			return false;
		}
		BchSections s;
		try {
			s = BchSections.read(b);
		} catch (IllegalArgumentException notWhole) {
			return false; //its relocation table is not inside it: nothing here can read it
		}
		int contentsAddr = s.contentsAddr;
		if (contentsAddr != 0x44 || contentsAddr + 15 * 12 > b.length) {
			return false;
		}
		int models = LittleEndian.i32(b, contentsAddr + 4);       // dict0 (models) count
		return models == 1;
	}

	/** Structural inconsistencies found during parse; empty == clean parse. */
	public List<String> validate() {
		return problems;
	}

	/** The header and relocation table, as the one reader reads them (F066). */
	private BchSections sections;

	/** The header and relocation table this model was read with. */
	BchSections sections() {
		return sections;
	}

	private void parseHeader() {
		sections = BchSections.read(raw);
		BchSections s = sections;
		bc = s.bc;
		fc = s.fc;
		converterVersion = s.version;
		if (bc != 0x21) {
			throw new IllegalArgumentException("unsupported BCH backwardCompatibility 0x" + Integer.toHexString(bc));
		}
		contentsAddr = s.contentsAddr;
		stringsAddr = s.stringsAddr;
		commandsAddr = s.commandsAddr;
		rawDataAddr = s.rawDataAddr;
		rawExtAddr = s.rawExtAddr;
		relocAddr = s.relocAddr;
		contentsLen = s.contentsLen;
		stringsLen = s.stringsLen;
		commandsLen = s.commandsLen;
		rawDataLen = s.rawDataLen;
		rawExtLen = s.rawExtLen;
		relocLen = s.relocLen;
		uninitData = s.uninitData;
		uninitCmd = s.uninitCmd;
		flagsByte = s.flags & 0xFF;
		addressCount = s.addressCount;
		check(contentsAddr == 0x44, "contentsAddr==0x44");
	}

	private void parseReloc() {
		reloc = sections.relocWords.clone();
		for (int i = 0; i < sections.relocCount(); i++) {
			BchSections.Reloc r = sections.reloc(i);
			if (r.ptrLoc < 0) {
				continue;
			}
			if (r.source == SEC_CONTENTS) {
				ptrLocToTargetBase.put(r.ptrLoc, sections.targetBase(r.target));
			} else if (r.source == SEC_COMMANDS) {
				cmdRelocs.add(new int[]{r.flag, r.ptrLoc});
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
	 *  in the raw vertex buffer, and how many vertices it has. Positions are read
	 *  as Float(3); a mesh whose position attribute is any other shape is not
	 *  decoded ({@link #posOk} false) - see {@link BchMapModel#undecodedMeshes}.
	 *  How many retail has is counted over the corpus by BchMapModelTest (F239:
	 *  this comment said none and the next said a minority, and neither was
	 *  measured by anything in the tree). */
	public static final class MeshGeom {
		public final int meshIndex;
		public final int vtxAbs;       // absolute file offset of the vertex buffer
		public final int stride;       // bytes per vertex
		public final int posOffset;    // byte offset of the position attribute within a vertex (-1 if none)
		public final int posType;      // PICA format: 3 == float
		public final int posElems;     // components (3 for XYZ)
		public final int vertexCount;  // vertex count, bounded by the buffer's actual extent
		/** True when the position attribute decoded confidently (Float(3), fits the
		 *  stride, finite map-scale coords). A mesh where it is false is left untouched
		 *  by every edit, and the edits that report say how many were (F239). */
		public final boolean posOk;

		/** Final, so the one decode {@link #geometry} keeps for the model is the one every caller reads (F068). */
		MeshGeom(int meshIndex, int vtxAbs, int stride, int posOffset, int posType, int posElems, int vertexCount,
				boolean posOk) {
			this.meshIndex = meshIndex;
			this.vtxAbs = vtxAbs;
			this.stride = stride;
			this.posOffset = posOffset;
			this.posType = posType;
			this.posElems = posElems;
			this.vertexCount = vertexCount;
			this.posOk = posOk;
		}
	}

	/**
	 * The meshes whose positions did not decode - every geometry edit leaves them where they are,
	 * so an edit that reports says how many it left (F239). Empty for a model all of whose
	 * meshes decoded.
	 */
	public java.util.List<Integer> undecodedMeshes() {
		java.util.List<Integer> out = new java.util.ArrayList<>();
		for (MeshGeom g : geometry()) {
			if (!g.posOk) {
				out.add(g.meshIndex);
			}
		}
		return out;
	}

	/**
	 * One buffered vertex attribute of a mesh: PICA semantic, format, and byte
	 * offset within the vertex stride. Fixed (non-buffered) attributes live as
	 * constants in the command stream and are not listed - they take no bytes.
	 */
	public static final class MeshAttr {

		/** PICA semantic: 0=Position, 1=Normal, 2=Tangent, 3=Color, 4=TexCoord0, 5=TexCoord1, 6=TexCoord2, 7=BoneIndex, 8=BoneWeight. */
		public int name;
		/** PICA format: 0=s8, 1=u8, 2=s16, 3=float. */
		public int type;
		/** Components, 1..4. */
		public int elems;
		/** Byte offset within a vertex. */
		public int offset;

		public int size() {
			return elems * TYPE_BYTES[type];
		}
	}

	/**
	 * The complete buffered attribute layout of a mesh, in buffer order - the
	 * decode/encode map for full-fidelity import/export (UVs, normals, colors).
	 * Uses the same double-indirection walk as {@link #geometry()} (attrs64
	 * component nibble -> perm64 semantic / fmt64 format). Returns an empty list
	 * when the walk does not fit the stride (exotic-format meshes).
	 */
	public List<MeshAttr> attributes(int meshIndex) {
		List<MeshAttr> out = new ArrayList<>();
		if (meshIndex < 0 || meshIndex >= meshes.size()) {
			return out;
		}
		int p = meshes.get(meshIndex)[1];
		long fmt64 = u32(p + 0x28) | (u32(p + 0x2C) << 32);
		long perm64 = u32(p + 0x10) | (u32(p + 0x18) << 32);
		long attrs64 = u32(p + 0x34) | (u32(p + 0x38) << 32);
		int stride = (int) ((attrs64 >>> 48) & 0xFF);
		int bufAttrCount = (int) ((attrs64 >>> 60) & 0xF);
		int running = 0;
		for (int j = 0; j < bufAttrCount; j++) {
			int permIndex = (int) ((attrs64 >>> (j * 4)) & 0xF);
			int fmtNibble = (int) ((fmt64 >>> (permIndex * 4)) & 0xF);
			MeshAttr a = new MeshAttr();
			a.name = (int) ((perm64 >>> (permIndex * 4)) & 0xF);
			a.type = fmtNibble & 3;
			a.elems = ((fmtNibble >>> 2) & 3) + 1;
			a.offset = running;
			running += a.size();
			if (running > stride) {
				return new ArrayList<>(); //walk does not fit - unrecognized layout
			}
			out.add(a);
		}
		return out;
	}

	/** The first attribute with the given PICA semantic, or null. */
	public MeshAttr findAttr(int meshIndex, int name) {
		for (MeshAttr a : attributes(meshIndex)) {
			if (a.name == name) {
				return a;
			}
		}
		return null;
	}

	/**
	 * Decodes each mesh's vertex layout: walks the PICA attribute config in the
	 * EnableCommands stream (buffer-attribute -> permutation index -> name/format
	 * double indirection) to find the position attribute, and reads the submesh
	 * index buffer to get the vertex count. This is what makes offset-preserving
	 * geometry edits possible without touching any pointer/scaffolding.
	 */
	public List<MeshGeom> geometry() {
		if (geometry == null) {
			geometry = java.util.Collections.unmodifiableList(decodeGeometry());
		}
		return geometry;
	}

	/**
	 * The decode, kept: the model's bytes do not change under it - every edit returns a new array -
	 * and the geometry tools asked for it of every mesh several times per mesh, hundreds of full
	 * passes a click on a region of 20-40 meshes (F068).
	 */
	private List<MeshGeom> geometry;

	private List<MeshGeom> decodeGeometry() {
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
			int vtxAbs = rawDataAddr + i32(p + 0x30);
			int stride = (int) ((attrs64 >>> 48) & 0xFF);
			int posOffset = -1, posType = 0, posElems = 0;
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
				if (name == 0 && posOffset < 0) { // Position (first match)
					posOffset = running;
					posType = type;
					posElems = elems;
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
			Integer next = starts.higher(vtxAbs);
			int extent = (next != null ? next : rawEnd) - vtxAbs;
			int byExtent = stride > 0 ? extent / stride : 0;
			int vertexCount = idxCount > 0 ? Math.min(idxCount, byExtent) : byExtent;

			// decode confidence: a Float(3) position that fits the stride and whose
			// first/last vertex read as finite, map-scale coordinates.
			boolean posOk = posOffset >= 0 && posType == 3 && posElems >= 3
					&& stride >= posOffset + 12 && vertexCount > 0
					&& coordSane(vtxAbs + posOffset, stride, 0) && coordSane(vtxAbs + posOffset, stride, vertexCount - 1);
			out.add(new MeshGeom(mi, vtxAbs, stride, posOffset, posType, posElems, vertexCount, posOk));
		}
		return out;
	}

	private boolean coordSane(int firstPos, int stride, int v) {
		int base = firstPos + v * stride;
		if (base < 0 || base + 12 > raw.length) {
			return false;
		}
		for (int c = 0; c < 3; c++) {
			float f = Float.intBitsToFloat(LittleEndian.i32(raw, base + c * 4));
			if (Float.isNaN(f) || Float.isInfinite(f) || Math.abs(f) > 1e5f) {
				return false;
			}
		}
		return true;
	}

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
			out[v][0] = Float.intBitsToFloat(LittleEndian.i32(raw, base));
			out[v][1] = Float.intBitsToFloat(LittleEndian.i32(raw, base + 4));
			out[v][2] = Float.intBitsToFloat(LittleEndian.i32(raw, base + 8));
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
		return setVertexPositions(java.util.Collections.singletonMap(meshIndex, positions));
	}

	/**
	 * {@link #setVertexPositions(int, float[][])} for several meshes, written into ONE copy - the
	 * edit is offset-preserving, so nothing moves and no re-parse is owed between meshes (F068: the
	 * box move wrote one mesh, re-parsed the whole model, and went on to the next). A mesh index out
	 * of range, or a mesh without decodable positions, is left as it is.
	 */
	public byte[] setVertexPositions(Map<Integer, float[][]> positionsByMesh) {
		List<MeshGeom> geom = geometry();
		byte[] out = raw.clone();
		for (Map.Entry<Integer, float[][]> e : positionsByMesh.entrySet()) {
			int meshIndex = e.getKey();
			float[][] positions = e.getValue();
			if (meshIndex < 0 || meshIndex >= geom.size() || positions == null || !geom.get(meshIndex).posOk) {
				continue;
			}
			MeshGeom g = geom.get(meshIndex);
			int n = Math.min(positions.length, g.vertexCount);
			for (int v = 0; v < n; v++) {
				int base = g.vtxAbs + v * g.stride + g.posOffset;
				LittleEndian.putF32(out, base, positions[v][0]);
				LittleEndian.putF32(out, base + 4, positions[v][1]);
				LittleEndian.putF32(out, base + 8, positions[v][2]);
			}
		}
		return out;
	}

	/**
	 * Reads a mesh's triangle list as vertex indices (3 per face, in draw
	 * order), or null if the mesh's index buffer cannot be located. This is the
	 * face data for OBJ export and for in-app mesh picking.
	 */
	public int[] getTriangles(int meshIndex) {
		if (meshIndex < 0 || meshIndex >= meshes.size()) {
			return null;
		}
		int subPtr = meshes.get(meshIndex)[3];
		int subCmdPtr = ptr(subPtr + 0x2C);
		int idxCmdLoc = subCmdPtr + 0x10;
		int elemSize = -1;
		for (int[] ib : idxBuffers) {
			if (ib[1] == idxCmdLoc) {
				elemSize = ib[2];
				break;
			}
		}
		if (elemSize < 0) {
			return null;
		}
		int numIdx = i32(subCmdPtr + 0x18);
		int idxAbs = rawDataAddr + (i32(idxCmdLoc) & 0x7FFFFFFF);
		if (numIdx < 0 || idxAbs + numIdx * elemSize > raw.length) {
			return null;
		}
		int[] out = new int[numIdx];
		for (int k = 0; k < numIdx; k++) {
			out[k] = elemSize == 1 ? (raw[idxAbs + k] & 0xFF) : u16(idxAbs + k * 2);
		}
		return out;
	}

	/**
	 * The first mesh whose material is named exactly {@code material}, or -1 when none is. The one
	 * copy of this lookup: seven loops wrote it out across the importers, the prefab stamper and
	 * the painter until F092.
	 */
	public int meshWithMaterial(String material) {
		for (int m = 0; m < meshes.size(); m++) {
			if (material.equals(getMaterialName(getMeshMaterialIndex(m)))) {
				return m;
			}
		}
		return -1;
	}

	/** The material index a mesh draws with. */
	public int getMeshMaterialIndex(int meshIndex) {
		return meshes.get(meshIndex)[5];
	}

	/**
	 * The model's internal name (e.g. "world03_02_02") - what the area's world
	 * animations bind to (animation "&lt;name&gt;_chikei_" plays on this model).
	 */
	public String getModelName() {
		if (modelNamePtr <= 0) {
			return null;
		}
		return StringUtils.cString(raw, modelNamePtr);
	}

	/**
	 * Reads a material's name from the strings section (the texture/material
	 * identifier, e.g. "lawn01" - human-meaningful labels for the editor UI).
	 */
	public String getMaterialName(int matIndex) {
		if (matIndex < 0 || matIndex >= materialHeaderOffsets.size()) {
			return null;
		}
		int namePtr = ptr(materialHeaderOffsets.get(matIndex) + 0x28);
		if (namePtr <= 0) {
			return null;
		}
		return StringUtils.cString(raw, namePtr);
	}

	/**
	 * The texture names a material's header names in its three texture slots (+0x1C/+0x20/+0x24),
	 * in slot order, each once - what must exist in the AREA's texture packs for the material to
	 * draw. The one reader (F093): the terrain importer and the prefab cutter each wrote this loop
	 * out, and a string that ran off the end came back cut short rather than refused.
	 *
	 * @throws IllegalArgumentException when a slot's string runs off the end of the model
	 */
	public List<String> getMaterialTextureNames(int matIndex) {
		List<String> out = new ArrayList<>();
		int header = materialHeaderOffsets.get(matIndex);
		for (int slot : new int[]{0x1C, 0x20, 0x24}) {
			int at = ptr(header + slot);
			if (at > 0) {
				String name = StringUtils.cString(raw, at);
				if (!name.isEmpty() && !out.contains(name)) {
					out.add(name);
				}
			}
		}
		return out;
	}

	/**
	 * Returns a copy of this model with every vertex position translated by
	 * (dx,dy,dz). This is an offset-PRESERVING edit: only vertex-position bytes
	 * in the raw-data section change, so every section length, pointer, patricia
	 * tree and relocation entry stays byte-identical - the result is game-valid
	 * by construction (the sibling of BchTexturePack's deterministic rebuild).
	 * Meshes whose position is not Float(3) are left untouched.
	 */
	public byte[] translate(float dx, float dy, float dz) {
		byte[] out = raw.clone();
		for (MeshGeom g : geometry()) {
			if (!g.posOk) {
				continue; // unrecognized vertex format - leave untouched, never corrupt
			}
			for (int v = 0; v < g.vertexCount; v++) {
				int base = g.vtxAbs + v * g.stride + g.posOffset;
				LittleEndian.putF32(out, base, Float.intBitsToFloat(LittleEndian.i32(out, base)) + dx);
				LittleEndian.putF32(out, base + 4, Float.intBitsToFloat(LittleEndian.i32(out, base + 4)) + dy);
				LittleEndian.putF32(out, base + 8, Float.intBitsToFloat(LittleEndian.i32(out, base + 8)) + dz);
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
		return rebuildRawData(replacements, null);
	}

	/**
	 * Like {@link #rebuildRawData(Map)}, with optional OUTPUT-ORDER overrides:
	 * {@code sortKeyOverride} maps a buffer's command-word location to a
	 * synthetic sort key (default key = originalOffset*2), letting a buffer be
	 * relocated - e.g. an index buffer upgraded u8-&gt;u16 moves to the end of
	 * the u16 group (key = firstU8Offset*2-1) so GF's vtx&lt;idx16&lt;idx8
	 * grouping convention survives. Spans/extents always come from the
	 * ORIGINAL adjacency; with no overrides the output order (and a no-op
	 * rebuild) is byte-identical as before.
	 */
	public byte[] rebuildRawData(Map<Integer, byte[]> replacements, Map<Integer, Long> sortKeyOverride) {
		// all raw-data buffers, in on-disk order (for span/extent derivation)
		List<int[]> bufs = new ArrayList<>(); // {absOffset, cmdWordLoc}
		for (int[] vb : vtxBuffers) {
			bufs.add(new int[]{vb[0], vb[1]});
		}
		for (int[] ib : idxBuffers) {
			bufs.add(new int[]{ib[0], ib[1]});
		}
		bufs.sort((a, b) -> Integer.compare(a[0], b[0]));
		int rawEnd = rawDataAddr + rawDataLen;

		// extents from ORIGINAL adjacency, then emit in (possibly overridden) order
		List<long[]> emit = new ArrayList<>(); // {sortKey, absOffset, spanEnd, cmdLoc}
		for (int bi = 0; bi < bufs.size(); bi++) {
			int absOff = bufs.get(bi)[0];
			int cmdLoc = bufs.get(bi)[1];
			int spanEnd = (bi + 1 < bufs.size()) ? bufs.get(bi + 1)[0] : rawEnd;
			Long key = sortKeyOverride == null ? null : sortKeyOverride.get(cmdLoc);
			emit.add(new long[]{key != null ? key : absOff * 2L, absOff, spanEnd, cmdLoc});
		}
		emit.sort((a, b) -> Long.compare(a[0], b[0]));

		java.io.ByteArrayOutputStream newRaw = new java.io.ByteArrayOutputStream();
		Map<Integer, Integer> newRelByCmd = new HashMap<>();
		// preserve any bytes before the first buffer (data not referenced by a
		// vertex/index relocation - kept verbatim so the layout stays exact)
		int firstBufAbs = bufs.isEmpty() ? rawEnd : bufs.get(0)[0];
		newRaw.write(raw, rawDataAddr, firstBufAbs - rawDataAddr);
		for (long[] e : emit) {
			int absOff = (int) e[1], spanEnd = (int) e[2], cmdLoc = (int) e[3];
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
			LittleEndian.putI32(out, cmdLoc, e.getValue() | high);
		}
		// new raw-data, then (zero) section padding, then relocation verbatim
		System.arraycopy(newRawData, 0, out, rawDataAddr, newRawData.length);
		System.arraycopy(raw, relocAddr, out, newRelocAddr, relocLen);
		// patch header addresses/length
		LittleEndian.putI32(out, 24, newRelocAddr); // rawExtAddr (rawExt is empty, shares reloc addr)
		LittleEndian.putI32(out, 28, newRelocAddr); // relocationAddr
		LittleEndian.putI32(out, 44, newRawData.length); // rawDataLength
		return out;
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
		return setMeshGeometries(java.util.Collections.singletonMap(meshIndex,
				grown(meshIndex, extraVertexBytes, extraIndices)));
	}

	/**
	 * The edit that grows mesh {@code meshIndex} by these vertices and faces: its own buffers, then
	 * the extra - what {@link #appendGeometry} writes, for {@link #setMeshGeometries} to write with
	 * other meshes' edits in one rebuild.
	 */
	public MeshEdit grown(int meshIndex, byte[] extraVertexBytes, int[] extraIndices) {
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
		int[] old = getTriangles(meshIndex);
		if (old == null) {
			throw new IllegalStateException("mesh " + meshIndex + " index buffer not located");
		}
		//THE MESH AS IT IS, AND THE EXTRA (F062): this was the whole of setMeshGeometry written out a
		//second time - the buffer lookup, the width upgrade, the encoding and the relocation flip -
		//so a fix to one stayed out of the other. Growing a mesh IS replacing it with more.
		int vtxDataLen = g.vertexCount * g.stride;
		byte[] vertices = new byte[vtxDataLen + extraVertexBytes.length];
		System.arraycopy(raw, g.vtxAbs, vertices, 0, vtxDataLen);
		System.arraycopy(extraVertexBytes, 0, vertices, vtxDataLen, extraVertexBytes.length);
		int[] indices = java.util.Arrays.copyOf(old, old.length + extraIndices.length);
		System.arraycopy(extraIndices, 0, indices, old.length, extraIndices.length);
		return new MeshEdit(vertices, indices);
	}

	/**
	 * Replaces a mesh's ENTIRE geometry - vertex buffer and index buffer - the
	 * re-topology primitive (an imported/Blender-edited mesh need not preserve
	 * the original vertex or face structure). Reuses the mesh's material and
	 * vertex format; {@code vertexBytes} must be whole strides in the mesh's
	 * exact attribute layout. The index width adapts automatically (u8 buffers
	 * upgrade to u16 when the new vertex count needs it, including the reloc
	 * flag flip). The one place a mesh's buffers are rewritten: {@link #appendGeometry}
	 * is this with the mesh's own vertices and faces first (F062).
	 *
	 * @param meshIndex   the mesh to replace
	 * @param vertexBytes the new vertex data (n * stride bytes)
	 * @param indices     the new triangle list into those vertices (3 per face)
	 * @return a new BCH with the replaced mesh
	 */
	public byte[] setMeshGeometry(int meshIndex, byte[] vertexBytes, int[] indices) {
		return setMeshGeometries(java.util.Collections.singletonMap(meshIndex, new MeshEdit(vertexBytes, indices)));
	}

	/** A mesh's new vertex buffer (whole strides, in its own layout) and triangle list. */
	public static final class MeshEdit {

		public final byte[] vertices;
		public final int[] indices;

		public MeshEdit(byte[] vertices, int[] indices) {
			this.vertices = vertices.clone();
			this.indices = indices.clone();
		}
	}

	/** One edit checked and encoded, with where it goes. */
	private static final class Prepared {

		int vtxCmdLoc, idxCmdLoc, numVerticesLoc, elemSize, newElemSize;
		byte[] vertices, encodedIndices;
		int indexCount;
	}

	private Prepared prepare(int meshIndex, MeshEdit edit) {
		byte[] vertexBytes = edit.vertices;
		int[] indices = edit.indices;
		List<MeshGeom> geom = geometry();
		if (meshIndex < 0 || meshIndex >= geom.size()) {
			throw new IllegalArgumentException("mesh index out of range");
		}
		MeshGeom g = geom.get(meshIndex);
		if (!g.posOk || g.stride <= 0) {
			throw new IllegalStateException("mesh " + meshIndex + " has no decodable geometry");
		}
		if (vertexBytes.length % g.stride != 0) {
			throw new IllegalArgumentException("vertex bytes (" + vertexBytes.length
					+ ") must be a multiple of the mesh stride (" + g.stride + ")");
		}
		int newVertexCount = vertexBytes.length / g.stride;
		if (newVertexCount < 1 || indices.length < 3) {
			throw new IllegalArgumentException("a mesh needs at least one vertex and one face");
		}
		int vtxCmdLoc = meshes.get(meshIndex)[1] + 0x30;
		int subCmdPtr = ptr(meshes.get(meshIndex)[3] + 0x2C);
		int idxCmdLoc = subCmdPtr + 0x10;
		int numVerticesLoc = idxCmdLoc + 8;
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
		int maxIdx = 0;
		for (int v : indices) {
			if (v < 0) {
				throw new IllegalArgumentException("negative vertex index");
			}
			maxIdx = Math.max(maxIdx, v);
		}
		if (maxIdx >= newVertexCount) {
			throw new IllegalArgumentException("an index references a vertex that does not exist (max index "
					+ maxIdx + " >= vertex count " + newVertexCount + ")");
		}
		if (maxIdx > 0xFFFF) {
			throw new IllegalStateException("mesh " + meshIndex + " would exceed 16-bit indices (max index " + maxIdx + ")");
		}
		int newElemSize = (elemSize == 1 && maxIdx > 0xFF) ? 2 : elemSize;

		byte[] newIdx = new byte[indices.length * newElemSize];
		for (int i = 0; i < indices.length; i++) {
			if (newElemSize == 1) {
				newIdx[i] = (byte) indices[i];
			} else {
				LittleEndian.putU16(newIdx, i * 2, indices[i]);
			}
		}
		Prepared p = new Prepared();
		p.vtxCmdLoc = vtxCmdLoc;
		p.idxCmdLoc = idxCmdLoc;
		p.numVerticesLoc = numVerticesLoc;
		p.elemSize = elemSize;
		p.newElemSize = newElemSize;
		p.vertices = vertexBytes.clone();
		p.encodedIndices = newIdx;
		p.indexCount = indices.length;
		return p;
	}

	/**
	 * Replaces several meshes' geometry in ONE rebuild of the raw data (F068): the box duplicate and
	 * delete rebuilt and re-parsed the whole model once per mesh they touched. The one place a mesh's
	 * buffers are rewritten - setMeshGeometry and appendGeometry are this with one edit.
	 *
	 * <p>AT MOST ONE 8-BIT INDEX BUFFER WIDENED PER REBUILD. A buffer widened to 16 bits is placed
	 * just before the first OTHER 8-bit buffer, so GF's grouping survives; two widened at once would
	 * each count the other as staying 8-bit. More than one is written one rebuild at a time.
	 */
	public byte[] setMeshGeometries(Map<Integer, MeshEdit> edits) {
		if (edits.isEmpty()) {
			return raw.clone();
		}
		Map<Integer, Prepared> prepared = new java.util.TreeMap<>();
		Integer widened = null;
		int widenings = 0;
		for (Map.Entry<Integer, MeshEdit> e : new java.util.TreeMap<>(edits).entrySet()) {
			Prepared p = prepare(e.getKey(), e.getValue());
			prepared.put(e.getKey(), p);
			if (p.newElemSize != p.elemSize) {
				widenings++;
				widened = widened == null ? e.getKey() : widened;
			}
		}
		if (widenings > 1) {
			Map<Integer, MeshEdit> rest = new java.util.TreeMap<>(edits);
			MeshEdit first = rest.remove(widened);
			byte[] once = setMeshGeometries(java.util.Collections.singletonMap(widened, first));
			return new BchMapModel(once).setMeshGeometries(rest);
		}
		Map<Integer, byte[]> repl = new HashMap<>();
		Map<Integer, Long> orderFix = null;
		for (Prepared p : prepared.values()) {
			repl.put(p.vtxCmdLoc, p.vertices);
			repl.put(p.idxCmdLoc, p.encodedIndices);
			if (p.newElemSize != p.elemSize) {
				orderFix = upgradeOrderFix(p.idxCmdLoc);
			}
		}
		byte[] out = rebuildRawData(repl, orderFix);
		for (Prepared p : prepared.values()) {
			LittleEndian.putI32(out, p.numVerticesLoc, p.indexCount);
			// if the index width was upgraded u8 -> u16, flip its relocation flag
			// (0x28 RawDataIndex8 -> 0x27 RawDataIndex16) so the loader reads 2-byte
			// indices; the on-disk offset word stays plain (the high bit is injected
			// at load by the relocator per the flag)
			if (p.newElemSize != p.elemSize) {
				int ei = relocEntryIndexFor(p.idxCmdLoc, 0x28);
				if (ei >= 0) {
					int outRelocAddr = LittleEndian.i32(out, 28);
					int off = outRelocAddr + ei * 4;
					LittleEndian.putI32(out, off, (LittleEndian.i32(out, off) & 0x1FFFFFF) | (0x27 << 25));
				}
			}
		}
		return out;
	}

	/**
	 * Sort-key override that moves an upgraded (u8-&gt;u16) index buffer to the
	 * end of the u16 group - just before the first OTHER u8 buffer - so GF's
	 * vtx&lt;idx16&lt;idx8 raw-data grouping convention survives the upgrade.
	 * Null when no other u8 buffer exists (position already conforms).
	 */
	private Map<Integer, Long> upgradeOrderFix(int idxCmdLoc) {
		int minOtherU8 = Integer.MAX_VALUE;
		for (int[] ib : idxBuffers) {
			if (ib[2] == 1 && ib[1] != idxCmdLoc) {
				minOtherU8 = Math.min(minOtherU8, ib[0]);
			}
		}
		if (minOtherU8 == Integer.MAX_VALUE) {
			return null;
		}
		Map<Integer, Long> m = new HashMap<>();
		m.put(idxCmdLoc, minOtherU8 * 2L - 1);
		return m;
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

	private void check(boolean cond, String what) {
		if (!cond) {
			problems.add(what);
		}
	}

	private int u8(int o) {
		return raw[o] & 0xFF;
	}

	private int u16(int o) {
		return LittleEndian.u16(raw, o);
	}

	private int i32(int o) {
		return LittleEndian.i32(raw, o);
	}
}
