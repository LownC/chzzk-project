package com.programmers.chzzk.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * 결제 게이트웨이를 고른다. 기본은 외부 호출 없는 가짜 게이트웨이이고, {@code APP_PAYMENT_GATEWAY=toss}이면
 * 토스페이먼츠 테스트 모드를 쓴다. 실제 결제를 막기 위해 {@code test_}로 시작하는 테스트 키만 받는다.
 */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

	@Bean
	public PaymentGateway paymentGateway(PaymentProperties properties, JsonMapper jsonMapper) {
		return switch (properties.gateway()) {
			case FakePaymentGateway.NAME -> new FakePaymentGateway();
			case TossPaymentsGateway.NAME -> tossGateway(properties.toss(), jsonMapper);
			default -> throw new IllegalStateException(
				"app.payment.gateway는 fake 또는 toss여야 합니다: " + properties.gateway());
		};
	}

	private static TossPaymentsGateway tossGateway(PaymentProperties.Toss toss, JsonMapper jsonMapper) {
		requireTestKey("TOSS_CLIENT_KEY", toss.clientKey(), "test_ck_", "test_gck_");
		requireTestKey("TOSS_SECRET_KEY", toss.secretKey(), "test_sk_", "test_gsk_");
		return new TossPaymentsGateway(RestClient.builder(), toss.apiBaseUrl(), toss.secretKey(), jsonMapper);
	}

	/**
	 * 키 값은 예외 메시지에도 넣지 않는다.
	 */
	private static void requireTestKey(String name, String key, String... allowedPrefixes) {
		for (String prefix : allowedPrefixes) {
			if (key.startsWith(prefix)) {
				return;
			}
		}
		throw new IllegalStateException(name + "에는 토스페이먼츠 테스트 키(" + String.join(", ", allowedPrefixes)
			+ "로 시작)만 쓸 수 있습니다. 실제 결제 키는 지원하지 않습니다.");
	}

}
