package com.programmers.chzzk.download;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * (⑥) 데스크톱 클라이언트 다운로드 페이지.
 */
class DownloadPageTests {

	private static final String RELEASE = "https://github.com/LownC/chzzk-project/releases/download/client-v1.0.0/";

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	class WithDefaultSettings {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void linksThePublishedReleaseFilesAndChecksums() throws Exception {
			mockMvc.perform(get("/download"))
				.andExpect(status().isOk())
				.andExpect(xpath("//tr[@id='windows']").string(containsString("x64")))
				.andExpect(xpath("//tr[@id='macos']").string(containsString("arm64")))
				.andExpect(xpath("//tr[@id='windows']//a/@href").string(RELEASE + "chzzk-client-windows-x64.zip"))
				.andExpect(xpath("//tr[@id='macos']//a/@href").string(RELEASE + "chzzk-client-macos-arm64.dmg"))
				.andExpect(xpath("//a[text()='SHA256SUMS.txt']/@href").string(RELEASE + "SHA256SUMS.txt"))
				.andExpect(content().string(containsString("Java를 따로 설치하지 않아도")))
				.andExpect(content().string(containsString("서명하지 않은")));
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"app.download.windows-x64-url=",
		"app.download.macos-arm64-url=",
		"app.download.checksums-url="})
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	class WhenLinksAreCleared {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void showsNotPublishedInsteadOfLinks() throws Exception {
			mockMvc.perform(get("/download"))
				.andExpect(status().isOk())
				.andExpect(xpath("//tr[@id='windows']//a").doesNotExist())
				.andExpect(xpath("//tr[@id='macos']//a").doesNotExist())
				.andExpect(xpath("//a[text()='SHA256SUMS.txt']").doesNotExist())
				.andExpect(content().string(containsString("아직 게시되지 않음")));
		}
	}

}
