package ctrmap.tests;

import ctrmap.formats.containers.ContainerBytes;
import java.util.Arrays;
import static ctrmap.util.LittleEndian.putI32;
import static ctrmap.util.LittleEndian.putU16;

/**
 * The in-memory mini-pack reader every GARC walker now shares.
 *
 * <p>Thirty-four private copies of this slicer were folded into
 * {@link ContainerBytes}. The suites that went through the copies still run
 * against real containers; what none of them ever asked is what the slicer
 * answers for a container that is wrong - cut short, an index past the count,
 * an offset table that runs backwards - because a retail dump has no such
 * container. This suite hands it those, and pins that the honest answer is
 * null rather than an exception or a slice of the wrong bytes.
 *
 * Usage: java ctrmap.tests.ContainerBytesTest
 */
public class ContainerBytesTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		byte[] c = pack(new byte[][]{{1, 2, 3}, {}, {9, 8, 7, 6, 5}});
		wellFormed(c);
		outsideTheTable(c);
		malformed(c);
		nothingAtAll();
		aReplacementIsAskedWhatItIsFirst(new java.io.File(args.length > 0 ? args[0] : "src"));
		theBuilderShowsTheContainerItOpened();
		choosingAnArchiveEntryShowsTheContainerItHolds();
		aModelImportStopsOnlyWhenItsTemplateCannotBeWritten();
		aContainerIsReplacedOnlyByAContainer();
		aBlankContainerIsMadeOnlyOfAKindThatCanBe();
		aClearThatCannotStoreOneSubfileChangesNothing();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A mini-pack with the given subfiles, magic "GR", offsets padded to 0x80 like the real ones. */
	static byte[] pack(byte[][] subs) {
		int header = 4 + (subs.length + 1) * 4;
		int[] off = new int[subs.length + 1];
		int at = pad(header);
		for (int i = 0; i < subs.length; i++) {
			off[i] = at;
			at = pad(at + subs[i].length);
		}
		off[subs.length] = at;
		byte[] c = new byte[at];
		c[0] = 'G';
		c[1] = 'R';
		putU16(c, 2, subs.length);
		for (int i = 0; i <= subs.length; i++) {
			putI32(c, 4 + i * 4, off[i]);
		}
		for (int i = 0; i < subs.length; i++) {
			System.arraycopy(subs[i], 0, c, off[i], subs[i].length);
		}
		return c;
	}

	static int pad(int n) {
		return (n + 0x7F) & ~0x7F;
	}

	static void wellFormed(byte[] c) {
		check(ContainerBytes.count(c) == 3, "count reads the u16 at offset 2: " + ContainerBytes.count(c));
		byte[] s0 = ContainerBytes.subfile(c, 0);
		//a padded container: the slice runs to the next subfile's offset, padding included
		check(s0 != null && s0.length == 0x80 && s0[0] == 1 && s0[1] == 2 && s0[2] == 3 && s0[3] == 0,
				"subfile 0 is its bytes up to the next offset: " + (s0 == null ? "null" : s0.length + " bytes"));
		byte[] s1 = ContainerBytes.subfile(c, 1);
		check(s1 != null && s1.length == 0, "a subfile the table says is empty is an empty array, not null");
		byte[] s2 = ContainerBytes.subfile(c, 2);
		check(s2 != null && s2[0] == 9 && s2[4] == 5, "the last subfile runs to the end offset");
		check(s2 != null && s2.length == c.length - 0x100, "the last subfile ends where the container does");

		//an unpadded one, the shape a packer that wrote no padding produces
		byte[] tight = new byte[4 + 3 * 4 + 5];
		tight[0] = 'Z';
		tight[1] = 'O';
		putU16(tight, 2, 2);
		putI32(tight, 4, 16);
		putI32(tight, 8, 18);
		putI32(tight, 12, 21);
		for (int i = 16; i < 21; i++) {
			tight[i] = (byte) (0x40 + i);
		}
		byte[] t0 = ContainerBytes.subfile(tight, 0), t1 = ContainerBytes.subfile(tight, 1);
		check(t0 != null && t0.length == 2 && t0[0] == 0x50 && t1 != null && t1.length == 3 && t1[2] == 0x54,
				"offsets are honoured exactly when nothing is padded");
	}

	static void outsideTheTable(byte[] c) {
		check(ContainerBytes.subfile(c, 3) == null, "an index equal to the count is null, not an exception");
		check(ContainerBytes.subfile(c, 99) == null, "an index far past the count is null");
		check(ContainerBytes.subfile(c, -1) == null, "a negative index is null");
	}

	/** Every way a container can lie, and the reader must not believe it. */
	static void malformed(byte[] c) {
		//the count claims more subfiles than the offset table holds
		byte[] tall = c.clone();
		putU16(tall, 2, 200);
		check(ContainerBytes.count(tall) == 200, "count repeats what the header says even when it is wrong");
		check(ContainerBytes.subfile(tall, 150) == null, "an index whose offsets would lie past the end is null, not an exception");
		check(Arrays.equals(ContainerBytes.subfile(tall, 0), ContainerBytes.subfile(c, 0)),
				"...but the subfiles the table does hold still read");

		//cut the container off inside the offset table
		byte[] cut = Arrays.copyOf(c, 10);
		check(ContainerBytes.subfile(cut, 0) == null, "a container cut off inside its offset table is null for every index");

		//an end offset past the buffer
		byte[] past = c.clone();
		putI32(past, 4 + 3 * 4, c.length + 1);
		check(ContainerBytes.subfile(past, 2) == null, "an offset past the end is null");
		check(ContainerBytes.subfile(past, 1) != null, "...and only the subfile it bounds is affected");

		//offsets that run backwards
		byte[] back = c.clone();
		putI32(back, 4 + 1 * 4, 0x200);
		check(ContainerBytes.subfile(back, 0) == null, "a subfile whose end is before its start is null");
		check(ContainerBytes.subfile(back, 1) == null, "...and so is the neighbour that shares the bad offset");

		//a negative start offset
		byte[] neg = c.clone();
		putI32(neg, 4, -4);
		check(ContainerBytes.subfile(neg, 0) == null, "a negative offset is null");
	}

	static void nothingAtAll() {
		check(ContainerBytes.count(null) == 0 && ContainerBytes.subfile(null, 0) == null, "no container: count 0, subfile null");
		check(ContainerBytes.count(new byte[0]) == 0 && ContainerBytes.subfile(new byte[0], 0) == null, "an empty buffer: count 0, subfile null");
		check(ContainerBytes.count(new byte[3]) == 0, "three bytes is not enough for a count");
		byte[] headerOnly = {'G', 'R', 1, 0};
		check(ContainerBytes.count(headerOnly) == 1 && ContainerBytes.subfile(headerOnly, 0) == null,
				"a header with no offset table declares its count but answers null for the subfile");
		check(ContainerBytes.offsets(null) == null && ContainerBytes.offsets(headerOnly) == null,
				"and no whole offset table either (F215)");
		byte[] c = pack(new byte[][]{{1, 2, 3}, {}, {9, 8, 7, 6, 5}});
		int[] off = ContainerBytes.offsets(c);
		boolean asSubfiles = off != null && off.length == 4 && off[3] == c.length;
		for (int i = 0; asSubfiles && i < 3; i++) {
			asSubfiles = ContainerBytes.subfile(c, i).length == off[i + 1] - off[i];
		}
		check(asSubfiles, "the whole table is count + 1 entries, the last the end, and each gap is that subfile's length: "
				+ java.util.Arrays.toString(off));
		check(ContainerBytes.offsets(java.util.Arrays.copyOf(c, 4 + 3 * 4)) == null,
				"and a table cut one entry short is null, not three entries and a zero");
	}

	/**
	 * Choosing an entry of an archive in the Builder shows the container the entry holds, read by its magic (the
	 * 2026-10-15 shared sweep, Builder 114, negate-if, SURVIVED: no suite chose an entry). Inverted, the magic of
	 * every entry longer than three bytes went unread and no container was ever shown. A scratch game whose region
	 * archive holds two "GR" containers of two subfiles each.
	 */
	static void choosingAnArchiveEntryShowsTheContainerItHolds() throws Exception {
		System.out.println("--- choosing an archive entry in the Builder shows the container it holds");
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		String shown;
		try {
			MapResizeTest.game("builder_entry");
			ctrmap.humaninterface.builder.Builder builder = new ctrmap.humaninterface.builder.Builder((title, tool) -> { });
			builder.loadGARC(ctrmap.gamedef.ArchiveType.FIELD_DATA.ordinal());
			java.lang.reflect.Field entries = builder.getClass().getDeclaredField("garcFileList");
			entries.setAccessible(true);
			java.lang.reflect.Field files = builder.getClass().getDeclaredField("contFileList");
			files.setAccessible(true);
			java.lang.reflect.Field held = builder.getClass().getDeclaredField("currentAGFC");
			held.setAccessible(true);
			javax.swing.JList<?> list = (javax.swing.JList<?>) entries.get(builder);
			check(list.getModel().getSize() == 2 && held.get(builder) == null,
					"control: the archive lists its 2 entries and no container is shown yet (" + list.getModel().getSize() + ")");
			list.setSelectedIndex(1);
			Object cont = held.get(builder);
			shown = cont == null ? "nothing" : cont.getClass().getSimpleName() + " of "
					+ ((javax.swing.JList<?>) files.get(builder)).getModel().getSize() + " files";
		} finally {
			ctrmap.Workspace.install(was);
		}
		check("GR of 2 files".equals(shown), "choosing region 1 shows its container: " + shown);
	}

	/**
	 * Importing an OBJ over a model in the Builder stops, saying so, only when the converter's template model could
	 * not be written (the 2026-10-15 shared sweep, Builder 488, negate-if, SURVIVED: no suite pressed Import on a
	 * model). Inverted, every import stopped there with a template written, and one without went on to the converter
	 * with none. With no converter set, a run that gets past the template stops at "ESPICA path not set" - nothing is
	 * launched. The Builder's last-folder setting, which the file chooser writes, is put back as it was.
	 */
	static void aModelImportStopsOnlyWhenItsTemplateCannotBeWritten() throws Exception {
		System.out.println("--- an OBJ import over a model stops only when the converter's template cannot be written");
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		String converter = ctrmap.Workspace.ESPICA_PATH;
		java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot()
				.node(ctrmap.humaninterface.builder.Builder.class.getName());
		String lastDir = node.get("LAST_DIR", null);
		String written;
		String unwritable;
		try {
			ctrmap.Workspace.ESPICA_PATH = null;
			ctrmap.WorkspaceSession ws = MapResizeTest.game("builder_obj");
			java.io.File gr = new java.io.File(Scratch.dir("builder_obj_gr"), "models.gr");
			java.nio.file.Files.write(gr.toPath(), pack(new byte[][]{
				ctrmap.resources.ResourceAccess.getByteArray("DummyBCH3DModel.bch")}));
			java.io.File obj = new java.io.File(Scratch.dir("builder_obj_in"), "model.obj");
			java.nio.file.Files.write(obj.toPath(), "o model".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			ctrmap.humaninterface.builder.Builder builder = new ctrmap.humaninterface.builder.Builder((title, tool) -> { });
			builder.loadContainer(new ctrmap.formats.containers.GR(gr, ws));
			java.lang.reflect.Field list = builder.getClass().getDeclaredField("contFileList");
			list.setAccessible(true);
			((javax.swing.JList<?>) list.get(builder)).setSelectedIndex(0);
			java.lang.reflect.Method importBtn = builder.getClass().getDeclaredMethod("btnFAImportActionPerformed",
					java.awt.event.ActionEvent.class);
			importBtn.setAccessible(true);
			java.util.List<String> said = ctrmap.Ui.record(javax.swing.JOptionPane.YES_OPTION, obj, javax.swing.JOptionPane.NO_OPTION);
			try {
				importBtn.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			written = said.toString();
			//and with the workspace's scratch folder a file, so the template cannot be written into it - the model
			//chosen again, since every import ends by reading the container again, which drops the selection
			java.io.File temp = ws.temp();
			for (java.io.File f : temp.listFiles()) {
				java.nio.file.Files.delete(f.toPath());
			}
			java.nio.file.Files.delete(temp.toPath());
			java.nio.file.Files.write(temp.toPath(), new byte[]{1});
			((javax.swing.JList<?>) list.get(builder)).setSelectedIndex(0);
			said = ctrmap.Ui.record(javax.swing.JOptionPane.YES_OPTION, obj, javax.swing.JOptionPane.NO_OPTION);
			try {
				importBtn.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			unwritable = said.toString();
		} finally {
			ctrmap.Workspace.ESPICA_PATH = converter;
			ctrmap.Workspace.install(was);
			if (lastDir == null) {
				node.remove("LAST_DIR");
			} else {
				node.put("LAST_DIR", lastDir);
			}
		}
		check(written.contains("Do you want to import it as OBJ?") && written.contains("ESPICA path not set")
				&& !written.contains("template model could not be written"),
				"with its template written, the import goes on to the converter: " + written);
		check(unwritable.contains("template model could not be written") && !unwritable.contains("ESPICA path not set"),
				"and with none, it stops and says why: " + unwritable);
	}

	/**
	 * Replacing the shown container from a file takes a container of its kind and refuses anything else, leaving the
	 * container as it was (the 2026-10-15 shared sweep, Builder 559, negate-if, SURVIVED: aReplacementIsAskedWhatItIsFirst
	 * reads the order in the source, and no suite pressed the button). Inverted, a file that is no container was copied
	 * over the container and one that is was refused, "not replaced: null". The Builder's LAST_DIR is put back.
	 */
	static void aContainerIsReplacedOnlyByAContainer() throws Exception {
		System.out.println("--- a container is replaced only by a container of its kind");
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot()
				.node(ctrmap.humaninterface.builder.Builder.class.getName());
		String lastDir = node.get("LAST_DIR", null);
		byte[] before = pack(new byte[][]{{1, 2, 3}});
		byte[] another = pack(new byte[][]{{4, 5}, {6}});
		String junkSaid;
		String containerSaid;
		byte[] afterJunk;
		byte[] afterContainer;
		try {
			ctrmap.WorkspaceSession ws = MapResizeTest.game("builder_replace");
			java.io.File shown = new java.io.File(Scratch.dir("builder_replace_gr"), "region.gr");
			java.nio.file.Files.write(shown.toPath(), before);
			java.io.File junk = new java.io.File(Scratch.dir("builder_replace_in"), "notes.txt");
			java.nio.file.Files.write(junk.toPath(), "no container".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			java.io.File gr = new java.io.File(Scratch.dir("builder_replace_in2"), "other.gr");
			java.nio.file.Files.write(gr.toPath(), another);
			ctrmap.humaninterface.builder.Builder builder = new ctrmap.humaninterface.builder.Builder((title, tool) -> { });
			builder.loadContainer(new ctrmap.formats.containers.GR(shown, ws));
			java.lang.reflect.Method replace = builder.getClass().getDeclaredMethod("btnImportContainerActionPerformed",
					java.awt.event.ActionEvent.class);
			replace.setAccessible(true);
			java.util.List<String> said = ctrmap.Ui.record(junk);
			try {
				replace.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			junkSaid = said.toString();
			afterJunk = java.nio.file.Files.readAllBytes(shown.toPath());
			said = ctrmap.Ui.record(gr);
			try {
				replace.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			containerSaid = said.toString();
			afterContainer = java.nio.file.Files.readAllBytes(shown.toPath());
		} finally {
			ctrmap.Workspace.install(was);
			if (lastDir == null) {
				node.remove("LAST_DIR");
			} else {
				node.put("LAST_DIR", lastDir);
			}
		}
		check(junkSaid.contains("The container was not replaced: notes.txt is not a container")
				&& java.util.Arrays.equals(before, afterJunk),
				"a file that is no container is refused, naming it, and the container is kept: " + junkSaid);
		check(!containerSaid.contains("not replaced") && java.util.Arrays.equals(another, afterContainer),
				"and a container of its kind replaces it: " + containerSaid);
	}

	/**
	 * New container makes a blank one of a kind CTRMap can make - a region (GR) or a matrix (MM) - with the same
	 * number of subfiles, and refuses any other kind, saying so (the 2026-10-15 shared sweep, Builder 735, negate-if,
	 * SURVIVED: no suite pressed it). Inverted, a region was refused as a kind that cannot be made, and an area
	 * container was cast to a matrix and failed with a ClassCastException.
	 */
	static void aBlankContainerIsMadeOnlyOfAKindThatCanBe() throws Exception {
		System.out.println("--- a blank container is made only of a kind CTRMap can make");
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		String regionSaid;
		String areaSaid;
		ctrmap.formats.containers.AbstractGamefreakContainer blank;
		byte[] areaAfter;
		byte[] area = MapResizeTest.container(0x4144, new byte[]{1, 2}, new byte[]{3});
		try {
			ctrmap.WorkspaceSession ws = MapResizeTest.game("builder_blank");
			java.io.File region = new java.io.File(Scratch.dir("builder_blank_gr"), "region.gr");
			java.nio.file.Files.write(region.toPath(), pack(new byte[][]{{1, 2, 3}, {4}, {5, 6}}));
			java.io.File areaFile = new java.io.File(Scratch.dir("builder_blank_ad"), "area.ad");
			java.nio.file.Files.write(areaFile.toPath(), area);
			ctrmap.humaninterface.builder.Builder builder = new ctrmap.humaninterface.builder.Builder((title, tool) -> { });
			java.lang.reflect.Method blankBtn = builder.getClass().getDeclaredMethod("btnNewContainerActionPerformed",
					java.awt.event.ActionEvent.class);
			blankBtn.setAccessible(true);
			java.lang.reflect.Field held = builder.getClass().getDeclaredField("currentAGFC");
			held.setAccessible(true);
			builder.loadContainer(new ctrmap.formats.containers.GR(region, ws));
			java.util.List<String> said = ctrmap.Ui.record(javax.swing.JOptionPane.YES_OPTION);
			try {
				blankBtn.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			regionSaid = said.toString();
			blank = (ctrmap.formats.containers.AbstractGamefreakContainer) held.get(builder);
			builder.loadContainer(ctrmap.formats.containers.ContainerIdentifier.makeAGFC(areaFile, ws));
			said = ctrmap.Ui.record(javax.swing.JOptionPane.YES_OPTION);
			try {
				blankBtn.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			areaSaid = said.toString();
			areaAfter = java.nio.file.Files.readAllBytes(areaFile.toPath());
		} finally {
			ctrmap.Workspace.install(was);
		}
		boolean emptied = blank != null && blank.len == 3;
		StringBuilder held = new StringBuilder(blank == null ? "nothing" : blank.getClass().getSimpleName() + " of " + blank.len + ":");
		for (int i = 0; blank != null && i < blank.len; i++) {
			byte[] sub = blank.getFile(i);
			held.append(' ').append(sub == null ? "null" : sub.length + "b");
			//A BLANK SUBFILE IS ITS PADDING, all zero: 128 bytes each in a region, measured - not length 0
			for (int k = 0; sub != null && k < sub.length; k++) {
				emptied &= sub[k] == 0;
			}
		}
		check(emptied && !regionSaid.contains("not supported") && !regionSaid.contains("not made"),
				"a region is made blank, its 3 subfiles kept and every byte of them zero (" + held + "): " + regionSaid);
		check(areaSaid.contains("Creating this container type from scratch is not supported.")
				&& java.util.Arrays.equals(area, areaAfter),
				"and an area container is refused as a kind that cannot be made, left as it was: " + areaSaid);
	}

	/**
	 * Clear stops at a subfile that will not store, says which, and changes nothing - WHOLE OR NOT AT ALL (F209c; the
	 * 2026-10-15 shared sweep, Builder 766, drop-throw, SURVIVED: every container a suite cleared stored every
	 * subfile). Dropped, the failed subfile was passed over, the rest cleared and kept, and nothing was said. A region
	 * whose subfile 1 refuses to store.
	 */
	static void aClearThatCannotStoreOneSubfileChangesNothing() throws Exception {
		System.out.println("--- a clear that cannot store one subfile stops, says which, and changes nothing");
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		byte[] before = pack(new byte[][]{{1, 2, 3}, {4}, {5, 6}});
		String said;
		byte[] after;
		try {
			ctrmap.WorkspaceSession ws = MapResizeTest.game("builder_clear");
			java.io.File region = new java.io.File(Scratch.dir("builder_clear_gr"), "region.gr");
			java.nio.file.Files.write(region.toPath(), before);
			ctrmap.humaninterface.builder.Builder builder = new ctrmap.humaninterface.builder.Builder((title, tool) -> { });
			builder.loadContainer(new ctrmap.formats.containers.GR(region, ws) {
				@Override
				public void storeFile(int num, byte[] data) {
					if (num == 1) {
						throw new IllegalStateException("the disk refused subfile 1");
					}
					super.storeFile(num, data);
				}
			});
			java.lang.reflect.Method clear = builder.getClass().getDeclaredMethod("btnClearContainerActionPerformed",
					java.awt.event.ActionEvent.class);
			clear.setAccessible(true);
			java.util.List<String> heard = ctrmap.Ui.record(javax.swing.JOptionPane.YES_OPTION);
			try {
				clear.invoke(builder, (Object) null);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			said = heard.toString();
			after = java.nio.file.Files.readAllBytes(region.toPath());
		} finally {
			ctrmap.Workspace.install(was);
		}
		check(said.contains("The container was not cleared - it stopped at subfile 1") && said.contains("the disk refused subfile 1")
				&& java.util.Arrays.equals(before, after),
				"a clear that cannot store subfile 1 stops there, says so, and leaves the container as it was ("
				+ (java.util.Arrays.equals(before, after) ? "unchanged" : "CHANGED") + "): " + said);
	}

	/** Census M.16: the Builder shows the path of the container it opened - it set the label's hidden name instead. */
	static void theBuilderShowsTheContainerItOpened() throws Exception {
		System.out.println("--- the Builder shows the path of the container it opened (census M.16)");
		java.io.File f = new java.io.File(Scratch.dir("builder_label"), "region.gr");
		java.nio.file.Files.write(f.toPath(), pack(new byte[][]{{1, 2}}));
		ctrmap.humaninterface.builder.Builder builder = new ctrmap.humaninterface.builder.Builder((title, tool) -> { });
		//census M.80: Import and Import from file threw on the null container in a panel that opens empty
		String[] needs = {"btnFAImport", "btnFAExport", "btnFADummy", "btnImportContainer", "btnContSaveExternal", "btnClearContainer"};
		java.util.List<String> offeredEmpty = new java.util.ArrayList<>();
		for (String name : needs) {
			java.lang.reflect.Field b = builder.getClass().getDeclaredField(name);
			b.setAccessible(true);
			if (((javax.swing.JButton) b.get(builder)).isEnabled()) {
				offeredEmpty.add(name);
			}
		}
		check(offeredEmpty.isEmpty(), "with no container loaded, no button that acts on one is offered: " + offeredEmpty);
		//census M.136: Add new file did nothing, saying nothing, for every archive but two
		java.lang.reflect.Field addField = builder.getClass().getDeclaredField("btnAddFileToGARC");
		addField.setAccessible(true);
		javax.swing.JButton add = (javax.swing.JButton) addField.get(builder);
		boolean offeredWithNone = add.isEnabled();
		builder.loadGARC(ctrmap.gamedef.ArchiveType.AREA_DATA.ordinal());
		boolean offeredForArea = add.isEnabled();
		builder.loadGARC(ctrmap.gamedef.ArchiveType.FIELD_DATA.ordinal());
		boolean offeredForField = add.isEnabled();
		check(!offeredWithNone && !offeredForArea && offeredForField,
				"Add new file is offered only for an archive CTRMap can make an entry of (" + offeredWithNone + ", "
				+ offeredForArea + ", " + offeredForField + ")");
		builder.loadGARC(ctrmap.gamedef.ArchiveType.AREA_DATA.ordinal());
		java.lang.reflect.Method addBtn = builder.getClass().getDeclaredMethod("btnAddFileToGARCActionPerformed", java.awt.event.ActionEvent.class);
		addBtn.setAccessible(true);
		java.util.List<String> addSaid = ctrmap.Ui.record();
		try {
			addBtn.invoke(builder, (Object) null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(addSaid.size() == 1 && addSaid.get(0).contains("not of AREA_DATA"),
				"and pressed anyway for another archive it says so, rather than nothing: " + addSaid);
		//census M.137: the one storeFile caller in the Builder whose failed write reached nobody - the button looked
		//dead and a half-built container sat in the workspace. Its stores need a whole workspace, so the catch is read
		String builderSrc = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(
				new java.io.File("src/ctrmap/humaninterface/builder/Builder.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
		String addBody = CollisionReadTest.body(builderSrc, "private void btnAddFileToGARCActionPerformed(");
		int stores = addBody == null ? -1 : addBody.indexOf("gr.storeFile(0, tm.assembleTilemap());");
		int caught = addBody == null ? -1 : addBody.indexOf("} catch (RuntimeException notMade) {", stores);
		check(stores > 0 && caught > stores && addBody.indexOf("currentAGFC = before;", caught) > caught
				&& addBody.indexOf("ctrmap.Ui.error(this, \"The new \"", caught) > caught,
				"a new entry whose store fails is said, and the container shown goes back to the one before (" + stores + ", " + caught + ")");
		java.lang.reflect.Method importBtn = builder.getClass().getDeclaredMethod("btnFAImportActionPerformed", java.awt.event.ActionEvent.class);
		importBtn.setAccessible(true);
		Throwable pressed = null;
		try {
			importBtn.invoke(builder, (Object) null);
		} catch (java.lang.reflect.InvocationTargetException threw) {
			pressed = threw.getCause();
		}
		check(pressed == null, "and Import pressed anyway with nothing loaded throws nothing: " + pressed);
		//what Undo and Redo run in the browser: with nothing shown it reads the archive list again and nothing else
		//(the 2026-10-15 shared sweep, Builder 85, negate-if, SURVIVED: no suite took back a change in the browser)
		java.lang.reflect.Method readAgain = builder.getClass().getDeclaredMethod("readAgain");
		readAgain.setAccessible(true);
		Throwable emptyReread = null;
		try {
			readAgain.invoke(builder);
		} catch (java.lang.reflect.InvocationTargetException threw) {
			emptyReread = threw.getCause();
		}
		check(emptyReread == null, "an Undo in the browser with no container shown reads nothing it has not got: "
				+ emptyReread);
		try {
			builder.loadContainer(null);
			check(false, "loading no container is refused in words");
		} catch (IllegalArgumentException refused) {
			check(refused.getMessage().contains("no container"), "loading no container is refused in words: " + refused.getMessage());
		}
		builder.loadContainer(new ctrmap.formats.containers.GR(f, new FakeGameFiles()));
		java.lang.reflect.Field imp = builder.getClass().getDeclaredField("btnFAImport");
		imp.setAccessible(true);
		check(((javax.swing.JButton) imp.get(builder)).isEnabled(), "and once one is loaded, they are offered");
		java.lang.reflect.Field label = builder.getClass().getDeclaredField("container");
		label.setAccessible(true);
		String shown = ((javax.swing.JLabel) label.get(builder)).getText();
		check(f.getAbsolutePath().equals(shown), "the label shows the container's path, not <none>: " + shown);
		//and with one shown, an Undo reads it again from its file, a new container where the old one was
		java.lang.reflect.Field held = builder.getClass().getDeclaredField("currentAGFC");
		held.setAccessible(true);
		Object before = held.get(builder);
		String reread;
		//a game open, as there is whenever the browser is: the container is read again for the session it reports to
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		Sessions.bare(Scratch.dir("builder_reread_ws"), Scratch.dir("builder_reread_game"), ctrmap.gamedef.GameType.ORAS);
		try {
			readAgain.invoke(builder);
			reread = held.get(builder) != before && held.get(builder) != null ? "read again" : "kept as it was";
		} catch (java.lang.reflect.InvocationTargetException threw) {
			reread = "threw " + threw.getCause();
		} finally {
			ctrmap.Workspace.install(was);
		}
		check("read again".equals(reread), "an Undo in the browser with a container shown reads it again from its file: "
				+ reread);
	}

	/**
	 * Census H.40: a file chosen to replace a container is asked what it is BEFORE it is copied. The Builder copied
	 * it over the workspace's container first and identified it after, so a file that was no container - or a
	 * container of another kind - was kept and packed into the game.
	 */
	static void aReplacementIsAskedWhatItIsFirst(java.io.File src) throws Exception {
		System.out.println("--- a file chosen to replace a container is asked what it is before it is copied (census H.40)");
		java.io.File dir = Scratch.dir("container_replace");
		java.io.File gr = new java.io.File(dir, "region.gr");
		java.io.File otherGr = new java.io.File(dir, "other.gr");
		java.io.File zo = new java.io.File(dir, "zone.zo");
		java.io.File text = new java.io.File(dir, "notes.txt");
		java.io.File one = new java.io.File(dir, "one.bin");
		java.nio.file.Files.write(gr.toPath(), pack(new byte[][]{{1}}));
		java.nio.file.Files.write(otherGr.toPath(), pack(new byte[][]{{2, 3}}));
		byte[] zoBytes = pack(new byte[][]{{1}});
		zoBytes[0] = 'Z';
		zoBytes[1] = 'O';
		java.nio.file.Files.write(zo.toPath(), zoBytes);
		java.nio.file.Files.write(text.toPath(), "hello".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		java.nio.file.Files.write(one.toPath(), new byte[]{'G'});
		check(ctrmap.formats.containers.ContainerIdentifier.whyNotReplaceable(gr, otherGr) == null,
				"control: a container of the same kind may replace it");
		String kind = ctrmap.formats.containers.ContainerIdentifier.whyNotReplaceable(gr, zo);
		check(kind != null && kind.contains("ZO container") && kind.contains("is a GR"),
				"a container of another kind is refused, naming both: " + kind);
		String none = ctrmap.formats.containers.ContainerIdentifier.whyNotReplaceable(gr, text);
		String cut = ctrmap.formats.containers.ContainerIdentifier.whyNotReplaceable(gr, one);
		check(none != null && none.contains("not a container") && cut != null && cut.contains("not a container"),
				"and a file that is no container, or too short to say, is refused: " + none + " / " + cut);
		String builder = SourceSeamTest.stripComments(DialogSeamTest.read(new java.io.File(src,
				"ctrmap/humaninterface/builder/Builder.java")));
		String handler = CollisionReadTest.body(builder, "private void btnImportContainerActionPerformed(");
		int asked = handler == null ? -1 : handler.indexOf("ContainerIdentifier.whyNotReplaceable(");
		int copied = handler == null ? -1 : handler.indexOf("WorkspaceUnit.copy(");
		check(asked > 0 && copied > asked, "the Builder's Import from file asks before it copies (" + asked + ", " + copied + ")");
	}

	static void check(boolean ok, String what) {
		System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
		if (!ok) {
			fails++;
		}
	}
}
