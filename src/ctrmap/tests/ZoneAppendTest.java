package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.ZoneAppender;
import ctrmap.ZoneCloner;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.formats.zone.ZoneHeader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Headless end-to-end test of the EXPERIMENTAL zone append (ZoneAppender).
 * Operates ONLY on copies of the PRISTINE ORAS ZoneData GARC in a fresh temp
 * dir with a simulated workspace (never the real workspace). Run with:
 * java -cp "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar" ctrmap.tests.ZoneAppendTest
 *
 * Builds the three append payloads (new ZO cloned from zone 24 with
 * OAZoneNumber patched to 536, master grown to 537 rows, EN pack grown to
 * count 537 with an empty blob), runs the REAL GARC.packDirectory with the
 * compression overrides and re-verifies everything from the packed file:
 * 539 entries, zones 0..535 byte-identical, entry 536 a valid ZO, entry 537
 * the grown master, entry 538 the grown EN pack, correct raw (compressed/
 * uncompressed) storage and GARC header fields. Also checks the EN rebuild
 * round-trip and that invalid inputs are rejected before anything is built.
 */
public class ZoneAppendTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
	private static final int SRC = 24;
	private static final int NEW_INDEX = 536;

	public static void main(String[] args) throws IOException {
		File pristineFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!pristineFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + pristineFile.getAbsolutePath());
			System.exit(1);
		}
		GARC pristine = new GARC(pristineFile);
		check(pristine.length == 538, "pristine ORAS ZoneData has 538 entries, got " + pristine.length);

		byte[] srcZo = pristine.getDecompressedEntry(SRC);
		byte[] masterOld = pristine.getDecompressedEntry(536);
		byte[] enOld = pristine.getDecompressedEntry(537);
		check(srcZo != null && masterOld != null && enOld != null, "extracted entries " + SRC + "/536/537 from the pristine GARC");
		check(masterOld.length == 536 * ZoneCloner.ZONE_HEADER_SIZE, "master table is 536 rows x 0x38, got " + masterOld.length + " bytes");

		//EN rebuild round-trip: count unchanged must be byte-identical
		byte[] enRoundTrip = ZoneAppender.rebuildEN(enOld, 536, false);
		check(Arrays.equals(enRoundTrip, enOld), "EN rebuild (count unchanged) is byte-identical to the pristine pack");

		//negative tests: invalid inputs must be rejected (no partial payloads)
		expectThrow("src index out of range", srcZo, masterOld, enOld, NEW_INDEX, NEW_INDEX);
		expectThrow("negative src index", srcZo, masterOld, enOld, -1, NEW_INDEX);
		byte[] enBadMagic = enOld.clone();
		enBadMagic[0] = 'X';
		expectThrow("corrupt EN magic", srcZo, masterOld, enBadMagic, SRC, NEW_INDEX);
		byte[] enBadCount = enOld.clone();
		enBadCount[2] = (byte) 0x99;
		expectThrow("wrong EN count", srcZo, masterOld, enBadCount, SRC, NEW_INDEX);
		byte[] masterBadLen = Arrays.copyOf(masterOld, masterOld.length - ZoneCloner.ZONE_HEADER_SIZE);
		expectThrow("wrong master table size", srcZo, masterBadLen, enOld, SRC, NEW_INDEX);

		//the production byte transform
		ZoneAppender.AppendPayloads p = ZoneAppender.buildAppendPayloads(srcZo, masterOld, enOld, SRC, NEW_INDEX);
		check(p.newZo.length == srcZo.length, "new ZO length == source ZO length");
		check(p.master.length == 537 * ZoneCloner.ZONE_HEADER_SIZE, "grown master is 537 rows");
		check(p.en.length == enOld.length + 4, "grown EN is exactly 4 bytes longer (one more offset)");
		check((p.compressedZo[0] & 0xFF) == 0x11, "compressed ZO payload starts with the LZ11 magic byte");

		//---- simulated workspace + the real packDirectory ----
		File tmp = Files.createTempDirectory("ZoneAppendTest").toFile();
		File garcCopy = new File(tmp, "3");
		Files.copy(pristineFile.toPath(), garcCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
		File packDir = new File(tmp, "zonedata");
		packDir.mkdirs();
		File f536 = new File(packDir, "536");
		File f537 = new File(packDir, "537");
		File f538 = new File(packDir, "538");
		writeAll(f536, p.compressedZo); //shifted slot was uncompressed -> stored verbatim, sniffed compressed on reopen
		writeAll(f537, p.master);
		writeAll(f538, p.en);
		String savedWsPath = Workspace.WORKSPACE_PATH;
		Workspace.WORKSPACE_PATH = tmp.getAbsolutePath();
		Workspace.persist_paths.clear();
		Workspace.persist_paths.add(f536.getAbsolutePath());
		Workspace.persist_paths.add(f537.getAbsolutePath());
		Workspace.persist_paths.add(f538.getAbsolutePath());
		HashMap<Integer, Boolean> overrides = new HashMap<>();
		overrides.put(538, Boolean.FALSE); //appended EN slot must stay uncompressed
		GARC work = new GARC(garcCopy);
		work.packDirectory(packDir, overrides);
		Workspace.WORKSPACE_PATH = savedWsPath;
		Workspace.persist_paths.clear();

		//---- reopen and verify everything from the packed file ----
		GARC packed = new GARC(garcCopy);
		check(packed.length == 539, "packed GARC has 539 entries, got " + packed.length);
		for (int i = 0; i < 536; i++) {
			if (!Arrays.equals(pristine.getDecompressedEntry(i), packed.getDecompressedEntry(i))) {
				check(false, "zone " + i + " decompressed bytes changed after repack");
			}
		}
		System.out.println("zones 0..535 decompress byte-identical after repack");
		byte[] e536 = packed.getDecompressedEntry(536);
		check(Arrays.equals(e536, p.newZo), "entry 536 decompresses to the patched clone ZO (" + e536.length + " bytes)");
		byte[] e537 = packed.getDecompressedEntry(537);
		check(Arrays.equals(e537, p.master), "entry 537 == grown master (" + e537.length + " bytes)");
		byte[] e538 = packed.getDecompressedEntry(538);
		check(Arrays.equals(e538, p.en), "entry 538 == grown EN (" + e538.length + " bytes)");

		//entry 536: structurally valid zone with the right index
		File zoFile = new File(tmp, "zo536");
		writeAll(zoFile, e536);
		ZO zo = new ZO(zoFile);
		check(zo.len == 5, "new zone ZO has 5 subfiles, got " + zo.len);
		ZoneHeader hdr = new ZoneHeader(zo.getFile(0), Workspace.GameType.ORAS);
		check(hdr.OAZoneNumber == NEW_INDEX, "parsed OAZoneNumber == " + hdr.OAZoneNumber + ", expected " + NEW_INDEX);
		ZoneHeader srcHdr = new ZoneHeader(srcZo(srcZo, tmp), Workspace.GameType.ORAS);
		check(hdr.areadataID == srcHdr.areadataID, "new zone areadataID equals source");
		check(hdr.mapmatrixID == srcHdr.mapmatrixID, "new zone mapmatrixID equals source");
		check(hdr.textID == srcHdr.textID, "new zone textID equals source");
		ZoneEntities entities = new ZoneEntities(zo.getFile(1));
		check(entities.npcs.size() == entities.NPCCount, "entities NPC list/count consistent");
		check(entities.warps.size() == entities.warpCount, "entities warp list/count consistent");
		GFLPawnScript script = new GFLPawnScript(zo.getFile(2));
		script.decompressThis();
		check(!script.instructions.isEmpty(), "new zone script has instructions");
		check(script.lookupInstructionByPtr(script.mainEntryPoint) != null, "new zone script main entry point valid");

		//entry 537: rows 0..535 byte-identical to the pristine master, row 536 correct
		for (int i = 0; i < masterOld.length; i++) {
			if (e537[i] != masterOld[i]) {
				check(false, String.format("master rows 0..535 changed at 0x%X", i));
			}
		}
		System.out.println("master rows 0..535 byte-identical");
		int newRowOff = NEW_INDEX * ZoneCloner.ZONE_HEADER_SIZE;
		int srcRowOff = SRC * ZoneCloner.ZONE_HEADER_SIZE;
		for (int i = 0; i < ZoneCloner.ZONE_HEADER_SIZE; i++) {
			if (i >= ZoneCloner.UNKNOWN_FLAGS_OFFSET && i < ZoneCloner.UNKNOWN_FLAGS_OFFSET + 4) {
				continue;
			}
			if (e537[newRowOff + i] != masterOld[srcRowOff + i]) {
				check(false, String.format("master row 536 differs from row %d outside the flags word at row offset 0x%X", SRC, i));
			}
		}
		int srcRowFlags = readIntLE(masterOld, srcRowOff + ZoneCloner.UNKNOWN_FLAGS_OFFSET);
		int newRowFlags = readIntLE(e537, newRowOff + ZoneCloner.UNKNOWN_FLAGS_OFFSET);
		check((newRowFlags >>> 21) == NEW_INDEX, "master row 536 OAZoneNumber == " + (newRowFlags >>> 21) + ", expected " + NEW_INDEX);
		check((newRowFlags & 0x1FFFFF) == (srcRowFlags & 0x1FFFFF), "master row 536 flags bits 0..20 preserved from source row");

		//entry 538: EN pack structure - count 537, all original offsets +4, empty new blob, blobs verbatim
		check(e538[0] == 'E' && e538[1] == 'N', "EN magic intact");
		int enCount = (e538[2] & 0xFF) | ((e538[3] & 0xFF) << 8);
		check(enCount == 537, "EN count == " + enCount + ", expected 537");
		check(readIntLE(e538, 4) == 0x86C, "EN first offset == 0x86C (table end)");
		for (int i = 0; i <= 536; i++) {
			int oldOff = readIntLE(enOld, 4 + i * 4);
			int newOff = readIntLE(e538, 4 + i * 4);
			if (newOff != oldOff + 4) {
				check(false, "EN offset " + i + " not shifted by exactly +4");
			}
		}
		System.out.println("EN offsets 0..536 all shifted by +4");
		int off536 = readIntLE(e538, 4 + 536 * 4);
		int off537 = readIntLE(e538, 4 + 537 * 4);
		check(off536 == off537, "EN offsets[536] == offsets[537] (empty new blob)");
		check(off537 == e538.length, "EN end sentinel == file length");
		int oldDataStart = readIntLE(enOld, 4);
		int newDataStart = readIntLE(e538, 4);
		for (int i = 0; i < enOld.length - oldDataStart; i++) {
			if (e538[newDataStart + i] != enOld[oldDataStart + i]) {
				check(false, String.format("EN blob data changed at data offset 0x%X", i));
			}
		}
		System.out.println("EN blob data byte-identical");

		//raw storage: entry 536 stored LZ11-compressed, 537/538 stored uncompressed
		RawEntry[] raw = parseRawEntries(garcCopy);
		check(raw.length == 539, "raw FATB parse found 539 entries");
		check(raw[536].len == p.compressedZo.length, "raw entry 536 length == compressed ZO length");
		check((raw[536].firstByte & 0xFF) == 0x11, "raw entry 536 starts with LZ11 magic (stored compressed)");
		check(raw[537].len == p.master.length, "raw entry 537 length == grown master length (stored uncompressed)");
		check(raw[537].firstByte == p.master[0], "raw entry 537 first byte == master first byte");
		check(raw[538].len == p.en.length, "raw entry 538 length == grown EN length (stored uncompressed)");
		check(raw[538].firstByte == 'E', "raw entry 538 starts with 'E' (stored uncompressed)");

		//GARC header fields of the packed file
		byte[] head = readN(garcCopy, 0x1C);
		check(readIntLE(head, 0x14) == (int) garcCopy.length(), "GARC header 0x14 == packed file length");
		check(readIntLE(head, 0x18) == p.en.length, "GARC header 0x18 (largest padded entry) == grown EN size 0x" + Integer.toHexString(p.en.length));

		System.out.println("stats: packed " + garcCopy.length() + " bytes (pristine " + pristineFile.length()
				+ "), new zone " + NEW_INDEX + " from source " + SRC + ", master " + e537.length
				+ " bytes, EN " + e538.length + " bytes");
		chainedAppend(pristine, srcZo, masterOld, enOld);
		System.out.println("PASS");
	}

	/**
	 * Verifies zone append CHAINS: a first append's grown master/EN must be
	 * valid input to a second append (536 -> 537 -> 538), so multiple new zones
	 * can be created over successive pack cycles.
	 */
	private static void chainedAppend(GARC pristine, byte[] srcZo, byte[] masterOld, byte[] enOld) {
		ZoneAppender.AppendPayloads c1 = ZoneAppender.buildAppendPayloads(srcZo, masterOld, enOld, SRC, 536);
		ZoneAppender.AppendPayloads c2 = ZoneAppender.buildAppendPayloads(pristine.getDecompressedEntry(25), c1.master, c1.en, 25, 537);
		check(c2.master.length == 538 * 0x38, "chained append: master grows to 538 rows");
		ZoneAppender.validateEN(c2.en, 538);
		boolean rowsKept = true;
		for (int r = 0; r < 537 * 0x38; r++) {
			if (c1.master[r] != c2.master[r]) { rowsKept = false; break; }
		}
		check(rowsKept, "chained append: second append preserves the first appended row");
		check(c2.en.length == enOld.length + 8, "chained append: EN grew by 8 bytes over two appends");
	}

	private static void expectThrow(String what, byte[] srcZo, byte[] master, byte[] en, int srcIndex, int newIndex) {
		try {
			ZoneAppender.buildAppendPayloads(srcZo, master, en, srcIndex, newIndex);
		} catch (IllegalArgumentException ex) {
			System.out.println("ok: rejected " + what + " (" + ex.getMessage() + ")");
			return;
		}
		check(false, "invalid input not rejected: " + what);
	}

	/** Writes the source ZO to a temp file and returns subfile 0 for header parsing. */
	private static byte[] srcZo(byte[] srcZo, File tmp) throws IOException {
		File f = new File(tmp, "zoSrc");
		writeAll(f, srcZo);
		return new ZO(f).getFile(0);
	}

	private static class RawEntry {

		int len;
		byte firstByte;
	}

	/** Minimal raw GARC parse (VER_4, flags == 1) - entry lengths and first data bytes as stored on disk. */
	private static RawEntry[] parseRawEntries(File garcFile) throws IOException {
		byte[] b = Files.readAllBytes(garcFile.toPath());
		int headerLen = readIntLE(b, 0x4);
		int dataOffset = readIntLE(b, 0x10);
		int fatoPos = headerLen;
		int fatoLen = readIntLE(b, fatoPos + 4);
		int count = (b[fatoPos + 8] & 0xFF) | ((b[fatoPos + 9] & 0xFF) << 8);
		int fatbPos = fatoPos + fatoLen;
		RawEntry[] out = new RawEntry[count];
		for (int i = 0; i < count; i++) {
			int entryPos = fatbPos + 0xC + i * 16; //flags, start, end, length
			int start = readIntLE(b, entryPos + 4);
			out[i] = new RawEntry();
			out[i].len = readIntLE(b, entryPos + 12);
			out[i].firstByte = out[i].len > 0 ? b[dataOffset + start] : 0;
		}
		return out;
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("FAIL: " + what);
			System.exit(1);
		}
		System.out.println("ok: " + what);
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static byte[] readN(File f, int n) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[n];
		in.read(b);
		in.close();
		return b;
	}

	private static void writeAll(File f, byte[] b) throws IOException {
		OutputStream os = new FileOutputStream(f);
		os.write(b);
		os.flush();
		os.close();
	}
}
