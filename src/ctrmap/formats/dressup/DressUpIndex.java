package ctrmap.formats.dressup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The dress-up (kisekae) part index that ships alongside the swappable player
 * part models. One instance decodes ONE index set; the archive carries two,
 * one per player character, each wrapped in a container whose magic is "DB"
 * or "DA".
 *
 * <p>Everything here was measured against a real dump, not read from a spec.
 * The container has eight sections; section 0 is a four-byte pad and sections
 * 1..7 are the seven tables:
 *
 * <table><caption>sections</caption>
 * <tr><th>#</th><th>meaning</th><th>shape</th></tr>
 * <tr><td>1</td><td>{@link MasterRow master}</td>
 * <td>flat 6-byte rows {@code u16 category, u16 item, u16 design}</td></tr>
 * <tr><td>2</td><td>{@link Item items}</td>
 * <td>{@code u8 nDesigns, u8 nParts, u16 kind, u16 design[], {u16 part, u16 flag}[]}</td></tr>
 * <tr><td>3</td><td>{@link Design designs}</td><td>{@code u16 flag, u16 texture[]}</td></tr>
 * <tr><td>4</td><td>face texture sets</td><td>prefixed offset table, {@code u16 flag, u16 texture[]}</td></tr>
 * <tr><td>5</td><td>hair texture sets</td><td>same shape as 4</td></tr>
 * <tr><td>6</td><td>{@link Part parts}</td>
 * <td>one record per part model: {@code u16 item[], u16 0xFF00|flag}</td></tr>
 * <tr><td>7</td><td>part textures, then a make-up trailer</td>
 * <td>one {@code u16 texture[]} per part model</td></tr>
 * </table>
 *
 * <p>Sections 2..7 are self-describing offset arrays: the first {@code u16} is
 * the byte offset of the first record AND therefore the size of the offset
 * array itself, so the record count needs no separate field. Sections 4 and 5
 * put a small prefix ({@code u16 n, u16 item[n]}) in front of that array,
 * naming the items whose textures they carry.
 *
 * <p>Section 6 has exactly one record per part model in the archive, in
 * archive order, so record <i>i</i> is the <i>i</i>th part model of that set.
 * Its item ids index section 2, and section 2's part ids index section 6 back
 * again - the relation is stored in both directions and the two agree.
 *
 * <p><b>What an editor wants from this</b>: {@link #categoriesOfPart(int)}
 * gives the dress-up slot(s) a part model occupies, and
 * {@link #designsOfItem(int)} the texture variants legal for an item.
 * Category numbers are per-set ordinals with no name in the data - the archive
 * never spells "hat" anywhere - so a caller that wants a label must take it
 * from the part model's own name. Anything unknown is -1 or empty, never a
 * guess.
 */
public class DressUpIndex {

	/** Terminator marker in the high byte of a part record's last word. */
	public static final int PART_TERMINATOR = 0xFF00;
	/** A design id at or above this is a marker, not an index into {@link #designs}. */
	public static final int DESIGN_MARKER_MIN = 0xFF00;
	/** Bytes per make-up record in the section-7 trailer. */
	private static final int MAKEUP_RECORD = 28;

	/** One row of the master table: a wearable choice, in menu order. */
	public static class MasterRow {

		/** Dress-up slot ordinal. Per-set; the data carries no name for it. */
		public final int category;
		/** Index into {@link #items}. */
		public final int item;
		/** Index into {@link #designs}, or {@code >= DESIGN_MARKER_MIN} for a marker. */
		public final int design;

		MasterRow(int category, int item, int design) {
			this.category = category;
			this.item = item;
			this.design = design;
		}
	}

	/** One wearable item: a style, with its designs and the models that draw it. */
	public static class Item {

		public final int index;
		/** Design ids; a value {@code >= DESIGN_MARKER_MIN} means "see the face/hair table". */
		public final int[] designs;
		/** Part-model indices that render this item. May be empty (an unused item). */
		public final int[] partModels;
		/** Per-part flag, parallel to {@link #partModels}. */
		public final int[] partFlags;
		/** Record kind byte. Meaning not established. */
		public final int kind;

		Item(int index, int[] designs, int[] partModels, int[] partFlags, int kind) {
			this.index = index;
			this.designs = designs;
			this.partModels = partModels;
			this.partFlags = partFlags;
			this.kind = kind;
		}
	}

	/** One design/colour variant: the textures it swaps in. */
	public static class Design {

		public final int index;
		/** Leading word; 0 or 1 in the measured data, meaning not established. */
		public final int flag;
		/** Texture ids, relative to the set's own texture block. */
		public final int[] textures;

		Design(int index, int flag, int[] textures) {
			this.index = index;
			this.flag = flag;
			this.textures = textures;
		}
	}

	/** One part model's entry: which items it can render, and under what flag. */
	public static class Part {

		public final int index;
		/** Item indices; empty for a model the index does not use. */
		public final int[] items;
		/**
		 * Low byte of the record terminator: a leg/foot compatibility
		 * discriminator (leggings-for-boots vs leggings-for-shoes, socks worn
		 * under boots vs under shoes, and so on). 0xFF when the record carries
		 * none, -1 when the record is empty.
		 */
		public final int flag;
		/** Extra texture ids the model itself binds (toon/highlight lookups and the like). */
		public final int[] textures;

		Part(int index, int[] items, int flag, int[] textures) {
			this.index = index;
			this.items = items;
			this.flag = flag;
			this.textures = textures;
		}

		public boolean isUsed() {
			return items.length > 0;
		}
	}

	/** A face or hair texture set: one row of section 4 or 5. */
	public static class TextureSet {

		public final int index;
		public final int flag;
		public final int[] textures;

		TextureSet(int index, int flag, int[] textures) {
			this.index = index;
			this.flag = flag;
			this.textures = textures;
		}
	}

	/** Container magic: "DB" or "DA". */
	public final String magic;
	public final MasterRow[] master;
	public final Item[] items;
	public final Design[] designs;
	/** Items whose face textures section 4 carries. */
	public final int[] faceItems;
	public final TextureSet[] faceTextures;
	/** Items whose hair textures section 5 carries. */
	public final int[] hairItems;
	public final TextureSet[] hairTextures;
	public final Part[] parts;
	/** Make-up texture ids from the section-7 trailer; empty when it has none. */
	public final int[] makeUpTextures;
	/** Face-paint texture ids from the trailer's tail block; empty when absent. */
	public final int[] facePaintTextures;
	/** The section-7 trailer, verbatim - not every field in it is decoded. */
	public final byte[] trailer;

	private final int[] itemCategory;

	private DressUpIndex(String magic, MasterRow[] master, Item[] items, Design[] designs,
			int[] faceItems, TextureSet[] faceTextures, int[] hairItems, TextureSet[] hairTextures,
			Part[] parts, int[] makeUp, int[] facePaint, byte[] trailer) {
		this.magic = magic;
		this.master = master;
		this.items = items;
		this.designs = designs;
		this.faceItems = faceItems;
		this.faceTextures = faceTextures;
		this.hairItems = hairItems;
		this.hairTextures = hairTextures;
		this.parts = parts;
		this.makeUpTextures = makeUp;
		this.facePaintTextures = facePaint;
		this.trailer = trailer;
		this.itemCategory = new int[items.length];
		Arrays.fill(this.itemCategory, -1);
		for (MasterRow r : master) {
			if (r.item >= 0 && r.item < itemCategory.length && itemCategory[r.item] == -1) {
				itemCategory[r.item] = r.category;
			}
		}
	}

	// ---------------------------------------------------------------- queries

	/** Dress-up slot of an item, or -1 when the master table never lists it. */
	public int categoryOfItem(int item) {
		return (item >= 0 && item < itemCategory.length) ? itemCategory[item] : -1;
	}

	/**
	 * Dress-up slot(s) a part model occupies, in the order the part lists them.
	 * Empty for a model the index does not use (an unfinished leftover, or the
	 * assembled default player model). Usually one; a combined mesh - shoes
	 * with the socks baked in - can occupy two.
	 */
	public int[] categoriesOfPart(int partModel) {
		if (partModel < 0 || partModel >= parts.length) {
			return new int[0];
		}
		Set<Integer> cats = new LinkedHashSet<>();
		for (int item : parts[partModel].items) {
			int c = categoryOfItem(item);
			if (c != -1) {
				cats.add(c);
			}
		}
		return toIntArray(cats);
	}

	/** The single slot a part occupies, or -1 when it has none or more than one. */
	public int categoryOfPart(int partModel) {
		int[] c = categoriesOfPart(partModel);
		return c.length == 1 ? c[0] : -1;
	}

	/** Part-model indices that render anything in a slot, in archive order. */
	public int[] partModelsInCategory(int category) {
		List<Integer> out = new ArrayList<>();
		for (int p = 0; p < parts.length; p++) {
			for (int c : categoriesOfPart(p)) {
				if (c == category) {
					out.add(p);
					break;
				}
			}
		}
		return toIntArray(out);
	}

	/** Item indices in a slot, in master-table order. */
	public int[] itemsInCategory(int category) {
		Set<Integer> out = new LinkedHashSet<>();
		for (MasterRow r : master) {
			if (r.category == category) {
				out.add(r.item);
			}
		}
		return toIntArray(out);
	}

	/** Every slot ordinal the master table uses, ascending. */
	public int[] categories() {
		Set<Integer> out = new LinkedHashSet<>();
		for (MasterRow r : master) {
			out.add(r.category);
		}
		int[] a = toIntArray(out);
		Arrays.sort(a);
		return a;
	}

	/**
	 * Design ids legal for an item: the real ones only, markers dropped. An
	 * item whose designs are all markers (hair, face) takes its textures from
	 * {@link #hairTextures} / {@link #faceTextures} instead.
	 */
	public int[] designsOfItem(int item) {
		if (item < 0 || item >= items.length) {
			return new int[0];
		}
		List<Integer> out = new ArrayList<>();
		for (int d : items[item].designs) {
			if (d < DESIGN_MARKER_MIN) {
				out.add(d);
			}
		}
		return toIntArray(out);
	}

	/** Texture ids a part model may legally wear, via every item that uses it. */
	public int[] texturesOfPart(int partModel) {
		if (partModel < 0 || partModel >= parts.length) {
			return new int[0];
		}
		Set<Integer> out = new LinkedHashSet<>();
		for (int item : parts[partModel].items) {
			for (int d : designsOfItem(item)) {
				if (d < designs.length) {
					for (int t : designs[d].textures) {
						out.add(t);
					}
				}
			}
		}
		return toIntArray(out);
	}

	// ----------------------------------------------------------------- decode

	/**
	 * Decodes one index set from its container blob.
	 *
	 * @param c the whole "DB"/"DA" subfile, decompressed
	 * @return the decoded index
	 * @throws IllegalArgumentException when the blob is not one of these
	 * containers, or a section does not parse - never a silently empty result
	 */
	public static DressUpIndex read(byte[] c) {
		if (c == null || c.length < 16) {
			throw new IllegalArgumentException("dress-up container too short");
		}
		String mg = "" + (char) (c[0] & 0xFF) + (char) (c[1] & 0xFF);
		if (!isContainer(c)) {
			throw new IllegalArgumentException("not a dress-up index container: magic '" + mg + "'");
		}
		int sections = u16(c, 2);
		int[] off = new int[sections + 1];
		for (int i = 0; i <= sections; i++) {
			off[i] = u32(c, 4 + 4 * i);
			if (off[i] < 0 || off[i] > c.length || (i > 0 && off[i] < off[i - 1])) {
				throw new IllegalArgumentException("dress-up container section table is not monotonic");
			}
		}
		byte[][] sec = new byte[sections][];
		for (int i = 0; i < sections; i++) {
			sec[i] = Arrays.copyOfRange(c, off[i], off[i + 1]);
		}

		MasterRow[] master = readMaster(sec[1]);
		Item[] items = readItems(sec[2]);
		Design[] designs = readDesigns(sec[3]);
		int[] faceItems = readPrefix(sec[4]);
		TextureSet[] faceTex = readTextureSets(sec[4], 2 + 2 * faceItems.length);
		int[] hairItems = readPrefix(sec[5]);
		TextureSet[] hairTex = readTextureSets(sec[5], 2 + 2 * hairItems.length);

		int[] texEnd = new int[1];
		int[][] partTextures = readWordLists(sec[7], texEnd);
		byte[] tail = Arrays.copyOfRange(sec[7], texEnd[0], sec[7].length);
		Part[] parts = readParts(sec[6], partTextures);
		if (partTextures.length != parts.length) {
			throw new IllegalArgumentException("section 7 has " + partTextures.length
					+ " records but section 6 has " + parts.length);
		}

		int mk = makeUpStart(tail);
		return new DressUpIndex(mg, master, items, designs, faceItems, faceTex,
				hairItems, hairTex, parts, makeUp(tail, mk), facePaint(tail, mk), tail);
	}

	/** True when the blob looks like one of these containers - magic and a sane section table. */
	public static boolean isContainer(byte[] c) {
		if (c == null || c.length < 16) {
			return false;
		}
		String mg = "" + (char) (c[0] & 0xFF) + (char) (c[1] & 0xFF);
		if (!"DB".equals(mg) && !"DA".equals(mg)) {
			return false;
		}
		int sections = u16(c, 2);
		if (sections != 8 || 4 + 4 * (sections + 1) > c.length) {
			return false;
		}
		int prev = -1;
		for (int i = 0; i <= sections; i++) {
			int o = u32(c, 4 + 4 * i);
			if (o < 0 || o > c.length || o < prev) {
				return false;
			}
			prev = o;
		}
		return u32(c, 4 + 4 * sections) == c.length;
	}

	private static MasterRow[] readMaster(byte[] b) {
		if (b.length % 6 != 0) {
			throw new IllegalArgumentException("master table is " + b.length + " bytes, not a multiple of 6");
		}
		MasterRow[] out = new MasterRow[b.length / 6];
		for (int i = 0; i < out.length; i++) {
			out[i] = new MasterRow(u16(b, i * 6), u16(b, i * 6 + 2), u16(b, i * 6 + 4));
		}
		return out;
	}

	/** Offsets of a self-describing offset array starting at {@code start}. */
	private static int[] offsets(byte[] b, int start) {
		if (b.length < start + 2) {
			throw new IllegalArgumentException("section too short for an offset array");
		}
		int first = u16(b, start);
		if (first <= start || (first & 1) != 0 || first > b.length) {
			throw new IllegalArgumentException("bad offset-array header: " + first);
		}
		int n = (first - start) / 2;
		int[] off = new int[n];
		for (int i = 0; i < n; i++) {
			off[i] = u16(b, start + 2 * i);
			if (off[i] > b.length || (i > 0 && off[i] < off[i - 1])) {
				throw new IllegalArgumentException("offset array is not monotonic at " + i);
			}
		}
		return off;
	}

	/** {@code u16 n, u16 v[n]} prefix in front of a section's offset array. */
	private static int[] readPrefix(byte[] b) {
		int n = u16(b, 0);
		if (n < 0 || 2 + 2 * n > b.length) {
			throw new IllegalArgumentException("bad section prefix count " + n);
		}
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[i] = u16(b, 2 + 2 * i);
		}
		return out;
	}

	private static Item[] readItems(byte[] b) {
		int[] off = offsets(b, 0);
		Item[] out = new Item[off.length - 1];
		for (int i = 0; i < out.length; i++) {
			int p = off[i], end = off[i + 1];
			int nDesigns = b[p] & 0xFF;
			int nParts = b[p + 1] & 0xFF;
			int kind = u16(b, p + 2);
			int need = 4 + 2 * nDesigns + 4 * nParts;
			if (p + need != end) {
				throw new IllegalArgumentException("item record " + i + " is " + (end - p)
						+ " bytes, its own fields say " + need);
			}
			int[] d = new int[nDesigns];
			for (int k = 0; k < nDesigns; k++) {
				d[k] = u16(b, p + 4 + 2 * k);
			}
			int[] pm = new int[nParts];
			int[] pf = new int[nParts];
			for (int k = 0; k < nParts; k++) {
				pm[k] = u16(b, p + 4 + 2 * nDesigns + 4 * k);
				pf[k] = u16(b, p + 4 + 2 * nDesigns + 4 * k + 2);
			}
			out[i] = new Item(i, d, pm, pf, kind);
		}
		return out;
	}

	private static Design[] readDesigns(byte[] b) {
		int[] off = offsets(b, 0);
		Design[] out = new Design[off.length - 1];
		for (int i = 0; i < out.length; i++) {
			out[i] = flagged(b, off[i], off[i + 1], i);
		}
		return out;
	}

	private static TextureSet[] readTextureSets(byte[] b, int start) {
		int[] off = offsets(b, start);
		TextureSet[] out = new TextureSet[off.length - 1];
		for (int i = 0; i < out.length; i++) {
			Design d = flagged(b, off[i], off[i + 1], i);
			out[i] = new TextureSet(i, d.flag, d.textures);
		}
		return out;
	}

	/** A record shaped {@code u16 flag, u16 rest[]}. */
	private static Design flagged(byte[] b, int p, int end, int index) {
		if (end <= p) {
			return new Design(index, -1, new int[0]);
		}
		int[] t = new int[(end - p) / 2 - 1];
		for (int k = 0; k < t.length; k++) {
			t[k] = u16(b, p + 2 + 2 * k);
		}
		return new Design(index, u16(b, p), t);
	}

	/** Records that are simply a list of words; reports where the record run ends. */
	private static int[][] readWordLists(byte[] b, int[] endOut) {
		int[] off = offsets(b, 0);
		int[][] out = new int[off.length - 1][];
		for (int i = 0; i < out.length; i++) {
			int p = off[i], end = off[i + 1];
			int[] v = new int[(end - p) / 2];
			for (int k = 0; k < v.length; k++) {
				v[k] = u16(b, p + 2 * k);
			}
			out[i] = v;
		}
		endOut[0] = off[off.length - 1];
		return out;
	}

	private static Part[] readParts(byte[] b, int[][] partTextures) {
		int[] off = offsets(b, 0);
		Part[] out = new Part[off.length - 1];
		for (int i = 0; i < out.length; i++) {
			int p = off[i], end = off[i + 1];
			int[] tex = (partTextures != null && i < partTextures.length) ? partTextures[i] : new int[0];
			if (end == p) {
				out[i] = new Part(i, new int[0], -1, tex);
				continue;
			}
			int words = (end - p) / 2;
			int last = u16(b, p + 2 * (words - 1));
			if ((last & 0xFF00) != PART_TERMINATOR) {
				throw new IllegalArgumentException("part record " + i + " does not end in a 0xFFxx terminator");
			}
			int[] it = new int[words - 1];
			for (int k = 0; k < it.length; k++) {
				it[k] = u16(b, p + 2 * k);
			}
			out[i] = new Part(i, it, last & 0xFF, tex);
		}
		return out;
	}

	/**
	 * Start of the make-up record run in the section-7 trailer, or -1.
	 * <p>The trailer's own header is not decoded, so the run is LOCATED rather
	 * than assumed: the first offset where two consecutive 28-byte records
	 * carry the same leading word and the indices 0 then 1. Nothing here
	 * depends on a guessed header length.
	 */
	private static int makeUpStart(byte[] t) {
		for (int p = 0; p + 2 * MAKEUP_RECORD <= t.length; p += 2) {
			int kind = u16(t, p);
			if (kind == 0 || kind >= 0xFF00) {
				continue;
			}
			if (u16(t, p + 2) == 0 && u16(t, p + MAKEUP_RECORD) == kind
					&& u16(t, p + MAKEUP_RECORD + 2) == 1) {
				return p;
			}
		}
		return -1;
	}

	/** How many 28-byte records the run at {@code start} holds. */
	private static int makeUpCount(byte[] t, int start) {
		if (start < 0) {
			return 0;
		}
		int kind = u16(t, start);
		int n = 0;
		for (int p = start; p + MAKEUP_RECORD <= t.length && u16(t, p) == kind; p += MAKEUP_RECORD) {
			n++;
		}
		return n;
	}

	/**
	 * Make-up texture ids. Each 28-byte record carries exactly one real
	 * texture id across three {@code u16} slots, the other two being 0xFFFF;
	 * only that id is decoded, the rest of the record is not.
	 */
	private static int[] makeUp(byte[] t, int start) {
		int n = makeUpCount(t, start);
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int p = start + i * MAKEUP_RECORD;
			int found = -1;
			for (int s = 0; s < 3; s++) {
				int v = u16(t, p + 5 + 2 * s);
				if (v != 0xFFFF) {
					if (found != -1) {
						found = -1;
						break; // ambiguous: report nothing rather than a guess
					}
					found = v;
				}
			}
			if (found == -1) {
				return new int[0];
			}
			out.add(found);
		}
		return toIntArray(out);
	}

	/**
	 * Face-paint texture ids: an optional tail block after the make-up records,
	 * {@code u32 blockLength} then four-byte entries {@code u8, u8, u16 texture}.
	 */
	private static int[] facePaint(byte[] t, int start) {
		int n = makeUpCount(t, start);
		if (n == 0) {
			return new int[0];
		}
		int p = start + n * MAKEUP_RECORD;
		if (p + 8 > t.length) {
			return new int[0];
		}
		int len = u32(t, p);
		if (len < 8 || (len - 4) % 4 != 0 || p + len != t.length) {
			return new int[0];
		}
		int[] out = new int[(len - 4) / 4];
		for (int i = 0; i < out.length; i++) {
			out[i] = u16(t, p + 4 + 4 * i + 2);
		}
		return out;
	}

	// ------------------------------------------------------------------ bytes

	private static int u16(byte[] b, int p) {
		return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
	}

	private static int u32(byte[] b, int p) {
		return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
				| ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
	}

	private static int[] toIntArray(Collection<Integer> c) {
		int[] a = new int[c.size()];
		int i = 0;
		for (int v : c) {
			a[i++] = v;
		}
		return a;
	}
}
