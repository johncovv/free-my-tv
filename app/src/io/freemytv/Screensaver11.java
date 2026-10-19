package io.freemytv;

/**
 * The screensaver on Android 11, in the main process: there the system starts a dream's activity in the app's default
 * process, and a DreamService in another process (:home) crashes it (ClassCastException on DreamActivityCallback).
 * Android 12 and later use Screensaver, in :home with the home screen. The manifest enables one or the other.
 */
public class Screensaver11 extends Screensaver {}
