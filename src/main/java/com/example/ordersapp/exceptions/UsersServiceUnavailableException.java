package com.example.ordersapp.exceptions;

public class UsersServiceUnavailableException extends RuntimeException {

    public UsersServiceUnavailableException(String message) {
        super(message);
    }

    public UsersServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
