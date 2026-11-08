package com.programmers.chzzk.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;

import com.programmers.chzzk.broadcast.BroadcastRegistrationError;
import com.programmers.chzzk.broadcast.BroadcastRegistrationException;

import jakarta.servlet.http.HttpServletRequest;

@ControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String XHR_HEADER = "X-Requested-With";
	private static final String XHR_HEADER_VALUE = "XMLHttpRequest";
	private static final String REGISTRATION_PATH = "/broadcasts";

	@ExceptionHandler(BroadcastRegistrationException.class)
	public Object handleRegistrationFailure(BroadcastRegistrationException exception, HttpServletRequest request) {
		BroadcastRegistrationError error = exception.getError();
		if (error.status().is5xxServerError()) {
			log.error("방송 등록 실패: reason={}", error, exception);
		} else {
			log.info("방송 등록 입력 거부: reason={}", error);
		}
		return registrationErrorResponse(request, error.status(), error.message(), request.getParameter("title"));
	}

	/**
	 * multipart 해석 단계에서 용량 한도를 넘은 요청. 일반 multipart 예외의 하위 유형이므로 아래 처리기보다 먼저 적용된다.
	 * 요청 파라미터를 읽을 수 없으므로 제목은 다시 채우지 않는다.
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public Object handleUploadSizeExceeded(MaxUploadSizeExceededException exception, HttpServletRequest request) {
		log.info("업로드 용량 초과로 요청 거부: {}", exception.getMessage());
		BroadcastRegistrationError error = BroadcastRegistrationError.VIDEO_FILE_TOO_LARGE;
		return registrationErrorResponse(request, error.status(), error.message(), null);
	}

	/**
	 * 형식이 깨져 해석할 수 없는 multipart 요청. 클라이언트 요청 오류이므로 400과 일반 등록 실패 문구로 응답한다.
	 */
	@ExceptionHandler(MultipartException.class)
	public Object handleMalformedMultipart(MultipartException exception, HttpServletRequest request) {
		log.warn("잘못된 multipart 요청 거부", exception);
		return registrationErrorResponse(request, HttpStatus.BAD_REQUEST,
			BroadcastRegistrationError.REGISTRATION_FAILED.message(), null);
	}

	/**
	 * 등록 요청에서 분류되지 않은 예외(DB 저장 실패 포함)는 일반 등록 실패로 응답한다.
	 * 그 밖의 요청은 찾을 수 없음(방송 없음, 없는 경로·영상)이면 404 화면, Spring이 상태를 정해 둔 다른 요청 오류는
	 * 기본 오류 처리에 맡기고, 나머지 예상하지 못한 오류는 500 화면으로 응답한다.
	 */
	@ExceptionHandler(Exception.class)
	public Object handleUnexpectedFailure(Exception exception, HttpServletRequest request) throws Exception {
		if (isRegistrationRequest(request)) {
			log.error("방송 등록 중 예상하지 못한 오류", exception);
			BroadcastRegistrationError error = BroadcastRegistrationError.REGISTRATION_FAILED;
			return registrationErrorResponse(request, error.status(), error.message(), request.getParameter("title"));
		}
		if (exception instanceof org.springframework.web.ErrorResponse errorResponse) {
			if (errorResponse.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
				return new ModelAndView("error/404", HttpStatus.NOT_FOUND);
			}
			throw exception;
		}
		log.error("예상하지 못한 오류: method={}, uri={}", request.getMethod(), request.getRequestURI(), exception);
		return new ModelAndView("error/500", HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * 등록 오류를 요청 종류에 맞게 응답한다. XHR은 JSON, 일반 폼은 제출한 제목을 채운 등록 화면이다.
	 */
	private Object registrationErrorResponse(HttpServletRequest request, HttpStatus status, String message,
		String submittedTitle) {
		if (XHR_HEADER_VALUE.equals(request.getHeader(XHR_HEADER))) {
			return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new ErrorResponse(message));
		}
		ModelAndView registrationPage = new ModelAndView("new", status);
		registrationPage.addObject("title", submittedTitle);
		registrationPage.addObject("errorMessage", message);
		return registrationPage;
	}

	private static boolean isRegistrationRequest(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return HttpMethod.POST.matches(request.getMethod()) && REGISTRATION_PATH.equals(path);
	}

	record ErrorResponse(String message) {
	}

}
