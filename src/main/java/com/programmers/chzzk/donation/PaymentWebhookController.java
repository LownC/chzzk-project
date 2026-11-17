package com.programmers.chzzk.donation;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결제 서비스의 승인 통지(토스페이먼츠 웹훅 {@code PAYMENT_STATUS_CHANGED}). 서버끼리 주고받는 요청이라 로그인·CSRF 검사 대상이 아니다.
 * 본문은 주문번호·결제 키를 찾는 데만 쓰고, 승인 여부와 금액은 {@link DonationService#handleApprovalNotice}가
 * 결제 서비스에 다시 조회해 확인한다. 같은 통지가 반복되면 처음 한 번만 확정되고 나머지는 무시된다.
 */
@RestController
public class PaymentWebhookController {

	static final String EVENT_TYPE = "PAYMENT_STATUS_CHANGED";

	private final DonationService donationService;
	private final JsonMapper jsonMapper;

	public PaymentWebhookController(DonationService donationService, JsonMapper jsonMapper) {
		this.donationService = donationService;
		this.jsonMapper = jsonMapper;
	}

	@PostMapping(value = "/payments/toss/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> handle(@RequestBody(required = false) String body) {
		JsonNode notice;
		try {
			notice = body == null ? null : jsonMapper.readTree(body);
		} catch (JacksonException e) {
			notice = null;
		}
		if (notice == null || !notice.isObject()) {
			return ResponseEntity.badRequest().body("{\"result\":\"INVALID\"}");
		}
		if (!EVENT_TYPE.equals(notice.path("eventType").asString())) {
			return ResponseEntity.ok("{\"result\":\"IGNORED\"}");
		}
		JsonNode data = notice.path("data");
		String paymentKey = data.path("paymentKey").asString("");
		String orderId = data.path("orderId").asString("");
		if (paymentKey.isBlank() || orderId.isBlank() || paymentKey.length() > 200 || orderId.length() > 64) {
			return ResponseEntity.badRequest().body("{\"result\":\"INVALID\"}");
		}
		DonationService.NoticeResult result = donationService.handleApprovalNotice(paymentKey, orderId);
		return ResponseEntity.ok("{\"result\":\"" + result.name() + "\"}");
	}

}
