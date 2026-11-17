package com.programmers.chzzk.payment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis에 둔 가짜 결제 기록(⑤ cluster 프로파일). 결제 하나가 해시 {@code chzzk:fakepay:{paymentKey}}이고 하루 뒤 사라진다.
 * 승인은 Lua 스크립트로 확인·변경을 한 번에 해, 여러 인스턴스가 같은 결제를 동시에 승인해도 DONE은 한 번만 나온다.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.enabled", havingValue = "true")
public class RedisFakePaymentLedger implements FakePaymentLedger {

	private static final String KEY_PREFIX = "chzzk:fakepay:";
	private static final Duration TTL = Duration.ofDays(1);

	private static final DefaultRedisScript<String> CONFIRM = new DefaultRedisScript<>("""
		local p = redis.call('HMGET', KEYS[1], 'orderId', 'amount', 'status', 'decline')
		if not p[1] then return 'NOT_FOUND' end
		if p[1] ~= ARGV[1] or p[2] ~= ARGV[2] then return 'MISMATCH' end
		if p[3] == 'DONE' then return 'ALREADY_DONE' end
		if p[4] == 'true' then
		  redis.call('HSET', KEYS[1], 'status', 'ABORTED')
		  return 'DECLINED'
		end
		redis.call('HSET', KEYS[1], 'status', 'DONE')
		return 'DONE'
		""", String.class);

	private final StringRedisTemplate redis;

	public RedisFakePaymentLedger(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void save(String paymentKey, FakePayment payment) {
		String key = KEY_PREFIX + paymentKey;
		redis.opsForHash().putAll(key, Map.of(
			"orderId", payment.orderId(),
			"amount", String.valueOf(payment.amount()),
			"status", payment.status(),
			"decline", String.valueOf(payment.declineOnConfirm())));
		redis.expire(key, TTL);
	}

	@Override
	public Optional<FakePayment> find(String paymentKey) {
		List<Object> values = redis.opsForHash().multiGet(KEY_PREFIX + paymentKey,
			List.of("orderId", "amount", "status", "decline"));
		if (values.getFirst() == null) {
			return Optional.empty();
		}
		return Optional.of(new FakePayment(String.valueOf(values.get(0)), Long.parseLong(String.valueOf(values.get(1))),
			String.valueOf(values.get(2)), Boolean.parseBoolean(String.valueOf(values.get(3)))));
	}

	@Override
	public ConfirmResult confirm(String paymentKey, String orderId, long amount) {
		String result = redis.execute(CONFIRM, List.of(KEY_PREFIX + paymentKey), orderId, String.valueOf(amount));
		return ConfirmResult.valueOf(result);
	}

}
