package ctrmap.tests;

import ctrmap.formats.dressup.DressUpArchive;
import ctrmap.formats.dressup.DressUpIndex;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The known-answer guard for the dress-up part index. Every claim below is
 * checked against something the archive says elsewhere - the models' own
 * names, the models' own material texture references - so a decoding that
 * drifts stops being self-consistent and this fails.
 *
 * <ul>
 * <li><b>slot agrees with the name</b>: every part model whose name carries a
 * slot word (tops, btms, hat, shoes, hair, ...) lands in the same category as
 * every other part with that word, no category means two different words, and
 * no part lands in a slot its own name does not justify. This is the check the
 * whole exercise exists for: guessing the slot from the name is exactly what
 * must NOT be trusted, so the decoded table and the names are made to agree or
 * the suite fails.</li>
 * <li><b>the relation is stored twice and agrees</b>: section 6 says which
 * items a part renders, section 2 says which parts an item uses. Every pair
 * must appear in both.</li>
 * <li><b>texture ids resolve, and to the right texture</b>: every id names a
 * subfile that really holds a texture; every design texture belongs to its own
 * item's family; and section 7's per-part ids name a texture that part's own
 * materials really bind. The first of those three is weak on its own - every
 * subfile in the range holds a texture, so reading the record one word out of
 * step still "resolves". Measured: that mutation survived until the other two
 * were added.</li>
 * <li><b>a design's flag word</b> is its texture count minus one, in every
 * record of both sets.</li>
 * <li><b>the make-up trailer</b>: the ids it carries name make-up textures,
 * and the hero's tail block names face paints.</li>
 * <li><b>the reader refuses rubbish</b> rather than returning an empty index.</li>
 * </ul>
 *
 * Usage: java ctrmap.tests.DressUpIndexTest &lt;romfs-root&gt;
 */
public class DressUpIndexTest {

	/**
	 * Slot words as they appear in a part model name. A name is
	 * {@code <prefix>_<slot>_<style>[_<compat>]} or {@code <prefix>_<slot><nn>},
	 * so these are matched against whole underscore-separated tokens (or a
	 * token's leading letters, for "face01" / "bag01" / "bngl01").
	 */
	private static final String[] SLOT_WORDS = {
		"btms", "tops", "shoes", "socks", "point", "body", "hair", "hat", "leg", "face", "bag", "bngl"
	};

	public static void main(String[] args) throws Exception {
		String root = args.length > 0 ? args[0] : "../RomFS/000400000011C400";
		File archive = new File(root + "/a/0/8/8");
		if (!archive.isFile()) {
			System.out.println("SKIP: no player-parts archive at " + archive);
			System.out.println("ALL PASS");
			return;
		}
		GARC g = new GARC(archive, true);
		int fails = 0;

		DressUpArchive da = new DressUpArchive(g);
		fails += check("two index sets found", da.sets().size() == 2);
		if (da.sets().size() != 2) {
			System.out.println("FAILURES PRESENT (" + fails + ")");
			System.exit(1);
		}

		// names and texture presence, read straight from the archive
		Map<Integer, String> modelName = new HashMap<>();
		Set<Integer> hasTexture = new HashSet<>();
		Map<Integer, Set<String>> modelTexRefs = new HashMap<>();
		Map<Integer, String> textureName = new HashMap<>();
		int lateDecompressed = 0;
		for (int i = 0; i < g.getEntryCount(); i++) {
			byte[] d;
			try {
				if (g.getEntryStoredLength(i) <= 0) {
					continue;
				}
				d = g.getDecompressedEntry(i);
			} catch (Throwable t) {
				continue;
			}
			// GARC used to hand these back still compressed - its sniff carried a
			// 64:1 ratio cap and these textures are stored at 75:1 to 172:1 - so
			// this loop had to decompress them itself. The cap is gone (measured:
			// it rejected 696 entries dump-wide and prevented nothing the size
			// ceiling did not already reject), so the workaround is gone with it.
			// The counter stays, asserted at zero below, so if the sniff ever
			// regresses this suite says so instead of silently coping again.
			if (d != null && d.length > 4 && (d[0] & 0xFF) == 0x11) {
				lateDecompressed++;
			}
			if (d == null || d.length < 4 || d[0] != 'B' || d[1] != 'C' || d[2] != 'H') {
				continue;
			}
			try {
				BCHFile b = new BCHFile(d);
				if (!b.models.isEmpty()) {
					modelName.put(i, b.models.get(0).name);
					Set<String> refs = new LinkedHashSet<>();
					for (H3DModel m : b.models) {
						for (H3DMaterial mat : m.materials) {
							for (String tn : new String[]{mat.name0, mat.name1, mat.name2}) {
								if (tn != null && !tn.isEmpty()) {
									refs.add(tn);
								}
							}
						}
					}
					modelTexRefs.put(i, refs);
				}
				if (!b.textures.isEmpty()) {
					hasTexture.add(i);
					textureName.put(i, b.textures.get(0).textureName);
				}
			} catch (Throwable t) {
				// a subfile that will not parse is simply not evidence
			}
		}

		int setNo = 0;
		int totalNamed = 0, totalParts = 0;
		for (DressUpArchive.Set s : da.sets()) {
			setNo++;
			DressUpIndex ix = s.index;
			String tag = "set " + setNo + " (" + ix.magic + ")";

			fails += check(tag + ": model base measured", s.modelBase > 0);
			fails += check(tag + ": texture base = model base + part count",
					s.textureBase == s.modelBase + ix.parts.length);
			fails += check(tag + ": every part record has a model subfile",
					modelName.containsKey(s.modelSubfile(0))
					&& modelName.containsKey(s.modelSubfile(ix.parts.length - 1)));

			// ---- the relation is stored twice, and the two agree ----------
			int both = 0, oneWay = 0;
			for (int p = 0; p < ix.parts.length; p++) {
				for (int item : ix.parts[p].items) {
					boolean back = item < ix.items.length
							&& contains(ix.items[item].partModels, p);
					if (back) {
						both++;
					} else {
						oneWay++;
					}
				}
			}
			for (DressUpIndex.Item it : ix.items) {
				for (int p : it.partModels) {
					if (p >= ix.parts.length || !contains(ix.parts[p].items, it.index)) {
						oneWay++;
					}
				}
			}
			fails += check(tag + ": part<->item relation agrees in both directions ("
					+ both + " pairs, " + oneWay + " one-way)", oneWay == 0 && both > 0);

			// ---- KNOWN ANSWER: the slot agrees with the model's own name ---
			// A model name is "<prefix>_<slot>_<style>[_<compat>]". Only the
			// FIRST slot word is the slot: the trailing one, when there is
			// one, is a footwear-compatibility qualifier - b1_btms_lpants_shoes
			// is leggings cut for shoes, not footwear. Getting that backwards
			// is precisely the guess this exercise exists to replace, so the
			// rule is stated once, here, and enforced.
			Map<String, Set<Integer>> wordSlots = new TreeMap<>();
			List<int[]> placed = new ArrayList<>();
			int named = 0;
			for (int p = 0; p < ix.parts.length; p++) {
				String nm = modelName.get(s.modelSubfile(p));
				if (nm == null) {
					continue;
				}
				String word = primarySlotWord(nm);
				int[] cats = ix.categoriesOfPart(p);
				if (word == null) {
					continue;
				}
				named++;
				if (cats.length == 0) {
					// an unused leftover may have no slot - but only if it is
					// really unused; a part that claims items must have one
					fails += check(tag + ": " + nm + " has no slot yet claims items",
							!ix.parts[p].isUsed());
					continue;
				}
				wordSlots.computeIfAbsent(word, k -> new LinkedHashSet<>()).add(cats[0]);
				placed.add(new int[]{p});
			}
			totalNamed += named;
			totalParts += ix.parts.length;

			List<String> spread = new ArrayList<>();
			for (Map.Entry<String, Set<Integer>> e : wordSlots.entrySet()) {
				if (e.getValue().size() > 1) {
					spread.add(e.getKey() + " -> " + e.getValue());
				}
			}
			fails += check(tag + ": each slot word means exactly one slot " + spread + " "
					+ wordSlots, spread.isEmpty());

			Map<Integer, Set<String>> slotWords = new TreeMap<>();
			for (Map.Entry<String, Set<Integer>> e : wordSlots.entrySet()) {
				for (int c : e.getValue()) {
					slotWords.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(e.getKey());
				}
			}
			List<String> mixed = new ArrayList<>();
			for (Map.Entry<Integer, Set<String>> e : slotWords.entrySet()) {
				if (e.getValue().size() > 1) {
					mixed.add(e.getKey() + " <- " + e.getValue());
				}
			}
			fails += check(tag + ": no slot means two different words " + mixed, mixed.isEmpty());

			// Every slot a part occupies beyond its primary one must be a slot
			// another word in its own name claims (b1_shoes_shoes_socks is
			// shoes AND socks), or a slot no word claims at all (a hairstyle
			// is listed under both its style slot and the hat-on/hat-off
			// pairing slot). A part in some third party's slot is a decoding
			// error, and nothing here quietly forgives one.
			List<String> stray = new ArrayList<>();
			for (int[] pp : placed) {
				int p = pp[0];
				String nm = modelName.get(s.modelSubfile(p));
				int[] cats = ix.categoriesOfPart(p);
				Set<Integer> own = new LinkedHashSet<>();
				for (String w : allSlotWords(nm)) {
					own.addAll(wordSlots.getOrDefault(w, new LinkedHashSet<>()));
				}
				if (cats.length > 2) {
					stray.add(nm + " in " + cats.length + " slots");
					continue;
				}
				for (int k = 1; k < cats.length; k++) {
					if (!own.contains(cats[k]) && slotWords.containsKey(cats[k])) {
						stray.add(nm + " also in slot " + cats[k] + " = " + slotWords.get(cats[k]));
					}
				}
			}
			fails += check(tag + ": no part lands in a slot its name does not justify " + stray,
					stray.isEmpty());

			// spot checks the exercise was set to answer
			fails += check(tag + ": a _tops_ part and a _shoes_ part are in different slots",
					differentSlots(ix, s, modelName, "tops", "shoes"));

			// ---- texture ids resolve to real textures ---------------------
			int texOk = 0, texBad = 0;
			for (DressUpIndex.Design d : ix.designs) {
				for (int t : d.textures) {
					if (hasTexture.contains(s.textureSubfile(t))) {
						texOk++;
					} else {
						texBad++;
					}
				}
			}
			fails += check(tag + ": every design texture id resolves to a texture subfile ("
					+ texOk + " ok, " + texBad + " bad)", texBad == 0 && texOk > 0);

			// Resolving to SOME texture is a weak claim - every subfile in that
			// range holds one. The sharp claim is that an item's designs are
			// all textures of that item: b1_tops_tshirt's designs are all
			// b1_topstshirt_*. An index read one word out of step still
			// resolves, but starts pulling in a neighbour's texture or a
			// shared lookup table, and that is what this catches.
			int famOk = 0, famBad = 0;
			List<String> famMiss = new ArrayList<>();
			for (DressUpIndex.Item it : ix.items) {
				List<String> names = new ArrayList<>();
				for (int d : designsOfItem(ix, it)) {
					for (int t : ix.designs[d].textures) {
						String tn = textureName.get(s.textureSubfile(t));
						names.add(tn == null ? "<none>" : tn);
					}
				}
				if (names.size() < 3) {
					continue;
				}
				Map<String, Integer> tally = new LinkedHashMap<>();
				for (String nm : names) {
					String k = nm.length() >= 7 ? nm.substring(0, 7) : nm;
					tally.merge(k, 1, Integer::sum);
				}
				String modal = null;
				for (Map.Entry<String, Integer> e : tally.entrySet()) {
					if (modal == null || e.getValue() > tally.get(modal)) {
						modal = e.getKey();
					}
				}
				for (String nm : names) {
					if (nm.startsWith(modal)) {
						famOk++;
					} else {
						famBad++;
						if (famMiss.size() < 6) {
							famMiss.add("item " + it.index + " " + modal + "* got " + nm);
						}
					}
				}
			}
			fails += check(tag + ": every design texture belongs to its own item's family ("
					+ famOk + " ok, " + famBad + " bad) " + famMiss, famBad == 0 && famOk > 0);

			int selfOk = 0, selfBad = 0;
			List<String> selfMiss = new ArrayList<>();
			for (int p = 0; p < ix.parts.length; p++) {
				Set<String> refs = modelTexRefs.get(s.modelSubfile(p));
				if (refs == null) {
					continue;
				}
				for (int t : ix.parts[p].textures) {
					String tn = textureName.get(s.textureSubfile(t));
					if (tn != null && refs.contains(tn)) {
						selfOk++;
					} else {
						selfBad++;
						selfMiss.add(modelName.get(s.modelSubfile(p)) + " -> " + tn);
					}
				}
			}
			fails += check(tag + ": section-7 texture ids name a texture the model itself binds ("
					+ selfOk + " ok, " + selfBad + " bad) " + selfMiss, selfBad == 0 && selfOk > 0);

			// A design's leading word is one less than its texture count in
			// every record of both sets. That is a field with a meaning, and a
			// reader that mistook it for a texture id would break it.
			int flagOk = 0, flagBad = 0;
			for (DressUpIndex.Design d : ix.designs) {
				if (d.textures.length == 0) {
					continue;
				}
				if (d.flag == d.textures.length - 1) {
					flagOk++;
				} else {
					flagBad++;
				}
			}
			fails += check(tag + ": a design's flag word is its texture count minus one ("
					+ flagOk + " ok, " + flagBad + " bad)", flagBad == 0 && flagOk > 0);

			// ---- the make-up trailer -------------------------------------
			int mkOk = 0, mkBad = 0;
			for (int t : ix.makeUpTextures) {
				String tn = textureName.get(s.textureSubfile(t));
				if (tn != null && tn.contains("_make_")) {
					mkOk++;
				} else {
					mkBad++;
				}
			}
			fails += check(tag + ": make-up trailer names make-up textures ("
					+ mkOk + " ok, " + mkBad + " bad)", mkBad == 0 && mkOk > 0);
			// the slot the id sits in is the face layer it draws into, which
			// the texture's own "_0"/"_1" suffix names independently
			int layOk = 0, layBad = 0;
			for (int k = 0; k < ix.makeUpTextures.length; k++) {
				String tn = textureName.get(s.textureSubfile(ix.makeUpTextures[k]));
				int layer = k < ix.makeUpLayers.length ? ix.makeUpLayers[k] : -1;
				if (tn != null && tn.endsWith("_" + layer)) {
					layOk++;
				} else {
					layBad++;
				}
			}
			fails += check(tag + ": each make-up texture's layer matches its own name suffix ("
					+ layOk + " ok, " + layBad + " bad)", layBad == 0 && layOk > 0);
			int fpOk = 0, fpBad = 0;
			for (int t : ix.facePaintTextures) {
				String tn = textureName.get(s.textureSubfile(t));
				if (tn != null && tn.contains("_paint_")) {
					fpOk++;
				} else {
					fpBad++;
				}
			}
			fails += check(tag + ": face-paint block names face paints (" + fpOk + " ok, "
					+ fpBad + " bad)", fpBad == 0);

			System.out.println("  " + tag + ": " + ix.parts.length + " parts, " + ix.items.length
					+ " items, " + ix.designs.length + " designs, " + ix.master.length
					+ " master rows, " + ix.categories().length + " slots; models at "
					+ s.modelBase + ", textures at " + s.textureBase + "; "
					+ ix.makeUpTextures.length + " make-up, " + ix.facePaintTextures.length
					+ " face paint");
			for (int c : ix.categories()) {
				StringBuilder sb = new StringBuilder();
				for (int p : ix.partModelsInCategory(c)) {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					sb.append(modelName.get(s.modelSubfile(p)));
				}
				System.out.println("      slot " + c + ": " + (sb.length() == 0 ? "(no models)" : sb));
			}
		}

		fails += check("most parts carry a slot word to check against (" + totalNamed
				+ " of " + totalParts + ")", totalNamed * 2 > totalParts);
		// The per-set face-paint check passes on an empty list, and only one
		// of the two sets has that block - so on its own it would go green if
		// the decoder found nothing at all, for either. This is the assertion
		// that stops a confident empty result.
		int facePaintTotal = 0;
		for (DressUpArchive.Set s : da.sets()) {
			facePaintTotal += s.index.facePaintTextures.length;
		}
		fails += check("at least one set's face-paint block decoded (" + facePaintTotal
				+ " textures)", facePaintTotal > 0);
		// Zero is the point. A non-zero count means GARC handed this suite a
		// still-compressed entry, which is the exact defect GarcSniffTest guards
		// from the other side - and it would mean every OTHER consumer of these
		// textures is getting a raw blob too.
		fails += check("GARC decompressed every compressed texture itself, so this"
				+ " suite had to decompress none (" + lateDecompressed + ")",
				lateDecompressed == 0);

		// ---- the reader refuses rubbish instead of returning empty --------
		fails += check("refuses a non-container", refuses(new byte[]{'X', 'Y', 8, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0}));
		byte[] truncated = Arrays.copyOf(g.getDecompressedEntry(da.sets().get(0).containerSubfile), 200);
		fails += check("refuses a truncated container", refuses(truncated));
		byte[] corrupt = g.getDecompressedEntry(da.sets().get(0).containerSubfile).clone();
		corrupt[4 + 4 * 3] = (byte) 0xFF; // shove a section offset out of order
		corrupt[4 + 4 * 3 + 1] = (byte) 0xFF;
		fails += check("refuses a bad section table", refuses(corrupt));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	private static boolean differentSlots(DressUpIndex ix, DressUpArchive.Set s,
			Map<Integer, String> names, String wordA, String wordB) {
		Set<Integer> a = new LinkedHashSet<>(), b = new LinkedHashSet<>();
		for (int p = 0; p < ix.parts.length; p++) {
			String nm = names.get(s.modelSubfile(p));
			if (nm == null) {
				continue;
			}
			String w = primarySlotWord(nm);
			for (int c : ix.categoriesOfPart(p)) {
				if (wordA.equals(w)) {
					a.add(c);
				}
				if (wordB.equals(w)) {
					b.add(c);
				}
			}
		}
		if (a.isEmpty() || b.isEmpty()) {
			return false;
		}
		Set<Integer> both = new LinkedHashSet<>(a);
		both.retainAll(b);
		return both.isEmpty();
	}

	/** Design ids of an item, markers dropped and out-of-range ids dropped. */
	private static List<Integer> designsOfItem(DressUpIndex ix, DressUpIndex.Item it) {
		List<Integer> out = new ArrayList<>();
		for (int d : it.designs) {
			if (d < DressUpIndex.DESIGN_MARKER_MIN && d < ix.designs.length) {
				out.add(d);
			}
		}
		return out;
	}

	private static boolean refuses(byte[] blob) {
		try {
			DressUpIndex.read(blob);
			return false;
		} catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
			return true;
		}
	}

	/**
	 * The part's own slot, from its name: the FIRST slot token after the
	 * "b1"/"b2" prefix. A later one is a compatibility qualifier, not a slot.
	 */
	private static String primarySlotWord(String name) {
		String[] tok = name.split("_");
		for (int i = 1; i < tok.length; i++) {
			for (String w : SLOT_WORDS) {
				if (tok[i].startsWith(w)) {
					return w;
				}
			}
		}
		return null;
	}

	/** Every slot token in a model name; "b1_shoes_shoes_socks" carries two. */
	private static List<String> allSlotWords(String name) {
		List<String> out = new ArrayList<>();
		for (String t : name.split("_")) {
			for (String w : SLOT_WORDS) {
				if (t.startsWith(w) && !out.contains(w)) {
					out.add(w);
				}
			}
		}
		return out;
	}

	private static boolean contains(int[] a, int v) {
		for (int x : a) {
			if (x == v) {
				return true;
			}
		}
		return false;
	}

	static int check(String what, boolean ok) {
		if (!ok) {
			System.out.println("FAIL: " + what);
			return 1;
		}
		return 0;
	}
}
