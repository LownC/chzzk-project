package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.comment.Comment;
import com.programmers.chzzk.comment.CommentRepository;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;

/**
 * 방송 관리·송출 제어의 서버 권한 검사. 비로그인 사용자, 방송 소유자, 다른 회원으로 나눠 허용·거절을 확인한다.
 * 화면 버튼과 상관없이 요청을 직접 보내도 서버가 거절하는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastAuthorizationTests {

	private static final byte[] VIDEO_CONTENT = "fake mp4 bytes".getBytes(StandardCharsets.UTF_8);

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BroadcastService broadcastService;

	private Member owner;
	private Member other;

	@BeforeEach
	void setUp() {
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
		owner = TestMembers.save(memberRepository, "broadcast_owner");
		other = TestMembers.save(memberRepository, "other_member");
	}

	@AfterEach
	void cleanUp() {
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
	}

	@Test
	void anonymousUserIsSentToLoginForRegistrationAndLivePages() throws Exception {
		mockMvc.perform(get("/broadcasts/new")).andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/live/new")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void anonymousRegistrationIsRejectedAndNothingIsSaved() throws Exception {
		long storedFilesBefore = storedVideoFileCount();

		mockMvc.perform(multipart("/broadcasts").file(mp4File()).param("title", "몰래 등록").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		mockMvc.perform(multipart("/broadcasts").file(mp4File()).param("title", "몰래 등록").with(csrf())
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("로그인이 필요합니다. 로그인한 뒤 다시 시도해 주세요."));

		assertThat(broadcastRepository.count()).isZero();
		assertThat(storedVideoFileCount()).isEqualTo(storedFilesBefore);
	}

	@Test
	void registrationWithoutCsrfTokenIsForbidden() throws Exception {
		mockMvc.perform(multipart("/broadcasts").file(mp4File()).param("title", "토큰 없음").with(as(owner))
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").exists());

		assertThat(broadcastRepository.count()).isZero();
	}

	@Test
	void registeredBroadcastIsOwnedByLoggedInMember() throws Exception {
		mockMvc.perform(multipart("/broadcasts").file(mp4File()).param("title", "내 방송").with(as(owner)).with(csrf()))
			.andExpect(status().is3xxRedirection());

		Broadcast saved = broadcastRepository.findAll().getFirst();
		assertThat(saved.isOwnedBy(owner.getId())).isTrue();
		assertThat(saved.isOwnedBy(other.getId())).isFalse();
	}

	@Test
	void listAndDetailArePublicAndShowOwnerButtonsOnlyToOwner() throws Exception {
		long id = recordedBroadcastOf(owner, "공개 방송");

		mockMvc.perform(get("/broadcasts")).andExpect(status().isOk());
		mockMvc.perform(get("/broadcasts/" + id))
			.andExpect(status().isOk())
			.andExpect(xpath("//p[@class='owner']").string("방송자: broadcast_owner"))
			.andExpect(xpath("//a[@href='/broadcasts/" + id + "/edit']").doesNotExist());
		mockMvc.perform(get("/broadcasts/" + id).with(as(other)))
			.andExpect(xpath("//a[@href='/broadcasts/" + id + "/edit']").doesNotExist());
		mockMvc.perform(get("/broadcasts/" + id).with(as(owner)))
			.andExpect(xpath("//a[@href='/broadcasts/" + id + "/edit']").exists())
			.andExpect(xpath("//form[@action='/broadcasts/" + id + "/delete']//input[@name='_csrf']").exists());
	}

	@Test
	void onlyOwnerCanOpenEditFormAndChangeTitle() throws Exception {
		long id = recordedBroadcastOf(owner, "원래 제목");

		mockMvc.perform(get("/broadcasts/" + id + "/edit")).andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/broadcasts/" + id + "/edit").with(as(other))).andExpect(status().isForbidden());
		mockMvc.perform(get("/broadcasts/" + id + "/edit").with(as(owner)))
			.andExpect(status().isOk())
			.andExpect(view().name("edit"));

		mockMvc.perform(post("/broadcasts/" + id + "/edit").param("title", "익명 수정").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/broadcasts/" + id + "/edit").param("title", "남의 수정").with(as(other)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(view().name("error/403"));
		assertThat(titleOf(id)).isEqualTo("원래 제목");

		mockMvc.perform(post("/broadcasts/" + id + "/edit").param("title", "  바뀐 제목  ").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + id));
		assertThat(titleOf(id)).isEqualTo("바뀐 제목");
	}

	@Test
	void invalidTitleOnEditShowsMessageAndKeepsTitle() throws Exception {
		long id = recordedBroadcastOf(owner, "원래 제목");

		mockMvc.perform(post("/broadcasts/" + id + "/edit").param("title", "   ").with(as(owner)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(view().name("edit"))
			.andExpect(xpath("//p[@class='error-message']").string(BroadcastRegistrationError.TITLE_REQUIRED.message()));
		mockMvc.perform(post("/broadcasts/" + id + "/edit").param("title", "가".repeat(101)).with(as(owner)).with(csrf()))
			.andExpect(status().isBadRequest());

		assertThat(titleOf(id)).isEqualTo("원래 제목");
	}

	@Test
	void onlyOwnerCanDeleteAndDeletionRemovesCommentsAndVideoFile() throws Exception {
		long id = recordedBroadcastOf(owner, "삭제할 방송");
		Broadcast broadcast = broadcastRepository.findById(id).orElseThrow();
		Path videoFile = uploadDirectory.resolve(broadcast.getVideoFileName());
		commentRepository.save(new Comment(broadcast, other, "남긴 댓글"));

		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(csrf())).andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(as(other)).with(csrf()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(as(owner)))
			.andExpect(status().isForbidden());
		assertThat(broadcastRepository.existsById(id)).isTrue();
		assertThat(Files.exists(videoFile)).isTrue();

		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts"));

		assertThat(broadcastRepository.existsById(id)).isFalse();
		assertThat(commentRepository.count()).isZero();
		assertThat(Files.exists(videoFile)).isFalse();
	}

	@Test
	void liveBroadcastOnAirCannotBeDeletedUntilEnded() throws Exception {
		long id = broadcastRepository.save(Broadcast.startLive("방송 중", owner, LocalDateTime.now())).getId();

		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + id))
			.andExpect(flash().attribute("manageError", BroadcastStillLiveException.MESSAGE));
		assertThat(broadcastRepository.existsById(id)).isTrue();

		mockMvc.perform(post("/broadcasts/" + id + "/end").with(as(owner)).with(csrf()));
		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts"));
		assertThat(broadcastRepository.existsById(id)).isFalse();
	}

	@Test
	void onlyOwnerCanEndLiveBroadcast() throws Exception {
		long id = broadcastRepository.save(Broadcast.startLive("방송 중", owner, LocalDateTime.now())).getId();

		mockMvc.perform(post("/broadcasts/" + id + "/end").with(csrf())).andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/broadcasts/" + id + "/end").with(as(other)).with(csrf()))
			.andExpect(status().isForbidden());
		assertThat(broadcastRepository.findById(id).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);

		mockMvc.perform(post("/broadcasts/" + id + "/end").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + id));
		assertThat(broadcastRepository.findById(id).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.ENDED);
	}

	/**
	 * ③ 이전에 만든 방송(소유자 없음)은 아무도 수정·삭제할 수 없다.
	 */
	@Test
	void broadcastWithoutOwnerCannotBeManagedByAnyone() throws Exception {
		long id = broadcastRepository.save(new Broadcast("옛 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4")).getId();

		mockMvc.perform(post("/broadcasts/" + id + "/edit").param("title", "가로채기").with(as(owner)).with(csrf()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/broadcasts/" + id + "/delete").with(as(owner)).with(csrf()))
			.andExpect(status().isForbidden());
		assertThat(titleOf(id)).isEqualTo("옛 방송");
	}

	@Test
	void managingMissingOrMalformedBroadcastIsNotFound() throws Exception {
		mockMvc.perform(get("/broadcasts/999999/edit").with(as(owner))).andExpect(status().isNotFound());
		mockMvc.perform(post("/broadcasts/999999/delete").with(as(owner)).with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("요청한 페이지를 찾을 수 없습니다.")));
		mockMvc.perform(post("/broadcasts/abc/end").with(as(owner)).with(csrf())).andExpect(status().isNotFound());
	}

	private long recordedBroadcastOf(Member member, String title) {
		return broadcastService.register(title, mp4File(), member.getId());
	}

	private String titleOf(long id) {
		return broadcastRepository.findById(id).orElseThrow().getTitle();
	}

	private static RequestPostProcessor as(Member member) {
		return user(TestMembers.loginMember(member));
	}

	private static MockMultipartFile mp4File() {
		return new MockMultipartFile("videoFile", "video.mp4", "video/mp4", VIDEO_CONTENT);
	}

	/**
	 * 업로드 폴더는 이 클래스의 테스트가 함께 쓰므로, 비어 있는지 대신 요청 전후 파일 수를 비교한다.
	 */
	private static long storedVideoFileCount() throws IOException {
		try (var files = Files.list(uploadDirectory)) {
			return files.count();
		}
	}

}
