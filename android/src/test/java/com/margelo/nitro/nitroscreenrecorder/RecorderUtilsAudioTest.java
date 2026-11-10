package com.margelo.nitro.nitroscreenrecorder;

import static org.junit.Assert.*;

import android.media.MediaExtractor;
import com.margelo.nitro.nitroscreenrecorder.utils.RecorderUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = RecorderUtilsAudioTest.FailedExtractorShadow.class)
public class RecorderUtilsAudioTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void rapidChunkAllocationsReserveDistinctFiles() throws Exception {
    assertDistinctAllocations("chunk_audio");
  }

  @Test
  public void rapidGlobalAllocationsReserveDistinctFiles() throws Exception {
    assertDistinctAllocations("global_recording_audio");
  }

  private void assertDistinctAllocations(String prefix) throws Exception {
    File directory = temporary.newFolder();
    List<File> files = IntStream.range(0, 100)
        .mapToObj(index -> RecorderUtils.INSTANCE.createAudioOutputFile(directory, prefix))
        .collect(Collectors.toList());
    assertEquals(files.size(), new HashSet<>(files).size());
    files.forEach(file -> {
      assertTrue(file.isFile());
      assertEquals(directory, file.getParentFile());
      assertTrue(file.getName().startsWith(prefix + "_"));
      assertTrue(file.getName().endsWith(".m4a"));
    });
  }

  @Test
  public void concurrentWritersAndFailedExtractionKeepSeparateOutputs() throws Exception {
    File directory = temporary.newFolder();
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch allocated = new CountDownLatch(8);
    List<Callable<File>> writes = IntStream.range(0, 8).<Callable<File>>mapToObj(index -> () -> {
      File output = RecorderUtils.INSTANCE.createAudioOutputFile(directory, "chunk_audio");
      allocated.countDown();
      assertTrue(allocated.await(5, TimeUnit.SECONDS));
      Files.write(output.toPath(), ("audio-" + index).getBytes(StandardCharsets.UTF_8));
      return output;
    }).collect(Collectors.toList());
    try {
      List<Future<File>> results = executor.invokeAll(writes);
      List<File> files = new java.util.ArrayList<>();
      for (int index = 0; index < results.size(); index++) {
        File output = results.get(index).get(5, TimeUnit.SECONDS);
        files.add(output);
        assertEquals("audio-" + index, new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
      }
      assertEquals(files.size(), new HashSet<>(files).size());

      File failedOutput = RecorderUtils.INSTANCE.createAudioOutputFile(directory, "chunk_audio");
      File invalidVideo = temporary.newFile("invalid.mp4");
      assertFalse(RecorderUtils.INSTANCE.extractAudioFromVideo(invalidVideo, failedOutput));
      assertFalse(failedOutput.exists());
      for (int index = 0; index < files.size(); index++) {
        assertEquals("audio-" + index, new String(Files.readAllBytes(files.get(index).toPath()), StandardCharsets.UTF_8));
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void createsTheRequestedDirectory() {
    File directory = new File(temporary.getRoot(), "nested/recordings");
    File output = RecorderUtils.INSTANCE.createAudioOutputFile(directory, "chunk_audio");
    assertTrue(output.isFile());
    assertEquals(directory, output.getParentFile());
  }

  @Test
  public void rejectsAFileInPlaceOfTheOutputDirectory() throws Exception {
    File directory = temporary.newFile();
    assertThrows(IOException.class, () -> RecorderUtils.INSTANCE.createAudioOutputFile(directory, "chunk_audio"));
    assertTrue(directory.isFile());
    assertEquals(0, directory.length());
  }

  @Test
  public void rejectsDirectoryCreationFailure() throws Exception {
    File blockedParent = temporary.newFile();
    File directory = new File(blockedParent, "recordings");
    assertThrows(IOException.class, () -> RecorderUtils.INSTANCE.createAudioOutputFile(directory, "global_recording_audio"));
    assertFalse(directory.exists());
    assertEquals(0, blockedParent.length());
  }

  @Implements(MediaExtractor.class)
  public static class FailedExtractorShadow {
    @Implementation
    protected void setDataSource(String path) throws IOException {
      throw new IOException("injected invalid media");
    }

    @Implementation
    protected void release() {}
  }
}
