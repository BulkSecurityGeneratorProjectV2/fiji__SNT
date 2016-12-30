/* -*- mode: java; c-basic-offset: 8; indent-tabs-mode: t; tab-width: 8 -*- */

/* Copyright 2006, 2007, 2008, 2009, 2010, 2011 Mark Longair */

/*
  This file is part of the ImageJ plugin "Auto Tracer".

  The ImageJ plugin "Auto Tracer" is free software; you can
  redistribute it and/or modify it under the terms of the GNU General
  Public License as published by the Free Software Foundation; either
  version 3 of the License, or (at your option) any later version.

  The ImageJ plugin "Auto Tracer" is distributed in the hope that it
  will be useful, but WITHOUT ANY WARRANTY; without even the implied
  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU General Public License for more details.

  In addition, as a special exception, the copyright holders give
  you permission to combine this program with free software programs or
  libraries that are released under the Apache Public License.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package tracing;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;

import features.TubenessProcessor;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import stacks.PaneOwner;
import stacks.ThreePanes;
import util.BatchOpener;

public class Auto_Tracer extends ThreePanes implements PlugIn, PaneOwner, SearchProgressCallback {

	int width;
	int height;
	int depth;

	HashSet<AutoPoint> done;
	PriorityQueue<AutoPoint> mostTubelikePoints;
	float[][] tubeValues;

	public boolean dimensionsIdentical(final ImagePlus a, final ImagePlus b) {
		return a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight() && a.getStackSize() == b.getStackSize();
	}

	/*
	 * Just for convenience, keep casted references to the superclass's
	 * TracerCanvas objects
	 */

	AutoTracerCanvas canvas;

	/* This override the method in ThreePanes... */

	@Override
	public TracerCanvas createCanvas(final ImagePlus imagePlus, final int plane) {
		return new AutoTracerCanvas(imagePlus, this, plane, null);
	}

	static class AutoTracerParameters {
		float tubenessThreshold;
		float minimumRollingMean;
	}

	AutoTracerParameters loadParameters(final String filename) {
		float tubenessThreshold = Float.MIN_VALUE;
		float minimumRollingMean = Float.MIN_VALUE;
		try {
			final BufferedReader br = new BufferedReader(new FileReader(filename));
			String lastLine;
			while (null != (lastLine = br.readLine())) {
				lastLine = lastLine.trim();
				if (lastLine.length() == 0)
					continue;
				if (lastLine.length() < 4)
					return null;
				if (lastLine.startsWith("t: ")) {
					final String rest = lastLine.substring(3);
					System.out.println("   For tubenessThreshold got string: '" + rest + "'");
					try {
						tubenessThreshold = Float.parseFloat(rest);
					} catch (final NumberFormatException e) {
						return null;
					}
				} else if (lastLine.startsWith("m: ")) {
					final String rest = lastLine.substring(3);
					System.out.println("   For minimumRollingMean got string: '" + rest + "'");
					try {
						minimumRollingMean = Float.parseFloat(rest);
					} catch (final NumberFormatException e) {
						return null;
					}
				}
			}
		} catch (final IOException e) {
			return null;
		}
		final AutoTracerParameters p = new AutoTracerParameters();
		p.tubenessThreshold = tubenessThreshold;
		p.minimumRollingMean = minimumRollingMean;
		return p;
	}

	public class TubenessComparator implements Comparator<AutoPoint> {

		int width, height, depth;
		float[][] tubeValues;

		public TubenessComparator(final int width, final int height, final int depth, final float[][] tubeValues) {
			this.width = width;
			this.height = height;
			this.depth = depth;
			this.tubeValues = tubeValues;
		}

		@Override
		public int compare(final AutoPoint a, final AutoPoint b) {
			return -Float.compare(tubeValues[a.z][a.y * width + a.x], tubeValues[b.z][b.y * width + b.x]);
		}

	}

	public void recreatePriorityQueue(final boolean checkDone) {

		System.out.println("  [Recreating Priority Queue]");
		mostTubelikePoints = new PriorityQueue<>(512, new TubenessComparator(width, height, depth, tubeValues));
		System.gc();

		for (int z = 0; z < depth; ++z) {
			for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					if (tubeValues[z][y * width + x] > tubenessThreshold) {
						final AutoPoint p = new AutoPoint(x, y, z);
						if (checkDone) {
							if (!done.contains(p))
								mostTubelikePoints.add(p);
						} else
							mostTubelikePoints.add(p);
					}
				}
			}
		}
		System.out.println("  [Done]");
	}

	boolean verbose = false;

	public void autoTrace(final ImagePlus image) {

		final Calibration calibration = image.getCalibration();

		final FileInfo originalFileInfo = image.getOriginalFileInfo();

		final String originalFileName = originalFileInfo.fileName;
		System.out.println("originalFileName is " + originalFileName);
		final int lastDot = originalFileName.lastIndexOf(".");
		final String beforeExtension = originalFileName.substring(0, lastDot);
		final String tubesFileName = beforeExtension + ".tubes.tif";
		final String thresholdsFileName = beforeExtension + ".thresholds";
		final String outputFileName = beforeExtension + ".traces.obj";
		ImagePlus tubenessImage = null;
		final File tubesFile = new File(originalFileInfo.directory, tubesFileName);
		if (tubesFile.exists()) {
			IJ.showStatus("Loading tubes file.");
			tubenessImage = BatchOpener.openFirstChannel(tubesFile.getAbsolutePath());
			if (tubenessImage == null) {
				IJ.error("Failed to load tubes image from " + tubesFile.getAbsolutePath());
				return;
			}
		} else {
			IJ.showStatus("No tubes file found, generating anew...");

			double minimumSeparation = 1;
			if (calibration != null)
				minimumSeparation = Math.min(Math.abs(calibration.pixelWidth),
						Math.min(Math.abs(calibration.pixelHeight), Math.abs(calibration.pixelDepth)));

			final TubenessProcessor tubifier = new TubenessProcessor(minimumSeparation, true);
			tubenessImage = tubifier.generateImage(image);
			System.out.println("Got tubes file.");
			final boolean saved = new FileSaver(tubenessImage).saveAsTiffStack(tubesFile.getAbsolutePath());
			if (!saved) {
				IJ.error("Failed to save tubes image to " + tubesFile.getAbsolutePath());
				return;
			}
		}
		if (!dimensionsIdentical(image, tubenessImage)) {
			IJ.error("The dimensions of the image and the tube image didn't match.");
			return;
		}

		// If there is a file with the thresholds that we'd
		// otherwise query for, load that and use them:
		final File thresholdsFile = new File(originalFileInfo.directory, thresholdsFileName);
		System.out.println("Testing for the existence of: " + thresholdsFile.getAbsolutePath());
		if (thresholdsFile.exists()) {
			final AutoTracerParameters p = loadParameters(thresholdsFile.getAbsolutePath());
			if (p == null) {
				throw new RuntimeException(
						"The thresholds file '" + thresholdsFile.getAbsolutePath() + "' was corrupted somehow.");
			}
			tubenessThreshold = p.tubenessThreshold;
			minimumRollingMean = p.minimumRollingMean;
		} else {
			/*
			 * if (true) throw new
			 * RuntimeException("Tried to create a generic dialog!");
			 */ // Pop up a GenericDialog to ask:
			final GenericDialog gd = new GenericDialog("Auto Tracer");
			gd.addNumericField("Tubeness threshold for destinations", tubenessThreshold, 2);
			gd.addNumericField("Minimum rolling mean tubeness", minimumRollingMean, 2);
			gd.addMessage("(For help about these options, please go to: http://fruitfly.inf.ed.ac.uk/auto-tracer/ )");
			gd.showDialog();
			if (gd.wasCanceled())
				return;
			tubenessThreshold = (float) gd.getNextNumber();
			minimumRollingMean = (float) gd.getNextNumber();
			if (tubenessThreshold < 0) {
				throw new RuntimeException("Tubeness threshold for destinations must be positive");
			}
			if (minimumRollingMean < 0) {
				throw new RuntimeException("Minimum rolling mean tubeness must be positive");
			}
			if (minimumRollingMean > tubenessThreshold) {
				throw new RuntimeException(
						"It doesn't make sense for tubeness threshold for destinations to be less than the minimum rolling mean tubeness.");
			}
		}

		totalTimeStarted = System.currentTimeMillis();

		System.out.println("Now using tubenessThreshold: " + tubenessThreshold);
		System.out.println("     and minimumRollingMean: " + minimumRollingMean);

		width = image.getWidth();
		height = image.getHeight();
		depth = image.getStackSize();
		final ImageStack tubeStack = tubenessImage.getStack();
		tubeValues = new float[depth][];
		for (int z = 0; z < depth; ++z) {
			tubeValues[z] = (float[]) tubeStack.getPixels(z + 1);
		}

		done = new HashSet<>();

		recreatePriorityQueue(false);

		System.out.println("Initial points: " + mostTubelikePoints.size());

		if (false)
			return;

		final SinglePathsGraph completePaths = new SinglePathsGraph(width, height, depth,
				Math.abs(calibration.pixelWidth), Math.abs(calibration.pixelHeight), Math.abs(calibration.pixelDepth));

		final int maxLoops = -1;
		int loopsDone = 0;

		while (mostTubelikePoints.size() > 0) {

			final long currentTime = System.currentTimeMillis();
			final long secondsSinceStart = (currentTime - totalTimeStarted) / 1000;
			if (secondsSinceStart > totalTimeLimitSeconds)
				break;

			if (maxLoops >= 0 && loopsDone >= maxLoops)
				break;

			// Now get the most tubelike point:
			final AutoPoint startPoint = mostTubelikePoints.poll();

			if (done.contains(startPoint)) {
				continue;
			}

			System.out.println("=== Done size is: " + done.size());
			System.out.println("=== Priority queue now has: " + mostTubelikePoints.size());
			System.out.println("=== Loops done: " + loopsDone);

			System.out.println("  Got point " + startPoint + " with tubeness: "
					+ tubeValues[startPoint.z][startPoint.y * width + startPoint.x]);

			// Move to that slice, just for presentation purposes:
			if (liveDisplay)
				image.setSlice(startPoint.z + 1);

			ast = new AutoSearchThread(image, /* original image */
					tubeValues, /* the "tubeness" filtered image */
					startPoint, /* the point to start the search from */
					tubenessThreshold, completePaths);

			threadTimeStarted = currentTime;

			ast.setDrawingColors(Color.BLUE, Color.CYAN);
			ast.setDrawingThreshold(-1);

			ast.addProgressListener(this);

			if (liveDisplay)
				canvas.addSearchThread(ast);

			ast.start();

			try {
				ast.join();
			} catch (final InterruptedException e) {
			}

			if (liveDisplay)
				canvas.removeSearchThread(ast);

			// Now start the pruning:

			final ArrayList<AutoPoint> destinations = ast.getDestinations();
			System.out.println("  === Destinations: " + destinations.size());
			if (verbose)
				System.out.print("  === Destinations: " + destinations.size() + " ");
			if (verbose)
				System.out.flush();

			for (final AutoPoint d : destinations) {

				if (verbose)
					System.out.print("    ");

				final Path path = ast.getPathBack(d.x, d.y, d.z);

				final float[] rollingTubeness = new float[rollingLength];
				int nextRollingAt = 0;
				int slotsFilled = 0;

				int lastIndex = path.size() - 1;

				if (minimumPointsOnPath >= 0 && path.size() < minimumPointsOnPath) {

					lastIndex = -1;

				} else {

					for (int i = 0; i < path.size(); ++i) {

						if (verbose)
							System.out.print(".");
						if (verbose)
							System.out.flush();

						final int pax = path.getXUnscaled(i);
						final int pay = path.getYUnscaled(i);
						final int paz = path.getZUnscaled(i);

						final float tubenessThere = tubeValues[paz][pay * width + pax];

						rollingTubeness[nextRollingAt] = tubenessThere;

						if (slotsFilled < nextRollingAt + 1)
							slotsFilled = nextRollingAt + 1;

						// Now calculate the mean...

						float mean = 0;
						for (int s = 0; s < slotsFilled; ++s) {
							mean += rollingTubeness[s];
						}
						mean /= slotsFilled;

						if (mean < minimumRollingMean) {
							lastIndex = (i + 1) - slotsFilled;
							break;
						}

						if (nextRollingAt == rollingLength - 1)
							nextRollingAt = 0;
						else
							++nextRollingAt;
					}
				}

				AutoPoint current = null;
				AutoPoint last = null;

				final HashSet<AutoPoint> destinationsToPrune = new HashSet<>();

				for (int i = 0; i <= lastIndex; ++i) {

					if (verbose)
						System.out.print("#");
					if (verbose)
						System.out.flush();

					// If the tubeness is above threshold, add this to the list
					// to prune:

					final int pax = path.getXUnscaled(i);
					final int pay = path.getYUnscaled(i);
					final int paz = path.getZUnscaled(i);

					final float tubenessThere = tubeValues[paz][pay * width + pax];

					current = new AutoPoint(pax, pay, paz);

					if (tubenessThere > tubenessThreshold) {
						destinationsToPrune.add(current);
					}

					// And add it to the full graph:

					completePaths.addPoint(current, last);

					last = current;
				}

				// Now remove all the destinations
				// genuinely found in this search:

				for (final AutoPoint toRemove : destinationsToPrune) {

					if (verbose)
						System.out.flush();

					done.add(toRemove);
				}

				if (verbose)
					System.out.println("");
			}

			ast = null;

			System.gc();

			/*
			 * Check the memory usage, and recreate the PriorityQueue if it's
			 * going too high (and every 50 loops in any case). It's not clear
			 * how trustworthy these memory statistics are, unfortunately.
			 */

			final long freeMem = Runtime.getRuntime().freeMemory();
			final long totMem = Runtime.getRuntime().totalMemory();
			final int percentUsed = (int) (((totMem - freeMem) * 100) / totMem);

			System.out.println("=== Memory usage: " + percentUsed + "%");
			if ((percentUsed > 95) || ((loopsDone % 50) == 49)) {
				recreatePriorityQueue(true);
				if (mostTubelikePoints.size() == 0)
					break;
			}

			++loopsDone;
		}

		final File outputFile = new File(originalFileInfo.directory, outputFileName);

		try {
			completePaths.writeWavefrontObj(outputFile.getAbsolutePath());
		} catch (final IOException e) {
			IJ.error("Writing the Wavefront OBJ file '" + outputFile.getAbsolutePath() + "' failed");
			return;
		}
	}

	AutoSearchThread ast;

	long totalTimeLimitSeconds = 1 * 60;
	long totalTimeStarted;

	long threadTimeStarted;

	int maxNodes = 22000; // Takes about 10 seconds to do this on a 1.8GHz Duron
	int maxSeconds = 120;
	float tubenessThreshold = 18f;
	float minimumRollingMean = 5.0f;
	int rollingLength = 4;

	// If you only want to add paths that have more than a certain
	// number of points in them, change this...
	int minimumPointsOnPath = -1;

	/*
	 * This is a (hopefully accurate) description of the algorithm we're using
	 * here for automatic tracing. The following parameters must be picked
	 * manually:
	 *
	 *
	 * [A] Tubeness threshold [B] Max time for each search [C] Max iterations
	 * for each search [D] Minimum mean tubeness along path segment [E] Length
	 * of path segment used to calculate "mean tubeness"
	 *
	 * - The image is preprocessed to find a "tubeness" value for each point in
	 * the image. This gives a score to each point according to how tube-like
	 * the local shape of the image is.
	 *
	 * - The user should pick a threshold [A] for these tubneness values such
	 * at:
	 *
	 * - Almost all of the values above that threshold genuinely seem to be
	 * parts of neuron-like structures. However, one shouldn't set the threshold
	 * such that there are clear paths along the neurons of interest - scattered
	 * points are fine, so the aim should be to minimize "false positives" (i.e.
	 * points above the threshold that are not plausibly part of any neuron-like
	 * structure.
	 *
	 * - The above threshold points are put into a priority queue, where the
	 * most tube-like points are the first to be removed from the queue.
	 *
	 * - While there are still points in the priority queue, we do the following
	 * loop:
	 *
	 * - Extract the most tube-like point left in the priority queue.
	 *
	 * - Begin a best-first search from that point. Carry on until a given
	 * number of iterations have been reached [C] or a certain amount of time
	 * has been reached [B].
	 *
	 * - When each new point is added in the search check whether it is either
	 * in a path found on a previous iteration or above the tubeness threshold
	 * [A]. If so, add that to a hashtable H.
	 *
	 * - Once the search has terminated we first build paths from the start
	 * point to each of the points we recorded in the hashtable. There should be
	 * a *lot* of overlap, so while building this we:
	 *
	 * - Reuse bits of paths that we've already found. (Approximately
	 * described...)
	 *
	 * We also record the rolling tubeness value over a certain distance.
	 *
	 * - Now we want to exclude any bits of paths that might have big gaps in
	 * them.
	 *
	 * - Delete parts of the paths where the rolling average drops too low.
	 *
	 * - For all of the points above the tubeness threshold that we can still
	 * reach after the pruning, remove them from the most-tube like priority
	 * queue.
	 *
	 */

	boolean liveDisplay = true;

	@Override
	public void run(final String arg0) {

		final ImagePlus image = IJ.getImage();
		if (image == null) {
			IJ.error("No current image for automatic tracing.");
			return;
		}

		final long width = image.getWidth();
		final long height = image.getHeight();
		final long depth = image.getStackSize();

		final long pointsInImage = width * height * depth;
		if (pointsInImage >= Integer.MAX_VALUE) {
			IJ.error("This plugin currently only works with images with less that " + Integer.MAX_VALUE + " points.");
			return;
		}

		final String macroOptions = Macro.getOptions();
		if (macroOptions != null) {
			final String liveValue = Macro.getValue(macroOptions, "live", "");
			final String lower = liveValue.toLowerCase();
			if (lower.length() > 0
					&& (lower.equals("no") || lower.equals("f") || lower.equals("false") || lower.equals("n")))
				liveDisplay = false;
		}

		single_pane = true;

		if (liveDisplay) {
			initialize(image);
			canvas = (AutoTracerCanvas) xy_canvas;
		}

		autoTrace(image);
	}

	// ------------------------------------------------------------------------
	// Implement the methods in SearchProgressCallback here.
	// (Comments repeated from the interface file.)

	/* How many points have we considered? */

	@Override
	public void pointsInSearch(final SearchInterface source, final int inOpen, final int inClosed) {
		if (liveDisplay)
			repaintAllPanes();
		// Also check whether we're over the requested number
		// of iterations or time:
		final long currentTime = System.currentTimeMillis();
		final long timeSinceStarted = currentTime - threadTimeStarted;
		if ((inOpen + inClosed) > maxNodes || (timeSinceStarted / 1000) > maxSeconds) {
			if (verbose)
				System.out.println("### Requesting stop...");
			ast.requestStop();
		}
	}

	/*
	 * Once finished is called, you should be able to get the result from
	 * whatever means you've implemented, e.g. TracerThreed.getResult()
	 */

	@Override
	public void finished(final SearchInterface source, final boolean success) {
		final long currentTime = System.currentTimeMillis();
		final long secondsSinceThreadStarted = (currentTime - threadTimeStarted) / 1000;

		// FIXME: a quick hack to make this compile again, since we're not using
		// it much:
		final SearchThread sourceThread = (SearchThread) source;

		// Just log how many nodes were explored in that time:
		System.out.println("  " + (sourceThread.open_from_start.size() + sourceThread.closed_from_start.size())
				+ " nodes in " + secondsSinceThreadStarted + " seconds");
	}

	/*
	 * This reports the current status of the thread, which may be:
	 *
	 * SearchThread.RUNNING SearchThread.PAUSED SearchThread.STOPPING
	 */

	@Override
	public void threadStatus(final SearchInterface source, final int currentStatus) {

	}

	// ------------------------------------------------------------------------

}
