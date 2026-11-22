package com.programmers.chzzk.download;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * (⑥) 데스크톱 클라이언트 다운로드 페이지. OS별 패키지 주소는 실제로 게시한 뒤 설정(app.download.*)으로 넣는다.
 * 주소가 비어 있으면 링크 대신 "아직 게시되지 않음"을 보여 준다(없는 파일을 받을 수 있는 것처럼 보이지 않게).
 */
@Controller
public class DownloadController {

	private final String windowsX64Url;
	private final String macosArm64Url;
	private final String checksumsUrl;

	public DownloadController(@Value("${app.download.windows-x64-url:}") String windowsX64Url,
		@Value("${app.download.macos-arm64-url:}") String macosArm64Url,
		@Value("${app.download.checksums-url:}") String checksumsUrl) {
		this.windowsX64Url = blankToNull(windowsX64Url);
		this.macosArm64Url = blankToNull(macosArm64Url);
		this.checksumsUrl = blankToNull(checksumsUrl);
	}

	@GetMapping("/download")
	public String download(Model model) {
		model.addAttribute("windowsX64Url", windowsX64Url);
		model.addAttribute("macosArm64Url", macosArm64Url);
		model.addAttribute("checksumsUrl", checksumsUrl);
		return "download";
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

}
