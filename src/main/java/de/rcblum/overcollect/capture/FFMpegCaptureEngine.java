package de.rcblum.overcollect.capture;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.Timer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber.Exception;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.rcblum.overcollect.capture.listener.ImageListener;
import de.rcblum.overcollect.capture.listener.ImageSource;
import de.rcblum.overcollect.configuration.OWItem;
import de.rcblum.overcollect.configuration.OWLib;
import de.rcblum.overcollect.utils.Helper;

public class FFMpegCaptureEngine implements ActionListener, ImageSource {
	private static final Logger LOGGER = LoggerFactory.getLogger(FFMpegCaptureEngine.class);

	private static GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();

	private List<ImageListener> listeners = new ArrayList<>(5);

	FFmpegFrameGrabber[] availableScreens = new FFmpegFrameGrabber[screens.length];

	// TODO: why use a Java Swing timer?? Is this class for an actual Swing
	// component?
	// Consider quartz or java.util.Timer instead.
	private Timer timer = null;

	private int captureInterval = 1000;

	private int selectedScreen = -1;

	long screenAutodetectCount = 0;

	private GraphicsDevice screen = null;

	private FFmpegFrameGrabber frameGrabber = null;

	private Java2DFrameConverter frameConverter = new Java2DFrameConverter();

	public FFMpegCaptureEngine() throws NullPointerException {
		int i = 0;
		for (GraphicsDevice graphicsDevice : screens) {
			Rectangle rect = graphicsDevice.getDefaultConfiguration().getBounds();
			FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("desktop");
			grabber.setFormat("gdigrab");
			grabber.setOption("offset_x", String.valueOf(rect.x));
			grabber.setOption("offset_y", String.valueOf(rect.y));
			grabber.setOption("vb", "90M");
			grabber.setOption("vcodec", "png");
			grabber.setBitsPerPixel(8);
			grabber.setImageWidth(rect.width);
			grabber.setImageHeight(rect.height);
			try {
				grabber.start();
				availableScreens[i++] = grabber;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				LOGGER.debug("Error initializing image grabber");
			}
		}
		boolean allNull = true;
		for (int j = 0; j < availableScreens.length; j++) {
			if (availableScreens[j] != null)
				allNull = false;
		}
		if (allNull)
			throw new NullPointerException("FFMpeg Capture Engine could not be initialized");
		this.captureInterval = OWLib.getInstance().getInteger("captureInterval", 1000);
		// this.screen = Objects.requireNonNull(screen);
		// this.r = new Robot(screen);
		LOGGER.info("captureInterval: {}", this.captureInterval);
		timer = new Timer(this.captureInterval, this);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		LOGGER.trace("Action performed.");
		if (this.screen == null) {
			autodetectScreen();
		} else {
			this.grabScreen();
		}
	}

	private void grabScreen() {
		LOGGER.trace("Grabbing screen...");
		try {
			Frame frame = this.frameGrabber.grabImage();
			BufferedImage br = new Java2DFrameConverter().getBufferedImage(frame);
			this.fireImage(br);
			if (OWLib.getInstance().getBoolean("debug.capture")) {
				this.saveCaptureScreen(br);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to grab screen.", e);
		}
	}

	private void saveCaptureScreen(BufferedImage br) {
		try {
			Path debugPath = Paths.get(OWLib.getInstance().getDebugDir(), "capture");
			if (!Files.exists(debugPath)) {
				Files.createDirectories(debugPath);
			}
			Path debugFile = debugPath
					.resolve(Helper.getSimpleDateFormatFile().format(new Date(System.currentTimeMillis())) + ".png");
			ImageIO.write(br, "PNG", debugFile.toFile());
		} catch (IOException ioe) {
			LOGGER.error("IOException: ", ioe);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#addImageListener(de.rcblum.
	 * overcollect.capture.listener.ImageListener)
	 */
	@Override
	public void addImageListener(ImageListener i) {
		listeners.add(i);
	}

	private void autodetectScreen() {
		LOGGER.debug("Searching for screen...");

		// Wait for Overwatch to launch
		OWLib lib = OWLib.getInstance();

		// if (screenAutodetectCount%(this.captureInterval/100)==0) {
		// LOGGER.info( "Autodetecting
		// Overwatch["+Math.round(screenAutodetectCount/(this.captureInterval/1000.0))+"
		// sec]...");
		// }
		screenAutodetectCount++;
		for (int i = 0; i < this.availableScreens.length; i++) {
			FFmpegFrameGrabber robot = this.availableScreens[i];
			GraphicsDevice screen = screens[i];
			if (robot != null) {
				try {
					Frame frame = robot.grabImage();
					BufferedImage br = this.frameConverter.getBufferedImage(frame);
					if (lib.supportScreenResolution(br.getWidth(), br.getHeight())) {
						OWItem itemStart = lib.getItem(br.getWidth(), br.getHeight(), "_start_screen");
						OWItem itemMain = lib.getItem(br.getWidth(), br.getHeight(), "_main_menu");
						if (itemStart.hasFilter() && itemStart.getFilter().match(br)
								|| itemMain.hasFilter() && itemMain.getFilter().match(br)) {
							this.screen = screen;
							this.frameGrabber = robot;
							this.selectedScreen = i;
							for (int j = 0; j < availableScreens.length; j++) {
								if (j != i && availableScreens[j] != null)
									availableScreens[j].stop();
							}
							LOGGER.info("Screen found");
							break;
						}
					}
				} catch (Exception e) {
					LOGGER.error("Exception: ", e);
				}
			}
		}
		// if (isRunning)
		// this.start();
	}

	public void fireImage(BufferedImage i) {
		for (ImageListener imageListener : listeners) {
			imageListener.addImage(i);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#getCaptureInterval()
	 */
	@Override
	public int getCaptureInterval() {
		return captureInterval;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#getResolution()
	 */
	@Override
	public Rectangle getResolution() {
		return selectedScreen >= 0 ? this.screens[selectedScreen].getDefaultConfiguration().getBounds() : null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#getScreen()
	 */
	@Override
	public GraphicsDevice getScreen() {
		return selectedScreen >= 0 ? this.screens[selectedScreen] : null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#isRunning()
	 */
	@Override
	public boolean isRunning() {
		return this.timer.isRunning();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#removeImageListener(de.rcblum.
	 * overcollect.capture.listener.ImageListener)
	 */
	@Override
	public void removeImageListener(ImageListener i) {
		listeners.remove(i);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#setCaptureInterval(int)
	 */
	@Override
	public void setCaptureInterval(int captureInterval) {
		this.captureInterval = captureInterval;
	}

	@Override
	public void setScreen(GraphicsDevice screen) throws AWTException {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#start()
	 */
	@Override
	public void start() {
		LOGGER.info("Start capture");
		this.timer.start();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.rcblum.overcollect.capture.ImageSource#stop()
	 */
	@Override
	public void stop() {
		LOGGER.info("Stop capture");
		this.timer.stop();
	}
}
