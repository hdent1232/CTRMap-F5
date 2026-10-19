package ctrmap.humaninterface;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.codepatch.ItemIconTable;
import ctrmap.formats.pokedata.ItemData;
import ctrmap.formats.pokedata.ItemEditSession;
import ctrmap.formats.pokedata.ItemEffectLabels;
import ctrmap.formats.pokedata.ItemTable;
import ctrmap.formats.recordschema.RecordField;
import ctrmap.formats.recordschema.RecordSchema;
import ctrmap.formats.recordschema.SchemaRegistry;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

/**
 * The item editor: price, behaviour, name, description, icon.
 *
 * <p>WHAT IT HONESTLY DOES, said here because the dialog is required to say it
 * to the user too. There are exactly TWO tiers of change and no third:
 * <ol>
 * <li>the 36-byte record and the two text lines - archive data, reversible,
 *     ships with the normal deploy, no patch;</li>
 * <li>the icon, one word per item inside the executable - a code.ips patch,
 *     labelled as such everywhere it appears.</li>
 * </ol>
 *
 * <p>And the thing it CANNOT do, which is why the dialog says so out loud:
 * every held effect, fling effect and use routine offered here is one the engine
 * already implements. Pointing an item at one hands it that behaviour for the
 * cost of a byte. There is no spare id to hang a NEW behaviour on - the palette
 * of 183 is full, with no gaps - and some behaviour is not in the record at all:
 * Exp. Share is a literal item id at six code.bin sites and inside nine CROs,
 * and Ability Capsule has an all-zero record and works anyway. An editor that
 * let somebody believe otherwise would waste their evening.
 *
 * <p>Why this exists when pk3DS already edits item data: it is not a better
 * pk3DS. It is the item editor for somebody already building a world in CTRMap
 * - the same workspace, the same deploy, and item ids the shop editor, the
 * trainer editor and the script tools in this program already understand.
 */
public class ItemEditDialog {

	private static final String NEW_ITEM_HINT =
			"Four ids in the whole game can hold a new item: the records that are entirely empty and"
			+ " sit below every bound the engine checks. Four is a real limit, not a choice made"
			+ " here - the table is " + ctrmap.formats.codepatch.ItemIconTable.COUNT + " long, and raising that is a code"
			+ " patch this editor does not"
			+ " attempt.";

	/**
	 * Shows item {@code id}'s record in {@code form}, or - where the table refused it, past its end or not a whole
	 * record - says so and shows nothing to edit or save: SAID, NOT ZEROED (census M.134). Out of the editor so a suite
	 * reaches it (the 2026-10-14 shared sweep, ItemEditDialog 240, negate-if, SURVIVED).
	 */
	public static void showRecord(RecordEditPanel form, byte[] rec, int id) {
		if (rec != null) {
			form.setRecord(rec);
		} else {
			form.showUnreadable("Item " + id + "'s record could not be read - it is past the end of the item"
					+ " table, or not a whole record - so there is nothing here to edit or save.");
		}
	}

	/**
	 * The editor, as a panel for the Game Data tab.
	 *
	 * <p>It was a modal window. A feature lives in the part of the UI it belongs to,
	 * and the Game Data tab existed already, holding nothing but the button that
	 * opened this. The editing is untouched: the same table, the same saves, the same
	 * refusals - only the frame around it.
	 *
	 * @param onClose what to do when the user is finished with it; the host clears
	 * @return the editor, or null when there is nothing to edit (it says why first)
	 */
	public static javax.swing.JComponent panel(java.awt.Component parent, Runnable onClose, SaveHistory saves) {
		if (saves == null) {
			throw new IllegalArgumentException("the item editor must be handed the history its saves are kept in");
		}
		//Refuse before building anything: a headless suite proves the order.
		if (!Workspace.isValid()) {
			Ui.error(parent, "Load a workspace first.", "Item editor");
			return null;
		}
		final ItemEditSession s;
		try {
			s = ItemEditSession.open(Workspace.session());
		} catch (Exception ex) {
			Ui.error(parent, "Could not read the item table:\n" + ctrmap.util.Bytes.reason(ex), "Item editor");
			return null;
		}
		if (s == null) {
			Ui.error(parent, "CTRMap has no VERIFIED item table for this game yet.\n\n"
					+ "A location for it may be known from another tool, but nothing here has"
					+ " measured it against a dump of this game - and this editor writes 36 bytes"
					+ " straight into the archive, so a location that is only probably right is not"
					+ " good enough.", "Item editor");
			return null;
		}

		final RecordSchema schema = SchemaRegistry.items();
		final DerivedLabels labels = new DerivedLabels(s.table.all(), s.names);
		final State st = new State();

		//A PANEL, not a window: everything under this is unchanged.
		final JPanel dlg = new JPanel();
		dlg.setLayout(new BorderLayout(8, 8));

		// ---- left: the list -------------------------------------------------
		final DefaultListModel<String> listModel = new DefaultListModel<>();
		for (int i = 0; i < s.count(); i++) {
			listModel.addElement(rowText(i, s));
		}
		final JList<String> list = new JList<>(listModel);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
				list.getFont().getSize()));
		final JTextField filter = new JTextField();
		JPanel left = new JPanel(new BorderLayout(2, 2));
		JPanel filterRow = new JPanel(new BorderLayout(4, 0));
		filterRow.add(new JLabel("Find:"), BorderLayout.WEST);
		filterRow.add(filter, BorderLayout.CENTER);
		left.add(filterRow, BorderLayout.NORTH);
		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setPreferredSize(new Dimension(250, 500));
		left.add(listScroll, BorderLayout.CENTER);
		final JButton newItem = new JButton("New item (" + s.free.size() + " free)");
		newItem.setToolTipText(NEW_ITEM_HINT);
		left.add(newItem, BorderLayout.SOUTH);

		// ---- right: the form ------------------------------------------------
		final RecordEditPanel form = new RecordEditPanel(schema, labels);
		final JTextField nameField = new JTextField();
		final JTextField descField = new JTextField();
		final JSpinner iconSpin = new JSpinner(new SpinnerNumberModel(0, 0, ItemIconTable.MAX_ICON, 1));
		final JButton loadCode = new JButton("Load code.bin...");
		final JButton copyIcon = new JButton("Use another item's icon...");
		final JButton saveIps = new JButton("Save code.ips...");
		final JLabel iconState = new JLabel("no code.bin loaded");
		ctrmap.Ui.offer(iconSpin, "Load code.bin first - item icons are kept in the game's executable");
		ctrmap.Ui.offer(copyIcon, "Load code.bin first - item icons are kept in the game's executable");
		ctrmap.Ui.offer(saveIps, "Load code.bin first - item icons are kept in the game's executable");

		JPanel tierNote = new JPanel(new BorderLayout());
		tierNote.add(new JLabel("<html><b>Two kinds of change here, and no third.</b><br>"
				+ "The record and the text below are <b>data</b>: they save into your game folder,"
				+ " ship with Deploy, and need no patch.<br>"
				+ "The <b>icon</b> lives in the executable, so it is a <b>code.ips patch</b> - a"
				+ " different thing to install and a different thing to undo.<br><br>"
				+ "<i>Every effect offered below is one the game already implements.</i> Pointing an"
				+ " item at one hands it that behaviour. Nothing here can author a NEW behaviour:"
				+ " the effect palette is full at 183 with no gaps, and some behaviour (Exp. Share,"
				+ " Ability Capsule) is keyed on the item id in code and is not in this record at"
				+ " all.</html>"), BorderLayout.CENTER);
		tierNote.setBorder(BorderFactory.createEmptyBorder(4, 6, 10, 6));

		JPanel textPanel = titled("Name and description - data, saves with the workspace");
		textPanel.setLayout(new java.awt.GridBagLayout());
		java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
		gc.insets = new java.awt.Insets(2, 4, 2, 4);
		gc.anchor = java.awt.GridBagConstraints.WEST;
		gc.gridx = 0;
		gc.gridy = 0;
		textPanel.add(new JLabel("Name:"), gc);
		gc.gridx = 1;
		gc.weightx = 1;
		gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
		textPanel.add(nameField, gc);
		gc.gridx = 0;
		gc.gridy = 1;
		gc.weightx = 0;
		gc.fill = java.awt.GridBagConstraints.NONE;
		textPanel.add(new JLabel("Description:"), gc);
		gc.gridx = 1;
		gc.weightx = 1;
		gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
		textPanel.add(descField, gc);

		JPanel iconPanel = titled("Icon - NEEDS THE CODE PATCH (this part is not data)");
		iconPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		iconPanel.add(loadCode);
		iconPanel.add(new JLabel("Icon index:"));
		iconPanel.add(iconSpin);
		iconPanel.add(copyIcon);
		iconPanel.add(saveIps);
		iconState.setForeground(Color.DARK_GRAY);
		iconPanel.add(iconState);

		JPanel right = new JPanel();
		right.setLayout(new javax.swing.BoxLayout(right, javax.swing.BoxLayout.Y_AXIS));
		for (JComponent c : new JComponent[]{tierNote, textPanel, iconPanel, form}) {
			c.setAlignmentX(0f);
			right.add(c);
		}
		JScrollPane rightScroll = new JScrollPane(right);
		rightScroll.getVerticalScrollBar().setUnitIncrement(16);
		rightScroll.setPreferredSize(new Dimension(640, 540));

		dlg.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightScroll), BorderLayout.CENTER);

		// ---- bottom ---------------------------------------------------------
		final JLabel dirtyLabel = new JLabel(" ");
		dirtyLabel.setForeground(new Color(0x99, 0x55, 0x00));
		JButton revert = new JButton("Revert to pre-edit copy");
		revert.setToolTipText("Puts this item's record back to the copy CTRMap took of the item"
				+ " archive before the first edit in this workspace.");
		JButton save = new JButton("Save item");
		JButton close = new JButton("Close");
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		for (JButton b : new JButton[]{revert, save, close}) {
			buttons.add(b);
		}
		JPanel south = new JPanel(new BorderLayout());
		south.add(dirtyLabel, BorderLayout.WEST);
		south.add(buttons, BorderLayout.EAST);
		dlg.add(south, BorderLayout.SOUTH);

		// ---- wiring: the widgets show the session, the session writes the game --
		final Runnable refresh = () -> {
			dirtyLabel.setText(st.dirty ? "  Unsaved changes to item " + st.id : " ");
		};

		final Runnable load = () -> {
			st.loading = true;
			st.shown = new Object();   //another load: another document, not an edit of the last
			try {
				showRecord(form, s.record(st.id), st.id);
				nameField.setText(s.name(st.id));
				descField.setText(s.description(st.id));
				if (st.icons != null && st.id < st.icons.length) {
					iconSpin.setValue(Math.min(st.icons[st.id], ItemIconTable.MAX_ICON));
				}
				st.dirty = false;
			} finally {
				st.loading = false;
			}
			refresh.run();
		};

		form.setListener(rec -> {
			if (!st.loading) {
				st.dirty = true;
			}
			refresh.run();
		});
		Touch touch = new Touch(st, refresh);
		nameField.getDocument().addDocumentListener(touch);
		descField.getDocument().addDocumentListener(touch);
		iconSpin.addChangeListener(e -> {
			if (!st.loading && st.icons != null && st.id < st.icons.length) {
				st.icons[st.id] = ((Number) iconSpin.getValue()).intValue();
				iconState.setText("icon changed - not saved until you write the code.ips");
			}
		});

		ctrmap.util.Selections.onSettled(list, () -> {
			int id = switchTarget(list.getSelectedValue(), st.id);
			if (id < 0) {
				return;
			}
			if (st.dirty && !confirmDiscard(dlg)) {
				select(list, listModel, st.id);
				return;
			}
			st.id = id;
			load.run();
		});

		filter.getDocument().addDocumentListener(new SimpleDoc(() -> {
			String q = filter.getText().trim().toLowerCase();
			listModel.clear();
			for (int i = 0; i < s.count(); i++) {
				String row = rowText(i, s);
				if (q.isEmpty() || row.toLowerCase().contains(q)) {
					listModel.addElement(row);
				}
			}
		}));


		newItem.addActionListener(e -> {
			if (s.free.isEmpty()) {
				Ui.error(dlg, "There is no empty item slot left in this game.\n\n" + NEW_ITEM_HINT,
						"New item");
				return;
			}
			String[] opts = new String[s.free.size()];
			for (int i = 0; i < s.free.size(); i++) {
				opts[i] = "id " + s.free.get(i);
			}
			Object pick = Ui.input(dlg, NEW_ITEM_HINT + "\n\nWhich slot?", "New item",
					JOptionPane.PLAIN_MESSAGE, opts, opts[0]);
			if (pick == null) {
				return;
			}
			if (st.dirty && !confirmDiscard(dlg)) {
				return;
			}
			st.id = s.free.get(Arrays.asList(opts).indexOf(pick));
			load.run();
			select(list, listModel, st.id);
			nameField.requestFocusInWindow();
			nameField.selectAll();
			info(dlg, "Slot " + st.id + " is yours.\n\nGive it a name and a description first - a"
					+ " new item still called \"???\" is one nobody can use - then set its record"
					+ " and press Save item.\n\nIts icon stays blank until you set one, and that"
					+ " part needs the code patch.", "New item");
		});

		loadCode.addActionListener(e -> {
			try {
				//the same file the shop editor and the zone-limit patch use, remembered with them
				File picked = CodeBinFile.choose(dlg, new File(Workspace.GAMEDIR_PATH));
				if (picked == null) {
					return;
				}
				st.loadCode(picked);
				CodeBinFile.remember(picked);
				iconSpin.setEnabled(true);
				copyIcon.setEnabled(true);
				saveIps.setEnabled(true);
				iconState.setText(st.codeFile.getName() + " - " + st.icons.length + " icons");
				st.loading = true;
				iconSpin.setValue(Math.min(st.icons[st.id], ItemIconTable.MAX_ICON));
				st.loading = false;
			} catch (Exception ex) {
				iconState.setText("no code.bin loaded");
				Ui.error(dlg, "That file is not the executable this editor knows how to patch:\n\n"
						+ ctrmap.util.Bytes.reason(ex), "Item icons");
			}
		});

		copyIcon.addActionListener(e -> {
			int other = ctrmap.humaninterface.pokepick.PokePickers.pickItem(dlg, st.id, Workspace.session());
			if (other < 0 || st.icons == null || other >= st.icons.length) {
				return;
			}
			iconSpin.setValue(Math.min(st.icons[other], ItemIconTable.MAX_ICON));
		});

		revert.addActionListener(e -> {
			byte[] original;
			try {
				original = s.baselineRecord(st.id);
			} catch (IllegalStateException unreadable) {
				//a copy that is there and does not read is not "no copy" (census M.112): it is not retaken while it exists
				Ui.error(dlg, Ui.reason(unreadable) + "\n\nIt is not taken again while it is there: put back a good one,"
						+ " or remove it and the next save takes a fresh one.", "Revert");
				return;
			}
			if (original == null) {
				Ui.error(dlg, "This workspace holds no pre-edit copy of the item archive, so there"
						+ " is nothing to revert to.\n\nThe copy is taken the first time you save"
						+ " an item.", "Revert");
				return;
			}
			form.setRecord(original);
			st.dirty = true;
			refresh.run();
		});

		save.addActionListener(e -> {
			//ONE UNIT (F082): the record and its text are staged together, and a failure part way
			//puts both back - then the lists are read again from what was put back
			try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Save item " + st.id, Workspace.session())) {
				//put back with the files - by a failure, or by Undo - and put again by Redo: the lists re-read
				Runnable reread = rereadOf(s);
				ctrmap.WorkspaceUnit.inMemory(reread, reread);
				if (form.record() == null) {
					throw new IllegalStateException("item " + st.id + "'s record could not be read, so there is nothing to save");
				}
				EnumSet<ItemEditSession.Changed> changed
						= s.save(st.id, form.record(), nameField.getText(), descField.getText());
				saves.keep(unit.commitKeeping());
				if (changed.contains(ItemEditSession.Changed.NAME)) {
					int row = rowFor(listModel, st.id);
					if (row >= 0) {
						listModel.set(row, rowText(st.id, s));
					}
				}
				st.dirty = false;
				refresh.run();
				String iconNote = st.iconsChanged()
						? "\n\nThe ICON is NOT saved by this button - it lives in the executable."
						+ " Use \"Save code.ips...\" for it." : "";
				info(dlg, "Item " + st.id + " is staged in the workspace, with its name and description."
						+ "\nPack Workspace writes them into the game; Deploy to emulator ships them."
						+ iconNote, "Item editor");
			} catch (Exception ex) {
				Ui.error(dlg, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Item editor");
			}
		});

		saveIps.addActionListener(e -> saveIconPatch(dlg, st, saves));

		close.addActionListener(e -> {
			if (st.dirty && !confirmDiscard(dlg)) {
				return;
			}
			onClose.run();
		});

		st.id = 1;
		load.run();
		select(list, listModel, st.id);
		dlg.setPreferredSize(new java.awt.Dimension(1040, 700));
		//THE ITEM SHOWN IS A DOCUMENT (F209c): its record, name, description and icons, watched by the history of
		//the tab this editor is shown in - one Undo for all of it, unsaved edits and saves alike
		return SaveHistory.watching(dlg, () -> java.util.Collections.<UndoHistory.Document<?>>singletonList(
				new UndoHistory.Document<Object[]>() {
			private final Object loaded = st.shown;

			@Override
			public Object subject() {
				return loaded;
			}

			@Override
			public Object[] take() {
				return new Object[]{st.id, form.record(), nameField.getText(), descField.getText(),
					st.icons == null ? null : st.icons.clone()};
			}

			@Override
			public void put(Object[] state) {
				int id = (Integer) state[0];
				if (id != st.id) {
					st.id = id;
					load.run();
					select(list, listModel, id);
				}
				st.loading = true;
				try {
					if (state[1] != null) {
						form.setRecord((byte[]) state[1]);
					}
					nameField.setText((String) state[2]);
					descField.setText((String) state[3]);
					st.icons = state[4] == null ? null : ((int[]) state[4]).clone();
					if (st.icons != null && st.id < st.icons.length) {
						iconSpin.setValue(Math.min(st.icons[st.id], ItemIconTable.MAX_ICON));
					}
					st.dirty = true;
				} finally {
					st.loading = false;
				}
				refresh.run();
			}

			@Override
			public boolean same(Object[] a, Object[] b) {
				return a[0].equals(b[0]) && Arrays.equals((byte[]) a[1], (byte[]) b[1]) && a[2].equals(b[2])
						&& a[3].equals(b[3]) && Arrays.equals((int[]) a[4], (int[]) b[4]);
			}

			@Override
			public String what() {
				return "item " + st.id;
			}
		}));
	}

	/**
	 * Everything the listeners share that is NOT the game data: which item is
	 * up, whether it is dirty, and the icon table the user loaded (the one
	 * part of this editor that is a code patch rather than data).
	 */
	private static final class State {

		int id = 1;
		boolean dirty;
		boolean loading;
		/** One per item loaded: what the item's document is keyed by, so a load is no edit. */
		Object shown = new Object();
		/** The icon table as the user's code.bin has it, or null until one is loaded. */
		int[] stockIcons;
		/** The same table with their changes. */
		int[] icons;
		File codeFile;
		byte[] code;

		/**
		 * Reads a decompressed code.bin and its icon table. Throws, with a
		 * sentence saying WHY, when this is not the build the offsets were
		 * measured in, rather than patching it blind - and then nothing is
		 * loaded, so no later click can patch a file that was refused.
		 */
		void loadCode(File f) throws IOException {
			try {
				codeFile = f;
				code = Files.readAllBytes(f.toPath());
				stockIcons = ItemIconTable.read(code);
				icons = stockIcons.clone();
			} catch (IOException | RuntimeException ex) {
				code = null;
				icons = null;
				stockIcons = null;
				throw ex;
			}
		}

		/** The user changed an icon since loading the table. */
		boolean iconsChanged() {
			return icons != null && stockIcons != null && !Arrays.equals(icons, stockIcons);
		}
	}

	/**
	 * Writes the changed icon table out as an IPS - merged with any code.ips
	 * already there, so one file still carries the zone-limit and shop patches.
	 */
	private static void saveIconPatch(java.awt.Component dlg, State st, SaveHistory saves) {
		if (st.code == null || st.icons == null) {
			Ui.error(dlg, "Load your decompressed code.bin first.", "Item icons");
			return;
		}
		saveIconPatch(dlg, st.codeFile, st.code, st.icons, saves);
	}

	/**
	 * The Save button's work once a code.bin is loaded: the code.ips the user picks beside
	 * {@code codeFile} gets these icons. Public so a suite can press it headless - the chooser is
	 * answered through the Ui seam (F152).
	 */
	public static void saveIconPatch(java.awt.Component dlg, File codeFile, byte[] code, int[] icons, SaveHistory saves) {
		try {
			//NO "nothing to save" (F152): every icon put back to the game's own is still a change to a
			//code.ips that carries an old edit, and saving it takes the icon table out of that file
			byte[] ips = ItemIconTable.diffIPS(code, icons);
			JFileChooser fc = new JFileChooser(codeFile.getParentFile());
			fc.setDialogTitle("Save code.ips (Luma/Azahar executable patch)");
			fc.setSelectedFile(new File(codeFile.getParentFile(), "code.ips"));
			if (ctrmap.Ui.choose(dlg, fc, true) == null) {
				return;
			}
			File out = fc.getSelectedFile();
			//ONE WRITER FOR THE ONE code.ips (F152): the patches already in it are kept and only the
			//table this editor owns is replaced - there is no question to close by mistake
			String note;
			//ONE KEPT CHANGE (F209c): the code.ips is copied before it changes, and the save can be taken back
			try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Save the item icons", Workspace.session())) {
				note = ctrmap.formats.codepatch.CodeIps.save(out, ips, ItemIconTable.owned(), ctrmap.WorkspaceUnit::touching);
				saves.keep(unit.commitKeeping());
			}
			info(dlg, "Saved " + out.getName() + note + ".\n\n"
					+ "Install it like the zone patch:\n"
					+ "  Azahar: load/mods/<titleid>/exefs/code.ips\n"
					+ "  Luma3DS: sdmc:/luma/titles/<titleid>/code.ips (+ enable Game Patching)\n\n"
					+ "Then fully restart the emulator. Icons are the only part of the item editor"
					+ " that needs any of this.", "Item icons");
		} catch (Exception ex) {
			Ui.error(dlg, "Could not write the icon patch:\n" + ctrmap.util.Bytes.reason(ex), "Item icons");
		}
	}

	/**
	 * What reads the editor's lists again when a save is put back - by a failure, or by Undo - and put again by Redo. A
	 * re-read that fails throws, saying so, rather than leave lists that no longer match the files. Out of the save so a
	 * suite reaches it (the 2026-10-15 shared sweep, ItemEditDialog 393, drop-throw, SURVIVED: no suite failed one).
	 */
	public static Runnable rereadOf(ItemEditSession s) {
		return () -> {
			try {
				s.reread();
			} catch (java.io.IOException rereadFailed) {
				throw new IllegalStateException("the item editor could not re-read the items after a save was"
						+ " put back; close it and open it again: " + ctrmap.util.Bytes.reason(rereadFailed),
						rereadFailed);
			}
		};
	}

	// ---- odds and ends -----------------------------------------------------

	private static JPanel titled(String title) {
		JPanel p = new JPanel();
		p.setBorder(BorderFactory.createTitledBorder(title));
		return p;
	}

	private static void info(java.awt.Component parent, String text, String title) {
		Ui.message(parent, text, title, JOptionPane.INFORMATION_MESSAGE);
	}

	private static String rowText(int id, ItemEditSession s) {
		String n = s.name(id);
		if (ItemTable.isPlaceholderName(n)) {
			n = s.isFree(id) ? "(free slot)" : "(unused)";
		}
		return pad(id) + "  " + n;
	}

	private static String pad(int id) {
		StringBuilder s = new StringBuilder(String.valueOf(id));
		while (s.length() < 3) {
			s.insert(0, " ");
		}
		return s.toString();
	}

	/**
	 * The item a list row selected switches the editor to, or -1 for none: nothing selected, a row naming no item, or
	 * the item already shown. Out of the editor so a suite reaches it (the 2026-10-14 shared sweep, ItemEditDialog 276,
	 * negate-if, SURVIVED: its own null check repeated idOf's, and no suite selected a row).
	 */
	public static int switchTarget(String selected, int shown) {
		int id = idOf(selected);
		return id < 0 || id == shown ? -1 : id;
	}

	/** The item id a list row names. */
	private static int idOf(String row) {
		return row == null ? -1
				: ctrmap.util.Text.parseLeadingInt(row.trim());
	}

	private static int rowFor(DefaultListModel<String> model, int id) {
		for (int i = 0; i < model.size(); i++) {
			if (idOf(model.get(i)) == id) {
				return i;
			}
		}
		return -1;
	}

	private static void select(JList<String> list, DefaultListModel<String> model, int id) {
		int row = rowFor(model, id);
		if (row >= 0) {
			list.setSelectedIndex(row);
			list.ensureIndexIsVisible(row);
		}
	}

	private static boolean confirmDiscard(java.awt.Component c) {
		return Ui.confirm(c, "Discard unsaved changes to this item?", "Item editor",
				JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
	}

	/** Marks the dialog dirty when a text field is typed into. */
	private static final class Touch implements javax.swing.event.DocumentListener {

		private final State st;
		private final Runnable refresh;

		Touch(State st, Runnable refresh) {
			this.st = st;
			this.refresh = refresh;
		}

		@Override
		public void insertUpdate(javax.swing.event.DocumentEvent e) {
			mark();
		}

		@Override
		public void removeUpdate(javax.swing.event.DocumentEvent e) {
			mark();
		}

		@Override
		public void changedUpdate(javax.swing.event.DocumentEvent e) {
			mark();
		}

		private void mark() {
			if (!st.loading) {
				st.dirty = true;
				refresh.run();
			}
		}
	}

	/** Runs one action for any document change. */
	private static final class SimpleDoc implements javax.swing.event.DocumentListener {

		private final Runnable r;

		SimpleDoc(Runnable r) {
			this.r = r;
		}

		@Override
		public void insertUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}

		@Override
		public void removeUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}

		@Override
		public void changedUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}
	}

	/**
	 * Dropdown text for the numbered fields, derived from the table itself.
	 *
	 * <p>The game ships no name list for the 183 held effects or the use
	 * routines, so a control reading "hold effect: 77" asks the user to know
	 * something nobody wrote down. Naming the retail items that carry an id is
	 * free, cannot go stale, and says what the id does in the only vocabulary
	 * that matters. An id no item carries is offered WITH the admission that
	 * nobody knows what it does - more use than hiding it, and more honest than
	 * giving it a made-up name.
	 */
	private static final class DerivedLabels implements RecordEditPanel.Labels {

		private final Map<ItemEffectLabels.Kind, ItemEffectLabels> byKind
				= new EnumMap<>(ItemEffectLabels.Kind.class);

		DerivedLabels(List<ItemData> records, List<String> names) {
			for (ItemEffectLabels.Kind k : ItemEffectLabels.Kind.values()) {
				byKind.put(k, ItemEffectLabels.build(records, names, k));
			}
		}

		@Override
		public String label(RecordField f, int value) {
			if (value == 0) {
				return "0 - none";
			}
			ItemEffectLabels l = byKind.get(f.effectKind());
			return l == null ? String.valueOf(value) : l.label(value);
		}

		@Override
		public List<Integer> choices(RecordField f) {
			List<Integer> out = new ArrayList<>();
			for (int i = Math.max(0, f.min()); i <= Math.min(f.max(), 255); i++) {
				out.add(i);
			}
			return out;
		}
	}
}
