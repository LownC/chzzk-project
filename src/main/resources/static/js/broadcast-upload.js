/*
 * 등록 폼 제출을 가로채 XHR로 업로드하고 진행률을 보여 준다.
 * 이 스크립트가 동작하지 않으면 폼은 일반 multipart 제출로 동작한다.
 */
(function () {
	// spec 기준: 500MB(524,288,000바이트) 사전 차단, XHR timeout 30분(1,800,000ms)
	const MAX_VIDEO_FILE_SIZE = 524288000;
	const UPLOAD_TIMEOUT_MILLIS = 1800000;

	const VIDEO_FILE_TOO_LARGE_MESSAGE = '영상 파일은 최대 500MB까지 업로드할 수 있습니다.';
	const REGISTRATION_FAILED_MESSAGE = '방송 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.';
	const NETWORK_ERROR_MESSAGE = '네트워크 연결이 불안정합니다. 연결 상태를 확인하고 다시 시도해 주세요.';
	const TIMEOUT_MESSAGE = '영상 업로드 시간이 초과되었습니다. 다시 시도해 주세요.';

	const form = document.getElementById('broadcast-form');
	const fileInput = document.getElementById('videoFile');
	const submitButton = form.querySelector('button[type="submit"]');
	const errorMessage = document.querySelector('.error-message');
	const progressArea = form.querySelector('.upload-progress');
	const progressBar = progressArea.querySelector('progress');
	const progressPercent = progressArea.querySelector('.upload-percent');

	function warnBeforeLeaving(event) {
		event.preventDefault();
		event.returnValue = '';
	}

	function showProgress(percent) {
		progressBar.value = percent;
		progressPercent.textContent = percent + '%';
	}

	function startUpload() {
		errorMessage.textContent = '';
		submitButton.disabled = true;
		showProgress(0);
		progressArea.hidden = false;
		window.addEventListener('beforeunload', warnBeforeLeaving);
	}

	function failUpload(message) {
		window.removeEventListener('beforeunload', warnBeforeLeaving);
		errorMessage.textContent = message;
		submitButton.disabled = false;
		showProgress(0);
		progressArea.hidden = true;
		fileInput.value = '';
	}

	/**
	 * 오류 응답 JSON의 message를 꺼낸다. JSON이 아니면 방송 등록 실패 문구를 쓴다.
	 */
	function errorMessageOf(xhr) {
		try {
			const body = JSON.parse(xhr.responseText);
			if (body && typeof body.message === 'string') {
				return body.message;
			}
		} catch (e) {
			// JSON이 아닌 응답(프록시 오류 페이지 등)은 아래 기본 문구로 처리한다.
		}
		return REGISTRATION_FAILED_MESSAGE;
	}

	form.addEventListener('submit', function (event) {
		event.preventDefault();

		const videoFile = fileInput.files[0];
		if (videoFile && videoFile.size > MAX_VIDEO_FILE_SIZE) {
			errorMessage.textContent = VIDEO_FILE_TOO_LARGE_MESSAGE;
			return;
		}

		const xhr = new XMLHttpRequest();
		xhr.open('POST', form.action);
		xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
		xhr.timeout = UPLOAD_TIMEOUT_MILLIS;

		xhr.upload.addEventListener('progress', function (progress) {
			if (progress.lengthComputable) {
				showProgress(Math.floor(progress.loaded * 100 / progress.total));
			}
		});
		xhr.addEventListener('load', function () {
			if (xhr.status === 201) {
				window.removeEventListener('beforeunload', warnBeforeLeaving);
				window.location.href = JSON.parse(xhr.responseText).redirectUrl;
				return;
			}
			failUpload(errorMessageOf(xhr));
		});
		xhr.addEventListener('error', function () {
			failUpload(NETWORK_ERROR_MESSAGE);
		});
		xhr.addEventListener('timeout', function () {
			failUpload(TIMEOUT_MESSAGE);
		});

		startUpload();
		xhr.send(new FormData(form));
	});
})();
