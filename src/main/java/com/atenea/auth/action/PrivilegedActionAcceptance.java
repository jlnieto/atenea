package com.atenea.auth.action;

@FunctionalInterface
public interface PrivilegedActionAcceptance<T> {
    T accept();
}
