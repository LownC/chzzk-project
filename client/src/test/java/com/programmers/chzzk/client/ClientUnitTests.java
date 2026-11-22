package com.programmers.chzzk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.programmers.chzzk.client.BroadcastApiClient.Broadcast;
import com.programmers.chzzk.client.BroadcastApiClient.BroadcastPage;

class ClientUnitTests {

	@Test
	void serverDefaultsToDeployedServer() {
		assertEquals(URI.create("https://chzzk.lowndev.com"), ServerAddress.resolve(List.of(), Map.of()));
	}

	@Test
	void argumentWinsOverEnvironmentAndTrailingSlashIsRemoved() {
		Map<String, String> env = Map.of(ServerAddress.ENV_NAME, "http://env.example:9000");
		assertEquals(URI.create("http://127.0.0.1:8080"),
			ServerAddress.resolve(List.of("--server=http://127.0.0.1:8080/"), env));
		assertEquals(URI.create("http://env.example:9000"), ServerAddress.resolve(List.of(), env));
	}

	@Test
	void nonHttpServerIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("ftp://example.org"));
		assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("not a url"));
	}

	@Test
	void parsesBroadcastListAndMarksOnlyRecordedAsPlayable() throws IOException {
		String json = """
			{"broadcasts":[
			  {"id":2,"title":"라이브","type":"LIVE","liveStatus":"LIVE","playbackUrl":null,
			   "pageUrl":"https://chzzk.lowndev.com/broadcasts/2","createdAt":"2026-11-22T10:00:00"},
			  {"id":1,"title":"녹화","type":"RECORDED","liveStatus":null,
			   "playbackUrl":"https://chzzk.lowndev.com/videos/a.mp4","pageUrl":"https://chzzk.lowndev.com/broadcasts/1",
			   "createdAt":"2026-11-22T09:00:00","futureField":true}
			],"page":1,"totalPages":1,"totalElements":2}
			""";

		BroadcastPage page = BroadcastApiClient.parse(json);

		assertEquals(2, page.broadcasts().size());
		Broadcast live = page.broadcasts().get(0);
		Broadcast recorded = page.broadcasts().get(1);
		assertFalse(live.playable());
		assertNull(live.playbackUrl());
		assertEquals("[라이브] 라이브", live.label());
		assertTrue(recorded.playable());
		assertEquals("https://chzzk.lowndev.com/videos/a.mp4", recorded.playbackUrl());
		assertEquals(1, page.totalPages());
	}

}
