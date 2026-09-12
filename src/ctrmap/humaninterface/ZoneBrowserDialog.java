package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.WorkspaceSession;
import ctrmap.formats.zone.Zone;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * Look through every zone's map in 3D without loading them one at a time.
 *
 * <p>WHY IT EXISTS. The zone dropdown names 540 zones and shows none of them, so
 * finding the one you want meant loading each candidate - and loading a zone
 * brings its entities, its script, its editors and its undo history with it.
 * The owner asked for this in as many words: "would there be any way to add a
 * preview like this to the zone loader so i can quickly see what each zone is
 * rather than needing to load them one by one to see them?"
 *
 * <p>NOTHING IS LOADED BY LOOKING. The decode is {@link ZonePreview}, which
 * reads one region's model straight out of the workspace and hands back bytes.
 * Picking a zone here does nothing to the editor; the Load button is the only
 * thing that opens one, and it does it through the same path the dropdown uses.
 *
 * <p>ONE DECODE AT A TIME, AND THE LAST ONE WINS. Each selection decodes on a
 * worker with a sequence number, and a result that is not the newest is dropped
 * - scrolling a list with the arrow keys starts a decode per row, and without
 * that the pane would settle on whichever finished last rather than whichever
 * was asked for last. Measured on the owner's dump: a region decodes in about
 * 47 ms, 107 ms at the ninetieth percentile, which is fast enough to feel like
 * scrolling and far too slow to do on the event thread.
 */
public final class ZoneBrowserDialog {

	private ZoneBrowserDialog() {
	}

	/** A row: the zone's index and the name the dropdown would show. */
	public static final class Row {

		public final int index;
		public final String label;

		public Row(int index, String label) {
			this.index = index;
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/**
	 * Every zone, in index order, named the way the dropdown names them.
	 *
	 * <p>Separate from the dialog so a suite can check the list is built from
	 * the zone table rather than from a count - a browser that lists 540 rows
	 * and previews the wrong one is worse than no browser.
	 */
	public static List<Row> rows(LoadedZone zones) {
		List<Row> out = new ArrayList<>();
		if (zones == null) {
			return out;
		}
		for (int i = 0; i < zones.count(); i++) {
			String name = "Zone " + i;
			try {
				Zone z = zones.at(i);
				if (z != null && z.header != null) {
					name = ctrmap.formats.text.LocationNames.getLocName(z.header.parentMap) + " - " + i;
				}
			} catch (Exception unnamed) {
				//a zone whose header will not parse still gets a row: it is exactly
				//the kind of thing somebody opens this to go and look at
			}
			out.add(new Row(i, name));
		}
		return out;
	}

	/**
	 * @param parent what the dialog belongs to
	 * @param ws the open game, handed in - see {@link ZonePreview#of} for why
	 * @param zones the zone table, handed in
	 * @param load what to do with the chosen index - the window's own zone load
	 */
	public static void show(Component parent, WorkspaceSession ws, LoadedZone zones,
			java.util.function.IntConsumer load) {
		if (ws == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Browse zones");
			return;
		}
		List<Row> all = rows(zones);
		if (all.isEmpty()) {
			ctrmap.Ui.error(parent, "There are no zones to browse yet - open a game first.", "Browse zones");
			return;
		}

		final JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(parent), "Browse zones",
				java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		final DefaultListModel<Row> shown = new DefaultListModel<>();
		for (Row r : all) {
			shown.addElement(r);
		}
		final JList<Row> list = new JList<>(shown);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		final JTextField search = new JTextField();
		search.setToolTipText("Type part of a name, or a zone number.");

		final MapPreview3D view = new MapPreview3D();
		view.setPreferredSize(new Dimension(560, 440));
		final JLabel info = new JLabel(" ");

		JPanel left = new JPanel(new BorderLayout(4, 4));
		left.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(280, 440));
		left.add(sp, BorderLayout.CENTER);
		left.add(new JLabel("<html><small>Looking costs nothing - no zone is opened until you<br>"
				+ "press Load. Drag to orbit, scroll to zoom.</small></html>"), BorderLayout.SOUTH);

		JPanel centre = new JPanel(new BorderLayout(4, 4));
		centre.add(view, BorderLayout.CENTER);
		centre.add(info, BorderLayout.SOUTH);

		dlg.setLayout(new BorderLayout(6, 6));
		dlg.add(left, BorderLayout.WEST);
		dlg.add(centre, BorderLayout.CENTER);

		JPanel buttons = new JPanel();
		final JButton loadBtn = new JButton("Load this zone");
		JButton close = new JButton("Close");
		buttons.add(loadBtn);
		buttons.add(close);
		dlg.add(buttons, BorderLayout.SOUTH);

		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			void refilter() {
				String q = search.getText().trim().toLowerCase();
				shown.clear();
				for (Row r : all) {
					if (q.isEmpty() || r.label.toLowerCase().contains(q)
							|| String.valueOf(r.index).equals(q)) {
						shown.addElement(r);
					}
				}
			}

			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				refilter();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				refilter();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				refilter();
			}
		});

		final int[] seq = {0};
		list.addListSelectionListener(ev -> {
			if (ev.getValueIsAdjusting()) {
				return;
			}
			final Row r = list.getSelectedValue();
			if (r == null) {
				return;
			}
			info.setText("  Reading zone " + r.index + "...");
			final int mine = ++seq[0];
			new Thread(() -> {
				final ZonePreview.Shot shot = ZonePreview.of(ws, r.index);
				SwingUtilities.invokeLater(() -> {
					if (mine != seq[0]) {
						return; //a newer selection has already been asked for
					}
					String said = shot.note;
					if (shot.drawable() && !view.setRegion(shot.model, shot.textures)) {
						//the bytes were there and would not decode. Saying so matters:
						//the view is blank now rather than still showing the last zone,
						//and a blank pane with no sentence reads as a broken dialog.
						said = "Zone " + r.index + "'s map (region " + shot.region
								+ ") could not be decoded - nothing to show.";
					} else if (!shot.drawable()) {
						view.setRegion(new byte[0], null); //clear, rather than keep the last one
					}
					info.setText("<html>  " + said + "</html>");
				});
			}, "zone-preview").start();
		});

		Runnable pick = () -> {
			Row r = list.getSelectedValue();
			if (r == null) {
				return;
			}
			dlg.dispose();
			if (load != null) {
				load.accept(r.index);
			}
		};
		loadBtn.addActionListener(e -> pick.run());
		close.addActionListener(e -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					pick.run();
				}
			}
		});

		if (zones != null && zones.index() >= 0 && zones.index() < shown.size()) {
			list.setSelectedIndex(zones.index());
			list.ensureIndexIsVisible(zones.index());
		}
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
		view.stop();
	}
}
