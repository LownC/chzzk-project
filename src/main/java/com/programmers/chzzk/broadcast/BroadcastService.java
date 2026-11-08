package com.programmers.chzzk.broadcast;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BroadcastService {

	private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

	private static final int MAX_TITLE_LENGTH = 100;
	private static final String MP4_EXTENSION = ".mp4";
	private static final String MP4_CONTENT_TYPE = "video/mp4";
	private static final long MAX_VIDEO_FILE_SIZE = 500L * 1024 * 1024;

	private final BroadcastRepository broadcastRepository;
	private final VideoFileStorage videoFileStorage;

	public BroadcastService(BroadcastRepository broadcastRepository, VideoFileStorage videoFileStorage) {
		this.broadcastRepository = broadcastRepository;
		this.videoFileStorage = videoFileStorage;
	}

	/**
	 * JPA는 int 범위 offset만 조회할 수 있다. 그보다 뒤의 페이지는 방송이 있을 수 없으므로 빈 페이지로 돌려준다.
	 */
	@Transactional(readOnly = true)
	public Page<Broadcast> findBroadcasts(Pageable pageable) {
		if (pageable.getOffset() > Integer.MAX_VALUE) {
			return new PageImpl<>(List.of(), pageable, broadcastRepository.count());
		}
		return broadcastRepository.findAll(pageable);
	}

	@Transactional(readOnly = true)
	public Optional<Broadcast> findBroadcast(long id) {
		return broadcastRepository.findById(id);
	}

	/**
	 * 방송을 등록한다. 트랜잭션을 걸지 않아 저장 호출이 커밋까지 끝낸 뒤 돌아온다.
	 */
	public Long register(String title, MultipartFile videoFile) {
		validate(title, videoFile);
		String videoFileName = storeVideoFile(videoFile);
		Broadcast broadcast;
		try {
			broadcast = broadcastRepository.save(new Broadcast(title.strip(), videoFileName));
		} catch (RuntimeException databaseFailure) {
			deleteStoredVideoFile(videoFileName, databaseFailure);
			throw databaseFailure;
		}
		log.info("방송 등록: broadcastId={}, videoFileName={}", broadcast.getId(), videoFileName);
		return broadcast.getId();
	}

	/**
	 * 업로드 스트림을 읽거나 디스크에 쓰다 생긴 IOException은 모두 파일 저장 실패로 처리한다.
	 */
	private String storeVideoFile(MultipartFile videoFile) {
		try (InputStream content = videoFile.getInputStream()) {
			return videoFileStorage.store(content);
		} catch (IOException e) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.VIDEO_FILE_STORE_FAILED, e);
		}
	}

	/**
	 * 방송 저장에 실패하면 이 요청에서 저장한 영상 파일을 보상 삭제한다.
	 * 삭제마저 실패하면 원래 예외를 가리지 않도록 suppressed로 덧붙이고 기록만 남긴다.
	 */
	private void deleteStoredVideoFile(String videoFileName, RuntimeException databaseFailure) {
		try {
			videoFileStorage.delete(videoFileName);
		} catch (IOException | RuntimeException deleteFailure) {
			databaseFailure.addSuppressed(deleteFailure);
			log.error("보상 삭제 실패, 영상 파일 수동 정리 필요: videoFileName={}", videoFileName, deleteFailure);
		}
	}

	private static void validate(String title, MultipartFile videoFile) {
		if (title == null || title.isBlank()) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.TITLE_REQUIRED);
		}
		if (title.strip().length() > MAX_TITLE_LENGTH) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.TITLE_TOO_LONG);
		}
		String originalFilename = videoFile == null ? null : videoFile.getOriginalFilename();
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.VIDEO_FILE_REQUIRED);
		}
		if (videoFile.isEmpty()) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.VIDEO_FILE_EMPTY);
		}
		if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(MP4_EXTENSION)
			|| !MP4_CONTENT_TYPE.equals(videoFile.getContentType())) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.VIDEO_FILE_NOT_MP4);
		}
		if (videoFile.getSize() > MAX_VIDEO_FILE_SIZE) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.VIDEO_FILE_TOO_LARGE);
		}
	}

}
