package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.MasterTable;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneRelinker;
import ctrmap.ZoneResource;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Re-link a zone, in the Zone Loader's column: for each thing the zone's header names - its map, area, story
 * text and script - what it names now and who shares it, what it named as the game shipped and who shared
 * that, and two ways to change it: back to as shipped, or share another zone's.
 *
 * <p>THE OWNER'S WORDS, 2026-09-11: "add a feature to re-link/connect zones ... (say they accidentally unlink
 * a vanilla zone that they want to relink back to its original other vanilla zones it was linked to)". So the
 * first thing on each row is the way back to what it was linked to, and "All back to as shipped" undoes every
 * unlink at once. It lives in the zone column like every zone tool - with ToolHost's way back above it.
 *
 * <p>Not: ZoneLinkDialog, the warp connector this request's name was given to, which the owner had removed.
 */
public final class ZoneRelinkPanel {

	/** What the "All" button says; suites look for it. */
	public static final String ALL_BACK = "All back to as shipped";
	/** What each row's way back to the game as shipped says - not "Back to ...", which only ToolHost's way out says. */
	public static final String BACK = "Re-link as shipped";

	private ZoneRelinkPanel() {
	}

	/** Writes one relink, kept in the workspace's history, and reads the zones again - the window hands it in. */
	public interface Relinker {

		String relink(int zone, Map<ZoneResource, Integer> to) throws Exception;
	}

	/** The panel, or null when there is no game to relink in (said first). */
	public static JComponent panel(Component parent, WorkspaceSession ws, LoadedZone zones, Relinker relinker) {
		if (ws == null || zones == null || zones.count() == 0 || relinker == null) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Re-link zone");
			return null;
		}
		MasterTable shipped;
		String noShipped = null;
		try {
			shipped = ZoneRelinker.shipped(ws);
			if (shipped == null) {
				noShipped = "This workspace has no pristine copy of the game's zone table, so what each zone shipped"
						+ " with cannot be read. Sharing another zone's still works.";
			}
		} catch (java.io.IOException | RuntimeException unreadable) {
			shipped = null;
			noShipped = "The pristine copy of the game's zone table could not be read (" + ctrmap.Ui.reason(unreadable)
					+ "). Sharing another zone's still works.";
		}
		final MasterTable asShipped = shipped;
		final String whyNoShipped = noShipped;

		JPanel root = new JPanel(new BorderLayout(6, 6));
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(new JLabel("Zone:"));
		JSpinner zone = new JSpinner(new SpinnerNumberModel(Math.max(0, zones.index()), 0, zones.count() - 1, 1));
		top.add(zone);
		JLabel name = new JLabel();
		top.add(name);
		root.add(top, BorderLayout.NORTH);
		JPanel rows = new JPanel(new GridBagLayout());
		root.add(rows, BorderLayout.CENTER);
		JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton allBack = new JButton(ALL_BACK);
		bottom.add(allBack);
		if (whyNoShipped != null) {
			bottom.add(new JLabel("<html><i>" + whyNoShipped + "</i></html>"));
		}
		root.add(bottom, BorderLayout.SOUTH);

		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> {
			int z = ((Number) zone.getValue()).intValue();
			name.setText(zoneName(ws, zones, z));
			rows.removeAll();
			for (java.awt.event.ActionListener l : allBack.getActionListeners()) {
				allBack.removeActionListener(l);
			}
			List<ZoneRelinker.Link> links;
			try {
				links = ZoneRelinker.links(ws, z, asShipped);
			} catch (java.io.IOException ex) {
				rows.add(new JLabel(ctrmap.Ui.reason(ex)));
				ctrmap.Ui.offer(allBack, "Zone " + z + "'s links could not be read: " + ctrmap.Ui.reason(ex));
				rows.revalidate();
				rows.repaint();
				return;
			}
			GridBagConstraints c = new GridBagConstraints();
			c.insets = new Insets(3, 4, 3, 4);
			c.anchor = GridBagConstraints.WEST;
			int y = 0;
			boolean anyDiffers = false;
			for (ZoneRelinker.Link link : links) {
				c.gridy = y++;
				c.gridx = 0;
				rows.add(new JLabel("<html><b>" + capital(link.res.label) + "</b></html>"), c);
				c.gridx = 1;
				rows.add(new JLabel("now " + link.now + (link.sharingNow.isEmpty() ? ", its own"
						: ", shared with " + list(link.sharingNow))), c);
				c.gridx = 2;
				rows.add(new JLabel(link.shipped < 0 ? (asShipped == null ? "as shipped: unknown" : "not in the game as shipped")
						: "as shipped " + link.shipped + (link.sharingShipped.isEmpty() ? ", its own"
						: ", with " + list(link.sharingShipped))), c);
				c.gridx = 3;
				JButton back = new JButton(BACK);
				back.putClientProperty(ZoneResource.class, link.res);
				back.setToolTipText("Point this zone's " + link.res.label + " back at " + link.shipped + ", as shipped.");
				ctrmap.Ui.offer(back, link.differsFromShipped() ? null : link.shipped < 0 ? (whyNoShipped != null ? whyNoShipped
						: "Zone " + z + " was added by CTRMap - the game as shipped has no zone " + z + ".")
						: "This zone's " + link.res.label + " is already " + link.shipped + ", as shipped.");
				back.addActionListener(e -> apply(parent, relinker, z, one(link.res, link.shipped), refresh[0]));
				rows.add(back, c);
				anyDiffers |= link.differsFromShipped();
				c.gridx = 4;
				JPanel share = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
				share.add(new JLabel("share zone"));
				JSpinner other = new JSpinner(new SpinnerNumberModel(0, 0, zones.count() - 1, 1));
				share.add(other);
				JButton shareIt = new JButton("Share");
				shareIt.putClientProperty(ZoneResource.class, link.res);
				shareIt.putClientProperty(JSpinner.class, other);
				shareIt.setToolTipText("Point this zone's " + link.res.label + " at the one the chosen zone names now.");
				shareIt.addActionListener(e -> {
					int from = ((Number) other.getValue()).intValue();
					try {
						int id = MasterTable.read(ws).id(link.res, from);
						apply(parent, relinker, z, one(link.res, id), refresh[0]);
					} catch (java.io.IOException ex) {
						ctrmap.Ui.error(parent, ctrmap.Ui.reason(ex), "Re-link zone");
					}
				});
				share.add(shareIt);
				rows.add(share, c);
			}
			ctrmap.Ui.offer(allBack, anyDiffers ? null : links.get(0).shipped < 0
					? "Zone " + z + " is not in the game as shipped, so there is nothing to go back to."
					: "Zone " + z + " already names everything it shipped with.");
			allBack.addActionListener(e -> {
				Map<ZoneResource, Integer> to = new EnumMap<>(ZoneResource.class);
				for (ZoneRelinker.Link link : links) {
					if (link.differsFromShipped()) {
						to.put(link.res, link.shipped);
					}
				}
				apply(parent, relinker, z, to, refresh[0]);
			});
			rows.revalidate();
			rows.repaint();
		};
		zone.addChangeListener(e -> refresh[0].run());
		refresh[0].run();
		return root;
	}

	/** Asks, writes through the window, says what happened, and redraws. */
	static void apply(Component parent, Relinker relinker, int zone, Map<ZoneResource, Integer> to, Runnable refresh) {
		if (to.isEmpty()) {
			return;
		}
		StringBuilder q = new StringBuilder("Re-link zone " + zone + ":\n");
		for (Map.Entry<ZoneResource, Integer> e : to.entrySet()) {
			q.append("  its ").append(e.getKey().label).append(" -> ").append(e.getValue()).append("\n");
		}
		q.append("\nAny private copy it leaves stays in the game, unused. Undo, under the zone list, takes this back.");
		if (ctrmap.Ui.confirm(parent, q.toString(), "Re-link zone", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			ctrmap.Ui.message(parent, relinker.relink(zone, to), "Re-link zone", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent, ctrmap.Ui.reason(ex), "Re-link zone");
		}
		refresh.run();
	}

	private static Map<ZoneResource, Integer> one(ZoneResource res, int id) {
		Map<ZoneResource, Integer> m = new EnumMap<>(ZoneResource.class);
		m.put(res, id);
		return m;
	}

	private static String zoneName(WorkspaceSession ws, LoadedZone zones, int z) {
		try {
			return ctrmap.formats.text.LocationNames.zoneLabel(ws, zones.at(z).header.parentMap, z);
		} catch (RuntimeException unnamed) {
			return "zone " + z;
		}
	}

	private static String list(List<Integer> zones) {
		return zones.size() <= 8 ? zones.toString() : zones.subList(0, 8) + " and " + (zones.size() - 8) + " more";
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
