package com.programmers.chzzk.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이 선택과 키 검사. 기본은 외부 호출 없는 가짜 게이트웨이이고, 토스는 테스트 키({@code test_})만 받는다.
 * 키는 형식만 맞춘 더미 값이며 오류 메시지에 키 값이 드러나지 않는지도 본다.
 */
class PaymentConfigTests {

	private final PaymentConfig config = new PaymentConfig();
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void defaultIsFakeGateway() {
		PaymentGateway gateway = config.paymentGateway(new PaymentProperties(null, null), jsonMapper);

		assertThat(gateway).isInstanceOf(FakePaymentGateway.class);
		assertThat(gateway.name()).isEqualTo("fake");
	}

	@Test
	void tossGatewayAcceptsOnlyTestKeys() {
		PaymentGateway gateway = config.paymentGateway(new PaymentProperties("toss",
			new PaymentProperties.Toss("test_ck_dummy", "test_sk_dummy", null)), jsonMapper);
		assertThat(gateway).isInstanceOf(TossPaymentsGateway.class);

		assertThatThrownBy(() -> config.paymentGateway(new PaymentProperties("toss",
			new PaymentProperties.Toss("live_ck_dummy", "test_sk_dummy", null)), jsonMapper))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TOSS_CLIENT_KEY")
			.hasMessageNotContaining("live_ck_dummy");
		assertThatThrownBy(() -> config.paymentGateway(new PaymentProperties("toss",
			new PaymentProperties.Toss("test_ck_dummy", "live_sk_dummy", null)), jsonMapper))
			.hasMessageContaining("TOSS_SECRET_KEY")
			.hasMessageNotContaining("live_sk_dummy");
		assertThatThrownBy(() -> config.paymentGateway(new PaymentProperties("toss",
			new PaymentProperties.Toss("", "", null)), jsonMapper))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unknownGatewayIsRejectedAndKeysAreMaskedInToString() {
		assertThatThrownBy(() -> config.paymentGateway(new PaymentProperties("paypal", null), jsonMapper))
			.isInstanceOf(IllegalStateException.class);
		assertThat(new PaymentProperties.Toss("test_ck_dummy", "test_sk_dummy", null).toString())
			.doesNotContain("test_ck_dummy", "test_sk_dummy")
			.contains("https://api.tosspayments.com");
	}

}
