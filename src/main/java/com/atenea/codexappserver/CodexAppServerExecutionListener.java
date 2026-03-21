package com.atenea.codexappserver;

public interface CodexAppServerExecutionListener {

    CodexAppServerExecutionListener NO_OP = new CodexAppServerExecutionListener() {
    };

    default void onThreadStarted(String threadId) {
    }

    default void onTurnStarted(String threadId, String turnId) {
    }
}
