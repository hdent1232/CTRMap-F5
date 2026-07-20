package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reader/writer for ORAS area texture packs (AreaData a/0/1/4, AD subfile 1) -
 * the BCH files that hold the textures for all props/models of an area.
 *
 * The format was measured on all 228 vanilla packs: every byte of the
 * contents/strings/commands/relocation sections is derivable from an ordered
 * list of (name, format, dimensions, raw texel data), so this class does a
 * full deterministic rebuild instead of splicing. A no-op parse+emit is
 * byte-identical for all 228 vanilla packs, including trailing padding.
 *
 * Import semantics: a texture whose name is already present in the target is
 * skipped (presence is what matters for the missing-texture hardlock; vanilla
 * areas legitimately reuse names with different pixels). Textures are inserted
 * at their alphabetical position, matching the vanilla converter's ordering.
 *
 * Only converter-style texture packs are supported (backwardCompat 0x21, no
 * mipmaps, 12-word command sets). Anything else - model BCHs, Pokemon BCHs
 * (bc 7), mipmapped or cube textures - is rejected with an exception rather
 * than risking garbage output.
 */
public class BchTexturePack {

	/**
	 * Bits per pixel for the 14 PICA200 texture formats (RGBA8..ETC1A4).
	 */
	private static final int[] BPP = {32, 24, 16, 16, 16, 16, 16, 8, 8, 8, 4, 4, 4, 8};

	/**
	 * PICA register indices of the dim/lod/data-address/type commands of the
	 * three texture units - the fixed 48-byte command template the converter
	 * emits per unit (and that parse() insists on).
	 */
	private static final int[][] UNIT_REGS = {{0x82, 0x84, 0x85, 0x8E}, {0x92, 0x94, 0x95, 0x96}, {0x9A, 0x9C, 0x9D, 0x9E}};

	/**
	 * One texture of a pack: everything that is not derivable by the emitter.
	 */
	public static class Texture {

		public String name;
		/**
		 * PICA format index (0..13), as in the texture struct and the unit
		 * type command parameter.
		 */
		public int format;
		/**
		 * height | (width << 16) - the unit dim command parameter, verbatim.
		 */
		public int dimParam;
		/**
		 * Raw encoded texel data, verbatim (not decoded).
		 */
		public byte[] data;

		public int getWidth() {
			return (dimParam >> 16) & 0x7FF;
		}

		public int getHeight() {
			return dimParam & 0x7FF;
		}
	}

	/**
	 * True when the byte array looks like an area texture pack this class can
	 * handle: BCH magic, converter version line (bc 0x21), no models, at
	 * least one texture.
	 */
	public static boolean isTexturePack(byte[] bch) {
		if (bch == null || bch.length < 0x44 + 360 || bch[0] != 'B' || bch[1] != 'C' || bch[2] != 'H' || bch[3] != 0) {
			return false;
		}
		if ((bch[4] & 0xFF) != 0x21) {
			return false;
		}
		int contentsAdr = peek(bch, 8);
		if (contentsAdr < 0 || contentsAdr + 44 > bch.length) {
			return false;
		}
		int modelCount = peek(bch, contentsAdr + 4);
		int textureCount = peek(bch, contentsAdr + 40);
		return modelCount == 0 && textureCount > 0;
	}

	/**
	 * Parses a texture pack into its minimal model. Throws
	 * IllegalArgumentException on anything that is not a plain converter-made
	 * texture pack - never returns a lossy result.
	 */
	public static List<Texture> parse(byte[] p) {
		if (p == null || p.length < 0x44 || p[0] != 'B' || p[1] != 'C' || p[2] != 'H' || p[3] != 0) {
			throw new IllegalArgumentException("not a BCH");
		}
		if ((p[4] & 0xFF) != 0x21) {
			throw new IllegalArgumentException("unsupported BCH backwardCompat " + (p[4] & 0xFF) + " (expected 0x21)");
		}
		int contentsAdr = peek(p, 8);
		int stringsAdr = peek(p, 12);
		int commandsAdr = peek(p, 16);
		int rawAdr = peek(p, 20);
		int count = peek(p, contentsAdr + 40);
		int ptrTab = peek(p, contentsAdr + 36) + contentsAdr;
		List<Texture> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < count; i++) {
			int st = peek(p, ptrTab + i * 4) + contentsAdr;
			Texture t = new Texture();
			t.format = p[st + 24] & 0xFF;
			if (t.format >= BPP.length) {
				throw new IllegalArgumentException("unknown PICA texture format " + t.format);
			}
			if ((p[st + 25] & 0xFF) != 1) {
				throw new IllegalArgumentException("mipmapped texture unsupported (mipLevels " + (p[st + 25] & 0xFF) + ")");
			}
			if (peek(p, st + 4) != 12 || peek(p, st + 12) != 12 || peek(p, st + 20) != 12) {
				throw new IllegalArgumentException("nonstandard texture command word count");
			}
			//validate the register opcode words of all 3 unit command sets - a
			//foreign/edited pack with a different layout must fail loudly here
			//instead of being mis-parsed by the fixed-template reads below
			for (int u = 0; u < 3; u++) {
				int cu = peek(p, st + u * 8) + commandsAdr;
				if (cu < 0 || cu + 48 > p.length
						|| peek(p, cu + 4) != ((0xF << 16) | UNIT_REGS[u][0])
						|| peek(p, cu + 12) != ((0x4 << 16) | UNIT_REGS[u][1])
						|| peek(p, cu + 20) != ((0xF << 16) | UNIT_REGS[u][2])
						|| peek(p, cu + 28) != ((0xF << 16) | UNIT_REGS[u][3])
						|| peek(p, cu + 44) != ((0xF << 16) | 0x23D)) {
					throw new IllegalArgumentException("unsupported texture command layout");
				}
			}
			t.name = readCString(p, peek(p, st + 28) + stringsAdr);
			if (!seen.add(t.name)) {
				throw new IllegalArgumentException("duplicate texture name in pack: " + t.name);
			}
			int c0 = peek(p, st) + commandsAdr;
			t.dimParam = peek(p, c0);
			int dataAdr = peek(p, c0 + 16);
			int size = t.getWidth() * t.getHeight() * BPP[t.format] / 8;
			if (rawAdr + dataAdr + size > p.length) {
				throw new IllegalArgumentException("texture data out of bounds: " + t.name);
			}
			t.data = new byte[size];
			System.arraycopy(p, rawAdr + dataAdr, t.data, 0, size);
			out.add(t);
		}
		return out;
	}

	/**
	 * Emits a texture pack byte-identically to the vanilla converter's output
	 * for the same texture list (including section alignment gaps, relocation
	 * entry order and trailing 0x80 padding).
	 */
	public static byte[] emit(List<Texture> texes) {
		int count = texes.size();
		if (count == 0) {
			throw new IllegalArgumentException("cannot emit an empty texture pack");
		}
		int contentsAdr = 0x44;
		int contentsLen = 360 + 48 * count;
		int treeOff = 216;
		int ptrTabOff = treeOff + (count + 1) * 12;
		int roots4Off = ptrTabOff + count * 4;
		int structsOff = roots4Off + 132;

		int[] nameOff = new int[count];
		int stringsLen = 0;
		for (int i = 0; i < count; i++) {
			nameOff[i] = stringsLen;
			stringsLen += texes.get(i).name.length() + 1;
		}
		int stringsAdr = contentsAdr + contentsLen;
		int commandsAdr = align(stringsAdr + stringsLen, 16);
		int commandsLen = 144 * count;
		int rawAdr = align(commandsAdr + commandsLen, 128);
		int[] dataAdr = new int[count];
		int rawEnd = 0;
		for (int i = 0; i < count; i++) {
			rawEnd = align(rawEnd, 128);
			dataAdr[i] = rawEnd;
			rawEnd += texes.get(i).data.length;
		}
		int rawLen = align(rawEnd, 128);
		int relocAdr = rawAdr + rawLen;
		int relocLen = 4 * (16 + 9 * count);
		int total = align(relocAdr + relocLen, 128);

		byte[] o = new byte[total];
		//header
		o[0] = 'B';
		o[1] = 'C';
		o[2] = 'H';
		o[4] = 0x21; //backwardCompat
		o[5] = 0x21; //forwardCompat
		poke16(o, 6, 42607); //converterVersion
		poke(o, 8, contentsAdr);
		poke(o, 12, stringsAdr);
		poke(o, 16, commandsAdr);
		poke(o, 20, rawAdr);
		poke(o, 24, relocAdr); //rawExt (length 0) shares the relocation address
		poke(o, 28, relocAdr);
		poke(o, 32, contentsLen);
		poke(o, 36, stringsLen);
		poke(o, 40, commandsLen);
		poke(o, 44, rawLen);
		poke(o, 48, 0); //rawExtLen
		poke(o, 52, relocLen);
		poke(o, 56, 12 * count); //uninitializedDataSectionLength = 4 * addressCount
		poke(o, 60, 0); //uninitializedDescriptionSectionLength
		poke16(o, 64, 1); //flags
		poke16(o, 66, 3 * count); //addressCount (all 3 units' data address words)

		//content header: 15 dicts, only index 3 (textures) is populated
		for (int d = 0; d < 15; d++) {
			int base = contentsAdr + d * 12;
			poke(o, base, d == 3 ? ptrTabOff : 0);
			poke(o, base + 4, d == 3 ? count : 0);
			poke(o, base + 8, d < 3 ? 180 + d * 12 : (d == 3 ? treeOff : roots4Off + (d - 4) * 12));
		}

		//patricia name tree
		List<Node> nodes = buildTree(texes);
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			int base = contentsAdr + treeOff + i * 12;
			poke(o, base, (int) n.refBit);
			poke16(o, base + 4, n.left);
			poke16(o, base + 6, n.right);
			poke(o, base + 8, i == 0 ? 0 : nameOff[i - 1]);
		}

		//texture pointer table + 32-byte texture structs
		for (int i = 0; i < count; i++) {
			poke(o, contentsAdr + ptrTabOff + i * 4, structsOff + i * 32);
			int st = contentsAdr + structsOff + i * 32;
			poke(o, st, i * 144);
			poke(o, st + 4, 12);
			poke(o, st + 8, i * 144 + 48);
			poke(o, st + 12, 12);
			poke(o, st + 16, i * 144 + 96);
			poke(o, st + 20, 12);
			o[st + 24] = (byte) texes.get(i).format;
			o[st + 25] = 1; //mipLevels
			poke(o, st + 28, nameOff[i]);
		}

		//strings (no leading empty string, names in table order, no holes)
		int sp = stringsAdr;
		for (int i = 0; i < count; i++) {
			String nm = texes.get(i).name;
			for (int k = 0; k < nm.length(); k++) {
				o[sp++] = (byte) nm.charAt(k);
			}
			o[sp++] = 0;
		}

		//commands: 3 x 48-byte unit templates per texture
		int[][] regs = UNIT_REGS;
		for (int i = 0; i < count; i++) {
			Texture t = texes.get(i);
			for (int u = 0; u < 3; u++) {
				int c = commandsAdr + i * 144 + u * 48;
				poke(o, c, t.dimParam);
				poke(o, c + 4, (0xF << 16) | regs[u][0]); //dim
				poke(o, c + 8, 0);
				poke(o, c + 12, (0x4 << 16) | regs[u][1]); //lod
				poke(o, c + 16, dataAdr[i]);
				poke(o, c + 20, (0xF << 16) | regs[u][2]); //data address
				poke(o, c + 24, t.format);
				poke(o, c + 28, (0xF << 16) | regs[u][3]); //type
				poke(o, c + 40, 1);
				poke(o, c + 44, (0xF << 16) | 0x23D); //block end
			}
		}

		//raw texel data, each block 128-aligned
		for (int i = 0; i < count; i++) {
			System.arraycopy(texes.get(i).data, 0, o, rawAdr + dataAdr[i], texes.get(i).data.length);
		}

		//relocation table, exact vanilla entry order
		int r = relocAdr;
		for (int i = 0; i < count; i++) {
			r = reloc(o, r, 0, ptrTabOff / 4 + i); //ptrTab entries -> contents
		}
		r = reloc(o, r, 0, 9); //texture dict ptrTab field
		r = reloc(o, r, 0, 2); //dict 0..2 name offsets
		r = reloc(o, r, 0, 5);
		r = reloc(o, r, 0, 8);
		for (int i = 1; i <= count; i++) {
			r = reloc(o, r, 1, treeOff + i * 12 + 8); //tree node names -> strings (byte offset)
		}
		int[] dictOrder = {3, 4, 5, 6, 7, 14, 8, 9, 10, 11, 12, 13};
		for (int d : dictOrder) {
			r = reloc(o, r, 0, d * 3 + 2); //remaining dict name offsets
		}
		for (int i = 0; i < count; i++) {
			r = reloc(o, r, 1, structsOff + i * 32 + 28); //struct name -> strings
			for (int u = 0; u < 3; u++) {
				r = reloc(o, r, 0x25, i * 36 + u * 12 + 4); //unit data address words -> rawData
			}
			for (int u = 0; u < 3; u++) {
				r = reloc(o, r, 2, (structsOff + i * 32) / 4 + u * 2); //command offsets -> commands
			}
		}
		return o;
	}

	/**
	 * Imports one texture from a donor pack into a target pack. Returns the
	 * target unchanged (same array) when the name is already present; throws
	 * IllegalArgumentException when the donor does not have the texture.
	 */
	public static byte[] importTexture(byte[] targetPack, byte[] donorPack, String textureName) {
		List<String> one = new ArrayList<>();
		one.add(textureName);
		return importTextures(targetPack, donorPack, one);
	}

	/**
	 * Imports several textures from one donor pack in a single rebuild.
	 * Already-present names are skipped; if nothing needs importing the target
	 * is returned unchanged (same array). Throws IllegalArgumentException when
	 * a requested texture is neither in the target nor in the donor.
	 */
	public static byte[] importTextures(byte[] targetPack, byte[] donorPack, List<String> textureNames) {
		List<Texture> target = parse(targetPack);
		Set<String> present = new HashSet<>();
		for (Texture t : target) {
			present.add(t.name);
		}
		List<Texture> toAdd = new ArrayList<>();
		List<Texture> donor = null;
		for (String name : textureNames) {
			if (present.contains(name)) {
				continue;
			}
			if (donor == null) {
				donor = parse(donorPack);
			}
			Texture found = null;
			for (Texture t : donor) {
				if (t.name.equals(name)) {
					found = t;
					break;
				}
			}
			if (found == null) {
				throw new IllegalArgumentException("donor pack has no texture " + name);
			}
			toAdd.add(found);
			present.add(name);
		}
		if (toAdd.isEmpty()) {
			return targetPack;
		}
		for (Texture t : toAdd) {
			int pos = 0;
			while (pos < target.size() && target.get(pos).name.compareTo(t.name) < 0) {
				pos++;
			}
			target.add(pos, t);
		}
		return emit(target);
	}

	//--- patricia name tree (converter-compatible insertion) ------------------

	private static class Node {

		long refBit;
		int left;
		int right;
		String name; //null for the root
	}

	private static boolean getBit(String name, long bit) {
		int pos = (int) (bit >>> 3);
		if (name != null && pos < name.length()) {
			return ((name.charAt(pos) >> (int) (bit & 7)) & 1) != 0;
		}
		return false;
	}

	private static int traverse(String name, List<Node> nodes, Node[] rootOut, long bit) {
		Node root = nodes.get(0);
		int out = root.left;
		Node left = nodes.get(out);
		while (root.refBit > left.refBit && left.refBit > bit) {
			out = getBit(name, left.refBit) ? left.right : left.left;
			root = left;
			left = nodes.get(out);
		}
		rootOut[0] = root;
		return out;
	}

	private static List<Node> buildTree(List<Texture> texes) {
		List<Node> nodes = new ArrayList<>();
		Node root = new Node();
		root.refBit = 0xFFFFFFFFL;
		nodes.add(root);
		int maxLen = 0;
		for (Texture t : texes) {
			maxLen = Math.max(maxLen, t.name.length());
		}
		Node[] rootOut = new Node[1];
		for (Texture t : texes) {
			long bit = (maxLen << 3) - 1;
			int index = traverse(t.name, nodes, rootOut, 0);
			while (getBit(nodes.get(index).name, bit) == getBit(t.name, bit)) {
				if (--bit < 0) {
					throw new IllegalArgumentException("duplicate texture name " + t.name);
				}
			}
			Node n = new Node();
			n.name = t.name;
			n.refBit = bit;
			if (getBit(t.name, bit)) {
				n.left = traverse(t.name, nodes, rootOut, bit);
				n.right = nodes.size();
			} else {
				n.left = nodes.size();
				n.right = traverse(t.name, nodes, rootOut, bit);
			}
			Node r = rootOut[0];
			if (getBit(t.name, r.refBit)) {
				r.right = nodes.size();
			} else {
				r.left = nodes.size();
			}
			nodes.add(n);
		}
		return nodes;
	}

	//--- byte helpers ---------------------------------------------------------

	private static int align(int v, int a) {
		return (v + a - 1) / a * a;
	}

	private static int peek(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static void poke(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >> 8);
		b[off + 2] = (byte) (v >> 16);
		b[off + 3] = (byte) (v >> 24);
	}

	private static void poke16(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >> 8);
	}

	private static String readCString(byte[] b, int off) {
		StringBuilder sb = new StringBuilder();
		for (int i = off; i < b.length && b[i] != 0; i++) {
			sb.append((char) (b[i] & 0xFF));
		}
		return sb.toString();
	}

	/**
	 * Writes one relocation entry (offset | flags << 25) and returns the
	 * advanced write position. The offset field is 25 bits wide.
	 */
	private static int reloc(byte[] o, int pos, int flags, int offset) {
		if (offset < 0 || offset >= 0x2000000) {
			throw new IllegalArgumentException("relocation offset out of 25-bit range: " + offset);
		}
		poke(o, pos, (flags << 25) | offset);
		return pos + 4;
	}
}
