package ctrmap.tests;

import ctrmap.Workspace;
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

/**
 * Headless test of ZoneCloner's core byte transform. Operates ONLY on copies
 * of the PRISTINE ORAS ZoneData GARC extracted into a fresh temp dir (never
 * the real workspace). Run with:
 * java -cp build/classes ctrmap.tests.ZoneClonerTest
 *
 * Clones zone 24 over zone 100 and asserts:
 * - dst ZO bytes == src ZO bytes except the OAZoneNumber bits of the header
 *   flags word ((flags &gt;&gt;&gt; 21) == 100, all other bits/bytes identical)
 * - master table row 100 == master table row 24 except bits 21..31, all other
 *   master bytes untouched
 * - the cloned ZO still parses as a structurally valid zone (ZoneHeader +
 *   ZoneEntities + GFLPawnScript)
 */
public class ZoneClonerTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
	private static final int SRC = 24;
	private static final int DST = 100;

	public static void main(String[] args) throws IOException {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);
		int masterIndex = garc.length - 2; //ORAS: last entry is the EN pack, second-to-last the master table
		check(garc.length == 538, "pristine ORAS ZoneData has 538 entries, got " + garc.length);

		//simulate the workspace layout on COPIES in a fresh temp dir
		File tmp = Files.createTempDirectory("ZoneClonerTest").toFile();
		File srcFile = new File(tmp, String.valueOf(SRC));
		File dstFile = new File(tmp, String.valueOf(DST));
		File masterFile = new File(tmp, String.valueOf(masterIndex));
		byte[] srcOrig = garc.getDecompressedEntry(SRC);
		byte[] dstOrig = garc.getDecompressedEntry(DST);
		byte[] masterOrig = garc.getDecompressedEntry(masterIndex);
		check(srcOrig != null && dstOrig != null && masterOrig != null, "could not extract entries " + SRC + "/" + DST + "/" + masterIndex);
		check(masterOrig.length == 536 * ZoneCloner.ZONE_HEADER_SIZE, "master table is 536 rows x 0x38, got " + masterOrig.length + " bytes");
		writeAll(srcFile, srcOrig);
		writeAll(dstFile, dstOrig);
		writeAll(masterFile, masterOrig);

		//the core clone transform, file locations injected
		ZoneCloner.cloneIntoFiles(srcFile, dstFile, masterFile, SRC, DST, true);

		byte[] srcAfter = readAll(srcFile);
		byte[] dstAfter = readAll(dstFile);
		byte[] masterAfter = readAll(masterFile);

		//source file must be untouched
		check(java.util.Arrays.equals(srcAfter, srcOrig), "source zone file was modified");

		//dst == src except the 4 header flag bytes; flags differ ONLY in bits 21..31
		check(dstAfter.length == srcOrig.length, "dst length " + dstAfter.length + " != src length " + srcOrig.length);
		int headerOffset = readIntLE(srcOrig, 4);
		check(headerOffset == readIntLE(dstAfter, 4), "subfile 0 offset differs between src and dst");
		int flagsOffset = headerOffset + ZoneCloner.UNKNOWN_FLAGS_OFFSET;
		for (int i = 0; i < dstAfter.length; i++) {
			if (i >= flagsOffset && i < flagsOffset + 4) {
				continue;
			}
			if (dstAfter[i] != srcOrig[i]) {
				check(false, String.format("dst differs from src outside the flags word at 0x%X: %02X != %02X", i, dstAfter[i], srcOrig[i]));
			}
		}
		int srcFlags = readIntLE(srcOrig, flagsOffset);
		int dstFlags = readIntLE(dstAfter, flagsOffset);
		check((dstFlags >>> 21) == DST, "dst OAZoneNumber (flags >>> 21) == " + (dstFlags >>> 21) + ", expected " + DST);
		check((dstFlags & 0x1FFFFF) == (srcFlags & 0x1FFFFF), "dst flags bits 0..20 changed: 0x" + Integer.toHexString(dstFlags & 0x1FFFFF) + " != 0x" + Integer.toHexString(srcFlags & 0x1FFFFF));
		System.out.println("src zone " + SRC + " OAZoneNumber was " + (srcFlags >>> 21) + ", dst patched to " + (dstFlags >>> 21));

		//master row DST == master row SRC except bits 21..31; everything else untouched
		check(masterAfter.length == masterOrig.length, "master table length changed");
		int srcRow = SRC * ZoneCloner.ZONE_HEADER_SIZE;
		int dstRow = DST * ZoneCloner.ZONE_HEADER_SIZE;
		for (int i = 0; i < masterAfter.length; i++) {
			if (i >= dstRow && i < dstRow + ZoneCloner.ZONE_HEADER_SIZE) {
				continue;
			}
			if (masterAfter[i] != masterOrig[i]) {
				check(false, String.format("master byte outside row %d changed at 0x%X", DST, i));
			}
		}
		int rowFlagsOffset = ZoneCloner.UNKNOWN_FLAGS_OFFSET;
		for (int i = 0; i < ZoneCloner.ZONE_HEADER_SIZE; i++) {
			if (i >= rowFlagsOffset && i < rowFlagsOffset + 4) {
				continue;
			}
			if (masterAfter[dstRow + i] != masterOrig[srcRow + i]) {
				check(false, String.format("master row %d differs from row %d outside the flags word at row offset 0x%X", DST, SRC, i));
			}
		}
		int srcRowFlags = readIntLE(masterOrig, srcRow + rowFlagsOffset);
		int dstRowFlags = readIntLE(masterAfter, dstRow + rowFlagsOffset);
		check((dstRowFlags >>> 21) == DST, "master row OAZoneNumber == " + (dstRowFlags >>> 21) + ", expected " + DST);
		check((dstRowFlags & 0x1FFFFF) == (srcRowFlags & 0x1FFFFF), "master row flags bits 0..20 changed");
		//the master convention: bit 16 clear even where the zone's own header has it set
		check((dstRowFlags >> 16 & 1) == (srcRowFlags >> 16 & 1), "master row bit16 convention not preserved");

		//the cloned ZO must still parse as a structurally valid zone
		ZO zo = new ZO(dstFile);
		check(zo.len == 5, "cloned ZO subfile count == " + zo.len + ", expected 5");
		ZoneHeader header = new ZoneHeader(zo.getFile(0), Workspace.GameType.ORAS);
		check(header.OAZoneNumber == DST, "parsed ZoneHeader.OAZoneNumber == " + header.OAZoneNumber + ", expected " + DST);
		ZoneHeader srcHeader = new ZoneHeader(new ZO(srcFile).getFile(0), Workspace.GameType.ORAS);
		check(header.areadataID == srcHeader.areadataID, "cloned areadataID differs from source");
		check(header.mapmatrixID == srcHeader.mapmatrixID, "cloned mapmatrixID differs from source");
		check(header.textID == srcHeader.textID, "cloned textID differs from source");
		check(header.parentMap == srcHeader.parentMap, "cloned parentMap differs from source");
		ZoneEntities entities = new ZoneEntities(zo.getFile(1));
		check(entities.npcs.size() == entities.NPCCount, "entities NPC list/count mismatch");
		check(entities.warps.size() == entities.warpCount, "entities warp list/count mismatch");
		check(entities.triggers1.size() == entities.trigger1Count, "entities trigger1 list/count mismatch");
		check(entities.totalLength == 8 + entities.furnitureCount * 0x14 + entities.NPCCount * 0x30
				+ (entities.warpCount + entities.trigger1Count + entities.trigger2Count) * 0x18,
				"entities totalLength inconsistent with record counts");
		GFLPawnScript script = new GFLPawnScript(zo.getFile(2));
		script.decompressThis();
		check(!script.instructions.isEmpty(), "cloned zone script has no instructions");
		check(script.lookupInstructionByPtr(script.mainEntryPoint) != null, "cloned zone script main entry point invalid");

		//best-effort temp cleanup
		srcFile.delete();
		dstFile.delete();
		masterFile.delete();
		tmp.delete();

		System.out.println("PASS");
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("FAIL: " + what);
			System.exit(1);
		}
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
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
