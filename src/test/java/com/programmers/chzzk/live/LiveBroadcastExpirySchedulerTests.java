package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 만료된 방송 하나의 연결 정리가 실패해도 다른 만료 방송의 종료 알림은 계속 보낸다.
 * 실제 전송 오류(IOException 등)는 시그널링 쪽에서 연결별로 처리되므로, 여기서는 그 밖의 예외가 번지지 않는지만 본다.
 */
class LiveBroadcastExpirySchedulerTests {

	private final LiveBroadcastService liveBroadcastService = mock(LiveBroadcastService.class);
	private final LiveSignalingHandler signalingHandler = mock(LiveSignalingHandler.class);
	private final LiveBroadcastExpiryScheduler scheduler =
		new LiveBroadcastExpiryScheduler(liveBroadcastService, signalingHandler);

	@Test
	void failureWhileClosingOneExpiredBroadcastDoesNotStopTheOthers() {
		given(liveBroadcastService.endExpiredBroadcasts()).willReturn(List.of(1L, 2L, 3L));
		willThrow(new IllegalStateException("session already closed")).given(signalingHandler).closeEndedBroadcast(2L);

		assertThatCode(scheduler::endExpiredBroadcasts).doesNotThrowAnyException();

		verify(signalingHandler).closeEndedBroadcast(1L);
		verify(signalingHandler).closeEndedBroadcast(2L);
		verify(signalingHandler).closeEndedBroadcast(3L);
	}

}
