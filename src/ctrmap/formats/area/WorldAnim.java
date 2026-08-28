package ctrmap.formats.area;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AreaData subfile-2 "world animations" BCH - the file that makes water
 * scroll, grass sway and waves foam. The engine plays the material animation
 * named {@code <regionModelName>_chikei_} on each loaded map cell; elements
 * inside it target materials by name (chip_sea_b = the two-layer sea scroll).
 *
 * <p>This class is a corpus-validated parser plus a SPLICER that grafts the
 * retail chip_sea_b scroll pair (TexCoord0.Translate +1 / TexCoord1.Translate
 * -1 over 240 frames, copied byte-exact from area 10's world03_02_02_chikei_)
 * into any area - into the region's existing animation when one is bound, or
 * as a whole new animation (values array + patricia-tree rebuild, dict
 * bootstrap) when none is.
 *
 * <p>Format facts (all MEASURED over the 228 retail files - see SPEC_RELOC /
 * SPEC_ANIM in the session notes): header 0x44 bytes, contents at 0x44,
 * strings abut contents, zero pad to a 128-aligned relocation table, zero tail
 * to a 128-multiple. Relocation entries are u32 {offset = v &amp; 0x1FFFFFF,
 * flag = v >>> 25}; only flag 0 (patch word at contents+off*4, += contentsAddr)
 * and flag 1 (patch word at contents+off BYTES, += stringsAddr) exist. All
 * stored pointers are section-relative, so appending to section ends leaves
 * every existing pointer and relocation entry valid.
 */
public class WorldAnim {

	public final byte[] raw;
	public final int contentsAddr, stringsAddr, relocAddr;
	public final int contentsLen, stringsLen, relocLen;

	/** Absolute patch location -> reloc flag (0 contents, 1 strings). */
	private final Map<Integer, Integer> relocMap = new HashMap<>();

	public static final String SEA_MAT = "chip_sea_b";
	public static final String ANIM_SUFFIX = "_chikei_";

	public WorldAnim(byte[] data) {
		this.raw = data;
		if (data == null || data.length < 0x44 || data[0] != 'B' || data[1] != 'C' || data[2] != 'H' || data[3] != 0) {
			throw new IllegalArgumentException("not a BCH");
		}
		contentsAddr = le32(8);
		stringsAddr = le32(0xC);
		relocAddr = le32(0x1C);
		contentsLen = le32(0x20);
		stringsLen = le32(0x24);
		relocLen = le32(0x34);
		int n = relocLen / 4;
		for (int i = 0; i < n; i++) {
			int v = le32(relocAddr + i * 4);
			int off = v & 0x1FFFFFF, flag = v >>> 25;
			int loc = flag == 1 ? contentsAddr + off : contentsAddr + off * 4;
			relocMap.put(loc, flag);
		}
	}

	// ---- logical reads (stored/on-disk form; pointers resolved on the fly) --

	private int le32(int o) {
		return (raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8) | ((raw[o + 2] & 0xFF) << 16) | ((raw[o + 3] & 0xFF) << 24);
	}

	private int u16(int o) {
		return (raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8);
	}

	private String str(int strOff) {
		StringBuilder sb = new StringBuilder();
		for (int p = stringsAddr + strOff; p < raw.length && raw[p] != 0; p++) {
			sb.append((char) (raw[p] & 0xFF));
		}
		return sb.toString();
	}

	/** dict9 header (contents-relative 0x6C): [valuesPtr, count, nameTreePtr]. */
	private int dict9Values() {
		return le32(contentsAddr + 0x6C);
	}

	public int animCount() {
		return le32(contentsAddr + 0x70);
	}

	private int dict9Tree() {
		return le32(contentsAddr + 0x74);
	}

	/** Absolute offset of anim struct i. */
	private int animAt(int i) {
		return contentsAddr + le32(contentsAddr + dict9Values() + i * 4);
	}

	public String animName(int i) {
		return str(le32(animAt(i)));
	}

	public int findAnim(String name) {
		for (int i = 0; i < animCount(); i++) {
			if (name.equals(animName(i))) {
				return i;
			}
		}
		return -1;
	}

	public List<String> animNames() {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < animCount(); i++) {
			out.add(animName(i));
		}
		return out;
	}

	/** Slot count of the element at absolute offset e (2 iff vec2 target). */
	private static int slotCount(int target) {
		return (target >>> 16) == 2 ? 2 : 1;
	}

	/** True if anim i contains the chip_sea_b scroll pair (TC0+TC1 translate). */
	public boolean animHasSeaScroll(int i) {
		int a = animAt(i);
		int arr = contentsAddr + le32(a + 0xC);
		int cnt = le32(a + 0x10);
		boolean tc0 = false, tc1 = false;
		for (int k = 0; k < cnt; k++) {
			int e = contentsAddr + le32(arr + k * 4);
			if (SEA_MAT.equals(str(le32(e)))) {
				int target = le32(e + 4);
				if (target == 0x20015) {
					tc0 = true;
				}
				if (target == 0x20018) {
					tc1 = true;
				}
			}
		}
		return tc0 && tc1;
	}

	/** True if this area animates the sea scroll for the given region model. */
	public boolean hasSeaScroll(String modelName) {
		int i = findAnim(modelName + ANIM_SUFFIX);
		return i >= 0 && animHasSeaScroll(i);
	}

	/**
	 * A location-independent canonical dump of anim i's logical content (names,
	 * targets, curve parameters, keyframe bytes - no absolute pointers), used to
	 * prove that a splice leaves every other animation untouched.
	 */
	public String describeAnim(int i) {
		StringBuilder sb = new StringBuilder();
		int a = animAt(i);
		sb.append(animName(i)).append('|').append(Integer.toHexString(u16(a + 4)))
				.append('|').append(u16(a + 6)).append('|').append(Integer.toHexString(le32(a + 8)));
		int arr = contentsAddr + le32(a + 0xC);
		int cnt = le32(a + 0x10);
		for (int k = 0; k < cnt; k++) {
			int e = contentsAddr + le32(arr + k * 4);
			int target = le32(e + 4);
			sb.append("\n  ").append(str(le32(e))).append('|').append(Integer.toHexString(target))
					.append('|').append(Integer.toHexString(le32(e + 8)));
			for (int s = 0; s < slotCount(target); s++) {
				int sl = e + 0xC + s * 4;
				if (covered(sl, 0)) {
					int g = contentsAddr + le32(sl);
					sb.append(" G{");
					for (int w = 0; w < 8; w++) {
						sb.append(Integer.toHexString(le32(g + w * 4))).append(',');
					}
					int kf = u16(g + 0xE), quant = raw[g + 0xD] & 0xFF;
					int dataLen = kfDataLen(quant, kf);
					for (int p = 0; p < dataLen; p++) {
						sb.append(String.format("%02x", raw[g + 0x24 + p]));
					}
					sb.append('}');
				} else {
					sb.append(" C{").append(Integer.toHexString(le32(sl))).append('}');
				}
			}
		}
		return sb.toString();
	}

	/** Keyframe data bytes for the quantization formats present in retail. */
	private static int kfDataLen(int quant, int count) {
		switch (quant) {
			case 7: return count * 4;   // StepLinear32
			case 3: return count * 12;  // UnifiedHermite96
			case 2: return count * 6;   // Hermite48
			case 4: return count * 6;   // UnifiedHermite48 (SPICA)
			case 5: return count * 4;   // UnifiedHermite32 (SPICA)
			case 6: return count * 8;   // StepLinear64 (SPICA)
			case 1: return count * 8;   // Hermite64 (SPICA)
			default: return count * 16; // Hermite128
		}
	}

	// ---- validation --------------------------------------------------------

	/** Full structural validation; empty list = OK. Passes all 228 retail files. */
	public List<String> validate() {
		List<String> errs = new ArrayList<>();
		try {
			validate0(errs);
		} catch (RuntimeException ex) {
			errs.add("exception: " + ex);
		}
		return errs;
	}

	private void chk(List<String> errs, boolean ok, String what) {
		if (!ok) {
			errs.add(what);
		}
	}

	private void validate0(List<String> errs) {
		// header invariants
		chk(errs, (raw[4] & 0xFF) == 0x21 && (raw[5] & 0xFF) == 0x21, "bc/fc 0x21");
		chk(errs, contentsAddr == 0x44, "contents at 0x44");
		chk(errs, stringsAddr == contentsAddr + contentsLen, "strings abut contents");
		chk(errs, le32(0x10) == 0 && le32(0x28) == 0, "commands empty");
		chk(errs, le32(0x14) == relocAddr && le32(0x18) == relocAddr, "rawData/rawExt == relocAddr");
		chk(errs, le32(0x2C) == 0 && le32(0x30) == 0 && le32(0x38) == 0 && le32(0x3C) == 0, "empty section lens 0");
		chk(errs, le32(0x40) == 1, "flags word 1");
		chk(errs, contentsLen % 4 == 0, "contentsLen %4");
		chk(errs, relocAddr % 128 == 0, "relocAddr 128-aligned");
		int stringsEnd = stringsAddr + stringsLen;
		chk(errs, relocAddr >= stringsEnd, "reloc after strings");
		for (int p = stringsEnd; p < relocAddr; p++) {
			if (raw[p] != 0) {
				errs.add("nonzero pad before reloc @" + p);
				break;
			}
		}
		int relocEnd = relocAddr + relocLen;
		chk(errs, relocEnd <= raw.length, "reloc in file");
		chk(errs, raw.length % 128 == 0, "file len %128");
		for (int p = relocEnd; p < raw.length; p++) {
			if (raw[p] != 0) {
				errs.add("nonzero tail @" + p);
				break;
			}
		}
		// relocation entries: flags, bounds, no duplicates
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < relocLen / 4; i++) {
			int v = le32(relocAddr + i * 4);
			int off = v & 0x1FFFFFF, flag = v >>> 25;
			if (flag != 0 && flag != 1) {
				errs.add("reloc flag " + flag);
				continue;
			}
			int loc = flag == 1 ? contentsAddr + off : contentsAddr + off * 4;
			chk(errs, loc >= contentsAddr && loc + 4 <= contentsAddr + contentsLen, "reloc loc in contents");
			chk(errs, seen.add(loc), "duplicate reloc loc @" + loc);
			int val = le32(loc);
			if (flag == 0) {
				chk(errs, val >= 0 && val < contentsLen, "flag0 value in contents (@" + loc + ")");
			} else {
				chk(errs, val >= 0 && val < stringsLen, "flag1 value in strings (@" + loc + ")");
				chk(errs, val == 0 || raw[stringsAddr + val - 1] == 0, "flag1 value at string start (@" + loc + ")");
			}
			if (!errs.isEmpty() && errs.size() > 20) {
				return;
			}
		}
		// dict 9 walk
		int count = animCount();
		if (count > 0) {
			chk(errs, covered(contentsAddr + 0x6C, 0), "dict9 valuesPtr reloc");
		}
		chk(errs, covered(contentsAddr + 0x74, 0), "dict9 treePtr reloc");
		for (int i = 0; i < count && errs.size() < 20; i++) {
			int slot = contentsAddr + dict9Values() + i * 4;
			chk(errs, covered(slot, 0), "values slot reloc " + i);
			int a = animAt(i);
			chk(errs, covered(a, 1), "anim namePtr reloc");
			String name = animName(i);
			chk(errs, !name.isEmpty(), "anim name nonempty");
			int curves = u16(a + 6);
			int arrOff = le32(a + 0xC);
			chk(errs, arrOff == le32(a + 0x18), "elementsPtr == elementsPtr2");
			chk(errs, covered(a + 0xC, 0) && covered(a + 0x18, 0), "elements ptr relocs");
			chk(errs, le32(a + 0x1C) == 0, "anim +0x1C zero");
			int arr = contentsAddr + arrOff;
			int ecnt = le32(a + 0x10);
			int walkIdx = 0;
			for (int k = 0; k < ecnt; k++) {
				chk(errs, covered(arr + k * 4, 0), "elem slot reloc");
				int e = contentsAddr + le32(arr + k * 4);
				chk(errs, covered(e, 1), "elem namePtr reloc");
				int target = le32(e + 4);
				int slots = slotCount(target);
				for (int s = 0; s < slots; s++) {
					int sl = e + 0xC + s * 4;
					if (covered(sl, 0)) { // group pointer
						int g = contentsAddr + le32(sl);
						chk(errs, covered(g + 0x20, 0), "group dataPtr reloc");
						chk(errs, le32(g + 0x20) == (g - contentsAddr) + 0x24, "group data inline");
						chk(errs, u16(g + 0xA) == walkIdx, "curveIndex sequential (got " + u16(g + 0xA) + " want " + walkIdx + ")");
						walkIdx++;
					}
				}
			}
			chk(errs, walkIdx == curves, "curvesCount == groups (" + curves + " vs " + walkIdx + ")");
			// patricia lookup must land on node i+1
			chk(errs, lookup(name) == i + 1, "patricia lookup '" + name + "' -> node " + (i + 1));
		}
	}

	private boolean covered(int absLoc, int flag) {
		Integer f = relocMap.get(absLoc);
		return f != null && f == flag;
	}

	// ---- patricia tree (lookup verified 486/486 on retail) -----------------

	private static int bitOf(String name, long n) {
		int pos = (int) (n >>> 3);
		return pos < name.length() ? (name.charAt(pos) >> (n & 7)) & 1 : 0;
	}

	/** Retail lookup: LSB-first char bits, set bit goes right, stop on up-link. */
	public int lookup(String name) {
		int tree = contentsAddr + dict9Tree();
		int index = u16(tree + 4); // root leftIndex
		long prevBit = le32(tree) & 0xFFFFFFFFL;
		while ((le32(tree + index * 12) & 0xFFFFFFFFL) < prevBit) {
			prevBit = le32(tree + index * 12) & 0xFFFFFFFFL;
			index = bitOf(name, prevBit) != 0 ? u16(tree + index * 12 + 6) : u16(tree + index * 12 + 4);
		}
		return index;
	}

	/** In-memory node for tree building. */
	private static final class PNode {

		long refBit;
		int left, right;
		int nameOff; // strings-relative; 0 for root
		String name; // for building only

		PNode(long refBit, int nameOff, String name) {
			this.refBit = refBit;
			this.nameOff = nameOff;
			this.name = name;
		}
	}

	/**
	 * Builds the patricia tree for the given names IN ORDER (node i+1 must map
	 * to values[i] - the retail invariant). Returns the node list; caller
	 * serializes. The algorithm is verified by rebuilding every retail dict-9
	 * tree byte-identically (see AnimSpliceTest).
	 */
	static List<PNode> buildTree(List<String> names, List<Integer> nameOffs) {
		List<PNode> nodes = new ArrayList<>();
		PNode root = new PNode(0xFFFFFFFFL, 0, "");
		root.left = 0;
		root.right = 0;
		nodes.add(root);
		for (int i = 0; i < names.size(); i++) {
			insert(nodes, names.get(i), nameOffs.get(i));
		}
		return nodes;
	}

	private static void insert(List<PNode> nodes, String name, int nameOff) {
		int nk = nodes.size();
		// current lookup result for this name
		int index = nodes.get(0).left;
		long prevBit = nodes.get(0).refBit;
		while (nodes.get(index).refBit < prevBit) {
			prevBit = nodes.get(index).refBit;
			index = bitOf(name, prevBit) != 0 ? nodes.get(index).right : nodes.get(index).left;
		}
		String found = nodes.get(index).name;
		// discriminating bit: highest index where the names differ
		long bit = Math.max(name.length(), found.length()) * 8L - 1;
		while (bit > 0 && bitOf(name, bit) == bitOf(found, bit)) {
			bit--;
		}
		PNode nn = new PNode(bit, nameOff, name);
		// walk again to the insertion point: descend while the child is a real
		// downward link AND still tests a higher bit than ours
		int parent = 0;
		boolean fromLeft = true;
		int child = nodes.get(0).left;
		prevBit = nodes.get(0).refBit;
		while (nodes.get(child).refBit < prevBit && nodes.get(child).refBit > bit) {
			parent = child;
			prevBit = nodes.get(child).refBit;
			fromLeft = bitOf(name, prevBit) == 0;
			child = fromLeft ? nodes.get(child).left : nodes.get(child).right;
		}
		if (bitOf(name, bit) != 0) {
			nn.right = nk;
			nn.left = child;
		} else {
			nn.left = nk;
			nn.right = child;
		}
		nodes.add(nn);
		if (fromLeft) {
			nodes.get(parent).left = nk;
		} else {
			nodes.get(parent).right = nk;
		}
	}

	/** Serializes tree nodes (12 bytes each). */
	private static byte[] treeBytes(List<PNode> nodes) {
		byte[] out = new byte[nodes.size() * 12];
		for (int i = 0; i < nodes.size(); i++) {
			PNode n = nodes.get(i);
			putLE(out, i * 12, (int) n.refBit);
			out[i * 12 + 4] = (byte) n.left;
			out[i * 12 + 5] = (byte) (n.left >> 8);
			out[i * 12 + 6] = (byte) n.right;
			out[i * 12 + 7] = (byte) (n.right >> 8);
			putLE(out, i * 12 + 8, n.nameOff);
		}
		return out;
	}

	/** The retail bytes of this file's dict-9 tree (for the gold test). */
	public byte[] retailTreeBytes() {
		int tree = contentsAddr + dict9Tree();
		int n = (animCount() + 1) * 12;
		return java.util.Arrays.copyOfRange(raw, tree, tree + n);
	}

	/** Rebuilds this file's dict-9 tree from its name list (for the gold test). */
	public byte[] rebuiltTreeBytes() {
		List<String> names = new ArrayList<>();
		List<Integer> offs = new ArrayList<>();
		for (int i = 0; i < animCount(); i++) {
			names.add(animName(i));
			offs.add(le32(animAt(i)));
		}
		return treeBytes(buildTree(names, offs));
	}

	// ---- the splice --------------------------------------------------------

	/**
	 * Donor curve shape, byte-exact from area 10 world03_02_02_chikei_ (groups
	 * 0x1B44/0x1B70): the classic two-layer sea scroll. Groups: start -1,
	 * linear StepLinear32, 2 keyframes, U translate 0->+1 (TC0) and 0->-1
	 * (TC1) over the FULL loop, V inline-constant 0. The end frame / keyframe
	 * loop point are scaled to the HOST animation's frame count so the scroll
	 * wraps seamlessly whatever the anim's loop length (retail sea anims span
	 * 239..599 frames; a fixed 240-frame ramp would snap mid-loop). For the
	 * donor length (239 frames / loop 240) the emitted bytes are identical to
	 * retail's. curveIndex (group +0xA) and all pointers are patched per splice.
	 */
	private static int[] groupTC0(int endBits, int invDurBits, int loop) {
		return new int[]{0xBF800000, endBits, 0, 0x00020701, 0x35800008, 0x00000000,
			0x3F800000, invDurBits, 0, /*data*/ 0x00000000, (0xFFFFF << 12) | loop};
	}

	private static int[] groupTC1(int endBits, int invDurBits, int loop) {
		return new int[]{0xBF800000, endBits, 0, 0x00020701, 0x35800008, 0xBF800000,
			0x3F800000, invDurBits, 0, /*data*/ 0xFFFFF000, loop};
	}

	private static final int DONOR_FLAGS = 0x0100;      // donor anim flags
	private static final int DONOR_FRAMES = 0x436F0000; // 239.0f

	/** Host frame count -> [endFrameBits, loopFrames]; donor 239/240 when the
	 *  host value is unusable (non-integral, tiny, or past the 12-bit frame field). */
	static int[] loopFor(int framesBits) {
		float f = Float.intBitsToFloat(framesBits);
		if (f >= 1f && f <= 4093f && f == Math.floor(f)) {
			return new int[]{framesBits, (int) f + 1};
		}
		return new int[]{DONOR_FRAMES, 240};
	}

	/**
	 * Returns a new subfile-2 with the sea-scroll pair bound to
	 * {@code modelName} - into its existing _chikei_ animation if one exists,
	 * else as a new animation. Returns the input array unchanged if the scroll
	 * is already present. The result revalidates cleanly (see AnimSpliceTest).
	 */
	public static byte[] spliceSeaScroll(byte[] sub2, String modelName) {
		WorldAnim wa = new WorldAnim(sub2);
		String animName = modelName + ANIM_SUFFIX;
		int ai = wa.findAnim(animName);
		if (ai >= 0 && wa.animHasSeaScroll(ai)) {
			return sub2;
		}
		return ai >= 0 ? wa.spliceIntoAnim(ai) : wa.spliceNewAnim(animName);
	}

	/** Finds "chip_sea_b" as a standalone string (start-of-string), or -1. */
	private int findSeaString() {
		byte[] want = (SEA_MAT + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		for (int p = stringsAddr; p + want.length <= stringsAddr + stringsLen; p++) {
			if (p > stringsAddr && raw[p - 1] != 0) {
				continue; // must be a string START (retail flag-1 invariant)
			}
			boolean hit = true;
			for (int k = 0; k < want.length; k++) {
				if (raw[p + k] != want[k]) {
					hit = false;
					break;
				}
			}
			if (hit) {
				return p - stringsAddr;
			}
		}
		return -1;
	}

	/** Accumulates the appended pieces + new reloc entries, then assembles. */
	private final class Splice {

		final ByteArrayOutputStream cont = new ByteArrayOutputStream();
		final ByteArrayOutputStream strs = new ByteArrayOutputStream();
		final List<Integer> relocs = new ArrayList<>();
		final byte[] contPatch; // copy of the old contents slice, for in-place patches

		Splice() {
			contPatch = java.util.Arrays.copyOfRange(raw, contentsAddr, contentsAddr + contentsLen);
		}

		int contOff() {
			return contentsLen + cont.size(); // contents-relative offset of the next appended byte
		}

		void word(int v) {
			cont.write(v);
			cont.write(v >> 8);
			cont.write(v >> 16);
			cont.write(v >> 24);
		}

		/** New flag-0 entry for the contents-relative pointer LOCATION. */
		void reloc0(int contLoc) {
			relocs.add((contLoc) >> 2);
		}

		/** New flag-1 entry for the contents-relative pointer LOCATION. */
		void reloc1(int contLoc) {
			relocs.add((1 << 25) | contLoc);
		}

		void patchWord(int contLoc, int v) {
			putLE(contPatch, contLoc, v);
		}

		void patchU16(int contLoc, int v) {
			contPatch[contLoc] = (byte) v;
			contPatch[contLoc + 1] = (byte) (v >> 8);
		}

		int addString(String s) {
			int off = stringsLen + strs.size();
			for (int i = 0; i < s.length(); i++) {
				strs.write(s.charAt(i));
			}
			strs.write(0);
			return off;
		}

		/** Emits one sea element (0x14) + its group (0x2C); returns element offset. */
		int emitSeaElement(int seaStr, int target, int[] group, int curveIndex) {
			int e = contOff();
			int g = e + 0x14;
			word(seaStr);
			reloc1(e);
			word(target);
			word(0x00000C02);
			word(g);
			reloc0(e + 0xC);
			word(0); // V inline constant 0.0
			for (int i = 0; i < group.length; i++) {
				int w = group[i];
				if (i == 2) {
					w = curveIndex << 16; // pre 0, post 0, u16 curveIndex
				}
				if (i == 8) {
					w = g + 0x24; // dataPtr -> inline data
					reloc0(g + 0x20);
				}
				word(w);
			}
			return e;
		}

		byte[] assemble() {
			int newContentsLen = contentsLen + cont.size();
			int newStringsLen = stringsLen + strs.size();
			int newStringsAddr = 0x44 + newContentsLen;
			int newRelocAddr = align(newStringsAddr + newStringsLen, 128);
			int newRelocLen = relocLen + relocs.size() * 4;
			int newLen = align(newRelocAddr + newRelocLen, 128);
			byte[] out = new byte[newLen];
			System.arraycopy(raw, 0, out, 0, 0x44);
			putLE(out, 0x0C, newStringsAddr);
			putLE(out, 0x14, newRelocAddr);
			putLE(out, 0x18, newRelocAddr);
			putLE(out, 0x1C, newRelocAddr);
			putLE(out, 0x20, newContentsLen);
			putLE(out, 0x24, newStringsLen);
			putLE(out, 0x34, newRelocLen);
			System.arraycopy(contPatch, 0, out, 0x44, contentsLen);
			System.arraycopy(cont.toByteArray(), 0, out, 0x44 + contentsLen, cont.size());
			System.arraycopy(raw, stringsAddr, out, newStringsAddr, stringsLen);
			System.arraycopy(strs.toByteArray(), 0, out, newStringsAddr + stringsLen, strs.size());
			System.arraycopy(raw, relocAddr, out, newRelocAddr, relocLen);
			for (int i = 0; i < relocs.size(); i++) {
				putLE(out, newRelocAddr + relocLen + i * 4, relocs.get(i));
			}
			return out;
		}
	}

	/** v1: append the pair to an existing bound animation. */
	private byte[] spliceIntoAnim(int ai) {
		Splice sp = new Splice();
		int seaStr = findSeaString();
		if (seaStr < 0) {
			seaStr = sp.addString(SEA_MAT);
		}
		int a = animAt(ai);
		int n = le32(a + 0x10);
		int curves = u16(a + 6);
		int oldArr = le32(a + 0xC);
		// curves scaled to the host anim's loop length (seamless wrap)
		int[] loop = loopFor(le32(a + 8));
		int invDur = Float.floatToIntBits(1f / loop[1]);
		// new element array = old slots + 2 new; every slot gets a fresh entry
		int arrOff = sp.contOff();
		int elemA = arrOff + (n + 2) * 4;
		int elemB = elemA + 0x40;
		for (int k = 0; k < n; k++) {
			sp.word(le32(contentsAddr + oldArr + k * 4));
			sp.reloc0(arrOff + k * 4);
		}
		sp.word(elemA);
		sp.reloc0(arrOff + n * 4);
		sp.word(elemB);
		sp.reloc0(arrOff + (n + 1) * 4);
		sp.emitSeaElement(seaStr, 0x00020015, groupTC0(loop[0], invDur, loop[1]), curves);
		sp.emitSeaElement(seaStr, 0x00020018, groupTC1(loop[0], invDur, loop[1]), curves + 1);
		// patch the animation in place (its relocs already cover these words)
		int ac = a - contentsAddr;
		sp.patchWord(ac + 0xC, arrOff);
		sp.patchWord(ac + 0x18, arrOff);
		sp.patchWord(ac + 0x10, n + 2);
		sp.patchU16(ac + 6, curves + 2);
		return sp.assemble();
	}

	/** v2: add a whole new animation (values array + patricia rebuild). */
	private byte[] spliceNewAnim(String animName) {
		Splice sp = new Splice();
		int seaStr = findSeaString();
		if (seaStr < 0) {
			seaStr = sp.addString(SEA_MAT);
		}
		int nameStr = sp.addString(animName);
		int count = animCount();

		// layout: values array | tree | anim | elem array | elemA | elemB (+groups)
		int valsOff = sp.contOff();
		int treeOff = valsOff + (count + 1) * 4;
		int animOff = treeOff + (count + 2) * 12;
		int arrOff = animOff + 0x20;
		int elemA = arrOff + 8;
		int elemB = elemA + 0x40;

		// values array
		for (int i = 0; i < count; i++) {
			sp.word(le32(contentsAddr + dict9Values() + i * 4));
			sp.reloc0(valsOff + i * 4);
		}
		sp.word(animOff);
		sp.reloc0(valsOff + count * 4);

		// patricia tree rebuilt over old names + the new one (order preserved)
		List<String> names = new ArrayList<>();
		List<Integer> offs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			names.add(animName(i));
			offs.add(le32(animAt(i)));
		}
		names.add(animName);
		offs.add(nameStr);
		byte[] tree = treeBytes(buildTree(names, offs));
		for (int p = 0; p < tree.length; p += 4) {
			sp.word((tree[p] & 0xFF) | ((tree[p + 1] & 0xFF) << 8) | ((tree[p + 2] & 0xFF) << 16) | ((tree[p + 3] & 0xFF) << 24));
		}
		for (int i = 1; i < count + 2; i++) {
			sp.reloc1(treeOff + i * 12 + 8); // node namePtrs (root's stays 0/uncovered)
		}

		// animation struct
		sp.word(nameStr);
		sp.reloc1(animOff);
		sp.word((2 << 16) | DONOR_FLAGS); // curvesCount 2, donor flags
		sp.word(DONOR_FRAMES);
		sp.word(arrOff);
		sp.reloc0(animOff + 0xC);
		sp.word(2);
		sp.word(0);
		sp.word(arrOff);
		sp.reloc0(animOff + 0x18);
		sp.word(0);

		// element pointer array + the pair (donor loop: 239 frames / wrap 240)
		int invDur = Float.floatToIntBits(1f / 240f);
		sp.word(elemA);
		sp.reloc0(arrOff);
		sp.word(elemB);
		sp.reloc0(arrOff + 4);
		sp.emitSeaElement(seaStr, 0x00020015, groupTC0(DONOR_FRAMES, invDur, 240), 0);
		sp.emitSeaElement(seaStr, 0x00020018, groupTC1(DONOR_FRAMES, invDur, 240), 1);

		// dict9 header: values/count/tree; count-0 dicts lack a valuesPtr reloc
		sp.patchWord(0x6C, valsOff);
		if (count == 0) {
			sp.reloc0(0x6C);
		}
		sp.patchWord(0x70, count + 1);
		sp.patchWord(0x74, treeOff);
		return sp.assemble();
	}

	// ---- utils -------------------------------------------------------------

	private static int align(int v, int a) {
		return (v + a - 1) / a * a;
	}

	private static void putLE(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}
}
