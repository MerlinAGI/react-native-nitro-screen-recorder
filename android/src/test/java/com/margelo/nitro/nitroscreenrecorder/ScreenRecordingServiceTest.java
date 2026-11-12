package com.margelo.nitro.nitroscreenrecorder;

import static org.junit.Assert.*;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
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
import com.facebook.react.bridge.BridgeReactContext;
import com.margelo.nitro.NitroModules;
import com.margelo.nitro.core.Promise;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {ScreenRecordingServiceTest.RecorderShadow.class,
    ScreenRecordingServiceTest.DisplayShadow.class, ScreenRecordingServiceTest.SpecShadow.class,
    ScreenRecordingServiceTest.PromiseShadow.class, ScreenRecordingServiceTest.NitroLoaderShadow.class})
public class ScreenRecordingServiceTest {
  private ScreenRecordingService service;
  private DisplayShadow display;
  private File directory;

  @Before
  public void setUp() {
    ShadowLog.clear();
    RecorderShadow.recorders.clear();
    RecorderShadow.failureStage = null;
    RecorderShadow.failure = new IllegalStateException("injected recorder failure");
    RecorderShadow.onStart = null;
    RecorderShadow.onStop = null;
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
    RecorderShadow.onStop = null;
    RecorderShadow.hasReleaseFailure = false;
    ReflectionHelpers.setStaticField(NitroScreenRecorder.class, "instance", null);
    NitroModules.setApplicationContext(null);
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
  public void failedStopRejectsOnceAndAllowsANewChunk() {
    service.markChunkStart();
    RecorderShadow failed = RecorderShadow.recorders.get(0);
    RecorderShadow.failureStage = "stop";

    IllegalStateException failure = assertThrows(IllegalStateException.class, service::finalizeChunk);
    assertTrue(failure.getMessage().startsWith("CHUNK_FINALIZE_FAILED:stop"));
    assertSame(RecorderShadow.failure, failure.getCause());
    assertFalse(service.isCapturingChunk());
    assertFalse(service.isCurrentlyRecording());
    assertEquals(0.0, service.getChunkStartedAt(), 0.0);
    assertEquals(ShadowMediaRecorder.STATE_RELEASED, failed.getState());
    assertFalse(new File(failed.getOutputPath()).exists());
    assertNull(service.finalizeChunk());
    assertEquals(1, failed.stopCount);
    assertFalse(ShadowLog.getLogsForTag("NitroScreenRecorder").stream().anyMatch(
        entry -> entry.msg.contains("notifyGlobalRecordingError")));

    RecorderShadow.failureStage = null;
    service.markChunkStart();
    File next = service.finalizeChunk();
    assertNotNull(next);
    assertTrue(next.exists());
    assertNotEquals(failed.getOutputPath(), next.getAbsolutePath());
  }

  @Test
  public void failedGlobalStopDoesNotPublishAnInvalidFile() {
    service.markChunkStart();
    RecorderShadow.failureStage = "stop";
    assertNull(service.stopRecording());
    assertFalse(service.isCapturingChunk());
    assertFalse(service.isCurrentlyRecording());
    assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecorderShadow.recorders.get(0).getState());
    assertFalse(new File(RecorderShadow.recorders.get(0).getOutputPath()).exists());
    assertFalse(ShadowLog.getLogsForTag("NitroScreenRecorder").stream().anyMatch(
        entry -> entry.msg.contains("notifyGlobalRecordingFinished")));
  }

  @Test
  public void bridgeRejectsFailedStopInsteadOfReturningPreviousChunk() throws Exception {
    NitroScreenRecorder bridge = createBridge();
    service.markChunkStart();
    File previous = service.finalizeChunk();
    ReflectionHelpers.setField(bridge, "lastGlobalRecording", previous);
    service.markChunkStart();
    RecorderShadow.failureStage = "stop";

    PromiseShadow promise = Shadow.extract(bridge.stopGlobalRecording(0));
    ExecutionException error = assertThrows(ExecutionException.class,
        () -> promise.outcome.get(5, TimeUnit.SECONDS));
    assertTrue(error.getCause().getMessage().contains("GLOBAL_STOP_FAILED:RecordingStopError"));
    assertTrue(error.getCause().getMessage().contains("injected recorder failure"));
    assertTrue(previous.exists());
    assertFalse(new File(RecorderShadow.recorders.get(1).getOutputPath()).exists());
    assertFalse(service.hasActiveSession());
  }

  @Test
  public void bridgeReturnsCurrentSuccessfulStopAfterPreviousChunk() throws Exception {
    NitroScreenRecorder bridge = createBridge();
    service.markChunkStart();
    File previous = service.finalizeChunk();
    ReflectionHelpers.setField(bridge, "lastGlobalRecording", previous);
    ScreenRecordingFile previousResult = bridge.retrieveLastGlobalRecording();
    service.markChunkStart();
    String currentPath = RecorderShadow.recorders.get(1).getOutputPath();

    PromiseShadow promise = Shadow.extract(bridge.stopGlobalRecording(0));
    Object result = promise.outcome.get(5, TimeUnit.SECONDS);
    assertNotNull(result);
    assertNotEquals(previousResult, result);
    assertEquals(bridge.retrieveLastGlobalRecording(), result);
    assertEquals("file://" + currentPath, bridge.retrieveLastGlobalRecording().getPath());
  }

  @Test
  public void bridgeWaitsForStopCompletionInsteadOfReadingPreviousChunk() throws Exception {
    NitroScreenRecorder bridge = createBridge();
    service.markChunkStart();
    File previous = service.finalizeChunk();
    ReflectionHelpers.setField(bridge, "lastGlobalRecording", previous);
    service.markChunkStart();
    CountDownLatch requested = new CountDownLatch(1);
    NitroModules.setApplicationContext(new BridgeReactContext(service) {
      @Override public ComponentName startService(Intent intent) {
        requested.countDown();
        return new ComponentName(service, ScreenRecordingService.class);
      }
    });
    PromiseShadow promise = Shadow.extract(bridge.stopGlobalRecording(0));
    try {
      assertTrue(requested.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> promise.outcome.get(100, TimeUnit.MILLISECONDS));
    } finally {
      service.stopRecording();
      assertNotNull(promise.outcome.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void bridgeStoppingBetweenChunksDoesNotReturnPreviousChunk() throws Exception {
    NitroScreenRecorder bridge = createBridge();
    service.markChunkStart();
    File previous = service.finalizeChunk();
    ReflectionHelpers.setField(bridge, "lastGlobalRecording", previous);
    PromiseShadow promise = Shadow.extract(bridge.stopGlobalRecording(0));
    assertNull(promise.outcome.get(5, TimeUnit.SECONDS));
    assertTrue(previous.exists());
  }

  private NitroScreenRecorder createBridge() {
    NitroModules.setApplicationContext(new BridgeReactContext(service) {
      @Override public ComponentName startService(Intent intent) {
        service.onStartCommand(intent, 0, 42);
        return new ComponentName(service, ScreenRecordingService.class);
      }
    });
    NitroScreenRecorder bridge = new NitroScreenRecorder();
    ReflectionHelpers.setField(bridge, "globalRecordingService", service);
    ReflectionHelpers.setField(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
    return bridge;
  }

  @Implements(HybridNitroScreenRecorderSpec.class)
  public static class SpecShadow {
    @Implementation protected void __constructor__() {}
  }

  @Implements(Promise.class)
  public static class PromiseShadow {
    final CompletableFuture<Object> outcome = new CompletableFuture<>();
    @Implementation protected void __constructor__() {}
    @Implementation protected void nativeResolve(Object result) { outcome.complete(result); }
    @Implementation protected void nativeReject(Throwable error) { outcome.completeExceptionally(error); }
  }

  @Implements(className = "com.margelo.nitro.JNIOnLoad", isInAndroidSdk = false)
  public static class NitroLoaderShadow {
    @Implementation protected static void initializeNativeNitro() {}
  }

  @Test
  public void failedDetachTerminatesSessionAndReleasesRecorder() {
    service.markChunkStart();
    display.failuresRemaining = 1;
    IllegalStateException failure = assertThrows(IllegalStateException.class, service::finalizeChunk);
    assertTrue(failure.getMessage().startsWith("CHUNK_FINALIZE_FAILED:detach-surface"));
    assertTrue(display.isReleased);
    assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecorderShadow.recorders.get(0).getState());
    assertFalse(new File(RecorderShadow.recorders.get(0).getOutputPath()).exists());
    assertFalse(service.isCapturingChunk());
    assertThrows(IllegalStateException.class, service::markChunkStart);
  }

  @Test
  public void failedReleasePreservesStopFailureAndTerminatesSession() {
    service.markChunkStart();
    RecorderShadow.failureStage = "stop";
    RecorderShadow.hasReleaseFailure = true;
    IllegalStateException failure = assertThrows(IllegalStateException.class, service::finalizeChunk);
    assertSame(RecorderShadow.failure, failure.getCause());
    assertTrue(failure.getCause().getSuppressed().length > 0);
    assertTrue(display.isReleased);
    assertFalse(new File(RecorderShadow.recorders.get(0).getOutputPath()).exists());
    assertFalse(service.isCapturingChunk());
    assertThrows(IllegalStateException.class, service::markChunkStart);
  }

  @Test
  public void projectionStopBetweenChunksPreventsRestartOnAnExpiredSession() {
    ReflectionHelpers.setField(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
    service.markChunkStart();
    File completed = service.finalizeChunk();
    MediaProjection.Callback callback = ReflectionHelpers.getField(service, "mediaProjectionCallback");
    callback.onStop();
    assertFalse(service.hasActiveSession());
    assertTrue(display.isReleased);
    assertTrue(completed.exists());
    assertThrows(IllegalStateException.class, service::markChunkStart);
  }

  @Test
  public void repeatedFinalizeDoesNotStopOrDeleteTheCompletedChunk() {
    service.markChunkStart();
    File completed = service.finalizeChunk();
    assertNull(service.finalizeChunk());
    assertNull(service.stopRecording());
    assertEquals(1, RecorderShadow.recorders.get(0).stopCount);
    assertTrue(completed.exists());
  }

  @Test
  public void finalizationWaitsForStartupToFinish() throws Exception {
    assertWaitsForStartup(service::finalizeChunk);
  }

  @Test
  public void stoppingWaitsForStartupToFinish() throws Exception {
    assertWaitsForStartup(service::stopRecording);
  }

  @Test
  public void globalStopWaitsForFinalizationAndKeepsCompletedFile() throws Exception {
    ReflectionHelpers.setField(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
    service.markChunkStart();
    RecorderShadow recording = RecorderShadow.recorders.get(0);
    assertWaitsForRecorderOperation(service::finalizeChunk, service::stopRecording,
        hook -> RecorderShadow.onStop = hook);
    assertEquals(1, recording.stopCount);
    assertTrue(new File(recording.getOutputPath()).exists());
    assertFalse(service.hasActiveSession());
  }

  private void assertWaitsForStartup(Runnable finish) throws Exception {
    assertWaitsForRecorderOperation(service::markChunkStart, finish,
        hook -> RecorderShadow.onStart = hook);
  }

  private void assertWaitsForRecorderOperation(Runnable start, Runnable finish,
      Consumer<Runnable> setHook) throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch allowStart = new CountDownLatch(1);
    CountDownLatch finishRequested = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    setHook.accept(() -> {
      started.countDown();
      try {
        if (!allowStart.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start was not released");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
    });
    try {
      Future<?> starting = executor.submit(start);
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
    static RuntimeException failure;
    static String failureStage;
    static Runnable onStart;
    static Runnable onStop;
    static boolean hasReleaseFailure;
    boolean didStop;
    int stopCount;

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
      stopCount++;
      if (onStop != null) onStop.run();
      if ("stop".equals(failureStage)) throw failure;
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
