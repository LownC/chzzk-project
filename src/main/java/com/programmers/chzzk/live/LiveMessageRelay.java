package com.programmers.chzzk.live;

/**
 * 이 인스턴스에 없는 WebSocket 연결로 보낼 시그널링 메시지를 다른 인스턴스에 넘긴다.
 * 기본(인스턴스 하나)은 넘길 곳이 없으므로 아무것도 하지 않고, ⑤ cluster 프로파일은 Redis pub/sub으로 넘긴다
 * ({@link RedisLiveMessageRelay}).
 */
public interface LiveMessageRelay {

	/**
	 * 다른 인스턴스의 연결에 메시지(JSON 문자열)를 보낸다.
	 */
	void forward(String sessionId, String payload);

	/**
	 * 다른 인스턴스의 연결을 닫는다.
	 */
	void forwardClose(String sessionId);

	/**
	 * 인스턴스가 하나뿐일 때. 다른 인스턴스가 없으므로 보낼 곳이 없다.
	 */
	LiveMessageRelay LOCAL_ONLY = new LiveMessageRelay() {
		@Override
		public void forward(String sessionId, String payload) {
		}

		@Override
		public void forwardClose(String sessionId) {
		}
	};

}
