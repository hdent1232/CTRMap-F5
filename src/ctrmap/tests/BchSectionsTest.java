package ctrmap.tests;

import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchSections;
import ctrmap.formats.h3d.BchTexturePack;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every BCH reader reads the header and the relocation table through one reader, and still makes
 * of a file what it made of it before (F066).
 *
 * <p>WHY THIS SUITE EXISTS. Five classes read the BCH header themselves and four decoded the
 * relocation table themselves; the area animation placed every relocation as if it came from the
 * contents section, so a command-section entry was recorded at a contents address - on the texture
 * pack below, on top of a real contents entry, overwriting its flag. Before the readers were merged,
 * what each made of two dump-free files was recorded (PINS); every reader but the area animation
 * must still make exactly that of them, and the area animation must now record exactly the
 * contents relocations and the command relocations, each where it is. The BCHFile materials and
 * textures pins were added the same way before the material decoder was cut down to what the 3D
 * view reads and the command reader's 64K table became a sparse one (F071, F079): measured on the
 * old decoder, so the new one is held to what the old one read.
 *
 * <p>ORDER: needs no dump. The subjects are the bundled DummyBCH3DModel.bch and a two-texture pack
 * BchTexturePack writes; their digests are recorded with the pins, so a changed writer reads as a
 * changed subject, not as a broken reader.
 *
 * Usage: java ctrmap.tests.BchSectionsTest
 */
public class BchSectionsTest {

	static int fails = 0;

	/** SHA-256 of the two subjects the pins were taken against, measured 2026-10-07. */
	static final String DUMMY_SHA = "84ae2a8c4d2752d70fcc85f7fdbce236db86755028d8ac40df91a4bc05d89821";
	static final String PACK_SHA = "c0e924d2244fe1d03da02339df981b9312c7245ee6e018afa54261b9798f5f6c";

	/**
	 * Measured 2026-10-07 by a scratch probe over the readers as they stood before F066 (the working
	 * tree over a93ef21): the first 16 hex digits of the SHA-256 of each reader's line, built exactly
	 * as {@link #describe} builds it. WorldAnim's two are the corrected decode's, taken after the
	 * merge, when the probe showed every other line unchanged.
	 *
	 * <p>RE-TAKEN 2026-10-08 (F228), three of them, from this suite's own lines: both BCHFile buf
	 * hashes and the dummy's materials. They were measured on a BCHFile whose relocation wrote
	 * through a wrapped buffer's absolute putInt(0, ...), so every relocation also overwrote the
	 * first four bytes of the file; and on a material reader that read a zero name pointer as a
	 * string at offset 0. A control run with the old classes overlaid (scratch f228_control.py)
	 * showed the old pins passing on the old code, the buf hashes moving with the relocation fix
	 * alone (the unused texture names then read the intact magic, "BCH"), and the names going
	 * empty with the zero-pointer fix. No other pin moved.
	 */
	static final String[][] PINS = {
		{"dummy", "BchMapModel header", "9818d7d5db7f358a"},
		{"dummy", "BchMapModel ptrLocToTargetBase", "4cc9452441f3f952"},
		{"dummy", "BchMapModel cmdRelocs", "d0feb3f97b151fe8"},
		{"dummy", "BchMapModel vtxBuffers", "384e054c9c75705f"},
		{"dummy", "BchMapModel idxBuffers", "873b04acd3770903"},
		{"dummy", "BchModelAppender.decode", "50aebf4d45bd7277"},
		{"dummy", "WorldAnim relocMap", "5bbe1462b2b638a6"},
		{"dummy", "BCHFile header", "c4e058d8f9be5463"},
		{"dummy", "BCHFile buf hash", "8052dffc65ba8f55"},
		{"dummy", "BchTexturePack.isTexturePack", "2692a0af9f0693e2"},
		{"dummy", "BchTexturePack.parse", "99d19c69b02e9a3e"},
		{"dummy", "BCHFile materials", "14f7934a14250e58"},
		{"dummy", "BCHFile textures", "451a3774a42a3529"},
		{"pack", "BchMapModel header", "0f31a1a826c63e16"},
		{"pack", "BchMapModel ptrLocToTargetBase", "9b63805e6aeb1576"},
		{"pack", "BchMapModel cmdRelocs", "8108c637f88b03dc"},
		{"pack", "BchMapModel vtxBuffers", "8f2ac0fc5b636a78"},
		{"pack", "BchMapModel idxBuffers", "b14633bf9a73d787"},
		{"pack", "BchModelAppender.decode", "46f9789913a88a59"},
		{"pack", "WorldAnim relocMap", "0496f869d436c21c"},
		{"pack", "BCHFile header", "8e2f900b177d61c7"},
		{"pack", "BCHFile buf hash", "b4f0244848b6d5d6"},
		{"pack", "BchTexturePack.isTexturePack", "cb3e12ac91218e98"},
		{"pack", "BchTexturePack.parse", "5e23a7d9eff36236"},
		{"pack", "BCHFile materials", "8e0ef6f644e726b4"},
		{"pack", "BCHFile textures", "3f846913af3d3414"},
	};

	/** One section of the suite. */
	interface Section {

		void run() throws Exception;
	}

	/**
	 * Runs a section so that one that throws is a failure said in words and the rest still run: a
	 * suite that dies at its first exception reports nothing about the checks after it.
	 */
	static void section(String name, Section s) {
		try {
			s.run();
		} catch (Throwable t) {
			check(false, name + " threw instead of answering: " + t);
		}
	}

	public static void main(String[] args) throws Exception {
		byte[] dummy = resource("ctrmap/resources/DummyBCH3DModel.bch");
		byte[] pack;
		String packSaid;
		try {
			pack = texturePack();
			packSaid = sha(pack).substring(0, 16);
		} catch (RuntimeException writerThrew) {
			pack = null;
			packSaid = "the writer threw: " + writerThrew;
		}
		boolean subjects = pack != null && sha(dummy).equals(DUMMY_SHA) && sha(pack).equals(PACK_SHA);
		check(subjects, "the subjects are the ones the pins were taken against (dummy " + sha(dummy).substring(0, 16)
				+ ", pack " + packSaid + ")" + (subjects ? "" : " - a changed subject is"
				+ " UNKNOWN to the pins, not a broken reader: re-take them with the probe the record names"));
		if (subjects) {
			final byte[] d = dummy, p = pack;
			section("the pinned readers", () -> everyReaderMakesOfThemWhatItDid(d, p));
			section("the area animation's relocations", () -> theAreaAnimationRecordsEachRelocationWhereItIs(d, p));
		}
		section("the header layouts", BchSectionsTest::theHeaderIsReadForEveryLayout);
		section("the refusals", BchSectionsTest::whatIsNotABchIsRefused);
		section("the string readers", BchSectionsTest::aStringNothingEndsIsRefused);
		section("the map-model verifier", () -> theVerifierReportsAModelWithNothingToDraw(dummy));
		section("the appender's refusal", BchSectionsTest::anAppendRefusalSaysWhatDidNotParse);
		section("a material's unlisted values", () -> aMaterialValueThePortDidNotListOpens(dummy));
		section("a relocation writes where it is told", BchSectionsTest::aRelocationWritesOnlyWhereItIsTold);
		section("the BCH reader's position", BchSectionsTest::theReaderIsWhereItRead);
		section("another revision, and a mesh past the last", () -> anotherRevisionAndAMeshPastTheLastAreRefused(dummy,
				texturePack()));
		section("a prefab's donor that will not parse", () -> aPrefabsDonorThatWillNotParseIsRefused(dummy));
		section("a command block past register 0xFFFF", BchSectionsTest::aCommandBlockPastTheLastRegisterIsRefused);
		section("a mesh whose index buffer cannot be read", () -> aMeshWhoseIndexBufferCannotBeReadIsRefused(dummy));
		section("each texture unit's mirrored wrap", () -> eachTextureUnitMirrorsOnlyAsItsMapperSays(dummy));
		section("a BCH that fails partway", BchSectionsTest::aBchThatFailsPartwayHandsOutNothing);
		section("every BCH the editor reads", () -> everyBchTheEditorReadsIsClassified(new java.io.File("src/ctrmap")));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The map-model verifier reports a model with no meshes; it threw IndexOutOfBounds on one, reading
	 * the first mesh before asking whether there was one (F063). The subject is the bundled dummy model
	 * with its mesh count set to 0, which still parses.
	 */
	static void theVerifierReportsAModelWithNothingToDraw(byte[] dummy) throws Exception {
		System.out.println("--- the map-model verifier reports a model with nothing to draw (F063)");
		ctrmap.formats.h3d.BchMapModel whole = new ctrmap.formats.h3d.BchMapModel(dummy);
		check(whole.meshCount == 1, "control: the dummy model has the one mesh the edit below takes away");
		byte[] empty = dummy.clone();
		int at = whole.modelPtr + 0x44;   //the model header's mesh count
		empty[at] = empty[at + 1] = empty[at + 2] = empty[at + 3] = 0;
		check(new ctrmap.formats.h3d.BchMapModel(empty).meshCount == 0, "control: the edited copy parses with no meshes");
		java.util.List<String> said;
		try {
			said = ctrmap.formats.h3d.BchModelVerifier.verify(empty);
		} catch (RuntimeException threw) {
			said = null;
			check(false, "the verifier reports a model with no meshes rather than throwing: " + threw);
		}
		if (said != null) {
			check(said.contains("no meshes"), "the verifier reports a model with no meshes rather than throwing: " + said);
		}
		java.util.List<String> again = ctrmap.formats.h3d.BchModelVerifier.verify(dummy);
		check(!again.contains("no meshes") && again.equals(ctrmap.formats.h3d.BchModelVerifier.verify(dummy)),
				"and the dummy model's own report is unchanged by verifying another between: " + again.size() + " finding(s)");
	}

	/**
	 * A texture pack of another BCH revision, and a mesh the model does not have, are each refused in their own words.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (BchTexturePack 113 and BchMapModel 696, both dropped, SURVIVED): no
	 * pack of another revision, and no mesh past the last, had been asked for. Dropped, the pack was read as the 0x21
	 * layout it may not be, and the mesh was refused by the list's index error, naming no model.
	 */
	static void anotherRevisionAndAMeshPastTheLastAreRefused(byte[] dummy, byte[] pack) {
		System.out.println("--- a pack of another BCH revision, and a mesh past the last, are refused in their words");
		check(!BchTexturePack.parse(pack).isEmpty(), "control: the pack parses to its textures");
		byte[] other = pack.clone();
		other[4] = 0x22;
		String revision;
		try {
			revision = "it read " + BchTexturePack.parse(other).size() + " texture(s)";
		} catch (IllegalArgumentException refused) {
			revision = refused.getMessage();
		} catch (RuntimeException crash) {
			revision = "it failed some other way: " + crash;
		}
		check(revision.equals("unsupported BCH backwardCompat 34 (expected 0x21)"),
				"a texture pack of BCH revision 0x22 is refused, naming it: " + revision);
		BchMapModel m = new BchMapModel(dummy);
		check(m.meshCount > 0 && m.getTriangles(0).length > 0, "control: the dummy model's mesh 0 has triangles");
		String past;
		try {
			past = "it answered " + m.getTriangles(m.meshCount).length + " index(es)";
		} catch (IllegalArgumentException refused) {
			past = refused.getMessage();
		} catch (RuntimeException crash) {
			past = "it failed some other way: " + crash;
		}
		check(past.equals("there is no mesh " + m.meshCount + " - the model has " + m.meshCount),
				"a mesh past the model's last is refused, naming the model's count: " + past);
	}

	/**
	 * A prefab whose embedded donor model passes the header check and will not parse is refused when it stamps, naming
	 * the prefab - never stamped with the check the donor feeds switched off.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (MapPrefab 558, dropped, SURVIVED): no donor that reads as a map model
	 * by its header and fails its parse had been stamped. Dropped, the donor stayed null, which switches off the check
	 * that a fast-path target has the donor's vertex layout and not merely its stride (F211).
	 */
	static void aPrefabsDonorThatWillNotParseIsRefused(byte[] dummy) {
		System.out.println("--- a prefab whose donor model will not parse is refused when it stamps, naming it");
		byte[] torn = dummy.clone();
		//the models dictionary's pointer, sent past the end: the header still reads as one map model
		torn[0x44] = torn[0x45] = torn[0x46] = (byte) 0xFF;
		torn[0x47] = 0x0F;
		String parse;
		try {
			parse = "it parsed, " + new BchMapModel(torn).meshCount + " mesh(es)";
		} catch (RuntimeException refused) {
			parse = "refused";
		}
		check(BchMapModel.isMapModel(torn) && parse.equals("refused"),
				"control: the torn donor reads as a map model by its header and will not parse (" + parse + ")");
		ctrmap.formats.h3d.MapPrefab p = new ctrmap.formats.h3d.MapPrefab();
		p.name = "torn donor";
		p.donorModel = torn;
		String said;
		try {
			said = p.stampGeometry(dummy, 0, 0, 0f) == null ? "it answered no result" : "it stamped";
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		} catch (RuntimeException other) {
			said = "it failed some other way: " + other;
		}
		check(said.startsWith("the embedded donor model of torn donor could not be read: "),
				"a prefab whose donor model will not parse is refused when it stamps, naming it: " + said);
	}

	/**
	 * A PICA command block whose consecutive writes run past register 0xFFFF is refused as damaged - a register is 16
	 * bits, and the map that holds them would keep one no GPU has.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (PICACommandReader 64, dropped, SURVIVED): no block had run past the
	 * last register. Dropped, register 0x10000 was written into the map without a word.
	 */
	static void aCommandBlockPastTheLastRegisterIsRefused() throws Exception {
		System.out.println("--- a command block whose writes run past register 0xFFFF is refused as damaged");
		//one write to 0xFFFF and one more parameter: consecutive, it lands on 0x10000; not, it lands on 0xFFFF again
		ctrmap.formats.h3d.PICACommandReader plain = new ctrmap.formats.h3d.PICACommandReader(
				new ctrmap.formats.h3d.RandomAccessBAIS(words(7, 0x001FFFFF, 9)), 3, true);
		check(plain.getParameter(0xFFFF) == 9, "control: a block writing register 0xFFFF twice keeps the second, "
				+ plain.getParameter(0xFFFF));
		String said;
		try {
			new ctrmap.formats.h3d.PICACommandReader(new ctrmap.formats.h3d.RandomAccessBAIS(words(7, 0x801FFFFF, 9)), 3, true);
			said = "it read the block";
		} catch (java.io.IOException refused) {
			said = refused.getMessage();
		} catch (RuntimeException other) {
			said = "it failed some other way: " + other;
		}
		check(said.equals("A PICA command block writes past register 0xFFFF - it is damaged."),
				"a block whose consecutive writes run past register 0xFFFF is refused as damaged: " + said);
	}

	/**
	 * A mesh whose index buffer was never located, or runs past the model, is refused by getTriangles in its own words,
	 * never read past the end.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (BchMapModel 715, dropped, SURVIVED; 709 its sibling): no mesh with
	 * an index buffer the model cannot hold had been asked for its triangles. Dropped, the read ran past the model's
	 * bytes into an index error naming nothing.
	 */
	static void aMeshWhoseIndexBufferCannotBeReadIsRefused(byte[] dummy) {
		System.out.println("--- a mesh whose index buffer cannot be read is refused, never read past the end");
		byte[] bytes = dummy.clone();
		BchMapModel m = new BchMapModel(bytes);
		int count = m.getTriangles(0).length;
		check(count > 0, "control: the dummy model's mesh 0 reads " + count + " indices");
		//the model keeps the bytes it was handed: its index count, made larger than the model can hold
		int countAt = m.ptr(m.meshes.get(0)[3] + 0x2C) + 0x18;
		java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(countAt, 0x00FFFFFF);
		check(said(() -> m.getTriangles(0)).endsWith(") runs past the model, so its triangles cannot be read"),
				"a mesh whose index buffer runs past the model is refused: " + said(() -> m.getTriangles(0)));
		BchMapModel lost = new BchMapModel(dummy.clone());
		lost.idxBuffers.clear();
		check(said(() -> lost.getTriangles(0)).equals("mesh 0's index buffer is not located, so its triangles cannot be read"),
				"a mesh whose index buffer was never located is refused: " + said(() -> lost.getTriangles(0)));
	}

	/**
	 * Each texture unit's coordinates are mirrored exactly when its own mapper wraps mirrored - a mirrored unit stores
	 * {@code 1 - u}, an unmirrored one {@code -u} - so a material mirroring one unit mirrors no other.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (H3DModel 570 and 573, negate-if, SURVIVED): the frames
	 * OffscreenRenderTest draws mirror only unit 0, so units 1 and 2 were never asked. Inverted, a second or third
	 * texture drew mirrored where its mapper said repeat, and the other way.
	 */
	static void eachTextureUnitMirrorsOnlyAsItsMapperSays(byte[] dummy) throws Exception {
		System.out.println("--- each texture unit mirrors exactly when its own mapper says so");
		ctrmap.formats.h3d.model.H3DModel m = new ctrmap.formats.h3d.BCHFile(dummy).models.get(0);
		ctrmap.formats.h3d.model.H3DModel.H3DMesh mesh = m.meshes.get(0);
		ctrmap.formats.h3d.texturing.H3DMaterial mat = m.materials.get(mesh.materialId);
		ctrmap.formats.h3d.model.H3DVertex v = mesh.vertices.get(0);
		v.texture0.x = 0.25;
		v.texture1.x = 0.25;
		v.texture2.x = 0.25;
		ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper.TextureWrap mirrored
				= ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper.TextureWrap.mirroredRepeat;
		ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper.TextureWrap repeat
				= ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper.TextureWrap.repeat;
		StringBuilder wrong = new StringBuilder();
		//each unit mirrored alone, then none: 1 - 0.25 where mirrored, -0.25 everywhere else
		for (int mirroredUnit = -1; mirroredUnit < 3; mirroredUnit++) {
			for (int k = 0; k < 3; k++) {
				mat.mappers[k].wrapU = k == mirroredUnit ? mirrored : repeat;
			}
			mesh.makeBOs(mat);
			for (int k = 0; k < 3; k++) {
				double u = mesh.tcbo[k].get(0);
				double want = k == mirroredUnit ? 0.75 : -0.25;
				if (u != want) {
					wrong.append(" unit ").append(k).append(" stored ").append(u).append(" with unit ").append(mirroredUnit)
							.append(" mirrored (want ").append(want).append(");");
				}
			}
		}
		check(wrong.length() == 0, "each texture unit mirrors exactly when its own mapper says so:" + (wrong.length() == 0
				? " units 0, 1 and 2, each alone, and none" : wrong.toString()));
	}

	/** What a call said: its refusal's words, or what it answered. */
	static String said(java.util.function.Supplier<int[]> call) {
		try {
			return "it answered " + call.get().length + " index(es)";
		} catch (IllegalStateException refused) {
			return refused.getMessage();
		} catch (RuntimeException other) {
			return "it failed some other way: " + other;
		}
	}

	/** Little-endian 32-bit words, as a command block is stored. */
	static byte[] words(int... w) {
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(w.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int x : w) {
			b.putInt(x);
		}
		return b.array();
	}

	static void everyReaderMakesOfThemWhatItDid(byte[] dummy, byte[] pack) throws Exception {
		System.out.println("--- every reader makes of the two files what it made of them before (F066)");
		Map<String, Map<String, String>> lines = new LinkedHashMap<>();
		lines.put("dummy", describe(dummy));
		lines.put("pack", describe(pack));
		for (String[] pin : PINS) {
			String line = lines.get(pin[0]).get(pin[1]);
			String got = line == null ? "(no such line)" : sha(line.getBytes("UTF-8")).substring(0, 16);
			check(got.equals(pin[2]), pin[0] + " / " + pin[1] + (got.equals(pin[2]) ? "" : ": " + line));
		}
	}

	static void theAreaAnimationRecordsEachRelocationWhereItIs(byte[] dummy, byte[] pack) throws Exception {
		System.out.println("--- the area animation records each relocation in its own section (F066)");
		for (byte[] b : new byte[][]{dummy, pack}) {
			BchMapModel m = new BchMapModel(b);
			java.util.Set<Integer> expected = new java.util.TreeSet<>(((Map<Integer, Integer>) get(m, "ptrLocToTargetBase")).keySet());
			for (Object cr : (List<?>) get(m, "cmdRelocs")) {
				expected.add(((int[]) cr)[1]);
			}
			Object wa = Class.forName("ctrmap.formats.area.WorldAnim").getConstructor(byte[].class).newInstance((Object) b);
			java.util.Set<Integer> got = new java.util.TreeSet<>(((Map<Integer, Integer>) get(wa, "relocMap")).keySet());
			check(got.equals(expected), b.length + "-byte file: its locations are the contents relocations and the"
					+ " command relocations, " + expected.size() + " of them" + (got.equals(expected) ? "" : " - got " + got));
		}
	}

	/** A hand-made header for {@code bc}: every offset and length a distinct number. */
	static byte[] header(int bc) {
		byte[] b = new byte[0x60];
		b[0] = 'B';
		b[1] = 'C';
		b[2] = 'H';
		b[4] = (byte) bc;
		b[5] = (byte) bc;
		for (int o = 8, v = 0x10; o + 4 <= 0x48; o += 4, v += 4) {
			MapResizeTest.putI32(b, o, v);
		}
		return b;
	}

	static void theHeaderIsReadForEveryLayout() {
		System.out.println("--- the header is read for the layout its compatibility byte declares");
		//0x21: contents 8, strings 12, commands 16, raw 20, rawExt 24, reloc 28 | lengths 32..52
		BchSections s = BchSections.read(withReloc(header(0x21), 28, 52));
		check(s.contentsAddr == 0x10 && s.rawExtAddr == 0x20 && s.contentsLen == 0x28 && s.rawExtLen == 0x38,
				"0x21: the raw-extension section is read (rawExt at " + s.rawExtAddr + ", length " + s.rawExtLen + ")");
		//0x20: no raw extension - reloc at 24, lengths start at 28
		BchSections o = BchSections.read(withReloc(header(0x20), 24, 44));
		check(o.rawExtAddr == 0 && o.rawExtLen == 0 && o.contentsLen == 0x24,
				"0x20: no raw-extension section, and every offset after it moves up (contents length "
				+ Integer.toHexString(o.contentsLen) + ")");
		BchSections old = BchSections.read(withReloc(header(7), 24, 44));
		check(old.flags == 0 && old.addressCount == 0, "a layout of 7 or older has no flags or address count");

		//measured by the 2026-10-12 sweep (WorldAnim 58, dropped, SURVIVED): the area animation reader was never handed
		//a layout older than the one it reads. Dropped, it took an 0x20 file - no raw-extension section, every offset
		//after it moved up - as the layout it assumes, and edited an animation it had misread
		String newer;
		try {
			new ctrmap.formats.area.WorldAnim(withReloc(header(0x21), 28, 52));
			newer = "read";
		} catch (RuntimeException refused) {
			newer = "refused: " + refused;
		}
		String older;
		try {
			new ctrmap.formats.area.WorldAnim(withReloc(header(0x20), 24, 44));
			older = "it read an 0x20 file as the layout it reads";
		} catch (IllegalArgumentException refused) {
			older = refused.getMessage();
		} catch (RuntimeException other) {
			older = "it went on and failed: " + other;
		}
		check("read".equals(newer) && older.equals("BCH backwardCompatibility 0x20 predates the layout the area animation reader reads"),
				"the area animation reader reads the 0x21 layout and refuses an older one, saying so (0x21: " + newer
				+ "; 0x20: " + older + ")");
	}

	/**
	 * A material carrying values the Ohana port did not list still opens, drawn with what it does
	 * list (F079): the port indexed values() with raw bits, so each of these threw and BCHFile
	 * refused the whole model. The dummy's first texture mapper gets a wrap mode of 0x7F and a
	 * magnification filter byte of 0x80 (a signed shift made that negative), and its alpha test
	 * gets bit 7, past the register's three function bits. Each place is found by reading the
	 * dummy, and checked against what the pins say is there before it is changed.
	 */
	static void aMaterialValueThePortDidNotListOpens(byte[] dummy) throws Exception {
		System.out.println("--- a material value the port did not list opens, drawn with what it does (F079)");
		BchMapModel m = new BchMapModel(dummy);
		int header = m.materialHeaderOffsets.get(0);
		int mapper = m.ptr(header + 0x18);
		int params = m.ptr(header);
		int fsh = m.ptr(params + 200);
		int words = ctrmap.util.LittleEndian.i32(dummy, params + 204);
		int alphaAt = -1, found = 0;
		for (int w = 0; w + 1 < words; w += 2) {
			int head = ctrmap.util.LittleEndian.i32(dummy, fsh + (w + 1) * 4);
			if ((head & 0xffff) == ctrmap.formats.h3d.PICACommand.alphaTestConfig && ((head >> 20) & 0x7ff) == 0) {
				alphaAt = fsh + w * 4;
				found++;
			}
		}
		boolean located = found == 1 && dummy[mapper + 1] == 2 && dummy[mapper + 2] == 2
				&& ((ctrmap.util.LittleEndian.i32(dummy, alphaAt) >> 4) & 0xf) == 6;
		check(located, "control: the first mapper's wraps read repeat/repeat and one alpha-test write reads 'greater'"
				+ " where this looks for them (mapper at " + mapper + ", alpha test at " + alphaAt + ", " + found + " found)");
		if (!located) {
			return;
		}
		byte[] odd = dummy.clone();
		odd[mapper + 1] = 0x7F;
		odd[mapper + 3] = (byte) 0x80;
		odd[alphaAt] |= (byte) 0x80;
		ctrmap.formats.h3d.BCHFile f = new ctrmap.formats.h3d.BCHFile(odd);
		boolean opened = f.errorlevel == 0 && f.models.size() == 1 && f.models.get(0).materials.size() == 1;
		check(opened, "the model opens (errorlevel " + f.errorlevel + ", " + f.models.size() + " model(s))");
		if (opened) {
			ctrmap.formats.h3d.texturing.H3DMaterial mat = f.models.get(0).materials.get(0);
			check(mat.mappers[0].wrapU == ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper.TextureWrap.repeat
					&& mat.mappers[0].wrapV == ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper.TextureWrap.repeat,
					"an unlisted wrap mode draws as repeat, and the listed one beside it is read as it was ("
					+ mat.mappers[0].wrapU + "/" + mat.mappers[0].wrapV + ")");
			check(mat.params.alphaTest.isTestEnabled
					&& mat.params.alphaTest.testFunction == ctrmap.formats.h3d.texturing.H3DMaterial.TestFunction.greater,
					"the alpha test reads its three function bits: still on, still 'greater' ("
					+ mat.params.alphaTest.isTestEnabled + "/" + mat.params.alphaTest.testFunction + ")");
		}
	}

	/**
	 * The model appender says which model did not parse and what its reader found (F229): it threw
	 * "parse problems" and dropped both lists, and an OBJ import showed that for every new-material
	 * group. Data-free: a header-only 0x21 model the reader cannot make a model of, against the
	 * bundled dummy model, which it can (the control).
	 */
	static void anAppendRefusalSaysWhatDidNotParse() throws Exception {
		System.out.println("--- an append refusal says which model did not parse, and why (F229)");
		byte[] dummy = resource("ctrmap/resources/DummyBCH3DModel.bch");
		java.util.List<String> clean = new ctrmap.formats.h3d.BchMapModel(dummy).validate();
		check(clean.isEmpty(), "control: the bundled dummy model parses clean (" + clean + ")");
		byte[] empty = new byte[0x100];
		empty[0] = 'B';
		empty[1] = 'C';
		empty[2] = 'H';
		empty[4] = 0x21;
		empty[5] = 0x21;
		MapResizeTest.putI32(empty, 8, 0x44);
		MapResizeTest.putI32(empty, 28, 0x100);
		MapResizeTest.putI32(empty, 32, 15 * 12);
		String asTarget = appendRefusal(empty, dummy);
		check(asTarget.startsWith("the map's model does not parse: [") && asTarget.contains("models dict count==1"),
				"a target that does not parse is named, with what its reader found (" + asTarget + ")");
		String asDonor = appendRefusal(dummy, empty);
		check(asDonor.startsWith("the donor model does not parse: [") && asDonor.contains("models dict count==1"),
				"a donor that does not parse is named, with what its reader found (" + asDonor + ")");
		String pastIt;
		try {
			ctrmap.formats.h3d.BchModelAppender.append(dummy, dummy, 5, "probe");
			pastIt = "(appended)";
		} catch (RuntimeException ex) {
			pastIt = ex.getClass().getSimpleName() + ": " + ex.getMessage();
		}
		check(pastIt.contains("1 mesh(es)") && pastIt.contains("mesh 5 is not one of them"), "a template mesh the donor"
				+ " does not have is refused, naming how many it has (F226: it was an index out of bounds) (" + pastIt + ")");
	}

	private static String appendRefusal(byte[] target, byte[] donor) {
		try {
			ctrmap.formats.h3d.BchModelAppender.append(target, donor, 0, "probe");
			return "(appended)";
		} catch (IllegalStateException ex) {
			return String.valueOf(ex.getMessage());
		}
	}

	/** The header with its relocation table pointed inside the file: empty, at the end. */
	static byte[] withReloc(byte[] b, int addrAt, int lenAt) {
		MapResizeTest.putI32(b, addrAt, b.length);
		MapResizeTest.putI32(b, lenAt, 0);
		return b;
	}

	static void whatIsNotABchIsRefused() {
		System.out.println("--- what is not a whole BCH is refused in words");
		check(BchSections.hasMagic(new byte[]{'B', 'C', 'H', 0}) && !BchSections.hasMagic(new byte[]{'B', 'C', 'H', 1})
				&& !BchSections.isBch(new byte[]{'B', 'C', 'H', 0, 0x21}), "four magic bytes are a sniff, not a header");
		byte[] past = header(0x21);
		MapResizeTest.putI32(past, 28, 0x50);
		MapResizeTest.putI32(past, 52, 0x40);
		String said = refusal(past);
		check(said.contains("relocation table") && said.contains("past the end"), "a relocation table past the end: " + said);
		check(refusal(new byte[]{'X', 'Y', 'Z', 0, 0x21, 0, 0, 0}).equals("not a BCH"), "and what is not a BCH says so");
	}

	static void aStringNothingEndsIsRefused() throws Exception {
		System.out.println("--- a string nothing ends is refused, by the reader the model parse uses (F066)");
		//THE MODEL PARSE'S READER (F204): this asked a stream reader the program never called -
		//the parse reads names at an address - so it guarded a door nothing used
		String said;
		try {
			said = "read \"" + ctrmap.formats.h3d.StringUtils.readString(0, new byte[]{'s', 'e', 'a'}) + "\"";
		} catch (java.io.IOException ex) {
			said = ex.getMessage();
		}
		check(said.contains("runs off the end"), "the address reader says where it ran out: " + said);
		String strict;
		try {
			strict = ctrmap.formats.h3d.StringUtils.cString(new byte[]{'s', 'e', 'a'}, 0);
		} catch (IllegalArgumentException ex) {
			strict = ex.getMessage();
		}
		check(strict.contains("runs off the end"), "and so does the array reader: " + strict);
		check(ctrmap.formats.h3d.StringUtils.cString(new byte[]{'s', 'e', 'a', 0, 'x'}, 0).equals("sea"),
				"control: a string its NUL ends is read to the NUL");
		//AN ADDRESS OUTSIDE THE DATA (StringUtils 35, dropped, SURVIVED the 2026-10-14 shared sweep): dropped, one past
		//the end took the other refusal's words, and -1 and no data at all were an index and a null-pointer error
		byte[] five = {'s', 'e', 'a', 0, 'x'};
		check(cStringSaid(five, 5).equals("A string at byte 5 runs off the end of the data (5 bytes).")
				&& cStringSaid(five, -1).equals("A string at byte -1 runs off the end of the data (5 bytes).")
				&& cStringSaid(null, 0).equals("A string at byte 0 runs off the end of the data (absent)."),
				"a string asked for outside the data, or of no data, is refused saying so: " + cStringSaid(five, 5) + " / "
				+ cStringSaid(five, -1) + " / " + cStringSaid(null, 0));
	}

	/** What the array string reader said: the string, or its refusal's words. */
	static String cStringSaid(byte[] b, int off) {
		try {
			return "it read \"" + ctrmap.formats.h3d.StringUtils.cString(b, off) + "\"";
		} catch (IllegalArgumentException refused) {
			return refused.getMessage();
		} catch (RuntimeException other) {
			return "it failed some other way: " + other;
		}
	}

	static String refusal(byte[] b) {
		try {
			BchSections.read(b);
			return "read";
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		}
	}

	// ---- the probe the pins were taken with, kept here so they mean the same thing

	static Map<String, String> describe(byte[] b) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		try {
			BchMapModel m = new BchMapModel(b);
			StringBuilder sb = new StringBuilder("BchMapModel header:");
			for (String f : new String[]{"bc", "fc", "converterVersion", "contentsAddr", "stringsAddr", "commandsAddr",
				"rawDataAddr", "rawExtAddr", "relocAddr", "contentsLen", "stringsLen", "commandsLen", "rawDataLen",
				"rawExtLen", "relocLen", "uninitData", "uninitCmd", "flagsByte", "addressCount", "baseAddrCount"}) {
				sb.append(' ').append(f).append('=').append(get(m, f));
			}
			put(out, sb.toString());
			put(out, "BchMapModel ptrLocToTargetBase: " + new TreeMap<>((Map<?, ?>) get(m, "ptrLocToTargetBase")));
			put(out, "BchMapModel cmdRelocs: " + ints((List<?>) get(m, "cmdRelocs")));
			put(out, "BchMapModel vtxBuffers: " + ints((List<?>) get(m, "vtxBuffers")));
			put(out, "BchMapModel idxBuffers: " + ints((List<?>) get(m, "idxBuffers")));
			Method decode = Class.forName("ctrmap.formats.h3d.BchModelAppender").getDeclaredMethod("decode", BchMapModel.class, byte[].class);
			decode.setAccessible(true);
			StringBuilder re = new StringBuilder("BchModelAppender.decode:");
			for (Object e : (List<?>) decode.invoke(null, m, b)) {
				re.append(" [").append(get(e, "flag")).append(',').append(get(e, "tgt")).append(',').append(get(e, "src"))
						.append(',').append(get(e, "ptrLoc")).append(',').append(get(e, "word")).append(']');
			}
			put(out, re.toString());
		} catch (RuntimeException | java.lang.reflect.InvocationTargetException ex) {
			put(out, "BchMapModel/decode: refused - " + ex);
		}
		try {
			Object wa = Class.forName("ctrmap.formats.area.WorldAnim").getConstructor(byte[].class).newInstance((Object) b);
			put(out, "WorldAnim relocMap: " + new TreeMap<>((Map<?, ?>) get(wa, "relocMap")));
		} catch (java.lang.reflect.InvocationTargetException ex) {
			put(out, "WorldAnim: refused - " + ex.getCause());
		}
		Object bf = Class.forName("ctrmap.formats.h3d.BCHFile").getConstructor(byte[].class).newInstance((Object) b);
		Object h = get(bf, "header");
		StringBuilder hs = new StringBuilder("BCHFile header:");
		for (Field f : h.getClass().getDeclaredFields()) {
			f.setAccessible(true);
			hs.append(' ').append(f.getName()).append('=').append(f.get(h));
		}
		put(out, hs.toString());
		put(out, "BCHFile buf hash: " + java.util.Arrays.hashCode((byte[]) get(bf, "buf")) + " models "
				+ ((List<?>) get(bf, "models")).size() + " textures " + ((List<?>) get(bf, "textures")).size()
				+ " errorlevel " + get(bf, "errorlevel"));
		//WHAT THE 3D VIEW READS from each material (F079): pinned before the material decoder was cut
		//down to it, so the cut is held to what the old decoder made of these fields
		StringBuilder mats = new StringBuilder("BCHFile materials:");
		for (Object model : (List<?>) get(bf, "models")) {
			for (ctrmap.formats.h3d.texturing.H3DMaterial m : ((ctrmap.formats.h3d.model.H3DModel) model).materials) {
				mats.append(" [").append(m.name).append('|').append(m.name0).append('|').append(m.name1).append('|')
						.append(m.name2);
				for (ctrmap.formats.h3d.texturing.H3DMaterial.TextureMapper mp : m.mappers) {
					mats.append('|').append(mp.wrapU).append('/').append(mp.wrapV);
				}
				mats.append('|').append(m.params.blendop.mode).append('|').append(m.params.alphaTest.isTestEnabled)
						.append('/').append(m.params.alphaTest.testFunction).append(']');
			}
		}
		put(out, mats.toString());
		StringBuilder texs = new StringBuilder("BCHFile textures:");
		for (Object tex : (List<?>) get(bf, "textures")) {
			ctrmap.formats.h3d.texturing.H3DTexture x = (ctrmap.formats.h3d.texturing.H3DTexture) tex;
			texs.append(" [").append(x.textureName).append('|').append(x.textureSize.width).append('x')
					.append(x.textureSize.height).append('|').append(java.util.Arrays.hashCode(x.textureData)).append(']');
		}
		put(out, texs.toString());
		put(out, "BchTexturePack.isTexturePack: " + BchTexturePack.isTexturePack(b));
		try {
			StringBuilder t = new StringBuilder("BchTexturePack.parse:");
			for (BchTexturePack.Texture x : BchTexturePack.parse(b)) {
				t.append(" [").append(x.name).append(',').append(x.format).append(',').append(x.dimParam).append(',')
						.append(java.util.Arrays.hashCode(x.data)).append(']');
			}
			put(out, t.toString());
		} catch (RuntimeException ex) {
			put(out, "BchTexturePack.parse: refused - " + ex);
		}
		return out;
	}

	static void put(Map<String, String> out, String line) {
		out.put(line.substring(0, line.indexOf(':')), line);
	}

	static String ints(List<?> l) {
		StringBuilder sb = new StringBuilder();
		for (Object o : l) {
			sb.append(java.util.Arrays.toString((int[]) o));
		}
		return sb.toString();
	}

	static Object get(Object o, String f) throws Exception {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field x = c.getDeclaredField(f);
				x.setAccessible(true);
				return x.get(o);
			} catch (NoSuchFieldException next) {
				//try the superclass
			}
		}
		throw new NoSuchFieldException(f);
	}

	static byte[] resource(String path) throws Exception {
		try (java.io.InputStream in = BchSectionsTest.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("the bundled " + path + " is not on the classpath");
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0;) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** Two 8x8 RGBA8 textures through the pack writer - the probe's second subject. */
	static byte[] texturePack() {
		List<BchTexturePack.Texture> texes = new ArrayList<>();
		String[] names = {"chip_grass_a", "chip_sea_b"};
		for (int i = 0; i < names.length; i++) {
			BchTexturePack.Texture t = new BchTexturePack.Texture();
			t.name = names[i];
			t.format = 0;
			t.dimParam = 8 | (8 << 16);
			t.data = new byte[8 * 8 * 4];
			for (int b = 0; b < t.data.length; b++) {
				t.data[b] = (byte) (b * (i + 3));
			}
			texes.add(t);
		}
		return BchTexturePack.emit(texes);
	}

	static String sha(byte[] b) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

	/**
	 * BCHFile's relocation writes the four bytes it is told to and no others (F228). It wrapped the
	 * buffer at the offset and then wrote with ByteBuffer's ABSOLUTE putInt(0, ...) - index 0 of the
	 * whole array - so every relocation and every changed value also overwrote the first four bytes,
	 * the file's magic, before copying the value to where it belonged.
	 */
	static void aRelocationWritesOnlyWhereItIsTold() {
		byte[] b = {'B', 'C', 'H', 0, 0x10, 0, 0, 0, 0, 0, 0, 0};
		ctrmap.formats.h3d.BCHFile.changeValueTo(8, 0x11223344, b);
		check(b[0] == 'B' && b[1] == 'C' && b[2] == 'H' && b[3] == 0 && ctrmap.util.LittleEndian.i32(b, 8) == 0x11223344
				&& ctrmap.formats.h3d.BCHFile.peek(8, b) == 0x11223344,
				"changing a value writes it at its offset and leaves the magic alone: " + java.util.Arrays.toString(b));
		byte[] c = {'B', 'C', 'H', 0, 0x10, 0, 0, 0};
		new ctrmap.formats.h3d.BCHFile(new byte[0]).relocateOffset(4, 0x20, c);
		check(c[0] == 'B' && c[1] == 'C' && c[2] == 'H' && c[3] == 0 && ctrmap.util.LittleEndian.i32(c, 4) == 0x30,
				"and relocating an offset adds to the word at its offset only: " + java.util.Arrays.toString(c));
	}

	/**
	 * The BCH reader is where it read (F228). It counted its position beside a stream's and the two
	 * drifted, measured on the old reader over six bytes: skip(100) said 100; read() at the end said 7;
	 * read(byte[]) at the end said 5, backwards; seek(-2) said -2 while the stream read from 0. Each of
	 * those is asked again here, with the reads' values against bytes whose answers are known.
	 */
	static void theReaderIsWhereItRead() throws Exception {
		System.out.println("--- the BCH reader is where it read (F228)");
		byte[] b = {0x01, 0x02, (byte) 0xFE, (byte) 0xFF, 0x00, 0x00, (byte) 0x80, 0x3F, 0x7F};
		ctrmap.formats.h3d.RandomAccessBAIS in = new ctrmap.formats.h3d.RandomAccessBAIS(b);
		check(in.readShort() == 0x0201 && in.position() == 2, "control: readShort reads low byte first and moves 2");
		check(in.readUnsignedShort() == 0xFFFE && in.position() == 4, "readUnsignedShort is unsigned and moves 2");
		check(in.readFloat() == 1.0f && in.position() == 8, "readFloat reads the IEEE bits low byte first and moves 4");
		check(in.read() == 0x7F && in.position() == 9, "read() answers the byte and moves 1");
		check(in.read() == -1 && in.position() == 9, "read() at the end answers -1 and does not move: " + in.position());
		in.seek(0);
		check(in.skip(100) == 9 && in.position() == 9, "skip past the end stops at the end: " + in.position());
		in.seek(0);
		check(in.readInt() == 0xFFFE0201 && in.position() == 4, "readInt reads four bytes low first and moves 4");
		boolean refused;
		try {
			in.seek(-2);
			refused = false;
		} catch (java.io.IOException e) {
			refused = true;
		}
		check(refused && in.position() == 4, "a seek before the start is refused, and the reader stays: " + in.position());
		in.seek(7);
		try {
			in.readInt();
			check(false, "a read past the end throws EOFException");
		} catch (java.io.EOFException e) {
			check(in.position() == 7, "a read past the end throws EOFException and does not move: " + in.position());
		}
		in.seek(100);
		check(in.read() == -1 && in.position() == 100, "a seek past the end is allowed, and reading there answers the end");

		//census M.110: a texture read took whatever bytes were left and decoded the rest as zeros
		in.seek(5);
		byte[] three = new byte[3];
		in.readFully(three);
		check(three[0] == 0x00 && three[1] == (byte) 0x80 && three[2] == 0x3F && in.position() == 8,
				"control: readFully reads all of what it is asked, and moves by it");
		try {
			in.readFully(new byte[4]);
			check(false, "readFully past the end throws EOFException");
		} catch (java.io.EOFException e) {
			check(in.position() == 8, "readFully past the end throws EOFException and does not move - never part of it: " + in.position());
		}
		check(ctrmap.formats.h3d.texturing.TextureCodec.storedSize(8, 8, ctrmap.formats.h3d.PICACommandReader.TextureFormat.etc1) == 32
				&& ctrmap.formats.h3d.texturing.TextureCodec.storedSize(8, 8, ctrmap.formats.h3d.PICACommandReader.TextureFormat.rgba8) == 256
				&& ctrmap.formats.h3d.texturing.TextureCodec.storedSize(8, 8, ctrmap.formats.h3d.PICACommandReader.TextureFormat.rgb565) == 128,
				"a texture's stored size follows its format: ETC1 half a byte a texel, RGBA8 four, RGB565 two");
		String texture = new String(java.nio.file.Files.readAllBytes(new java.io.File(
				"src/ctrmap/formats/h3d/texturing/H3DTexture.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
		check(texture.contains("TextureCodec.storedSize(") && texture.contains("in.readFully(buffer);") && !texture.contains("in.read(buffer)"),
				"and a texture is read whole, at the size its format stores, or refused");
	}

	/**
	 * A BCH whose read fails partway hands out nothing it read. BCHFile counted a read that threw
	 * partway as an error and kept the models and textures it had read before it, so the readers that
	 * never ask its error level - 11 of the 19 the editor has - took half a pack as the whole one
	 * (found from the 2026-10-14 shared sweep's BuildingPaletteDialog 199). The subject is the pack
	 * writer's two textures with the second one's name pointed past the end: the first reads whole,
	 * the second throws.
	 */
	static void aBchThatFailsPartwayHandsOutNothing() throws Exception {
		System.out.println("--- a BCH that fails partway hands out nothing it read");
		byte[] whole = texturePack();
		ctrmap.formats.h3d.BCHFile control = new ctrmap.formats.h3d.BCHFile(whole);
		check(control.errorlevel == 0 && control.textures.size() == 2, "CONTROL: the writer's pack reads whole - error level "
				+ control.errorlevel + ", " + control.textures.size() + " texture(s)");
		ctrmap.formats.h3d.BCHFile half = new ctrmap.formats.h3d.BCHFile(secondNameUnreadable(whole));
		check(half.errorlevel > 0, "CONTROL: the second texture's name past the end fails the read (error level "
				+ half.errorlevel + ")");
		check(half.textures.isEmpty() && half.models.isEmpty(), "and a read that failed partway hands out nothing - not the "
				+ half.textures.size() + " texture(s) it read before it failed");
	}

	/** {@code pack}, a texture pack of the writer's layout, with its second texture's name offset past the end. */
	static byte[] secondNameUnreadable(byte[] pack) {
		byte[] b = pack.clone();
		int contents = ctrmap.util.LittleEndian.i32(b, 8);
		int pointers = contents + ctrmap.util.LittleEndian.i32(b, contents + 3 * 12);   //content dict 3: textures
		int second = contents + ctrmap.util.LittleEndian.i32(b, pointers + 4);
		MapResizeTest.putI32(b, second + 28, 0x7FFF0000);
		return b;
	}

	/** {@code pack}, a texture pack of the writer's layout, whose first texture is of a format no reader knows. */
	static byte[] firstFormatUnknown(byte[] pack) {
		byte[] b = pack.clone();
		MapResizeTest.putI32(b, ctrmap.util.LittleEndian.i32(b, 16) + 24, 99);   //unit 0's type command
		return b;
	}

	/** Whether a reader of a BCH refuses one that does not read, or may take it as nothing to draw. */
	enum BchRead {
		/** It reads the error level on its own line or one of the next eight, and refuses or answers nothing. */
		ASKS,
		/** It draws or classifies what the BCH holds, and one that failed holds nothing: BCHFile empties it. */
		NOTHING
	}

	/**
	 * Every place the editor reads a BCH, by its file and the words of the line - 19 on 2026-10-15. A
	 * reader that must not take an unreadable BCH as an empty one asks the error level; one that may
	 * is listed as drawing nothing, which holds because a failed read hands out nothing (above). The
	 * palette's donor textures were neither: an unreadable pack was taken as holding no textures, and
	 * the building drew untextured without a word - the silence F211 removed for a pack that throws.
	 */
	static final Object[][] BCH_READS = {
		{"CtrmapMainframe.java", "new ctrmap.formats.h3d.BCHFile(bch)", 1, BchRead.ASKS},
		{"PropRegistration.java", "new BCHFile(merged)", 1, BchRead.ASKS},
		{"TerrainTextures.java", "new BCHFile(modelBytes)", 1, BchRead.ASKS},
		{"ZoneHeader.java", "new BCHFile(adbch1)", 1, BchRead.ASKS},
		{"BuildingPaletteDialog.java", "new BCHFile(pack)", 1, BchRead.ASKS},
		{"MapPreview3D.java", "new BCHFile(modelBytes)", 1, BchRead.ASKS},
		{"GroundMaterialAudit.java", "new BCHFile(model)", 1, BchRead.ASKS},
		{"GroundMaterialAudit.java", "new BCHFile(wTex)", 1, BchRead.ASKS},
		{"TerrainDonorHarvester.java", "new BCHFile(merged)", 1, BchRead.ASKS},
		//a move model or building model that does not read draws nothing: the registries skip a BCH with no model
		{"NPCRegistry.java", "new BCHFile(new MM(staged, files).getFile(0))", 1, BchRead.NOTHING},
		{"ADPropRegistry.java", "new BCHFile(new BM(staged, files).getFile(0))", 1, BchRead.NOTHING},
		//the zone's world textures: a world pack that does not read leaves the map untextured, as F066 decided
		{"ZoneHeader.java", "new BCHFile(dec)", 1, BchRead.NOTHING},
		{"ZoneHeader.java", "new BCHFile(wTex)", 1, BchRead.NOTHING},
		//the container builder names a subfile's type by what it holds; one that holds nothing is named by the rest
		{"Builder.java", "new BCHFile(file)", 1, BchRead.NOTHING},
		//the 3D views draw a model that does not read as nothing, which is what they draw for a missing one
		{"PropEditForm.java", "new BCHFile(bch)", 1, BchRead.NOTHING},
		{"TileMapPanel.java", "new BCHFile(tg)", 1, BchRead.NOTHING},
		{"TileMapPanel.java", "new BCHFile(file.getFile(1))", 1, BchRead.NOTHING},
		{"TileMapPanel.java", "new BCHFile(modelBytes)", 1, BchRead.NOTHING},
		{"TileMapPanel.java", "new BCHFile(mm.regions.get(j, i).getFile(1))", 1, BchRead.NOTHING}};

	static final java.util.regex.Pattern BCH_READ = java.util.regex.Pattern.compile("new (ctrmap\\.formats\\.h3d\\.)?BCHFile\\(");

	static void everyBchTheEditorReadsIsClassified(java.io.File root) throws Exception {
		System.out.println("--- every BCH the editor reads is classified, and every one that must refuse asks the error level");
		Map<Object[], Integer> matched = new java.util.HashMap<>();
		List<String> unlisted = new ArrayList<>();
		List<String> unasked = new ArrayList<>();
		int lines = 0, files = 0;
		java.util.Deque<java.io.File> walk = new java.util.ArrayDeque<>();
		walk.push(root);
		while (!walk.isEmpty()) {
			java.io.File d = walk.pop();
			java.io.File[] in = d.listFiles();
			if (in == null) {
				check(false, "CONTROL: the source folder " + d + " can be listed");
				continue;
			}
			for (java.io.File f : in) {
				if (f.isDirectory() && !f.getName().equals("tests")) {
					walk.push(f);
				} else if (f.getName().endsWith(".java")) {
					files++;
					String[] src = SourceSeamTest.stripComments(DialogSeamTest.read(f)).split("\n", -1);
					for (int i = 0; i < src.length; i++) {
						if (!BCH_READ.matcher(src[i]).find()) {
							continue;
						}
						lines++;
						Object[] entry = null;
						for (Object[] e : BCH_READS) {
							if (e[0].equals(f.getName()) && src[i].contains((String) e[1])) {
								entry = e;
								break;
							}
						}
						if (entry == null) {
							unlisted.add(f.getName() + ":" + (i + 1) + " " + src[i].trim());
							continue;
						}
						matched.merge(entry, 1, Integer::sum);
						boolean asks = false;
						for (int j = i; j < Math.min(src.length, i + 9); j++) {
							asks |= src[j].contains(".errorlevel");
						}
						if (entry[3] == BchRead.ASKS && !asks) {
							unasked.add(f.getName() + ":" + (i + 1) + " " + src[i].trim());
						}
					}
				}
			}
		}
		List<String> stale = new ArrayList<>();
		int listed = 0;
		for (Object[] e : BCH_READS) {
			listed += (Integer) e[2];
			if (!((Integer) e[2]).equals(matched.getOrDefault(e, 0))) {
				stale.add(e[0] + " " + e[1] + ": " + matched.getOrDefault(e, 0) + " line(s), " + e[2] + " listed");
			}
		}
		check(files > 250 && lines == listed, "CONTROL: " + files + " production sources walked, " + lines
				+ " BCH read(s) found, " + listed + " listed");
		check(unlisted.isEmpty(), "every BCH the editor reads is classified - not: " + unlisted);
		check(stale.isEmpty(), "and every classified read is still there, as many times as listed - not: " + stale);
		check(unasked.isEmpty(), "and every read that must refuse an unreadable BCH asks its error level - not: " + unasked);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
