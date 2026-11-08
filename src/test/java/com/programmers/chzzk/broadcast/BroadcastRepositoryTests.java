package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BroadcastRepositoryTests {

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Test
	void savedBroadcastGetsCreatedAtAutomatically() {
		LocalDateTime before = LocalDateTime.now();

		Broadcast saved = broadcastRepository.save(
			new Broadcast("첫 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4"));

		LocalDateTime after = LocalDateTime.now();
		Broadcast found = broadcastRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getTitle()).isEqualTo("첫 방송");
		assertThat(found.getVideoFileName()).isEqualTo("3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4");
		assertThat(found.getCreatedAt()).isBetween(before, after);
	}

}
