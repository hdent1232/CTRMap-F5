package ctrmap.humaninterface.pokepick;

import ctrmap.formats.pokedata.PokeData;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Searchable pickers for Pokemon species, moves and items with a LIVE VISUAL
 * PREVIEW so the user sees what they are choosing - type badges and base-stat
 * bars for a species, the type/category/power card for a move - instead of
 * guessing from a bare name list. Each picker is a small modal dialog returning
 * the chosen id (or -1 if cancelled). Backed by {@link PokeData}; degrades to a
 * plain searchable list when the reference data is unavailable.
 */
public class PokePickers {

	public enum Kind {
		SPECIES, MOVE, ITEM
	}

	/** Opens the species picker (with the stat/type card). Returns id or -1. */
	public static int pickSpecies(Component parent, int current) {
		return pick(parent, current, Kind.SPECIES, "Choose a Pokemon");
	}

	/** Opens the move picker (with the type/category card). Returns id or -1. */
	public static int pickMove(Component parent, int current) {
		return pick(parent, current, Kind.MOVE, "Choose a move");
	}

	/** Opens the item picker. Returns id or -1. */
	public static int pickItem(Component parent, int current) {
		return pick(parent, current, Kind.ITEM, "Choose an item");
	}

	/**
	 * Wires double-click on a table into the matching visual picker: for a cell
	 * in a column {@code colToKind} maps to a non-null Kind, opens the picker
	 * seeded with the cell's current id and writes the chosen id back through the
	 * table model. The mapped columns should be marked non-editable so the picker
	 * (not a text editor) opens. Column index passed to the mapper is the MODEL
	 * index.
	 */
	public static void installDoubleClickPickers(final javax.swing.JTable table,
			final java.util.function.IntFunction<Kind> colToKind) {
		table.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() != 2) {
					return;
				}
				int row = table.rowAtPoint(e.getPoint());
				int vcol = table.columnAtPoint(e.getPoint());
				if (row < 0 || vcol < 0) {
					return;
				}
				int col = table.convertColumnIndexToModel(vcol);
				Kind k = colToKind.apply(col);
				if (k == null) {
					return;
				}
				int cur = parseLeadingInt(String.valueOf(table.getModel().getValueAt(row, col)));
				int picked = k == Kind.SPECIES ? pickSpecies(table, cur)
						: k == Kind.MOVE ? pickMove(table, cur) : pickItem(table, cur);
				if (picked >= 0) {
					table.getModel().setValueAt(picked, row, col);
				}
			}
		});
	}

	/** First integer token of a cell string ("25  Pikachu" -> 25), or -1. */
	public static int parseLeadingInt(String s) {
		if (s == null) {
			return -1;
		}
		int i = 0, n = s.length();
		while (i < n && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-') {
			i++;
		}
		int j = i;
		if (j < n && s.charAt(j) == '-') {
			j++;
		}
		while (j < n && Character.isDigit(s.charAt(j))) {
			j++;
		}
		try {
			return Integer.parseInt(s.substring(i, j));
		} catch (RuntimeException ex) {
			return -1;
		}
	}

	private static int pick(Component parent, int current, Kind kind, String title) {
		Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		final JDialog dlg = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
		final int[] result = {-1};

		int count = kind == Kind.SPECIES ? PokeData.speciesCount()
				: kind == Kind.MOVE ? PokeData.moveCount() : PokeData.itemCount();
		final List<int[]> all = new ArrayList<>();   // {id}
		final List<String> labels = new ArrayList<>();
		for (int id = 0; id < count; id++) {
			all.add(new int[]{id});
			labels.add(id + "  " + nameOf(kind, id));
		}

		final DefaultListModel<String> lm = new DefaultListModel<>();
		final List<Integer> visibleIds = new ArrayList<>();
		final JList<String> list = new JList<>(lm);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		final PreviewPanel preview = new PreviewPanel(kind);
		final JTextField search = new JTextField();

		Runnable rebuild = () -> {
			String q = search.getText().trim().toLowerCase();
			lm.clear();
			visibleIds.clear();
			for (int i = 0; i < all.size(); i++) {
				int id = all.get(i)[0];
				if (id == 0 && kind != Kind.ITEM) {
					continue; // species/move 0 = none/dummy
				}
				if (q.isEmpty() || labels.get(i).toLowerCase().contains(q)) {
					lm.addElement(labels.get(i));
					visibleIds.add(id);
				}
			}
		};
		rebuild.run();
		list.addListSelectionListener(e -> {
			int vi = list.getSelectedIndex();
			if (vi >= 0 && vi < visibleIds.size()) {
				preview.set(visibleIds.get(vi));
			}
		});
		search.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				rebuild.run();
			}

			public void removeUpdate(DocumentEvent e) {
				rebuild.run();
			}

			public void changedUpdate(DocumentEvent e) {
				rebuild.run();
			}
		});
		// select the current value
		selectId(list, visibleIds, current);
		if (list.getSelectedIndex() < 0 && !visibleIds.isEmpty()) {
			list.setSelectedIndex(0);
		}

		JButton ok = new JButton("Choose");
		JButton cancel = new JButton("Cancel");
		Runnable choose = () -> {
			int vi = list.getSelectedIndex();
			if (vi >= 0 && vi < visibleIds.size()) {
				result[0] = visibleIds.get(vi);
			}
			dlg.dispose();
		};
		ok.addActionListener(e -> choose.run());
		cancel.addActionListener(e -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					choose.run();
				}
			}
		});

		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(240, 320));
		left.add(sp, BorderLayout.CENTER);

		JPanel buttons = new JPanel();
		buttons.add(ok);
		buttons.add(cancel);

		dlg.setLayout(new BorderLayout(8, 8));
		((JPanel) dlg.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		dlg.add(left, BorderLayout.WEST);
		dlg.add(preview, BorderLayout.CENTER);
		dlg.add(buttons, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
		return result[0];
	}

	private static void selectId(JList<String> list, List<Integer> ids, int id) {
		for (int i = 0; i < ids.size(); i++) {
			if (ids.get(i) == id) {
				list.setSelectedIndex(i);
				list.ensureIndexIsVisible(i);
				return;
			}
		}
	}

	static String nameOf(Kind kind, int id) {
		switch (kind) {
			case SPECIES: return PokeData.speciesName(id);
			case MOVE: return PokeData.moveName(id);
			default: return PokeData.itemName(id);
		}
	}

	/** Paints the live preview card for the focused id. */
	private static class PreviewPanel extends JPanel {

		final Kind kind;
		int id = -1;

		PreviewPanel(Kind kind) {
			this.kind = kind;
			setPreferredSize(new Dimension(300, 320));
			setBackground(null);
		}

		void set(int id) {
			this.id = id;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g0) {
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			Color fg = getForeground();
			int w = getWidth();
			int pad = 14, x = pad, y = pad + 14;
			if (id < 0) {
				return;
			}
			g.setColor(fg);
			g.setFont(getFont().deriveFont(Font.BOLD, 18f));
			String title = id + "  " + nameOf(kind, id);
			g.drawString(title, x, y);
			y += 14;

			if (kind == Kind.SPECIES) {
				paintSpecies(g, x, y, w - pad * 2, fg);
			} else if (kind == Kind.MOVE) {
				paintMove(g, x, y, fg);
			} else {
				g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
				g.setColor(fg);
				g.drawString("Item #" + id, x, y + 24);
			}
		}

		private void paintSpecies(Graphics2D g, int x, int y, int cardW, Color fg) {
			int[] tp = PokeData.types(id);
			int[] st = PokeData.baseStats(id);
			int[] ab = PokeData.abilities(id);
			y += 18;
			if (tp != null) {
				int bx = x;
				bx = badge(g, bx, y, PokeData.typeName(tp[0]), PokeData.typeColor(tp[0]));
				if (tp[1] != tp[0]) {
					badge(g, bx, y, PokeData.typeName(tp[1]), PokeData.typeColor(tp[1]));
				}
				y += 34;
			}
			if (st != null) {
				String[] names = {"HP", "Atk", "Def", "SpA", "SpD", "Spe"};
				int[] order = {0, 1, 2, 4, 5, 3}; // display order HP/Atk/Def/SpA/SpD/Spe
				g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
				int total = 0;
				for (int s : st) {
					total += s;
				}
				int barX = x + 40, barW = cardW - 90;
				for (int i = 0; i < 6; i++) {
					int val = st[order[i]];
					int by = y + i * 22;
					g.setColor(fg);
					g.drawString(names[i], x, by + 11);
					g.setColor(new Color(0, 0, 0, 40));
					g.fillRoundRect(barX, by, barW, 12, 6, 6);
					int fillW = Math.max(3, (int) (barW * Math.min(val, 200) / 200.0));
					g.setColor(statColor(val));
					g.fillRoundRect(barX, by, fillW, 12, 6, 6);
					g.setColor(fg);
					g.drawString(String.valueOf(val), barX + barW + 6, by + 11);
				}
				y += 6 * 22 + 6;
				g.setFont(getFont().deriveFont(Font.BOLD, 12f));
				g.drawString("Total  " + total, x, y + 10);
				y += 24;
			}
			if (ab != null) {
				g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
				g.setColor(fg);
				String a = PokeData.abilityName(ab[0]);
				if (ab[1] != 0 && ab[1] != ab[0]) {
					a += " / " + PokeData.abilityName(ab[1]);
				}
				g.drawString("Ability: " + a, x, y + 10);
				if (ab[2] != 0) {
					g.drawString("Hidden: " + PokeData.abilityName(ab[2]), x, y + 28);
				}
			}
		}

		private void paintMove(Graphics2D g, int x, int y, Color fg) {
			int[] mi = PokeData.moveInfo(id);
			y += 18;
			if (mi == null) {
				g.setColor(fg);
				g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
				g.drawString("(move data unavailable)", x, y);
				return;
			}
			int bx = badge(g, x, y, PokeData.typeName(mi[0]), PokeData.typeColor(mi[0]));
			String cat = mi[1] >= 0 && mi[1] < PokeData.CATEGORY_NAMES.length ? PokeData.CATEGORY_NAMES[mi[1]] : "?";
			badge(g, bx, y, cat, categoryColor(mi[1]));
			y += 44;
			g.setColor(fg);
			g.setFont(getFont().deriveFont(Font.PLAIN, 13f));
			g.drawString("Power:     " + (mi[2] == 0 ? "-" : mi[2]), x, y);
			g.drawString("Accuracy:  " + (mi[3] == 0 || mi[3] > 100 ? "-" : mi[3]), x, y + 22);
			g.drawString("PP:        " + mi[4], x, y + 44);
		}

		/** Draws a rounded type/category badge; returns the next x. */
		private int badge(Graphics2D g, int x, int y, String text, Color c) {
			g.setFont(getFont().deriveFont(Font.BOLD, 12f));
			int tw = g.getFontMetrics().stringWidth(text);
			int bw = tw + 18, bh = 22;
			g.setColor(c);
			g.fillRoundRect(x, y, bw, bh, 11, 11);
			g.setColor(textOn(c));
			g.drawString(text, x + 9, y + 15);
			return x + bw + 8;
		}
	}

	private static Color statColor(int v) {
		if (v >= 120) {
			return new Color(0x4CAF50);
		}
		if (v >= 90) {
			return new Color(0x8BC34A);
		}
		if (v >= 60) {
			return new Color(0xFFC107);
		}
		if (v >= 40) {
			return new Color(0xFF9800);
		}
		return new Color(0xF44336);
	}

	private static Color categoryColor(int cat) {
		switch (cat) {
			case 1: return new Color(0xC0392B); // physical
			case 2: return new Color(0x2980B9); // special
			default: return new Color(0x7F8C8D); // status
		}
	}

	private static Color textOn(Color c) {
		double lum = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue());
		return lum > 150 ? Color.BLACK : Color.WHITE;
	}
}
