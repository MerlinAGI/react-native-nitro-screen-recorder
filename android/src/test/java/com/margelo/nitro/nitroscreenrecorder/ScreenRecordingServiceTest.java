package com.margelo.nitro.nitroscreenrecorder;

import static org.junit.Assert.*;

import android.app.Notification;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowMediaRecorder;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowService;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {ScreenRecordingServiceTest.RecorderShadow.class,
    ScreenRecordingServiceTest.DisplayShadow.class})
public class ScreenRecordingServiceTest {
  private ScreenRecordingService service;
  private DisplayShadow display;
  private File directory;

  @Before
  public void setUp() {
    ShadowLog.clear();
    RecorderShadow.recorders.clear();
    RecorderShadow.failureStage = null;
    RecorderShadow.onStart = null;
    RecorderShadow.hasReleaseFailure = false;
    service = Robolectric.buildService(ScreenRecordingService.class).create().get();
    VirtualDisplay virtualDisplay = Shadow.newInstanceOf(VirtualDisplay.class);
    display = Shadow.extract(virtualDisplay);
    ReflectionHelpers.setField(service, "virtualDisplay", virtualDisplay);
    File base = service.getExternalCacheDir();
    directory = new File(base != null ? base : service.getFilesDir(), "recordings");
  }

  @After
  public void tearDown() {
    RecorderShadow.onStart = null;
    RecorderShadow.hasReleaseFailure = false;
    service.onDestroy();
    File[] files = directory.listFiles();
    if (files != null) Arrays.stream(files).forEach(File::delete);
  }

  @Test
  public void missingDisplayRejectsWithoutClaimingCapture() {
    ReflectionHelpers.setField(service, "virtualDisplay", null);
    IllegalStateException failure = assertThrows(IllegalStateException.class, service::markChunkStart);
    assertEquals("CHUNK_START_FAILED:no-virtual-display", failure.getMessage());
    assertFalse(service.isCapturingChunk());
    assertFalse(service.isCurrentlyRecording());
  }

  @Test
  public void configurationFailureCleansUpAndAllowsRetry() {
    assertFailedStartCanRetry("configure");
  }

  @Test
  public void prepareFailureCleansUpAndAllowsRetry() {
    assertFailedStartCanRetry("prepare");
  }

  @Test
  public void startFailureCleansUpAndAllowsRetry() {
    assertFailedStartCanRetry("start");
  }

  private void assertFailedStartCanRetry(String stage) {
    RecorderShadow.failureStage = stage;
    IllegalStateException failure = assertThrows(IllegalStateException.class, service::markChunkStart);
    assertTrue(failure.getMessage().startsWith("CHUNK_START_FAILED:" + stage));
    assertSame(RecorderShadow.failure, failure.getCause());
    assertTrue(ShadowLog.getLogsForTag("ScreenRecordingService").stream().anyMatch(
        entry -> entry.type == Log.ERROR && entry.throwable == failure.getCause()));
    assertFalse(service.isCapturingChunk());
    assertFalse(service.isCurrentlyRecording());
    assertEquals(0.0, service.getChunkStartedAt(), 0.0);
    assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecorderShadow.recorders.get(0).getState());
    assertFalse(new File(RecorderShadow.recorders.get(0).getOutputPath()).exists());

    RecorderShadow.failureStage = null;
    service.markChunkStart();
    assertTrue(service.isCapturingChunk());
    assertTrue(service.isCurrentlyRecording());
    File recorded = service.finalizeChunk();
    assertNotNull(recorded);
    assertTrue(recorded.exists());
  }

  @Test
  public void failedSurfaceSwapStopsNewRecorderAndRestoresPreviousCapture() {
    service.markChunkStart();
    RecorderShadow previous = RecorderShadow.recorders.get(0);
    Surface previousSurface = display.surface;
    double previousStartedAt = service.getChunkStartedAt();
    display.failuresRemaining = 1;

    assertThrows(IllegalStateException.class, service::markChunkStart);
    RecorderShadow rejected = RecorderShadow.recorders.get(1);
    assertTrue(rejected.didStop);
    assertEquals(ShadowMediaRecorder.STATE_RELEASED, rejected.getState());
    assertFalse(new File(rejected.getOutputPath()).exists());
    assertSame(previousSurface, display.surface);
    assertEquals(ShadowMediaRecorder.STATE_RECORDING, previous.getState());
    assertTrue(new File(previous.getOutputPath()).exists());
    assertTrue(service.isCapturingChunk());
    assertEquals(previousStartedAt, service.getChunkStartedAt(), 0.0);

    service.markChunkStart();
    assertEquals(ShadowMediaRecorder.STATE_RELEASED, previous.getState());
    assertTrue(service.isCapturingChunk());
  }

  @Test
  public void failedSurfaceRestoreStopsForegroundServiceAndRemovesNotification() {
    service.onStartCommand(null, 0, 42);
    service.startForeground(1001, new Notification.Builder(service, "screen_recording_channel").build());
    ShadowService serviceState = Shadow.extract(service);
    assertNotNull(serviceState.getLastForegroundNotification());
    assertFalse(serviceState.isStoppedBySelf());
    service.markChunkStart();
    display.failuresRemaining = 2;
    IllegalStateException failure = assertThrows(IllegalStateException.class, service::markChunkStart);
    assertEquals(1, failure.getCause().getSuppressed().length);
    assertTrue(display.isReleased);
    assertTrue(serviceState.isForegroundStopped());
    assertNull(serviceState.getLastForegroundNotification());
    assertTrue(serviceState.isStoppedBySelf());
    assertEquals(42, serviceState.getStopSelfId());
    assertFalse(service.hasActiveSession());
    assertFalse(ScreenRecordingService.Companion.isSessionActive());
    assertTrue(RecorderShadow.recorders.get(0).didStop);
    assertTrue(RecorderShadow.recorders.get(1).didStop);
    assertFalse(service.isCapturingChunk());
    assertFalse(service.isCurrentlyRecording());
    assertThrows(IllegalStateException.class, service::markChunkStart);
  }

  @Test
  public void releaseFailureDoesNotHideStartupFailureOrSkipFileCleanup() {
    RecorderShadow.failureStage = "prepare";
    RecorderShadow.hasReleaseFailure = true;
    IllegalStateException failure = assertThrows(IllegalStateException.class, service::markChunkStart);
    assertSame(RecorderShadow.failure, failure.getCause());
    assertFalse(new File(RecorderShadow.recorders.get(0).getOutputPath()).exists());
  }

  @Test
  public void consecutiveChunksKeepDistinctFilesEvenWithinTheSameSecond() {
    service.markChunkStart();
    File first = service.finalizeChunk();
    service.markChunkStart();
    File second = service.finalizeChunk();
    assertNotNull(first);
    assertNotNull(second);
    assertNotEquals(first, second);
    assertTrue(first.exists());
    assertTrue(second.exists());
  }

  @Test
  public void finalizationWaitsForStartupToFinish() throws Exception {
    assertWaitsForStartup(service::finalizeChunk);
  }

  @Test
  public void stoppingWaitsForStartupToFinish() throws Exception {
    assertWaitsForStartup(service::stopRecording);
  }

  private void assertWaitsForStartup(Runnable finish) throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch allowStart = new CountDownLatch(1);
    CountDownLatch finishRequested = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    RecorderShadow.onStart = () -> {
      started.countDown();
      try {
        if (!allowStart.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start was not released");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
    };
    try {
      Future<?> starting = executor.submit(service::markChunkStart);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Future<?> finishing = executor.submit(() -> {
        finishRequested.countDown();
        finish.run();
      });
      assertTrue(finishRequested.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> finishing.get(100, TimeUnit.MILLISECONDS));
      allowStart.countDown();
      starting.get(5, TimeUnit.SECONDS);
      finishing.get(5, TimeUnit.SECONDS);
      assertFalse(service.isCapturingChunk());
      assertFalse(service.isCurrentlyRecording());
      assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecorderShadow.recorders.get(0).getState());
    } finally {
      allowStart.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Implements(MediaRecorder.class)
  public static class RecorderShadow extends ShadowMediaRecorder {
    static final List<RecorderShadow> recorders = new ArrayList<>();
    static final RuntimeException failure = new IllegalStateException("injected recorder failure");
    static String failureStage;
    static Runnable onStart;
    static boolean hasReleaseFailure;
    boolean didStop;

    @Implementation
    protected void setOutputFile(String path) {
      super.setOutputFile(path);
      recorders.add(this);
      if ("configure".equals(failureStage)) throw failure;
    }

    @Implementation
    protected void prepare() {
      if ("prepare".equals(failureStage)) throw failure;
      super.prepare();
    }

    @Implementation
    protected void start() {
      if (onStart != null) onStart.run();
      if ("start".equals(failureStage)) throw failure;
      super.start();
    }

    @Implementation
    protected void stop() {
      didStop = true;
      super.stop();
    }

    @Implementation
    protected void release() {
      super.release();
      if (hasReleaseFailure) throw new IllegalStateException("injected release failure");
    }
  }

  @Implements(VirtualDisplay.class)
  public static class DisplayShadow {
    Surface surface;
    int failuresRemaining;
    boolean isReleased;

    @Implementation
    protected Surface getSurface() { return surface; }

    @Implementation
    protected void setSurface(Surface next) {
      surface = next;
      if (failuresRemaining > 0) {
        failuresRemaining--;
        throw new IllegalStateException("injected surface failure");
      }
    }

    @Implementation
    protected void release() {
      isReleased = true;
      surface = null;
    }
  }
}
