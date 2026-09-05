package ctrmap.setup;

import ctrmap.CtrmapMainframe;
import ctrmap.ModDeployer;
import ctrmap.Ui;
import ctrmap.Workspace;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Walks a first-time user from "I just downloaded this" to "there is a map on my
 * screen".
 *
 * <p>Before this existed, a new user's first experience was an error box listing
 * eight missing GARC archives by name, over an empty editor, with a note telling
 * them to go and fill in two paths. Everything the wizard does was technically
 * possible before; none of it was discoverable.
 *
 * <p>Each step explains itself, validates before it lets you past, and can say
 * what is wrong in words rather than in archive names. The one thing it will not
 * do is pretend: the final step verifies that zones actually loaded, because
 * {@code Workspace.valid} is set before anything is read and so cannot tell
 * "settings saved" apart from "editor working".
 */
public class SetupWizard extends JDialog {

	/** Empty HTML, not a space: a JLabel decides between plain and HTML mode from
	 *  the FIRST text it is given, so a label that will later show HTML has to
	 *  start in HTML mode or it paints the markup literally. */
	private static final String BLANK = "<html>&nbsp;</html>";

	private static final String PREF_NODE = "ctrmap.setup";
	private static final String PREF_SUPPRESS = "SKIP_SETUP_ON_STARTUP";

	private static final int STEP_WELCOME = 0;
	private static final int STEP_GAME = 1;
	private static final int STEP_WORKSPACE = 2;
	private static final int STEP_EMULATOR = 3;
	private static final int STEP_FINISH = 4;
	private static final String[] TITLES = {
		"Welcome", "Your game", "Working folder", "Your emulator", "Finish"
	};

	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	private final JLabel heading = new JLabel();
	private final JLabel stepCount = new JLabel();
	private final JButton back = new JButton("Back");
	private final JButton next = new JButton("Next");
	private final JButton skip = new JButton("Skip");
	private final JButton cancel = new JButton("Cancel");
	private final JCheckBox suppress = new JCheckBox("Do not show this automatically again");

	private int step = STEP_WELCOME;
	private boolean finished = false;

	// step 2
	private final JTextField gameField = new JTextField();
	private final JLabel gameVerdict = new JLabel(BLANK);
	private final JComponent gameDetail = prose("");
	private final JButton useSuggestion = new JButton("Use that folder instead");
	private final JButton findForMe = new JButton("Find it for me");
	private DumpCheck.Result gameResult;
	private boolean gameSkipped = false;

	// step 3
	private final JTextField wsField = new JTextField();
	private final JLabel wsVerdict = new JLabel(BLANK);

	// step 4
	private final JTextField emuField = new JTextField();
	private boolean emuSkipped = false;

	// step 5
	private final JComponent finishSummary = prose("");
	private final JLabel finishStatus = new JLabel(BLANK);
	private final JProgressBar finishBar = new JProgressBar();

	/** True when CTRMap has never been set up and the user has not opted out. */
	public static boolean shouldRunOnStartup() {
		return !Workspace.isConfigured() && !prefs().getBoolean(PREF_SUPPRESS, false);
	}

	private static Preferences prefs() {
		return Preferences.userRoot().node(PREF_NODE);
	}

	/** Opens the wizard. Returns true when the user finished setup successfully. */
	public static boolean show(Frame parent) {
		SetupWizard w = new SetupWizard(parent);
		w.setVisible(true);
		return w.finished;
	}

	private SetupWizard(Frame parent) {
		super(parent, "Set up CTRMap", true);
		buildUi();
		prefill();
		showStep(STEP_WELCOME);
		setSize(660, 470);
		setMinimumSize(new Dimension(560, 420));
		setLocationRelativeTo(parent);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().setDefaultButton(next);
	}

	// ---- layout -----------------------------------------------------------

	private void buildUi() {
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize() + 5f));
		stepCount.setForeground(Color.GRAY);
		JPanel top = new JPanel(new BorderLayout());
		top.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		top.add(heading, BorderLayout.CENTER);
		top.add(stepCount, BorderLayout.EAST);

		body.setBorder(BorderFactory.createEmptyBorder(0, 16, 8, 16));
		body.add(welcomePanel(), String.valueOf(STEP_WELCOME));
		body.add(gamePanel(), String.valueOf(STEP_GAME));
		body.add(workspacePanel(), String.valueOf(STEP_WORKSPACE));
		body.add(emulatorPanel(), String.valueOf(STEP_EMULATOR));
		body.add(finishPanel(), String.valueOf(STEP_FINISH));

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		left.add(suppress);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.add(back);
		right.add(skip);
		right.add(next);
		right.add(cancel);
		JPanel buttons = new JPanel(new BorderLayout());
		buttons.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));
		buttons.add(left, BorderLayout.WEST);
		buttons.add(right, BorderLayout.EAST);

		setLayout(new BorderLayout());
		add(top, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);

		back.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				showStep(step - 1);
			}
		});
		next.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (step == STEP_FINISH) {
					doFinish();
				} else {
					showStep(step + 1);
				}
			}
		});
		skip.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (step == STEP_GAME) {
					gameSkipped = true;
				}
				if (step == STEP_EMULATOR) {
					emuSkipped = true;
					emuField.setText("");
				}
				showStep(step + 1);
			}
		});
		cancel.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				doCancel();
			}
		});
	}

	private JPanel welcomePanel() {
		JPanel p = column();
		p.add(prose("CTRMap edits the world of the Pokemon 3DS games - the maps, the buildings,"
				+ " the people in them and what they say."));
		p.add(gap(12));
		p.add(lede("It needs one thing from you: a copy of your own game, unpacked into a folder."));
		p.add(gap(6));
		p.add(prose("CTRMap does not include any game files and cannot download them. You unpack"
				+ " them yourself, from a cartridge or eShop copy that you own, using a 3DS"
				+ " dumping tool."));
		p.add(gap(12));
		p.add(prose("Supported games: Pokemon X, Y, Omega Ruby and Alpha Sapphire."));
		p.add(gap(12));
		p.add(prose("This takes about a minute. You can skip any step and come back later from"
				+ " Options > Setup wizard."));
		return p;
	}

	private JPanel gamePanel() {
		JPanel p = column();
		p.add(prose("Choose the folder your game was unpacked into. It is the folder that"
				+ " contains folders named \"a\", \"sound\" and \"shader\"."));
		p.add(gap(10));
		JPanel row = new JPanel(new BorderLayout(6, 0));
		JButton browse = new JButton("Browse...");
		row.add(gameField, BorderLayout.CENTER);
		row.add(browse, BorderLayout.EAST);
		row.setAlignmentX(0f);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		p.add(row);
		p.add(gap(4));
		JPanel finders = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		finders.setAlignmentX(0f);
		finders.add(findForMe);
		finders.add(Box.createHorizontalStrut(8));
		finders.add(useSuggestion);
		finders.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		p.add(finders);
		p.add(gap(8));
		gameVerdict.setFont(gameVerdict.getFont().deriveFont(Font.BOLD));
		gameVerdict.setAlignmentX(0f);
		gameDetail.setAlignmentX(0f);
		p.add(gameVerdict);
		p.add(gameDetail);
		useSuggestion.setVisible(false);

		browse.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				File f = pickFolder("Choose your unpacked game folder", gameField.getText());
				if (f != null) {
					gameField.setText(f.getAbsolutePath());
					revalidateGame();
				}
			}
		});
		findForMe.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				searchForDumps();
			}
		});
		useSuggestion.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (gameResult != null && gameResult.suggestion != null) {
					gameField.setText(gameResult.suggestion.getAbsolutePath());
					revalidateGame();
				}
			}
		});
		gameField.getDocument().addDocumentListener(new SimpleDocListener() {
			@Override
			public void changed() {
				revalidateGame();
			}
		});
		return p;
	}

	private JPanel workspacePanel() {
		JPanel p = column();
		p.add(prose("CTRMap keeps a working copy of the parts of the game you are editing in a"
				+ " folder of its own. Pick somewhere with a few hundred megabytes free."));
		p.add(gap(8));
		p.add(prose("Your game folder is not changed until you choose to save."));
		p.add(gap(10));
		JPanel row = new JPanel(new BorderLayout(6, 0));
		JButton browse = new JButton("Browse...");
		row.add(wsField, BorderLayout.CENTER);
		row.add(browse, BorderLayout.EAST);
		row.setAlignmentX(0f);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		p.add(row);
		p.add(gap(8));
		wsVerdict.setAlignmentX(0f);
		p.add(wsVerdict);
		browse.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				File f = pickFolder("Choose a working folder", wsField.getText());
				if (f != null) {
					wsField.setText(f.getAbsolutePath());
					revalidateWorkspace();
				}
			}
		});
		wsField.getDocument().addDocumentListener(new SimpleDocListener() {
			@Override
			public void changed() {
				revalidateWorkspace();
			}
		});
		return p;
	}

	private JPanel emulatorPanel() {
		JPanel p = column();
		p.add(prose("When you want to play what you have made, CTRMap can copy your changes"
				+ " straight into an emulator. This is optional - skip it if you are not sure."));
		p.add(gap(10));
		JPanel row = new JPanel(new BorderLayout(6, 0));
		JButton browse = new JButton("Browse...");
		row.add(emuField, BorderLayout.CENTER);
		row.add(browse, BorderLayout.EAST);
		row.setAlignmentX(0f);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		p.add(row);
		p.add(gap(8));
		p.add(prose("Your changes are never applied to the emulator automatically - you choose"
				+ " when, with File > Deploy mod."));
		browse.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				File f = pickFolder("Choose your emulator's mod folder", emuField.getText());
				if (f != null) {
					emuField.setText(f.getAbsolutePath());
				}
			}
		});
		return p;
	}

	private JPanel finishPanel() {
		JPanel p = column();
		finishSummary.setAlignmentX(0f);
		p.add(finishSummary);
		p.add(gap(12));
		finishStatus.setAlignmentX(0f);
		finishBar.setAlignmentX(0f);
		finishBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		finishBar.setVisible(false);
		p.add(finishStatus);
		p.add(gap(4));
		p.add(finishBar);
		return p;
	}

	// ---- step flow --------------------------------------------------------

	private void showStep(int s) {
		if (s < STEP_WELCOME || s > STEP_FINISH) {
			return;
		}
		//skipping the game step makes everything after it pointless
		if (s > STEP_GAME && gameSkipped) {
			s = STEP_FINISH;
		}
		step = s;
		cards.show(body, String.valueOf(step));
		heading.setText(TITLES[step]);
		stepCount.setText("Step " + (step + 1) + " of " + TITLES.length);
		back.setEnabled(step > STEP_WELCOME);
		skip.setVisible(step == STEP_GAME || step == STEP_EMULATOR);
		suppress.setVisible(step == STEP_WELCOME || step == STEP_FINISH);
		next.setText(step == STEP_FINISH ? "Set up CTRMap" : "Next");
		if (step == STEP_GAME) {
			gameSkipped = false;
			revalidateGame();
		} else if (step == STEP_WORKSPACE) {
			revalidateWorkspace();
		} else if (step == STEP_FINISH) {
			refreshSummary();
		} else {
			next.setEnabled(true);
		}
		getRootPane().setDefaultButton(next);
	}

	private void prefill() {
		if (Workspace.GAMEDIR_PATH != null) {
			gameField.setText(Workspace.GAMEDIR_PATH);
		}
		if (Workspace.WORKSPACE_PATH != null && !Workspace.WORKSPACE_PATH.trim().isEmpty()) {
			wsField.setText(Workspace.WORKSPACE_PATH);
		} else {
			//somewhere writable that is not inside the install folder, because an
			//installed copy may live somewhere the user cannot write to, and an
			//update replaces the install folder's contents
			String home = System.getProperty("user.home");
			wsField.setText(new File(home == null ? "." : home, "CTRMap Workspace").getAbsolutePath());
		}
		File emu = ModDeployer.azaharModRoot(ModDeployer.guessTitleId());
		if (emu != null && emu.getParentFile() != null && emu.getParentFile().isDirectory()) {
			emuField.setText(emu.getAbsolutePath());
		}
		suppress.setSelected(prefs().getBoolean(PREF_SUPPRESS, false));
	}

	private void revalidateGame() {
		String path = gameField.getText().trim();
		if (path.isEmpty()) {
			gameResult = null;
			gameVerdict.setText(BLANK);
			setProse(gameDetail, "");
			useSuggestion.setVisible(false);
			next.setEnabled(false);
			return;
		}
		gameResult = DumpCheck.check(new File(path));
		boolean ok = gameResult.usable();
		gameVerdict.setForeground(ok ? new Color(0, 128, 0) : new Color(160, 0, 0));
		gameVerdict.setText("<html>" + (ok ? "&#10003;&nbsp; " : "&#10007;&nbsp; ")
				+ esc(gameResult.headline) + "</html>");
		setProse(gameDetail, gameResult.detail);
		useSuggestion.setVisible(gameResult.suggestion != null);
		next.setEnabled(ok);
	}

	private void revalidateWorkspace() {
		String path = wsField.getText().trim();
		if (path.isEmpty()) {
			wsVerdict.setForeground(Color.GRAY);
			wsVerdict.setText("Choose a folder.");
			next.setEnabled(false);
			return;
		}
		File f = new File(path);
		if (f.isFile()) {
			wsVerdict.setForeground(new Color(160, 0, 0));
			wsVerdict.setText("✗  That is a file. Choose a folder.");
			next.setEnabled(false);
			return;
		}
		if (gameResult != null && gameResult.usable() && isInside(f, new File(gameField.getText().trim()))) {
			wsVerdict.setForeground(new Color(160, 0, 0));
			wsVerdict.setText("✗  That is inside your game folder. Choose somewhere separate.");
			next.setEnabled(false);
			return;
		}
		wsVerdict.setForeground(new Color(0, 128, 0));
		wsVerdict.setText(f.isDirectory()
				? "✓  Ready." + (isEmptyDir(f) ? "" : " (CTRMap will add its own folders here.)")
				: "✓  This folder will be created.");
		next.setEnabled(true);
	}

	private void refreshSummary() {
		StringBuilder sb = new StringBuilder();
		if (gameSkipped || gameResult == null || !gameResult.usable()) {
			sb.append("You have not chosen a game yet, so CTRMap will open with nothing loaded.\n\n"
					+ "You can look around, and run Options > Setup wizard whenever your game is\n"
					+ "unpacked and ready.");
			next.setText("Close");
		} else {
			sb.append("Game\n    ").append(gameResult.gameName())
					.append("\n    ").append(shorten(gameField.getText())).append("\n\n");
			sb.append("Working folder\n    ").append(shorten(wsField.getText())).append("\n\n");
			if (!emuSkipped && !emuField.getText().trim().isEmpty()) {
				sb.append("Emulator\n    ").append(shorten(emuField.getText())).append("\n\n");
			}
			sb.append("CTRMap will now make a pristine backup of the parts of the game it can"
					+ " edit, then load the zone list. This takes a few seconds.");
			next.setText("Set up CTRMap");
		}
		setProse(finishSummary, sb.toString());
		next.setEnabled(true);
	}

	/** Replaces the text of a {@link #prose} component. */
	private static void setProse(JComponent c, String text) {
		((javax.swing.JTextArea) c).setText(text == null ? "" : text);
	}

	// ---- the work ---------------------------------------------------------

	private void doFinish() {
		prefs().putBoolean(PREF_SUPPRESS, suppress.isSelected());
		if (gameSkipped || gameResult == null || !gameResult.usable()) {
			dispose();
			return;
		}
		final String gamePath = gameField.getText().trim();
		final String wsPath = wsField.getText().trim();
		File ws = new File(wsPath);
		if (!ws.isDirectory() && !ws.mkdirs()) {
			JOptionPane.showMessageDialog(this,
					"CTRMap could not create that working folder:\n  " + wsPath
					+ "\n\nChoose somewhere you have permission to write, such as a folder"
					+ "\ninside your user folder.",
					"Cannot create folder", JOptionPane.ERROR_MESSAGE);
			showStep(STEP_WORKSPACE);
			return;
		}
		//a workspace carrying a pristine backup of a DIFFERENT game folder would
		//silently mis-report what the user has changed, forever
		Workspace.WORKSPACE_PATH = wsPath;
		settleBackup(this, gamePath, new Runnable() {
			@Override
			public void run() {
				showStep(STEP_WORKSPACE);
			}
		}, new Runnable() {
			@Override
			public void run() {
				makeBackupAndFinish(gamePath, wsPath);
			}
		});
	}

	/** Takes the pristine backup off the UI thread, then loads the game. */
	private void makeBackupAndFinish(final String gamePath, final String wsPath) {
		setButtonsBusy(true);
		finishBar.setVisible(true);
		finishBar.setIndeterminate(true);
		finishStatus.setText("Making a pristine backup of your game's editable parts...");

		new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() {
				//done off the UI thread on purpose: this copies a few hundred
				//megabytes, and doing it inside validate() froze the whole app
				Workspace.GAMEDIR_PATH = gamePath;
				Workspace.WORKSPACE_PATH = wsPath;
				Workspace.game = gameResult.game;
				Workspace.snapshotOriginals();
				return null;
			}

			@Override
			protected void done() {
				finishBar.setIndeterminate(false);
				finishBar.setVisible(false);
				try {
					get(); //without this, a backup that threw looks made and setup carries on without one
				} catch (Exception ex) {
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					setButtonsBusy(false);
					finishStatus.setText(BLANK);
					JOptionPane.showMessageDialog(SetupWizard.this, "The pristine backup was not made:\n" + cause
							+ "\n\nPick a working folder CTRMap can write to and try again.", "Backup failed", JOptionPane.ERROR_MESSAGE);
					showStep(STEP_WORKSPACE);
					return;
				}
				//Offered here, after the dump is accepted and the workspace exists,
				//and BEFORE the zone list loads - this is the last moment the game
				//folder is known to be untouched by this editor. The existing
				//snapshot covers only the archives CTRMap writes; the vault is the
				//whole game, outside the workspace, verifiable, and restorable one
				//file at a time. It never blocks setup: a workspace with no vault is
				//still a usable workspace, and the user is told if it did not happen.
				try {
					java.io.File dump = new java.io.File(gamePath);
					ctrmap.vault.VaultUi.offer(SetupWizard.this, dump, dirSize(dump));
				} catch (RuntimeException vex) {
					//a vault that blew up must not cost the user their finished setup
					ctrmap.Ui.error(SetupWizard.this,
							"The pristine copy could not be kept:\n" + vex
							+ "\n\nSetup itself finished - you can carry on working.",
							"Backup not kept");
				}
				finishStatus.setText("Loading the zone list...");
				completeSetup();
			}
		}.execute();
	}

	/** Runs the real load and checks that it actually produced something. */
	private void completeSetup() {
		Workspace.validate(this, false);
		//Workspace.valid is set BEFORE the archives are read, so it says nothing
		//about whether this worked. The only honest test is what the user can
		//see: are there zones in the list?
		int zones = 0;
		try {
			zones = CtrmapMainframe.mZonePnl.getLoadedZoneCount();
		} catch (RuntimeException ex) {
			zones = 0;
		}
		setButtonsBusy(false);
		if (!Workspace.valid || zones == 0) {
			finishStatus.setText(BLANK);
			JOptionPane.showMessageDialog(this,
					"CTRMap read that folder but found no maps in it.\n\n"
					+ "The unpacked game is probably incomplete - unpacking sometimes stops\n"
					+ "early without saying so. Try unpacking your game again, then point\n"
					+ "CTRMap at the new folder.",
					"No maps found", JOptionPane.ERROR_MESSAGE);
			showStep(STEP_GAME);
			return;
		}
		Workspace.saveWorkspace();
		finished = true;
		dispose();
		JOptionPane.showMessageDialog(getOwner(),
				gameResult.gameName() + " is loaded - " + zones + " maps.\n\n"
				+ "Open the \"Zone Loader\" tab and pick one from the dropdown to start editing.",
				"CTRMap is ready", JOptionPane.INFORMATION_MESSAGE);
	}

	private void doCancel() {
		//nothing has been written yet unless Finish ran, so cancelling is free
		prefs().putBoolean(PREF_SUPPRESS, suppress.isSelected());
		dispose();
	}

	private void searchForDumps() {
		findForMe.setEnabled(false);
		findForMe.setText("Looking...");
		new SwingWorker<List<File>, Void>() {
			@Override
			protected List<File> doInBackground() {
				return DumpCheck.findLikelyDumps(4000);
			}

			@Override
			protected void done() {
				findForMe.setEnabled(true);
				findForMe.setText("Find it for me");
				List<File> hits = new ArrayList<>();
				try {
					hits = get();
				} catch (Exception ex) {
					//treated as "found nothing"
				}
				if (hits.isEmpty()) {
					JOptionPane.showMessageDialog(SetupWizard.this,
							"CTRMap looked in your Desktop, Downloads, Documents and emulator\n"
							+ "folders and did not find an unpacked game.\n\n"
							+ "If you have one somewhere else, use Browse to point at it.",
							"Nothing found", JOptionPane.INFORMATION_MESSAGE);
					return;
				}
				if (hits.size() == 1) {
					gameField.setText(hits.get(0).getAbsolutePath());
					revalidateGame();
					return;
				}
				Object pick = JOptionPane.showInputDialog(SetupWizard.this,
						"CTRMap found more than one unpacked game. Which one?",
						"Choose your game", JOptionPane.QUESTION_MESSAGE, null,
						hits.toArray(), hits.get(0));
				if (pick != null) {
					gameField.setText(pick.toString());
					revalidateGame();
				}
			}
		}.execute();
	}

	private void setButtonsBusy(boolean busy) {
		next.setEnabled(!busy);
		back.setEnabled(!busy && step > STEP_WELCOME);
		skip.setEnabled(!busy);
		cancel.setEnabled(!busy);
	}

	// ---- small helpers ----------------------------------------------------

	private File pickFolder(String title, String start) {
		JFileChooser fc = new JFileChooser(start == null || start.trim().isEmpty() ? null : start);
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fc.setDialogTitle(title);
		return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
	}

	private static boolean isInside(File child, File parent) {
		try {
			String c = child.getCanonicalPath(), p = parent.getCanonicalPath();
			return c.equalsIgnoreCase(p) || c.toLowerCase().startsWith(p.toLowerCase() + File.separator);
		} catch (Exception ex) {
			return false;
		}
	}

	private static boolean isEmptyDir(File f) {
		String[] kids = f.list();
		return kids == null || kids.length == 0;
	}

	private static String shorten(String path) {
		return path == null ? "" : path; //the summary wraps, so show the whole path
	}

	/**
	 * Settles the pristine backup, then sets up - or does not, when the user
	 * would rather keep the backup they have and pick another working folder,
	 * in which case setup goes back a step having touched nothing.
	 *
	 * <p>Word for word what {@link #doFinish} used to do inline. It is out
	 * here, static, and takes both sides as things it can be handed, because
	 * {@code doFinish} needs the whole wizard - a JDialog a headless suite
	 * cannot build, a card layout, a SwingWorker. {@link #backupBelongsHere}
	 * was reachable, but nothing could reach the finish ACTING on its answer,
	 * and a mutation sweep proved it: inverting the refusal, so that a wizard
	 * told to leave the other game's backup alone set up on it anyway, left the
	 * whole battery green. Handing the setting-up in rather than returning a
	 * flag keeps that decision out here whole: a flag would leave the wizard
	 * holding an {@code if} of its own, in the same unreachable place, deciding
	 * the same thing.
	 *
	 * @param goBackAndPickAnother what to do instead of setting up - in the
	 * wizard, returning to the working-folder step
	 * @param setUp what to do once the backup question is settled - in the
	 * wizard, taking the pristine backup and loading the game
	 */
	public static void settleBackup(java.awt.Component parent, String gamePath, Runnable goBackAndPickAnother, Runnable setUp) {
		if (!backupBelongsHere(parent, gamePath)) {
			goBackAndPickAnother.run();
			return;
		}
		setUp.run();
	}

	/**
	 * Whether setup may go ahead with the pristine backup already sitting in
	 * the chosen working folder. False means the user would rather pick another
	 * folder, and setup must go back a step without touching anything.
	 *
	 * <p>A backup taken from a DIFFERENT game folder is worse than no backup:
	 * CTRMap works out what the user changed by diffing against it, and cuts
	 * donor buildings out of it, so both answers come from the wrong game and
	 * nothing ever says so.
	 *
	 * <p>Static, and asking through {@link Ui}, so the decision can be driven
	 * without a window. It lived inside {@link #doFinish}, which needs the
	 * whole wizard - a JDialog, a card layout, a SwingWorker - and asked with a
	 * modal {@code JOptionPane} that no test can answer. Both branches went
	 * unmeasured; keeping the foreign backup is silent, and discarding a good
	 * one throws away the only record of what the game shipped with.
	 */
	public static boolean backupBelongsHere(java.awt.Component parent, String gamePath) {
		if (Workspace.snapshotIsForeign(gamePath)) {
			int r = Ui.confirm(parent,
					"That working folder already holds a backup of a different game folder:\n  "
					+ shorten(Workspace.snapshotSourcePath())
					+ "\n\nCTRMap compares your edits against that backup to work out"
					+ "\nwhat you changed, so keeping it would give the wrong answer."
					+ "\n\nReplace the backup with one taken from your current game folder?"
					+ "\n(Choose No to go back and pick a different working folder.)",
					"This folder belongs to another game", JOptionPane.YES_NO_OPTION);
			if (r != JOptionPane.YES_OPTION) {
				return false;
			}
			Workspace.discardSnapshot();
		}
		return true;
	}

	/** Bytes under a folder, for the vault's size question. Never throws. */
	static long dirSize(java.io.File f) {
		if (f == null || !f.exists()) {
			return 0;
		}
		if (f.isFile()) {
			return f.length();
		}
		long n = 0;
		java.io.File[] kids = f.listFiles();
		if (kids != null) {
			for (java.io.File k : kids) {
				n += dirSize(k);
			}
		}
		return n;
	}

	private static String esc(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String wrap(String s) {
		return s == null || s.isEmpty() ? BLANK
				: "<html><body style='width:566px'>" + esc(s) + "</body></html>";
	}

	private static JPanel column() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		return p;
	}

	private static JComponent gap(int h) {
		return (JComponent) Box.createVerticalStrut(h);
	}

	/**
	 * A wrapped paragraph of explanation.
	 *
	 * <p>A JTextArea rather than an HTML JLabel on purpose: a JLabel sizes itself
	 * to one line and only wraps if a CSS width happens to take, which is fragile
	 * and silently produced a sentence running off the edge of the dialog. A text
	 * area wraps to whatever width it is given, every time.
	 */
	private static JComponent prose(String text) {
		javax.swing.JTextArea a = new javax.swing.JTextArea(text) {
			@Override
			public Dimension getMaximumSize() {
				//BoxLayout stretches anything whose maximum height is unbounded,
				//which spreads a few short paragraphs over the whole dialog
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		a.setEditable(false);
		a.setOpaque(false);
		a.setFocusable(false);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setBorder(null);
		a.setFont(new JLabel().getFont());
		a.setAlignmentX(0f);
		return a;
	}

	/** One emphasised line - the thing on the step the user must not miss. */
	private static JComponent lede(String text) {
		JComponent c = prose(text);
		c.setFont(c.getFont().deriveFont(Font.BOLD, c.getFont().getSize2D() + 1f));
		return c;
	}

	/** DocumentListener with one method instead of three. */
	private abstract static class SimpleDocListener implements javax.swing.event.DocumentListener {

		public abstract void changed();

		@Override
		public void insertUpdate(javax.swing.event.DocumentEvent e) {
			SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() {
					changed();
				}
			});
		}

		@Override
		public void removeUpdate(javax.swing.event.DocumentEvent e) {
			insertUpdate(e);
		}

		@Override
		public void changedUpdate(javax.swing.event.DocumentEvent e) {
			insertUpdate(e);
		}
	}
}
