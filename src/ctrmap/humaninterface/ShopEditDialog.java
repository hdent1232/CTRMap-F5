package ctrmap.humaninterface;

import ctrmap.Workspace;
import ctrmap.formats.codepatch.ShopData;
import ctrmap.formats.codepatch.Ips;
import ctrmap.formats.pokedata.PokeData;
import ctrmap.humaninterface.pokepick.PokePickers;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.nio.file.Files;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
	public static javax.swing.JComponent panel(java.awt.Component parent, Runnable onClose) {
		//CODE_PATCHES, not "is it ORAS": the shop lists are found by absolute
		//addresses in a particular game's executable, so what this needs is a
		//game whose code.bin has been reverse engineered - which is a different
		//question from which game it is, and one the profile can answer for a
		//game nobody has RE'd yet.
		ctrmap.gamedef.GameProfile prof = Workspace.isValid() ? Workspace.profile() : null;
		if (prof == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Shop editor");
			return null;
		}
		if (!prof.supports(ctrmap.gamedef.GameProfile.Feature.CODE_PATCHES)) {
			ctrmap.Ui.error(parent, "Editing shops is not available for " + prof.displayName() + "."
					+ "\n\nShop inventories live in the executable, not the RomFS, and CTRMap"
					+ " finds them by addresses read out of a disassembled code.bin. Those"
					+ " addresses were found in the Omega Ruby / Alpha Sapphire executable."
					+ "\n\nCTRMap refuses here rather than patching bytes at an address that"
					+ " means something else in this game's code.",
					"Shop editor");
			return null;
		}
		// the decompressed code.bin, remembered across sessions and shared with the other
		// editors that patch the executable
		File codeFile = CodeBinFile.pick(parent, "Shop lists live inside it, not in the RomFS.",
				new File(ctrmap.Workspace.GAMEDIR_PATH));
		if (codeFile == null) {
			return null;
		}
		final byte[] code;
		final int[][] shops;
		try {
			code = Files.readAllBytes(codeFile.toPath());
			shops = ShopData.read(code);
		} catch (Exception ex) {
			CodeBinFile.forget();
			ctrmap.Ui.error(parent, "Could not read the shop table:\n" + ctrmap.util.Bytes.reason(ex)
					+ "\n\n(The file must be the DECOMPRESSED ORAS code.bin - pick it again next time.)",
					"Shop editor");
			return null;
		}
		CodeBinFile.remember(codeFile);
		final File fCodeFile = codeFile;

		//A PANEL, not a window: everything under this is unchanged.
		final JPanel dlg = new JPanel();
		dlg.setLayout(new BorderLayout(8, 8));
		dlg.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JComboBox<String> shopBox = new JComboBox<>(ShopData.NAMES);
		final ShopModel model = new ShopModel(shops);
		final JTable table = new JTable(model);
		table.setRowHeight(22);
		table.getColumnModel().getColumn(0).setMaxWidth(50);
		PokePickers.installDoubleClickPickers(table, col -> col == 1 ? PokePickers.Kind.ITEM : null, Workspace.session());
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

		save.addActionListener(e -> savePatch(dlg, fCodeFile, code, model.shops));
		close.addActionListener(e -> onClose.run());

		//no pack: a pane is sized by the tab it is in
		return dlg;
	}

	/**
	 * The Save button's work: the code.ips the user picks beside {@code codeFile} gets these shop
	 * lists. Public so a suite can press it headless - the chooser is answered through the Ui seam
	 * (F152).
	 */
	public static void savePatch(java.awt.Component dlg, File codeFile, byte[] code, int[][] shops) {
		try {
			byte[] patched = ShopData.write(code, shops);
			//NO "nothing to save" (F152): every list put back to the game's own is still a change to
			//a code.ips that carries an old edit, and saving it takes the shop table out of that file
			byte[] ips = Ips.diff(code, patched);
			JFileChooser fc = new JFileChooser(codeFile.getParentFile());
			fc.setDialogTitle("Save code.ips (Luma/Azahar executable patch)");
			fc.setSelectedFile(new File(codeFile.getParentFile(), "code.ips"));
			if (ctrmap.Ui.choose(dlg, fc, true) == null) {
				return;
			}
			File out = fc.getSelectedFile();
			//ONE WRITER FOR THE ONE code.ips (F152): the patches already in it are kept and only the
			//table this editor owns is replaced - there is no question to close by mistake
			String note = ctrmap.formats.codepatch.CodeIps.save(out, ips, ShopData.owned());
			ctrmap.Ui.message(dlg,
					"Saved " + out.getName() + note + ".\n\n"
					+ "Deploy it like the zone patch:\n"
					+ "  Azahar: load/mods/<titleid>/exefs/code.ips\n"
					+ "  Luma3DS: sdmc:/luma/titles/<titleid>/code.ips (+ enable Game Patching)\n\n"
					+ "Then fully restart the emulator and talk to a shop clerk (TESTING.md item).",
					"Shop editor", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			ctrmap.Ui.error(dlg, "Save failed:\n" + ctrmap.util.Bytes.reason(ex), "Shop editor");
		}
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
			ctrmap.WorkspaceSession game = ctrmap.Workspace.session();
			String name = (game == null ? PokeData.none() : PokeData.of(game)).itemName(id);
			return id + "  " + (name != null ? name : "?");
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			if (c != 1) {
				return;
			}
			int id = v instanceof Number ? ((Number) v).intValue() : ctrmap.util.Text.parseLeadingInt(String.valueOf(v));
			if (id >= 0) {
				shops[shop][r] = id;
				fireTableRowsUpdated(r, r);
			}
		}
	}
}
