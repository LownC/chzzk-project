package com.programmers.chzzk.global.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private static final Duration VIDEO_CACHE_MAX_AGE = Duration.ofDays(365);

	private final Path uploadDirectory;

	public WebConfig(@Value("${app.file.upload-dir}") Path uploadDirectory) {
		this.uploadDirectory = uploadDirectory.toAbsolutePath().normalize();
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/videos/**")
			.addResourceLocations(uploadDirectoryLocation())
			.setCacheControl(CacheControl.maxAge(VIDEO_CACHE_MAX_AGE).cachePublic().immutable())
			.resourceChain(false)
			.addResolver(new VideoFileResourceResolver(uploadDirectory));
	}

	/**
	 * 폴더를 가리키는 리소스 위치는 끝이 /여야 한다. Windows 경로도 Path의 URI 변환으로 file: URI를 만든다.
	 */
	private String uploadDirectoryLocation() {
		String location = uploadDirectory.toUri().toString();
		return location.endsWith("/") ? location : location + "/";
	}

	/**
	 * 기본 경로 리졸버를 확장해, 요청 경로가 정확히 소문자 UUID.mp4이고 정규화한 경로가 업로드 폴더 바로 아래일 때만
	 * 파일을 찾는다. 그 밖의 요청은 리소스 없음(404)이다.
	 */
	private static final class VideoFileResourceResolver extends PathResourceResolver {

		private static final Pattern VIDEO_FILE_NAME =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mp4");

		private final Path uploadDirectory;

		private VideoFileResourceResolver(Path uploadDirectory) {
			this.uploadDirectory = uploadDirectory;
		}

		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			if (!VIDEO_FILE_NAME.matcher(resourcePath).matches()) {
				return null;
			}
			Path videoFile = uploadDirectory.resolve(resourcePath).normalize();
			if (!uploadDirectory.equals(videoFile.getParent())) {
				return null;
			}
			return super.getResource(resourcePath, location);
		}

	}

}
