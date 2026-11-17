package com.programmers.chzzk.live;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRegistrationException;
import com.programmers.chzzk.member.LoginMember;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * WebRTC 연결 협상(SDP Offer/Answer, ICE Candidate)을 송출자와 시청자 사이에 중계한다. 영상은 서버를 거치지 않는다.
 * 방송 ID·역할·시청자 ID는 서버가 연결에 기록한 값만 믿고, 클라이언트가 메시지에 넣은 값은 쓰지 않는다.
 * 프로토콜과 오류 코드는 docs/live-streaming.md 4절에 있다.
 */
@Component
public class LiveSignalingHandler extends TextWebSocketHandler implements ApplicationListener<ContextClosedEvent> {

	private static final Logger log = LoggerFactory.getLogger(LiveSignalingHandler.class);

	static final int MAX_MESSAGE_LENGTH = 64 * 1024;
	private static final int MAX_SDP_LENGTH = 32 * 1024;
	private static final int MAX_CANDIDATE_LENGTH = 1024;
	private static final int MAX_SDP_MID_LENGTH = 64;
	private static final int SEND_TIME_LIMIT_MILLIS = 5_000;
	private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

	private static final String ROLE = "live.role";
	private static final String BROADCAST_ID = "live.broadcastId";
	private static final String VIEWER_ID = "live.viewerId";

	private enum Role { PUBLISHER, VIEWER }

	private final LiveBroadcastService liveBroadcastService;
	private final LiveRoomStore registry;
	private final LiveProperties liveProperties;
	private final JsonMapper jsonMapper;
	private final ObjectProvider<LiveMessageRelay> relayProvider;
	private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
	private volatile boolean shuttingDown;

	public LiveSignalingHandler(LiveBroadcastService liveBroadcastService, LiveRoomStore registry,
		LiveProperties liveProperties, JsonMapper jsonMapper, ObjectProvider<LiveMessageRelay> relayProvider) {
		this.liveBroadcastService = liveBroadcastService;
		this.registry = registry;
		this.liveProperties = liveProperties;
		this.jsonMapper = jsonMapper;
		this.relayProvider = relayProvider;
	}

	/**
	 * (⑤) 인스턴스가 줄어 이 서버가 내려갈 때. 방송을 끝내지 않고, 이 서버에 붙은 연결에 다시 연결하라고 알린 뒤 닫는다.
	 * 송출 화면은 다른 인스턴스에 다시 붙어 {@code resume}으로 송출을 이어 가고, 시청 화면은 다시 {@code watch}한다.
	 * 방송 상태(DB)와 방 등록부(Redis)는 인스턴스 밖에 있으므로 그대로 남는다.
	 */
	@Override
	public void onApplicationEvent(ContextClosedEvent event) {
		shuttingDown = true;
		for (WebSocketSession session : List.copyOf(sessions.values())) {
			send(session.getId(), message("reconnect"));
			try {
				session.close(CloseStatus.SERVICE_RESTARTED);
			} catch (IOException e) {
				log.debug("종료 중 연결 닫기 실패: sessionId={}", session.getId(), e);
			}
		}
	}

	/**
	 * 다른 인스턴스가 중계한 메시지를 이 서버의 연결로 보낸다. 이 서버에 없는 연결이면 무시한다.
	 */
	public void deliverLocal(String sessionId, String payload) {
		WebSocketSession session = sessions.get(sessionId);
		if (session == null || !session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(new TextMessage(payload));
		} catch (IOException | SessionLimitExceededException e) {
			log.debug("중계 메시지 전송 실패: sessionId={}", sessionId, e);
		}
	}

	/**
	 * 다른 인스턴스가 요청한 연결 닫기. 이 서버에 없는 연결이면 무시한다.
	 */
	public void closeLocal(String sessionId) {
		WebSocketSession session = sessions.get(sessionId);
		if (session == null) {
			return;
		}
		try {
			session.close(CloseStatus.NORMAL);
		} catch (IOException e) {
			log.debug("중계된 연결 종료 실패: sessionId={}", sessionId, e);
		}
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		session.setTextMessageSizeLimit(MAX_MESSAGE_LENGTH);
		sessions.put(session.getId(),
			new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		try {
			JsonNode request = parse(message.getPayload());
			switch (request.path("type").asString()) {
				case "start" -> start(session, request);
				case "resume" -> resume(session, request);
				case "watch" -> watch(session, request);
				case "offer" -> offer(session, request);
				case "answer" -> answer(session, request);
				case "ice" -> ice(session, request);
				case "heartbeat" -> heartbeat(session);
				case "end" -> end(session);
				default -> throw new SignalingException(SignalingError.INVALID_MESSAGE);
			}
		} catch (SignalingException e) {
			log.debug("시그널링 메시지 거절: sessionId={}, code={}", session.getId(), e.error);
			send(session.getId(), error(e.error, e.getMessage()));
		} catch (RuntimeException e) {
			log.error("시그널링 처리 중 예상하지 못한 오류: sessionId={}", session.getId(), e);
			send(session.getId(), error(SignalingError.SERVER_ERROR, SignalingError.SERVER_ERROR.defaultMessage));
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		sessions.remove(session.getId());
		Role role = (Role)session.getAttributes().get(ROLE);
		if (role == Role.PUBLISHER && shuttingDown) {
			log.info("인스턴스 종료로 송출 연결이 끊김, 방송은 유지(송출 화면이 다시 연결해 이어 감): broadcastId={}",
				broadcastIdOf(session));
		} else if (role == Role.PUBLISHER) {
			long broadcastId = broadcastIdOf(session);
			boolean stillPublishing = registry.publisherOf(broadcastId).filter(session.getId()::equals).isPresent();
			if (stillPublishing) {
				endBroadcast(broadcastId);
			}
		} else if (role == Role.VIEWER) {
			String viewerId = (String)session.getAttributes().get(VIEWER_ID);
			registry.leave(broadcastIdOf(session), viewerId)
				.ifPresent(publisherSessionId -> send(publisherSessionId, message("viewer-left").put("viewerId", viewerId)));
		}
	}

	/**
	 * 방송 시작은 로그인한 회원만 할 수 있다. 핸드셰이크 때 HTTP 세션의 로그인 정보가 연결에 들어오며,
	 * 시작한 회원이 방송 소유자가 된다. 시청(watch)은 로그인 없이도 된다.
	 */
	private void start(WebSocketSession session, JsonNode request) {
		requireNoRole(session);
		LoginMember loginMember = loginMemberOf(session)
			.orElseThrow(() -> new SignalingException(SignalingError.UNAUTHENTICATED));
		JsonNode title = request.path("title");
		Broadcast broadcast;
		try {
			broadcast = liveBroadcastService.start(title.isString() ? title.stringValue() : null, loginMember.getId());
		} catch (BroadcastRegistrationException e) {
			throw new SignalingException(SignalingError.INVALID_TITLE, e.getError().message());
		}
		long broadcastId = broadcast.getId();
		registry.openRoom(broadcastId, session.getId());
		session.getAttributes().put(ROLE, Role.PUBLISHER);
		session.getAttributes().put(BROADCAST_ID, broadcastId);
		send(session.getId(), message("started")
			.put("broadcastId", broadcastId)
			.put("watchUrl", "/broadcasts/" + broadcastId));
	}

	/**
	 * (⑤) 송출 연결이 있던 인스턴스가 내려간 뒤 송출 화면이 다른 인스턴스에 다시 붙어 송출을 이어 간다.
	 * 로그인한 방송 소유자이고 방송이 아직 방송 중일 때만 이 연결을 송출자로 바꾼다.
	 */
	private void resume(WebSocketSession session, JsonNode request) {
		requireNoRole(session);
		LoginMember loginMember = loginMemberOf(session)
			.orElseThrow(() -> new SignalingException(SignalingError.UNAUTHENTICATED));
		long broadcastId = requirePositiveId(request.path("broadcastId"));
		Broadcast broadcast = liveBroadcastService.findBroadcast(broadcastId)
			.orElseThrow(() -> new SignalingException(SignalingError.NOT_FOUND));
		if (!broadcast.isOwnedBy(loginMember.getId())) {
			throw new SignalingException(SignalingError.INVALID_ROLE);
		}
		if (!broadcast.isLiveNow()) {
			throw new SignalingException(SignalingError.NOT_LIVE);
		}
		List<String> viewerIds = registry.replacePublisher(broadcastId, session.getId())
			.orElseThrow(() -> new SignalingException(SignalingError.NOT_LIVE));
		session.getAttributes().put(ROLE, Role.PUBLISHER);
		session.getAttributes().put(BROADCAST_ID, broadcastId);
		try {
			liveBroadcastService.recordHeartbeat(broadcastId);
		} catch (IllegalStateException e) {
			endBroadcast(broadcastId);
			return;
		}
		log.info("송출 연결 다시 이어짐: broadcastId={}", broadcastId);
		ObjectNode resumed = message("resumed").put("broadcastId", broadcastId);
		viewerIds.forEach(resumed.putArray("viewerIds")::add);
		send(session.getId(), resumed);
	}

	private void watch(WebSocketSession session, JsonNode request) {
		requireNoRole(session);
		long broadcastId = requirePositiveId(request.path("broadcastId"));
		Broadcast broadcast = liveBroadcastService.findBroadcast(broadcastId)
			.orElseThrow(() -> new SignalingException(SignalingError.NOT_FOUND));
		if (!broadcast.isLiveNow()) {
			throw new SignalingException(SignalingError.NOT_LIVE);
		}
		LiveSessionRegistry.JoinResult joined =
			registry.join(broadcastId, session.getId(), liveProperties.maxViewers());
		switch (joined.status()) {
			case NO_PUBLISHER -> throw new SignalingException(SignalingError.NOT_LIVE);
			case FULL -> throw new SignalingException(SignalingError.FULL);
			case JOINED -> {
				session.getAttributes().put(ROLE, Role.VIEWER);
				session.getAttributes().put(BROADCAST_ID, broadcastId);
				session.getAttributes().put(VIEWER_ID, joined.viewerId());
				send(session.getId(), message("watching").put("broadcastId", broadcastId));
				send(joined.publisherSessionId(), message("viewer-joined").put("viewerId", joined.viewerId()));
			}
		}
	}

	private void offer(WebSocketSession session, JsonNode request) {
		requireRole(session, Role.PUBLISHER);
		String viewerSessionId = targetViewerSession(session, request);
		String sdp = requireSdp(request);
		send(viewerSessionId, message("offer").put("sdp", sdp));
	}

	private void answer(WebSocketSession session, JsonNode request) {
		requireRole(session, Role.VIEWER);
		String sdp = requireSdp(request);
		send(publisherOfViewer(session), message("answer")
			.put("viewerId", (String)session.getAttributes().get(VIEWER_ID))
			.put("sdp", sdp));
	}

	private void ice(WebSocketSession session, JsonNode request) {
		Role role = (Role)session.getAttributes().get(ROLE);
		if (role == null) {
			throw new SignalingException(SignalingError.INVALID_ROLE);
		}
		JsonNode candidate = sanitizeCandidate(request.get("candidate"));
		if (role == Role.PUBLISHER) {
			String viewerSessionId = targetViewerSession(session, request);
			ObjectNode relayed = message("ice");
			relayed.set("candidate", candidate);
			send(viewerSessionId, relayed);
		} else {
			ObjectNode relayed = message("ice").put("viewerId", (String)session.getAttributes().get(VIEWER_ID));
			relayed.set("candidate", candidate);
			send(publisherOfViewer(session), relayed);
		}
	}

	/**
	 * 송출 화면이 살아 있다는 신호. 방송이 이미 끝났으면(생존 신호 만료 등) 송출도 끝낸다.
	 */
	private void heartbeat(WebSocketSession session) {
		requireRole(session, Role.PUBLISHER);
		long broadcastId = broadcastIdOf(session);
		try {
			liveBroadcastService.recordHeartbeat(broadcastId);
		} catch (IllegalStateException e) {
			endBroadcast(broadcastId);
		}
	}

	private void end(WebSocketSession session) {
		requireRole(session, Role.PUBLISHER);
		endBroadcast(broadcastIdOf(session));
	}

	/**
	 * DB를 ENDED로 바꾸고 송출자·시청자 모두에게 종료를 알린 뒤 연결을 닫는다.
	 */
	private void endBroadcast(long broadcastId) {
		liveBroadcastService.end(broadcastId);
		closeEndedBroadcast(broadcastId);
	}

	/**
	 * 이미 ENDED로 저장된 방송의 방을 없애고, 남아 있던 시청자·송출자에게 종료를 알린 뒤 연결을 닫는다.
	 * 생존 신호 만료로 끝난 방송은 송출 연결이 아직 열려 있을 수 있어 {@link LiveBroadcastExpiryScheduler}가 호출한다.
	 * 방이 이미 없으면(다른 경로가 먼저 정리함) 아무것도 하지 않는다. 전송·닫기 실패는 연결별로 기록만 한다.
	 */
	public void closeEndedBroadcast(long broadcastId) {
		registry.closeRoom(broadcastId).ifPresent(room -> {
			for (String viewerSessionId : room.viewerSessionIds()) {
				send(viewerSessionId, message("ended"));
				close(viewerSessionId);
			}
			send(room.publisherSessionId(), message("ended"));
			close(room.publisherSessionId());
		});
	}

	private String targetViewerSession(WebSocketSession publisher, JsonNode request) {
		JsonNode viewerId = request.path("viewerId");
		if (!viewerId.isString()) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
		return registry.viewerSessionOf(broadcastIdOf(publisher), viewerId.stringValue())
			.orElseThrow(() -> new SignalingException(SignalingError.UNKNOWN_VIEWER));
	}

	private String publisherOfViewer(WebSocketSession viewer) {
		return registry.publisherOf(broadcastIdOf(viewer))
			.orElseThrow(() -> new SignalingException(SignalingError.NOT_LIVE));
	}

	private JsonNode parse(String payload) {
		if (payload.length() > MAX_MESSAGE_LENGTH) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
		try {
			JsonNode request = jsonMapper.readTree(payload);
			if (request == null || !request.isObject() || !request.path("type").isString()) {
				throw new SignalingException(SignalingError.INVALID_MESSAGE);
			}
			return request;
		} catch (JacksonException e) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
	}

	private static long requirePositiveId(JsonNode id) {
		if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
		return id.longValue();
	}

	private static String requireSdp(JsonNode request) {
		JsonNode sdp = request.path("sdp");
		if (!sdp.isString() || sdp.stringValue().isEmpty() || sdp.stringValue().length() > MAX_SDP_LENGTH) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
		return sdp.stringValue();
	}

	/**
	 * 브라우저 RTCIceCandidate 형태만 허용하고, 받은 JSON을 그대로 넘기지 않고 필요한 필드만 다시 만든다.
	 * null은 "후보 수집 끝"을 뜻하므로 그대로 전달한다.
	 */
	private JsonNode sanitizeCandidate(JsonNode candidate) {
		if (candidate == null) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
		if (candidate.isNull()) {
			return candidate;
		}
		JsonNode text = candidate.path("candidate");
		JsonNode sdpMid = candidate.path("sdpMid");
		JsonNode sdpMLineIndex = candidate.path("sdpMLineIndex");
		boolean valid = candidate.isObject()
			&& text.isString() && text.stringValue().length() <= MAX_CANDIDATE_LENGTH
			&& (sdpMid.isMissingNode() || sdpMid.isNull()
				|| (sdpMid.isString() && sdpMid.stringValue().length() <= MAX_SDP_MID_LENGTH))
			&& (sdpMLineIndex.isMissingNode() || sdpMLineIndex.isNull() || sdpMLineIndex.isIntegralNumber());
		if (!valid) {
			throw new SignalingException(SignalingError.INVALID_MESSAGE);
		}
		ObjectNode sanitized = jsonMapper.createObjectNode().put("candidate", text.stringValue());
		if (sdpMid.isString()) {
			sanitized.put("sdpMid", sdpMid.stringValue());
		}
		if (sdpMLineIndex.isIntegralNumber()) {
			sanitized.put("sdpMLineIndex", sdpMLineIndex.intValue());
		}
		return sanitized;
	}

	private static void requireNoRole(WebSocketSession session) {
		if (session.getAttributes().get(ROLE) != null) {
			throw new SignalingException(SignalingError.INVALID_ROLE);
		}
	}

	private static void requireRole(WebSocketSession session, Role role) {
		if (session.getAttributes().get(ROLE) != role) {
			throw new SignalingException(SignalingError.INVALID_ROLE);
		}
	}

	private static Optional<LoginMember> loginMemberOf(WebSocketSession session) {
		if (session.getPrincipal() instanceof Authentication authentication
			&& authentication.getPrincipal() instanceof LoginMember loginMember) {
			return Optional.of(loginMember);
		}
		return Optional.empty();
	}

	private static long broadcastIdOf(WebSocketSession session) {
		return (Long)session.getAttributes().get(BROADCAST_ID);
	}

	private ObjectNode message(String type) {
		return jsonMapper.createObjectNode().put("type", type);
	}

	private ObjectNode error(SignalingError error, String detail) {
		return message("error").put("code", error.name()).put("message", detail);
	}

	/**
	 * 이 서버에 있는 연결이면 바로 보내고, 없으면(⑤ 다른 인스턴스의 연결) 중계로 넘긴다.
	 */
	private void send(String sessionId, ObjectNode message) {
		WebSocketSession session = sessions.get(sessionId);
		if (session == null) {
			relay().forward(sessionId, jsonMapper.writeValueAsString(message));
			return;
		}
		if (!session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
		} catch (IOException | SessionLimitExceededException e) {
			log.debug("시그널링 메시지 전송 실패: sessionId={}", sessionId, e);
		}
	}

	private LiveMessageRelay relay() {
		return relayProvider.getIfAvailable(() -> LiveMessageRelay.LOCAL_ONLY);
	}

	private void close(String sessionId) {
		WebSocketSession session = sessions.get(sessionId);
		if (session == null) {
			relay().forwardClose(sessionId);
			return;
		}
		try {
			session.close(CloseStatus.NORMAL);
		} catch (IOException e) {
			log.debug("시그널링 연결 종료 실패: sessionId={}", sessionId, e);
		}
	}

	private enum SignalingError {
		INVALID_MESSAGE("메시지 형식이 올바르지 않습니다."),
		UNAUTHENTICATED("로그인한 회원만 방송을 시작할 수 있습니다. 로그인한 뒤 다시 시도해 주세요."),
		INVALID_TITLE("제목을 확인해 주세요."),
		INVALID_ROLE("지금 보낼 수 없는 메시지입니다."),
		NOT_FOUND("방송을 찾을 수 없습니다."),
		NOT_LIVE("지금 방송 중이 아닙니다."),
		FULL("시청자가 가득 찼습니다. 잠시 후 다시 시도해 주세요."),
		UNKNOWN_VIEWER("이 방송에 연결된 시청자가 아닙니다."),
		SERVER_ERROR("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

		private final String defaultMessage;

		SignalingError(String defaultMessage) {
			this.defaultMessage = defaultMessage;
		}
	}

	private static final class SignalingException extends RuntimeException {

		private final SignalingError error;

		private SignalingException(SignalingError error) {
			this(error, error.defaultMessage);
		}

		private SignalingException(SignalingError error, String message) {
			super(message, null, false, false);
			this.error = error;
		}

	}

}
