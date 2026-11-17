package com.programmers.chzzk.payment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토스페이먼츠 결제 승인·조회 API(테스트 모드). {@link PaymentConfig}가 테스트 키({@code test_})일 때만 만든다.
 * <ul>
 *     <li>승인: {@code POST /v1/payments/confirm} {@code {paymentKey, orderId, amount}}, 같은 주문의 재시도는 멱등 키(주문번호)로 묶는다.</li>
 *     <li>조회: {@code GET /v1/payments/{paymentKey}}</li>
 *     <li>인증: 시크릿 키 뒤에 콜론을 붙여 Base64로 인코딩한 Basic 인증. 키는 환경변수로만 받고 로그에 남기지 않는다.</li>
 * </ul>
 * 이 프로젝트의 자동 테스트와 검증 서버는 실제 토스 서버를 호출하지 않는다(README "결제 연동 범위").
 */
public class TossPaymentsGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(TossPaymentsGateway.class);

	public static final String NAME = "toss";

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public TossPaymentsGateway(RestClient.Builder restClientBuilder, String apiBaseUrl, String secretKey,
		JsonMapper jsonMapper) {
		String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
		this.restClient = restClientBuilder
			.baseUrl(apiBaseUrl)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
			.build();
		this.jsonMapper = jsonMapper;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public PaymentApproval confirm(String paymentKey, String orderId, long amount) {
		String body = jsonMapper.createObjectNode()
			.put("paymentKey", paymentKey)
			.put("orderId", orderId)
			.put("amount", amount)
			.toString();
		return call(() -> restClient.post()
			.uri("/v1/payments/confirm")
			.header("Idempotency-Key", orderId)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				throw errorOf(response.getStatusCode(), response.getBody());
			})
			.body(String.class));
	}

	@Override
	public PaymentApproval find(String paymentKey) {
		return call(() -> restClient.get()
			.uri("/v1/payments/{paymentKey}", paymentKey)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				throw errorOf(response.getStatusCode(), response.getBody());
			})
			.body(String.class));
	}

	private PaymentApproval call(ResponseCall responseCall) {
		String response;
		try {
			response = responseCall.send();
		} catch (PaymentGatewayException e) {
			throw e;
		} catch (RestClientException e) {
			log.warn("토스페이먼츠 호출 실패: {}", e.getClass().getSimpleName());
			throw new PaymentGatewayException(PaymentGatewayException.NETWORK_ERROR, "결제 서비스에 연결하지 못했습니다.", e);
		}
		return parse(response);
	}

	private PaymentApproval parse(String response) {
		try {
			JsonNode payment = jsonMapper.readTree(response);
			return new PaymentApproval(
				payment.path("paymentKey").asString(),
				payment.path("orderId").asString(),
				payment.path("totalAmount").asLong(),
				payment.path("status").asString());
		} catch (JacksonException e) {
			throw new PaymentGatewayException("INVALID_RESPONSE", "결제 서비스 응답을 해석하지 못했습니다.", e);
		}
	}

	/**
	 * 토스 오류 응답 {@code {"code": "...", "message": "..."}}를 예외로 바꾼다. 응답을 읽지 못하면 HTTP 상태로 코드를 만든다.
	 */
	private PaymentGatewayException errorOf(HttpStatusCode status, InputStream body) throws IOException {
		String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
		try {
			JsonNode error = jsonMapper.readTree(text);
			String code = error.path("code").asString("HTTP_" + status.value());
			log.info("토스페이먼츠 오류 응답: status={}, code={}", status.value(), code);
			return new PaymentGatewayException(code, error.path("message").asString("결제가 거절되었습니다."));
		} catch (JacksonException e) {
			return new PaymentGatewayException("HTTP_" + status.value(), "결제가 거절되었습니다.");
		}
	}

	@FunctionalInterface
	private interface ResponseCall {
		String send();
	}

}
