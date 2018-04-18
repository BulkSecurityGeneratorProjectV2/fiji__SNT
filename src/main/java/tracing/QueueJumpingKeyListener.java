/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2018 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package tracing;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.SwingUtilities;

import org.scijava.util.PlatformUtils;
import org.scijava.vecmath.Point3d;

import ij3d.Content;
import ij3d.DefaultUniverse;
import ij3d.Image3DUniverse;
import ij3d.behaviors.InteractiveBehavior;
import ij3d.behaviors.Picker;
import tracing.gui.GuiUtils;
import tracing.gui.ShollAnalysisDialog;

class QueueJumpingKeyListener implements KeyListener {

	private final SimpleNeuriteTracer tracerPlugin;
	private final InteractiveTracerCanvas canvas;
	private final Image3DUniverse univ;

	private final ArrayList<KeyListener> listeners = new ArrayList<>();
	private final boolean mac;

	private static final int DOUBLE_PRESS_INTERVAL = 300; // ms
	private long timeKeyDown = 0; // last time key was pressed
	private int lastKeyPressedCode;

	/* Define which keys are always parsed by IJ listeners */
	private static final int[] W_KEYS = new int[] { //
		KeyEvent.VK_EQUALS, KeyEvent.VK_MINUS, KeyEvent.VK_UP, KeyEvent.VK_DOWN, // zoom
																																							// keys
		KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, // navigation
																																								// keys
		KeyEvent.VK_L, // Command Finder
		KeyEvent.VK_PLUS, KeyEvent.VK_LESS, KeyEvent.VK_GREATER, KeyEvent.VK_TAB // extra
																																							// navigation/zoom
																																							// keys
	};

	public QueueJumpingKeyListener(final SimpleNeuriteTracer tracerPlugin,
		final InteractiveTracerCanvas canvas)
	{
		this.tracerPlugin = tracerPlugin;
		this.canvas = canvas;
		mac = PlatformUtils.isMac();
		univ = null;
	}

	public QueueJumpingKeyListener(final SimpleNeuriteTracer tracerPlugin,
		final Image3DUniverse univ)
	{
		this.tracerPlugin = tracerPlugin;
		this.canvas = null;
		mac = PlatformUtils.isMac();
		this.univ = univ;
		univ.addInteractiveBehavior(new PointSelectionBehavior(univ));
	}

	@Override
	public void keyPressed(final KeyEvent e) {

		if (!tracerPlugin.isUIready() || (canvas != null && canvas
			.isEventsDisabled()))
		{
			waiveKeyPress(e);
			return;
		}

		// NB: we don't want accidental keystrokes to interfere with SNT tasks
		// so we'll block any keys from reaching other listeners, unless: 1)
		// the keystroke is white-listed, 2) the user pressed a modifier key,
		// 3) it is a numeric keypad or 'action' (eg, fn) key
		final int keyCode = e.getKeyCode();
		final boolean doublePress = isDoublePress(e);

		if (keyCode == KeyEvent.VK_SPACE) {// IJ's pan tool shortcut
			tracerPlugin.panMode = true;
			waiveKeyPress(e);
			return;
		}
		else if (keyCode == KeyEvent.VK_ESCAPE) {
			if (doublePress) tracerPlugin.getUI().reset();
			else tracerPlugin.getUI().abortCurrentOperation();
			e.consume();
			return;
		}
		else if (keyCode == KeyEvent.VK_ENTER) {
			tracerPlugin.getUI().toFront();
			e.consume();
			return;
		}
		else if (keyCode == KeyEvent.VK_4) {
			tracerPlugin.unzoomAllPanes();
			e.consume();
			return;
		}
		else if (keyCode == KeyEvent.VK_5) {
			tracerPlugin.zoom100PercentAllPanes();
			e.consume();
			return;
		}
		else if (Arrays.stream(W_KEYS).anyMatch(i -> i == keyCode)) {
			waiveKeyPress(e);
			return;
		}

		final char keyChar = e.getKeyChar();
		final int modifiers = e.getModifiersEx();
		final boolean shift_down = (modifiers & InputEvent.SHIFT_DOWN_MASK) > 0;
		final boolean control_down = (modifiers & InputEvent.CTRL_DOWN_MASK) > 0;
		final boolean alt_down = (modifiers & InputEvent.ALT_DOWN_MASK) > 0;
		final boolean shift_pressed = (keyCode == KeyEvent.VK_SHIFT);
		final boolean join_modifier_pressed = mac ? keyCode == KeyEvent.VK_ALT
			: keyCode == KeyEvent.VK_CONTROL;

		// SNT Hotkeys that do not override defaults
		if (shift_down && (control_down || alt_down) &&
			(keyCode == KeyEvent.VK_A))
		{
			startShollAnalysis();
			e.consume();
		}

		// SNT Keystrokes that override IJ defaults. These
		// are common to both tracing and edit mode
		else if (canvas != null && (shift_pressed || join_modifier_pressed)) {

			// This case is just so that when someone starts holding down
			// the modified immediately see the effect, rather than having
			// to wait for the next mouse move event
			canvas.fakeMouseMoved(shift_pressed, join_modifier_pressed);
			e.consume();
		}
		else if (keyChar == 'g' || keyChar == 'G') {
			// IJ1 built-in: Shift+G Take a screenshot
			selectNearestPathToMousePointer(shift_down);
			e.consume();
		}

		// Exceptions above aside, do not intercept any other
		// keystrokes that include a modifier key
		else if (shift_down || control_down || alt_down) {
			waiveKeyPress(e);
			return;
		}

		// Hotkeys common to both tracing and edit mode
		else if (keyChar == 'a' || keyChar == 'A') {
			// IJ1 built-in: Select All
			tracerPlugin.getUI().togglePathsChoice();
			e.consume();
		}

		else if (keyChar == 'z' || keyChar == 'Z') {
			// IJ1 built-in: Undo
			tracerPlugin.getUI().togglePartsChoice();
			e.consume();
		}

		// Keystrokes exclusive to edit mode (NB: Currently these only work
		// with InteractiveCanvas). We'll skip hasty keystrokes to avoid
		// mis-editing
		else if (canvas != null && canvas.isEditMode() && !doublePress) {
			if (keyCode == KeyEvent.VK_BACK_SPACE || keyCode == KeyEvent.VK_DELETE ||
				keyChar == 'd' || keyChar == 'D')
			{
				canvas.deleteEditingNode(false);
			}
			else if (keyCode == KeyEvent.VK_INSERT || keyChar == 'i' ||
				keyChar == 'I')
			{
				canvas.apppendLastPositionToEditingNode(false);
			}
			else if (keyChar == 'm' || keyChar == 'M') {
				canvas.moveEditingNodeToLastPosition(false);
			}
			else if (keyChar == 'b' || keyChar == 'B') {
				canvas.assignLastZPositionToEditNode(false);
			}
			e.consume();
			return;
		}

		// Keystrokes exclusive to tracing mode
		else if (keyChar == 'y' || keyChar == 'Y') {
			// IJ1 built-in: ROI Properties...
			if (tracerPlugin.getUI().finishOnDoubleConfimation && doublePress)
				tracerPlugin.finishedPath();
			else tracerPlugin.confirmTemporary();
			e.consume();
		}
		else if (keyChar == 'n' || keyChar == 'N') {
			// IJ1 built-in: New Image
			if (tracerPlugin.getUI().discardOnDoubleCancellation && doublePress)
				tracerPlugin.cancelPath();
			else tracerPlugin.cancelTemporary();
			e.consume();
		}
		else if (keyChar == 'c' || keyChar == 'C') {
			// IJ1 built-in: Copy
			if (tracerPlugin.getUIState() == NeuriteTracerResultsDialog.PARTIAL_PATH)
				tracerPlugin.cancelPath();
			else if (doublePress) tracerPlugin.getUI().abortCurrentOperation();
			e.consume();
		}
		else if (keyChar == 'f' || keyChar == 'F') {
			// IJ1 built-in: Fill
			tracerPlugin.finishedPath();
			e.consume();
		}
		else if (keyChar == 'h' || keyChar == 'H') {
			// IJ1 built-in: Histogram
			tracerPlugin.getUI().toggleHessian();
			e.consume();
		}
		else if (keyChar == 'i' || keyChar == 'I') {
			// IJ1 built-in: Get Info
			tracerPlugin.getUI().toggleFilteredImgTracing();
			e.consume();
		}
		else if ((keyChar == 'm' || keyChar == 'M') && canvas != null) {
			// IJ1 built-in: Measure
			canvas.clickAtMaxPoint();
			e.consume();
		}
		else if (keyChar == 's' || keyChar == 's') {
			// IJ1 built-in: Save
			tracerPlugin.toogleSnapCursor();
			e.consume();
		}

		else if (e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD || e
			.isActionKey())
		{
			waiveKeyPress(e);
		}

		// Uncomment below to pass on any other key press to existing listeners
		// else waiveKeyPress(e);

	}

	private void startShollAnalysis() {
		if (canvas != null) {
			canvas.startShollAnalysis();
			return;
		}
		new Thread(() -> {
			final NearPoint np = getNearestPickedPoint();
			if (np == null) return;
			final ShollAnalysisDialog sd = new ShollAnalysisDialog(
				"Sholl analysis for tracing of " + tracerPlugin.getImagePlus()
					.getTitle(), np.pathPointX, np.pathPointY, np.pathPointZ,
				tracerPlugin);
			sd.toFront();
		}).start();
	}

	private NearPoint getNearestPickedPoint() {
		final Point p = univ.getCanvas().getMousePosition();
		if (p == null) return null;
		final Picker picker = univ.getPicker();
		final Content c = picker.getPickedContent(p.x, p.y);
		if (null == c) return null;
		final Point3d point = picker.getPickPointGeometry(c, p.x, p.y);
		final double diagonalLength = tracerPlugin.getStackDiagonalLength();
		final NearPoint np = tracerPlugin.getPathAndFillManager()
			.nearestPointOnAnyPath(point.x, point.y, point.z, diagonalLength);
		if (np == null) {
			SNT.error("BUG: No nearby path was found within " + diagonalLength +
				" of the pointer");
		}
		return np;
	}

	private void selectNearestPathToMousePointer(final boolean shift_down) {
		if (canvas != null) {
			canvas.selectNearestPathToMousePointer(shift_down);
			return;
		}
		new Thread(() -> {
			final NearPoint np = getNearestPickedPoint();
			if (np == null) {
				return;
			}
			final Path path = np.getPath();
		tracerPlugin.selectPath(path, shift_down);
		}).start();
	}

	private void waiveKeyPress(final KeyEvent e) {
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyPressed(e);
		}
	}

	@Override
	public void keyReleased(final KeyEvent e) {
		if (e.getKeyCode() == KeyEvent.VK_SPACE && canvas != null && !canvas.isEventsDisabled()) { // IJ's
																																							// pan
																																							// tool
																																							// shortcut
			tracerPlugin.panMode = false;
		}
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyReleased(e);
		}
	}

	@Override
	public void keyTyped(final KeyEvent e) {
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyTyped(e);
		}
	}

	private boolean isDoublePress(final KeyEvent ke) {
		if (lastKeyPressedCode == ke.getKeyCode() && ((ke.getWhen() -
			timeKeyDown) < DOUBLE_PRESS_INTERVAL)) return true;
		timeKeyDown = ke.getWhen();
		lastKeyPressedCode = ke.getKeyCode();
		return false;
	}

	/**
	 * This method should add the other key listeners in 'laterKeyListeners' that
	 * will be called for 'source' if this key listener isn't interested in the
	 * key press.
	 */
	public void addOtherKeyListeners(final KeyListener[] laterKeyListeners) {
		final ArrayList<KeyListener> newListeners = new ArrayList<>(Arrays.asList(
			laterKeyListeners));
		listeners.addAll(newListeners);
	}

	private class PointSelectionBehavior extends InteractiveBehavior {

		public PointSelectionBehavior(final DefaultUniverse univ) {
			super(univ);
		}

		@Override
		public void doProcess(final KeyEvent e) {
			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					keyPressed(e);
				}
			});
		}

		@Override
		public void doProcess(final MouseEvent me) {

			if (!tracerPlugin.isUIready() || !me.isControlDown() || me
				.getID() != MouseEvent.MOUSE_PRESSED)
			{
				super.doProcess(me);
				return;
			}

			final Picker picker = univ.getPicker();
			final Content c = picker.getPickedContent(me.getX(), me.getY());
			if (null == c) {
				new GuiUtils(univ.getCanvas()).tempMsg("No content picked!");
				return;
			}

			final Point3d point = picker.getPickPointGeometry(c, me.getX(), me
				.getY());
			final boolean joiner_modifier_down = mac ? ((me.getModifiersEx() &
				InputEvent.ALT_DOWN_MASK) != 0) : ((me.getModifiersEx() &
					InputEvent.CTRL_DOWN_MASK) != 0);

			tracerPlugin.clickForTrace(point, joiner_modifier_down);

		}
	}
}
