package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.codepatch.ShopData;
import ctrmap.formats.pokedata.PokeData;
import ctrmap.humaninterface.pokepick.PokePickers;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Files;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Shop inventory editor - changes what the Poke Marts and specialty shops
 * sell. The lists live in the EXECUTABLE (code.bin .rodata), so this editor
 * reads the user's decompressed code.bin, edits in memory, and saves a
 * code.ips diff for Luma/Azahar - merged with any existing code.ips (e.g. the
 * zone-limit patch) so one file carries every executable patch.
 */
public class ShopEditDialog {

	private static final String PREF_CODEBIN = "SHOP_CODEBIN_PATH";

	public static void show(Frame parent) {
		if (!Workspace.valid || !Workspace.isOA()) {
			JOptionPane.showMessageDialog(parent, "Load an ORAS workspace first.", "Shop editor", JOptionPane.ERROR_MESSAGE);
			return;
		}
		Preferences prefs = Preferences.userRoot().node(ShopEditDialog.class.getName());

		// locate the decompressed code.bin (remembered across sessions)
		File codeFile = null;
		String remembered = prefs.get(PREF_CODEBIN, "");
		if (!remembered.isEmpty() && new File(remembered).exists()) {
			codeFile = new File(remembered);
		}
		if (codeFile == null) {
			JOptionPane.showMessageDialog(parent,
					"Pick your DECOMPRESSED code.bin (the executable - shop lists live inside it,\n"
					+ "not in the RomFS). It's the same file the zone-limit patch uses.",
					"Shop editor", JOptionPane.INFORMATION_MESSAGE);
			JFileChooser fc = new JFileChooser(Workspace.GAMEDIR_PATH);
			fc.setDialogTitle("Pick the decompressed code.bin");
			if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
				return;
			}
			codeFile = fc.getSelectedFile();
		}
		final byte[] code;
		final int[][] shops;
		try {
			code = Files.readAllBytes(codeFile.toPath());
			shops = ShopData.read(code);
		} catch (Exception ex) {
			prefs.remove(PREF_CODEBIN);
			JOptionPane.showMessageDialog(parent, "Could not read the shop table:\n" + ex.getMessage()
					+ "\n\n(The file must be the DECOMPRESSED ORAS code.bin - pick it again next time.)",
					"Shop editor", JOptionPane.ERROR_MESSAGE);
			return;
		}
		prefs.put(PREF_CODEBIN, codeFile.getAbsolutePath());
		final File fCodeFile = codeFile;

		final JDialog dlg = new JDialog(parent, "Shop inventories (code.bin)", true);
		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JComboBox<String> shopBox = new JComboBox<>(ShopData.NAMES);
		final ShopModel model = new ShopModel(shops);
		final JTable table = new JTable(model);
		table.setRowHeight(22);
		table.getColumnModel().getColumn(0).setMaxWidth(50);
		PokePickers.installDoubleClickPickers(table, col -> col == 1 ? PokePickers.Kind.ITEM : null);
		shopBox.addActionListener(e -> model.setShop(shopBox.getSelectedIndex()));

		JPanel north = new JPanel(new BorderLayout(4, 4));
		north.add(shopBox, BorderLayout.CENTER);
		north.add(new JLabel("<html><small>Double-click an item to pick a replacement (visual picker). "
				+ "List lengths are fixed by the engine - you change WHICH items, not how many.</small></html>"),
				BorderLayout.SOUTH);
		dlg.add(north, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(table);
		sp.setPreferredSize(new Dimension(460, 420));
		dlg.add(sp, BorderLayout.CENTER);

		JPanel buttons = new JPanel();
		JButton save = new JButton("Save code.ips...");
		JButton close = new JButton("Close");
		buttons.add(save);
		buttons.add(close);
		dlg.add(buttons, BorderLayout.SOUTH);

		save.addActionListener(e -> {
			try {
				byte[] patched = ShopData.write(code, model.shops);
				if (java.util.Arrays.equals(patched, code)) {
					JOptionPane.showMessageDialog(dlg, "No changes to save.", "Shop editor", JOptionPane.INFORMATION_MESSAGE);
					return;
				}
				byte[] ips = ShopData.diffIPS(code, patched);
				JFileChooser fc = new JFileChooser(fCodeFile.getParentFile());
				fc.setDialogTitle("Save code.ips (Luma/Azahar executable patch)");
				fc.setSelectedFile(new File(fCodeFile.getParentFile(), "code.ips"));
				if (fc.showSaveDialog(dlg) != JFileChooser.APPROVE_OPTION) {
					return;
				}
				File out = fc.getSelectedFile();
				String note = "";
				if (out.exists()) {
					int rsl = JOptionPane.showConfirmDialog(dlg,
							"code.ips already exists (e.g. the zone-limit patch).\n"
							+ "MERGE the shop changes into it? (No = overwrite with shops only)",
							"Shop editor", JOptionPane.YES_NO_CANCEL_OPTION);
					if (rsl == JOptionPane.CANCEL_OPTION) {
						return;
					}
					if (rsl == JOptionPane.YES_OPTION) {
						ips = ShopData.mergeIPS(Files.readAllBytes(out.toPath()), ips);
						note = " (merged with the existing patch)";
					}
				}
				Files.write(out.toPath(), ips);
				JOptionPane.showMessageDialog(dlg,
						"Saved " + out.getName() + note + ".\n\n"
						+ "Deploy it like the zone patch:\n"
						+ "  Azahar: load/mods/<titleid>/exefs/code.ips\n"
						+ "  Luma3DS: sdmc:/luma/titles/<titleid>/code.ips (+ enable Game Patching)\n\n"
						+ "Then fully restart the emulator and talk to a shop clerk (TESTING.md item).",
						"Shop editor", JOptionPane.INFORMATION_MESSAGE);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dlg, "Save failed:\n" + ex.getMessage(), "Shop editor", JOptionPane.ERROR_MESSAGE);
			}
		});
		close.addActionListener(e -> dlg.dispose());

		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	/** Table model over one shop's item list (ids shown with names). */
	private static class ShopModel extends AbstractTableModel {

		final int[][] shops;
		int shop = 0;

		ShopModel(int[][] shops) {
			this.shops = shops;
		}

		void setShop(int s) {
			shop = s;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return shops[shop].length;
		}

		@Override
		public int getColumnCount() {
			return 2;
		}

		@Override
		public String getColumnName(int c) {
			return c == 0 ? "Slot" : "Item (double-click to change)";
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return false; // the double-click picker edits column 1
		}

		@Override
		public Object getValueAt(int r, int c) {
			if (c == 0) {
				return r + 1;
			}
			int id = shops[shop][r];
			String name = PokeData.itemName(id);
			return id + "  " + (name != null ? name : "?");
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			if (c != 1) {
				return;
			}
			int id = v instanceof Number ? ((Number) v).intValue() : PokePickers.parseLeadingInt(String.valueOf(v));
			if (id >= 0) {
				shops[shop][r] = id;
				fireTableRowsUpdated(r, r);
			}
		}
	}
}
