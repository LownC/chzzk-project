package com.programmers.chzzk.global.cluster;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * (⑤) 여러 인스턴스로 실행할 때만 켜지는 설정({@code app.cluster.enabled=true}, cluster 프로파일).
 * <ul>
 *     <li>로그인 세션(Spring Security 인증 정보, CSRF 토큰, 저장된 요청)을 Redis에 둔다. 어느 인스턴스로 요청이 가도 로그인 상태가 같다.
 *     세션 30분 만료는 ③과 같다.</li>
 *     <li>응답 헤더 {@code X-Chzzk-Instance}에 응답한 인스턴스 이름(컨테이너 호스트 이름)을 넣는다. 부하 분산 확인용이다.</li>
 * </ul>
 * 인스턴스가 하나인 기본·테스트 실행에서는 만들어지지 않아 ①~④ 동작(서버 메모리 세션)이 그대로다.
 */
@Configuration
@ConditionalOnProperty(name = "app.cluster.enabled", havingValue = "true")
@EnableRedisHttpSession(redisNamespace = "chzzk:session", maxInactiveIntervalInSeconds = 1800)
public class ClusterConfig {

	static final String INSTANCE_HEADER = "X-Chzzk-Instance";

	@Bean
	public FilterRegistrationBean<OncePerRequestFilter> instanceHeaderFilter() {
		String instance = System.getenv().getOrDefault("HOSTNAME", "unknown");
		FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
			@Override
			protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
				response.setHeader(INSTANCE_HEADER, instance);
				chain.doFilter(request, response);
			}
		});
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

}
