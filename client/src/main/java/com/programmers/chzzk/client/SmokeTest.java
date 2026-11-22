package com.programmers.chzzk.client;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import com.programmers.chzzk.client.BroadcastApiClient.Broadcast;
import com.programmers.chzzk.client.BroadcastApiClient.BroadcastPage;

/**
 * 화면 없이 확인하는 모드(--smoke-test): 목록 API 조회 → 첫 녹화방송을 MediaPlayer로 2초 이상 재생 → 중간으로 탐색.
 * 결과를 표준 출력(--smoke-log=파일을 주면 그 파일에도)에 남기고 성공 0, 실패 1을 돌려준다.
 * 패키지의 실행 파일은 콘솔이 없어 출력이 보이지 않으므로 --smoke-log로 결과를 남긴다. 실제 화면 표시·소리 확인은 하지 않는다.
 */
final class SmokeTest {

	private static final long TIMEOUT_SECONDS = 40;
	private static final String LOG_PREFIX = "--smoke-log=";
	private static PrintStream log = System.out;

	private SmokeTest() {
	}

	static int run(String[] args) {
		for (String arg : args) {
			if (arg.startsWith(LOG_PREFIX)) {
				try {
					log = new PrintStream(new FileOutputStream(arg.substring(LOG_PREFIX.length())), true, StandardCharsets.UTF_8);
				} catch (IOException e) {
					System.err.println("SMOKE cannot open log file: " + e.getMessage());
				}
			}
		}
		URI server = ServerAddress.resolve(List.of(args), System.getenv());
		println("SMOKE server=" + server + " java=" + System.getProperty("java.version")
			+ " runtime=" + System.getProperty("java.home") + " os=" + System.getProperty("os.name") + "/"
			+ System.getProperty("os.arch"));
		BroadcastPage page;
		try {
			page = new BroadcastApiClient(server).fetch(1);
		} catch (Exception e) {
			println("SMOKE FAIL list: " + e);
			return 1;
		}
		println("SMOKE list ok: " + page.broadcasts().size() + " broadcasts, totalPages=" + page.totalPages());
		Optional<Broadcast> target = page.broadcasts().stream().filter(Broadcast::playable).findFirst();
		if (target.isEmpty()) {
			println("SMOKE FAIL playback: no recorded broadcast to play");
			return 1;
		}
		println("SMOKE playing: " + target.get().title() + " " + target.get().playbackUrl());
		int result = play(target.get().playbackUrl());
		Platform.exit();
		return result;
	}

	private static int play(String url) {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> outcome = new AtomicReference<>("timeout after " + TIMEOUT_SECONDS + "s");
		AtomicReference<MediaPlayer> holder = new AtomicReference<>();
		Platform.startup(() -> {
			Media media = new Media(url);
			MediaPlayer player = new MediaPlayer(media);
			holder.set(player);
			player.setMute(true);
			Runnable fail = () -> {
				outcome.set("error " + (player.getError() != null ? player.getError() : media.getError()));
				done.countDown();
			};
			media.setOnError(fail);
			player.setOnError(fail);
			player.setOnReady(() -> {
				println("SMOKE ready: duration=" + media.getDuration().toSeconds() + "s width="
					+ media.getWidth() + " height=" + media.getHeight());
				player.play();
			});
			AtomicReference<Boolean> seeked = new AtomicReference<>(false);
			player.currentTimeProperty().addListener((obs, old, now) -> {
				double total = media.getDuration().toSeconds();
				if (!seeked.get() && now.toSeconds() >= 2.0) {
					println("SMOKE played " + String.format("%.1f", now.toSeconds()) + "s, seeking to middle");
					seeked.set(true);
					player.seek(Duration.seconds(total / 2));
				} else if (seeked.get() && now.toSeconds() >= total / 2 + 0.5) {
					outcome.set("ok (played, seek to " + String.format("%.1f", now.toSeconds()) + "s)");
					done.countDown();
				}
			});
		});
		try {
			done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		Platform.runLater(() -> Optional.ofNullable(holder.get()).ifPresent(MediaPlayer::dispose));
		boolean ok = outcome.get().startsWith("ok");
		println("SMOKE " + (ok ? "PASS" : "FAIL") + " playback: " + outcome.get());
		return ok ? 0 : 1;
	}

	private static void println(String line) {
		System.out.println(line);
		if (log != System.out) {
			log.println(line);
		}
	}

}
