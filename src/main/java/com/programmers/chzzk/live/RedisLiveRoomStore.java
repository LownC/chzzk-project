package com.programmers.chzzk.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.programmers.chzzk.live.LiveSessionRegistry.ClosedRoom;
import com.programmers.chzzk.live.LiveSessionRegistry.JoinResult;

/**
 * Redis에 둔 방송별 연결 등록부(⑤ cluster 프로파일). 방 하나가 해시 하나다.
 * <pre>
 * chzzk:live:room:{broadcastId}  publisher → 송출자 세션 ID, v:{viewerId} → 시청자 세션 ID
 * </pre>
 * 시청자 입장·퇴장·송출자 교체는 Lua 스크립트로, 방 닫기는 MULTI/EXEC(읽고 지우기)로 원자적으로 처리해
 * 여러 인스턴스가 동시에 같은 방을 다뤄도 정원 초과나 종료 알림 중복이 생기지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.enabled", havingValue = "true")
public class RedisLiveRoomStore implements LiveRoomStore {

	static final String KEY_PREFIX = "chzzk:live:room:";
	private static final String PUBLISHER = "publisher";
	private static final String VIEWER_PREFIX = "v:";

	private static final DefaultRedisScript<List> JOIN = new DefaultRedisScript<>("""
		local publisher = redis.call('HGET', KEYS[1], 'publisher')
		if not publisher then return {'NO_PUBLISHER'} end
		if redis.call('HLEN', KEYS[1]) - 1 >= tonumber(ARGV[3]) then return {'FULL'} end
		redis.call('HSET', KEYS[1], 'v:' .. ARGV[1], ARGV[2])
		return {'JOINED', publisher}
		""", List.class);

	private static final DefaultRedisScript<String> LEAVE = new DefaultRedisScript<>("""
		if redis.call('HDEL', KEYS[1], 'v:' .. ARGV[1]) == 1 then
		  return redis.call('HGET', KEYS[1], 'publisher')
		end
		return false
		""", String.class);

	@SuppressWarnings("rawtypes")
	private static final DefaultRedisScript<List> REPLACE_PUBLISHER = new DefaultRedisScript<>("""
		if redis.call('HEXISTS', KEYS[1], 'publisher') == 1 then
		  redis.call('HSET', KEYS[1], 'publisher', ARGV[1])
		  return redis.call('HKEYS', KEYS[1])
		end
		return {}
		""", List.class);

	private final StringRedisTemplate redis;

	public RedisLiveRoomStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void openRoom(long broadcastId, String publisherSessionId) {
		String key = key(broadcastId);
		redis.execute(new SessionCallback<List<Object>>() {
			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public List<Object> execute(RedisOperations operations) throws DataAccessException {
				operations.multi();
				operations.delete(key);
				operations.opsForHash().put(key, PUBLISHER, publisherSessionId);
				return operations.exec();
			}
		});
	}

	@Override
	public Optional<String> publisherOf(long broadcastId) {
		return Optional.ofNullable((String)redis.opsForHash().get(key(broadcastId), PUBLISHER));
	}

	@Override
	public JoinResult join(long broadcastId, String viewerSessionId, int maxViewers) {
		String viewerId = UUID.randomUUID().toString();
		List<?> result = redis.execute(JOIN, List.of(key(broadcastId)), viewerId, viewerSessionId,
			String.valueOf(maxViewers));
		return switch (String.valueOf(result.getFirst())) {
			case "JOINED" -> JoinResult.joined(viewerId, String.valueOf(result.get(1)));
			case "FULL" -> JoinResult.full();
			default -> JoinResult.noPublisher();
		};
	}

	@Override
	public Optional<String> viewerSessionOf(long broadcastId, String viewerId) {
		return Optional.ofNullable((String)redis.opsForHash().get(key(broadcastId), VIEWER_PREFIX + viewerId));
	}

	@Override
	public Optional<String> leave(long broadcastId, String viewerId) {
		return Optional.ofNullable(redis.execute(LEAVE, List.of(key(broadcastId)), viewerId));
	}

	@Override
	public Optional<ClosedRoom> closeRoom(long broadcastId) {
		String key = key(broadcastId);
		List<Object> results = redis.execute(new SessionCallback<List<Object>>() {
			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public List<Object> execute(RedisOperations operations) throws DataAccessException {
				operations.multi();
				operations.opsForHash().entries(key);
				operations.delete(key);
				return operations.exec();
			}
		});
		@SuppressWarnings("unchecked")
		Map<Object, Object> room = results == null || results.isEmpty() ? Map.of() : (Map<Object, Object>)results.getFirst();
		Object publisher = room.get(PUBLISHER);
		if (publisher == null) {
			return Optional.empty();
		}
		List<String> viewers = new ArrayList<>();
		room.forEach((field, value) -> {
			if (String.valueOf(field).startsWith(VIEWER_PREFIX)) {
				viewers.add(String.valueOf(value));
			}
		});
		return Optional.of(new ClosedRoom(String.valueOf(publisher), List.copyOf(viewers)));
	}

	@Override
	public Optional<List<String>> replacePublisher(long broadcastId, String publisherSessionId) {
		// 방이 있으면 publisher 필드가 늘 있으므로, 빈 목록은 방이 없다는 뜻이다.
		List<?> fields = redis.execute(REPLACE_PUBLISHER, List.of(key(broadcastId)), publisherSessionId);
		if (fields == null || fields.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(fields.stream()
			.map(String::valueOf)
			.filter(field -> field.startsWith(VIEWER_PREFIX))
			.map(field -> field.substring(VIEWER_PREFIX.length()))
			.toList());
	}

	private static String key(long broadcastId) {
		return KEY_PREFIX + broadcastId;
	}

}
