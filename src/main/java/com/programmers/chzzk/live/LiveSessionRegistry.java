package com.programmers.chzzk.live;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 이 서버에 붙어 있는 송출자·시청자 연결(WebSocket 세션 ID)을 방송별로 기억한다.
 * 방송 상태의 기준은 DB이고, 이 등록부는 메시지를 어느 연결로 보낼지 정하는 데만 쓴다.
 * 서버 메모리에만 있으므로 인스턴스가 하나일 때(기본·테스트) 쓴다. 여러 인스턴스(⑤ cluster 프로파일)는
 * {@link RedisLiveRoomStore}를 쓴다.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.enabled", havingValue = "false", matchIfMissing = true)
public class LiveSessionRegistry implements LiveRoomStore {

	private final Map<Long, Room> rooms = new HashMap<>();

	public synchronized void openRoom(long broadcastId, String publisherSessionId) {
		rooms.put(broadcastId, new Room(publisherSessionId));
	}

	public synchronized Optional<String> publisherOf(long broadcastId) {
		return Optional.ofNullable(rooms.get(broadcastId)).map(Room::publisherSessionId);
	}

	/**
	 * 시청자를 방에 넣고 서버가 만든 시청자 ID를 돌려준다. 클라이언트가 정한 ID는 쓰지 않는다.
	 */
	public synchronized JoinResult join(long broadcastId, String viewerSessionId, int maxViewers) {
		Room room = rooms.get(broadcastId);
		if (room == null) {
			return JoinResult.noPublisher();
		}
		if (room.viewers().size() >= maxViewers) {
			return JoinResult.full();
		}
		String viewerId = UUID.randomUUID().toString();
		room.viewers().put(viewerId, viewerSessionId);
		return JoinResult.joined(viewerId, room.publisherSessionId());
	}

	public synchronized Optional<String> viewerSessionOf(long broadcastId, String viewerId) {
		return Optional.ofNullable(rooms.get(broadcastId)).map(room -> room.viewers().get(viewerId));
	}

	/**
	 * 시청자를 뺀다. 방이 아직 있으면 알려 줄 송출자 세션 ID를 돌려준다.
	 */
	public synchronized Optional<String> leave(long broadcastId, String viewerId) {
		Room room = rooms.get(broadcastId);
		if (room == null || room.viewers().remove(viewerId) == null) {
			return Optional.empty();
		}
		return Optional.of(room.publisherSessionId());
	}

	/**
	 * 방을 없애고 그 순간의 송출자·시청자 세션 ID를 돌려준다. 이미 없으면 빈 값이다.
	 * 종료 요청과 생존 신호 만료가 겹쳐도 방을 받는 쪽은 하나뿐이라, 종료 알림이 두 번 가지 않는다.
	 */
	public synchronized Optional<ClosedRoom> closeRoom(long broadcastId) {
		Room room = rooms.remove(broadcastId);
		if (room == null) {
			return Optional.empty();
		}
		return Optional.of(new ClosedRoom(room.publisherSessionId(), List.copyOf(room.viewers().values())));
	}

	@Override
	public synchronized Optional<List<String>> replacePublisher(long broadcastId, String publisherSessionId) {
		Room room = rooms.get(broadcastId);
		if (room == null) {
			return Optional.empty();
		}
		rooms.put(broadcastId, new Room(publisherSessionId, room.viewers()));
		return Optional.of(List.copyOf(room.viewers().keySet()));
	}

	public record ClosedRoom(String publisherSessionId, List<String> viewerSessionIds) {
	}

	private record Room(String publisherSessionId, Map<String, String> viewers) {

		private Room(String publisherSessionId) {
			this(publisherSessionId, new LinkedHashMap<>());
		}

	}

	public record JoinResult(Status status, String viewerId, String publisherSessionId) {

		public enum Status { JOINED, NO_PUBLISHER, FULL }

		static JoinResult joined(String viewerId, String publisherSessionId) {
			return new JoinResult(Status.JOINED, viewerId, publisherSessionId);
		}

		static JoinResult noPublisher() {
			return new JoinResult(Status.NO_PUBLISHER, null, null);
		}

		static JoinResult full() {
			return new JoinResult(Status.FULL, null, null);
		}

	}

}
