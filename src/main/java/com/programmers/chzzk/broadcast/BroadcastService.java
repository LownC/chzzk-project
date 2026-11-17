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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.programmers.chzzk.comment.CommentRepository;
import com.programmers.chzzk.donation.DonationRepository;
import com.programmers.chzzk.donation.DonationStatus;
import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.MemberRepository;

@Service
public class BroadcastService {

	private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

	private static final int MAX_TITLE_LENGTH = 100;
	private static final String MP4_EXTENSION = ".mp4";
	private static final String MP4_CONTENT_TYPE = "video/mp4";
	private static final long MAX_VIDEO_FILE_SIZE = 500L * 1024 * 1024;

	private final BroadcastRepository broadcastRepository;
	private final VideoFileStorage videoFileStorage;
	private final MemberRepository memberRepository;
	private final CommentRepository commentRepository;
	private final DonationRepository donationRepository;
	private final TransactionTemplate transactionTemplate;

	public BroadcastService(BroadcastRepository broadcastRepository, VideoFileStorage videoFileStorage,
		MemberRepository memberRepository, CommentRepository commentRepository, DonationRepository donationRepository,
		PlatformTransactionManager transactionManager) {
		this.broadcastRepository = broadcastRepository;
		this.videoFileStorage = videoFileStorage;
		this.memberRepository = memberRepository;
		this.commentRepository = commentRepository;
		this.donationRepository = donationRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
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
	 * 로그인한 회원을 소유자로 방송을 등록한다. 트랜잭션을 걸지 않아 저장 호출이 커밋까지 끝낸 뒤 돌아온다.
	 */
	public Long register(String title, MultipartFile videoFile, long ownerId) {
		validate(title, videoFile);
		String videoFileName = storeVideoFile(videoFile);
		Broadcast broadcast;
		try {
			broadcast = broadcastRepository.save(
				new Broadcast(title.strip(), videoFileName, memberRepository.getReferenceById(ownerId)));
		} catch (RuntimeException databaseFailure) {
			deleteStoredVideoFile(videoFileName, databaseFailure);
			throw databaseFailure;
		}
		log.info("방송 등록: broadcastId={}, ownerId={}, videoFileName={}", broadcast.getId(), ownerId, videoFileName);
		return broadcast.getId();
	}

	/**
	 * 수정 화면을 보여 줄 방송. 소유자가 아니면 {@link AccessDeniedException}이다.
	 */
	@Transactional(readOnly = true)
	public Broadcast findOwnBroadcast(long id, long memberId) {
		return requireOwner(findExisting(id), memberId);
	}

	/**
	 * 소유자만 제목을 바꿀 수 있다. 제목 규칙은 등록과 같다.
	 */
	@Transactional
	public void changeTitle(long id, long memberId, String title) {
		Broadcast broadcast = requireOwner(findExisting(id), memberId);
		broadcast.changeTitle(validateTitle(title));
		log.info("방송 제목 수정: broadcastId={}, memberId={}", id, memberId);
	}

	/**
	 * (④) 확정됐거나 승인 처리 중인 후원이 있는 방송은 결제 기록을 지키기 위해 삭제하지 않는다.
	 * 확정되지 않고 끝난 주문(READY·FAILED·CANCELED)은 방송과 함께 지운다.
	 */
	private static final List<DonationStatus> KEPT_DONATION_STATUSES = List.of(DonationStatus.DONE, DonationStatus.IN_PROGRESS);

	/**
	 * 소유자만 삭제할 수 있다. 방송 중인 라이브는 먼저 종료해야 한다. 방송의 댓글도 함께 지운다.
	 * DB 삭제가 커밋된 뒤 녹화방송의 영상 파일을 지운다. 파일 삭제에 실패하면 방송은 이미 없으므로 기록만 남긴다.
	 */
	public void delete(long id, long memberId) {
		String videoFileName = transactionTemplate.execute(status -> {
			Broadcast broadcast = requireOwner(findExisting(id), memberId);
			if (broadcast.isLiveNow()) {
				throw new BroadcastStillLiveException();
			}
			if (donationRepository.existsByBroadcastIdAndStatusIn(id, KEPT_DONATION_STATUSES)) {
				throw new BroadcastHasDonationsException();
			}
			donationRepository.deleteByBroadcastId(id);
			int deletedComments = commentRepository.deleteByBroadcastId(id);
			broadcastRepository.delete(broadcast);
			log.info("방송 삭제: broadcastId={}, memberId={}, deletedComments={}", id, memberId, deletedComments);
			return broadcast.getVideoFileName();
		});
		if (videoFileName != null) {
			try {
				videoFileStorage.delete(videoFileName);
			} catch (IOException | RuntimeException e) {
				log.error("삭제한 방송의 영상 파일 삭제 실패, 수동 정리 필요: videoFileName={}", videoFileName, e);
			}
		}
	}

	private Broadcast findExisting(long id) {
		return broadcastRepository.findById(id).orElseThrow(() -> new NotFoundException("방송 없음: id=" + id));
	}

	private static Broadcast requireOwner(Broadcast broadcast, long memberId) {
		if (!broadcast.isOwnedBy(memberId)) {
			throw new AccessDeniedException("방송 소유자가 아님: broadcastId=" + broadcast.getId() + ", memberId=" + memberId);
		}
		return broadcast;
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

	/**
	 * 제목은 필수이고 앞뒤 공백을 뺀 길이가 최대 100자다. 공백을 뺀 제목을 돌려준다.
	 */
	static String validateTitle(String title) {
		if (title == null || title.isBlank()) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.TITLE_REQUIRED);
		}
		String stripped = title.strip();
		if (stripped.length() > MAX_TITLE_LENGTH) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.TITLE_TOO_LONG);
		}
		return stripped;
	}

	private static void validate(String title, MultipartFile videoFile) {
		validateTitle(title);
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
