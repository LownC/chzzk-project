package com.programmers.chzzk.client;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 접속할 치지직 서버 주소. 우선순위: 실행 인자 --server=주소 → 환경변수 CHZZK_SERVER_URL → 배포 서버(https://chzzk.lowndev.com).
 */
public final class ServerAddress {

	public static final String DEFAULT_SERVER = "https://chzzk.lowndev.com";
	public static final String ENV_NAME = "CHZZK_SERVER_URL";
	private static final String ARG_PREFIX = "--server=";

	private ServerAddress() {
	}

	public static URI resolve(List<String> args, Map<String, String> environment) {
		String value = args.stream()
			.filter(arg -> arg.startsWith(ARG_PREFIX))
			.map(arg -> arg.substring(ARG_PREFIX.length()))
			.findFirst()
			.orElseGet(() -> environment.getOrDefault(ENV_NAME, DEFAULT_SERVER));
		return parse(value);
	}

	static URI parse(String value) {
		String trimmed = value.strip();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		URI uri;
		try {
			uri = URI.create(trimmed);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("서버 주소 형식이 올바르지 않습니다: " + value, e);
		}
		if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
			throw new IllegalArgumentException("서버 주소는 http:// 또는 https://로 시작해야 합니다: " + value);
		}
		return uri;
	}

}
