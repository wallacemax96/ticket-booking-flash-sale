package com.cielo.ticketbooking.domain.exception;

public class SoldOutException extends RuntimeException {
    public SoldOutException(String message) { super(message); }
}
