package com.programmers.chzzk.client;

import java.net.URI;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.programmers.chzzk.client.BroadcastApiClient.Broadcast;
import com.programmers.chzzk.client.BroadcastApiClient.BroadcastPage;

/**
 * 방송 목록(왼쪽)과 플레이어(가운데). 목록 요청은 배경 스레드(Task)에서 하고 결과만 화면 스레드에서 반영한다.
 */
public class ClientApp extends Application {

	private final ListView<Broadcast> broadcastList = new ListView<>();
	private final MediaView mediaView = new MediaView();
	private final Label message = new Label("방송을 선택하세요.");
	private final Label status = new Label();
	private final Label pageLabel = new Label();
	private final Button previousPage = new Button("이전");
	private final Button nextPage = new Button("다음");
	private final Button playPause = new Button("재생");
	private final Button stop = new Button("정지");
	private final Slider seekBar = new Slider();
	private final Label time = new Label("00:00 / 00:00");

	private BroadcastApiClient apiClient;
	private MediaPlayer player;
	private int currentPage = 1;
	private int totalPages = 0;

	@Override
	public void start(Stage stage) {
		URI server;
		try {
			server = ServerAddress.resolve(getParameters().getRaw(), System.getenv());
		} catch (IllegalArgumentException e) {
			server = URI.create(ServerAddress.DEFAULT_SERVER);
			status.setText(e.getMessage() + " 기본 서버를 씁니다.");
		}
		apiClient = new BroadcastApiClient(server);

		stage.setTitle("치지직 - " + server);
		stage.setScene(new Scene(layout(server), 1100, 640));
		stage.setOnCloseRequest(event -> disposePlayer());
		stage.show();
		loadPage(1);
	}

	private BorderPane layout(URI server) {
		broadcastList.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(Broadcast item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : item.label());
			}
		});
		broadcastList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> select(selected));
		broadcastList.setPrefWidth(320);

		Button refresh = new Button("새로고침");
		refresh.setOnAction(event -> loadPage(currentPage));
		previousPage.setOnAction(event -> loadPage(currentPage - 1));
		nextPage.setOnAction(event -> loadPage(currentPage + 1));
		HBox pager = new HBox(6, previousPage, pageLabel, nextPage, refresh);
		pager.setAlignment(Pos.CENTER_LEFT);
		VBox left = new VBox(6, new Label("서버: " + server), broadcastList, pager);
		VBox.setVgrow(broadcastList, Priority.ALWAYS);
		left.setPadding(new Insets(8));

		mediaView.setPreserveRatio(true);
		message.setStyle("-fx-text-fill: white;");
		message.setWrapText(true);
		StackPane screen = new StackPane(mediaView, message);
		screen.setStyle("-fx-background-color: black;");
		mediaView.fitWidthProperty().bind(screen.widthProperty());
		mediaView.fitHeightProperty().bind(screen.heightProperty());

		playPause.setOnAction(event -> togglePlay());
		stop.setOnAction(event -> {
			if (player != null) {
				player.stop();
				playPause.setText("재생");
			}
		});
		seekBar.setOnMouseReleased(event -> seekToBar());
		seekBar.setOnKeyReleased(event -> seekToBar());
		HBox controls = new HBox(8, playPause, stop, seekBar, time);
		HBox.setHgrow(seekBar, Priority.ALWAYS);
		controls.setAlignment(Pos.CENTER_LEFT);
		VBox bottom = new VBox(4, controls, status);
		bottom.setPadding(new Insets(8));
		setPlayerControlsDisabled(true);

		BorderPane root = new BorderPane();
		root.setLeft(left);
		root.setCenter(screen);
		root.setBottom(bottom);
		return root;
	}

	private void loadPage(int page) {
		status.setText("방송 목록을 불러오는 중…");
		previousPage.setDisable(true);
		nextPage.setDisable(true);
		Task<BroadcastPage> task = new Task<>() {
			@Override
			protected BroadcastPage call() throws Exception {
				return apiClient.fetch(page);
			}
		};
		task.setOnSucceeded(event -> {
			BroadcastPage result = task.getValue();
			currentPage = result.page();
			totalPages = result.totalPages();
			broadcastList.getItems().setAll(result.broadcasts());
			pageLabel.setText(totalPages == 0 ? "0 / 0" : currentPage + " / " + totalPages);
			previousPage.setDisable(currentPage <= 1);
			nextPage.setDisable(currentPage >= totalPages);
			status.setText(result.broadcasts().isEmpty() ? "등록된 방송이 없습니다." : "방송 " + result.broadcasts().size() + "개");
		});
		task.setOnFailed(event -> {
			previousPage.setDisable(currentPage <= 1);
			nextPage.setDisable(currentPage >= totalPages);
			Throwable error = task.getException();
			status.setText("방송 목록을 불러오지 못했습니다. 네트워크와 서버 주소(" + apiClient.server() + ")를 확인하세요."
				+ (error.getMessage() == null ? "" : " (" + error.getMessage() + ")"));
		});
		Thread thread = new Thread(task, "broadcast-list");
		thread.setDaemon(true);
		thread.start();
	}

	private void select(Broadcast broadcast) {
		disposePlayer();
		if (broadcast == null) {
			return;
		}
		if (!broadcast.playable()) {
			message.setText("라이브 방송은 앱에서 재생할 수 없습니다. 웹 화면에서 시청하세요.\n" + broadcast.pageUrl());
			message.setVisible(true);
			return;
		}
		try {
			Media media = new Media(broadcast.playbackUrl());
			MediaPlayer newPlayer = new MediaPlayer(media);
			media.setOnError(() -> showPlaybackError(media.getError()));
			newPlayer.setOnError(() -> showPlaybackError(newPlayer.getError()));
			newPlayer.setOnReady(() -> {
				seekBar.setMax(Math.max(media.getDuration().toSeconds(), 0));
				setPlayerControlsDisabled(false);
				newPlayer.play();
			});
			newPlayer.setOnPlaying(() -> playPause.setText("일시정지"));
			newPlayer.setOnPaused(() -> playPause.setText("재생"));
			newPlayer.setOnEndOfMedia(() -> {
				newPlayer.stop();
				playPause.setText("재생");
			});
			newPlayer.currentTimeProperty().addListener((obs, old, now) -> {
				if (!seekBar.isValueChanging() && !seekBar.isPressed()) {
					seekBar.setValue(now.toSeconds());
				}
				time.setText(format(now) + " / " + format(media.getDuration()));
			});
			player = newPlayer;
			mediaView.setMediaPlayer(newPlayer);
			message.setVisible(false);
			status.setText("재생 준비 중: " + broadcast.title());
		} catch (MediaException | IllegalArgumentException e) {
			showPlaybackError(e);
		}
	}

	private void togglePlay() {
		if (player == null) {
			return;
		}
		if (player.getStatus() == MediaPlayer.Status.PLAYING) {
			player.pause();
		} else {
			player.play();
		}
	}

	private void seekToBar() {
		if (player != null) {
			player.seek(Duration.seconds(seekBar.getValue()));
		}
	}

	private void showPlaybackError(Exception error) {
		disposePlayer();
		message.setText("영상을 재생할 수 없습니다. 네트워크 연결이나 영상 형식을 확인하세요."
			+ (error == null || error.getMessage() == null ? "" : "\n(" + error.getMessage() + ")"));
		message.setVisible(true);
	}

	private void disposePlayer() {
		if (player != null) {
			player.dispose();
			player = null;
		}
		mediaView.setMediaPlayer(null);
		seekBar.setValue(0);
		time.setText("00:00 / 00:00");
		playPause.setText("재생");
		setPlayerControlsDisabled(true);
	}

	private void setPlayerControlsDisabled(boolean disabled) {
		playPause.setDisable(disabled);
		stop.setDisable(disabled);
		seekBar.setDisable(disabled);
	}

	private static String format(Duration duration) {
		if (duration == null || duration.isUnknown() || duration.isIndefinite()) {
			return "--:--";
		}
		long seconds = (long) duration.toSeconds();
		return String.format("%02d:%02d", seconds / 60, seconds % 60);
	}

}
