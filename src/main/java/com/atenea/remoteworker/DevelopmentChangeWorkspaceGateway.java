package com.atenea.remoteworker;

public interface DevelopmentChangeWorkspaceGateway {

    DevelopmentChangeWorkspaceObservation execute(
            DevelopmentChangeWorkspaceCommand command);
}
