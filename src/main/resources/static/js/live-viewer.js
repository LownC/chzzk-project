/*
 * 라이브 시청 화면.
 * /ws/live에 watch를 보내면 송출자가 Offer를 보낸다. Answer를 돌려주고 ICE Candidate를 주고받으면
 * 송출 휴대폰에서 이 브라우저로 영상·음성이 직접 들어온다(WebRTC P2P).
 */
(function () {
	const root = document.getElementById('live-viewer');
	const video = document.getElementById('live-video');
	const unmuteButton = document.getElementById('unmute');
	const stateText = root.querySelector('.live-state');
	const statusText = root.querySelector('.live-status');
	const errorText = root.querySelector('.live-error');
	const videoFrame = root.querySelector('.video-frame');
	const broadcastId = Number(root.dataset.broadcastId);
	const iceServers = (root.dataset.iceServers || '').split(',')
		.map(url => url.trim()).filter(url => url).map(url => ({urls: url}));

	let socket = null;
	let connection = null;
	let pendingCandidates = [];
	let remoteReady = false;
	let finished = false;

	function setStatus(message) {
		statusText.textContent = message;
	}

	function showError(message) {
		errorText.textContent = message;
		errorText.hidden = false;
	}

	function send(message) {
		if (socket && socket.readyState === WebSocket.OPEN) {
			socket.send(JSON.stringify(message));
		}
	}

	function socketUrl() {
		return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/live';
	}

	async function acceptOffer(sdp) {
		connection = new RTCPeerConnection({iceServers: iceServers});
		connection.addEventListener('track', event => {
			video.srcObject = event.streams[0];
			setStatus('방송 중입니다. 소리를 들으려면 "소리 켜기"를 누르세요.');
		});
		connection.addEventListener('icecandidate', event => send({
			type: 'ice',
			candidate: event.candidate ? event.candidate.toJSON() : null
		}));
		connection.addEventListener('connectionstatechange', () => {
			if (connection.connectionState === 'failed') {
				showError('영상 연결에 실패했습니다. 송출 휴대폰과 같은 Wi-Fi인지 확인하고 새로고침해 주세요.');
			} else if (connection.connectionState === 'disconnected') {
				setStatus('송출 기기와의 연결이 불안정합니다. 잠시 기다려 주세요.');
			}
		});
		await connection.setRemoteDescription({type: 'offer', sdp: sdp});
		remoteReady = true;
		for (const candidate of pendingCandidates) {
			await connection.addIceCandidate(candidate);
		}
		pendingCandidates = [];
		const answer = await connection.createAnswer();
		await connection.setLocalDescription(answer);
		send({type: 'answer', sdp: answer.sdp});
	}

	async function addRemoteCandidate(candidate) {
		if (!candidate) {
			return;
		}
		if (remoteReady) {
			await connection.addIceCandidate(candidate);
		} else {
			pendingCandidates.push(candidate);
		}
	}

	function showEnded() {
		finished = true;
		if (connection) {
			connection.close();
		}
		video.srcObject = null;
		videoFrame.hidden = true;
		unmuteButton.hidden = true;
		stateText.textContent = '방송 종료';
		setStatus('방송이 종료되었습니다.');
	}

	async function handleMessage(message) {
		switch (message.type) {
			case 'watching':
				setStatus('송출자와 연결하는 중입니다.');
				break;
			case 'offer':
				await acceptOffer(message.sdp);
				break;
			case 'ice':
				await addRemoteCandidate(message.candidate);
				break;
			case 'ended':
				showEnded();
				break;
			case 'error':
				finished = true;
				showError(message.message);
				break;
		}
	}

	socket = new WebSocket(socketUrl());
	socket.addEventListener('open', () => send({type: 'watch', broadcastId: broadcastId}));
	socket.addEventListener('message', event => handleMessage(JSON.parse(event.data)));
	socket.addEventListener('close', () => {
		if (!finished) {
			showError('서버와 연결이 끊어졌습니다. 새로고침해 주세요.');
		}
	});

	unmuteButton.addEventListener('click', () => {
		video.muted = false;
		video.play();
		unmuteButton.hidden = true;
	});
})();
