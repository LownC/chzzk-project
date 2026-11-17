package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.programmers.chzzk.live.LiveSessionRegistry.JoinResult;

/**
 * (⑤) 라이브 방 등록부의 규칙. 한 서버용(메모리)과 여러 인스턴스용(Redis) 구현이 똑같이 지켜야 한다.
 */
abstract class LiveRoomStoreContract {

	// Redis를 다른 실행과 같이 쓰더라도 겹치지 않도록 방송 ID를 무작위로 잡는다.
	protected final long broadcastId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);

	protected abstract LiveRoomStore store();

	@AfterEach
	void closeTestRoom() {
		store().closeRoom(broadcastId);
	}

	@Test
	void joinWithoutPublisherIsRejected() {
		assertThat(store().join(broadcastId, "viewer-session", 4).status()).isEqualTo(JoinResult.Status.NO_PUBLISHER);
	}

	@Test
	void joinedViewerIsReachableByIssuedViewerId() {
		store().openRoom(broadcastId, "publisher-session");

		JoinResult joined = store().join(broadcastId, "viewer-session", 4);

		assertThat(joined.status()).isEqualTo(JoinResult.Status.JOINED);
		assertThat(joined.publisherSessionId()).isEqualTo("publisher-session");
		assertThat(store().viewerSessionOf(broadcastId, joined.viewerId())).contains("viewer-session");
		assertThat(store().publisherOf(broadcastId)).contains("publisher-session");
	}

	@Test
	void joinBeyondMaxViewersIsFull() {
		store().openRoom(broadcastId, "publisher-session");
		store().join(broadcastId, "viewer-1", 2);
		store().join(broadcastId, "viewer-2", 2);

		assertThat(store().join(broadcastId, "viewer-3", 2).status()).isEqualTo(JoinResult.Status.FULL);
	}

	@Test
	void leaveReturnsPublisherOnlyOnce() {
		store().openRoom(broadcastId, "publisher-session");
		String viewerId = store().join(broadcastId, "viewer-session", 4).viewerId();

		assertThat(store().leave(broadcastId, viewerId)).contains("publisher-session");
		assertThat(store().leave(broadcastId, viewerId)).isEmpty();
		assertThat(store().viewerSessionOf(broadcastId, viewerId)).isEmpty();
	}

	@Test
	void replacePublisherKeepsViewers() {
		store().openRoom(broadcastId, "old-publisher");
		String viewerId = store().join(broadcastId, "viewer-session", 4).viewerId();

		// 송출자가 끊겨 있던 동안의 입장 알림을 놓쳤을 수 있으므로, 남아 있는 시청자 ID를 돌려준다.
		assertThat(store().replacePublisher(broadcastId, "new-publisher")).contains(List.of(viewerId));

		assertThat(store().publisherOf(broadcastId)).contains("new-publisher");
		assertThat(store().viewerSessionOf(broadcastId, viewerId)).contains("viewer-session");
		assertThat(store().leave(broadcastId, viewerId)).contains("new-publisher");
	}

	@Test
	void replacePublisherOfRoomWithoutViewersReturnsEmptyList() {
		store().openRoom(broadcastId, "old-publisher");

		assertThat(store().replacePublisher(broadcastId, "new-publisher")).contains(List.of());
	}

	@Test
	void replacePublisherOfClosedRoomFails() {
		assertThat(store().replacePublisher(broadcastId, "new-publisher")).isEmpty();
		assertThat(store().publisherOf(broadcastId)).isEmpty();
	}

	@Test
	void closeRoomHandsOverPublisherAndViewersOnce() {
		store().openRoom(broadcastId, "publisher-session");
		store().join(broadcastId, "viewer-session", 4);

		assertThat(store().closeRoom(broadcastId)).hasValueSatisfying(room -> {
			assertThat(room.publisherSessionId()).isEqualTo("publisher-session");
			assertThat(room.viewerSessionIds()).containsExactly("viewer-session");
		});
		assertThat(store().closeRoom(broadcastId)).isEmpty();
	}

	@Test
	void reopeningRoomDropsPreviousViewers() {
		store().openRoom(broadcastId, "publisher-session");
		String viewerId = store().join(broadcastId, "viewer-session", 4).viewerId();

		store().openRoom(broadcastId, "publisher-session-2");

		assertThat(store().viewerSessionOf(broadcastId, viewerId)).isEmpty();
	}

	@Test
	void concurrentJoinsNeverExceedMaxViewers() throws Exception {
		store().openRoom(broadcastId, "publisher-session");
		int callers = 12;
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			List<Future<JoinResult>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				String viewerSession = "viewer-" + i;
				results.add(executor.submit(() -> {
					go.await();
					return store().join(broadcastId, viewerSession, 4);
				}));
			}
			go.countDown();

			int joined = 0;
			for (Future<JoinResult> result : results) {
				if (result.get(10, TimeUnit.SECONDS).status() == JoinResult.Status.JOINED) {
					joined++;
				}
			}
			assertThat(joined).isEqualTo(4);
		} finally {
			executor.shutdownNow();
		}
	}

}
