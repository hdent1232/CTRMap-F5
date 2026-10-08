package ctrmap.humaninterface;

import java.awt.event.ActionListener;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;

/**
 * The World Editor's tool row: the ten tool buttons, the "Current tool"
 * label, Undo/Redo for tile edits and the 2D/3D view toggle.
 *
 * <p>It OWNS these widgets. They were sixteen public statics of the main
 * window, four of them reached into from other files - the Set button by
 * TileEditForm, the label by the input manager, Undo/Redo by TileUndo - and
 * the rest reachable by anything that static-imported the window. What
 * anyone outside needs is offered by name instead: {@link #selectSetTool},
 * {@link #selectPaintTool}, {@link #setView3D}; the undo buttons follow
 * TileUndo on their own.
 *
 * <p>The row knows nothing about the window. Who makes the tool when a
 * button is pressed, and what the view toggle does, are handed in - which is
 * what lets MainframeShapeTest build it with no display and read it back.
 */
public class WorldEditorToolbar extends JToolBar {

	/** {action command, what the label calls it, icon prefix, tooltip}, in row order. */
	private static final String[][] TOOLS = {
		{"edit", "Edit", "_tool_edit", "Edit tool - click a tile to load its settings"},
		{"set", "Set", "_tool_set", "Set tool - paint the panel's settings onto tiles"},
		{"fill", "Fill", "_tool_fill", "Fill tool - drag a box to fill tiles with settings"},
		{"cam", "Camera", "_tool_cam", "Camera tool - place and edit camera zones"},
		{"prop", "Prop", "_tool_prop", "Prop tool - place and edit map props (trees, signs)"},
		{"npc", "NPC", "_tool_npc", "NPC tool - place and edit overworld NPCs"},
		{"warp", "Warp", "_tool_warp", "Warp tool - place and edit warps (doors, stairs)"},
		{"trigger", "Trigger", "_tool_trigger", "Trigger tool - place script triggers (step-on events)"},
		{"paint", "Map Builder", "_tool_paint", "Map Builder - edit this zone's map with terrain brushes, elevation, buildings and decor, directly on the map view. Only tiles you touch are rebuilt. (The brush-icon Set tool instead paints tile TYPES onto the existing map.)"},
		{"geo", "Geometry", "_tool_geo", "Geometry tool - drag a box on the map to select its 3D geometry, then move/duplicate/delete it, or copy it as a prefab and stamp it elsewhere"},
	};

	private final JRadioButton[] tools = new JRadioButton[TOOLS.length];
	private final JLabel currentTool = new JLabel("Current tool: Edit");
	private final JButton undo = new JButton("↶ Undo");
	private final JButton redo = new JButton("↷ Redo");
	private final JToggleButton view3D = new JToggleButton("3D view");
	private final JToggleButton fogOff = new JToggleButton("Fog off");

	/**
	 * @param toolSwitch gets each tool button's action command ("edit", "set",
	 * "fill", "cam", "prop", "npc", "warp", "trigger", "paint", "geo") when the
	 * user picks it: the input manager, which makes the tool
	 * @param viewToggle what pressing "3D view" does. The window decides from
	 * what it is actually showing and calls {@link #setView3D} back, so the
	 * toggle can never disagree with the view
	 */
	public WorldEditorToolbar(ActionListener toolSwitch, Runnable viewToggle, Runnable fogToggle,
			UndoHistory history) {
		if (history == null) {
			throw new IllegalArgumentException("the tool row must be handed the zone's history its undo buttons"
				+ " take back from");
		}
		this.history = history;
		ButtonGroup group = new ButtonGroup();
		for (int i = 0; i < TOOLS.length; i++) {
			String[] t = TOOLS[i];
			JRadioButton b = ctrmap.humaninterface.Forms.createGraphicalButton(t[2]);
			b.setActionCommand(t[0]);
			b.setToolTipText(t[3]);
			b.getAccessibleContext().setAccessibleName(t[1]);
			b.addActionListener(e -> currentTool.setText("Current tool: " + t[1]));
			b.addActionListener(toolSwitch);
			group.add(b);
			add(b);
			tools[i] = b;
		}
		tools[0].setSelected(true);
		add(currentTool);

		//undo/redo for EVERY edit to the open zone - tiles, entities, the painter - ON the World
		//Editor where they belong (the panel's Ctrl+Z / Ctrl+Y keep working; this is the primary
		//home). It was the tile history alone (F209).
		addSeparator();
		undo.setFocusable(false);
		redo.setFocusable(false);
		undo.addActionListener(e -> history.act(this, true));
		redo.addActionListener(e -> history.act(this, false));
		add(undo);
		add(redo);
		history.addListener(this::refreshUndoRedo);
		refreshUndoRedo();

		//the 2D/3D view switch, ALWAYS visible and valid for every tool
		addSeparator();
		view3D.setToolTipText("Show the map in 3D (fly with WASD + drag; the Map Builder updates it live). F2 = 2D, F3 = 3D.");
		view3D.setFocusable(false);
		view3D.addActionListener(e -> viewToggle.run());
		add(view3D);

		//NEXT TO THE VIEW SWITCH BECAUSE IT IS ONE. An area's fog is part of the
		//area - a cave really is black by 360 units, a route really does haze out
		//at 4000 - and drawing it honestly is what makes the editor show what the
		//player will see. It also makes some areas impossible to work in. This hides
		//it for looking at, and writes nothing: the same reason Fog & lighting is a
		//separate thing from this button.
		fogOff.setToolTipText("Hide this area\u0027s fog in the 3D view so you can see what you are editing."
			+ " Viewing only - it changes nothing in the zone and nothing that gets deployed.");
		fogOff.setFocusable(false);
		fogOff.addActionListener(e -> fogToggle.run());
		add(fogOff);
	}

	/** The window says which view is up (F2/F3, or its answer to the toggle). */
	public void setView3D(boolean on) {
		view3D.setSelected(on);
	}

	/** The window says whether the view is hiding fog, the same way it does for 2D/3D. */
	public void setFogSuppressed(boolean suppressed) {
		fogOff.setSelected(suppressed);
	}

	/** Map > Map Builder: the painter is a tool here, so pick it. */
	public void selectPaintTool() {
		tool("paint").doClick();
	}

	/** Picking a tile template in TileEditForm switches to the Set tool. */
	public void selectSetTool() {
		tool("set").doClick();
	}

	/** What the label says right now, e.g. "Current tool: Edit". */
	public String currentToolText() {
		return currentTool.getText();
	}

	private JRadioButton tool(String command) {
		for (JRadioButton b : tools) {
			if (command.equals(b.getActionCommand())) {
				return b;
			}
		}
		throw new IllegalArgumentException("no tool button for " + command);
	}

	/** The zone's history these buttons take back from and put again. */
	private final UndoHistory history;

	/** Enabled exactly when there is something to do, saying what (the history calls this on every change). */
	private void refreshUndoRedo() {
		undo.setEnabled(history.canUndo());
		redo.setEnabled(history.canRedo());
		undo.setToolTipText(history.canUndo() ? "Undo " + history.undoName() + " (Ctrl+Z)" : "Nothing to undo (Ctrl+Z)");
		redo.setToolTipText(history.canRedo() ? "Redo " + history.redoName() + " (Ctrl+Y)" : "Nothing to redo (Ctrl+Y)");
	}
}
