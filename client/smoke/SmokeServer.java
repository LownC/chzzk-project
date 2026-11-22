import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * ⑥ 클라이언트 패키지 확인용 작은 HTTP 서버(배포 서버 대신, 외부 의존성 없음). CI와 로컬에서만 쓴다.
 *   java client/smoke/SmokeServer.java 18080 client/smoke/sample-h264-aac.mp4
 * 실제 백엔드와 같은 모양의 GET /api/broadcasts(녹화 1개 + 라이브 1개)와, Range(206)를 지원하는 GET/HEAD /videos/sample.mp4를 준다.
 */
public class SmokeServer {

	private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

	public static void main(String[] args) throws IOException {
		int port = Integer.parseInt(args[0]);
		Path video = Path.of(args[1]);
		long length = Files.size(video);
		String base = "http://127.0.0.1:" + port;
		String list = """
			{"broadcasts":[
			 {"id":2,"title":"smoke live","type":"LIVE","liveStatus":"LIVE","playbackUrl":null,"pageUrl":"%1$s/broadcasts/2","createdAt":"2026-11-22T10:00:00"},
			 {"id":1,"title":"smoke recorded","type":"RECORDED","liveStatus":null,"playbackUrl":"%1$s/videos/sample.mp4","pageUrl":"%1$s/broadcasts/1","createdAt":"2026-11-22T09:00:00"}
			],"page":1,"totalPages":1,"totalElements":2}
			""".formatted(base);

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/api/broadcasts", exchange -> {
			byte[] body = list.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/videos/sample.mp4", exchange -> serveVideo(exchange, video, length));
		server.start();
		System.out.println("smoke server on " + base + " video=" + video + " (" + length + " bytes)");
	}

	private static void serveVideo(HttpExchange exchange, Path video, long length) throws IOException {
		long start = 0;
		long end = length - 1;
		int status = 200;
		String range = exchange.getRequestHeaders().getFirst("Range");
		if (range != null) {
			Matcher m = RANGE.matcher(range.trim());
			if (m.matches()) {
				if (m.group(1).isEmpty()) {
					start = Math.max(0, length - Long.parseLong(m.group(2)));
				} else {
					start = Long.parseLong(m.group(1));
					if (!m.group(2).isEmpty()) {
						end = Math.min(end, Long.parseLong(m.group(2)));
					}
				}
				status = 206;
			}
		}
		if (start > end || start >= length) {
			exchange.getResponseHeaders().set("Content-Range", "bytes */" + length);
			exchange.sendResponseHeaders(416, -1);
			exchange.close();
			return;
		}
		long count = end - start + 1;
		exchange.getResponseHeaders().set("Content-Type", "video/mp4");
		exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
		if (status == 206) {
			exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + length);
		}
		boolean head = "HEAD".equals(exchange.getRequestMethod());
		exchange.sendResponseHeaders(status, head ? -1 : count);
		if (head) {
			exchange.close();
			return;
		}
		try (RandomAccessFile file = new RandomAccessFile(video.toFile(), "r"); OutputStream out = exchange.getResponseBody()) {
			file.seek(start);
			byte[] buffer = new byte[64 * 1024];
			long remaining = count;
			while (remaining > 0) {
				int read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
				if (read < 0) {
					break;
				}
				out.write(buffer, 0, read);
				remaining -= read;
			}
		} catch (IOException e) {
			// 플레이어가 탐색하며 연결을 끊는 경우가 있다. 서버는 계속 동작한다.
		}
	}

}
