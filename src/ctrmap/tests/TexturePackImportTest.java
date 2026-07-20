package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.propdata.PropDatabase;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Headless acceptance battery for BchTexturePack (cross-area texture import).
 *
 * Operates exclusively on temp-dir copies of the pristine AreaData (a/0/1/4)
 * and BuildingModels (a/0/2/3) GARC backups - nothing under the backup
 * directory is written or even opened for writing.
 *
 * Battery:
 *  (a) sweep guard - every AD subfile-1 texture pack parses, zero exceptions
 *  (d) no-op rebuild fidelity - emit(parse(p)) across all packs, expecting
 *      byte-identity (reader-equivalence asserted as the floor if not)
 *  (b) 25+ diverse donor/target import pairs, including
 *      battle01_bm_object01's textures from its Battle Resort donor area into
 *      area 0, plus smallest<->largest pack pairs; each import must satisfy:
 *      target reparses cleanly, all original textures decode byte-identical,
 *      the imported texture decodes byte-identical to the donor's, and the
 *      structural invariants hold (alphabetical order, name-set union,
 *      idempotent re-emit)
 *  (c) idempotence semantics - importing an already-present name is a NO-OP
 *      (documented contract: the unchanged target array is returned; presence
 *      is what fixes the hardlock, vanilla reuses names across areas)
 *  plus negative guards (absent donor texture, mipmapped input).
 */
public class TexturePackImportTest {

	private static final String AD_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\4";
	private static final String BM_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\2\\3";

	private static boolean pass = true;

	private static void fail(String msg) {
		System.out.println("FAIL: " + msg);
		pass = false;
	}

	public static void main(String[] args) throws Exception {
		File adSrc = new File(args.length > 0 ? args[0] : AD_GARC_PATH);
		File bmSrc = new File(args.length > 1 ? args[1] : BM_GARC_PATH);
		if (!adSrc.exists() || !bmSrc.exists()) {
			fail("pristine GARC backups not found: " + adSrc + " / " + bmSrc);
			System.exit(1);
		}
		//work on copies in java.io.tmpdir, never on the pristine backups
		File tmpDir = new File(System.getProperty("java.io.tmpdir"), "ctrmap_texpack_test");
		tmpDir.mkdirs();
		File adCopy = new File(tmpDir, "areadata_garc");
		File bmCopy = new File(tmpDir, "buildingmodels_garc");
		Files.copy(adSrc.toPath(), adCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(bmSrc.toPath(), bmCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

		GARC ad = new GARC(adCopy);
		GARC bm = new GARC(bmCopy);

		//--- (a) sweep guard: parse every area texture pack, zero exceptions --
		Map<Integer, byte[]> packs = new LinkedHashMap<>();
		Map<Integer, List<BchTexturePack.Texture>> parsed = new LinkedHashMap<>();
		int sweepExceptions = 0;
		int minTex = Integer.MAX_VALUE, maxTex = 0;
		for (int area = 0; area < ad.length; area++) {
			byte[] cont = ad.getDecompressedEntry(area);
			if (cont == null || cont.length < 8 || cont[0] != 'A' || cont[1] != 'D') {
				continue;
			}
			byte[] p = PropDatabase.getSubfile(cont, 1);
			if (!BchTexturePack.isTexturePack(p)) {
				fail("area " + area + " AD subfile 1 not recognized as texture pack");
				continue;
			}
			try {
				List<BchTexturePack.Texture> t = BchTexturePack.parse(p);
				packs.put(area, p);
				parsed.put(area, t);
				minTex = Math.min(minTex, t.size());
				maxTex = Math.max(maxTex, t.size());
			} catch (Exception ex) {
				sweepExceptions++;
				System.out.println("  sweep exception area " + area + ": " + ex);
			}
		}
		System.out.println("SWEEP: packs=" + packs.size() + " exceptions=" + sweepExceptions
				+ " textureCount min=" + minTex + " max=" + maxTex);
		if (packs.size() != 228) {
			fail("expected 228 area texture packs, got " + packs.size());
		}
		if (sweepExceptions != 0) {
			fail("sweep raised " + sweepExceptions + " parse exceptions");
		}

		//--- (d) no-op rebuild fidelity across all packs ----------------------
		int identical = 0, readerEquiv = 0, broken = 0;
		for (Map.Entry<Integer, byte[]> e : packs.entrySet()) {
			byte[] p = e.getValue();
			byte[] rebuilt = BchTexturePack.emit(parsed.get(e.getKey()));
			if (Arrays.equals(rebuilt, p)) {
				identical++;
			} else if (readerEquivalent(p, rebuilt)) {
				readerEquiv++;
				System.out.println("  area " + e.getKey() + " no-op rebuild not byte-identical (reader-equivalent)");
			} else {
				broken++;
				fail("area " + e.getKey() + " no-op rebuild not even reader-equivalent");
			}
		}
		System.out.println("NO-OP REBUILD: byteIdentical=" + identical + "/" + packs.size()
				+ " readerEquivalentOnly=" + readerEquiv + " broken=" + broken);
		if (identical != packs.size()) {
			fail("expected all " + packs.size() + " no-op rebuilds byte-identical, got " + identical);
		}

		//--- patricia name tree walk over all no-op rebuilt packs -------------
		int treesOk = 0;
		for (Map.Entry<Integer, byte[]> e : packs.entrySet()) {
			byte[] rebuilt = BchTexturePack.emit(parsed.get(e.getKey()));
			if (verifyNameTree("area " + e.getKey() + " rebuilt", rebuilt, parsed.get(e.getKey()))) {
				treesOk++;
			}
		}
		System.out.println("NAME TREES: " + treesOk + "/" + packs.size() + " rebuilt packs tree-walk clean");
		if (treesOk != packs.size()) {
			fail("patricia name tree verification failed for " + (packs.size() - treesOk) + " rebuilt packs");
		}

		//--- (b) pair 1: battle01_bm_object01 Battle Resort -> area 0 ---------
		PropDatabase db = PropDatabase.build(bm, ad);
		PropDatabase.PropModel battle01 = null;
		for (PropDatabase.PropModel m : db.getNamedModels()) {
			if ("battle01_bm_object01".equals(m.name)) {
				battle01 = m;
				break;
			}
		}
		int pairsRun = 0, pairsOk = 0;
		if (battle01 == null || battle01.donorAreas.isEmpty()) {
			fail("battle01_bm_object01 not found or has no donor areas");
		} else {
			int donorArea = battle01.donorAreas.get(0);
			byte[] modelBch = PropDatabase.getSubfile(bm.getDecompressedEntry(battle01.modelIndex), 0);
			Set<String> required = PropDatabase.getMaterialTextureNames(modelBch);
			List<String> missing = PropDatabase.getMissingTextureNames(modelBch, db.areaTextureNames.get(0));
			System.out.println("battle01_bm_object01: donor area " + donorArea + " (Battle Resort), requires "
					+ required + ", missing in area 0: " + missing);
			if (required.isEmpty()) {
				fail("battle01_bm_object01 has no material texture names");
			}
			if (missing.isEmpty()) {
				fail("battle01_bm_object01 unexpectedly texture-satisfied in area 0 - hardlock scenario gone?");
			}
			byte[] target = packs.get(0);
			byte[] donor = packs.get(donorArea);
			byte[] merged = BchTexturePack.importTextures(target, donor, missing);
			pairsRun++;
			boolean ok = true;
			for (String nm : missing) {
				ok &= checkImport("battle01->area0 [" + nm + "]", target, donor, nm, merged, missing.size());
			}
			if (!PropDatabase.getMissingTextureNames(modelBch, PropDatabase.getTexturePackTextureNames(merged)).isEmpty()) {
				fail("battle01_bm_object01 still texture-missing in area 0 after import");
				ok = false;
			}
			if (ok) {
				pairsOk++;
			}
		}

		//--- (b) format-coverage pairs: one import per rare texture format ----
		//the diverse sweep below only ever hits formats 3 (RGB565), 12 (ETC1)
		//and 13 (ETC1A4); import at least one texture of every other format
		//present in the romfs so the encoder-agnostic rebuild is proven on them
		List<Integer> areasForFormats = new ArrayList<>(packs.keySet());
		for (int wantFormat : new int[]{1, 2, 4, 5, 7}) {
			int fmtDonor = -1;
			String fmtTexName = null;
			formatSearch:
			for (int a : areasForFormats) {
				for (BchTexturePack.Texture t : parsed.get(a)) {
					if (t.format == wantFormat) {
						fmtDonor = a;
						fmtTexName = t.name;
						break formatSearch;
					}
				}
			}
			if (fmtDonor == -1) {
				fail("no vanilla pack contains a texture of format " + wantFormat);
				continue;
			}
			int fmtTarget = -1;
			for (int a : areasForFormats) {
				if (a != fmtDonor && !PropDatabase.getTexturePackTextureNames(packs.get(a)).contains(fmtTexName)) {
					fmtTarget = a;
					break;
				}
			}
			if (fmtTarget == -1) {
				fail("every area already contains texture " + fmtTexName + " (format " + wantFormat + ") - no import target");
				continue;
			}
			byte[] fmtMerged = BchTexturePack.importTexture(packs.get(fmtTarget), packs.get(fmtDonor), fmtTexName);
			pairsRun++;
			boolean fmtOk = checkImport("format" + wantFormat + " area" + fmtDonor + "->area" + fmtTarget + " [" + fmtTexName + "]",
					packs.get(fmtTarget), packs.get(fmtDonor), fmtTexName, fmtMerged, 1);
			if (fmtOk) {
				pairsOk++;
			}
			System.out.println("FORMAT PAIR: format " + wantFormat + " via '" + fmtTexName
					+ "' area " + fmtDonor + " -> area " + fmtTarget + " ok=" + fmtOk);
		}

		//--- (b) pairs 2..N: diverse donor/target sweep -----------------------
		List<Integer> areas = new ArrayList<>(packs.keySet());
		//smallest and largest packs by texture count
		int smallest = areas.get(0), largest = areas.get(0);
		for (int a : areas) {
			if (parsed.get(a).size() < parsed.get(smallest).size()) {
				smallest = a;
			}
			if (parsed.get(a).size() > parsed.get(largest).size()) {
				largest = a;
			}
		}
		List<int[]> pairPlan = new ArrayList<>();
		pairPlan.add(new int[]{smallest, largest}); //small target <- large donor
		pairPlan.add(new int[]{largest, smallest}); //large target <- small donor
		for (int k = 0; k < 60 && pairPlan.size() < 40; k++) {
			int t = areas.get((k * 37 + 3) % areas.size());
			int d = areas.get((k * 53 + 91) % areas.size());
			if (t != d) {
				pairPlan.add(new int[]{t, d});
			}
		}
		for (int[] pr : pairPlan) {
			if (pairsRun >= 30) {
				break;
			}
			int tArea = pr[0], dArea = pr[1];
			Set<String> mine = PropDatabase.getTexturePackTextureNames(packs.get(tArea));
			String pick = null;
			for (BchTexturePack.Texture t : parsed.get(dArea)) {
				if (!mine.contains(t.name)) {
					pick = t.name;
					break;
				}
			}
			if (pick == null) {
				continue; //donor has nothing this target lacks
			}
			byte[] merged = BchTexturePack.importTexture(packs.get(tArea), packs.get(dArea), pick);
			pairsRun++;
			if (checkImport("area" + dArea + "->area" + tArea + " [" + pick + "]",
					packs.get(tArea), packs.get(dArea), pick, merged, 1)) {
				pairsOk++;
			}
		}
		System.out.println("PAIRS: run=" + pairsRun + " ok=" + pairsOk
				+ " (smallest pack: area " + smallest + " with " + parsed.get(smallest).size()
				+ " tex, largest: area " + largest + " with " + parsed.get(largest).size() + " tex)");
		if (pairsRun < 25) {
			fail("fewer than 25 import pairs executed: " + pairsRun);
		}
		if (pairsOk != pairsRun) {
			fail((pairsRun - pairsOk) + " import pairs failed acceptance");
		}
		System.out.println("FORMAT COVERAGE: imported formats " + importedFormats);
		if (!importedFormats.containsAll(REQUIRED_FORMATS)) {
			Set<Integer> missing = new TreeSet<>(REQUIRED_FORMATS);
			missing.removeAll(importedFormats);
			fail("import pairs no longer cover texture formats " + missing);
		}

		//--- (c) idempotence semantics: already-present name is a no-op -------
		{
			int tArea = areas.get(0);
			byte[] target = packs.get(tArea);
			String existing = parsed.get(tArea).get(0).name;
			//donor also has to be valid; use any other pack (name need not exist there - present names are skipped before the donor is consulted)
			byte[] donor = packs.get(areas.get(1));
			byte[] res = BchTexturePack.importTexture(target, donor, existing);
			if (res != target) {
				fail("importing already-present texture must return the target unchanged (no-op semantics)");
			} else {
				System.out.println("IDEMPOTENCE: already-present import is a no-op (same array returned)");
			}
		}

		//--- negative guards --------------------------------------------------
		{
			boolean threw = false;
			try {
				BchTexturePack.importTexture(packs.get(areas.get(0)), packs.get(areas.get(1)), "no_such_texture_xyz");
			} catch (IllegalArgumentException ex) {
				threw = true;
			}
			if (!threw) {
				fail("import of a texture absent from the donor must throw");
			}

			//synthesize a mipmapped pack: patch mipLevels of texture 0
			byte[] bad = packs.get(areas.get(0)).clone();
			int contentsAdr = peek(bad, 8);
			int ptrTab = peek(bad, contentsAdr + 36) + contentsAdr;
			int st = peek(bad, ptrTab) + contentsAdr;
			bad[st + 25] = 3;
			boolean threwMip = false;
			try {
				BchTexturePack.parse(bad);
			} catch (IllegalArgumentException ex) {
				threwMip = true;
			}
			if (!threwMip) {
				fail("parse of a mipmapped texture pack must throw");
			}
			System.out.println("NEGATIVE: absentDonorTexture throws=" + threw + " mipmappedPack throws=" + threwMip);
		}

		//--- isTexturePack discrimination ------------------------------------
		{
			byte[] modelBch = PropDatabase.getSubfile(bm.getDecompressedEntry(0), 0);
			if (BchTexturePack.isTexturePack(modelBch)) {
				fail("isTexturePack accepted a prop model BCH");
			}
			if (BchTexturePack.isTexturePack(null) || BchTexturePack.isTexturePack(new byte[16])) {
				fail("isTexturePack accepted null/garbage");
			}
			for (Map.Entry<Integer, byte[]> e : packs.entrySet()) {
				if (!BchTexturePack.isTexturePack(e.getValue())) {
					fail("isTexturePack rejected vanilla pack of area " + e.getKey());
				}
			}
			System.out.println("DISCRIMINATION: all packs accepted, model BCH / garbage rejected");
		}

		if (!pass) {
			System.out.println("FAIL");
			System.exit(1);
		}
		System.out.println("PASS");
	}

	/**
	 * The four acceptance criteria for one imported texture.
	 */
	//union of texture formats exercised across every import pair; asserted against
	//REQUIRED_FORMATS at the end so coverage cannot silently rot if the pair plan changes
	private static final Set<Integer> importedFormats = new TreeSet<>();
	private static final List<Integer> REQUIRED_FORMATS = Arrays.asList(1, 2, 3, 4, 5, 7, 12, 13);

	private static boolean checkImport(String tag, byte[] target, byte[] donor, String name, byte[] merged, int addedCount) {
		boolean ok = true;
		for (BchTexturePack.Texture t : BchTexturePack.parse(donor)) {
			if (t.name.equals(name)) {
				importedFormats.add(t.format);
			}
		}
		BCHFile before = new BCHFile(target);
		BCHFile after = new BCHFile(merged);
		BCHFile donorBch = new BCHFile(donor);
		//1. target reparses
		if (after.errorlevel != 0) {
			fail(tag + ": merged pack reader errorlevel " + after.errorlevel);
			ok = false;
		}
		if (after.textures.size() != before.textures.size() + addedCount) {
			fail(tag + ": texture count " + before.textures.size() + " + " + addedCount + " != " + after.textures.size());
			ok = false;
		}
		//2. every original texture decodes byte-identical
		for (H3DTexture a : before.textures) {
			H3DTexture b = findTex(after, a.textureName);
			if (b == null || !a.textureSize.equals(b.textureSize) || !Arrays.equals(a.textureData, b.textureData)) {
				fail(tag + ": original texture '" + a.textureName + "' no longer decodes identically");
				ok = false;
			}
		}
		//3. imported texture decodes byte-identical to the donor's
		H3DTexture imp = findTex(after, name);
		H3DTexture src = findTex(donorBch, name);
		if (imp == null || src == null || !imp.textureSize.equals(src.textureSize) || !Arrays.equals(imp.textureData, src.textureData)) {
			fail(tag + ": imported texture does not decode identically to the donor's");
			ok = false;
		}
		//4. structural invariants: alphabetical order, name-set union, idempotent re-emit
		List<BchTexturePack.Texture> mergedParsed = BchTexturePack.parse(merged);
		for (int i = 1; i < mergedParsed.size(); i++) {
			if (mergedParsed.get(i - 1).name.compareTo(mergedParsed.get(i).name) > 0) {
				fail(tag + ": merged pack not alphabetically ordered at index " + i);
				ok = false;
				break;
			}
		}
		Set<String> expect = new TreeSet<>(PropDatabase.getTexturePackTextureNames(target));
		expect.add(name);
		Set<String> got = new TreeSet<>(PropDatabase.getTexturePackTextureNames(merged));
		if (!got.containsAll(expect) || !got.contains(name)) {
			fail(tag + ": merged name set wrong (missing " + name + " or an original)");
			ok = false;
		}
		if (!Arrays.equals(BchTexturePack.emit(mergedParsed), merged)) {
			fail(tag + ": emit(parse(merged)) not byte-identical - emitter not idempotent");
			ok = false;
		}
		//5. the patricia name tree of the merged pack is sound and every
		//texture name is findable through it (the game's runtime name lookup)
		if (!verifyNameTree(tag, merged, mergedParsed)) {
			ok = false;
		}
		return ok;
	}

	/**
	 * Walks the emitted patricia/dict name tree of a pack directly (node
	 * layout as BchTexturePack.emit writes it: 12-byte nodes at the texture
	 * dict's tree offset - u32 refBit, u16 left, u16 right, u32 name offset
	 * into the strings section) and checks:
	 *  - structural soundness: node count, root refBit sentinel, no dangling
	 *    (out-of-range) links, child refBits below the root sentinel
	 *  - every node's name offset resolves to that node's texture name
	 *  - every texture name in the pack is reachable via the standard patricia
	 *    search (descend while refBit strictly decreases, branch on the name
	 *    bit), terminating without cycles
	 */
	private static boolean verifyNameTree(String tag, byte[] pack, List<BchTexturePack.Texture> texes) {
		boolean ok = true;
		int contentsAdr = peek(pack, 8);
		int stringsAdr = peek(pack, 12);
		int count = peek(pack, contentsAdr + 40);
		if (count != texes.size()) {
			fail(tag + ": tree check: dict texture count " + count + " != parsed " + texes.size());
			return false;
		}
		//the texture dict (index 3) name field holds the tree offset
		int nodeBase = contentsAdr + peek(pack, contentsAdr + 3 * 12 + 8);
		int nodeCount = count + 1;
		if (nodeBase < 0 || nodeBase + nodeCount * 12 > pack.length) {
			fail(tag + ": tree nodes out of bounds");
			return false;
		}
		long rootBit = peek(pack, nodeBase) & 0xFFFFFFFFL;
		if (rootBit != 0xFFFFFFFFL) {
			fail(tag + ": tree root refBit is " + rootBit + ", expected 0xFFFFFFFF");
			ok = false;
		}
		for (int i = 0; i < nodeCount; i++) {
			int left = peekU16(pack, nodeBase + i * 12 + 4);
			int right = peekU16(pack, nodeBase + i * 12 + 6);
			if (left >= nodeCount || right >= nodeCount) {
				fail(tag + ": tree node " + i + " has dangling link (left " + left + ", right " + right + ", " + nodeCount + " nodes)");
				ok = false;
			}
			if (i > 0) {
				long refBit = peek(pack, nodeBase + i * 12) & 0xFFFFFFFFL;
				if (refBit >= rootBit) {
					fail(tag + ": tree node " + i + " refBit " + refBit + " not below the root sentinel");
					ok = false;
				}
				//emit writes node i's name offset as texture i-1's name
				String nodeName = readCString(pack, stringsAdr + peek(pack, nodeBase + i * 12 + 8));
				if (!texes.get(i - 1).name.equals(nodeName)) {
					fail(tag + ": tree node " + i + " name '" + nodeName + "' != texture '" + texes.get(i - 1).name + "'");
					ok = false;
				}
			}
		}
		if (!ok) {
			return false; //links unsafe to walk
		}
		for (BchTexturePack.Texture t : texes) {
			long prevBit = rootBit;
			int cur = peekU16(pack, nodeBase + 4); //root.left
			int steps = 0;
			boolean walked = true;
			while ((peek(pack, nodeBase + cur * 12) & 0xFFFFFFFFL) < prevBit) {
				if (++steps > nodeCount) {
					fail(tag + ": tree cycle while looking up '" + t.name + "'");
					ok = false;
					walked = false;
					break;
				}
				prevBit = peek(pack, nodeBase + cur * 12) & 0xFFFFFFFFL;
				cur = treeGetBit(t.name, prevBit)
						? peekU16(pack, nodeBase + cur * 12 + 6)
						: peekU16(pack, nodeBase + cur * 12 + 4);
			}
			if (!walked) {
				continue;
			}
			if (cur == 0) {
				fail(tag + ": tree lookup of '" + t.name + "' ended at the root");
				ok = false;
				continue;
			}
			String found = readCString(pack, stringsAdr + peek(pack, nodeBase + cur * 12 + 8));
			if (!t.name.equals(found)) {
				fail(tag + ": tree lookup of '" + t.name + "' found '" + found + "'");
				ok = false;
			}
		}
		return ok;
	}

	/**
	 * Bit convention of the converter's patricia tree: bit (b&7) of character
	 * (b>>>3), false past the end of the name.
	 */
	private static boolean treeGetBit(String name, long bit) {
		int pos = (int) (bit >>> 3);
		return pos < name.length() && ((name.charAt(pos) >> (int) (bit & 7)) & 1) != 0;
	}

	private static int peekU16(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static String readCString(byte[] b, int off) {
		if (off < 0 || off >= b.length) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = off; i < b.length && b[i] != 0; i++) {
			sb.append((char) (b[i] & 0xFF));
		}
		return sb.toString();
	}

	private static H3DTexture findTex(BCHFile f, String name) {
		for (H3DTexture t : f.textures) {
			if (t.textureName.equals(name)) {
				return t;
			}
		}
		return null;
	}

	private static boolean readerEquivalent(byte[] a, byte[] b) {
		try {
			BCHFile fa = new BCHFile(a);
			BCHFile fb = new BCHFile(b);
			if (fa.errorlevel != 0 || fb.errorlevel != 0 || fa.textures.size() != fb.textures.size()) {
				return false;
			}
			for (H3DTexture t : fa.textures) {
				H3DTexture u = findTex(fb, t.textureName);
				if (u == null || !t.textureSize.equals(u.textureSize) || !Arrays.equals(t.textureData, u.textureData)) {
					return false;
				}
			}
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	private static int peek(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}
}
