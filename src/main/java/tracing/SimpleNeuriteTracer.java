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

import io.scif.services.DatasetIOService;

import java.awt.Color;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.KeyListener;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import net.imagej.Dataset;
import net.imagej.axis.Axes;
import net.imagej.legacy.LegacyService;

import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.app.StatusService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.vecmath.Color3f;
import org.scijava.vecmath.Point3d;
import org.scijava.vecmath.Point3f;

import amira.AmiraMeshDecoder;
import amira.AmiraParameters;
import features.ComputeCurvatures;
import features.GaussianGenerationCallback;
import features.TubenessProcessor;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.ImageRoi;
import ij.gui.NewImage;
import ij.gui.Overlay;
import ij.gui.StackWindow;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.plugin.ZProjector;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ij3d.Content;
import ij3d.Image3DUniverse;
import tracing.gui.GuiUtils;
import tracing.gui.SWCImportOptionsDialog;
import tracing.gui.SigmaPalette;
import tracing.gui.SwingSafeResult;
import tracing.hyperpanes.MultiDThreePanes;
import tracing.util.PointInImage;

/* Note on terminology:

   "traces" files are made up of "paths".  Paths are non-branching
   sequences of adjacent points (including diagonals) in the image.
   Branches and joins are supported by attributes of paths that
   specify that they begin on (or end on) other paths.

*/

public class SimpleNeuriteTracer extends MultiDThreePanes implements
	SearchProgressCallback, GaussianGenerationCallback, PathAndFillListener
{

	@Parameter
	private Context context;
	@Parameter
	protected StatusService statusService;
	@Parameter
	protected LegacyService legacyService;
	@Parameter
	protected LogService logService;
	@Parameter
	protected DatasetIOService datasetIOService;
	@Parameter
	protected ConvertService convertService;

	protected static boolean verbose = false; // FIXME: Use prefservice

	protected static final int MIN_SNAP_CURSOR_WINDOW_XY = 2;
	protected static final int MIN_SNAP_CURSOR_WINDOW_Z = 0;
	protected static final int MAX_SNAP_CURSOR_WINDOW_XY = 20;
	protected static final int MAX_SNAP_CURSOR_WINDOW_Z = 10;

	protected static final String startBallName = "Start point";
	protected static final String targetBallName = "Target point";
	protected static final int ballRadiusMultiplier = 5;

	private static final String OVERLAY_IDENTIFIER = "SNT-MIP-OVERLAY";

	protected PathAndFillManager pathAndFillManager;
	protected SNTPrefs prefs;
	private GuiUtils guiUtils;
	protected Image3DUniverse univ;
	protected Content imageContent;
	protected boolean use3DViewer;
	
	/* UI preferences */
	protected boolean useCompressedXML = true;
	volatile protected int cursorSnapWindowXY;
	volatile protected int cursorSnapWindowZ;
	volatile protected boolean autoCanvasActivation;
	volatile protected boolean panMode;
	volatile protected boolean snapCursor;
	volatile protected boolean unsavedPaths = false;

	/*
	 * Just for convenience, keep casted references to the superclass's
	 * InteractiveTracerCanvas objects:
	 */
	protected InteractiveTracerCanvas xy_tracer_canvas;
	protected InteractiveTracerCanvas xz_tracer_canvas;
	protected InteractiveTracerCanvas zy_tracer_canvas;

	/* Image properties */
	protected int width, height, depth;
	protected int imageType = -1;
	private boolean singleSlice;
	protected double x_spacing = 1;
	protected double y_spacing = 1;
	protected double z_spacing = 1;
	protected String spacing_units = "";
	protected boolean setupTrace = false;
	protected int channel;
	protected int frame;

	/* loaded pixels (main image) */
	protected byte[][] slices_data_b;
	protected short[][] slices_data_s;
	protected float[][] slices_data_f;
	volatile protected float stackMax = Float.MIN_VALUE;
	volatile protected float stackMin = Float.MAX_VALUE;

	/* The main auto-tracing thread */
	private TracerThread currentSearchThread = null;
	/* The thread for manual tracing */
	private ManualTracerThread manualSearchThread = null;

	/*
	 * Fields for tracing on secondary data: a filtered image.
	 * This can work in one of two ways: image is loaded into
	 * memory or we waive its file path to a third-party class
	 * that will parse it
	 */
	protected boolean doSearchOnFilteredData;
	protected float[][] filteredData;
	protected File filteredFileImage = null;
	protected boolean tubularGeodesicsTracingEnabled = false;
	protected TubularGeodesicsTracer tubularGeodesicsThread;


	/*
	 * pathUnfinished indicates that we have started to create a path, but not
	 * yet finished it (in the sense of moving on to a new path with a differen
	 * starting point.) FIXME: this may be redundant - check that.
	 */
	volatile boolean pathUnfinished = false;
	private Path editingPath; // Path being edited when in 'Edit Mode'

	/*
	 * For the original file info - needed for loading the corresponding labels
	 * file and checking if a "tubes.tif" file already exists:
	 */

	@Deprecated
	protected FileInfo file_info;

	/* Labels*/
	protected String[] materialList;
	byte[][] labelData;

	volatile boolean loading = false;
	volatile boolean lastStartPointSet = false;

	double last_start_point_x;
	double last_start_point_y;
	double last_start_point_z;

	Path endJoin;
	PointInImage endJoinPoint;

	/*
	 * If we've finished searching for a path, but the user hasn't confirmed
	 * that they want to keep it yet, temporaryPath is non-null and holds the
	 * Path we just searched out.
	 */

	// Any method that deals with these two fields should be synchronized.
	Path temporaryPath = null;
	Path currentPath = null;

	/* GUI */
	protected NeuriteTracerResultsDialog resultsDialog;
	protected boolean nonInteractiveSession = false;

	/* Deprecated */
	protected static final int DISPLAY_PATHS_SURFACE = 1;
	protected static final int DISPLAY_PATHS_LINES = 2;
	protected static final int DISPLAY_PATHS_LINES_AND_DISCS = 3;

	// This should only be assigned to when synchronized on this object
	// (FIXME: check that that is true)
	FillerThread filler = null;

	/* Colors */
	private static final Color DEFAULT_SELECTED_COLOR = Color.GREEN;
	protected static final Color DEFAULT_DESELECTED_COLOR = Color.MAGENTA;
	protected static final Color3f DEFAULT_SELECTED_COLOR3F = new Color3f(
		Color.GREEN);
	protected static final Color3f DEFAULT_DESELECTED_COLOR3F = new Color3f(
		Color.MAGENTA);
	protected Color3f selectedColor3f = DEFAULT_SELECTED_COLOR3F;
	protected Color3f deselectedColor3f = DEFAULT_DESELECTED_COLOR3F;
	protected ImagePlus colorImage;

	@Deprecated
	public Color selectedColor = DEFAULT_SELECTED_COLOR;
	@Deprecated
	public Color deselectedColor = DEFAULT_DESELECTED_COLOR;
	@Deprecated
	public boolean displayCustomPathColors = true;


	protected SimpleNeuriteTracer() {
		Context context = (Context) IJ.runPlugIn("org.scijava.Context",
				"");
		context.inject(this);
		pathAndFillManager = new PathAndFillManager(this);
		nonInteractiveSession = true;
		disableAstar(true);
	}

	public SimpleNeuriteTracer(final Context context, final ImagePlus sourceImage) {

		if (context == null) throw new NullContextException();
		if (sourceImage.getStackSize() == 0) throw new IllegalArgumentException(
			"Uninitialized image object");
		if (sourceImage.getType()==ImagePlus.COLOR_RGB) throw new IllegalArgumentException(
				"RGB images are not supported. Please convert to multichannel and re-run");

		context.inject(this);
		xy = sourceImage;
		width = sourceImage.getWidth();
		height = sourceImage.getHeight();
		depth = sourceImage.getNSlices();
		imageType = sourceImage.getType();
		singleSlice = depth == 1;

		final Calibration calibration = sourceImage.getCalibration();
		if (calibration != null) {
			x_spacing = calibration.pixelWidth;
			y_spacing = calibration.pixelHeight;
			z_spacing = calibration.pixelDepth;
			spacing_units = calibration.getUnits();
			if (spacing_units == null || spacing_units.length() == 0) spacing_units =
				"" + calibration.getUnit();
		}
		if ((x_spacing == 0.0) || (y_spacing == 0.0) || (z_spacing == 0.0)) {
			throw new IllegalArgumentException(
				"One dimension of the calibration information was zero: (" + x_spacing +
					"," + y_spacing + "," + z_spacing + ")");
		}

		pathAndFillManager = new PathAndFillManager(this);
		prefs = new SNTPrefs(this);
		prefs.loadPluginPrefs();
	}


	public SimpleNeuriteTracer(final Context context, final File file) {

		if (context == null)
			throw new NullContextException();
		if (file == null)
			throw new IllegalArgumentException("Input file cannot be null");

		pathAndFillManager = new PathAndFillManager(this);
		pathAndFillManager.needImageDataFromTracesFile = true;
		pathAndFillManager.setHeadless(true);
		if (!pathAndFillManager.loadGuessingType(file.getAbsolutePath())) {
			throw new IllegalArgumentException(String.format("%s is not a valid file", file.getAbsolutePath()));
		}

		width = pathAndFillManager.parsed_width;
		height = pathAndFillManager.parsed_height;
		depth = pathAndFillManager.parsed_depth;
		imageType = ImagePlus.GRAY8;
		singleSlice = depth == 1;

		final Calibration calibration = pathAndFillManager.getParsedCalibration();
		if (calibration != null) {
			x_spacing = calibration.pixelWidth;
			y_spacing = calibration.pixelHeight;
			z_spacing = calibration.pixelDepth;
			spacing_units = calibration.getUnits();
			if (spacing_units == null || spacing_units.length() == 0)
				spacing_units = "" + calibration.getUnit();
		}
		if ((x_spacing == 0.0) || (y_spacing == 0.0) || (z_spacing == 0.0)) {
			throw new IllegalArgumentException("One dimension of the calibration information was zero: (" + x_spacing
					+ "," + y_spacing + "," + z_spacing + ")");
		}
		pathAndFillManager.needImageDataFromTracesFile = false;
		pathAndFillManager.setHeadless(false);
		xy = NewImage.createByteImage(file.getName(), width, height, depth, NewImage.FILL_BLACK);
		nonInteractiveSession = true;

		context.inject(this);
		prefs = new SNTPrefs(this);
		prefs.loadPluginPrefs();

		// now disable auto-tracing features
		disableAstar(true);
		enableSnapCursor(false);

	}

	public void initialize(boolean singlePane, int channel, int frame) {
		this.channel = channel;
		this.frame = frame;
		setSinglePane(singlePane);
		final Overlay sourceImageOverlay = xy.getOverlay();
		initialize(xy);
		xy.setOverlay(sourceImageOverlay);

		xy_tracer_canvas = (InteractiveTracerCanvas) xy_canvas;
		xz_tracer_canvas = (InteractiveTracerCanvas) xz_canvas;
		zy_tracer_canvas = (InteractiveTracerCanvas) zy_canvas;
		setupTrace = true;

		/*
		 * FIXME: this could be changed to add 'this', and move the small
		 * implementation out of NeuriteTracerResultsDialog into this class.
		 */
		pathAndFillManager.addPathAndFillListener(this);

		loadData();
		addListener(xy_tracer_canvas);
		if (!single_pane) {
			xz.setDisplayRange(xy.getDisplayRangeMin(), xy.getDisplayRangeMax());
			zy.setDisplayRange(xy.getDisplayRangeMin(), xy.getDisplayRangeMax());
			addListener(xz_tracer_canvas);
			addListener(zy_tracer_canvas);
		}
	}

	private void addListener(final InteractiveTracerCanvas canvas) {
		final QueueJumpingKeyListener listener = new QueueJumpingKeyListener(this,
			canvas);
		setAsFirstKeyListener(canvas, listener);
	}

	public void reloadImage(int channel, int frame) {
		if (!setupTrace) throw new IllegalArgumentException(
			"SNT has not yet been initialized");
		if (frame < 1 || channel < 1 || frame > getImagePlus().getNFrames() ||
			channel > getImagePlus().getNChannels())
			throw new IllegalArgumentException("Invalid position: C=" + channel +
				" T=" + frame);
		this.channel = channel;
		this.frame = frame;
		loadData();
		reloadZYXZpanes(frame);
		hessian = null;
	}

	public void rebuildZYXZpanes() {
		single_pane = false;
		reloadZYXZpanes(frame);
		zy_tracer_canvas = (InteractiveTracerCanvas) zy_canvas;
		addListener(zy_tracer_canvas);
		xz_tracer_canvas = (InteractiveTracerCanvas) xz_canvas;
		addListener(xz_tracer_canvas);
		if (!zy.isVisible()) zy.show();
		if (!xz.isVisible()) xz.show();
	}

	private void loadData() {
		final ImageStack s = xy.getStack();
		switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				slices_data_b = new byte[depth][];
				for (int z = 0; z < depth; ++z)
					slices_data_b[z] = (byte[]) s.getPixels(xy.getStackIndex(channel, z+1, frame));
				stackMin = 0;
				stackMax = 255;
				break;
			case ImagePlus.GRAY16:
				slices_data_s = new short[depth][];
				for (int z = 0; z < depth; ++z)
					slices_data_s[z] = (short[]) s.getPixels(xy.getStackIndex(channel, z+1, frame));
				statusService.showStatus("Finding stack minimum / maximum");
				for (int z = 0; z < depth; ++z) {
					for (int y = 0; y < height; ++y)
						for (int x = 0; x < width; ++x) {
							final short v = slices_data_s[z][y * width + x];
							if (v < stackMin) stackMin = v;
							if (v > stackMax) stackMax = v;
						}
					statusService.showProgress(z, depth);
				}
				statusService.showProgress(0, 0);
				break;
			case ImagePlus.GRAY32:
				slices_data_f = new float[depth][];
				for (int z = 0; z < depth; ++z)
					slices_data_f[z] = (float[]) s.getPixels(xy.getStackIndex(channel, z+1, frame));
				statusService.showStatus("Finding stack minimum / maximum");
				for (int z = 0; z < depth; ++z) {
					for (int y = 0; y < height; ++y)
						for (int x = 0; x < width; ++x) {
							final float v = slices_data_f[z][y * width + x];
							if (v < stackMin) stackMin = v;
							if (v > stackMax) stackMax = v;
						}
					statusService.showProgress(z, depth);
				}
				statusService.showProgress(0, 0);
				break;
		}
	}

	public void startUI() {
		final SimpleNeuriteTracer thisPlugin = this;

		resultsDialog = SwingSafeResult.getResult(
			new Callable<NeuriteTracerResultsDialog>()
			{

				@Override
				public NeuriteTracerResultsDialog call() {
					return new NeuriteTracerResultsDialog(thisPlugin);
				}
			});
		guiUtils = new GuiUtils(resultsDialog);
		if (nonInteractiveSession) {
			changeUIState(NeuriteTracerResultsDialog.ANALYSIS_MODE);
		}
		resultsDialog.displayOnStarting();
	}

	public void loadTracings(File file) {
		if (file != null && file.exists()) {
			if (isUIready()) resultsDialog.changeState(
				NeuriteTracerResultsDialog.LOADING);
			pathAndFillManager.loadGuessingType(file.getAbsolutePath());
			if (isUIready()) resultsDialog.changeState(
				NeuriteTracerResultsDialog.WAITING_TO_START_PATH);
			prefs.setRecentFile(file);
		}
	}

	public boolean pathsUnsaved() {
		return unsavedPaths;
	}

	public PathAndFillManager getPathAndFillManager() {
		return pathAndFillManager;
	}

	public InteractiveTracerCanvas getXYCanvas() {
		return xy_tracer_canvas;
	}

	public InteractiveTracerCanvas getZYCanvas() {
		return zy_tracer_canvas;
	}

	public InteractiveTracerCanvas getXZCanvas() {
		return xz_tracer_canvas;
	}

	public ImagePlus getImagePlus() {
		return xy;
	}

	public double getLargestDimension() {
		return Math.max(x_spacing * width, Math.max(y_spacing * height, z_spacing *
			depth));
	}

	public double getStackDiagonalLength() {
		return Math.sqrt((x_spacing * width) * (x_spacing * width) + (y_spacing *
			height) * (y_spacing * height) + (z_spacing * depth) * (z_spacing *
				depth));
	}

	/* This overrides the method in ThreePanes... */
	@Override
	public InteractiveTracerCanvas createCanvas(final ImagePlus imagePlus,
		final int plane)
	{
		return new InteractiveTracerCanvas(imagePlus, this, plane,
			pathAndFillManager);
	}

	public void cancelSearch(final boolean cancelFillToo) {
		if (currentSearchThread != null) currentSearchThread.requestStop();
		if (manualSearchThread != null) manualSearchThread.requestStop();
		if (tubularGeodesicsThread != null) tubularGeodesicsThread.requestStop();
		endJoin = null;
		endJoinPoint = null;
		if (cancelFillToo && filler != null) filler.requestStop();
	}

	@Override
	public void threadStatus(final SearchInterface source, final int status) {
		// Ignore this information.
	}

	public void changeUIState(final int newState) {
		resultsDialog.changeState(newState);
	}

	public int getUIState() {
		return resultsDialog.getCurrentState();
	}

	synchronized public void saveFill() {

		if (filler != null) {
			// The filler must be paused while we save to
			// avoid concurrent modifications...

			SNT.log("[" + Thread.currentThread() +
				"] going to lock filler in plugin.saveFill");
			synchronized (filler) {
				SNT.log("[" + Thread.currentThread() + "] acquired it");
				if (SearchThread.PAUSED == filler.getThreadStatus()) {
					// Then we can go ahead and save:
					pathAndFillManager.addFill(filler.getFill());
					// ... and then stop filling:
					filler.requestStop();
					resultsDialog.changeState(
						NeuriteTracerResultsDialog.WAITING_TO_START_PATH);
					filler = null;
				}
				else {
					guiUtils.error("The filler must be paused before saving the fill.");
				}

			}
			SNT.log("[" + Thread.currentThread() + "] left lock on filler");
		}
	}

	synchronized public void discardFill() {
		discardFill(true);
	}

	synchronized public void discardFill(final boolean updateState) {
		if (filler != null) {
			synchronized (filler) {
				filler.requestStop();
				if (updateState) resultsDialog.changeState(
					NeuriteTracerResultsDialog.WAITING_TO_START_PATH);
				filler = null;
			}
		}
	}

	synchronized public void pauseOrRestartFilling() {
		if (filler != null) {
			filler.pauseOrUnpause();
		}
	}

	/* Listeners */
	protected List<SNTListener> listeners = Collections.synchronizedList(
		new ArrayList<SNTListener>());

	public void addListener(final SNTListener listener) {
		listeners.add(listener);
	}

	public void notifyListeners(final SNTEvent event) {
		for (final SNTListener listener : listeners.toArray(new SNTListener[0])) {
			listener.onEvent(event);
		}
	}

	public boolean anyListeners() {
		return listeners.size() > 0;
	}

	/*
	 * Now a couple of callback methods, which get information about the
	 * progress of the search.
	 */

	@Override
	public void finished(final SearchInterface source, final boolean success) {

		/*
		 * This is called by both filler and currentSearchThread, so distinguish
		 * these cases:
		 */

		if (source == currentSearchThread || source == tubularGeodesicsThread || source == manualSearchThread) {

			removeSphere(targetBallName);

			if (success) {
				final Path result = source.getResult();
				if (result == null) {
					SNT.error("Bug! Succeeded, but null result.");
					return;
				}
				if (endJoin != null) {
					result.setEndJoin(endJoin, endJoinPoint);
				}
				setTemporaryPath(result);

				if (resultsDialog.confirmTemporarySegments) {
					resultsDialog.changeState(NeuriteTracerResultsDialog.QUERY_KEEP);
				} else {
					confirmTemporary();
					resultsDialog.changeState(NeuriteTracerResultsDialog.PARTIAL_PATH);
				}
			}
			else {

				resultsDialog.changeState(NeuriteTracerResultsDialog.PARTIAL_PATH);
			}

			// Indicate in the dialog that we've finished...

			if (source == currentSearchThread) {
				currentSearchThread = null;
			}

		}

		removeThreadToDraw(source);
		repaintAllPanes();

	}

	@Override
	public void pointsInSearch(final SearchInterface source, final int inOpen,
		final int inClosed)
	{
		// Just use this signal to repaint the canvas, in case there's
		// been no mouse movement.
		repaintAllPanes();
	}

	public void justDisplayNearSlices(final boolean value, final int eitherSide) {

		xy_tracer_canvas.just_near_slices = value;
		if (!single_pane) {
			xz_tracer_canvas.just_near_slices = value;
			zy_tracer_canvas.just_near_slices = value;
		}

		xy_tracer_canvas.eitherSide = eitherSide;
		if (!single_pane) {
			xz_tracer_canvas.eitherSide = eitherSide;
			zy_tracer_canvas.eitherSide = eitherSide;
		}

		repaintAllPanes();

	}

	private boolean uiReadyForModeChange() {
		return isUIready() && (getUIState() == NeuriteTracerResultsDialog.WAITING_TO_START_PATH
				|| getUIState() == NeuriteTracerResultsDialog.ANALYSIS_MODE);
	}

	protected Path getEditingPath() {
		return editingPath;
	}

	protected int getEditingNode() {
		return (getEditingPath() == null) ? -1 : getEditingPath().getEditableNodeIndex();
	}

	/**
	 * Assesses if activation of 'Edit Mode' is possible.
	 *
	 * @return true, if possible, false otherwise
	 */
	public boolean editModeAllowed() {
		return editModeAllowed(false);
	}

	protected boolean editModeAllowed(final boolean warnUserIfNot) {
		final boolean uiReady = uiReadyForModeChange() || isEditModeEnabled();
		if (warnUserIfNot && !uiReady) {
			discreteMsg("Please finish current operation before editing paths");
			return false;
		}
		detectEditingPath();
		final boolean pathExists = editingPath != null;
		if (warnUserIfNot && !pathExists) {
			discreteMsg("You must select a single path in order to edit it");
			return false;
		}
		final boolean validPath = pathExists && !editingPath.getUseFitted();
		if (warnUserIfNot && !validPath) {
			discreteMsg("Only unfitted paths can be edited.<br>Run \"Un-fit volume\" to proceed");
			return false;
		}
		final boolean editAllowed = uiReady && pathExists && validPath;
		return editAllowed;
	}

	protected void setEditingPath(final Path path) {
		editingPath = path;
	}

	protected void detectEditingPath() {
		editingPath = getSingleSelectedPath();
	}

	protected Path getSingleSelectedPath() {
		final Set<Path> sPaths = getSelectedPaths();
		if (sPaths == null || sPaths.size() != 1) return null;
		return getSelectedPaths().iterator().next();
	}

	protected void enableEditMode(final boolean enable) {
		if (enable) {
			changeUIState(NeuriteTracerResultsDialog.EDITING_MODE);
			setCanvasLabelAllPanes(InteractiveTracerCanvas.EDIT_MODE_LABEL);
			if (isUIready() && !getUI().nearbySlices())
				getUI().togglePartsChoice();
		} else {
			changeUIState(NeuriteTracerResultsDialog.WAITING_TO_START_PATH);
			setCanvasLabelAllPanes(null);
		}
		if (enable && pathAndFillManager.getSelectedPaths().size() == 1) {
			editingPath = getSelectedPaths().iterator().next();
		} else {
			if (editingPath != null) editingPath.setEditableNode(-1);
			editingPath = null;
		}
		setDrawCrosshairsAllPanes(!enable);
		setLockCursorAllPanes(enable);
		xy_tracer_canvas.setEditMode(enable);
		if (!single_pane) {
			xz_tracer_canvas.setEditMode(enable);
			zy_tracer_canvas.setEditMode(enable);
		}
		repaintAllPanes();
	}

	protected void pause(final boolean pause) {
		if (pause) {
			if (!uiReadyForModeChange()) {
				guiUtils.error(
					"Please finish/abort current task before pausing SNT.");
				return;
			}
			changeUIState(NeuriteTracerResultsDialog.PAUSED);
			disableEventsAllPanes(true);
			setDrawCrosshairsAllPanes(false);
			setCanvasLabelAllPanes(InteractiveTracerCanvas.PAUSE_MODE_LABEL);
			
		}
		else {
			if (xy != null && xy.isLocked() && !getConfirmation(
				"Image appears to be locked by other process. Activate SNT nevertheless?",
				"Image Locked")) return;
			changeUIState(NeuriteTracerResultsDialog.WAITING_TO_START_PATH);
			disableEventsAllPanes(false);
			setDrawCrosshairsAllPanes(true);
			setCanvasLabelAllPanes(null);
		}
	}

	protected boolean isEditModeEnabled() {
		return isUIready() && NeuriteTracerResultsDialog.EDITING_MODE == getUIState();
	}

	@Deprecated
	public void setCrosshair(final double new_x, final double new_y,
		final double new_z) {
		xy_tracer_canvas.setCrosshairs(new_x, new_y, new_z, true);
		if (!single_pane) {
			xz_tracer_canvas.setCrosshairs(new_x, new_y, new_z, true);
			zy_tracer_canvas.setCrosshairs(new_x, new_y, new_z, true);
		}
	}

	public void updateCursor(final double new_x, final double new_y,
		final double new_z)
	{
		xy_tracer_canvas.updateCursor(new_x, new_y, new_z);
		if (!single_pane) {
			xz_tracer_canvas.updateCursor(new_x, new_y, new_z);
			zy_tracer_canvas.updateCursor(new_x, new_y, new_z);
		}

	}

	synchronized public void loadLabelsFile(final String path) {

		final AmiraMeshDecoder d = new AmiraMeshDecoder();

		if (!d.open(path)) {
			guiUtils.error("Could not open the labels file '" + path + "'");
			return;
		}

		final ImageStack stack = d.getStack();

		final ImagePlus labels = new ImagePlus("Label file for Tracer", stack);

		if ((labels.getWidth() != width) || (labels.getHeight() != height) ||
			(labels.getNSlices() != depth))
		{
			guiUtils.error(
				"The size of that labels file doesn't match the size of the image you're tracing.");
			return;
		}

		// We need to get the AmiraParameters object for that image...

		final AmiraParameters parameters = d.parameters;

		materialList = parameters.getMaterialList();

		labelData = new byte[depth][];
		for (int z = 0; z < depth; ++z) {
			labelData[z] = (byte[]) stack.getPixels(xy.getStackIndex(channel, z+1, frame));
		}

	}

	protected File loadedImageFile() {
		try {
			final FileInfo fInfo = getImagePlus().getFileInfo();
			return new File(fInfo.directory, fInfo.fileName);
		} catch (final NullPointerException npe) {
			return null;
		}
	}

	synchronized protected void loadTracesFile() {
		loading = true;
		final File suggestedFile = SNT.findClosestPair(prefs.getRecentFile(), ".traces");
		final File chosenFile = guiUtils.openFile("Open .traces file...", suggestedFile,
				Collections.singletonList(".traces"));
		if (chosenFile == null)
			return; // user pressed cancel;

		if (!chosenFile.exists()) {
			guiUtils.error(chosenFile.getAbsolutePath() + " is no longer available");
			loading = false;
			return;
		}

		final int guessedType = PathAndFillManager.guessTracesFileType(chosenFile.getAbsolutePath());
		switch (guessedType) {
		case PathAndFillManager.TRACES_FILE_TYPE_COMPRESSED_XML:
			if (pathAndFillManager.loadCompressedXML(chosenFile.getAbsolutePath()))
				unsavedPaths = false;
			break;
		case PathAndFillManager.TRACES_FILE_TYPE_UNCOMPRESSED_XML:
			if (pathAndFillManager.loadUncompressedXML(chosenFile.getAbsolutePath()))
				unsavedPaths = false;
			break;
		default:
			guiUtils.error(chosenFile.getAbsolutePath() + " is not a valid traces file.");
			break;
		}

		loading = false;
	}

	synchronized protected void loadSWCFile() {
		loading = true;
		final File suggestedFile = SNT.findClosestPair(prefs.getRecentFile(), ".swc");
		final File chosenFile = guiUtils.openFile("Open SWC file...", suggestedFile, Arrays.asList(".swc", ".eswc"));
		if (chosenFile == null)
			return; // user pressed cancel;

		if (!chosenFile.exists()) {
			guiUtils.error(chosenFile.getAbsolutePath() + " is no longer available");
			loading = false;
			return;
		}

		final int guessedType = PathAndFillManager.guessTracesFileType(chosenFile.getAbsolutePath());
		switch (guessedType) {
		case PathAndFillManager.TRACES_FILE_TYPE_SWC: {
			final SWCImportOptionsDialog swcImportDialog = new SWCImportOptionsDialog(
					"SWC import options for " + chosenFile.getName());
			if (swcImportDialog.succeeded() && pathAndFillManager.importSWC(chosenFile.getAbsolutePath(),
					swcImportDialog.getIgnoreCalibration(), swcImportDialog.getXOffset(), swcImportDialog.getYOffset(),
					swcImportDialog.getZOffset(), swcImportDialog.getXScale(), swcImportDialog.getYScale(),
					swcImportDialog.getZScale(), swcImportDialog.getReplaceExistingPaths()))
				unsavedPaths = false;
			break;
		}
		default:
			guiUtils.error(chosenFile.getAbsolutePath() + " does not seem to contain valid SWC data.");
			break;
		}
		loading = false;
	}

	synchronized public void loadTracings() {

		loading = true;
		final File suggestedFile = SNT.findClosestPair(prefs.getRecentFile(), ".traces"); 
		final File chosenFile = guiUtils.openFile("Open .traces or .(e)swc file...", suggestedFile, Arrays.asList(".traces", ".swc", ".eswc")); 
		if (chosenFile == null) return; // user pressed cancel; 

		if (!chosenFile.exists()) {
			guiUtils.error(chosenFile.getAbsolutePath() + " is no longer available");
			loading = false;
			return;
		}

		final int guessedType = PathAndFillManager.guessTracesFileType(chosenFile.getAbsolutePath());
		switch (guessedType) {
		case PathAndFillManager.TRACES_FILE_TYPE_SWC: {
			final SWCImportOptionsDialog swcImportDialog =
					new SWCImportOptionsDialog("SWC import options for " + chosenFile
							.getName());
			if (swcImportDialog.succeeded() && pathAndFillManager.importSWC(
					chosenFile.getAbsolutePath(), swcImportDialog
					.getIgnoreCalibration(), swcImportDialog.getXOffset(),
					swcImportDialog.getYOffset(), swcImportDialog.getZOffset(),
					swcImportDialog.getXScale(), swcImportDialog.getYScale(),
					swcImportDialog.getZScale(), swcImportDialog
					.getReplaceExistingPaths())) unsavedPaths = false;
			break;
		}
		case PathAndFillManager.TRACES_FILE_TYPE_COMPRESSED_XML:
			if (pathAndFillManager.loadCompressedXML(chosenFile
					.getAbsolutePath())) unsavedPaths = false;
			break;
		case PathAndFillManager.TRACES_FILE_TYPE_UNCOMPRESSED_XML:
			if (pathAndFillManager.loadUncompressedXML(chosenFile
					.getAbsolutePath())) unsavedPaths = false;
			break;
		default:
			guiUtils.error("The file '" + chosenFile.getAbsolutePath() +
					"' was of unknown type (" + guessedType + ")");
			break;
		}

		loading = false;
	}

	public void mouseMovedTo(final double x_in_pane, final double y_in_pane,
		final int in_plane, final boolean shift_key_down,
		final boolean join_modifier_down)
	{

		double x, y, z;

		final double[] pd = new double[3];
		findPointInStackPrecise(x_in_pane, y_in_pane, in_plane, pd);
		x = pd[0];
		y = pd[1];
		z = pd[2];

		final boolean editing = isEditModeEnabled() && editingPath != null && editingPath.isSelected();
		final boolean joining = !editing && join_modifier_down && pathAndFillManager.anySelected();

		PointInImage pim = null;
		if (joining) {
			// find the nearest node to this cursor position
			pim = pathAndFillManager.nearestJoinPointOnSelectedPaths(x, y, z);
		} else if (editing) {
			// find the nearest node to this cursor 2D position. 
			// then activate the Z-slice of the retrieved node
			final int eNode = editingPath.indexNearestTo2D(x * x_spacing, y * y_spacing, getMinimumSeparation());
			if (eNode != -1) {
				pim = editingPath.getPointInImage(eNode);
				editingPath.setEditableNode(eNode);
			}
		}
		if (pim != null) {
			x = pim.x / x_spacing;
			y = pim.y / y_spacing;
			z = pim.z / z_spacing;
			setCursorTextAllPanes((joining) ? " Fork Point" : null);
		} else {
			setCursorTextAllPanes(null);
		}

		final int ix = (int) Math.round(x);
		final int iy = (int) Math.round(y);
		final int iz = (int) Math.round(z);

		if (shift_key_down || editing) setSlicesAllPanes(ix, iy, iz);

		String statusMessage = "";
		if (editing) {
			statusMessage = editingPath.getName() + ", Node " + editingPath.getEditableNodeIndex();
		} else { // tracing
			statusMessage = "World: (" + SNT.formatDouble(ix * x_spacing, 2) + ", "
					+ SNT.formatDouble(iy * y_spacing, 2) + ", " + SNT.formatDouble(iz * z_spacing, 2) + ");";
			if (labelData != null) {
				final byte b = labelData[iz][iy * width + ix];
				final int m = b & 0xFF;
				final String material = materialList[m];
				statusMessage += ", " + material;
			}
		}
		statusMessage += " | Image: (" + ix + ", " + iy + ", " + (iz + 1) + ")";
		updateCursor(x, y, z);
		statusService.showStatus(statusMessage);
		repaintAllPanes(); // Or the crosshair isn't updated...

		if (filler != null) {
			synchronized (filler) {
				final float distance = filler.getDistanceAtPoint(ix, iy, iz);
				resultsDialog.showMouseThreshold(distance);
			}
		}
	}

	// When we set temporaryPath, we also want to update the display:

	synchronized public void setTemporaryPath(final Path path) {

		final Path oldTemporaryPath = this.temporaryPath;

		xy_tracer_canvas.setTemporaryPath(path);
		if (!single_pane) {
			zy_tracer_canvas.setTemporaryPath(path);
			xz_tracer_canvas.setTemporaryPath(path);
		}

		temporaryPath = path;

		if (temporaryPath != null) temporaryPath.setName("Temporary Path");
		if (use3DViewer) {

			if (oldTemporaryPath != null) {
				oldTemporaryPath.removeFrom3DViewer(univ);
			}
			if (temporaryPath != null) temporaryPath.addTo3DViewer(univ, Color.BLUE,
				null);
		}
	}

	synchronized public void setCurrentPath(final Path path) {
		final Path oldCurrentPath = this.currentPath;
		currentPath = path;
		if (currentPath != null) {
			currentPath.setName("Current Path");
			path.setSelected(true); // so it is rendered as an active path
		}
		xy_tracer_canvas.setCurrentPath(path);
		if (!single_pane) {
			zy_tracer_canvas.setCurrentPath(path);
			xz_tracer_canvas.setCurrentPath(path);
		}
		if (use3DViewer) {
			if (oldCurrentPath != null) {
				oldCurrentPath.removeFrom3DViewer(univ);
			}
			if (currentPath != null) currentPath.addTo3DViewer(univ, getXYCanvas().getTemporaryPathColor(), null);
		}
	}

	synchronized public Path getCurrentPath() {
		return currentPath;
	}

	public void setPathUnfinished(final boolean unfinished) {

		this.pathUnfinished = unfinished;
		xy_tracer_canvas.setPathUnfinished(unfinished);
		if (!single_pane) {
			zy_tracer_canvas.setPathUnfinished(unfinished);
			xz_tracer_canvas.setPathUnfinished(unfinished);
		}
	}

	void addThreadToDraw(final SearchInterface s) {
		xy_tracer_canvas.addSearchThread(s);
		if (!single_pane) {
			zy_tracer_canvas.addSearchThread(s);
			xz_tracer_canvas.addSearchThread(s);
		}
	}

	void removeThreadToDraw(final SearchInterface s) {
		xy_tracer_canvas.removeSearchThread(s);
		if (!single_pane) {
			zy_tracer_canvas.removeSearchThread(s);
			xz_tracer_canvas.removeSearchThread(s);
		}
	}

	int[] selectedPaths = null;

	/*
	 * Create a new 8 bit ImagePlus of the same dimensions as this image, but
	 * with values set to either 255 (if there's a point on a path there) or 0
	 */

	synchronized public ImagePlus makePathVolume(final ArrayList<Path> paths) {

		final byte[][] snapshot_data = new byte[depth][];

		for (int i = 0; i < depth; ++i)
			snapshot_data[i] = new byte[width * height];

		pathAndFillManager.setPathPointsInVolume(paths, snapshot_data, width,
			height, depth);

		final ImageStack newStack = new ImageStack(width, height);

		for (int i = 0; i < depth; ++i) {
			final ByteProcessor thisSlice = new ByteProcessor(width, height);
			thisSlice.setPixels(snapshot_data[i]);
			newStack.addSlice(null, thisSlice);
		}

		final ImagePlus newImp = new ImagePlus(xy.getShortTitle() +
			" Rendered Paths", newStack);
		newImp.setCalibration(xy.getCalibration());
		return newImp;
	}

	synchronized public ImagePlus makePathVolume() {
		return makePathVolume(pathAndFillManager.getPaths());
	}

	/* Start a search thread looking for the goal in the arguments: */

	synchronized void testPathTo(final double world_x, final double world_y,
		final double world_z, final PointInImage joinPoint)
	{

		if (!lastStartPointSet) {
			statusService.showStatus(

				"No initial start point has been set.  Do that with a mouse click." +
					" (Or a Shift-" + GuiUtils.ctrlKey() +
					"-click if the start of the path should join another neurite.");
			return;
		}

		if (temporaryPath != null) {
			statusService.showStatus(
				"There's already a temporary path; Press 'N' to cancel it or 'Y' to keep it.");
			return;
		}

		double real_x_end, real_y_end, real_z_end;

		int x_end, y_end, z_end;
		if (joinPoint == null) {
			real_x_end = world_x;
			real_y_end = world_y;
			real_z_end = world_z;
		}
		else {
			real_x_end = joinPoint.x;
			real_y_end = joinPoint.y;
			real_z_end = joinPoint.z;
			endJoin = joinPoint.onPath;
			endJoinPoint = joinPoint;
		}

		addSphere(targetBallName, real_x_end, real_y_end, real_z_end, Color.BLUE,
			x_spacing * ballRadiusMultiplier);

		x_end = (int) Math.round(real_x_end / x_spacing);
		y_end = (int) Math.round(real_y_end / y_spacing);
		z_end = (int) Math.round(real_z_end / z_spacing);

		if (tubularGeodesicsTracingEnabled) {

			// Then useful values are:
			// oofFile.getAbsolutePath() - the filename of the OOF file
			// last_start_point_[xyz] - image coordinates of the start point
			// [xyz]_end - image coordinates of the end point

			// [xyz]_spacing

			tubularGeodesicsThread = new TubularGeodesicsTracer(filteredFileImage,
					(int)Math.round(last_start_point_x), (int)Math.round(last_start_point_y), (int)Math.round(last_start_point_z), x_end,
				y_end, z_end, x_spacing, y_spacing, z_spacing, spacing_units);
			addThreadToDraw(tubularGeodesicsThread);
			tubularGeodesicsThread.addProgressListener(this);
			tubularGeodesicsThread.start();

		}

		else if (isAstarDisabled()) {
			manualSearchThread = new ManualTracerThread(this, last_start_point_x,
				last_start_point_y, last_start_point_z, x_end, y_end, z_end);
			addThreadToDraw(manualSearchThread);
			manualSearchThread.addProgressListener(this);
			manualSearchThread.start();
		}
		else {
			currentSearchThread = new TracerThread(xy, stackMin, stackMax, 0, // timeout
				// in
				// seconds
				1000, // reportEveryMilliseconds
				(int)Math.round(last_start_point_x), (int)Math.round(last_start_point_y), (int)Math.round(last_start_point_z),
				x_end, y_end, z_end, //
				true, // reciprocal
				singleSlice, (hessianEnabled ? hessian : null), resultsDialog
					.getMultiplier(), doSearchOnFilteredData?filteredData:null, hessianEnabled);

			addThreadToDraw(currentSearchThread);
			currentSearchThread.setDrawingColors(Color.CYAN, null);
			currentSearchThread.setDrawingThreshold(-1);
			currentSearchThread.addProgressListener(this);
			currentSearchThread.start();

		}

		repaintAllPanes();
	}

	synchronized public void confirmTemporary() {

		if (temporaryPath == null)
			// Just ignore the request to confirm a path (there isn't one):
			return;

		currentPath.add(temporaryPath);

		final PointInImage last = currentPath.lastPoint();
		last_start_point_x = (int) Math.round(last.x / x_spacing);
		last_start_point_y = (int) Math.round(last.y / y_spacing);
		last_start_point_z = (int) Math.round(last.z / z_spacing);

		if (currentPath.endJoins == null) {
			setTemporaryPath(null);
			resultsDialog.changeState(NeuriteTracerResultsDialog.PARTIAL_PATH);
			repaintAllPanes();
		}
		else {
			setTemporaryPath(null);
			// Since joining onto another path for the end must finish the path:
			finishedPath();
		}

		/*
		 * This has the effect of removing the path from the 3D viewer and
		 * adding it again:
		 */
		setCurrentPath(currentPath);
	}

	synchronized public void cancelTemporary() {

		if (!lastStartPointSet) {
			discreteMsg(
				"No initial start point has been set yet.<br>Do that with a mouse click or a Shift+" +
					GuiUtils.ctrlKey() +
					"-click if the start of the path should join another.");
			return;
		}

		if (temporaryPath == null) {
			discreteMsg("There is no temporary path to discard");
			return;
		}

		removeSphere(targetBallName);

		if (temporaryPath.endJoins != null) {
			temporaryPath.unsetEndJoin();
		}

		setTemporaryPath(null);

		endJoin = null;
		endJoinPoint = null;

		resultsDialog.changeState(NeuriteTracerResultsDialog.PARTIAL_PATH);
		repaintAllPanes();
	}

	synchronized public void cancelPath() {

		// Is there an unconfirmed path? If so, warn people about it...
		if (temporaryPath != null) {
			discreteMsg(
				"You need to confirm the last segment before canceling the path.");
			return;
		}

		if (currentPath != null) {
			if (currentPath.startJoins != null) currentPath.unsetStartJoin();
			if (currentPath.endJoins != null) currentPath.unsetEndJoin();
		}

		removeSphere(targetBallName);
		removeSphere(startBallName);

		setCurrentPath(null);
		setTemporaryPath(null);

		lastStartPointSet = false;
		setPathUnfinished(false);

		resultsDialog.changeState(NeuriteTracerResultsDialog.WAITING_TO_START_PATH);

		repaintAllPanes();
	}

	synchronized public void finishedPath() {

		// Is there an unconfirmed path? If so, confirm it first
		if (temporaryPath != null) {
			confirmTemporary();
			finishedPath();
			return;
		}

		if (currentPath == null) {
			discreteMsg("No temporary path to finish..."); // this can happen through repeated shortcut triggers
			return;
		}

		if (justFirstPoint() && !getConfirmation("Create a single point path? (such path is typically used to mark the cell soma)", "Create Single Point Path?")) {
			return;
		}

		final Path savedCurrentPath = currentPath;

		if (justFirstPoint()) {
			final PointInImage p = new PointInImage(last_start_point_x * x_spacing, last_start_point_y * y_spacing,
					last_start_point_z * z_spacing);
			savedCurrentPath.addPointDouble(p.x, p.y, p.z);
			savedCurrentPath.endJoinsPoint = p;
			savedCurrentPath.startJoinsPoint = p;
			cancelSearch(false);
		} else {
			removeSphere(startBallName);
		}

		removeSphere(targetBallName);
		lastStartPointSet = false;
		setPathUnfinished(false);
		setCurrentPath(null);
		pathAndFillManager.addPath(savedCurrentPath, true);
		unsavedPaths = true;

		// ... and change the state of the UI
		resultsDialog.changeState(NeuriteTracerResultsDialog.WAITING_TO_START_PATH);

		repaintAllPanes();
	}

	synchronized public void clickForTrace(final Point3d p, final boolean join) {
		final double x_unscaled = p.x / x_spacing;
		final double y_unscaled = p.y / y_spacing;
		final double z_unscaled = p.z / z_spacing;
		setSlicesAllPanes((int) x_unscaled, (int) y_unscaled, (int) z_unscaled);
		clickForTrace(p.x, p.y, p.z, join);
	}

	synchronized public void clickForTrace(final double world_x,
		final double world_y, final double world_z, final boolean join)
	{

		PointInImage joinPoint = null;

		if (join) {
			joinPoint = pathAndFillManager.nearestJoinPointOnSelectedPaths(world_x /
				x_spacing, world_y / y_spacing, world_z / z_spacing);
		}

		if (resultsDialog == null) return;

		// FIXME: in some of the states this doesn't make sense; check for them:

		if (currentSearchThread != null) return;

		if (temporaryPath != null) return;

		if (filler != null) {
			setFillThresholdFrom(world_x, world_y, world_z);
			return;
		}

		if (pathUnfinished) {
			/*
			 * Then this is a succeeding point, and we should start a search.
			 */
			testPathTo(world_x, world_y, world_z, joinPoint);
			resultsDialog.changeState(NeuriteTracerResultsDialog.SEARCHING);
		}
		else {
			/* This is an initial point. */
			startPath(world_x, world_y, world_z, joinPoint);
			resultsDialog.changeState(NeuriteTracerResultsDialog.PARTIAL_PATH);
		}

	}

	synchronized public void clickForTrace(final double x_in_pane_precise,
		final double y_in_pane_precise, final int plane, final boolean join)
	{

		final double[] p = new double[3];
		findPointInStackPrecise(x_in_pane_precise, y_in_pane_precise, plane, p);

		final double world_x = p[0] * x_spacing;
		final double world_y = p[1] * y_spacing;
		final double world_z = p[2] * z_spacing;

		clickForTrace(world_x, world_y, world_z, join);
	}

	public void setFillThresholdFrom(final double world_x, final double world_y,
		final double world_z)
	{

		final float distance = filler.getDistanceAtPoint(world_x / x_spacing,
			world_y / y_spacing, world_z / z_spacing);

		setFillThreshold(distance);
	}

	public void setFillThreshold(final double distance) {

		if (distance > 0) {

			SNT.log("Setting new threshold of: " + distance);

			resultsDialog.thresholdChanged(distance);

			filler.setThreshold(distance);
		}

	}

	synchronized void startPath(final double world_x, final double world_y,
		final double world_z, final PointInImage joinPoint)
	{

		endJoin = null;
		endJoinPoint = null;

		if (lastStartPointSet) {
			statusService.showStatus(
				"The start point has already been set; to finish a path press 'F'");
			return;
		}

		setPathUnfinished(true);
		lastStartPointSet = true;

		final Path path = new Path(x_spacing, y_spacing, z_spacing, spacing_units);
		path.setName("New Path");

		Color ballColor;

		double real_last_start_x, real_last_start_y, real_last_start_z;

		if (joinPoint == null) {
			real_last_start_x = world_x;
			real_last_start_y = world_y;
			real_last_start_z = world_z;
			ballColor = Color.BLUE;
		}
		else {
			real_last_start_x = joinPoint.x;
			real_last_start_y = joinPoint.y;
			real_last_start_z = joinPoint.z;
			path.setStartJoin(joinPoint.onPath, joinPoint);
			ballColor = Color.GREEN;
		}

		last_start_point_x = real_last_start_x / x_spacing;
		last_start_point_y = real_last_start_y / y_spacing;
		last_start_point_z = real_last_start_z / z_spacing;

		addSphere(startBallName, real_last_start_x, real_last_start_y,
			real_last_start_z, ballColor, x_spacing * ballRadiusMultiplier);

		setCurrentPath(path);
	}

	protected void addSphere(final String name, final double x, final double y,
		final double z, final Color color, final double radius)
	{
		if (use3DViewer) {
			final List<Point3f> sphere = customnode.MeshMaker.createSphere(x, y, z,
				radius);
			univ.addTriangleMesh(sphere, new Color3f(color), name);
		}
	}

	protected void removeSphere(final String name) {
		if (use3DViewer) univ.removeContent(name);
	}

	/*
	 * Return true if we have just started a new path, but have not yet added
	 * any connections to it, otherwise return false.
	 */
	private boolean justFirstPoint() {
		return pathUnfinished && (currentPath.size() == 0);
	}

	public static String getStackTrace() {
		final StringWriter sw = new StringWriter();
		new Exception("Dummy Exception for Stack Trace").printStackTrace(
			new PrintWriter(sw));
		return sw.toString();
	}

	public void viewFillIn3D(final boolean asMask) {
		final ImagePlus imagePlus = filler.fillAsImagePlus(asMask);
		imagePlus.show();
	}

	public void setPositionAllPanes(final int x, final int y, final int z) {

		xy.setPosition(channel, z + 1, frame);
		zy.setPosition(channel, x, frame);
		xz.setPosition(channel, y, frame);

	}

	public int guessResamplingFactor() {
		if (width == 0 || height == 0 || depth == 0) throw new RuntimeException(
			"Can't call guessResamplingFactor() before width, height and depth are set...");
		/*
		 * This is about right for me, but probably should be related to the
		 * free memory somehow. However, those calls are so notoriously
		 * unreliable on Java that it's probably not worth it.
		 */
		final long maxSamplePoints = 500 * 500 * 100;
		int level = 0;
		while (true) {
			final long samplePoints = (long) (width >> level) *
				(long) (height >> level) * (depth >> level);
			if (samplePoints < maxSamplePoints) return (1 << level);
			++level;
		}
	}

	protected boolean isUIready() {
		if (resultsDialog == null) return false;
		return resultsDialog.isVisible();
	}

	public void launchPaletteAround(final int x, final int y, final int z) {

		final int either_side = 40;

		int x_min = x - either_side;
		int x_max = x + either_side;
		int y_min = y - either_side;
		int y_max = y + either_side;
		int z_min = z - either_side;
		int z_max = z + either_side;

		final int originalWidth = xy.getWidth();
		final int originalHeight = xy.getHeight();
		final int originalDepth = xy.getNSlices();

		if (x_min < 0) x_min = 0;
		if (y_min < 0) y_min = 0;
		if (z_min < 0) z_min = 0;
		if (x_max >= originalWidth) x_max = originalWidth - 1;
		if (y_max >= originalHeight) y_max = originalHeight - 1;
		if (z_max >= originalDepth) z_max = originalDepth - 1;

		final double[] sigmas = new double[9];
		for (int i = 0; i < sigmas.length; ++i) {
			sigmas[i] = ((i + 1) * getMinimumSeparation()) / 2;
		}

		resultsDialog.changeState(
			NeuriteTracerResultsDialog.WAITING_FOR_SIGMA_CHOICE);

		final SigmaPalette sp = new SigmaPalette();
		sp.setListener(resultsDialog.listener);
		sp.makePalette(getLoadedDataAsImp(), x_min, x_max, y_min, y_max, z_min, z_max,
			new TubenessProcessor(true), sigmas, 256 / resultsDialog.getMultiplier(),
			3, 3, z);
	}

	public void startFillerThread(final FillerThread filler) {

		this.filler = filler;

		filler.addProgressListener(this);
		filler.addProgressListener(resultsDialog.getFillWindow());

		addThreadToDraw(filler);

		filler.start();

		resultsDialog.changeState(NeuriteTracerResultsDialog.FILLING_PATHS);

	}

	synchronized public void startFillingPaths(final Set<Path> fromPaths) {

		// currentlyFilling = true;
		resultsDialog.getFillWindow().pauseOrRestartFilling.setText("Pause");
		resultsDialog.getFillWindow().thresholdChanged(0.03f);
		filler = new FillerThread(xy, stackMin, stackMax, false, // startPaused
			true, // reciprocal
			0.03f, // Initial threshold to display
			5000); // reportEveryMilliseconds

		addThreadToDraw(filler);

		filler.addProgressListener(this);
		filler.addProgressListener(resultsDialog.getFillWindow());

		filler.setSourcePaths(fromPaths);

		resultsDialog.setFillListVisible(true);

		filler.start();

		resultsDialog.changeState(NeuriteTracerResultsDialog.FILLING_PATHS);

	}

	public void setFillTransparent(final boolean transparent) {
		xy_tracer_canvas.setFillTransparent(transparent);
		if (!single_pane) {
			xz_tracer_canvas.setFillTransparent(transparent);
			zy_tracer_canvas.setFillTransparent(transparent);
		}
	}

	public double getMinimumSeparation() {
		return Math.min(Math.abs(x_spacing), Math.min(Math.abs(y_spacing), Math.abs(
			z_spacing)));
	}

	/**
	 * Retrieves the pixel data currently loaded as an image. The sole purpose of
	 * this method is is to bridge SNT with other legacy classes that cannot deal
	 * with multidimensional images.
	 *
	 * @return the loaded data corresponding to the C,T position currently being
	 *         traced
	 */
	protected ImagePlus getLoadedDataAsImp() {
		final ImageStack stack = new ImageStack(xy.getWidth(), xy.getHeight());
		switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				for (int z = 0; z < depth; ++z) {
					final ImageProcessor ip = new ByteProcessor(xy.getWidth(), xy.getHeight());
					ip.setPixels(slices_data_b[z]);
					stack.addSlice(ip);
				}
				break;
			case ImagePlus.GRAY16:
				for (int z = 0; z < depth; ++z) {
					final ImageProcessor ip = new ShortProcessor(xy.getWidth(), xy.getHeight());
					ip.setPixels(slices_data_s[z]);
					stack.addSlice(ip);
				}
				break;
			case ImagePlus.GRAY32:
				for (int z = 0; z < depth; ++z) {
					final ImageProcessor ip = new FloatProcessor(xy.getWidth(), xy.getHeight());
					ip.setPixels(slices_data_f[z]);
					stack.addSlice(ip);
				}
				break;
			default:
				throw new IllegalArgumentException("Bug: unsupported type somehow");
		}
		final ImagePlus imp = new ImagePlus("C" + channel + "F" + frame, stack);
		imp.setCalibration(xy.getCalibration());
		return imp;
	}

	volatile boolean hessianEnabled = false;
	ComputeCurvatures hessian = null;
	/*
	 * This variable just stores the sigma which the current 'hessian'
	 * ComputeCurvatures was / is being calculated (or -1 if 'hessian' is null)
	 * ...
	 */
	volatile double hessianSigma = -1;

	public void startHessian() {
		if (hessian == null) {
			resultsDialog.changeState(
				NeuriteTracerResultsDialog.CALCULATING_GAUSSIAN);
			hessianSigma = resultsDialog.getSigma();
			hessian = new ComputeCurvatures(getLoadedDataAsImp(), hessianSigma, this, true);
			new Thread(hessian).start();
		}
		else {
			final double newSigma = resultsDialog.getSigma();
			if (newSigma != hessianSigma) {
				resultsDialog.changeState(
					NeuriteTracerResultsDialog.CALCULATING_GAUSSIAN);
				hessianSigma = newSigma;
				hessian = new ComputeCurvatures(getLoadedDataAsImp(), hessianSigma, this, true);
				new Thread(hessian).start();
			}
		}
	}

	/**
	 * Specifies the "filtered" image to be used during a tracing session.
	 *
	 * @param file The file containing the "filtered" image (typically named
	 *       {@code <image-basename>.tubes.tif} (tubeness),
	 *       {@code <image-basename>.frangi.tif} (Frangi Vesselness), or
	 *       {@code <image-basename>.oof.tif} (tubular geodesics))
	 */
	public void setFilteredImage(final File file) {
		filteredFileImage = file;
	}

	/**
	 * Returns the file of the 'filtered' image, if any.
	 *
	 * @return the filtered image file, or null if no file has been set
	 */
	public File getFilteredImage() {
		return filteredFileImage;
	}

	/**
	 * Assesses if a 'filtered image' has been loaded into memory. Note that
	 * while some tracers will load the image into memory, others may waive the
	 * loading to third party libraries
	 * 
	 * @return true, if image has been loaded into memory.
	 */
	public boolean filteredImageLoaded() {
		return filteredData != null;
	}

	protected boolean isTracingOnFilteredImageAvailable() {
		return filteredImageLoaded() || tubularGeodesicsTracingEnabled;
	}

	/**
	 * loads the 'filtered image' specified by {@link #setFilteredImage(File) into
	 * memory as 32-bit data}
	 */
	public void loadFilteredImage() throws IOException, IllegalArgumentException {
		if (xy == null)
			throw new IllegalArgumentException("Data can only be loaded after main tracing image is known");
		if (!SNT.fileAvailable(filteredFileImage))
			throw new IllegalArgumentException("File path of input data unknown");
		final Dataset ds = datasetIOService.open(filteredFileImage.getAbsolutePath());
		final int bitsPerPix = ds.getType().getBitsPerPixel();
		final StringBuilder sBuilder = new StringBuilder();
		if (bitsPerPix != 32)
			sBuilder.append("Not a 32-bit image. ");
		if (ds.dimension(Axes.CHANNEL) > 1 || ds.dimension(Axes.TIME) > 1)
			sBuilder.append("Too many dimensions: C,T images are not supported. ");
		if (ds.getWidth() != xy.getWidth() || ds.getHeight() != xy.getHeight() || ds.getDepth() != xy.getNSlices())
			sBuilder.append("XYZ Dimensions do not match those of " + xy.getTitle() + ".");
		if (!sBuilder.toString().isEmpty())
			throw new IllegalArgumentException(sBuilder.toString());

		statusService.showStatus("Loading alternative tracing images");
		final ImagePlus imp = convertService.convert(ds, ImagePlus.class);
		final ImageStack s = imp.getStack();
		filteredData = new float[depth][];
		for (int z = 0; z < depth; ++z) {
			statusService.showStatus(z, depth, "Loading stack...");
			filteredData[z] = (float[]) s.getPixels(z + 1);
		}
		statusService.clearStatus();
	}

	public synchronized void enableHessian(final boolean enable) {
		hessianEnabled = enable;
		if (enable) startHessian();
	}

	public synchronized void cancelGaussian() {
		if (hessian != null) {
			hessian.cancelGaussianGeneration();
		}
	}

	// This is the implementation of GaussianGenerationCallback
	@Override
	public void proportionDone(final double proportion) {
		if (proportion < 0) {
			hessianEnabled = false;
			hessian = null;
			hessianSigma = -1;
			resultsDialog.gaussianCalculated(false);
			statusService.showProgress(1, 1);
			return;
		}
		else if (proportion >= 1.0) {
			hessianEnabled = true;
			resultsDialog.gaussianCalculated(true);
		}
		statusService.showProgress((int) proportion, 1); // FIXME:
	}

	/*
	 * public void getTracings( boolean mineOnly ) { boolean result =
	 * pathAndFillManager.getTracings( mineOnly, archiveClient ); if( result )
	 * unsavedPaths = false; }
	 */

	/*
	 * public void uploadTracings( ) { boolean result =
	 * pathAndFillManager.uploadTracings( archiveClient ); if( result )
	 * unsavedPaths = false; }
	 */

	@Deprecated
	public void showCorrespondencesTo(final File tracesFile, final Color c,
		final double maxDistance)
	{

		final PathAndFillManager pafmTraces = new PathAndFillManager(width, height,
			depth, (float) x_spacing, (float) y_spacing, (float) z_spacing,
			spacing_units);

		/*
		 * FIXME: may well want to odd SWC options here, which isn't done with
		 * the "loadGuessingType" method:
		 */
		if (!pafmTraces.loadGuessingType(tracesFile.getAbsolutePath())) {
			guiUtils.error("Failed to load traces from: " + tracesFile
				.getAbsolutePath());
			return;
		}

		final List<Point3f> linePoints = new ArrayList<>();

		// Now find corresponding points from the first one, and draw lines to
		// them:
		final ArrayList<NearPoint> cp = pathAndFillManager.getCorrespondences(
			pafmTraces, 2.5);
		int done = 0;
		for (final NearPoint np : cp) {
			if (np != null) {
				// SNT.log("Drawing:");
				// SNT.log(np.toString());

				linePoints.add(new Point3f((float) np.nearX, (float) np.nearY,
					(float) np.nearZ));
				linePoints.add(new Point3f((float) np.closestIntersection.x,
					(float) np.closestIntersection.y, (float) np.closestIntersection.z));

				final String ballName = univ.getSafeContentName("ball " + done);
				final List<Point3f> sphere = customnode.MeshMaker.createSphere(np.nearX,
					np.nearY, np.nearZ, Math.abs(x_spacing / 2));
				univ.addTriangleMesh(sphere, new Color3f(c), ballName);
			}
			++done;
		}
		univ.addLineMesh(linePoints, new Color3f(Color.red), "correspondences",
			false);

		for (int pi = 0; pi < pafmTraces.size(); ++pi) {
			final Path p = pafmTraces.getPath(pi);
			if (p.getUseFitted()) continue;
			p.addAsLinesTo3DViewer(univ, c, null);
		}
		// univ.resetView();
	}

	protected volatile boolean showOnlySelectedPaths;

	protected void setShowOnlySelectedPaths(final boolean showOnlySelectedPaths,
		final boolean updateGUI)
	{
		this.showOnlySelectedPaths = showOnlySelectedPaths;
		if (updateGUI) {
			update3DViewerContents();
			repaintAllPanes();
		}
	}

	public void setShowOnlySelectedPaths(final boolean showOnlySelectedPaths) {
		setShowOnlySelectedPaths(showOnlySelectedPaths, true);
	}

	protected StackWindow getWindow(final int plane) {
		switch (plane) {
			case MultiDThreePanes.XY_PLANE:
				return xy_window;
			case MultiDThreePanes.XZ_PLANE:
				return (single_pane) ? null : xz_window;
			case MultiDThreePanes.ZY_PLANE:
				return (single_pane) ? null : zy_window;
			default:
				return null;
		}
	}

	@Override
	public void error(final String msg) {
		new GuiUtils(getActiveWindow()).error(msg);
	}

	private Window getActiveWindow() {
		final Window[] images = { xy_window, xz_window, zy_window };
		for (final Window win : images) {
			if (win!=null && win.isActive()) return win;
		}
		if (!isUIready()) return null;
		final Window[] frames = { resultsDialog, resultsDialog.getPathWindow(), resultsDialog.getFillWindow() };
		for (final Window win : frames) {
			if (win.isActive()) return win;
		}
		return null;
	}

	public boolean getSinglePane() {
		return single_pane;
	}

	public void setSinglePane(final boolean single_pane) {
		this.single_pane = single_pane || is2D();
	}

	public boolean getShowOnlySelectedPaths() {
		return showOnlySelectedPaths;
	}

	/*
	 * Whatever the state of the paths, update the 3D viewer to make sure that
	 * they're the right colour, the right version (fitted or unfitted) is being
	 * used and whether the path should be displayed at all - it shouldn't if
	 * the "Show only selected paths" option is set.
	 */

	public void update3DViewerContents() {
		pathAndFillManager.update3DViewerContents();
	}

	public Image3DUniverse get3DUniverse() {
		return univ;
	}

	public void setSelectedColor(final Color newColor) {
		selectedColor = newColor;
		selectedColor3f = new Color3f(newColor);
		repaintAllPanes();
		update3DViewerContents();
	}

	public void setDeselectedColor(final Color newColor) {
		deselectedColor = newColor;
		deselectedColor3f = new Color3f(newColor);
		repaintAllPanes();
		update3DViewerContents();
	}

	/*
	 * FIXME: this can be very slow ... Perhaps do it in a separate thread?
	 */
	public void setColorImage(final ImagePlus newColorImage) {
		colorImage = newColorImage;
		update3DViewerContents();
	}

	private int paths3DDisplay = 1;

	public void setPaths3DDisplay(final int paths3DDisplay) {
		this.paths3DDisplay = paths3DDisplay;
		update3DViewerContents();
	}

	public int getPaths3DDisplay() {
		return this.paths3DDisplay;
	}

	public void selectPath(final Path p, final boolean addToExistingSelection) {
		final HashSet<Path> pathsToSelect = new HashSet<>();
		if (p.isFittedVersionOfAnotherPath()) pathsToSelect.add(p.fittedVersionOf);
		else pathsToSelect.add(p);
		if (isEditModeEnabled()) { // impose a single editing path
			resultsDialog.getPathWindow().setSelectedPaths(pathsToSelect, this);
			setEditingPath(p);
			return;
		}
		if (addToExistingSelection) {
			pathsToSelect.addAll(resultsDialog.getPathWindow().getSelectedPaths(false));
		}
		resultsDialog.getPathWindow().setSelectedPaths(pathsToSelect, this);
	}

	public Set<Path> getSelectedPaths() {
		if (resultsDialog.getPathWindow() != null) {
			return resultsDialog.getPathWindow().getSelectedPaths(false);
		}
		throw new IllegalArgumentException(
			"getSelectedPaths was called when resultsDialog.pw was null");
	}

	@Override
	public void setPathList(final String[] newList, final Path justAdded,
		final boolean expandAll)
	{}

	@Override
	public void setFillList(final String[] newList) {}

	// Note that rather unexpectedly the p.setSelcted calls make sure that
	// the colour of the path in the 3D viewer is right... (FIXME)
	@Override
	public void setSelectedPaths(final HashSet<Path> selectedPathsSet,
		final Object source)
	{
		if (source == this) return;
		for (int i = 0; i < pathAndFillManager.size(); ++i) {
			final Path p = pathAndFillManager.getPath(i);
			if (selectedPathsSet.contains(p)) {
				p.setSelected(true);
			}
			else {
				p.setSelected(false);
			}
		}
	}

	/**
	 * This method will remove the existing keylisteners from the component 'c',
	 * tells 'firstKeyListener' to call those key listeners if it has not dealt
	 * with the key, and then sets 'firstKeyListener' as the key listener for 'c'
	 */
	public static void setAsFirstKeyListener(final Component c,
		final QueueJumpingKeyListener firstKeyListener)
	{
		final KeyListener[] oldKeyListeners = c.getKeyListeners();
		for (final KeyListener kl : oldKeyListeners) {
			c.removeKeyListener(kl);
		}
		firstKeyListener.addOtherKeyListeners(oldKeyListeners);
		c.addKeyListener(firstKeyListener);
	}

	public synchronized void findSnappingPointInXYview(final double x_in_pane,
		final double y_in_pane, final double[] point)
	{

		// if (width == 0 || height == 0 || depth == 0)
		// throw new RuntimeException(
		// "Can't call findSnappingPointInXYview() before width, height and
		// depth are set...");

		final int[] window_center = new int[3];
		findPointInStack((int) Math.round(x_in_pane), (int) Math.round(y_in_pane),
			MultiDThreePanes.XY_PLANE, window_center);
		int startx = window_center[0] - cursorSnapWindowXY;
		if (startx < 0) startx = 0;
		int starty = window_center[1] - cursorSnapWindowXY;
		if (starty < 0) starty = 0;
		int startz = window_center[2] - cursorSnapWindowZ;
		if (startz < 0) startz = 0;
		int stopx = window_center[0] + cursorSnapWindowXY;
		if (stopx > width) stopx = width;
		int stopy = window_center[1] + cursorSnapWindowXY;
		if (stopy > height) stopy = height;
		int stopz = window_center[2] + cursorSnapWindowZ;
		if (cursorSnapWindowZ == 0) {
			++stopz;
		}
		else if (stopz > depth) {
			stopz = depth;
		}

		ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		float currentMaximum = -Float.MAX_VALUE;
		for (int x = startx; x < stopx; ++x) {
			for (int y = starty; y < stopy; ++y) {
				for (int z = startz; z < stopz; ++z) {
					float v = -Float.MAX_VALUE;
					final int xyIndex = y * width + x;
					switch (imageType) {
						case ImagePlus.GRAY8:
						case ImagePlus.COLOR_256:
							v = 0xFF & slices_data_b[z][xyIndex];
							break;
						case ImagePlus.GRAY16:
							v = slices_data_s[z][xyIndex];
							break;
						case ImagePlus.GRAY32:
							v = slices_data_f[z][xyIndex];
							break;
						default:
							throw new RuntimeException("Unknow image type: " + imageType);
					}
					if (v > currentMaximum) {
						pointsAtMaximum = new ArrayList<>();
						pointsAtMaximum.add(new int[] { x, y, z });
						currentMaximum = v;
					}
					else if (v == currentMaximum) {
						pointsAtMaximum.add(new int[] { x, y, z });
					}
				}
			}
		}

		// if (pointsAtMaximum.size() == 0) {
		// findPointInStackPrecise(x_in_pane, y_in_pane, ThreePanes.XY_PLANE,
		// point);
		// if (verbose)
		// SNT.log("No maxima in snap-to window");
		// return;
		// }

		final int[] snapped_p = pointsAtMaximum.get(pointsAtMaximum.size() / 2);
		if (window_center[2] != snapped_p[2]) xy.setSlice(snapped_p[2] + 1);
		point[0] = snapped_p[0];
		point[1] = snapped_p[1];
		point[2] = snapped_p[2];
	}

	public void clickAtMaxPointInMainPane(final int x_in_pane, final int y_in_pane)
	{
		clickAtMaxPoint(x_in_pane, y_in_pane, MultiDThreePanes.XY_PLANE);
	}

	public void clickAtMaxPoint(final int x_in_pane, final int y_in_pane,
		final int plane)
	{
		final int[][] pointsToConsider = findAllPointsAlongLine(x_in_pane,
			y_in_pane, plane);
		ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		float currentMaximum = -Float.MAX_VALUE;
		for (int i = 0; i < pointsToConsider.length; ++i) {
			float v = -Float.MAX_VALUE;
			final int[] p = pointsToConsider[i];
			final int xyIndex = p[1] * width + p[0];
			switch (imageType) {
				case ImagePlus.GRAY8:
				case ImagePlus.COLOR_256:
					v = 0xFF & slices_data_b[p[2]][xyIndex];
					break;
				case ImagePlus.GRAY16:
					v = slices_data_s[p[2]][xyIndex];
					break;
				case ImagePlus.GRAY32:
					v = slices_data_f[p[2]][xyIndex];
					break;
				default:
					throw new RuntimeException("Unknow image type: " + imageType);
			}
			if (v > currentMaximum) {
				pointsAtMaximum = new ArrayList<>();
				pointsAtMaximum.add(p);
				currentMaximum = v;
			}
			else if (v == currentMaximum) {
				pointsAtMaximum.add(p);
			}
		}
		/*
		 * Take the middle of those points, and pretend that was the point that
		 * was clicked on.
		 */
		final int[] p = pointsAtMaximum.get(pointsAtMaximum.size() / 2);

		clickForTrace(p[0] * x_spacing, p[1] * y_spacing, p[2] * z_spacing, false);
	}

	/**
	 * Overlays a semi-transparent MIP over the tracing canvas(es).
	 *
	 * @param opacity (alpha), in the range 0.0-1.0, where 0.0 is fully
	 *          transparent and 1.0 is fully opaque. Setting opacity to zero
	 *          clears previous MIPs.
	 */
	public void showMIPOverlays(final double opacity) {
		final ArrayList<ImagePlus> allImages = new ArrayList<>();
		allImages.add(xy);
		if (!single_pane) {
			allImages.add(xz);
			allImages.add(zy);
		}

		// Create a MI Z-projection of the active channel
		for (final ImagePlus imp : allImages) {
			if (imp == null || imp.getNSlices() == 1) continue;
			Overlay existingOverlay = imp.getOverlay();
			if (opacity > 0) {
				final ZProjector zp = new ZProjector(new ImagePlus("", imp
					.getChannelProcessor()));
				zp.setStartSlice(1);
				zp.setStopSlice(imp.getNSlices());
				zp.setMethod(ZProjector.MAX_METHOD);
				if (imp.getType() == ImagePlus.COLOR_RGB) {
					zp.doRGBProjection(); // 2017.10: side views of hyperstacks are RGB images 
				} else {
					zp.doHyperStackProjection(false);
				}
				final ImagePlus overlay = zp.getProjection();
				// (This logic is taken from OverlayCommands.)
				final ImageRoi roi = new ImageRoi(0, 0, overlay.getProcessor());
				roi.setName(OVERLAY_IDENTIFIER);
				roi.setOpacity(opacity);
				if (existingOverlay == null) existingOverlay = new Overlay();
				existingOverlay.add(roi);
			}
			else {
				removeMIPfromOverlay(existingOverlay);
			}
			imp.setOverlay(existingOverlay);
			imp.setHideOverlay(false);
		}
	}

	private void removeMIPfromOverlay(final Overlay overlay) {
		if (overlay != null && overlay.size() > 0) {
			for (int i = overlay.size() - 1; i >= 0; i--) {
				final String roiName = overlay.get(i).getName();
				if (roiName != null && roiName.equals(OVERLAY_IDENTIFIER)) {
					overlay.remove(i);
					return;
				}
			}
		}
	}

	protected void discreteMsg(final String msg) {  /* HTML format */
		new GuiUtils(getActiveWindow()).tempMsg(msg);
	}

	protected boolean getConfirmation(final String msg, final String title) {
		return new GuiUtils(getActiveWindow()).getConfirmation(msg, title);
	}

	protected void toogleSnapCursor() {
		enableSnapCursor(!snapCursor);
	}

	public synchronized void enableSnapCursor(final boolean enable) {
		snapCursor = enable;
		if (isUIready()) {
			resultsDialog.useSnapWindow.setSelected(enable);
			resultsDialog.snapWindowXYsizeSpinner.setEnabled(enable);
			resultsDialog.snapWindowZsizeSpinner.setEnabled(enable && !is2D());
		}
	}

	public void enableAutoActivation(final boolean enable) {
		autoCanvasActivation = enable;
	}

	// TODO: Use prefsService
	private boolean manualOverride = false;
	public void disableAstar(final boolean disable) {
		manualOverride = disable;
	}

	public boolean isAstarDisabled() {
		return manualOverride;
	}

	/**
	 * @return true if the image currently loaded does not have a depth (Z)
	 *         dimension
	 */
	public boolean is2D() {
		return singleSlice;
	}

	protected boolean drawDiametersXY = Prefs.get(
		"tracing.Simple_Neurite_Tracer.drawDiametersXY", "false").equals("true");

	public void setDrawDiametersXY(final boolean draw) {
		drawDiametersXY = draw;
		repaintAllPanes();
	}

	public boolean getDrawDiametersXY() {
		return drawDiametersXY;
	}

	@Override
	public void closeAndResetAllPanes() {
		// Dispose xz/zy images unless the user stored some annotations (ROIs)
		// on the image overlay or modified them somehow. In that case, restore
		// them to the user
		if (!single_pane) {
			final ImagePlus[] impPanes = { xz, zy };
			final StackWindow[] winPanes = { xz_window, zy_window };
			for (int i = 0; i < impPanes.length; ++i) {
				final Overlay overlay = impPanes[i].getOverlay();
				removeMIPfromOverlay(overlay);
				if (!impPanes[i].changes && (overlay == null || impPanes[i].getOverlay()
					.size() == 0)) impPanes[i].close();
				else {
					winPanes[i] = new StackWindow(impPanes[i]);
					winPanes[i].getCanvas().add(ij.Menus.getPopupMenu());
					removeMIPfromOverlay(overlay);
					impPanes[i].setOverlay(overlay);
				}
			}
		}
		// Restore main view
		final Overlay overlay = (xy == null) ? null : xy.getOverlay();
		if (original_xy_canvas != null && xy != null && xy.getImage() != null) {
			xy_window = new StackWindow(xy, original_xy_canvas);
			removeMIPfromOverlay(overlay);
			xy.setOverlay(overlay);
			xy_window.getCanvas().add(ij.Menus.getPopupMenu());
		}

	}

	public Context getContext() {
		return context;
	}

	/**
	 * @return the main dialog of SNT's UI
	 */
	public NeuriteTracerResultsDialog getUI() {
		return resultsDialog;
	}

	@Override
	public void showStatus(int progress, int maximum, String status) {
		statusService.showStatus(progress, maximum, status);
		if (isUIready()) getUI().showStatus(status);
	}

}
