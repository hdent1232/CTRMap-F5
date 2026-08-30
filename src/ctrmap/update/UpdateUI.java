package ctrmap.update;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * The user-facing half of updating: a quiet check when CTRMap starts, and a
 * "Check for updates" the user can ask for.
 *
 * <p>The startup check never blocks and never nags: it runs on a background
 * thread, says nothing at all when there is no update or no network, and offers
 * "Skip this version" so a release the user does not want stops asking.
 */
public class UpdateUI {

	/** Silent background check; shows the offer only if there is a newer release. */
	public static void checkOnStartup(final Frame parent) {
		if (!UpdateChecker.checkOnStartup()) {
			return;
		}
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				final UpdateChecker.Release rel = UpdateChecker.check();
				if (rel == null || UpdateChecker.isSkipped(rel.version)) {
					return;
				}
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						offer(parent, rel, true);
					}
				});
			}
		}, "ctrmap-update-check");
		t.setDaemon(true);
		t.start();
	}

	/** The menu action: always reports something, including "you are up to date". */
	public static void checkNow(final Frame parent) {
		final File install = Updater.installDir();
		if (Updater.isUpdateStaged(install)) {
			JOptionPane.showMessageDialog(parent, readyMessage(Updater.stagedVersion(install)),
					"Update ready", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		new SwingWorker<UpdateChecker.Release, Void>() {
			@Override
			protected UpdateChecker.Release doInBackground() {
				return UpdateChecker.check();
			}

			@Override
			protected void done() {
				UpdateChecker.Release rel = null;
				try {
					rel = get();
				} catch (Exception ex) {
					//treated as "nothing to report" below
				}
				if (rel == null) {
					JOptionPane.showMessageDialog(parent,
							"You are on CTRMap-F5 " + AppVersion.current() + ", which is the latest release.\n\n"
							+ "(If you are offline or GitHub is unreachable, this says the same thing -\n"
							+ "the check never interrupts you to report a network problem.)",
							"No update available", JOptionPane.INFORMATION_MESSAGE);
					return;
				}
				offer(parent, rel, false);
			}
		}.execute();
	}

	private static String readyMessage(String version) {
		//the two builds install at different moments, so say the true one
		boolean bundle = Updater.flavour() == Updater.Flavour.APP_IMAGE;
		return "CTRMap-F5 " + (version == null ? "" : version) + " is downloaded and ready.\n\n"
				+ (bundle
						? "Close CTRMap and it will finish installing, then reopen itself.\n"
						: "Close CTRMap and open it again - the update is applied as it starts.\n")
				+ "Nothing else on your machine changes: same folder, same shortcut,\n"
				+ "and your workspace, settings and game files are untouched.";
	}

	private static void offer(Frame parent, final UpdateChecker.Release rel, boolean startup) {
		final File install = Updater.installDir();

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.add(new JLabel("<html><b>CTRMap-F5 " + esc(rel.version) + "</b> is available."
				+ "<br>You have " + esc(AppVersion.current()) + ".</html>"));
		body.add(Box.createVerticalStrut(8));
		if (rel.notes != null && !rel.notes.trim().isEmpty()) {
			JTextArea notes = new JTextArea(rel.notes.trim(), 10, 58);
			notes.setEditable(false);
			notes.setLineWrap(true);
			notes.setWrapStyleWord(true);
			notes.setCaretPosition(0);
			JScrollPane sp = new JScrollPane(notes);
			sp.setBorder(BorderFactory.createTitledBorder("What changed"));
			sp.setPreferredSize(new Dimension(560, 200));
			body.add(sp);
			body.add(Box.createVerticalStrut(8));
		}
		if (install == null) {
			body.add(new JLabel("<html>This copy runs from a source checkout, so it updates with"
					+ "<br><code>git pull</code> and <code>build.ps1</code> rather than from here.</html>"));
		} else {
			body.add(new JLabel("<html>The update is downloaded into the folder CTRMap already lives in"
					+ " and applied<br>the next time you start it. There is never a second copy of the app,"
					+ " and your<br>workspace, settings and game files are not touched.</html>"));
		}
		JCheckBox noStartup = new JCheckBox("Check for updates when CTRMap starts", UpdateChecker.checkOnStartup());
		if (startup) {
			body.add(Box.createVerticalStrut(8));
			body.add(noStartup);
		}

		String[] options = install == null
				? new String[]{"Open the releases page", "Not now"}
				: new String[]{"Download and install", "Not now", "Skip this version"};
		int pick = JOptionPane.showOptionDialog(parent, body, "Update available",
				JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
		if (startup) {
			UpdateChecker.setCheckOnStartup(noStartup.isSelected());
		}
		if (pick == 2) {
			UpdateChecker.skip(rel.version);
			return;
		}
		if (pick != 0) {
			return;
		}
		if (install == null) {
			browse(parent, UpdateChecker.RELEASES_PAGE);
			return;
		}
		if (!Updater.isWritable(install)) {
			//checked BEFORE downloading: failing on permissions after fetching
			//seventy megabytes wastes the user's time for no reason
			JOptionPane.showMessageDialog(parent,
					"CTRMap cannot write to its own folder:\n  " + install.getAbsolutePath()
					+ "\n\nIt was probably installed somewhere that needs administrator rights."
					+ "\nMove the CTRMap folder somewhere you own - your Desktop or Documents -"
					+ "\nand try again, or download the update yourself from the releases page.",
					"Cannot update in place", JOptionPane.WARNING_MESSAGE);
			browse(parent, UpdateChecker.RELEASES_PAGE);
			return;
		}
		if (rel.downloadUrl == null) {
			JOptionPane.showMessageDialog(parent,
					"That release does not have a download for this kind of installation.\n"
					+ "Opening the releases page so you can pick one.",
					"No matching download", JOptionPane.INFORMATION_MESSAGE);
			browse(parent, UpdateChecker.RELEASES_PAGE);
			return;
		}
		download(parent, rel, install);
	}

	private static void download(final Frame parent, final UpdateChecker.Release rel, final File install) {
		final JDialog dlg = new JDialog(parent, "Downloading update", true);
		final JLabel status = new JLabel("Starting...");
		final JProgressBar bar = new JProgressBar(0, 100);
		JPanel p = new JPanel(new BorderLayout(8, 8));
		p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		p.add(status, BorderLayout.NORTH);
		p.add(bar, BorderLayout.CENTER);
		dlg.setContentPane(p);
		dlg.setSize(420, 130);
		dlg.setLocationRelativeTo(parent);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		final SwingWorker<Void, Object[]> worker = new SwingWorker<Void, Object[]>() {
			@Override
			protected Void doInBackground() throws Exception {
				Updater.stage(rel, install, new Updater.Progress() {
					@Override
					public void status(String message) {
						publish(new Object[]{"s", message});
					}

					@Override
					public void percent(int pct) {
						publish(new Object[]{"p", pct});
					}
				});
				return null;
			}

			@Override
			protected void process(java.util.List<Object[]> chunks) {
				for (Object[] c : chunks) {
					if ("s".equals(c[0])) {
						status.setText((String) c[1]);
					} else {
						int pct = (Integer) c[1];
						bar.setIndeterminate(pct < 0);
						if (pct >= 0) {
							bar.setValue(pct);
						}
					}
				}
			}

			@Override
			protected void done() {
				dlg.dispose();
				try {
					get();
				} catch (Exception ex) {
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					JOptionPane.showMessageDialog(parent,
							"The update was not installed.\n\n" + cause.getMessage()
							+ "\n\nNothing on your machine was changed.",
							"Update failed", JOptionPane.ERROR_MESSAGE);
					return;
				}
				JOptionPane.showMessageDialog(parent, readyMessage(rel.version),
						"Update ready", JOptionPane.INFORMATION_MESSAGE);
			}
		};
		worker.execute();
		dlg.setVisible(true);
	}

	private static void browse(Frame parent, String url) {
		try {
			java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(parent, "Open this in your browser:\n" + url,
					"Releases", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	/** Release text comes from GitHub, so it is escaped before it reaches HTML labels. */
	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
