package com.margelo.nitro.nitroscreenrecorder;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.margelo.nitro.nitroscreenrecorder.utils.RecorderUtils;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecorderUtilsOptimizationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void replacementNeverUnlinksTheOnlyRecordingBeforeInstallingItsReplacement() throws Exception {
    File stored = temporary.newFile("chunk.mp4");
    Files.write(stored.toPath(), unoptimizedMp4());
    File input = new File(stored.getPath()) {
      @Override public boolean delete() {
        assertTrue(super.delete());
        throw new SecurityException("injected failure after unlink and before rename");
      }
    };

    File result = RecorderUtils.INSTANCE.optimizeForStreaming(input);

    assertTrue(result.isFile());
    byte[] bytes = Files.readAllBytes(result.toPath());
    assertEquals("moov", new String(bytes, 4, 4, StandardCharsets.US_ASCII));
    assertEquals(unoptimizedMp4().length, bytes.length);
    assertFalse(new File(temporary.getRoot(), "chunk_optimized.mp4").exists());
  }

  @Test
  public void failedReplacementLeavesTheOriginalRecordingAvailableToUpload() throws Exception {
    Path sharedMemory = Paths.get("/dev/shm");
    assumeTrue(Files.isDirectory(sharedMemory));
    assumeTrue(!Files.getFileStore(temporary.getRoot().toPath()).equals(Files.getFileStore(sharedMemory)));
    Path replacementDirectory = Files.createTempDirectory(sharedMemory, "recorder-rename-");
    File stored = temporary.newFile("chunk.mp4");
    byte[] original = unoptimizedMp4();
    Files.write(stored.toPath(), original);
    File input = new File(stored.getPath()) {
      @Override public String getParent() {
        return replacementDirectory.toString();
      }
    };
    try {
      File result = RecorderUtils.INSTANCE.optimizeForStreaming(input);

      assertEquals(input, result);
      assertArrayEquals(original, Files.readAllBytes(result.toPath()));
      assertFalse(replacementDirectory.resolve("chunk_optimized.mp4").toFile().exists());
    } finally {
      Files.deleteIfExists(replacementDirectory.resolve("chunk_optimized.mp4"));
      Files.delete(replacementDirectory);
    }
  }

  private static byte[] unoptimizedMp4() {
    return ByteBuffer.allocate(28)
        .putInt(12).put("mdat".getBytes(StandardCharsets.US_ASCII)).putInt(123)
        .putInt(16).put("moov".getBytes(StandardCharsets.US_ASCII)).putLong(0)
        .array();
  }
}
