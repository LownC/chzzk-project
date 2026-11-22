package com.programmers.chzzk.client;

import java.util.List;

import javafx.application.Application;

/**
 * 실행 진입점. JavaFX jar를 클래스패스로 실을 때는 Application을 상속하지 않은 클래스에서 launch해야 한다.
 * --smoke-test를 주면 화면 없이 목록 조회·재생·탐색을 확인하고 결과를 출력한 뒤 끝난다(로컬·CI 확인용).
 */
public final class Launcher {

	private Launcher() {
	}

	public static void main(String[] args) {
		if (List.of(args).contains("--smoke-test")) {
			System.exit(SmokeTest.run(args));
		}
		Application.launch(ClientApp.class, args);
	}

}
