package com.example.qrorder.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money implements Comparable<Money>{
    private static final BigDecimal BASIS_POINTS_DIVISOR = BigDecimal.valueOf(10_000);

    private final long minor;
    private final String currency;

    private Money(long minor, String currency){
        this.minor = minor;
        this.currency = Objects.requireNonNull(currency, "currency");
        if(currency.length() != 3){
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
    }

    public static Money ofMinor(long minor, String currency){
        return new Money(minor, currency);
    }

    public static Money zero(String currency){
        return new Money(0L, currency);
    }

    public long minor(){
        return minor;
    }

    public String currency(){
        return currency;
    }

    public Money plus(Money other){
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other){
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money times(int quantity){
        return new Money(Math.multiplyExact(minor, (long) quantity),currency);
    }

    public Money percentOf(int basisPoints){
        BigDecimal result = BigDecimal.valueOf(minor).multiply(BigDecimal.valueOf(basisPoints))
                .divide(BASIS_POINTS_DIVISOR, 0, RoundingMode.HALF_UP);
        return new Money(result.longValueExact(), currency);
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isZero() {return minor == 0;}

    private void requireSameCurrency(Money other){
        if(!currency.equals(other.currency)){
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other){
        requireSameCurrency(other);
        return Long.compare(minor,other.minor);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && m.minor == minor && m.currency.equals(currency);
    }

    @Override
    public int hashCode(){
        return Objects.hash(minor, currency);
    }

    public String toString(){return minor + " " + currency+" (minor)";}

}