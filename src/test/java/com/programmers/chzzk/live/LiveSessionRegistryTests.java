package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * 방 닫기는 종료 요청·송출 연결 끊김·생존 신호 만료가 동시에 부를 수 있다. 방을 받는 호출은 하나뿐이어야 한다.
 */
class LiveSessionRegistryTests {

	private final LiveSessionRegistry registry = new LiveSessionRegistry();

	@Test
	void closeRoomReturnsPublisherAndViewersOnceThenNothing() {
		registry.openRoom(1L, "publisher-session");
		registry.join(1L, "viewer-session", 4);

		Optional<LiveSessionRegistry.ClosedRoom> closed = registry.closeRoom(1L);

		assertThat(closed).hasValueSatisfying(room -> {
			assertThat(room.publisherSessionId()).isEqualTo("publisher-session");
			assertThat(room.viewerSessionIds()).containsExactly("viewer-session");
		});
		assertThat(registry.closeRoom(1L)).isEmpty();
		assertThat(registry.publisherOf(1L)).isEmpty();
	}

	@Test
	void concurrentCloseRoomHandsTheRoomToExactlyOneCaller() throws Exception {
		registry.openRoom(1L, "publisher-session");
		registry.join(1L, "viewer-session", 4);
		int callers = 8;
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			List<Future<Optional<LiveSessionRegistry.ClosedRoom>>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				results.add(executor.submit(() -> {
					go.await();
					return registry.closeRoom(1L);
				}));
			}
			go.countDown();

			int received = 0;
			for (Future<Optional<LiveSessionRegistry.ClosedRoom>> result : results) {
				if (result.get(5, TimeUnit.SECONDS).isPresent()) {
					received++;
				}
			}
			assertThat(received).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

}
