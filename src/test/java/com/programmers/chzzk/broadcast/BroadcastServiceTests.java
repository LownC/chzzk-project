package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class BroadcastServiceTests {

	private static final byte[] VIDEO_CONTENT = "fake mp4 bytes".getBytes(StandardCharsets.UTF_8);
	private static final long MAX_VIDEO_FILE_SIZE = 500L * 1024 * 1024;

	private final BroadcastRepository broadcastRepository = mock(BroadcastRepository.class);
	private final VideoFileStorage videoFileStorage = mock(VideoFileStorage.class);
	private final BroadcastService broadcastService = new BroadcastService(broadcastRepository, videoFileStorage);

	@Test
	void videoFileLargerThan500MbIsRejectedBeforeStoring() throws Exception {
		MultipartFile oversized = mock(MultipartFile.class);
		when(oversized.getOriginalFilename()).thenReturn("big.mp4");
		when(oversized.getContentType()).thenReturn("video/mp4");
		when(oversized.isEmpty()).thenReturn(false);
		when(oversized.getSize()).thenReturn(MAX_VIDEO_FILE_SIZE + 1);

		assertRegistrationError("대용량", oversized, BroadcastRegistrationError.VIDEO_FILE_TOO_LARGE);
		verify(videoFileStorage, never()).store(any());
	}

	@Test
	void onlyTheFirstErrorInValidationOrderIsReported() {
		MockMultipartFile emptyMovFile = new MockMultipartFile("videoFile", "clip.mov", "video/quicktime", new byte[0]);
		MockMultipartFile noFileSelected = new MockMultipartFile("videoFile", "", "application/octet-stream", new byte[0]);

		assertRegistrationError("   ", emptyMovFile, BroadcastRegistrationError.TITLE_REQUIRED);
		assertRegistrationError("가".repeat(101), noFileSelected, BroadcastRegistrationError.TITLE_TOO_LONG);
		assertRegistrationError("제목", noFileSelected, BroadcastRegistrationError.VIDEO_FILE_REQUIRED);
		assertRegistrationError("제목", emptyMovFile, BroadcastRegistrationError.VIDEO_FILE_EMPTY);
		assertRegistrationError("제목", new MockMultipartFile("videoFile", "clip.mov", "video/quicktime", VIDEO_CONTENT),
			BroadcastRegistrationError.VIDEO_FILE_NOT_MP4);
	}

	@Test
	void databaseFailureDeletesStoredVideoFileAndRethrowsOriginalException() throws Exception {
		when(videoFileStorage.store(any())).thenReturn("stored.mp4");
		DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("db down");
		when(broadcastRepository.save(any())).thenThrow(databaseFailure);

		assertThatThrownBy(() -> broadcastService.register("제목", mp4File()))
			.isSameAs(databaseFailure);
		verify(videoFileStorage).delete("stored.mp4");
	}

	@Test
	void compensationDeleteFailureIsSuppressedUnderOriginalDatabaseException() throws Exception {
		when(videoFileStorage.store(any())).thenReturn("stored.mp4");
		DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("db down");
		when(broadcastRepository.save(any())).thenThrow(databaseFailure);
		IOException deleteFailure = new IOException("file locked");
		doThrow(deleteFailure).when(videoFileStorage).delete("stored.mp4");

		assertThatThrownBy(() -> broadcastService.register("제목", mp4File()))
			.isSameAs(databaseFailure)
			.satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(deleteFailure));
	}

	private static MockMultipartFile mp4File() {
		return new MockMultipartFile("videoFile", "video.mp4", "video/mp4", VIDEO_CONTENT);
	}

	private void assertRegistrationError(String title, MultipartFile videoFile, BroadcastRegistrationError expected) {
		assertThatThrownBy(() -> broadcastService.register(title, videoFile))
			.isInstanceOfSatisfying(BroadcastRegistrationException.class,
				exception -> assertThat(exception.getError()).isEqualTo(expected));
	}

}
