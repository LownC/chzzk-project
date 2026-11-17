package com.programmers.chzzk.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * 토스페이먼츠 테스트 모드 요청 형식(승인·조회 API, Basic 인증, 멱등 키)과 응답·오류 해석.
 * 네트워크를 쓰지 않는 가짜 HTTP 서버(MockRestServiceServer)로 확인하며, 실제 토스 서버는 호출하지 않는다.
 * 여기 쓰는 키는 형식만 맞춘 더미 값이다.
 */
class TossPaymentsGatewayTests {

	private static final String DUMMY_SECRET_KEY = "test_sk_dummy_for_unit_test";
	private static final String BASE_URL = "https://api.tosspayments.test";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final TossPaymentsGateway gateway =
		new TossPaymentsGateway(builder, BASE_URL, DUMMY_SECRET_KEY, JsonMapper.builder().build());

	@Test
	void confirmSendsPaymentKeyOrderIdAndAmountWithBasicAuthAndIdempotencyKey() {
		String expectedAuth = "Basic " + Base64.getEncoder()
			.encodeToString((DUMMY_SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
		server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", expectedAuth))
			.andExpect(header("Idempotency-Key", "donation-1"))
			.andExpect(content().json("{\"paymentKey\":\"pk_1\",\"orderId\":\"donation-1\",\"amount\":5000}"))
			.andRespond(withSuccess(
				"{\"paymentKey\":\"pk_1\",\"orderId\":\"donation-1\",\"totalAmount\":5000,\"status\":\"DONE\"}",
				MediaType.APPLICATION_JSON));

		PaymentApproval approval = gateway.confirm("pk_1", "donation-1", 5000);

		assertThat(approval).isEqualTo(new PaymentApproval("pk_1", "donation-1", 5000, "DONE"));
		assertThat(approval.isDone()).isTrue();
		server.verify();
	}

	@Test
	void errorResponseBecomesGatewayExceptionWithTossCode() {
		server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}"));

		assertThatThrownBy(() -> gateway.confirm("pk_1", "donation-1", 5000))
			.isInstanceOfSatisfying(PaymentGatewayException.class,
				e -> assertThat(e.getCode()).isEqualTo("ALREADY_PROCESSED_PAYMENT"));
	}

	@Test
	void findQueriesPaymentByKey() {
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_2"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				"{\"paymentKey\":\"pk_2\",\"orderId\":\"donation-2\",\"totalAmount\":1000,\"status\":\"CANCELED\"}",
				MediaType.APPLICATION_JSON));

		PaymentApproval payment = gateway.find("pk_2");

		assertThat(payment.status()).isEqualTo("CANCELED");
		assertThat(payment.isDone()).isFalse();
	}

	@Test
	void unreadableErrorBodyUsesHttpStatusCode() {
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_3"))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops"));

		assertThatThrownBy(() -> gateway.find("pk_3"))
			.isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.getCode()).isEqualTo("HTTP_500"));
	}

}
