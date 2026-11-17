package com.programmers.chzzk.live;

/**
 * 한 서버(기본 설정)에서 쓰는 메모리 방 등록부가 등록부 규칙을 지키는지 확인한다.
 */
class InMemoryLiveRoomStoreTests extends LiveRoomStoreContract {

	private final LiveSessionRegistry registry = new LiveSessionRegistry();

	@Override
	protected LiveRoomStore store() {
		return registry;
	}

}
