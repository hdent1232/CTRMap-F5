package ctrmap.humaninterface;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.codepatch.ItemIconTable;
import ctrmap.formats.codepatch.ShopData;
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
import java.awt.Frame;
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
import javax.swing.JDialog;
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
			+ " here - the table is 776 long, and raising that is a code patch this editor does not"
			+ " attempt.";

	public static void show(Frame parent) {
		//Refuse before building anything: a headless suite proves the order.
		if (!Workspace.isValid()) {
			Ui.error(parent, "Load a workspace first.", "Item editor");
			return;
		}
		final ItemEditSession s;
		try {
			s = ItemEditSession.openWorkspace();
		} catch (Exception ex) {
			Ui.error(parent, "Could not read the item table:\n" + ex.getMessage(), "Item editor");
			return;
		}
		if (s == null) {
			Ui.error(parent, "CTRMap has no VERIFIED item table for this game yet.\n\n"
					+ "A location for it may be known from another tool, but nothing here has"
					+ " measured it against a dump of this game - and this editor writes 36 bytes"
					+ " straight into the archive, so a location that is only probably right is not"
					+ " good enough.", "Item editor");
			return;
		}

		final RecordSchema schema = SchemaRegistry.items();
		final DerivedLabels labels = new DerivedLabels(s.table.all(), s.names);
		final State st = new State();

		final JDialog dlg = new JDialog(parent, "Items", true);
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
		iconSpin.setEnabled(false);
		copyIcon.setEnabled(false);
		saveIps.setEnabled(false);

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
		final JButton undo = new JButton("Undo");
		final JButton redo = new JButton("Redo");
		JButton revert = new JButton("Revert to pre-edit copy");
		revert.setToolTipText("Puts this item's record back to the copy CTRMap took of the item"
				+ " archive before the first edit in this workspace.");
		JButton save = new JButton("Save item");
		JButton close = new JButton("Close");
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		for (JButton b : new JButton[]{undo, redo, revert, save, close}) {
			buttons.add(b);
		}
		JPanel south = new JPanel(new BorderLayout());
		south.add(dirtyLabel, BorderLayout.WEST);
		south.add(buttons, BorderLayout.EAST);
		dlg.add(south, BorderLayout.SOUTH);

		// ---- wiring: the widgets show the session, the session writes the game --
		final Runnable refresh = () -> {
			undo.setEnabled(form.canUndo());
			redo.setEnabled(form.canRedo());
			dirtyLabel.setText(st.dirty ? "  Unsaved changes to item " + st.id : " ");
		};

		final Runnable load = () -> {
			st.loading = true;
			try {
				form.setRecord(s.record(st.id));
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

		list.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting() || list.getSelectedValue() == null) {
				return;
			}
			int id = idOf(list.getSelectedValue());
			if (id < 0 || id == st.id) {
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

		undo.addActionListener(e -> {
			form.undo();
			refresh.run();
		});
		redo.addActionListener(e -> {
			form.redo();
			refresh.run();
		});

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
				JFileChooser fc = new JFileChooser(Workspace.GAMEDIR_PATH);
				fc.setDialogTitle("Pick the DECOMPRESSED code.bin");
				if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) {
					return;
				}
				st.loadCode(fc.getSelectedFile());
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
						+ ex.getMessage(), "Item icons");
			}
		});

		copyIcon.addActionListener(e -> {
			int other = ctrmap.humaninterface.pokepick.PokePickers.pickItem(dlg, st.id);
			if (other < 0 || st.icons == null || other >= st.icons.length) {
				return;
			}
			iconSpin.setValue(Math.min(st.icons[other], ItemIconTable.MAX_ICON));
		});

		revert.addActionListener(e -> {
			byte[] original = s.baselineRecord(st.id);
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
			try {
				EnumSet<ItemEditSession.Changed> changed
						= s.save(st.id, form.record(), nameField.getText(), descField.getText());
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
				info(dlg, "Item " + st.id + " saved into the game folder."
						+ "\nText changes are staged in the workspace; Pack Workspace writes them."
						+ "\nDeploy to emulator ships both." + iconNote, "Item editor");
			} catch (Exception ex) {
				Ui.error(dlg, "Save failed:\n" + ex.getMessage(), "Item editor");
			}
		});

		saveIps.addActionListener(e -> saveIconPatch(dlg, st));

		close.addActionListener(e -> {
			if (st.dirty && !confirmDiscard(dlg)) {
				return;
			}
			dlg.dispose();
		});

		st.id = 1;
		load.run();
		select(list, listModel, st.id);
		dlg.pack();
		dlg.setSize(Math.min(1140, Math.max(980, dlg.getWidth())),
				Math.min(780, Math.max(640, dlg.getHeight())));
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
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
	private static void saveIconPatch(JDialog dlg, State st) {
		try {
			if (st.code == null || st.icons == null) {
				Ui.error(dlg, "Load your decompressed code.bin first.", "Item icons");
				return;
			}
			if (Arrays.equals(st.icons, st.stockIcons)) {
				info(dlg, "No icon changes to save.", "Item icons");
				return;
			}
			byte[] ips = ItemIconTable.diffIPS(st.code, st.icons);
			JFileChooser fc = new JFileChooser(st.codeFile.getParentFile());
			fc.setDialogTitle("Save code.ips (Luma/Azahar executable patch)");
			fc.setSelectedFile(new File(st.codeFile.getParentFile(), "code.ips"));
			if (fc.showSaveDialog(dlg) != JFileChooser.APPROVE_OPTION) {
				return;
			}
			File out = fc.getSelectedFile();
			String note = "";
			if (out.exists()) {
				int rsl = Ui.confirm(dlg, "code.ips already exists (the zone-limit or the shop"
						+ " patch).\nMERGE the icon changes into it? (No = overwrite with icons"
						+ " only)", "Item icons", JOptionPane.YES_NO_CANCEL_OPTION);
				if (rsl != JOptionPane.YES_OPTION && rsl != JOptionPane.NO_OPTION) {
					return;
				}
				if (rsl == JOptionPane.YES_OPTION) {
					ips = ShopData.mergeIPS(Files.readAllBytes(out.toPath()), ips);
					note = " (merged with the patch already there)";
				}
			}
			Files.write(out.toPath(), ips);
			info(dlg, "Saved " + out.getName() + note + ".\n\n"
					+ "Install it like the zone patch:\n"
					+ "  Azahar: load/mods/<titleid>/exefs/code.ips\n"
					+ "  Luma3DS: sdmc:/luma/titles/<titleid>/code.ips (+ enable Game Patching)\n\n"
					+ "Then fully restart the emulator. Icons are the only part of the item editor"
					+ " that needs any of this.", "Item icons");
		} catch (Exception ex) {
			Ui.error(dlg, "Could not write the icon patch:\n" + ex.getMessage(), "Item icons");
		}
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

	/** The item id a list row names. */
	private static int idOf(String row) {
		return row == null ? -1
				: ctrmap.humaninterface.pokepick.PokePickers.parseLeadingInt(row.trim());
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
