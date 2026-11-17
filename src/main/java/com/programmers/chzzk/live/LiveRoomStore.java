package com.programmers.chzzk.live;

import java.util.List;
import java.util.Optional;

import com.programmers.chzzk.live.LiveSessionRegistry.ClosedRoom;
import com.programmers.chzzk.live.LiveSessionRegistry.JoinResult;

/**
 * 방송별 송출자·시청자 연결(WebSocket 세션 ID) 등록부. 방송 상태의 기준은 DB이고, 이 등록부는 메시지를 어느 연결로 보낼지 정한다.
 * <ul>
 *     <li>{@link LiveSessionRegistry}: 서버 메모리(인스턴스 하나, 기본·테스트).</li>
 *     <li>{@link RedisLiveRoomStore}: Redis(⑤ cluster 프로파일). 송출자와 시청자가 서로 다른 인스턴스에 붙어도 같은 방을 본다.</li>
 * </ul>
 * 모든 구현은 방을 닫을 때 방을 받는 호출이 하나뿐이어야 한다(종료 알림이 두 번 가지 않음).
 */
public interface LiveRoomStore {

	void openRoom(long broadcastId, String publisherSessionId);

	Optional<String> publisherOf(long broadcastId);

	JoinResult join(long broadcastId, String viewerSessionId, int maxViewers);

	Optional<String> viewerSessionOf(long broadcastId, String viewerId);

	Optional<String> leave(long broadcastId, String viewerId);

	Optional<ClosedRoom> closeRoom(long broadcastId);

	/**
	 * 인스턴스가 줄어 송출 연결이 끊긴 뒤 송출 화면이 다시 연결하면, 방은 그대로 두고 송출자 연결만 바꾼다.
	 * 그동안 방에 남아 있거나 새로 들어온 시청자 ID를 돌려준다. 송출자가 끊겨 있는 동안 보낸 입장·퇴장 알림은
	 * 송출 화면에 닿지 않았으므로, 송출 화면은 이 목록으로 영상 연결을 맞춘다. 방이 없으면(이미 종료) 비어 있다.
	 */
	Optional<List<String>> replacePublisher(long broadcastId, String publisherSessionId);

}
