package com.programmers.chzzk.broadcast;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoDeliveryTests {

	private static final String VIDEO_FILE_NAME = "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4";
	private static final String UUID_PART = "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13";
	private static final byte[] VIDEO_CONTENT = "0123456789abcdefghij-fake-mp4".getBytes(StandardCharsets.UTF_8);

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void existingVideoIsServedAsMp4() throws Exception {
		writeUploadFile(VIDEO_FILE_NAME);

		mockMvc.perform(get("/videos/" + VIDEO_FILE_NAME))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
			.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
			.andExpect(content().bytes(VIDEO_CONTENT));
	}

	@Test
	void rangeRequestReturnsPartialContent() throws Exception {
		writeUploadFile(VIDEO_FILE_NAME);

		mockMvc.perform(get("/videos/" + VIDEO_FILE_NAME).header(HttpHeaders.RANGE, "bytes=0-9"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-9/" + VIDEO_CONTENT.length))
			.andExpect(content().bytes(Arrays.copyOfRange(VIDEO_CONTENT, 0, 10)));
	}

	@Test
	void videoIsCachedForOneYearAsImmutable() throws Exception {
		writeUploadFile(VIDEO_FILE_NAME);

		mockMvc.perform(get("/videos/" + VIDEO_FILE_NAME))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")));
	}

	@Test
	void missingVideoWithValidNameIsNotFound() throws Exception {
		mockMvc.perform(get("/videos/00000000-0000-4000-8000-000000000000.mp4"))
			.andExpect(status().isNotFound());
	}

	/**
	 * 파일을 실제로 만들어 두어, 404가 파일 누락이 아니라 이름 검증 때문임을 확인한다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
		"not-a-uuid.mp4",
		UUID_PART + ".MP4",
		UUID_PART + ".mp3",
		"3F2C8A1E-5B7D-4C9A-8E21-0D6F4B9A7C13.mp4",
		UUID_PART + ".mp4.txt"
	})
	void fileNameOtherThanLowercaseUuidMp4IsNotFound(String fileName) throws Exception {
		writeUploadFile(fileName);

		mockMvc.perform(get("/videos/" + fileName))
			.andExpect(status().isNotFound());
	}

	@Test
	void videoInSubdirectoryIsNotFound() throws Exception {
		Files.createDirectories(uploadDirectory.resolve("sub"));
		writeUploadFile("sub/" + VIDEO_FILE_NAME);

		mockMvc.perform(get("/videos/sub/" + VIDEO_FILE_NAME))
			.andExpect(status().isNotFound());
	}

	@Test
	void pathWithParentSegmentIsNotFound() throws Exception {
		writeUploadFile(VIDEO_FILE_NAME);

		mockMvc.perform(get("/videos/sub/../" + VIDEO_FILE_NAME))
			.andExpect(status().isNotFound());
	}

	private static void writeUploadFile(String relativePath) throws IOException {
		Files.write(uploadDirectory.resolve(relativePath), VIDEO_CONTENT);
	}

}
