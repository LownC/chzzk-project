package com.programmers.chzzk.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

/**
 * 연결 중단과 실제 서버 오류를 구분하는지 처리기만 따로 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTests {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void clientDisconnectRendersNothingAndIsNotLoggedAsError(CapturedOutput output) throws Exception {
		Object response = handler.handleUnexpectedFailure(
			new ClientAbortException(new IOException("Broken pipe")), videoRequest());

		assertThat(response).isInstanceOfSatisfying(ModelAndView.class, modelAndView -> {
			assertThat(modelAndView.hasView()).isFalse();
			assertThat(modelAndView.isEmpty()).isTrue();
		});
		assertThat(output.getOut()).doesNotContain("ERROR");
	}

	@Test
	void serverIoFailureIsStillAnUnexpectedError(CapturedOutput output) throws Exception {
		Object response = handler.handleUnexpectedFailure(new IOException("disk read failure"), videoRequest());

		assertThat(response).isInstanceOfSatisfying(ModelAndView.class, modelAndView -> {
			assertThat(modelAndView.getViewName()).isEqualTo("error/500");
			assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		});
		assertThat(output.getOut()).contains("ERROR").contains("예상하지 못한 오류");
	}

	/**
	 * 원인 메시지에 "connection reset"이 있어도 DB 오류는 클라이언트 연결 중단이 아니다.
	 */
	@Test
	void databaseFailureMentioningConnectionResetIsStillAnUnexpectedError(CapturedOutput output) throws Exception {
		Exception databaseFailure = new DataAccessResourceFailureException("could not execute statement",
			new SQLException("Connection reset by peer"));

		Object response = handler.handleUnexpectedFailure(databaseFailure, videoRequest());

		assertThat(response).isInstanceOfSatisfying(ModelAndView.class,
			modelAndView -> assertThat(modelAndView.getViewName()).isEqualTo("error/500"));
		assertThat(output.getOut()).contains("ERROR").contains("예상하지 못한 오류");
	}

	private static MockHttpServletRequest videoRequest() {
		return new MockHttpServletRequest("GET", "/videos/5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b.mp4");
	}

}
