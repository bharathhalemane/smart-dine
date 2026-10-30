package com.example.qrorder.common.error;

public class DomainException extends RuntimeException{

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message){
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static DomainException notFound(String what){
        return new DomainException(ErrorCode.NOT_FOUND, what +" not found");
    }

    public static DomainException conflict(String message){

        return new DomainException(ErrorCode.CONFLICT, message);

    }

    public static DomainException invalidTransition(String message){
        return new DomainException(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}