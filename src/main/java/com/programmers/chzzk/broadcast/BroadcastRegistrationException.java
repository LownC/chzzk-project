package com.programmers.chzzk.broadcast;

public class BroadcastRegistrationException extends RuntimeException {

	private final BroadcastRegistrationError error;

	public BroadcastRegistrationException(BroadcastRegistrationError error) {
		super(error.name());
		this.error = error;
	}

	public BroadcastRegistrationException(BroadcastRegistrationError error, Throwable cause) {
		super(error.name(), cause);
		this.error = error;
	}

	public BroadcastRegistrationError getError() {
		return error;
	}

}
