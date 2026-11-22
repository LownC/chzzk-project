package com.programmers.chzzk.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * 서버의 방송 목록 API(GET /api/broadcasts?page=n)를 부른다. JavaFX 화면 스레드가 아닌 곳에서 호출한다.
 */
public final class BroadcastApiClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private final URI server;
	private final HttpClient httpClient;

	public BroadcastApiClient(URI server) {
		this.server = server;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	public URI server() {
		return server;
	}

	public BroadcastPage fetch(int page) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(server + "/api/broadcasts?page=" + page))
			.header("Accept", "application/json")
			.timeout(REQUEST_TIMEOUT)
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("서버가 목록 요청에 HTTP " + response.statusCode() + "로 응답했습니다.");
		}
		return parse(response.body());
	}

	static BroadcastPage parse(String json) throws IOException {
		Map<String, Object> root = JSON.std.mapFrom(json);
		List<Broadcast> broadcasts = new ArrayList<>();
		for (Object item : (List<?>) root.getOrDefault("broadcasts", List.of())) {
			Map<?, ?> map = (Map<?, ?>) item;
			broadcasts.add(new Broadcast(
				((Number) map.get("id")).longValue(),
				(String) map.get("title"),
				(String) map.get("type"),
				(String) map.get("liveStatus"),
				(String) map.get("playbackUrl"),
				(String) map.get("pageUrl")));
		}
		return new BroadcastPage(List.copyOf(broadcasts),
			((Number) root.getOrDefault("page", 1)).intValue(),
			((Number) root.getOrDefault("totalPages", 0)).intValue());
	}

	public record BroadcastPage(List<Broadcast> broadcasts, int page, int totalPages) {
	}

	/**
	 * type은 RECORDED(녹화) 또는 LIVE. 앱은 녹화방송만 재생하고, 라이브는 웹 화면(pageUrl)에서 본다.
	 */
	public record Broadcast(long id, String title, String type, String liveStatus, String playbackUrl,
		String pageUrl) {

		public boolean playable() {
			return "RECORDED".equals(type) && playbackUrl != null;
		}

		public String label() {
			if ("LIVE".equals(type)) {
				return "[" + ("LIVE".equals(liveStatus) ? "라이브" : "라이브 종료") + "] " + title;
			}
			return title;
		}
	}

}
