package com.programmers.chzzk.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 설정({@code app.payment}). 값은 환경변수로 넣는다(README "환경변수").
 *
 * @param gateway {@code fake}(기본, 외부 호출 없음) 또는 {@code toss}(토스페이먼츠 테스트 모드)
 * @param toss    토스페이먼츠 테스트 키와 API 주소. {@code toss}일 때만 쓴다.
 */
@ConfigurationProperties("app.payment")
public record PaymentProperties(String gateway, Toss toss) {

	public PaymentProperties {
		gateway = gateway == null || gateway.isBlank() ? FakePaymentGateway.NAME : gateway.strip();
		toss = toss == null ? new Toss(null, null, null) : toss;
	}

	/**
	 * @param clientKey  브라우저 결제창용 테스트 클라이언트 키({@code test_ck_}·{@code test_gck_}로 시작)
	 * @param secretKey  서버 승인용 테스트 시크릿 키({@code test_sk_}·{@code test_gsk_}로 시작). 로그·화면에 내보내지 않는다.
	 * @param apiBaseUrl 토스페이먼츠 API 주소
	 */
	public record Toss(String clientKey, String secretKey, String apiBaseUrl) {

		public Toss {
			clientKey = clientKey == null ? "" : clientKey.strip();
			secretKey = secretKey == null ? "" : secretKey.strip();
			apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.tosspayments.com" : apiBaseUrl.strip();
		}

		@Override
		public String toString() {
			return "Toss[clientKey=" + mask(clientKey) + ", secretKey=" + mask(secretKey) + ", apiBaseUrl=" + apiBaseUrl + "]";
		}

		private static String mask(String key) {
			return key.isEmpty() ? "(없음)" : "(설정됨)";
		}

	}

}
