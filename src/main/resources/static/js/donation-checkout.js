/*
 * 토스페이먼츠 결제창(SDK v2, 테스트 모드)을 띄운다. APP_PAYMENT_GATEWAY=toss일 때만 화면에 포함된다.
 * 금액·주문번호·성공/실패 주소는 서버가 만든 값(data-*)만 쓴다. 결제창이 성공 주소로 돌려보내면
 * 서버가 주문과 금액을 확인하고 승인 API를 호출한 뒤에만 후원을 확정한다(화면의 성공 표시로는 확정되지 않음).
 */
(function () {
	const root = document.getElementById('donation-checkout');
	if (!root || root.dataset.gateway !== 'toss') {
		return;
	}
	const button = document.getElementById('toss-pay');
	const errorText = root.querySelector('.checkout-error');

	function showError(message) {
		errorText.textContent = message;
		errorText.hidden = false;
	}

	if (typeof TossPayments !== 'function') {
		showError('결제창을 불러오지 못했습니다. 네트워크를 확인하고 새로고침해 주세요.');
		button.disabled = true;
		return;
	}

	const payment = TossPayments(root.dataset.clientKey).payment({customerKey: root.dataset.customerKey});

	button.addEventListener('click', async function () {
		button.disabled = true;
		try {
			await payment.requestPayment({
				method: 'CARD',
				amount: {currency: 'KRW', value: Number(root.dataset.amount)},
				orderId: root.dataset.orderId,
				orderName: root.dataset.orderName,
				successUrl: root.dataset.successUrl,
				failUrl: root.dataset.failUrl
			});
		} catch (error) {
			// 리다이렉트 방식에서는 대부분 실패 주소로 이동한다. 이동하지 못한 오류만 화면에 보여 준다.
			showError(error && error.message ? error.message : '결제창을 열지 못했습니다.');
			button.disabled = false;
		}
	});
})();
