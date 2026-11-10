package com.example.qrorder.common.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;

/** Stack traces too: exception messages are where connection strings and addresses usually leak. */
public class RedactingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy proxy) {
        return Redactor.redact(super.throwableProxyToString(proxy));
    }
}