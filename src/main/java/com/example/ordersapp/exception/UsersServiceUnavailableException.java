package com.example.ordersapp.exception;

public class UsersServiceUnavailableException extends RuntimeException {

    public UsersServiceUnavailableException(String message) {
        super(message);
    }

    public UsersServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
