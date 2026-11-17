/*
 * 휴대폰 송출 화면.
 * 1) getUserMedia로 카메라·마이크를 켜고 미리보기를 보여 준다.
 * 2) /ws/live에 start를 보내 방송을 만든다.
 * 3) 시청자가 들어올 때마다 RTCPeerConnection을 하나씩 만들어 Offer를 보내고, Answer와 ICE Candidate를 받는다.
 * 영상·음성은 서버를 거치지 않고 시청자 브라우저로 직접 간다(WebRTC P2P). 서버는 연결 협상 메시지만 중계한다.
 */
(function () {
	const HEARTBEAT_INTERVAL_MILLIS = 10000;
	const SERVICE_RESTARTED = 1012;
	const RECONNECT_DELAYS_MILLIS = [500, 1000, 2000, 3000, 5000];

	const root = document.getElementById('live-broadcaster');
	const titleInput = document.getElementById('title');
	const preview = document.getElementById('preview');
	const prepareButton = document.getElementById('prepare-camera');
	const startButton = document.getElementById('start-live');
	const endButton = document.getElementById('end-live');
	const statusText = root.querySelector('.live-status');
	const errorText = root.querySelector('.live-error');
	const watchLink = root.querySelector('.watch-link');
	const viewerCount = root.querySelector('.viewer-count');
	const iceServers = parseIceServers(root.dataset.iceServers);

	/** viewerId → { connection, pendingCandidates, remoteReady } */
	const peers = new Map();
	let localStream = null;
	let socket = null;
	let heartbeatTimer = null;
	let live = false;
	let finished = false;
	let broadcastId = null;
	let reconnecting = false;
	let resumeAttempt = 0;

	function parseIceServers(value) {
		return (value || '').split(',').map(url => url.trim()).filter(url => url).map(url => ({urls: url}));
	}

	function setStatus(message) {
		statusText.textContent = message;
	}

	function showError(message) {
		errorText.textContent = message;
		errorText.hidden = false;
	}

	function clearError() {
		errorText.textContent = '';
		errorText.hidden = true;
	}

	function cameraErrorMessage(error) {
		switch (error && error.name) {
			case 'NotAllowedError':
			case 'SecurityError':
				return '카메라·마이크 권한이 거부되었습니다. 브라우저 사이트 설정에서 권한을 허용한 뒤 다시 시도해 주세요.';
			case 'NotFoundError':
			case 'OverconstrainedError':
				return '사용할 수 있는 카메라나 마이크를 찾지 못했습니다.';
			case 'NotReadableError':
				return '다른 앱이 카메라나 마이크를 사용 중입니다. 해당 앱을 종료한 뒤 다시 시도해 주세요.';
			default:
				return '카메라·마이크를 켜지 못했습니다.';
		}
	}

	async function prepareCamera() {
		clearError();
		if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
			showError('카메라는 HTTPS 주소에서만 사용할 수 있습니다. README의 HTTPS 실행 방법을 확인해 주세요.');
			return;
		}
		prepareButton.disabled = true;
		try {
			localStream = await navigator.mediaDevices.getUserMedia({
				video: {facingMode: 'environment'},
				audio: true
			});
			preview.srcObject = localStream;
			startButton.disabled = false;
			setStatus('카메라 준비 완료. 제목을 입력하고 방송을 시작하세요.');
		} catch (error) {
			prepareButton.disabled = false;
			showError(cameraErrorMessage(error));
		}
	}

	function socketUrl() {
		return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/live';
	}

	function send(message) {
		if (socket && socket.readyState === WebSocket.OPEN) {
			socket.send(JSON.stringify(message));
		}
	}

	function startBroadcast() {
		clearError();
		const title = titleInput.value.trim();
		if (!title) {
			showError('제목을 입력해 주세요.');
			return;
		}
		startButton.disabled = true;
		setStatus('방송을 시작하는 중입니다.');
		if (socket && socket.readyState === WebSocket.OPEN) {
			send({type: 'start', title: title});
			return;
		}
		connect(() => send({type: 'start', title: title}));
	}

	function connect(onOpen) {
		socket = new WebSocket(socketUrl());
		socket.addEventListener('open', onOpen);
		socket.addEventListener('message', event => handleMessage(JSON.parse(event.data)));
		socket.addEventListener('close', handleClose);
	}

	/**
	 * (⑤) 서버 인스턴스가 줄어 연결이 끊기면(서버가 reconnect를 보내거나 1012로 닫음) 다른 인스턴스에 다시 붙어
	 * resume으로 송출을 이어 간다. 시청자와의 영상 연결(P2P)은 서버를 거치지 않으므로 그대로 유지된다.
	 */
	function handleClose(event) {
		if (live && !finished && (reconnecting || event.code === SERVICE_RESTARTED)) {
			reconnecting = true;
			if (resumeAttempt >= RECONNECT_DELAYS_MILLIS.length) {
				finish('서버와 연결이 끊어져 방송이 종료되었습니다. 다시 방송하려면 새로 시작해 주세요.');
				return;
			}
			setStatus('서버를 옮기는 중입니다. 잠시만 기다려 주세요.');
			setTimeout(() => connect(() => send({type: 'resume', broadcastId: broadcastId})),
				RECONNECT_DELAYS_MILLIS[resumeAttempt++]);
			return;
		}
		if (live && !finished) {
			finish('서버와 연결이 끊어져 방송이 종료되었습니다. 다시 방송하려면 새로 시작해 주세요.');
		} else if (!live && !finished) {
			startButton.disabled = !localStream;
			showError('서버에 연결하지 못했습니다. 같은 Wi-Fi인지, 서버가 실행 중인지 확인해 주세요.');
		}
	}

	async function handleMessage(message) {
		switch (message.type) {
			case 'started':
				onStarted(message);
				break;
			case 'viewer-joined':
				await connectViewer(message.viewerId);
				break;
			case 'answer':
				await acceptAnswer(message.viewerId, message.sdp);
				break;
			case 'ice':
				await addRemoteCandidate(message.viewerId, message.candidate);
				break;
			case 'viewer-left':
				closeViewer(message.viewerId);
				break;
			case 'ended':
				finish('방송이 종료되었습니다.');
				break;
			case 'reconnect':
				reconnecting = true;
				break;
			case 'resumed':
				reconnecting = false;
				resumeAttempt = 0;
				setStatus('방송 중');
				await syncViewers(message.viewerIds || []);
				break;
			case 'error':
				if (reconnecting) {
					finish('방송을 이어 가지 못했습니다: ' + message.message);
					break;
				}
				if (message.code === 'UNKNOWN_VIEWER') {
					// 시청자가 나가는 순간 보낸 연결 메시지라 무시해도 된다.
					break;
				}
				if (!live) {
					startButton.disabled = false;
				}
				showError(message.message);
				break;
		}
	}

	function onStarted(message) {
		live = true;
		broadcastId = message.broadcastId;
		titleInput.disabled = true;
		endButton.disabled = false;
		setStatus('방송 중');
		const watchUrl = location.origin + message.watchUrl;
		const anchor = watchLink.querySelector('a');
		anchor.href = watchUrl;
		anchor.textContent = watchUrl;
		watchLink.hidden = false;
		heartbeatTimer = setInterval(() => send({type: 'heartbeat'}), HEARTBEAT_INTERVAL_MILLIS);
		window.addEventListener('beforeunload', warnBeforeLeaving);
	}

	function warnBeforeLeaving(event) {
		event.preventDefault();
		event.returnValue = '';
	}

	async function connectViewer(viewerId) {
		const connection = new RTCPeerConnection({iceServers: iceServers});
		const peer = {connection: connection, pendingCandidates: [], remoteReady: false};
		peers.set(viewerId, peer);
		updateViewerCount();
		localStream.getTracks().forEach(track => connection.addTrack(track, localStream));
		connection.addEventListener('icecandidate', event => send({
			type: 'ice',
			viewerId: viewerId,
			candidate: event.candidate ? event.candidate.toJSON() : null
		}));
		connection.addEventListener('connectionstatechange', () => {
			if (connection.connectionState === 'failed') {
				closeViewer(viewerId);
			}
		});
		const offer = await connection.createOffer();
		await connection.setLocalDescription(offer);
		send({type: 'offer', viewerId: viewerId, sdp: offer.sdp});
	}

	async function acceptAnswer(viewerId, sdp) {
		const peer = peers.get(viewerId);
		if (!peer) {
			return;
		}
		await peer.connection.setRemoteDescription({type: 'answer', sdp: sdp});
		peer.remoteReady = true;
		for (const candidate of peer.pendingCandidates) {
			await peer.connection.addIceCandidate(candidate);
		}
		peer.pendingCandidates = [];
	}

	/**
	 * Answer보다 먼저 도착한 후보는 원격 설명이 정해질 때까지 모아 두었다가 넣는다.
	 */
	async function addRemoteCandidate(viewerId, candidate) {
		const peer = peers.get(viewerId);
		if (!peer || !candidate) {
			return;
		}
		if (peer.remoteReady) {
			await peer.connection.addIceCandidate(candidate);
		} else {
			peer.pendingCandidates.push(candidate);
		}
	}

	/**
	 * (⑤) 다시 연결하는 동안 놓친 입장·퇴장 알림을 서버의 시청자 목록으로 맞춘다.
	 * 목록에 없는 시청자의 영상 연결은 닫고, 아직 연결이 없는 시청자에게는 새 Offer를 보낸다. 이미 연결된 시청자는 그대로 둔다.
	 */
	async function syncViewers(viewerIds) {
		for (const viewerId of Array.from(peers.keys())) {
			if (!viewerIds.includes(viewerId)) {
				closeViewer(viewerId);
			}
		}
		for (const viewerId of viewerIds) {
			if (!peers.has(viewerId)) {
				await connectViewer(viewerId);
			}
		}
	}

	function closeViewer(viewerId) {
		const peer = peers.get(viewerId);
		if (peer) {
			peer.connection.close();
			peers.delete(viewerId);
			updateViewerCount();
		}
	}

	function updateViewerCount() {
		viewerCount.textContent = '시청자 ' + peers.size + '명';
	}

	function endBroadcast() {
		endButton.disabled = true;
		send({type: 'end'});
	}

	function finish(message) {
		finished = true;
		live = false;
		clearInterval(heartbeatTimer);
		window.removeEventListener('beforeunload', warnBeforeLeaving);
		peers.forEach(peer => peer.connection.close());
		peers.clear();
		updateViewerCount();
		if (localStream) {
			localStream.getTracks().forEach(track => track.stop());
		}
		preview.srcObject = null;
		endButton.disabled = true;
		startButton.disabled = true;
		setStatus(message);
		if (socket && socket.readyState === WebSocket.OPEN) {
			socket.close();
		}
	}

	prepareButton.addEventListener('click', prepareCamera);
	startButton.addEventListener('click', startBroadcast);
	endButton.addEventListener('click', endBroadcast);
})();
