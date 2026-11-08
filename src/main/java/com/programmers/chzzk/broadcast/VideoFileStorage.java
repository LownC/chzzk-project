package com.programmers.chzzk.broadcast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VideoFileStorage {

	private static final Logger log = LoggerFactory.getLogger(VideoFileStorage.class);

	private static final String VIDEO_FILE_EXTENSION = ".mp4";

	private static final int MAX_STORE_ATTEMPTS = 3;

	private final Path uploadDirectory;

	public VideoFileStorage(@Value("${app.file.upload-dir}") Path uploadDirectory) {
		this.uploadDirectory = uploadDirectory;
		prepareUploadDirectory();
	}

	/**
	 * 서버가 만든 UUID 파일명으로 영상 파일을 저장한다. 기존 파일은 덮어쓰지 않는다.
	 * 파일명이 충돌하면 대상 파일을 만들기 전에 실패하므로 입력 스트림을 읽지 않은 상태에서 새 UUID로 다시 시도한다.
	 */
	public String store(InputStream content) throws IOException {
		for (int attempt = 1; attempt <= MAX_STORE_ATTEMPTS; attempt++) {
			String fileName = UUID.randomUUID() + VIDEO_FILE_EXTENSION;
			Path target = uploadDirectory.resolve(fileName);
			try {
				Files.copy(content, target);
				return fileName;
			} catch (FileAlreadyExistsException e) {
				log.warn("영상 파일명 충돌, 새 이름으로 재시도: attempt={}, fileName={}", attempt, fileName);
			} catch (IOException e) {
				removePartialFile(target, e);
				throw e;
			}
		}
		throw new IOException("영상 파일명 충돌이 " + MAX_STORE_ATTEMPTS + "회 반복되어 저장하지 못했습니다.");
	}

	public void delete(String fileName) throws IOException {
		Files.delete(uploadDirectory.resolve(fileName));
	}

	private void prepareUploadDirectory() {
		try {
			Files.createDirectories(uploadDirectory);
		} catch (IOException e) {
			log.error("업로드 폴더 생성 실패: path={}", uploadDirectory, e);
			throw new IllegalStateException("업로드 폴더를 준비할 수 없습니다: " + uploadDirectory, e);
		}
		if (!Files.isReadable(uploadDirectory) || !Files.isWritable(uploadDirectory)) {
			log.error("업로드 폴더 접근 불가: path={}", uploadDirectory);
			throw new IllegalStateException("업로드 폴더에 접근할 수 없습니다: " + uploadDirectory);
		}
	}

	private void removePartialFile(Path target, IOException cause) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException deleteFailure) {
			cause.addSuppressed(deleteFailure);
			log.error("부분 영상 파일 삭제 실패: path={}", target, deleteFailure);
		}
	}

}
