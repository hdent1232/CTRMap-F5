package ctrmap.tests;

import ctrmap.LittleEndianDataOutputStream;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.TileMapPanel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * The guards around the NPC record and the zone entity block. Each of these
 * was a live defect that reported success to the user.
 *
 * <ol>
 * <li>An NPC's altitude became NaN on the lower half of any map taller than
 *     it is wide - the height lookup tested both axes against the width -
 *     and NaN went straight into the zone file: the NPC vanished from the
 *     viewport and the Altitude field read "NaN". 27 retail matrices are
 *     taller than wide; zone 33 alone has 13 NPCs standing in those rows.
 *     Each axis now has its own bound, and a non-finite altitude is refused
 *     at the setter and at save.</li>
 * <li>The NPC editor kept a List&lt;H3DModel&gt; positionally parallel to the
 *     NPC list and deleted from it by object. Two NPCs sharing a MoveModel
 *     share the cached instance, so "Remove entry" took out the FIRST slot
 *     holding that model and every later NPC rendered as its neighbour. 184
 *     of 453 retail zones share a model between NPCs. There is no parallel
 *     list any more: the model is looked up from the record it belongs to.
 *     NpcEditFormGuardsTest deletes through the form and asks which model
 *     each survivor renders; the record-level checks are here.</li>
 * <li>Deleting an NPC never renumbered the ones after it, but all 2904
 *     retail NPCs hold uid == index and the editor's own dropdown, overlay
 *     and selection all assume it: after one delete the item labelled "5"
 *     edited uid 6 and the red box sat on a different NPC than the one in
 *     the form. Removal renumbers, and assembling refuses the broken
 *     invariant.</li>
 * <li>An NPC could be pointed at a script id its zone does not define, and
 *     both the save guard and the Dialogue note treated that exactly like a
 *     valid advanced script. Every retail NPC with a local id points at a
 *     case the dispatch defines; the editor now refuses the ones that do
 *     not - NpcEditFormGuardsTest drives the form's Save, NPC dropdown and
 *     Dialogue note; this suite covers the helper and the corpus.</li>
 * <li>The 256th prop, NPC, warp or trigger was written as a count byte of 0
 *     while every record stayed in the file, so the next workspace load
 *     threw parsing the script block and the zone list came up empty with
 *     no explanation. Assembling now refuses more than 255 of a kind.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.NpcEntityGuardsTest &lt;ZoneData GARC (a/0/1/3)&gt;
 */
public class NpcEntityGuardsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/1/3");
		GARC garc = garcFile.isFile() ? new GARC(garcFile) : null;
		if (garc == null) {
			System.out.println("  skip: no ZoneData GARC at " + garcFile + " - corpus checks not run");
		}
		//record checks first: each refusal is also logged to stderr by
		//assembleData, and test.ps1 shows a suite's last two lines - the
		//corpus sweep at the end keeps the verdict, not a stack trace, there
		nanAltitude();
		heightLookupBounds();
		uidIsIndex(garc);
		removeNpcRenumbers(garc);
		entityCeiling();
		scriptIdExists(garc);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A non-finite altitude must never reach the record, let alone the file. */
	static void nanAltitude() {
		ZoneEntities.NPC npc = new ZoneEntities.NPC();
		try {
			npc.setY(Float.NaN);
			System.out.println("  FAIL: setY accepted NaN");
			fails++;
		} catch (IllegalArgumentException ex) {
			System.out.println("  ok: setY refuses NaN (" + ex.getMessage() + ")");
		}
		check(npc.z3DCoordinate == 0f, "the refused NaN left the altitude untouched");

		ZoneEntities e = emptyEntities();
		ZoneEntities.NPC bad = new ZoneEntities.NPC();
		bad.z3DCoordinate = Float.NaN; //what the NPC tool used to assign after a drag
		e.npcs.add(bad);
		try {
			e.assembleData();
			System.out.println("  FAIL: an NPC with a NaN altitude serialised without complaint");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: a NaN altitude refuses to serialise (" + ex.getMessage() + ")");
		}
		bad.z3DCoordinate = 12.5f;
		check(new ZoneEntities(e.assembleData()).npcs.get(0).z3DCoordinate == 12.5f, "a finite altitude round-trips");
	}

	/**
	 * The height lookup indexes colls[width][height], so each axis has its
	 * own bound. Null cells stand in for collision files: in range reads 0,
	 * out of range reads NaN, and nothing throws.
	 */
	static void heightLookupBounds() {
		GRCollisionFile[][] tall = new GRCollisionFile[2][4]; //matrix 21 (zones 33-35): 2 wide, 4 tall
		check(!Float.isNaN(TileMapPanel.getHeightAtWorldLoc(tall, 100f, 720f + 10f)), "tall matrix: a row inside the width is in range");
		check(!Float.isNaN(TileMapPanel.getHeightAtWorldLoc(tall, 100f, 2 * 720f + 10f)), "tall matrix: the first row past the width is still in range");
		check(!Float.isNaN(TileMapPanel.getHeightAtWorldLoc(tall, 100f, 4 * 720f - 1f)), "tall matrix: the last row is in range");
		check(Float.isNaN(TileMapPanel.getHeightAtWorldLoc(tall, 100f, 4 * 720f)), "tall matrix: past the last row is NaN");
		check(Float.isNaN(TileMapPanel.getHeightAtWorldLoc(tall, 2 * 720f, 100f)), "tall matrix: past the width is NaN");
		GRCollisionFile[][] wide = new GRCollisionFile[4][2]; //matrix 8 (14x8) shape
		check(Float.isNaN(TileMapPanel.getHeightAtWorldLoc(wide, 100f, 3 * 720f)), "wide matrix: a row past the height is NaN, not an exception");
		check(Float.isNaN(TileMapPanel.getHeightAtWorldLoc(wide, -800f, 100f)), "a negative position is NaN, not an exception");
	}

	/**
	 * uid == index, the invariant every retail NPC holds and the editor's
	 * dropdown, overlay and selection all rely on.
	 */
	static void uidIsIndex(GARC garc) {
		if (garc != null) {
			int total = 0, equal = 0;
			for (int z = 0; z < garc.length; z++) {
				ZoneEntities e = zoneEntities(garc, z);
				if (e == null) {
					continue;
				}
				for (int i = 0; i < e.npcs.size(); i++) {
					total++;
					if (e.npcs.get(i).uid == i) {
						equal++;
					}
				}
			}
			check(total > 2000 && equal == total, "every retail NPC holds uid == index (" + equal + " / " + total + ")");
		}

		ZoneEntities e = emptyEntities();
		for (int i = 0; i < 3; i++) {
			ZoneEntities.NPC npc = new ZoneEntities.NPC();
			npc.uid = i;
			e.npcs.add(npc);
		}
		check(new ZoneEntities(e.assembleData()).npcs.size() == 3, "sequential uids assemble");
		e.npcs.remove(0); //what "Remove entry" used to do: a hole, and no renumbering
		try {
			e.assembleData();
			System.out.println("  FAIL: NPCs with uid != index serialised without complaint");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: uid != index refuses to serialise (" + ex.getMessage() + ")");
		}
	}

	/** Removing an NPC renumbers the ones after it, so the record and the file agree. */
	static void removeNpcRenumbers(GARC garc) {
		ZoneEntities e = emptyEntities();
		for (int i = 0; i < 3; i++) {
			ZoneEntities.NPC npc = new ZoneEntities.NPC();
			npc.uid = i;
			npc.model = 100 + i;
			e.npcs.add(npc);
		}
		e.removeNPC(1);
		check(e.npcs.size() == 2 && e.NPCCount == 2, "removeNPC drops the record and the count");
		check(e.npcs.get(0).model == 100 && e.npcs.get(1).model == 102, "removeNPC removed the NPC asked for, by position");
		check(e.firstMisnumberedNPC() == -1 && e.npcs.get(1).uid == 1, "the NPC after the hole took its uid");
		check(new ZoneEntities(e.assembleData()).npcs.get(1).uid == 1, "and it assembles");

		if (garc == null) {
			return;
		}
		ZoneEntities z451 = zoneEntities(garc, 451); //27 NPCs, the audit's example
		if (z451 == null || z451.npcs.size() < 2) {
			System.out.println("  skip: zone 451 has no NPCs to remove");
			return;
		}
		int before = z451.npcs.size();
		z451.removeNPC(0);
		check(z451.npcs.size() == before - 1 && z451.firstMisnumberedNPC() == -1,
				"zone 451: deleting NPC 0 leaves " + (before - 1) + " NPCs numbered 0.." + (before - 2));
		check(new ZoneEntities(z451.assembleData()).npcs.size() == before - 1, "zone 451 assembles after the delete");
	}

	/**
	 * "Does not exist" is distinct from "not a talker". Zone 24 defines
	 * cases 1..10; 0 means no script and 2000+ belongs to the engine.
	 */
	static void scriptIdExists(GARC garc) {
		if (garc == null) {
			return;
		}
		GFLPawnScript z24 = zoneScript(garc, 24);
		if (z24 == null) {
			System.out.println("  skip: zone 24 script not readable");
			return;
		}
		check(TalkerScriptWizard.scriptIdExists(z24, 7), "zone 24: script 7 is defined");
		check(TalkerScriptWizard.scriptIdExists(z24, 10), "zone 24: script 10 is defined");
		check(!TalkerScriptWizard.scriptIdExists(z24, 11), "zone 24: script 11 does not exist");
		check(!TalkerScriptWizard.scriptIdExists(z24, 999), "zone 24: script 999 does not exist");
		check(TalkerScriptWizard.scriptIdExists(z24, 0), "script 0 (no script) is always allowed");
		check(TalkerScriptWizard.scriptIdExists(z24, 3000 + 5), "an engine-reserved id is always allowed");

		int npcs = 0, dangling = 0;
		for (int z = 0; z < garc.length; z++) {
			ZoneEntities e = zoneEntities(garc, z);
			GFLPawnScript s = e == null ? null : zoneScript(garc, z);
			if (s == null) {
				continue;
			}
			for (ZoneEntities.NPC npc : e.npcs) {
				npcs++;
				if (!TalkerScriptWizard.scriptIdExists(s, npc.script)) {
					dangling++;
				}
			}
		}
		check(npcs > 2000 && dangling == 0, "every retail NPC points at a script its zone defines (" + npcs + " checked)");
	}

	/** The count fields are single bytes; a 256th record must be refused, not wrapped to 0. */
	static void entityCeiling() {
		ZoneEntities e = emptyEntities();
		for (int i = 0; i < 255; i++) {
			e.furniture.add(new ZoneEntities.Prop());
		}
		ZoneEntities back = new ZoneEntities(e.assembleData());
		check(back.furnitureCount == 255 && back.furniture.size() == 255, "255 props assemble and read back as 255");

		e.furniture.add(new ZoneEntities.Prop());
		try {
			e.assembleData();
			System.out.println("  FAIL: 256 props serialised without complaint");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: a 256th prop refuses to serialise (" + ex.getMessage() + ")");
		}

		ZoneEntities n = emptyEntities();
		for (int i = 0; i < 256; i++) {
			ZoneEntities.NPC npc = new ZoneEntities.NPC();
			npc.uid = i;
			n.npcs.add(npc);
		}
		try {
			n.assembleData();
			System.out.println("  FAIL: 256 NPCs serialised without complaint");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: a 256th NPC refuses to serialise (" + ex.getMessage() + ")");
		}
	}

	/** Subfile index of ZoneData entry z, or null when the entry is not a ZO container. */
	static byte[] subfile(GARC garc, int z, int index) {
		byte[] zo = garc.getDecompressedEntry(z);
		if (zo == null || zo.length < 4 || (((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF)) != 0x5A4F) {
			return null;
		}
		int count = (zo[2] & 0xFF) | ((zo[3] & 0xFF) << 8);
		if (count <= index || zo.length < 4 + (count + 1) * 4) {
			return null;
		}
		int start = readIntLE(zo, 4 + index * 4), end = readIntLE(zo, 4 + (index + 1) * 4);
		if (start < 0 || end > zo.length || end <= start) {
			return null;
		}
		byte[] sub = new byte[end - start];
		System.arraycopy(zo, start, sub, 0, sub.length);
		return sub;
	}

	static ZoneEntities zoneEntities(GARC garc, int z) {
		byte[] ent = subfile(garc, z, 1);
		return ent == null ? null : new ZoneEntities(ent);
	}

	static GFLPawnScript zoneScript(GARC garc, int z) {
		byte[] scr = subfile(garc, z, 2);
		if (scr == null) {
			return null;
		}
		GFLPawnScript s = new GFLPawnScript(scr);
		s.decompressThis();
		return s;
	}

	static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	/** An entity block with no records and the smallest script that parses. */
	static ZoneEntities emptyEntities() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
			dos.writeInt(8);
			dos.write(new byte[8]); //five zero counts and padding
			dos.writeInt(64); //len
			dos.writeShort((short) 0xE0F1); //magic
			dos.write(1); //ver
			dos.write(1); //minCompatVer
			dos.writeShort((short) 0); //flags
			dos.writeShort((short) 8); //defsize
			dos.writeInt(60); //instructionStart
			dos.writeInt(60); //dataStart
			dos.writeInt(64); //heapStart
			dos.writeInt(0x1000); //allocatedMem
			dos.writeInt(0); //mainEntryPoint
			for (int i = 0; i < 7; i++) {
				dos.writeInt(60); //publics .. overlays, all empty
			}
			dos.write(new byte[]{0x11, 0x22, 0x33, 0x44}); //compCode
			dos.close();
			return new ZoneEntities(baos.toByteArray());
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
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
