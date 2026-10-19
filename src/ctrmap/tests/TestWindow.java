package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The suites' main window: one object per suite run, built without its frame.
 *
 * <p>The window was a class of statics (F007), and the suites set and read its fields as the program's
 * globals. It is an object now and the program makes exactly one, in main. A suite that drives what the
 * window does - its menus, its actions, the panels it wires - drives this one; building it builds an
 * empty list and nothing else, so it needs no screen. Its frame and panels are whatever the suite hands
 * it, as before.
 *
 * <p>Not: a second static of the window's own - that would put the program's global back, one class
 * along. This is a suite's fixture, in the suites' package, and the program cannot reach it.
 */
final class TestWindow {

	private TestWindow() {
	}

	/** The window the suites in this run drive. */
	static final CtrmapMainframe W = new CtrmapMainframe();

	// ---- what the suites reach of it -------------------------------------------------------------
	//EVERY MEMBER OF THE WINDOW IS PRIVATE (F007): nothing in the program reaches into it, and its
	//fields existed public for the suites alone. A suite reaches one through the accessors below - one
	//per member a suite uses, written from the compiled window's signatures when it was made private;
	//a new one is the same three lines - which read it by reflection. TYPED, so a suite's code is
	//checked by the compiler as before;
	//RESOLVED WHEN THIS CLASS LOADS and checked against the type the suites were written for, so a
	//member renamed or retyped stops every suite at its first line, naming the member, rather than
	//failing twenty checks later as a null.

	/** The window's action table, which is private too: a menu item or button carries one under this key. */
	static final Class<?> AREA_ACTION = nested("AreaAction");

	/** The tooltip an area action declares. */
	static String tooltipOf(Object areaAction) {
		try {
			Field f = AREA_ACTION.getDeclaredField("tooltip");
			f.setAccessible(true);
			return (String) f.get(areaAction);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("the window's AreaAction no longer declares a tooltip: " + ex, ex);
		}
	}

	/** Sets a window field by name: the bench's fixtures, which install private state no accessor names. */
	static void field(String name, Object value) {
		write(field(name, null), value);
	}

	/** Reads a window field by name. */
	static Object field(String name) {
		return read(field(name, null));
	}

	private static final Field F_m3DDebugPanel = field("m3DDebugPanel", ctrmap.humaninterface.H3DRenderingPanel.class);

	static ctrmap.humaninterface.H3DRenderingPanel m3DDebugPanel() {
		return read(F_m3DDebugPanel);
	}

	static ctrmap.humaninterface.H3DRenderingPanel m3DDebugPanel(ctrmap.humaninterface.H3DRenderingPanel value) {
		return write(F_m3DDebugPanel, value);
	}

	private static final Field F_mCamEditForm = field("mCamEditForm", ctrmap.humaninterface.CameraEditForm.class);

	static ctrmap.humaninterface.CameraEditForm mCamEditForm() {
		return read(F_mCamEditForm);
	}

	static ctrmap.humaninterface.CameraEditForm mCamEditForm(ctrmap.humaninterface.CameraEditForm value) {
		return write(F_mCamEditForm, value);
	}

	private static final Field F_mScriptPnl = field("mScriptPnl", ctrmap.humaninterface.ScriptEditor.class);

	static ctrmap.humaninterface.ScriptEditor mScriptPnl() {
		return read(F_mScriptPnl);
	}

	static ctrmap.humaninterface.ScriptEditor mScriptPnl(ctrmap.humaninterface.ScriptEditor value) {
		return write(F_mScriptPnl, value);
	}

	private static final Field F_mCamScrollPane = field("mCamScrollPane", javax.swing.JScrollPane.class);

	static javax.swing.JScrollPane mCamScrollPane() {
		return read(F_mCamScrollPane);
	}

	static javax.swing.JScrollPane mCamScrollPane(javax.swing.JScrollPane value) {
		return write(F_mCamScrollPane, value);
	}

	private static final Field F_mCollEditPanel = field("mCollEditPanel", ctrmap.humaninterface.CollEditPanel.class);

	static ctrmap.humaninterface.CollEditPanel mCollEditPanel() {
		return read(F_mCollEditPanel);
	}

	static ctrmap.humaninterface.CollEditPanel mCollEditPanel(ctrmap.humaninterface.CollEditPanel value) {
		return write(F_mCollEditPanel, value);
	}

	private static final Field F_mGeoEditForm = field("mGeoEditForm", ctrmap.humaninterface.GeoEditForm.class);

	static ctrmap.humaninterface.GeoEditForm mGeoEditForm() {
		return read(F_mGeoEditForm);
	}

	static ctrmap.humaninterface.GeoEditForm mGeoEditForm(ctrmap.humaninterface.GeoEditForm value) {
		return write(F_mGeoEditForm, value);
	}

	private static final Field F_mMtxEditForm = field("mMtxEditForm", ctrmap.humaninterface.MatrixEditForm.class);

	static ctrmap.humaninterface.MatrixEditForm mMtxEditForm() {
		return read(F_mMtxEditForm);
	}

	static ctrmap.humaninterface.MatrixEditForm mMtxEditForm(ctrmap.humaninterface.MatrixEditForm value) {
		return write(F_mMtxEditForm, value);
	}

	private static final Field F_mMtxPanel = field("mMtxPanel", ctrmap.humaninterface.MapMatrixPanel.class);

	static ctrmap.humaninterface.MapMatrixPanel mMtxPanel() {
		return read(F_mMtxPanel);
	}

	static ctrmap.humaninterface.MapMatrixPanel mMtxPanel(ctrmap.humaninterface.MapMatrixPanel value) {
		return write(F_mMtxPanel, value);
	}

	private static final Field F_mNPCEditForm = field("mNPCEditForm", ctrmap.humaninterface.NPCEditForm.class);

	static ctrmap.humaninterface.NPCEditForm mNPCEditForm() {
		return read(F_mNPCEditForm);
	}

	static ctrmap.humaninterface.NPCEditForm mNPCEditForm(ctrmap.humaninterface.NPCEditForm value) {
		return write(F_mNPCEditForm, value);
	}

	private static final Field F_mPaintForm = field("mPaintForm", ctrmap.humaninterface.PaintForm.class);

	static ctrmap.humaninterface.PaintForm mPaintForm() {
		return read(F_mPaintForm);
	}

	static ctrmap.humaninterface.PaintForm mPaintForm(ctrmap.humaninterface.PaintForm value) {
		return write(F_mPaintForm, value);
	}

	private static final Field F_mPropEditForm = field("mPropEditForm", ctrmap.humaninterface.PropEditForm.class);

	static ctrmap.humaninterface.PropEditForm mPropEditForm() {
		return read(F_mPropEditForm);
	}

	static ctrmap.humaninterface.PropEditForm mPropEditForm(ctrmap.humaninterface.PropEditForm value) {
		return write(F_mPropEditForm, value);
	}

	private static final Field F_mTileEditForm = field("mTileEditForm", ctrmap.humaninterface.TileEditForm.class);

	static ctrmap.humaninterface.TileEditForm mTileEditForm() {
		return read(F_mTileEditForm);
	}

	static ctrmap.humaninterface.TileEditForm mTileEditForm(ctrmap.humaninterface.TileEditForm value) {
		return write(F_mTileEditForm, value);
	}

	private static final Field F_mTileMapPanel = field("mTileMapPanel", ctrmap.humaninterface.TileMapPanel.class);

	static ctrmap.humaninterface.TileMapPanel mTileMapPanel() {
		return read(F_mTileMapPanel);
	}

	static ctrmap.humaninterface.TileMapPanel mTileMapPanel(ctrmap.humaninterface.TileMapPanel value) {
		return write(F_mTileMapPanel, value);
	}

	private static final Field F_mTilemapScrollPane = field("mTilemapScrollPane", javax.swing.JScrollPane.class);

	static javax.swing.JScrollPane mTilemapScrollPane() {
		return read(F_mTilemapScrollPane);
	}

	static javax.swing.JScrollPane mTilemapScrollPane(javax.swing.JScrollPane value) {
		return write(F_mTilemapScrollPane, value);
	}

	private static final Field F_mTriggerEditForm = field("mTriggerEditForm", ctrmap.humaninterface.TriggerEditForm.class);

	static ctrmap.humaninterface.TriggerEditForm mTriggerEditForm() {
		return read(F_mTriggerEditForm);
	}

	static ctrmap.humaninterface.TriggerEditForm mTriggerEditForm(ctrmap.humaninterface.TriggerEditForm value) {
		return write(F_mTriggerEditForm, value);
	}

	private static final Field F_mWarpEditForm = field("mWarpEditForm", ctrmap.humaninterface.WarpEditForm.class);

	static ctrmap.humaninterface.WarpEditForm mWarpEditForm() {
		return read(F_mWarpEditForm);
	}

	static ctrmap.humaninterface.WarpEditForm mWarpEditForm(ctrmap.humaninterface.WarpEditForm value) {
		return write(F_mWarpEditForm, value);
	}

	private static final Field F_mZonePnl = field("mZonePnl", ctrmap.humaninterface.ZoneLoadingPanel.class);

	static ctrmap.humaninterface.ZoneLoadingPanel mZonePnl() {
		return read(F_mZonePnl);
	}

	static ctrmap.humaninterface.ZoneLoadingPanel mZonePnl(ctrmap.humaninterface.ZoneLoadingPanel value) {
		return write(F_mZonePnl, value);
	}

	private static final Field F_worldToolbar = field("worldToolbar", ctrmap.humaninterface.WorldEditorToolbar.class);

	static ctrmap.humaninterface.WorldEditorToolbar worldToolbar() {
		return read(F_worldToolbar);
	}

	static ctrmap.humaninterface.WorldEditorToolbar worldToolbar(ctrmap.humaninterface.WorldEditorToolbar value) {
		return write(F_worldToolbar, value);
	}

	private static final Method M_bindLoadedZone = method("bindLoadedZone", void.class, ctrmap.LoadedZone.class);

	static void bindLoadedZone(ctrmap.LoadedZone a0) {
		invoke(M_bindLoadedZone, a0);
	}

	private static final Method M_buildExtrasBar = method("buildExtrasBar", javax.swing.JToolBar.class);

	static javax.swing.JToolBar buildExtrasBar() {
		return TestWindow.<javax.swing.JToolBar>invoke(M_buildExtrasBar);
	}

	private static final Method M_buildExtrasTab = method("buildExtrasTab", javax.swing.JPanel.class, javax.swing.JComponent.class, javax.swing.JComponent.class);

	static javax.swing.JPanel buildExtrasTab(javax.swing.JComponent a0, javax.swing.JComponent a1) {
		return TestWindow.<javax.swing.JPanel>invoke(M_buildExtrasTab, a0, a1);
	}

	private static final Method M_buildGameDataTab = method("buildGameDataTab", javax.swing.JPanel.class, javax.swing.JComponent.class);

	static javax.swing.JPanel buildGameDataTab(javax.swing.JComponent a0) {
		return TestWindow.<javax.swing.JPanel>invoke(M_buildGameDataTab, a0);
	}

	private static final Method M_buildCollisionActionsBar = method("buildCollisionActionsBar", javax.swing.JToolBar.class);

	static javax.swing.JToolBar buildCollisionActionsBar() {
		return TestWindow.<javax.swing.JToolBar>invoke(M_buildCollisionActionsBar);
	}

	private static final Method M_buildMapActionsBar = method("buildMapActionsBar", javax.swing.JToolBar.class);

	static javax.swing.JToolBar buildMapActionsBar() {
		return TestWindow.<javax.swing.JToolBar>invoke(M_buildMapActionsBar);
	}

	private static final Method M_buildMenuBar = method("buildMenuBar", javax.swing.JMenuBar.class);

	static javax.swing.JMenuBar buildMenuBar() {
		return TestWindow.<javax.swing.JMenuBar>invoke(M_buildMenuBar);
	}

	private static final Method M_buildZoneActionsBar = method("buildZoneActionsBar", javax.swing.JToolBar.class);

	static javax.swing.JToolBar buildZoneActionsBar() {
		return TestWindow.<javax.swing.JToolBar>invoke(M_buildZoneActionsBar);
	}

	private static final Method M_buildZoneTab = method("buildZoneTab", javax.swing.JPanel.class, javax.swing.JComponent.class, ctrmap.LoadedZone.class, ctrmap.humaninterface.tools.ToolSelection.class);

	static javax.swing.JPanel buildZoneTab(javax.swing.JComponent a0, ctrmap.LoadedZone a1, ctrmap.humaninterface.tools.ToolSelection a2) {
		return TestWindow.<javax.swing.JPanel>invoke(M_buildZoneTab, a0, a1, a2);
	}

	private static final Method M_clearGameData = method("clearGameData", void.class);

	static void clearGameData() {
		invoke(M_clearGameData);
	}

	private static final Method M_deployAfterPack = method("deployAfterPack", void.class, java.io.File.class, java.io.File.class);

	static void deployAfterPack(java.io.File a0, java.io.File a1) {
		invoke(M_deployAfterPack, a0, a1);
	}

	private static final Method M_onWorkspaceOpened = method("onWorkspaceOpened", int.class, ctrmap.WorkspaceSession.class);

	static int onWorkspaceOpened(ctrmap.WorkspaceSession a0) {
		return TestWindow.<Integer>invoke(M_onWorkspaceOpened, a0);
	}

	private static final Method M_openChosenGr = method("openChosenGr", void.class, ctrmap.humaninterface.OpenEditors.class, java.lang.Runnable.class);

	static void openChosenGr(ctrmap.humaninterface.OpenEditors a0, java.lang.Runnable a1) {
		invoke(M_openChosenGr, a0, a1);
	}

	private static final Method M_openChosenMapMatrix = method("openChosenMapMatrix", void.class, java.io.File.class, java.lang.Runnable.class);

	static void openChosenMapMatrix(java.io.File a0, java.lang.Runnable a1) {
		invoke(M_openChosenMapMatrix, a0, a1);
	}

	private static final Method M_openMapMatrixFile = method("openMapMatrixFile", boolean.class, java.io.File.class);

	static boolean openMapMatrixFile(java.io.File a0) {
		return TestWindow.<Boolean>invoke(M_openMapMatrixFile, a0);
	}

	private static final Method M_showGameData = method("showGameData", void.class, java.lang.String.class, javax.swing.JComponent.class);

	static void showGameData(java.lang.String a0, javax.swing.JComponent a1) {
		invoke(M_showGameData, a0, a1);
	}

	private static final Method M_showInExtras = method("showInExtras", void.class, java.lang.String.class, javax.swing.JComponent.class);

	static void showInExtras(java.lang.String a0, javax.swing.JComponent a1) {
		invoke(M_showInExtras, a0, a1);
	}

	private static final Method M_switchToolUI = method("switchToolUI", void.class, javax.swing.JComponent.class);

	static void switchToolUI(javax.swing.JComponent a0) {
		invoke(M_switchToolUI, a0);
	}

	// ---- the reflection, once -------------------------------------------------------------------

	private static Class<?> nested(String name) {
		for (Class<?> c : CtrmapMainframe.class.getDeclaredClasses()) {
			if (c.getSimpleName().equals(name)) {
				return c;
			}
		}
		throw new IllegalStateException("the window declares no " + name + " - renamed or removed; the suites that use it"
				+ " must change with it");
	}

	/** One of the window's fields, of exactly {@code type} unless that is null. */
	static Field field(String name, Class<?> type) {
		Field f;
		try {
			f = CtrmapMainframe.class.getDeclaredField(name);
		} catch (NoSuchFieldException gone) {
			throw new IllegalStateException("the window has no field " + name + " - renamed or removed; the suites that"
					+ " reach it must change with it", gone);
		}
		if (type != null && f.getType() != type) {
			throw new IllegalStateException("the window's " + name + " is a " + f.getType().getName() + ", not the "
					+ type.getName() + " the suites were written for");
		}
		f.setAccessible(true);
		return f;
	}

	/** One of the window's methods, by its exact parameters, returning exactly {@code returns}. */
	static Method method(String name, Class<?> returns, Class<?>... params) {
		Method m;
		try {
			m = CtrmapMainframe.class.getDeclaredMethod(name, params);
		} catch (NoSuchMethodException gone) {
			throw new IllegalStateException("the window has no " + name + java.util.Arrays.toString(params)
					+ " - renamed, removed or re-parameterised; the suites that call it must change with it", gone);
		}
		if (m.getReturnType() != returns) {
			throw new IllegalStateException("the window's " + name + " returns " + m.getReturnType().getName()
					+ ", not the " + returns.getName() + " the suites were written for");
		}
		m.setAccessible(true);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static <T> T read(Field f) {
		try {
			return (T) f.get(W);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException("the window's " + f.getName() + " could not be read: " + ex, ex);
		}
	}

	private static <T> T write(Field f, T value) {
		try {
			f.set(W, value);
			return value;
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException("the window's " + f.getName() + " could not be set: " + ex, ex);
		}
	}

	/** Calls a window method as the suite's own call would: what it throws comes out as it was thrown. */
	@SuppressWarnings("unchecked")
	private static <T> T invoke(Method m, Object... args) {
		try {
			return (T) m.invoke(Modifier.isStatic(m.getModifiers()) ? null : W, args);
		} catch (InvocationTargetException thrown) {
			Throwable cause = thrown.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new IllegalStateException("the window's " + m.getName() + " threw " + cause, cause);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException("the window's " + m.getName() + " could not be called: " + ex, ex);
		}
	}
}
