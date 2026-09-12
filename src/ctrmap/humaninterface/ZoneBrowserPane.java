package ctrmap.humaninterface;

import ctrmap.WorkspaceSession;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * Look through the zones and see each one before opening it.
 *
 * <p>BUILT LIKE THE ATMOSPHERE PICKER, which is what was asked for at the start
 * and what two earlier attempts were not: a list you scroll with the arrow keys,
 * a live preview of whatever row you are on, and a button that commits. The
 * owner's words were "make it like the preview for the fog and lighting, where
 * you can easily scroll through several things and see the preview live before
 * selecting something".
 *
 * <p>WHY THE DROPDOWN COULD NEVER BE THAT. Selecting a row of the Load Zone
 * combo IS the load - it opens the zone, its entities, its script, its editors.
 * Arrowing down it to look at three candidates therefore loads three zones, and
 * the preview arrives after the thing it was supposed to save you from has
 * already happened. So looking and loading are two different controls here:
 * moving in this list draws, and nothing else; only {@link #LOAD} or a
 * double-click opens anything.
 *
 * <p>THE LIST IS A MIRROR of the dropdown's own rows, so the two can never come
 * to disagree about what zone 214 is called, and loading goes back out through
 * the dropdown - the one path that knows how to open a zone.
 */
public final class ZoneBrowserPane extends JPanel {

	/** What the button says; the suites look for it rather than a position. */
	public static final String LOAD = "Load this zone";

	/** Told which zone to open, when the user actually asks for one. */
	public interface Loader {

		void load(int zoneIndex);
	}

	private final DefaultListModel<String> shown = new DefaultListModel<>();
	private final JList<String> list = new JList<>(shown);
	private final JTextField search = new JTextField();
	private final ZonePreviewPane preview;
	private final JButton load = new JButton(LOAD);

	/** Every zone's label, by zone index. */
	private final List<String> labels = new ArrayList<>();
	/** Which zone each visible row is, since the search filters rows out. */
	private final List<Integer> rows = new ArrayList<>();

	private Loader loader;

	/** @param owner the window's loaded zone; the preview hosts the map view. */
	public ZoneBrowserPane(ctrmap.LoadedZone owner,
			ctrmap.humaninterface.tools.ToolSelection tools) {
		super(new BorderLayout(0, 4));
		preview = new ZonePreviewPane(owner, tools);
		setBorder(BorderFactory.createTitledBorder("Browse zones"));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		search.setToolTipText("Type part of a name, or a zone number.");

		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(search, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(360, 220));
		top.add(sp, BorderLayout.CENTER);
		add(top, BorderLayout.NORTH);
		add(preview, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		load.setToolTipText("Opens the highlighted zone. Looking costs nothing - nothing is"
				+ " opened until you press this.");
		buttons.add(load);
		add(buttons, BorderLayout.SOUTH);

		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refilter();
			}
		});
		list.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (e.getValueIsAdjusting()) {
					return;
				}
				//LOOKING ONLY. Nothing here opens a zone: that is the whole point.
				int zone = highlighted();
				if (zone >= 0) {
					preview.preview(zone);
				}
			}
		});
		load.addActionListener(e -> commit());
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					commit();
				}
			}
		});
	}

	/** The game to read maps out of, handed in rather than fetched. */
	public void use(WorkspaceSession ws) {
		preview.use(ws);
	}

	/** The rows, where {@code labels.get(i)} is zone {@code i}. */
	public void setZones(List<String> zoneLabels) {
		int wasZone = highlighted();
		labels.clear();
		labels.addAll(zoneLabels);
		refilter();
		if (wasZone >= 0) {
			select(wasZone);
		}
	}

	/** What to do when the user asks for a zone to be opened. */
	public void onLoad(Loader l) {
		this.loader = l;
	}

	/** Highlights a zone, without opening it - used to follow the loaded one. */
	public void select(int zoneIndex) {
		int row = rows.indexOf(zoneIndex);
		if (row >= 0 && row != list.getSelectedIndex()) {
			list.setSelectedIndex(row);
			list.ensureIndexIsVisible(row);
		}
	}

	/** The zone the cursor is on, or -1. */
	public int highlighted() {
		int row = list.getSelectedIndex();
		return row < 0 || row >= rows.size() ? -1 : rows.get(row);
	}

	/** The list itself, for suites - a picture cannot be read back, a selection can.
	 *  (Not list(): Component.list() is a deprecated AWT method and would be overridden.) */
	public JList<String> zoneRows() {
		return list;
	}

	/** The button that opens a zone; nothing else in here does. */
	public JButton loadButton() {
		return load;
	}

	/** The preview beside the list. */
	public ZonePreviewPane preview() {
		return preview;
	}

	/** The search box, so a suite can drive the filter the way a user does. */
	public JTextField searchBox() {
		return search;
	}

	private void commit() {
		int zone = highlighted();
		if (zone >= 0 && loader != null) {
			loader.load(zone);
		}
	}

	private void refilter() {
		String q = search.getText().trim().toLowerCase();
		shown.clear();
		rows.clear();
		for (int i = 0; i < labels.size(); i++) {
			String label = labels.get(i);
			if (q.isEmpty() || label.toLowerCase().contains(q) || String.valueOf(i).equals(q)) {
				shown.addElement(label);
				rows.add(i);
			}
		}
	}
}
