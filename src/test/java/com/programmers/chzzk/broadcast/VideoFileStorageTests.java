package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoFileStorageTests {

	@TempDir
	Path tempDir;

	@Test
	void storesVideoFileUnderGeneratedUuidName() throws Exception {
		Path uploadDirectory = Files.createDirectory(tempDir.resolve("uploads"));
		VideoFileStorage storage = new VideoFileStorage(uploadDirectory);
		byte[] content = "fake mp4 bytes".getBytes(StandardCharsets.UTF_8);

		String fileName = storage.store(new ByteArrayInputStream(content));

		assertThat(fileName).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mp4");
		assertThat(uploadDirectory.resolve(fileName)).hasBinaryContent(content);
	}

	@Test
	void deletesStoredVideoFile() throws Exception {
		Path uploadDirectory = Files.createDirectory(tempDir.resolve("uploads"));
		VideoFileStorage storage = new VideoFileStorage(uploadDirectory);
		String fileName = storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}));

		storage.delete(fileName);

		assertThat(uploadDirectory.resolve(fileName)).doesNotExist();
	}

	@Test
	void removesPartialFileWhenReadingContentFails() throws Exception {
		Path uploadDirectory = Files.createDirectory(tempDir.resolve("uploads"));
		VideoFileStorage storage = new VideoFileStorage(uploadDirectory);
		InputStream brokenContent = new SequenceInputStream(
			new ByteArrayInputStream(new byte[8192]),
			new InputStream() {
				@Override
				public int read() throws IOException {
					throw new IOException("client disconnected");
				}
			});

		assertThatThrownBy(() -> storage.store(brokenContent)).isInstanceOf(IOException.class);

		try (Stream<Path> remaining = Files.list(uploadDirectory)) {
			assertThat(remaining).isEmpty();
		}
	}

	@Test
	void createsMissingUploadDirectoryOnStartup() {
		Path uploadDirectory = tempDir.resolve("nested").resolve("uploads");

		new VideoFileStorage(uploadDirectory);

		assertThat(uploadDirectory).isDirectory();
	}

	@Test
	void failsStartupWhenUploadPathIsNotDirectory() throws Exception {
		Path regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "x");

		assertThatThrownBy(() -> new VideoFileStorage(regularFile))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(regularFile.toString());
	}

}
